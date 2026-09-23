"""Pure event and discovery helpers for the video test webapp."""

from __future__ import annotations

import json
from collections.abc import Callable
from typing import Any

from lyrebird_groundstation.dji_helpers import (
    parse_discovery_response as _parse_discovery_response,
)


def build_event_entry(
    event_type: str,
    payload: dict[str, Any],
    timestamp_factory: Callable[[], str],
) -> dict[str, Any]:
    """Build one event-log entry with stable core fields."""
    return {"ts": timestamp_factory(), "type": event_type, **payload}


def serialize_ndjson_entry(entry: dict[str, Any]) -> str:
    """Serialize one event-log entry as compact NDJSON."""
    return json.dumps(entry, separators=(",", ":")) + "\n"


def format_sse_message(message_type: str, payload: Any) -> bytes:
    """Serialize one server-sent event message."""
    return f"data: {json.dumps({'type': message_type, 'payload': payload})}\n\n".encode()


def parse_discovery_response(message: str, remote_ip: str) -> dict[str, str] | None:
    """Parse a Lyrebird discovery response for the video grid.

    The aircraft always replies ``LYREBIRD_HERE:<ip>:<name>`` (see
    ``LyrebirdDiscoveryManager.kt``), so the parsing lives in the shared
    ``lyrebird_groundstation.dji_helpers``; this only reshapes the result
    into the dict form the webapp consumes.
    """
    parsed = _parse_discovery_response(message, fallback_ip=remote_ip)
    if parsed is None:
        return None
    return {"ip": parsed.ip_address, "name": parsed.name}
