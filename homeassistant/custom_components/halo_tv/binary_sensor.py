"""``binary_sensor.<tv>_*``: the yes/no facts worth triggering on."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any

from homeassistant.components.binary_sensor import (
    BinarySensorDeviceClass,
    BinarySensorEntity,
    BinarySensorEntityDescription,
)
from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from .entity import HaloTvEntity

STATE = "state"
TODAY = "today"
MEDIA = "media"
MESSAGE = "message"


@dataclass(frozen=True, kw_only=True)
class HaloTvBinarySensorDescription(BinarySensorEntityDescription):
    """One yes/no: which answer it reads, and which field."""

    source: str = STATE
    field: str = ""


BINARY_SENSORS: tuple[HaloTvBinarySensorDescription, ...] = (
    HaloTvBinarySensorDescription(
        key="screen_on",
        name="Screen on",
        icon="mdi:monitor",
        device_class=BinarySensorDeviceClass.POWER,
        field="screen_on",
    ),
    HaloTvBinarySensorDescription(
        key="playing",
        name="Playing",
        icon="mdi:play-circle-outline",
        device_class=BinarySensorDeviceClass.RUNNING,
        source=MEDIA,
        field="playing",
    ),
    HaloTvBinarySensorDescription(
        key="message_showing",
        name="Message showing",
        icon="mdi:message-badge",
        source=MESSAGE,
        field="showing",
    ),
    HaloTvBinarySensorDescription(
        key="viewing_counting",
        name="Counting viewing time",
        icon="mdi:av-timer",
        source=TODAY,
        field="counting",
    ),
    HaloTvBinarySensorDescription(
        key="limit_reached",
        name="Daily limit reached",
        icon="mdi:alert-octagon-outline",
        source=TODAY,
        field="shutdown",
    ),
    HaloTvBinarySensorDescription(
        key="on_a_break",
        name="On a break",
        icon="mdi:sofa",
        source=TODAY,
        field="rest_left_min",
    ),
)


async def async_setup_entry(
    hass: HomeAssistant,
    entry: ConfigEntry,
    async_add_entities: AddEntitiesCallback,
) -> None:
    """Add the binary sensors."""
    coordinator = entry.runtime_data
    async_add_entities(
        HaloTvBinarySensor(coordinator, entry, description)
        for description in BINARY_SENSORS
    )


class HaloTvBinarySensor(HaloTvEntity, BinarySensorEntity):
    """One yes/no, read from whichever answer carries it."""

    entity_description: HaloTvBinarySensorDescription

    def __init__(self, coordinator, entry: ConfigEntry, description: HaloTvBinarySensorDescription) -> None:
        super().__init__(coordinator, entry)
        self.entity_description = description
        self._attr_unique_id = f"{entry.entry_id}_{description.key}"

    def _bucket(self) -> dict[str, Any]:
        source = self.entity_description.source
        if source == TODAY:
            return self.viewing_today
        if source == MEDIA:
            return self.media_data
        if source == MESSAGE:
            return self.message_data
        return self.state_data

    @property
    def is_on(self) -> bool:
        value = self._bucket().get(self.entity_description.field)
        if self.entity_description.field == "screen_on":
            return self.screen_on
        if self.entity_description.field == "rest_left_min":
            # A break is on while there is still time left on it.
            return bool(value and float(value) > 0)
        return bool(value)
