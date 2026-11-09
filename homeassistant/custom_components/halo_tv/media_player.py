"""The TV entity: the screen on or off, play and pause, and what is playing."""

from __future__ import annotations

from typing import Any

from homeassistant.components.media_player import (
    MediaPlayerEntity,
    MediaPlayerEntityFeature,
    MediaPlayerState,
)
from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant
from homeassistant.exceptions import HomeAssistantError
from homeassistant.helpers.entity_platform import AddEntitiesCallback
from homeassistant.util import dt as dt_util, slugify

from .api import HaloTvError
from .entity import HaloTvEntity

FEATURES = (
    MediaPlayerEntityFeature.TURN_ON
    | MediaPlayerEntityFeature.TURN_OFF
    | MediaPlayerEntityFeature.PLAY
    | MediaPlayerEntityFeature.PAUSE
    | MediaPlayerEntityFeature.STOP
    | MediaPlayerEntityFeature.VOLUME_SET
    | MediaPlayerEntityFeature.VOLUME_MUTE
)


async def async_setup_entry(
    hass: HomeAssistant,
    entry: ConfigEntry,
    async_add_entities: AddEntitiesCallback,
) -> None:
    """Add the TV."""
    async_add_entities([HaloTvMediaPlayer(entry.runtime_data, entry)])


class HaloTvMediaPlayer(HaloTvEntity, MediaPlayerEntity):
    """The TV.

    OFF is the screen being dark, so ``turn_off`` is "screen off" and ``turn_on`` brings it back -
    which is how a TV is expected to behave in Home Assistant. A message on screen does not change
    the state; it is reported as an attribute instead.
    """

    _attr_name = None  # named after the device: media_player.<your name>

    def __init__(self, coordinator, entry: ConfigEntry) -> None:
        super().__init__(coordinator, entry)
        # Fixed from the entry's name, so it is media_player.halo_tv as the documentation says, rather
        # than depending on whether the device registry was filled in first.
        self.entity_id = f"media_player.{slugify(entry.title)}"

    @property
    def supported_features(self) -> MediaPlayerEntityFeature:
        return FEATURES

    @property
    def _data(self) -> dict[str, Any]:
        return self.coordinator.data or {}

    @property
    def _media(self) -> dict[str, Any]:
        media = self._data.get("media")
        return media if isinstance(media, dict) else {}

    @property
    def state(self) -> MediaPlayerState:
        if self._data.get("screen") != "on":
            return MediaPlayerState.OFF
        if self._media.get("playing"):
            return MediaPlayerState.PLAYING
        if self._media.get("paused"):
            return MediaPlayerState.PAUSED
        return MediaPlayerState.IDLE

    @property
    def media_title(self) -> str | None:
        return self._media.get("title") or None

    @property
    def media_artist(self) -> str | None:
        return self._media.get("artist") or None

    @property
    def app_name(self) -> str | None:
        return self._media.get("app_label") or None

    @property
    def entity_picture(self) -> str | None:
        return self._media.get("artwork") or None

    @property
    def media_duration(self) -> int | None:
        duration = self._media.get("duration")
        return int(duration) if duration else None

    @property
    def media_position(self) -> int | None:
        position = self._media.get("position")
        return int(position) if position else None

    @property
    def media_position_updated_at(self):
        return dt_util.utcnow() if self.state == MediaPlayerState.PLAYING else None

    @property
    def volume_level(self) -> float | None:
        level = self._data.get("volume_level")
        return float(level) if level is not None else None

    @property
    def is_volume_muted(self) -> bool:
        return bool(self._data.get("muted"))

    @property
    def extra_state_attributes(self) -> dict[str, Any]:
        message = self._data.get("message") or {}
        return {
            "screen": self._data.get("screen"),
            "screen_mode": self._data.get("screen_mode"),
            "message_showing": bool(message.get("showing")),
            "message_text": message.get("text") or None,
            "playing_app": self._media.get("app_label") or None,
            "ambient_open": self._data.get("ambient"),
            # Two optional ADB grants from install.sh; false simply means less detail, not a fault.
            "overlay_allowed": self._data.get("overlay"),
        }

    async def _async_command(self, action: str, **params: Any) -> None:
        try:
            await self.coordinator.async_command(action, **params)
        except HaloTvError as err:
            raise HomeAssistantError(f"Halo TV did not take '{action}': {err}") from err

    async def async_turn_on(self) -> None:
        await self._async_command("screen_on")

    async def async_turn_off(self) -> None:
        await self._async_command("screen_off")

    async def async_media_play(self) -> None:
        await self._async_command("play")

    async def async_media_pause(self) -> None:
        await self._async_command("pause")

    async def async_media_stop(self) -> None:
        await self._async_command("stop")

    async def async_set_volume_level(self, volume: float) -> None:
        await self._async_command("volume_set", level=round(volume, 3))

    async def async_mute_volume(self, mute: bool) -> None:
        await self._async_command("mute" if mute else "unmute")
