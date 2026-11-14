"""``number.<tv>_*``: the viewing-time thresholds, and the volume.

Every value here is the TV's own setting: writing one changes what the set does by itself, which is
what makes a one-line automation ("at 21:00, set today's limit to 30") enough.
"""

from __future__ import annotations

from dataclasses import dataclass
from homeassistant.components.number import (
    NumberDeviceClass,
    NumberEntity,
    NumberEntityDescription,
    NumberMode,
)
from homeassistant.config_entries import ConfigEntry
from homeassistant.const import PERCENTAGE, UnitOfTime
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from .const import VIEWING_NUMBERS
from .entity import HaloTvEntity

VIEWING = "viewing"
VOLUME = "volume"


@dataclass(frozen=True, kw_only=True)
class HaloTvNumberDescription(NumberEntityDescription):
    """One number: where it lives, and the key it writes."""

    kind: str = VIEWING
    option: str = ""


NUMBERS: tuple[HaloTvNumberDescription, ...] = (
    HaloTvNumberDescription(
        key="daily_limit_min",
        name="Daily limit",
        icon="mdi:timer-sand",
        option="daily_limit_min",
        native_unit_of_measurement=UnitOfTime.MINUTES,
        device_class=NumberDeviceClass.DURATION,
        mode=NumberMode.BOX,
        native_min_value=1,
        native_max_value=1440,
        native_step=1,
    ),
    HaloTvNumberDescription(
        key="break_interval_min",
        name="Eye-rest every",
        icon="mdi:eye-outline",
        option="break_interval_min",
        native_unit_of_measurement=UnitOfTime.MINUTES,
        device_class=NumberDeviceClass.DURATION,
        mode=NumberMode.BOX,
        native_min_value=1,
        native_max_value=240,
        native_step=1,
    ),
    HaloTvNumberDescription(
        key="break_minutes",
        name="Break length",
        icon="mdi:sofa-outline",
        option="break_minutes",
        native_unit_of_measurement=UnitOfTime.MINUTES,
        device_class=NumberDeviceClass.DURATION,
        mode=NumberMode.BOX,
        native_min_value=0,
        native_max_value=120,
        native_step=1,
    ),
    HaloTvNumberDescription(
        key="warn_before_min",
        name="Warn before limit",
        icon="mdi:bell-ring-outline",
        option="warn_before_min",
        native_unit_of_measurement=UnitOfTime.MINUTES,
        device_class=NumberDeviceClass.DURATION,
        mode=NumberMode.BOX,
        native_min_value=0,
        native_max_value=120,
        native_step=1,
    ),
    HaloTvNumberDescription(
        key="message_seconds",
        name="Reminder seconds",
        icon="mdi:message-text-clock-outline",
        option="message_seconds",
        native_unit_of_measurement=UnitOfTime.SECONDS,
        device_class=NumberDeviceClass.DURATION,
        mode=NumberMode.BOX,
        native_min_value=5,
        native_max_value=600,
        native_step=1,
    ),
    HaloTvNumberDescription(
        key="shutdown_delay_s",
        name="Shutdown delay",
        icon="mdi:timer-outline",
        option="shutdown_delay_s",
        native_unit_of_measurement=UnitOfTime.SECONDS,
        device_class=NumberDeviceClass.DURATION,
        mode=NumberMode.BOX,
        native_min_value=0,
        native_max_value=300,
        native_step=1,
    ),
    HaloTvNumberDescription(
        key="volume",
        name="Volume",
        icon="mdi:volume-high",
        kind=VOLUME,
        native_unit_of_measurement=PERCENTAGE,
        mode=NumberMode.SLIDER,
        native_min_value=0,
        native_max_value=100,
        native_step=1,
    ),
)


async def async_setup_entry(
    hass: HomeAssistant,
    entry: ConfigEntry,
    async_add_entities: AddEntitiesCallback,
) -> None:
    """Add the numbers."""
    coordinator = entry.runtime_data
    async_add_entities(
        HaloTvNumber(coordinator, entry, description) for description in NUMBERS
    )


class HaloTvNumber(HaloTvEntity, NumberEntity):
    """One threshold, or the volume."""

    entity_description: HaloTvNumberDescription

    def __init__(self, coordinator, entry: ConfigEntry, description: HaloTvNumberDescription) -> None:
        super().__init__(coordinator, entry)
        self.entity_description = description
        self._attr_unique_id = f"{entry.entry_id}_{description.key}"
        # The TV clamps these itself; showing the same range keeps the slider honest.
        low, high = VIEWING_NUMBERS.get(description.option, (None, None))
        if description.kind == VIEWING and low is not None:
            self._attr_native_min_value = low
            self._attr_native_max_value = high

    @property
    def native_value(self) -> float | None:
        if self.entity_description.kind == VOLUME:
            volume = self.state_data.get("volume")
            return float(volume) if volume is not None else None
        value = self.viewing_config.get(self.entity_description.option)
        return float(value) if value is not None else None

    async def async_set_native_value(self, value: float) -> None:
        if self.entity_description.kind == VOLUME:
            await self.async_send("volume_set", volume=int(round(value)))
            return
        await self.coordinator.async_set_viewing(
            **{self.entity_description.option: int(round(value))}
        )
