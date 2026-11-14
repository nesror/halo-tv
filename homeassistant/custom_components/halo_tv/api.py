"""A small async client for the HTTP API Halo TV serves on port 8765.

The TV answers these (see docs/home-assistant.md):

* ``GET  /api/ha/info``       - what this TV is
* ``GET  /api/ha/state``      - what it is doing now
* ``POST /api/ha/notify``     - show a message (and optionally read it out)
* ``POST /api/ha/command``    - pause, screen off, volume...
* ``GET  /api/apps``          - the apps the TV can open
* ``POST /api/launch``        - open one of them
* ``POST /api/key``           - one press of the set's own remote
* ``GET  /api/tvtime/status`` - the viewing-time clock and its thresholds
* ``POST /api/tvtime/config`` - change those thresholds
* ``POST /api/tvtime/reset``  - start today's count again
* ``POST /api/tvtime/adjust`` - add or subtract minutes
"""

from __future__ import annotations

import asyncio
from typing import Any

import aiohttp

from .const import DEFAULT_PORT


class HaloTvError(Exception):
    """The TV did not answer, or answered with something we cannot use."""


class HaloTvClient:
    """Talks to one Halo TV."""

    def __init__(
        self,
        session: aiohttp.ClientSession,
        host: str,
        port: int = DEFAULT_PORT,
    ) -> None:
        self._session = session
        self.host = host
        self.port = port

    @property
    def base_url(self) -> str:
        """Where the TV's web server listens."""
        return f"http://{self.host}:{self.port}"

    async def _request(
        self,
        method: str,
        path: str,
        payload: dict[str, Any] | None = None,
        timeout: float = 8,
    ) -> dict[str, Any]:
        url = f"{self.base_url}{path}"
        try:
            async with self._session.request(
                method,
                url,
                json=payload,
                timeout=aiohttp.ClientTimeout(total=timeout),
            ) as response:
                if response.status != 200:
                    raise HaloTvError(f"{path} answered HTTP {response.status}")
                body = await response.json(content_type=None)
        except asyncio.TimeoutError as err:
            raise HaloTvError(f"{path} timed out") from err
        except aiohttp.ClientError as err:
            raise HaloTvError(f"{path} failed: {err}") from err
        if not isinstance(body, dict):
            raise HaloTvError(f"{path} answered something that is not an object")
        return body

    # ---- What the TV is, and what it is doing.

    async def async_get_info(self) -> dict[str, Any]:
        """What the TV is: model, version, which features it has."""
        return await self._request("GET", "/api/ha/info")

    async def async_get_state(self) -> dict[str, Any]:
        """What the TV is doing: screen, media, volume, message."""
        return await self._request("GET", "/api/ha/state", timeout=6)

    # ---- A message, and single commands.

    async def async_notify(self, payload: dict[str, Any]) -> dict[str, Any]:
        """Show a message. A camera snapshot rides along inline, so this gets a longer timeout."""
        return await self._request("POST", "/api/ha/notify", payload, timeout=25)

    async def async_command(self, action: str, **params: Any) -> dict[str, Any]:
        """Ask the TV to do something, and get its state back in the same breath."""
        return await self._request(
            "POST", "/api/ha/command", {"action": action, **params}, timeout=10
        )

    # ---- The set's own remote: one press at a time.

    async def async_key(self, key: str) -> dict[str, Any]:
        """Press one button of the remote: up, ok, home, menu, power, volume_up..."""
        return await self._request("POST", "/api/key", {"key": key}, timeout=10)

    # ---- The apps the launcher can open.

    async def async_get_apps(self) -> list[dict[str, Any]]:
        """Every app the phone remote offers, as {package, label}."""
        body = await self._request("GET", "/api/apps", timeout=10)
        apps = body.get("apps")
        return apps if isinstance(apps, list) else []

    async def async_launch(self, package: str) -> dict[str, Any]:
        """Open one app."""
        return await self._request("POST", "/api/launch", {"package": package}, timeout=10)

    # ---- The viewing-time clock.

    async def async_get_viewing(self) -> dict[str, Any]:
        """Today's count plus every threshold, in one answer."""
        return await self._request("GET", "/api/tvtime/status", timeout=8)

    async def async_set_viewing(self, **config: Any) -> dict[str, Any]:
        """Change thresholds; the TV ignores keys it does not know and says which."""
        return await self._request("POST", "/api/tvtime/config", config, timeout=8)

    async def async_reset_viewing(self) -> dict[str, Any]:
        """Start today's count again."""
        return await self._request("POST", "/api/tvtime/reset", {}, timeout=8)

    async def async_adjust_viewing(self, minutes: float) -> dict[str, Any]:
        """Add (or, with a negative number, take away) minutes of today's count."""
        return await self._request(
            "POST", "/api/tvtime/adjust", {"minutes": minutes}, timeout=8
        )
