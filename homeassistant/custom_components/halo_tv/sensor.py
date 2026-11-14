"""``sensor.<tv>_*``: what the TV is doing, and how much of today's allowance is left.

Two kinds live here. The viewing-time sensors are the ones a parent actually looks at - watched,
remaining, when the next eye-rest reminder lands - and the rest describe the picture and the app.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any

from homeassistant.components.sensor import (
    SensorDeviceClass,
    SensorEntity,
    SensorEntityDescription,
    SensorStateClass,
)
from homeassistant.config_entries import ConfigEntry
from homeassistant.const import PERCENTAGE, EntityCategory, UnitOfTime
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity_platform import AddEntitiesCallback
from homeassistant.util import dt as dt_util

from .entity import HaloTvEntity

STATE = "state"
TODAY = "today"
CONFIG = "config"
MEDIA = "media"
MESSAGE = "message"
INFO = "info"


@dataclass(frozen=True, kw_only=True)
class HaloTvSensorDescription(SensorEntityDescription):
    """One sensor: which answer it reads, and which field of it."""

    source: str = TODAY
    field: str = ""


SENSORS: tuple[HaloTvSensorDescription, ...] = (
    # ---- Today, as the TV counts it.
    HaloTvSensorDescription(
        key="watched_today",
        name="Watched today",
        icon="mdi:television-play",
        field="watched_min",
        native_unit_of_measurement=UnitOfTime.MINUTES,
        device_class=SensorDeviceClass.DURATION,
        state_class=SensorStateClass.MEASUREMENT,
    ),
    HaloTvSensorDescription(
        key="remaining_today",
        name="Remaining today",
        icon="mdi:timelapse",
        field="remaining_min",
        native_unit_of_measurement=UnitOfTime.MINUTES,
        device_class=SensorDeviceClass.DURATION,
        state_class=SensorStateClass.MEASUREMENT,
    ),
    HaloTvSensorDescription(
        key="watched_text",
        name="Watched text",
        icon="mdi:clock-outline",
        field="watched_text",
    ),
    HaloTvSensorDescription(
        key="next_break_in",
        name="Next break in",
        icon="mdi:eye-check-outline",
        field="next_break_in_min",
        native_unit_of_measurement=UnitOfTime.MINUTES,
        device_class=SensorDeviceClass.DURATION,
        state_class=SensorStateClass.MEASUREMENT,
    ),
    HaloTvSensorDescription(
        key="break_left",
        name="Break left",
        icon="mdi:sofa-outline",
        field="rest_left_min",
        native_unit_of_measurement=UnitOfTime.MINUTES,
        device_class=SensorDeviceClass.DURATION,
        state_class=SensorStateClass.MEASUREMENT,
    ),
    HaloTvSensorDescription(
        key="breaks_today",
        name="Breaks today",
        icon="mdi:counter",
        field="breaks",
        state_class=SensorStateClass.MEASUREMENT,
    ),
    HaloTvSensorDescription(
        key="viewing_reason",
        name="Counting because",
        icon="mdi:help-circle-outline",
        field="reason",
    ),
    # ---- The picture and the app.
    HaloTvSensorDescription(
        key="screen",
        name="Screen",
        icon="mdi:monitor-dashboard",
        source=STATE,
        field="screen",
    ),
    HaloTvSensorDescription(
        key="volume",
        name="Volume level",
        icon="mdi:volume-high",
        source=STATE,
        field="volume",
        native_unit_of_measurement=PERCENTAGE,
        state_class=SensorStateClass.MEASUREMENT,
    ),
    HaloTvSensorDescription(
        key="current_app",
        name="App in front",
        icon="mdi:apps",
        source=MEDIA,
        field="app_label",
    ),
    HaloTvSensorDescription(
        key="now_playing",
        name="Now playing",
        icon="mdi:music-note",
        source=MEDIA,
        field="title",
    ),
    HaloTvSensorDescription(
        key="message",
        name="Message on screen",
        icon="mdi:message-badge-outline",
        source=MESSAGE,
        field="text",
    ),
    # ---- Odds and ends worth having in a template.
    HaloTvSensorDescription(
        key="last_seen",
        name="Last answer",
        icon="mdi:clock-check-outline",
        source=STATE,
        field="ts",
        device_class=SensorDeviceClass.TIMESTAMP,
        entity_category=EntityCategory.DIAGNOSTIC,
    ),
    HaloTvSensorDescription(
        key="launcher_version",
        name="Version",
        icon="mdi:information-outline",
        source=INFO,
        field="version",
        entity_category=EntityCategory.DIAGNOSTIC,
    ),
)


async def async_setup_entry(
    hass: HomeAssistant,
    entry: ConfigEntry,
    async_add_entities: AddEntitiesCallback,
) -> None:
    """Add the sensors."""
    coordinator = entry.runtime_data
    async_add_entities(
        HaloTvSensor(coordinator, entry, description) for description in SENSORS
    )


class HaloTvSensor(HaloTvEntity, SensorEntity):
    """One number, word or timestamp out of the TV's answer."""

    entity_description: HaloTvSensorDescription

    def __init__(self, coordinator, entry: ConfigEntry, description: HaloTvSensorDescription) -> None:
        super().__init__(coordinator, entry)
        self.entity_description = description
        self._attr_unique_id = f"{entry.entry_id}_{description.key}"

    def _bucket(self) -> dict[str, Any]:
        source = self.entity_description.source
        if source == TODAY:
            return self.viewing_today
        if source == CONFIG:
            return self.viewing_config
        if source == MEDIA:
            return self.media_data
        if source == MESSAGE:
            return self.message_data
        if source == INFO:
            return self.coordinator.info
        return self.state_data

    @property
    def native_value(self) -> Any:
        value = self._bucket().get(self.entity_description.field)
        if value is None:
            return None
        if self.entity_description.device_class == SensorDeviceClass.TIMESTAMP:
            try:
                return dt_util.utc_from_timestamp(float(value) / 1000)
            except (TypeError, ValueError):
                return None
        if isinstance(value, bool):
            return "on" if value else "off"
        return value
