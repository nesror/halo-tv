"""A small async client for the HTTP API Halo TV serves on port 8765.

The TV answers four things (see docs/home-assistant.md):

* ``GET  /api/ha/info``    - what this TV is
* ``GET  /api/ha/state``   - what it is doing now
* ``POST /api/ha/notify``  - show a message (and optionally read it out)
* ``POST /api/ha/command`` - pause, screen off, volume...
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

    async def async_get_info(self) -> dict[str, Any]:
        """What the TV is: model, version, which features it has."""
        return await self._request("GET", "/api/ha/info")

    async def async_get_state(self) -> dict[str, Any]:
        """What the TV is doing: screen, media, volume, message."""
        return await self._request("GET", "/api/ha/state", timeout=6)

    async def async_notify(self, payload: dict[str, Any]) -> dict[str, Any]:
        """Show a message. A camera snapshot rides along inline, so this gets a longer timeout."""
        return await self._request("POST", "/api/ha/notify", payload, timeout=25)

    async def async_command(self, action: str, **params: Any) -> dict[str, Any]:
        """Ask the TV to do something, and get its state back in the same breath."""
        return await self._request(
            "POST", "/api/ha/command", {"action": action, **params}, timeout=10
        )
