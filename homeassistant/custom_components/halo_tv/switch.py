"""``switch.<tv>_*``: the screen, the mute, and the viewing-time rules as on/off.

The viewing-time switches are the TV's own settings (``/api/tvtime/config``), not a copy: turning one
here changes what the set does on its own, with no automation needed on the Home Assistant side.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any

from homeassistant.components.switch import SwitchEntity, SwitchEntityDescription
from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from .entity import HaloTvEntity

SCREEN = "screen"
MUTED = "muted"
VIEWING = "viewing"


@dataclass(frozen=True, kw_only=True)
class HaloTvSwitchDescription(SwitchEntityDescription):
    """One switch: which layer it reads and writes."""

    kind: str = VIEWING
    option: str = ""


SWITCHES: tuple[HaloTvSwitchDescription, ...] = (
    HaloTvSwitchDescription(
        key="screen",
        name="Screen",
        icon="mdi:monitor",
        kind=SCREEN,
    ),
    HaloTvSwitchDescription(
        key="muted",
        name="Muted",
        icon="mdi:volume-mute",
        kind=MUTED,
    ),
    HaloTvSwitchDescription(
        key="viewing_enabled",
        name="Viewing time limits",
        icon="mdi:clock-alert-outline",
        option="enabled",
    ),
    HaloTvSwitchDescription(
        key="viewing_auto_shutdown",
        name="Go dark at the daily limit",
        icon="mdi:power-sleep",
        option="auto_shutdown",
    ),
    HaloTvSwitchDescription(
        key="viewing_tts",
        name="Read reminders out loud",
        icon="mdi:bullhorn-outline",
        option="tts",
    ),
    HaloTvSwitchDescription(
        key="viewing_pause_on_break",
        name="Pause on a break",
        icon="mdi:pause-circle-outline",
        option="pause_on_break",
    ),
    HaloTvSwitchDescription(
        key="viewing_pause_during_break",
        name="Pause during a break",
        icon="mdi:hand-back-right-outline",
        option="pause_during_break",
    ),
)


async def async_setup_entry(
    hass: HomeAssistant,
    entry: ConfigEntry,
    async_add_entities: AddEntitiesCallback,
) -> None:
    """Add the switches."""
    coordinator = entry.runtime_data
    async_add_entities(
        HaloTvSwitch(coordinator, entry, description) for description in SWITCHES
    )


class HaloTvSwitch(HaloTvEntity, SwitchEntity):
    """One on/off, wherever it actually lives."""

    entity_description: HaloTvSwitchDescription

    def __init__(self, coordinator, entry: ConfigEntry, description: HaloTvSwitchDescription) -> None:
        super().__init__(coordinator, entry)
        self.entity_description = description
        self._attr_unique_id = f"{entry.entry_id}_{description.key}"

    @property
    def is_on(self) -> bool:
        kind = self.entity_description.kind
        if kind == SCREEN:
            return self.screen_on
        if kind == MUTED:
            return bool(self.state_data.get("muted"))
        return bool(self.viewing_config.get(self.entity_description.option))

    async def async_turn_on(self, **kwargs: Any) -> None:
        await self._async_set(True)

    async def async_turn_off(self, **kwargs: Any) -> None:
        await self._async_set(False)

    async def _async_set(self, on: bool) -> None:
        kind = self.entity_description.kind
        if kind == SCREEN:
            await self.async_send("screen_on" if on else "screen_off")
        elif kind == MUTED:
            await self.async_send("mute" if on else "unmute")
        else:
            await self.coordinator.async_set_viewing(**{self.entity_description.option: on})
