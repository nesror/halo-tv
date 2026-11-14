"""The Halo TV integration.

A TV running Halo TV already serves a small HTTP API for its phone remote; this integration uses the
same port to give Home Assistant the whole set:

* ``media_player.<your tv>`` - the screen on/off, play/pause, volume, and the app in front; its sources
  are the apps, so ``select_source`` opens one
* ``remote.<your tv> remote`` - every button of the set's own remote, one press at a time
* ``button.<your tv> *`` - those same buttons, plus standby and "reset today's viewing time"
* ``switch.<your tv> *`` - the screen, the mute, and each viewing-time rule as on/off
* ``number.<your tv> *`` - the viewing-time thresholds (daily limit, eye-rest interval...), and volume
* ``sensor.<your tv> *`` / ``binary_sensor.<your tv> *`` - how much of today is watched, what is left,
  when the next reminder lands, what is playing, and whether the screen is on
* ``text.<your tv> message`` - type a message, and it appears on the TV
* ``notify.mobile_app_halo_tv`` - the same message with a picture and a voice, shaped like the phone app
* ``halo_tv.command`` / ``halo_tv.key`` / ``halo_tv.send_message`` / ``halo_tv.set_viewing`` - the raw
  calls, for scripts that would rather name one thing than press an entity

See docs/home-assistant.md for what each one does.
"""

from __future__ import annotations

import logging
from typing import Any

import voluptuous as vol

from homeassistant.config_entries import ConfigEntry
from homeassistant.const import ATTR_DEVICE_ID, CONF_HOST, CONF_PORT, Platform
from homeassistant.core import HomeAssistant, ServiceCall, SupportsResponse
from homeassistant.exceptions import ConfigEntryNotReady, HomeAssistantError
from homeassistant.helpers import config_validation as cv, device_registry as dr
from homeassistant.helpers.aiohttp_client import async_get_clientsession
from homeassistant.helpers.typing import ConfigType
from homeassistant.util import slugify

from . import notify_service
from .api import HaloTvClient, HaloTvError
from .const import (
    ACTIONS,
    CONF_SCAN_INTERVAL,
    DEFAULT_PORT,
    DEFAULT_SCAN_INTERVAL,
    DOMAIN,
    KEYS,
    VIEWING_BOOLEANS,
    VIEWING_NUMBERS,
)
from .coordinator import HaloTvCoordinator

_LOGGER = logging.getLogger(__name__)

PLATFORMS = [
    Platform.MEDIA_PLAYER,
    Platform.REMOTE,
    Platform.BUTTON,
    Platform.SWITCH,
    Platform.NUMBER,
    Platform.SENSOR,
    Platform.BINARY_SENSOR,
    Platform.TEXT,
]

SERVICE_COMMAND = "command"
SERVICE_KEY = "key"
SERVICE_SEND_MESSAGE = "send_message"
SERVICE_SET_VIEWING = "set_viewing"

#: notify.mobile_app_<name> - named after the phone app's services, on purpose.
NOTIFY_PREFIX = "mobile_app"

COMMAND_SCHEMA = vol.Schema(
    {
        vol.Optional(ATTR_DEVICE_ID): cv.string,
        vol.Required("action"): vol.In(sorted(ACTIONS)),
        vol.Optional("text"): cv.string,
        vol.Optional("title"): cv.string,
        vol.Optional("message"): cv.string,
        vol.Optional("image"): cv.string,
        vol.Optional("language"): cv.string,
        vol.Optional("duration"): vol.Coerce(int),
        vol.Optional("volume"): vol.Coerce(int),
        vol.Optional("level"): vol.Coerce(float),
        vol.Optional("tts"): cv.boolean,
    }
)

KEY_SCHEMA = vol.Schema(
    {
        vol.Optional(ATTR_DEVICE_ID): cv.string,
        vol.Required("key"): vol.In(sorted(KEYS)),
    }
)

MESSAGE_SCHEMA = vol.Schema(
    {
        vol.Optional(ATTR_DEVICE_ID): cv.string,
        vol.Required("message"): cv.string,
        vol.Optional("title", default=""): cv.string,
        vol.Optional("image"): cv.string,
        vol.Optional("language"): cv.string,
        vol.Optional("position"): vol.In(["center", "top", "bottom"]),
        vol.Optional("duration", default=20): vol.All(vol.Coerce(int), vol.Range(min=0, max=3600)),
        vol.Optional("tts", default=False): cv.boolean,
    }
)

VIEWING_FIELDS: dict[Any, Any] = {vol.Optional(ATTR_DEVICE_ID): cv.string}
for _name in sorted(VIEWING_BOOLEANS):
    VIEWING_FIELDS[vol.Optional(_name)] = cv.boolean
