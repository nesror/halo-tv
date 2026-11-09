"""Tests for the Halo TV integration, against the fake TV in fake_tv.py.

    pip install pytest-homeassistant-custom-component
    pytest homeassistant/tests -q

They cover the parts that are easy to get wrong and hard to notice: the config flow, the entity and
service names the documentation promises, and exactly what is sent over the wire.
"""

from __future__ import annotations

from typing import Any

import pytest
from homeassistant.const import CONF_HOST, CONF_PORT
from homeassistant.core import HomeAssistant
from homeassistant.exceptions import HomeAssistantError
from pytest_homeassistant_custom_component.common import MockConfigEntry

from custom_components.halo_tv.const import DOMAIN
from custom_components.halo_tv.notify_service import build_payload


@pytest.fixture(name="entry")
async def fixture_entry(hass: HomeAssistant, tv_port: int) -> MockConfigEntry:
    """A config entry pointing at the fake TV, set up and ready."""
    entry = MockConfigEntry(
        domain=DOMAIN,
        title="Halo TV",
        data={CONF_HOST: "127.0.0.1", CONF_PORT: tv_port},
        unique_id=f"127.0.0.1:{tv_port}",
    )
    entry.add_to_hass(hass)
    assert await hass.config_entries.async_setup(entry.entry_id)
    await hass.async_block_till_done()
    return entry


async def test_config_flow(hass: HomeAssistant, tv_port: int) -> None:
    """Adding the integration asks for an address and creates an entry."""
    result = await hass.config_entries.flow.async_init(
        DOMAIN, context={"source": "user"}
    )
    assert result["type"] == "form"
    assert result["step_id"] == "user"

    result = await hass.config_entries.flow.async_configure(
        result["flow_id"], {CONF_HOST: "127.0.0.1", CONF_PORT: tv_port}
    )
    assert result["type"] == "create_entry"
    assert result["title"] == "Halo TV"
    assert result["data"] == {CONF_HOST: "127.0.0.1", CONF_PORT: tv_port}


async def test_config_flow_wrong_address(hass: HomeAssistant) -> None:
    """Nothing answering means a readable error, not an exception."""
    result = await hass.config_entries.flow.async_init(
        DOMAIN, context={"source": "user"}
    )
    result = await hass.config_entries.flow.async_configure(
        result["flow_id"], {CONF_HOST: "127.0.0.1", CONF_PORT: 1}
    )
    assert result["type"] == "form"
    assert result["errors"] == {"base": "cannot_connect"}


async def test_entities_are_named_as_documented(
    hass: HomeAssistant, entry: MockConfigEntry
) -> None:
    """media_player.halo_tv and notify.mobile_app_halo_tv are the promises the docs make."""
    assert hass.states.get("media_player.halo_tv") is not None
    assert hass.services.has_service("notify", "mobile_app_halo_tv")
    assert hass.services.has_service(DOMAIN, "command")


async def test_state_follows_the_tv(
    hass: HomeAssistant, entry: MockConfigEntry, fake_tv: Any
) -> None:
    """What the TV reports becomes the entity's state and attributes."""
    fake_tv.STATE["media"].update(
        {
            "playing": True,
            "title": "Blue Monday",
            "artist": "New Order",
            "app_label": "YouTube",
            "artwork": "http://example.test/art.jpg",
        }
    )
    await entry.runtime_data.async_refresh()
    await hass.async_block_till_done()

    state = hass.states.get("media_player.halo_tv")
    assert state is not None
    assert state.state == "playing"
    assert state.attributes["media_title"] == "Blue Monday"
    assert state.attributes["app_name"] == "YouTube"
    assert state.attributes["entity_picture"] == "http://example.test/art.jpg"
    assert state.attributes["volume_level"] == 0.4


async def test_notify_sends_everything_the_docs_show(
    hass: HomeAssistant, entry: MockConfigEntry, fake_tv: Any
) -> None:
    """A message with a picture, a voice and a duration reaches the TV as those fields."""
    await hass.services.async_call(
        "notify",
        "mobile_app_halo_tv",
        {
            "title": "Doorbell",
            "message": "Someone is at the front door",
            "data": {
                "image": "http://homeassistant.local:8123/local/doorbell.jpg",
                "tts": True,
                "duration": 25,
                "language": "en-GB",
            },
        },
        blocking=True,
    )
    sent = fake_tv.RECEIVED[-1]
    assert sent["endpoint"] == "notify"
    assert sent["title"] == "Doorbell"
    assert sent["message"] == "Someone is at the front door"
    assert sent["image"].endswith("doorbell.jpg")
    assert sent["tts"] is True
    assert sent["duration"] == 25
    assert sent["language"] == "en-GB"


