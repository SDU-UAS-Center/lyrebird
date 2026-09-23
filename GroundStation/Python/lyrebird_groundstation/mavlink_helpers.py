"""Pure MAVLink mapping helpers for GroundStation telemetry bridges."""

from __future__ import annotations

import math
from typing import Any

DISARMED_FLIGHT_MODES = {"", "MANUAL", "UNKNOWN"}


def is_armed_flight_mode(flight_mode: str | None) -> bool:
    """Return whether a DJI flight mode should be shown as armed to MAVLink clients."""
    return (flight_mode or "UNKNOWN") not in DISARMED_FLIGHT_MODES


def heartbeat_base_mode(
    armed: bool,
    satellite_count: int | float,
    custom_mode_enabled_flag: int,
    safety_armed_flag: int,
    stabilize_enabled_flag: int,
    guided_enabled_flag: int,
    gps_guided_satellite_threshold: int = 5,
) -> int:
    """Build MAVLink heartbeat base-mode flags from DJI state."""
    base_mode = custom_mode_enabled_flag
    if armed:
        base_mode |= safety_armed_flag
    if satellite_count > gps_guided_satellite_threshold:
        base_mode |= stabilize_enabled_flag | guided_enabled_flag
    return base_mode


def gps_fix_type(satellite_count: int | float) -> int:
    """Map satellite count to MAVLink GPS fix type."""
    if satellite_count >= 6:
        return 3
    if satellite_count >= 4:
        return 2
    return 0


def ground_speed_mps(speed: dict[str, Any]) -> float:
    """Calculate horizontal ground speed from DJI x/y velocity components."""
    vx = float(speed.get("x", 0) or 0)
    vy = float(speed.get("y", 0) or 0)
    return math.sqrt(vx**2 + vy**2)


def climb_rate_mps(speed: dict[str, Any]) -> float:
    """Convert DJI z velocity to MAVLink climb convention."""
    return -float(speed.get("z", 0) or 0)
