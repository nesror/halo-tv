"""Constants for the Halo TV integration."""

from __future__ import annotations

DOMAIN = "halo_tv"

#: The port of the little web server Halo TV already runs for its phone remote.
DEFAULT_PORT = 8765
DEFAULT_SCAN_INTERVAL = 5

CONF_HOST = "host"
CONF_PORT = "port"
CONF_SCAN_INTERVAL = "scan_interval"

MANUFACTURER = "Halo TV"

#: Commands the TV understands (POST /api/ha/command).
ACTIONS = {
    "pause",
    "play",
    "play_pause",
    "stop",
    "next",
    "prev",
    "screen_off",
    "screen_on",
    "screen_toggle",
    "home",
    "ambient",
    "dismiss",
    "say",
    "stop_speech",
    "volume_set",
    "volume_up",
    "volume_down",
    "mute",
    "unmute",
    "notify",
}
