"""One entity on the TV's device page."""

from __future__ import annotations

from typing import Any

from homeassistant.config_entries import ConfigEntry
from homeassistant.helpers.device_registry import DeviceInfo
from homeassistant.helpers.update_coordinator import CoordinatorEntity

from .const import DOMAIN
from .coordinator import HaloTvCoordinator


class HaloTvEntity(CoordinatorEntity[HaloTvCoordinator]):
    """Shared plumbing: the device a TV's entities belong to, and its last known state."""

    _attr_has_entity_name = True

    def __init__(self, coordinator: HaloTvCoordinator, entry: ConfigEntry) -> None:
        super().__init__(coordinator)
        self._entry = entry
        info = coordinator.info
        self._attr_device_info = DeviceInfo(
            identifiers={(DOMAIN, entry.entry_id)},
            manufacturer=info.get("manufacturer") or "Halo TV",
            model=info.get("model") or "Android TV",
            name=entry.title,
            sw_version=info.get("version"),
            configuration_url=coordinator.client.base_url,
        )

    # ---- The three answers the TV gives, each in the shape the platforms want.

    @property
    def state_data(self) -> dict[str, Any]:
        """Screen, media, volume, message: what changes every few seconds."""
        data = self.coordinator.data
        return data if isinstance(data, dict) else {}

    @property
    def media_data(self) -> dict[str, Any]:
        """What is playing, or an empty dict when nothing is."""
        media = self.state_data.get("media")
        return media if isinstance(media, dict) else {}

    @property
    def message_data(self) -> dict[str, Any]:
        """The message on screen right now, or an empty dict when there is none."""
        message = self.state_data.get("message")
        return message if isinstance(message, dict) else {}

    @property
    def viewing_config(self) -> dict[str, Any]:
        """The viewing-time thresholds as the TV holds them."""
        return self.coordinator.viewing_config

    @property
    def viewing_today(self) -> dict[str, Any]:
        """Today's count: watched, remaining, breaks, flags."""
        return self.coordinator.viewing_today

    @property
    def viewing_brief(self) -> dict[str, Any]:
        """The short clock carried inside the state poll."""
        brief = self.state_data.get("viewing")
        return brief if isinstance(brief, dict) else {}

    @property
    def screen_on(self) -> bool:
        return self.state_data.get("screen") == "on"

    # ---- Writing.

    async def async_send(self, action: str, **params: Any) -> None:
        """One command, with the error already turned into something Home Assistant can show."""
        await self.coordinator.async_command(action, **params)

    async def async_press_key(self, key: str) -> None:
        """One press of the set's own remote. Named apart from ButtonEntity.async_press."""
        await self.coordinator.async_key(key)
