"""One entity on the TV's device page."""

from __future__ import annotations

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
