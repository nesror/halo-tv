"""Polling one Halo TV, every few seconds."""

from __future__ import annotations

import logging
from datetime import timedelta
from typing import Any

from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant
from homeassistant.helpers.update_coordinator import DataUpdateCoordinator, UpdateFailed

from .api import HaloTvClient, HaloTvError
from .const import DEFAULT_SCAN_INTERVAL, DOMAIN

_LOGGER = logging.getLogger(__name__)


class HaloTvCoordinator(DataUpdateCoordinator[dict[str, Any]]):
    """Keeps the TV's last answer, so entities never block on the network."""

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

    async def _async_update_data(self) -> dict[str, Any]:
        try:
            return await self.client.async_get_state()
        except HaloTvError as err:
            raise UpdateFailed(str(err)) from err

    async def async_command(self, action: str, **params: Any) -> dict[str, Any]:
        """Send a command and use the state it returns, so the UI reacts at once instead of on the next poll."""
        result = await self.client.async_command(action, **params)
        state = result.get("state")
        if isinstance(state, dict):
            self.async_set_updated_data(state)
        return result

    async def async_notify(self, payload: dict[str, Any]) -> dict[str, Any]:
        """Show a message on the TV."""
        result = await self.client.async_notify(payload)
        self.hass.async_create_task(self.async_request_refresh())
        return result
