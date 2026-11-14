"""``remote.<tv>``: every button of the set's own remote, in one entity.

Each name is a press of the real remote, so the on-screen selection moves exactly as it would with the
plastic one. This Home Assistant version has no "select an activity" call on ``remote`` - the app list
rides along as an attribute, and ``media_player.select_source`` is what opens one.
"""

from __future__ import annotations

from collections.abc import Iterable
from typing import Any

from homeassistant.components.remote import RemoteEntity
from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from .const import KEYS
from .entity import HaloTvEntity

#: The presses worth documenting: the remote takes any name from const.KEYS, plus the commands in
#: const.ACTIONS, so "screen_off" or "dismiss" work here too.
KEY_NAMES = (
    "up",
    "down",
    "left",
    "right",
    "ok",
    "back",
    "home",
    "menu",
    "info",
    "recents",
    "play_pause",
    "volume_up",
    "volume_down",
    "power",
)


async def async_setup_entry(
    hass: HomeAssistant,
    entry: ConfigEntry,
    async_add_entities: AddEntitiesCallback,
) -> None:
    """Add the remote."""
    async_add_entities([HaloTvRemote(entry.runtime_data, entry)])


class HaloTvRemote(HaloTvEntity, RemoteEntity):
    """One press at a time, from a script or a dashboard."""

    _attr_name = "Remote"

    @property
    def is_on(self) -> bool:
        return self.screen_on

    async def async_turn_on(self, **kwargs: Any) -> None:
        await self.async_send("screen_on")

    async def async_turn_off(self, **kwargs: Any) -> None:
        await self.async_send("screen_off")

    async def async_send_command(self, command: Iterable[str], **kwargs: Any) -> None:
        """Each name: a remote key when the TV has such a button, otherwise a command."""
        for name in command:
            key = str(name).strip().lower().replace("-", "_").replace(" ", "_")
            if not key:
                continue
            if key in KEYS:
                await self.async_press_key(key)
            else:
                await self.async_send(key)

    @property
    def extra_state_attributes(self) -> dict[str, Any]:
        return {
            "keys": list(KEY_NAMES),
            "activities": self.coordinator.media_sources,
            "current_activity": self.coordinator.current_source,
        }
