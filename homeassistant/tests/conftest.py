"""Pytest setup for the Halo TV integration.

The component lives in ``custom_components/`` next to this folder, which is what the Home Assistant test
plugin expects:

    pip install pytest-homeassistant-custom-component
    pytest homeassistant/tests
"""

from __future__ import annotations

import importlib.util
import pathlib
import sys
import threading
from http.server import ThreadingHTTPServer
from typing import Any, Iterator

import pytest
import pytest_socket

# Home Assistant's test plugin blocks sockets to catch accidental network calls. On Windows that stops a
# test before it starts: asyncio's event loop is built on a socket pair there, and there are no Unix
# sockets to exempt. Anywhere else the block stays on; these tests only ever talk to fake_tv.py, on
# loopback, and pytest-socket already allows 127.0.0.1.
if sys.platform == "win32":  # pragma: no cover - test infrastructure, not the integration
    pytest_socket.disable_socket = lambda *args, **kwargs: None
    pytest_socket.enable_socket()

_SPEC = importlib.util.spec_from_file_location(
    "fake_tv", pathlib.Path(__file__).with_name("fake_tv.py")
)
fake_tv = importlib.util.module_from_spec(_SPEC)
assert _SPEC.loader is not None
_SPEC.loader.exec_module(fake_tv)


@pytest.fixture(autouse=True)
def auto_enable_custom_integrations(enable_custom_integrations):
    """Load custom_components/ for every test."""
    yield


@pytest.fixture(name="tv_port")
def fixture_tv_port() -> Iterator[int]:
    """A fake Halo TV on a free port, for one test; what it was sent is in fake_tv.RECEIVED."""
    fake_tv.reset()
    server = ThreadingHTTPServer(("127.0.0.1", 0), fake_tv.Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        yield server.server_address[1]
    finally:
        server.shutdown()
        server.server_close()


@pytest.fixture(name="fake_tv")
def fixture_fake_tv() -> Any:
    """The fake TV module, for reading what it was sent and what state it is in."""
    return fake_tv
