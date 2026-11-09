#!/usr/bin/env python3
"""A stand-in for a Halo TV, so the integration can be worked on without one.

    python3 homeassistant/tests/fake_tv.py --port 18765

It answers the four endpoints the integration calls and remembers the last message and command it was
sent, which is all it takes to see whether an automation sends what you meant. Point the integration at
http://127.0.0.1:18765, or call it yourself:

    curl -X POST http://127.0.0.1:18765/api/ha/command -d '{"action":"screen_off"}'
"""

from __future__ import annotations

import argparse
import json
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any

def _default_state() -> dict[str, Any]:
    return {
        "screen": "on",
        "screen_mode": "awake",
        "media": {
            "playing": False,
            "paused": False,
            "title": "",
            "artist": "",
            "artwork": "",
            "app": "",
            "app_label": "",
        },
        "message": {"showing": False},
        "volume": 40,
        "volume_level": 0.4,
        "volume_max": 15,
        "muted": False,
        "ambient": False,
        "overlay": True,
        "ts": 0,
    }


STATE: dict[str, Any] = _default_state()

#: Everything the fake TV was asked to do, newest last - what a test asserts against.
RECEIVED: list[dict[str, Any]] = []


def reset() -> None:
    """Back to a quiet, awake TV with nothing received: for the start of each test."""
    STATE.clear()
    STATE.update(_default_state())
    RECEIVED.clear()


def _apply_command(action: str, body: dict[str, Any]) -> None:
    if action in ("screen_off", "off", "sleep"):
        STATE["screen"], STATE["screen_mode"] = "off", "dim"
    elif action in ("screen_on", "on", "wake"):
        STATE["screen"], STATE["screen_mode"] = "on", "awake"
    elif action in ("screen_toggle", "power"):
        on = STATE["screen"] == "on"
        STATE["screen"], STATE["screen_mode"] = ("off", "dim") if on else ("on", "awake")
    elif action in ("pause", "play", "play_pause", "stop"):
        STATE["media"]["playing"] = action in ("play",)
        STATE["media"]["paused"] = action in ("pause", "stop")
    elif action in ("mute",):
        STATE["muted"] = True
    elif action in ("unmute",):
        STATE["muted"] = False
    elif action in ("volume_set", "set_volume"):
        level = body.get("level")
        if level is None:
            level = float(body.get("volume", 50)) / 100
        STATE["volume_level"] = round(float(level), 3)
        STATE["volume"] = round(float(level) * 100)
    elif action in ("dismiss", "clear"):
        STATE["message"] = {"showing": False}
    elif action in ("ambient",):
        STATE["ambient"] = True


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, format: str, *args: Any) -> None:  # noqa: A002 - the base class's signature
        print(f"  {format % args}", flush=True)

    def _json(self, body: Any, code: int = 200) -> None:
        data = json.dumps(body).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def _body(self) -> dict[str, Any]:
        length = int(self.headers.get("Content-Length") or 0)
        if not length:
            return {}
        try:
            return json.loads(self.rfile.read(length) or b"{}")
        except json.JSONDecodeError:
            return {}

    def do_GET(self) -> None:  # noqa: N802 - the base class's signature
        path = self.path.split("?", 1)[0]
        if path == "/api/ha/info":
            self._json(
                {
                    "app": "Halo TV",
                    "version": "2.4",
                    "model": "Fake TV",
                    "manufacturer": "Halo TV",
                    "android": "14",
                    "sdk": 34,
                    "port": self.server.server_address[1],
                    "features": ["notify", "tts", "image", "screen", "playback", "volume"],
                    "overlay": True,
                    "media_sessions": True,
                }
            )
        elif path == "/api/ha/state":
            STATE["ts"] = int(time.time() * 1000)
            self._json(STATE)
        elif path == "/debug":
            self._json({"state": STATE, "received": RECEIVED})
        else:
            self._json({"error": "not found"}, 404)

    def do_POST(self) -> None:  # noqa: N802 - the base class's signature
        path = self.path.split("?", 1)[0]
        body = self._body()
        if path == "/api/ha/notify":
            RECEIVED.append({"endpoint": "notify", **body})
            STATE["message"] = {
                "showing": True,
                "title": body.get("title", ""),
                "text": body.get("message", ""),
                "has_image": bool(body.get("image")),
                "since": int(time.time() * 1000),
            }
            command = body.get("command")
            if command:
                _apply_command(command, body)
            self._json({"ok": True, "overlay": True})
        elif path == "/api/ha/command":
            action = str(body.get("action", "")).lower()
            RECEIVED.append({"endpoint": "command", **body})
            _apply_command(action, body)
            self._json({"ok": True, "action": action, "state": STATE})
        else:
            self._json({"error": "not found"}, 404)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=18765)
    args = parser.parse_args()
    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    print(f"Fake Halo TV on http://127.0.0.1:{args.port} (state at /debug)", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
