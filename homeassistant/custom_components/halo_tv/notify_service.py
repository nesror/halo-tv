"""The notify service, ``notify.mobile_app_halo_tv``.

Deliberately the same shape as the Home Assistant companion app on a phone - ``message``, ``title``
and a ``data:`` dictionary - so an automation written for a phone works on the TV with one word
changed. Home Assistant's newer notify *entities* only take a message and a title, which is not enough
for a picture or a voice, so this is registered as a notify service instead, the way the phone app is.

What ``data:`` accepts is in docs/home-assistant.md; in short:

    data:
      image: http://homeassistant.local:8123/local/doorbell.jpg
      tts: true
      duration: 20
"""

from __future__ import annotations

import logging
from typing import Any

from homeassistant.components.notify import (
    ATTR_DATA,
    ATTR_TITLE,
    BaseNotificationService,
)
from homeassistant.core import HomeAssistant
from homeassistant.exceptions import HomeAssistantError
from homeassistant.helpers.service import async_set_service_schema

from .api import HaloTvError
from .const import ACTIONS
from .coordinator import HaloTvCoordinator

_LOGGER = logging.getLogger(__name__)

#: What a phone's notify call calls each thing, mapped to the TV's own field name.
ALIASES = {
    "image": ("image", "image_url", "picture", "url", "attachment"),
    "duration": ("duration", "timeout", "dismiss_after"),
    "language": ("language", "lang"),
    "position": ("position", "align"),
    "command": ("command", "action", "do"),
}

SERVICE_SCHEMA = {
    "name": "Send a message to Halo TV",
    "description": (
        "Puts a message on the TV: a title, a line or two of text, a picture, and optionally a voice "
        "that reads it out. Add data.command (or send a message of just '/screen_off') to pause, "
        "turn the screen off and so on instead."
    ),
    "fields": {
        "message": {
            "name": "Message",
            "description": "The text to show.",
            "required": True,
            "example": "Someone is at the front door",
            "selector": {"text": {"multiline": True}},
        },
        "title": {
            "name": "Title",
            "description": "The small heading above the message.",
            "example": "Doorbell",
            "selector": {"text": {}},
        },
        "data": {
            "name": "Data",
            "description": (
                "image: a picture to show (http(s) address or data: URI). "
                "tts: true to read the message out loud. "
                "duration: seconds on screen, 0 to stay until dismissed. "
                "language: a tag like en-GB or zh-CN for the voice. "
                "command: pause, play, screen_off, screen_on, ambient and the rest."
            ),
            "example": {"image": "http://homeassistant.local:8123/local/photo.jpg", "tts": True},
            "selector": {"object": {}},
        },
    },
}


class HaloTvNotificationService(BaseNotificationService):
    """Sends a message to one TV."""

    def __init__(self, coordinator: HaloTvCoordinator, language: str) -> None:
        self._coordinator = coordinator
        self._language = language

    async def async_send_message(self, message: str = "", **kwargs: Any) -> None:
        """Show a message, or run a command if that is what was sent."""
        data = kwargs.get(ATTR_DATA) or {}
        if not isinstance(data, dict):
            raise HomeAssistantError("data: must be a dictionary")
        payload = build_payload(message, kwargs.get(ATTR_TITLE), data, self._language)

        action = payload.get("command")
        if action and not payload.get("message") and not payload.get("title") and not payload.get("image"):
            # Nothing to show: the call is only a command.
            payload.pop("command")
            await self._async_command(action)
            return

        # Otherwise the command rides along: the TV runs it as it puts the message up.

        try:
            result = await self._coordinator.async_notify(payload)
        except HaloTvError as err:
            raise HomeAssistantError(f"Halo TV did not show the message: {err}") from err
        if result.get("overlay") is False:
            _LOGGER.warning(
                "Halo TV took the message but cannot draw over other apps on this TV, so it may only "
                "appear while Halo TV is on screen. Grant the overlay permission with install.sh, or: "
                "adb shell appops set com.kartikmodi.halo.home SYSTEM_ALERT_WINDOW allow"
            )

    async def _async_command(self, action: str) -> None:
        try:
            await self._coordinator.async_command(action)
        except HaloTvError as err:
            raise HomeAssistantError(f"Halo TV did not take '{action}': {err}") from err


def build_payload(
    message: str | None,
    title: str | None,
    data: dict[str, Any],
    language: str | None = None,
) -> dict[str, Any]:
    """Turn a notify call into the TV's own field names."""
    text = (message or "").strip()
    payload: dict[str, Any] = {}

    # "/pause", "/screen_off": a message that is really a command, for a single-line script.
    if text.startswith("/"):
        action = _action(text[1:])
        if action in ACTIONS:
            payload["command"] = action
            text = ""

    explicit = _first(data, ALIASES["command"])
    if explicit:
        action = _action(str(explicit))
        if action not in ACTIONS:
            raise HomeAssistantError(f"Halo TV does not know the command '{explicit}'")
        payload["command"] = action

    if title:
        payload["title"] = title
    if text:
        payload["message"] = text

    image = _first(data, ALIASES["image"])
    if image:
        payload["image"] = str(image)

    for key in ("tts", "speak"):
        if key in data:
            payload["tts"] = bool(data[key])
            break

    duration = _first(data, ALIASES["duration"])
    if duration is not None:
        payload["duration"] = int(duration)

    spoken = _first(data, ALIASES["language"]) or language
    if spoken:
        payload["language"] = str(spoken)

    position = _first(data, ALIASES["position"])
    if position:
        payload["position"] = str(position)

    return payload


def _action(text: str) -> str:
    return text.strip().lower().replace("-", "_").replace(" ", "_")


def _first(data: dict[str, Any], keys: tuple[str, ...]) -> Any:
    for key in keys:
        if data.get(key) not in (None, ""):
            return data[key]
    return None


async def async_register(
    hass: HomeAssistant,
    coordinator: HaloTvCoordinator,
    service_name: str,
) -> None:
    """Create notify.<service_name>, and describe it in the UI the way a phone's is described."""
    service = HaloTvNotificationService(coordinator, hass.config.language)
    await service.async_setup(hass, service_name, service_name)
    await service.async_register_services()
    async_set_service_schema(hass, "notify", service_name, SERVICE_SCHEMA)