for _name, (_low, _high) in sorted(VIEWING_NUMBERS.items()):
    VIEWING_FIELDS[vol.Optional(_name)] = vol.All(
        vol.Coerce(int), vol.Range(min=_low, max=_high)
    )
VIEWING_SCHEMA = vol.Schema(VIEWING_FIELDS)


async def async_setup(hass: HomeAssistant, config: ConfigType) -> bool:
    """Register the services that are not entities."""

    async def async_handle_command(call: ServiceCall) -> dict[str, Any]:
        coordinator = _coordinator_for_call(hass, call)
        data = dict(call.data)
        action = data.pop("action")
        data.pop(ATTR_DEVICE_ID, None)
        payload = {key: value for key, value in data.items() if value is not None}
        try:
            return await coordinator.async_command(action, **payload)
        except HaloTvError as err:
            raise HomeAssistantError(f"Halo TV did not take '{action}': {err}") from err

    async def async_handle_key(call: ServiceCall) -> None:
        coordinator = _coordinator_for_call(hass, call)
        await coordinator.async_key(call.data["key"])

    async def async_handle_message(call: ServiceCall) -> dict[str, Any]:
        coordinator = _coordinator_for_call(hass, call)
        payload = {
            key: value
            for key, value in call.data.items()
            if key not in (ATTR_DEVICE_ID, "message") and value is not None
        }
        payload["message"] = call.data["message"]
        return await coordinator.async_notify(payload)

    async def async_handle_set_viewing(call: ServiceCall) -> None:
        coordinator = _coordinator_for_call(hass, call)
        config = {key: value for key, value in call.data.items() if key != ATTR_DEVICE_ID}
        if not config:
            raise HomeAssistantError("Name at least one setting to change")
        await coordinator.async_set_viewing(**config)

    hass.services.async_register(
        DOMAIN,
        SERVICE_COMMAND,
        async_handle_command,
        schema=COMMAND_SCHEMA,
        supports_response=SupportsResponse.OPTIONAL,
    )
    hass.services.async_register(DOMAIN, SERVICE_KEY, async_handle_key, schema=KEY_SCHEMA)
    hass.services.async_register(
        DOMAIN,
        SERVICE_SEND_MESSAGE,
        async_handle_message,
        schema=MESSAGE_SCHEMA,
        supports_response=SupportsResponse.OPTIONAL,
    )
    hass.services.async_register(
        DOMAIN, SERVICE_SET_VIEWING, async_handle_set_viewing, schema=VIEWING_SCHEMA
    )
    return True


def _coordinator_for_call(hass: HomeAssistant, call: ServiceCall) -> HaloTvCoordinator:
    """The TV a service call is aimed at: its device, or the only TV there is."""
    entries = hass.config_entries.async_loaded_entries(DOMAIN)
    device_id = call.data.get(ATTR_DEVICE_ID)
    if device_id:
        device = dr.async_get(hass).async_get(device_id)
        if device is not None:
            for entry in entries:
                if (DOMAIN, entry.entry_id) in device.identifiers:
                    return entry.runtime_data
        raise HomeAssistantError("That device is not a Halo TV")
    if len(entries) == 1:
        return entries[0].runtime_data
    raise HomeAssistantError("More than one Halo TV: name one with target: device_id")


async def async_setup_entry(hass: HomeAssistant, entry: ConfigEntry) -> bool:
    """Set up one TV."""
    host = entry.data[CONF_HOST]
    port = entry.data.get(CONF_PORT, DEFAULT_PORT)
    client = HaloTvClient(async_get_clientsession(hass), host, port)

    try:
        info = await client.async_get_info()
    except HaloTvError as err:
        raise ConfigEntryNotReady(f"{host}:{port} did not answer: {err}") from err

    interval = entry.options.get(CONF_SCAN_INTERVAL, DEFAULT_SCAN_INTERVAL)
    coordinator = HaloTvCoordinator(hass, entry, client, info, interval)
    await coordinator.async_config_entry_first_refresh()

    entry.runtime_data = coordinator
    entry.async_on_unload(entry.add_update_listener(_async_reload_entry))
    await hass.config_entries.async_forward_entry_setups(entry, PLATFORMS)

    notify_name = f"{NOTIFY_PREFIX}_{slugify(entry.title) or entry.entry_id[:8]}"
    await notify_service.async_register(hass, coordinator, notify_name)
    entry.async_on_unload(
        lambda: hass.services.async_remove("notify", notify_name)
    )
    return True


async def async_unload_entry(hass: HomeAssistant, entry: ConfigEntry) -> bool:
    """Unload a TV."""
    return await hass.config_entries.async_unload_platforms(entry, PLATFORMS)


async def _async_reload_entry(hass: HomeAssistant, entry: ConfigEntry) -> None:
    await hass.config_entries.async_reload(entry.entry_id)
