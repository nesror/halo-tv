"""``text.<tv>_message``: a message for the screen, typed straight into a dashboard.

Setting the body sends it, using the title typed in ``text.<tv>_message_title``. Under the hood it is
the same call ``notify.mobile_app_<tv>`` makes, so anything that works there works here - this is just
the version that needs no YAML.
"""

from __future__ import annotations

from typing import Any

from homeassistant.components.text import TextEntity, TextMode
from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from .entity import HaloTvEntity

TITLE = "title"
BODY = "body"

#: Seconds a typed message stays up; long enough to read, short enough not to sit in front of a film.
SHOW_SECONDS = 20


async def async_setup_entry(
    hass: HomeAssistant,
    entry: ConfigEntry,
    async_add_entities: AddEntitiesCallback,
) -> None:
    """Add the two text boxes."""
    coordinator = entry.runtime_data
    async_add_entities(
        [
            HaloTvText(coordinator, entry, TITLE),
            HaloTvText(coordinator, entry, BODY),
        ]
    )


class HaloTvText(HaloTvEntity, TextEntity):
    """The heading, or the message itself."""

    _attr_mode = TextMode.TEXT
    _attr_native_max = 500

    def __init__(self, coordinator, entry: ConfigEntry, field: str) -> None:
        super().__init__(coordinator, entry)
        self._field = field
        self._attr_unique_id = f"{entry.entry_id}_message_{field}"
        if field == TITLE:
            self._attr_name = "Message title"
            self._attr_icon = "mdi:format-title"
            self._attr_native_max = 80
        else:
            self._attr_name = "Message"
            self._attr_icon = "mdi:message-text"

    @property
    def native_value(self) -> str:
        draft = self.coordinator.message_draft
        return draft.get(self._field, "")

    async def async_set_value(self, value: str) -> None:
        draft = self.coordinator.message_draft
        draft[self._field] = value
        self.async_write_ha_state()
        if self._field != BODY or not value.strip():
            return
        await self.coordinator.async_notify(
            {
                "title": draft.get(TITLE, ""),
                "message": value,
                "duration": SHOW_SECONDS,
                "tts": bool(self.viewing_config.get("tts")),
            }
        )

    @property
    def extra_state_attributes(self) -> dict[str, Any]:
        return {"shows_for_seconds": SHOW_SECONDS}
