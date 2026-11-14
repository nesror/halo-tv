"""The TV: the screen on or off, play and pause, volume, and the app in front."""

from __future__ import annotations

from typing import Any

from homeassistant.components.media_player import (
    MediaPlayerDeviceClass,
    MediaPlayerEntity,
    MediaPlayerEntityFeature,
    MediaPlayerState,
)
from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity_platform import AddEntitiesCallback
from homeassistant.util import dt as dt_util, slugify

from .entity import HaloTvEntity

FEATURES = (
    MediaPlayerEntityFeature.TURN_ON
    | MediaPlayerEntityFeature.TURN_OFF
    | MediaPlayerEntityFeature.PLAY
    | MediaPlayerEntityFeature.PAUSE
    | MediaPlayerEntityFeature.STOP
    | MediaPlayerEntityFeature.NEXT_TRACK
    | MediaPlayerEntityFeature.PREVIOUS_TRACK
    | MediaPlayerEntityFeature.VOLUME_SET
    | MediaPlayerEntityFeature.VOLUME_MUTE
    | MediaPlayerEntityFeature.SELECT_SOURCE
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
    which is how a TV is expected to behave in Home Assistant, and the only pair of commands that
    works from a distance. Real standby (``button.<tv>_standby``) is one-way: the set drops off the
    network and needs its own remote or Xiaomi's voice assistant to come back.

    Sources are the apps the launcher offers, so ``media_player.select_source`` opens one.
    """

    _attr_name = None  # named after the device: media_player.<your name>
    _attr_device_class = MediaPlayerDeviceClass.TV

    def __init__(self, coordinator, entry: ConfigEntry) -> None:
        super().__init__(coordinator, entry)
        # Fixed from the entry's name, so it is media_player.halo_tv as the documentation says, rather
        # than depending on whether the device registry was filled in first.
        self.entity_id = f"media_player.{slugify(entry.title)}"

    @property
    def supported_features(self) -> MediaPlayerEntityFeature:
        return FEATURES

    # ---- What it is doing.

    @property
    def state(self) -> MediaPlayerState:
        if not self.screen_on:
            return MediaPlayerState.OFF
        if self.media_data.get("playing"):
            return MediaPlayerState.PLAYING
        if self.media_data.get("paused"):
            return MediaPlayerState.PAUSED
        return MediaPlayerState.IDLE

    @property
    def media_title(self) -> str | None:
        return self.media_data.get("title") or None

    @property
    def media_artist(self) -> str | None:
        return self.media_data.get("artist") or None

    @property
    def app_name(self) -> str | None:
        return self.media_data.get("app_label") or None

    @property
    def entity_picture(self) -> str | None:
        return self.media_data.get("artwork") or None

    @property
    def media_duration(self) -> int | None:
        duration = self.media_data.get("duration")
        return int(duration) if duration else None

    @property
    def media_position(self) -> int | None:
        position = self.media_data.get("position")
        return int(position) if position else None

    @property
    def media_position_updated_at(self):
        return dt_util.utcnow() if self.state == MediaPlayerState.PLAYING else None

    @property
    def volume_level(self) -> float | None:
        level = self.state_data.get("volume_level")
        return float(level) if level is not None else None

    @property
    def is_volume_muted(self) -> bool:
        return bool(self.state_data.get("muted"))

    # ---- Apps as sources. The naming lives in the coordinator, so the remote agrees.

    @property
    def source_list(self) -> list[str] | None:
        sources = self.coordinator.media_sources
        return sources or None

    @property
    def source(self) -> str | None:
        return self.coordinator.current_source

    # ---- Writing.

    async def async_turn_on(self) -> None:
        await self.async_send("screen_on")

    async def async_turn_off(self) -> None:
        await self.async_send("screen_off")

    async def async_media_play(self) -> None:
        await self.async_send("play")

    async def async_media_pause(self) -> None:
        await self.async_send("pause")

    async def async_media_stop(self) -> None:
        await self.async_send("stop")

    async def async_media_next_track(self) -> None:
        await self.async_send("next")

    async def async_media_previous_track(self) -> None:
        await self.async_send("prev")

    async def async_set_volume_level(self, volume: float) -> None:
        await self.async_send("volume_set", level=round(volume, 3))

    async def async_mute_volume(self, mute: bool) -> None:
        await self.async_send("mute" if mute else "unmute")

    async def async_select_source(self, source: str) -> None:
        package = self.coordinator.package_for(source)
        if package:
            await self.coordinator.async_launch(package)

    # ---- Everything else the TV knows, for templates and cards.

    @property
    def extra_state_attributes(self) -> dict[str, Any]:
        today = self.viewing_today
        brief = self.viewing_brief
        return {
            "screen": self.state_data.get("screen"),
            "screen_mode": self.state_data.get("screen_mode"),
            "screen_since": self.state_data.get("screen_since"),
            "playing_app": self.media_data.get("app_label") or None,
            "playing_package": self.media_data.get("app") or None,
            "message_showing": bool(self.message_data.get("showing")),
            "message_title": self.message_data.get("title") or None,
            "message_text": self.message_data.get("text") or None,
            "volume_percent": self.state_data.get("volume"),
            "volume_raw": self.state_data.get("volume_raw"),
            "volume_max": self.state_data.get("volume_max"),
            # The viewing-time clock, in its short form; the sensors carry the full picture.
            "viewing_enabled": brief.get("enabled", today.get("enabled")),
            "watched_today_min": brief.get("watched_min", today.get("watched_min")),
            "remaining_today_min": brief.get("remaining_min", today.get("remaining_min")),
            "daily_limit_min": brief.get("daily_limit_min", today.get("shutdown_at_min")),
            "viewing_counting": brief.get("counting", today.get("counting")),
            "limit_reached": brief.get("limit_reached", today.get("shutdown")),
            "viewing_date": today.get("date"),
            # Two optional ADB grants from install.sh; false simply means less detail, not a fault.
            "overlay_allowed": self.state_data.get("overlay"),
        }