async def test_notify_carries_a_command(
    hass: HomeAssistant, entry: MockConfigEntry, fake_tv: Any
) -> None:
    """data.command pauses as the message goes up, the way a phone's notify does action buttons."""
    await hass.services.async_call(
        "notify",
        "mobile_app_halo_tv",
        {"message": "Paused", "data": {"command": "pause"}},
        blocking=True,
    )
    sent = fake_tv.RECEIVED[-1]
    assert sent["command"] == "pause"
    assert sent["message"] == "Paused"


async def test_notify_that_is_only_a_command(
    hass: HomeAssistant, entry: MockConfigEntry, fake_tv: Any
) -> None:
    """A notify call that is only a command goes down the command path, not the message one."""
    await hass.services.async_call(
        "notify", "mobile_app_halo_tv", {"message": "/screen_off"}, blocking=True
    )
    sent = fake_tv.RECEIVED[-1]
    assert sent["endpoint"] == "command"
    assert sent["action"] == "screen_off"


async def test_unknown_command_is_refused(
    hass: HomeAssistant, entry: MockConfigEntry, fake_tv: Any
) -> None:
    """A command the TV does not know is refused here, with a readable message."""
    with pytest.raises(HomeAssistantError):
        await hass.services.async_call(
            "notify",
            "mobile_app_halo_tv",
            {"message": "hi", "data": {"command": "self_destruct"}},
            blocking=True,
        )
    assert fake_tv.RECEIVED == []


async def test_media_player_off_is_the_screen(
    hass: HomeAssistant, entry: MockConfigEntry, fake_tv: Any
) -> None:
    """turn_off goes dark, turn_on comes back, and the state follows."""
    await hass.services.async_call(
        "media_player", "turn_off", {"entity_id": "media_player.halo_tv"}, blocking=True
    )
    assert fake_tv.RECEIVED[-1]["action"] == "screen_off"
    assert hass.states.get("media_player.halo_tv").state == "off"

    await hass.services.async_call(
        "media_player", "turn_on", {"entity_id": "media_player.halo_tv"}, blocking=True
    )
    assert fake_tv.RECEIVED[-1]["action"] == "screen_on"
    assert hass.states.get("media_player.halo_tv").state == "idle"


async def test_volume(
    hass: HomeAssistant, entry: MockConfigEntry, fake_tv: Any
) -> None:
    """Home Assistant's 0..1 volume becomes the TV's own scale."""
    await hass.services.async_call(
        "media_player",
        "volume_set",
        {"entity_id": "media_player.halo_tv", "volume_level": 0.75},
        blocking=True,
    )
    assert fake_tv.RECEIVED[-1]["action"] == "volume_set"
    assert fake_tv.RECEIVED[-1]["level"] == 0.75


async def test_command_service(
    hass: HomeAssistant, entry: MockConfigEntry, fake_tv: Any
) -> None:
    """halo_tv.command reaches the TV and answers with its state."""
    response = await hass.services.async_call(
        DOMAIN,
        "command",
        {"action": "say", "text": "Dinner is ready"},
        blocking=True,
        return_response=True,
    )
    assert fake_tv.RECEIVED[-1]["action"] == "say"
    assert fake_tv.RECEIVED[-1]["text"] == "Dinner is ready"
    assert response["ok"] is True


def test_payload_mapping() -> None:
    """The notify -> TV field mapping, including the aliases a phone's automation might use."""
    payload = build_payload(
        "Hello",
        "Title",
        {"attachment": "http://x/y.png", "timeout": "8", "speak": True},
        "en-GB",
    )
    assert payload == {
        "message": "Hello",
        "title": "Title",
        "image": "http://x/y.png",
        "duration": 8,
        "tts": True,
        "language": "en-GB",
    }


def test_slash_command() -> None:
    """A message of just "/screen_off" is a command, not text to show."""
    assert build_payload("/screen_off", None, {}) == {"command": "screen_off"}
    assert build_payload("/not-a-command", None, {}) == {"message": "/not-a-command"}
