"""``button.<tv>_*``: the remote's buttons, and the one-off actions, as things a dashboard can press.

A press here is the same thing the phone remote does: one key through the set's own remote when the TV
has such a button (``remote`` entity is the shortcut for the whole pad), otherwise one command from
``halo_tv.command``. Buttons are what make a dashboard usable on a wall tablet, where a text input is
not.
"""

from __future__ import annotations

from dataclasses import dataclass
from homeassistant.components.button import ButtonEntity, ButtonEntityDescription
from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from .entity import HaloTvEntity

#: What a button does: a remote key, or a command.
KEY = "key"
COMMAND = "command"


@dataclass(frozen=True, kw_only=True)
class HaloTvButtonDescription(ButtonEntityDescription):
    """One button: its name, its picture, and which of the two paths it takes."""

    kind: str = KEY
    value: str = ""


BUTTONS: tuple[HaloTvButtonDescription, ...] = (
    # ---- The pad.
    HaloTvButtonDescription(key="up", name="Up", icon="mdi:arrow-up-bold", value="up"),
    HaloTvButtonDescription(key="down", name="Down", icon="mdi:arrow-down-bold", value="down"),
    HaloTvButtonDescription(key="left", name="Left", icon="mdi:arrow-left-bold", value="left"),
    HaloTvButtonDescription(key="right", name="Right", icon="mdi:arrow-right-bold", value="right"),
    HaloTvButtonDescription(key="ok", name="OK", icon="mdi:circle-double", value="ok"),
    HaloTvButtonDescription(key="back", name="Back", icon="mdi:arrow-u-left-top", value="back"),
    HaloTvButtonDescription(key="home", name="Home", icon="mdi:home", value="home"),
    HaloTvButtonDescription(key="menu", name="Menu", icon="mdi:menu", value="menu"),
    HaloTvButtonDescription(key="info", name="Info", icon="mdi:information-outline", value="info"),
    HaloTvButtonDescription(key="recents", name="Recent apps", icon="mdi:history", value="recents"),
    # ---- Sound and picture.
    HaloTvButtonDescription(
        key="play_pause", name="Play or pause", icon="mdi:play-pause", value="play_pause"
    ),
    HaloTvButtonDescription(
        key="volume_up", name="Volume up", icon="mdi:volume-plus", value="volume_up"
    ),
    HaloTvButtonDescription(
        key="volume_down", name="Volume down", icon="mdi:volume-minus", value="volume_down"
    ),
    HaloTvButtonDescription(
        key="screen_off", name="Screen off", icon="mdi:monitor-off", kind=COMMAND, value="screen_off"
    ),
    HaloTvButtonDescription(
        key="screen_on", name="Screen on", icon="mdi:monitor", kind=COMMAND, value="screen_on"
    ),
    # ---- One-way: the set drops off the network, so there is no button back.
    HaloTvButtonDescription(
        key="standby", name="Standby", icon="mdi:power", value="power"
    ),
    # ---- Messages and the viewing-time clock.
    HaloTvButtonDescription(
        key="dismiss_message",
        name="Dismiss message",
        icon="mdi:close-box-outline",
        kind=COMMAND,
        value="dismiss",
    ),
    HaloTvButtonDescription(
        key="stop_speaking", name="Stop speaking", icon="mdi:volume-off", kind=COMMAND, value="stop_speech"
    ),
    HaloTvButtonDescription(
        key="viewing_reset",
        name="Reset viewing time",
        icon="mdi:restart",
        kind=COMMAND,
        value="viewing_reset",
    ),
)


async def async_setup_entry(
    hass: HomeAssistant,
    entry: ConfigEntry,
    async_add_entities: AddEntitiesCallback,
) -> None:
    """Add the buttons."""
    coordinator = entry.runtime_data
    async_add_entities(
        HaloTvButton(coordinator, entry, description) for description in BUTTONS
    )


class HaloTvButton(HaloTvEntity, ButtonEntity):
    """One press."""

    entity_description: HaloTvButtonDescription

    def __init__(self, coordinator, entry: ConfigEntry, description: HaloTvButtonDescription) -> None:
        super().__init__(coordinator, entry)
        self.entity_description = description
        self._attr_unique_id = f"{entry.entry_id}_{description.key}"

    async def async_press(self) -> None:
        if self.entity_description.kind == KEY:
            await self.async_press_key(self.entity_description.value)
        else:
            await self.async_send(self.entity_description.value)
