"""Setting up a Halo TV from the Home Assistant UI."""

from __future__ import annotations

import logging
from typing import Any
from urllib.parse import urlparse

import voluptuous as vol

from homeassistant.config_entries import ConfigEntry, ConfigFlow, OptionsFlow
from homeassistant.const import CONF_HOST, CONF_NAME, CONF_PORT
from homeassistant.core import callback
from homeassistant.data_entry_flow import FlowResult
from homeassistant.helpers.aiohttp_client import async_get_clientsession

from .api import HaloTvClient, HaloTvError
from .const import CONF_SCAN_INTERVAL, DEFAULT_PORT, DEFAULT_SCAN_INTERVAL, DOMAIN

_LOGGER = logging.getLogger(__name__)


def _split(address: str) -> tuple[str, int]:
    """Accepts "192.168.1.20", "192.168.1.20:8765" or the whole "http://192.168.1.20:8765/" URL."""
    text = address.strip().rstrip("/")
    if "//" in text:
        text = text.split("//", 1)[1]
    if "/" in text:
        text = text.split("/", 1)[0]
    if text.count(":") == 1:
        host, _, port = text.partition(":")
        if port.isdigit():
            return host, int(port)
    return text, DEFAULT_PORT


class HaloTvConfigFlow(ConfigFlow, domain=DOMAIN):
    """Ask for the TV's address once."""

    VERSION = 1

    async def async_step_user(
        self, user_input: dict[str, Any] | None = None
    ) -> FlowResult:
        errors: dict[str, str] = {}

        if user_input is not None:
            host, port = _split(user_input[CONF_HOST])
            if user_input.get(CONF_PORT):
                port = int(user_input[CONF_PORT])
            client = HaloTvClient(async_get_clientsession(self.hass), host, port)
            try:
                await client.async_get_info()
            except HaloTvError as err:
                _LOGGER.debug("No Halo TV at %s:%s: %s", host, port, err)
                errors["base"] = "cannot_connect"
            else:
                await self.async_set_unique_id(f"{host}:{port}")
                self._abort_if_unique_id_configured(
                    updates={CONF_HOST: host, CONF_PORT: port}
                )
                name = (user_input.get(CONF_NAME) or "").strip()
                if not name:
                    # "Halo TV" gives media_player.halo_tv; the model shows on the device page instead.
                    name = "Halo TV"
                return self.async_create_entry(
                    title=name, data={CONF_HOST: host, CONF_PORT: port}
                )

        return self.async_show_form(
            step_id="user",
            data_schema=vol.Schema(
                {
                    vol.Required(CONF_HOST): str,
                    vol.Optional(CONF_PORT, default=DEFAULT_PORT): int,
                    vol.Optional(CONF_NAME): str,
                }
            ),
            errors=errors,
        )

    @staticmethod
    @callback
    def async_get_options_flow(entry: ConfigEntry) -> OptionsFlow:
        return HaloTvOptionsFlow(entry)


class HaloTvOptionsFlow(OptionsFlow):
    """How often to ask the TV what it is doing."""

    def __init__(self, entry: ConfigEntry) -> None:
        self._entry = entry

    async def async_step_init(
        self, user_input: dict[str, Any] | None = None
    ) -> FlowResult:
        if user_input is not None:
            return self.async_create_entry(data=user_input)
        current = self._entry.options.get(CONF_SCAN_INTERVAL, DEFAULT_SCAN_INTERVAL)
        return self.async_show_form(
            step_id="init",
            data_schema=vol.Schema(
                {
                    vol.Optional(CONF_SCAN_INTERVAL, default=current): vol.All(
                        vol.Coerce(int), vol.Range(min=2, max=120)
                    )
                }
            ),
        )
