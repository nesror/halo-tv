"""Constants for the Halo TV integration."""

from __future__ import annotations

DOMAIN = "halo_tv"

#: The port of the little web server Halo TV already runs for its phone remote.
DEFAULT_PORT = 8765
DEFAULT_SCAN_INTERVAL = 5

#: Polls between the heavier reads: the viewing-time clock and the app list change rarely.
VIEWING_EVERY = 6
APPS_EVERY = 60

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
    "dismiss",
    "say",
    "stop_speech",
    "volume_set",
    "volume_up",
    "volume_down",
    "mute",
    "unmute",
    "notify",
    # Viewing-time clock: everything else is configured over /api/tvtime/config.
    "viewing_reset",
    "viewing_off",
    "viewing_on",
}

#: One press of the set's own remote (POST /api/key). "power" here is real standby, not screen off.
KEYS = {
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
}

#: The keys /api/tvtime/config accepts, with the range the TV clamps them to.
VIEWING_BOOLEANS = {
    "enabled",
    "auto_shutdown",
    "tts",
    "pause_on_break",
    "pause_during_break",
}

VIEWING_NUMBERS = {
    "daily_limit_min": (1, 1440),
    "break_interval_min": (1, 240),
    "break_minutes": (0, 120),
    "warn_before_min": (0, 120),
    "message_seconds": (5, 600),
    "shutdown_delay_s": (0, 300),
}
