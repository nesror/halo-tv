"""Polling one Halo TV, every few seconds."""

from __future__ import annotations

import logging
from datetime import timedelta
from typing import Any

from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant
from homeassistant.exceptions import HomeAssistantError
from homeassistant.helpers.update_coordinator import DataUpdateCoordinator, UpdateFailed

from .api import HaloTvClient, HaloTvError
from .const import (
    APPS_EVERY,
    DEFAULT_SCAN_INTERVAL,
    DOMAIN,
    VIEWING_EVERY,
)

_LOGGER = logging.getLogger(__name__)


class HaloTvCoordinator(DataUpdateCoordinator[dict[str, Any]]):
    """Keeps the TV's last answer, so entities never block on the network.

    ``data`` is the fast-moving state (screen, media, volume, message). The two slow answers - the
    viewing-time clock and the list of apps - are held beside it and refreshed on a longer beat, or
    on demand after a write, so a switch still reacts the moment it is flipped.
    """

    config_entry: ConfigEntry

    def __init__(
        self,
        hass: HomeAssistant,
        entry: ConfigEntry,
        client: HaloTvClient,
        info: dict[str, Any],
        scan_interval: int = DEFAULT_SCAN_INTERVAL,
    ) -> None:
        super().__init__(
            hass,
            _LOGGER,
            config_entry=entry,
            name=f"{DOMAIN} {entry.title}",
            update_interval=timedelta(seconds=scan_interval),
        )
        self.client = client
        self.info = info
        self.viewing: dict[str, Any] = {}
        self.apps: list[dict[str, Any]] = []
        #: What the two text entities hold: the heading and the body, sent when the body is set.
        self.message_draft: dict[str, str] = {}
        self._polls = 0

    @property
    def viewing_config(self) -> dict[str, Any]:
        """The thresholds as the TV holds them, empty when nothing has been read yet."""
        config = self.viewing.get("config")
        return config if isinstance(config, dict) else {}

    @property
    def viewing_today(self) -> dict[str, Any]:
        """Today's count and what it means: watched, remaining, breaks, flags."""
        today = self.viewing.get("today")
        return today if isinstance(today, dict) else {}

    # ---- Apps, named once here so the TV entity and the remote agree on them.

    def source_map(self) -> dict[str, str]:
        """App name to package, disambiguating the rare two apps that share a label."""
        seen: dict[str, list[str]] = {}
        for app in self.apps:
            package = app.get("package")
            label = (app.get("label") or package or "").strip()
            if not package or not label:
                continue
            packages = seen.setdefault(label, [])
            if package not in packages:
                packages.append(package)

        sources: dict[str, str] = {}
        for label, packages in seen.items():
            if len(packages) == 1:
                sources[label] = packages[0]
            else:
                for package in packages:
                    sources[f"{label} ({package})"] = package
        return sources

    @property
    def media_sources(self) -> list[str]:
        """Every app the TV can open, by name."""
        return sorted(self.source_map())

    def package_for(self, source: str) -> str | None:
        """The package behind an app name, or None when the list has moved on."""
        return self.source_map().get(source)

    @property
    def current_source(self) -> str | None:
        """The app in front, as it is named in ``media_sources``."""
        data = self.data if isinstance(self.data, dict) else {}
        media = data.get("media") if isinstance(data.get("media"), dict) else {}
        package = media.get("app")
        if package:
            for name, pkg in self.source_map().items():
                if pkg == package:
                    return name
        return media.get("app_label") or None

    async def _async_update_data(self) -> dict[str, Any]:
        try:
            state = await self.client.async_get_state()
        except HaloTvError as err:
            raise UpdateFailed(str(err)) from err

        self._polls += 1
        if not self.viewing or self._polls % VIEWING_EVERY == 0:
            try:
                self.viewing = await self.client.async_get_viewing()
            except HaloTvError as err:
                # The state poll succeeded, so the TV is there; a stale clock is better than no entity.
                _LOGGER.debug("viewing time stayed as it was: %s", err)
        if not self.apps or self._polls % APPS_EVERY == 0:
            try:
                self.apps = await self.client.async_get_apps()
            except HaloTvError as err:
                _LOGGER.debug("app list stayed as it was: %s", err)
        return state

    async def async_command(self, action: str, **params: Any) -> dict[str, Any]:
        """Send a command and use the state it returns, so the UI reacts at once instead of on the next poll."""
        try:
            result = await self.client.async_command(action, **params)
        except HaloTvError as err:
            raise HomeAssistantError(f"Halo TV did not take '{action}': {err}") from err
        state = result.get("state")
        if isinstance(state, dict):
            self.async_set_updated_data(state)
        return result

    async def async_key(self, key: str) -> dict[str, Any]:
        """Press one button of the set's own remote."""
        try:
            result = await self.client.async_key(key)
        except HaloTvError as err:
            raise HomeAssistantError(f"Halo TV did not take key '{key}': {err}") from err
        if not result.get("ok"):
            reason = result.get("reason") or "the TV refused it"
            raise HomeAssistantError(f"Halo TV did not take key '{key}': {reason}")
        state = result.get("state")
        if isinstance(state, dict):
            self.async_set_updated_data(state)
        return result

    async def async_launch(self, package: str) -> None:
        """Open an app by package name."""
        try:
            result = await self.client.async_launch(package)
        except HaloTvError as err:
            raise HomeAssistantError(f"Halo TV could not open {package}: {err}") from err
        if not result.get("ok"):
            raise HomeAssistantError(f"Halo TV could not open {package}")
        await self.async_request_refresh()

    async def async_notify(self, payload: dict[str, Any]) -> dict[str, Any]:
        """Show a message on the TV."""
        try:
            result = await self.client.async_notify(payload)
        except HaloTvError as err:
            raise HomeAssistantError(f"Halo TV did not show the message: {err}") from err
        self.hass.async_create_task(self.async_request_refresh())
        return result

    # ---- The viewing-time clock.

    async def async_set_viewing(self, **config: Any) -> None:
        """Change one or more thresholds, then keep the answer instead of waiting for the next poll."""
        try:
            result = await self.client.async_set_viewing(**config)
        except HaloTvError as err:
            raise HomeAssistantError(f"Halo TV did not take {config}: {err}") from err
        status = result.get("status")
        if isinstance(status, dict):
            self.viewing = status
            self.async_update_listeners()

    async def async_reset_viewing(self) -> None:
        """Start today's count again."""
        try:
            result = await self.client.async_reset_viewing()
        except HaloTvError as err:
            raise HomeAssistantError(f"Halo TV did not reset today's count: {err}") from err
        status = result.get("status")
        if isinstance(status, dict):
            self.viewing = status
            self.async_update_listeners()

    async def async_adjust_viewing(self, minutes: float) -> None:
        """Hand out (or take back) minutes of today's allowance."""
        try:
            result = await self.client.async_adjust_viewing(minutes)
        except HaloTvError as err:
            raise HomeAssistantError(f"Halo TV did not adjust today's count: {err}") from err
        status = result.get("status")
        if isinstance(status, dict):
            self.viewing = status
            self.async_update_listeners()
