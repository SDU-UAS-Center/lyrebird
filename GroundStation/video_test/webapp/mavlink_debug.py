"""MAVLink debugging surface for the webapp.

Exists because of how this project's MAVLink defects have actually been found. None of them came
from reading code: they came from reading the same aircraft over both wires at the same moment and
looking at where the two disagreed. That comparison caught a camera heartbeat overwriting the
flight mode, a gimbal message with two fields transposed, a home-position gate on the wrong latch,
and a heading reported in the wrong convention.

This makes that comparison a permanent instrument rather than a script someone rewrites each time.
It samples both transports against one drone and reports, field by field, what each wire says and
whether they agree.
"""

from __future__ import annotations

import threading
import time
from typing import Any

from lyrebird_groundstation.dji_client import DJIInterface
from lyrebird_groundstation.transport import Transport

#: How long a sampler stays alive after the last request for it. A debugging page that is closed
#: should not leave a socket bound to the aircraft's telemetry port forever.
IDLE_TIMEOUT_S = 120.0

#: Fields where a small numeric difference is sampling noise rather than disagreement.
NUMERIC_TOLERANCE = 1.0

#: Keys that name the same value on the two wires. The comparison is the project's gap-finder,
#: so a value HTTP reports twice under two names must not read as a field MAVLink is missing.
KEY_ALIASES = {
    # HTTP reports battery as both batteryLevel and remainingCharge; MAVLink uses batteryLevel.
    "remainingCharge": "batteryLevel",
}

#: How many (HTTP, MAVLink) pairs each comparison samples, and the pause between them.
#:
#: Each wire is a cache of its latest frame, updated at its own message rate, so a single read is
#: skewed by however far the aircraft moved between the two wires' last updates. That skew is
#: exactly how the gimbal rows read "differ" while the aircraft was moving -- both wires carried
#: the same value, sampled a few milliseconds apart. A burst lets both caches advance; a real
#: wire disagreement survives every pair, while sampling skew does not (some pair lands on the
#: same underlying value). A field therefore agrees when the two wires agreed on any pair.
#:
#: The window must span several refresh cycles of BOTH wires, or every pair returns the same
#: stale values and the burst degrades into a single skewed read (measured on the aircraft:
#: MAVLink gimbal GIMBAL_DEVICE_ATTITUDE_STATUS refreshes every 200ms, HTTP telemetry every
#: 500ms). 8 pairs at 250ms spans ~1.75s -- ~3 HTTP advances and ~8 MAVLink advances -- so
#: enough cross pairs exist to land on aligned values even while the aircraft is moving.
COMPARISON_BURST_PAIRS = 8
COMPARISON_BURST_PAUSE_S = 0.25


class _Comparison:
    """One drone read over both wires at once."""

    def __init__(self, ip: str, mavlink_port: int, peer_port: int):
        self.ip = ip
        self.touched = time.monotonic()
        self._lock = threading.Lock()
        self._http = DJIInterface(ip, transport=Transport.HTTP)
        self._mavlink = DJIInterface(
            ip,
            transport=Transport.MAVLINK,
            mavlink_port=mavlink_port,
            mavlink_peer_port=peer_port,
        )
        self._http.startTelemetryStream()
        self._mavlink.startTelemetryStream()

    def snapshot(self) -> dict[str, Any]:
        self.touched = time.monotonic()
        pairs: list[tuple[dict[str, Any], dict[str, Any]]] = []
        for pair_index in range(COMPARISON_BURST_PAIRS):
            with self._lock:
                http = self._http.getTelemetry()
                mavlink = self._mavlink.getTelemetry()
            pairs.append((http, mavlink))
            if pair_index < COMPARISON_BURST_PAIRS - 1:
                time.sleep(COMPARISON_BURST_PAUSE_S)
        latest_http, latest_mavlink = pairs[-1]
        return {
            "ip": self.ip,
            "httpKeys": len(latest_http),
            "mavlinkKeys": len([k for k in latest_mavlink if not k.startswith("_")]),
            "rows": _rows(pairs),
        }

    def close(self) -> None:
        self._http.stopTelemetryStream()
        self._mavlink.stopTelemetryStream()


def _rows(pairs: list[tuple[dict[str, Any], dict[str, Any]]]) -> list[dict[str, Any]]:
    """One row per field, ordered so disagreements come first."""
    http_by_key: dict[str, list[Any]] = {}
    mavlink_by_key: dict[str, list[Any]] = {}
    for http, mavlink in pairs:
        for key, value in http.items():
            if not key.startswith("_"):
                # Working state the transport keeps for itself, not telemetry.
                http_by_key.setdefault(KEY_ALIASES.get(key, key), []).append(value)
        for key, value in mavlink.items():
            if not key.startswith("_"):
                mavlink_by_key.setdefault(KEY_ALIASES.get(key, key), []).append(value)

    rows = []
    for key in sorted(set(http_by_key) | set(mavlink_by_key)):
        http_values = http_by_key.get(key, [])
        mavlink_values = mavlink_by_key.get(key, [])
        rows.append(
            {
                "key": key,
                "http": _render(http_values[-1] if http_values else None),
                "mavlink": _render(mavlink_values[-1] if mavlink_values else None),
                "status": _status(http_values, mavlink_values),
            }
        )
    order = {"differ": 0, "mavlinkOnly": 1, "httpOnly": 2, "agree": 3}
    rows.sort(key=lambda r: (order.get(r["status"], 9), r["key"]))
    return rows


def _status(http_values: list[Any], mavlink_values: list[Any]) -> str:
    if not mavlink_values:
        return "httpOnly"
    if not http_values:
        return "mavlinkOnly"
    # Agree when the wires ever agreed during the burst: a difference that is only sampling skew
    # disappears on the pair where both caches hold the same underlying value, while a genuine
    # disagreement is there on every pair.
    agreed = any(_close_enough(h, m) for h in http_values for m in mavlink_values)
    return "agree" if agreed else "differ"


def _close_enough(a: Any, b: Any) -> bool:
    if a == b:
        return True
    if isinstance(a, bool) or isinstance(b, bool):
        return bool(a) == bool(b)
    if isinstance(a, (int, float)) and isinstance(b, (int, float)):
        return abs(a - b) <= NUMERIC_TOLERANCE
    if isinstance(a, dict) and isinstance(b, dict):
        keys = set(a) | set(b)
        return all(_close_enough(a.get(k), b.get(k)) for k in keys)
    return False


def _render(value: Any) -> str:
    if value is None:
        return "—"
    if isinstance(value, float):
        return f"{value:.4g}"
    if isinstance(value, dict):
        return ", ".join(f"{k}={_render(v)}" for k, v in sorted(value.items()))
    return str(value)


class ComparisonRegistry:
    """Keeps one sampler per drone, and reaps them when the page stops asking."""

    def __init__(self, mavlink_port: int, peer_port: int):
        self._mavlink_port = mavlink_port
        self._peer_port = peer_port
        self._lock = threading.Lock()
        self._samplers: dict[str, _Comparison] = {}

    def snapshot(self, name: str, ip: str) -> dict[str, Any]:
        with self._lock:
            self._reap()
            sampler = self._samplers.get(name)
            if sampler is not None and sampler.ip != ip:
                # The drone moved networks; the old sampler is bound to an address that is no
                # longer it, and would sit reporting nothing forever.
                sampler.close()
                sampler = None
            if sampler is None:
                sampler = _Comparison(ip, self._mavlink_port, self._peer_port)
                self._samplers[name] = sampler
        return sampler.snapshot()

    def _reap(self) -> None:
        now = time.monotonic()
        for name, sampler in list(self._samplers.items()):
            if now - sampler.touched > IDLE_TIMEOUT_S:
                sampler.close()
                del self._samplers[name]

    def close_all(self) -> None:
        with self._lock:
            for sampler in self._samplers.values():
                sampler.close()
            self._samplers.clear()
