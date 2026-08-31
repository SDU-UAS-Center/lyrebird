"""Which wire the ground station talks to Lyrebird over.

Lyrebird has spoken HTTP since the beginning: commands are POSTs to port 8080, telemetry is a
JSON stream on 8081. The MAVLink endpoint is newer and covers a growing subset of the same ground.
Rather than fork the client, this module lets the existing :class:`DJIInterface` swap the wire
underneath itself, because the whole surface funnels through exactly two chokepoints — the
telemetry dictionary and ``requestSend`` — and everything above them (every getter, every ROS
publisher, every script) reads that dictionary and calls those methods without caring how they
were filled.

Select the wire with the ``LB_TRANSPORT`` environment variable:

``both``
    The default. Telemetry from MAVLink, commands over MAVLink where it has an equivalent and
    HTTP where it does not (media download, and anything else MAVLink has no form for yet). MAVLink
    is the lighter wire, so it is preferred wherever it can carry the whole request; HTTP fills the
    gaps rather than being the primary path.
``mavlink``
    Telemetry from the MAVLink stream, commands as MAVLink only. Anything MAVLink does not yet
    implement is **refused, not quietly retried over HTTP** — the point of this mode is to make
    the remaining gaps visible, and a silent fallback would hide exactly what we are trying to
    measure.
``http``
    Nothing touches MAVLink. Kept for ground stations built against this project's predecessor,
    WildBridge, and for links where only HTTP is reachable.
"""

from __future__ import annotations

import hashlib
import math
import os
import socket
import struct
import threading
import time
from collections.abc import Callable
from contextlib import suppress
from enum import Enum
from typing import Any

from lyrebird_groundstation.mavlink_helpers import gps_fix_type  # noqa: F401

ENV_VAR = "LB_TRANSPORT"

#: MAV_COMP_ID_AUTOPILOT1. Vehicle state is only believed from this component.
COMP_ID_AUTOPILOT1 = 1

#: MAVLink 2 frame marker.
MAVLINK2_MAGIC = 0xFD
DEFAULT_MAVLINK_PORT = 14550

#: How often this ground station announces itself. 1 Hz is the usual MAVLink heartbeat rate.
GCS_HEARTBEAT_PERIOD_S = 1.0

#: Returned by the MAVLink command channel when an endpoint has no MAVLink equivalent. Callers
#: parse command replies as strings, so this is a string rather than an exception: it flows
#: through the same path a rejection from the aircraft would.
UNSUPPORTED_PREFIX = "REJECTED: no MAVLink equivalent for"

#: Sent as the first line on the TCP telemetry socket by a ``both``-transport client, to ask the
#: aircraft for the trimmed, MAVLink-gap-only stream instead of the full one -- see
#: ``TelemetryServer.MODE_GAP_REQUEST`` on the Android side. An aircraft build that predates gap
#: mode simply ignores an unrecognised first line and keeps sending the full stream, so this is
#: safe to send unconditionally in ``both`` mode.
TCP_GAP_MODE_REQUEST = b"MODE=GAP\n"

#: Endpoints that are deliberately HTTP-only. Absence from ``_COMMAND_MAP`` is the mechanism; this
#: registry makes the reason visible to a reviewer and pins it with a test, so a future change
#: cannot silently turn one of these into "unsupported by accident".
HTTP_ONLY_BY_DESIGN: dict[str, str] = {
    "/send/rcPairing/start": (
        "RC pairing is a hardware handshake between the remote and the aircraft; no MAVLink form."
    ),
    "/send/rcPairing/stop": (
        "RC pairing is a hardware handshake between the remote and the aircraft; no MAVLink form."
    ),
    # Media stays on HTTP even though MAVLink FTP can list and read the card: FTP moves 239
    # bytes per request-reply round trip (tens of KB/s at best), so a multi-megabyte JPEG over
    # FTP is minutes of stop-and-wait. HTTP over the same Wi-Fi saturates the link (MB/s), so
    # it is the transfer path for media. MAVLink FTP exists for small transfers and for links
    # where only MAVLink is reachable; it is a complement, not the replacement, for HTTP media.
    "/send/listMedia": "Media listing walks the SD card; HTTP stays the fast path for media on Wi-Fi.",
    "/send/downloadMediaByName": "Media download streams a file off the SD card; HTTP stays the fast path for media on Wi-Fi.",
}


def mavlink_port_from_env(environ: dict[str, str] | None = None) -> int:
    """UDP port this ground station listens on, from ``LB_MAVLINK_PORT``.

    Configurable for two reasons, and both bite in practice. A fleet needs one port per aircraft,
    because only one socket receives a given UDP port's packets. And a second ground station on
    the same machine -- QGroundControl beside this one -- needs its own port for the same reason:
    sharing 14550 means the two compete for datagrams and each sees roughly half the telemetry,
    which looks like one of them being frozen rather than like a conflict.

    The aircraft fans its telemetry out to every ground station it has heard from, so running on
    a different port is all it takes for both to be fed.
    """
    return _port_from_env("LB_MAVLINK_PORT", DEFAULT_MAVLINK_PORT, environ)


def mavlink_peer_port_from_env(environ: dict[str, str] | None = None) -> int:
    """UDP port the *aircraft* listens on, from ``LB_MAVLINK_PEER_PORT``.

    Separate from the listen port: this ground station may listen on 14551 while the aircraft
    still accepts commands on 14550. Conflating the two sends every command to a port nothing is
    bound to, which fails silently.
    """
    return _port_from_env("LB_MAVLINK_PEER_PORT", DEFAULT_MAVLINK_PORT, environ)


def _port_from_env(name: str, default: int, environ: dict[str, str] | None) -> int:
    raw = (environ if environ is not None else os.environ).get(name, "").strip()
    if not raw:
        return default
    try:
        return int(raw)
    except ValueError:
        raise ValueError(f"{name}={raw!r} is not a port number") from None


class Transport(Enum):
    """Which wire to use."""

    HTTP = "http"
    MAVLINK = "mavlink"
    BOTH = "both"

    @classmethod
    def from_env(cls, environ: dict[str, str] | None = None) -> Transport:
        """Read ``LB_TRANSPORT``, defaulting to BOTH.

        An unrecognised value is a configuration mistake worth failing on rather than silently
        treating as the default: a typo'd ``LB_TRANSPORT=mavlnk`` that quietly ran over HTTP-only
        would produce a test result that looks like a passing MAVLink run.
        """
        raw = (environ if environ is not None else os.environ).get(ENV_VAR, "").strip().lower()
        if not raw:
            return cls.BOTH
        try:
            return cls(raw)
        except ValueError:
            valid = ", ".join(t.value for t in cls)
            raise ValueError(f"{ENV_VAR}={raw!r} is not one of: {valid}") from None

    @property
    def uses_mavlink(self) -> bool:
        return self in (Transport.MAVLINK, Transport.BOTH)

    @property
    def allows_http_fallback(self) -> bool:
        """Whether a command with no MAVLink equivalent may go out over HTTP instead."""
        return self is not Transport.MAVLINK


# --------------------------------------------------------------------------------------------
# Telemetry
# --------------------------------------------------------------------------------------------


def _quat_to_euler_deg(q: tuple[float, ...]) -> tuple[float, float, float]:
    """Convert a MAVLink attitude quaternion to DJI's degrees. Unused today, kept for gimbals."""
    w, x, y, z = q
    roll = math.atan2(2 * (w * x + y * z), 1 - 2 * (x * x + y * y))
    pitch = math.asin(max(-1.0, min(1.0, 2 * (w * y - z * x))))
    yaw = math.atan2(2 * (w * z + x * y), 1 - 2 * (y * y + z * z))
    return math.degrees(roll), math.degrees(pitch), math.degrees(yaw)


#: PX4 packed custom_mode values (``(main << 16) | (sub << 24)``) back to the mode names the HTTP
#: telemetry stream reports. The aircraft claims MAV_AUTOPILOT_PX4 so that QGroundControl enables
#: its guided-mode buttons, which means custom_mode arrives in PX4's packing rather than DJI's
#: names; this table is the inverse of MavlinkFlightMode on the aircraft side.
PX4_MODE_NAMES = {
    1 << 16: "MANUAL",
    2 << 16: "ATTI",
    3 << 16: "GPS_NORMAL",
    6 << 16: "VIRTUAL_STICK",
    (3 << 16) | (1 << 24): "POI",
    (4 << 16) | (4 << 24): "WAYPOINT",
    (4 << 16) | (2 << 24): "AUTO_TAKE_OFF",
    (4 << 16) | (6 << 24): "AUTO_LANDING",
    (4 << 16) | (5 << 24): "GO_HOME",
    (4 << 16) | (8 << 24): "SMART_FLIGHT",
}


def flight_mode_name(custom_mode: int) -> str:
    """Name for a PX4-packed custom_mode, or UNKNOWN when it is not one we advertise."""
    return PX4_MODE_NAMES.get(int(custom_mode), "UNKNOWN")


def _dji_heading(hdg_centidegrees: int) -> float:
    """MAVLink heading (0..360) in DJI's convention (-180..180).

    Both describe the same bearing, but a consumer comparing the two -- or switching transports
    mid-flight -- would see a 360-degree jump at north if the conventions were left to differ.
    65535 is MAVLink's "unknown".
    """
    if hdg_centidegrees == 65535:
        return 0.0
    degrees = hdg_centidegrees / 100.0
    return degrees - 360.0 if degrees > 180.0 else degrees


def _from_heartbeat(telemetry: dict[str, Any], msg: Any) -> bool:
    # A Lyrebird aircraft emits more than one heartbeat: the autopilot's, and the camera
    # component's at MAV_COMP_ID_CAMERA. The camera's custom_mode is a meaningless zero, so taking
    # whichever arrived last silently reports UNKNOWN for a drone in position hold. QGroundControl
    # filters the same way (MultiVehicleManager drops any heartbeat whose component is not
    # MAV_COMP_ID_AUTOPILOT1) and for the same reason.
    if msg.get_header().srcComponent != COMP_ID_AUTOPILOT1:
        return False
    telemetry["flightMode"] = flight_mode_name(msg.custom_mode)
    return True


def _from_global_position(telemetry: dict[str, Any], msg: Any) -> bool:
    telemetry["location"] = {
        "latitude": msg.lat / 1e7,
        "longitude": msg.lon / 1e7,
        # DJI reports altitude above takeoff, which is MAVLink's relative_alt.
        "altitude": msg.relative_alt / 1000.0,
    }
    # The HTTP stream carries altitude both nested and at the top level; consumers read both.
    telemetry["altitude"] = msg.relative_alt / 1000.0
    telemetry["heading"] = _dji_heading(msg.hdg)
    telemetry["speed"] = {
        "x": msg.vx / 100.0,
        "y": msg.vy / 100.0,
        # DJI's z is positive-down, and so is MAVLink's vz.
        "z": msg.vz / 100.0,
    }
    return True


def _from_attitude(telemetry: dict[str, Any], msg: Any) -> bool:
    telemetry["attitude"] = {
        "roll": math.degrees(msg.roll),
        "pitch": math.degrees(msg.pitch),
        "yaw": math.degrees(msg.yaw),
    }
    return True


def _from_gps_raw(telemetry: dict[str, Any], msg: Any) -> bool:
    telemetry["satelliteCount"] = msg.satellites_visible
    return True


def _from_sys_status(telemetry: dict[str, Any], msg: Any) -> bool:
    telemetry["batteryLevel"] = msg.battery_remaining
    return True


def _from_battery_status(telemetry: dict[str, Any], msg: Any) -> bool:
    telemetry["batteryLevel"] = msg.battery_remaining
    if getattr(msg, "time_remaining", 0):
        telemetry["remainingFlightTime"] = msg.time_remaining
    return True


def _from_home_position(telemetry: dict[str, Any], msg: Any) -> bool:
    telemetry["homeLocation"] = {
        "latitude": msg.latitude / 1e7,
        "longitude": msg.longitude / 1e7,
        "altitude": msg.altitude / 1000.0,
    }
    # Deliberately does not set homeSet. The aircraft sends HOME_POSITION as soon as it knows
    # where home is, which is well before its "home recorded on this flight" latch closes;
    # inferring the latch from the message's arrival would report it set when HTTP reports it
    # clear. LYREBIRD_STATUS carries the latch itself.
    return True


def _from_vfr_hud(telemetry: dict[str, Any], msg: Any) -> bool:
    telemetry["speed"] = dict(telemetry.get("speed", {}), z=-msg.climb)
    telemetry["altitude"] = msg.alt
    return True


def _from_capture_status(telemetry: dict[str, Any], msg: Any) -> bool:
    # video_status: 0 idle, 1 capturing.
    telemetry["isRecording"] = msg.video_status == 1
    telemetry.setdefault("zoomRatio", 1.0)
    return True


def _from_mission_current(telemetry: dict[str, Any], msg: Any) -> bool:
    telemetry["missionCurrent"] = msg.seq
    telemetry["missionState"] = msg.mission_state
    return True


def _from_gimbal_attitude(telemetry: dict[str, Any], msg: Any) -> bool:
    """Gimbal pointing, from the standard gimbal v2 report.

    The quaternion is kept as well as the euler angles, because the joint attitude DJI reports
    separately is the gimbal's rotation *relative to the aircraft*, and recovering it needs the
    rotation rather than the angles. See _derive.
    """
    quaternion = tuple(float(v) for v in msg.q)
    roll, pitch, yaw = _quat_to_euler_deg(quaternion)
    telemetry["gimbalAttitude"] = {"roll": roll, "pitch": pitch, "yaw": yaw}
    telemetry["_gimbalQuaternion"] = quaternion
    delta_yaw = getattr(msg, "delta_yaw", None)
    if delta_yaw is not None and not math.isnan(delta_yaw):
        telemetry["_gimbalJointYaw"] = math.degrees(delta_yaw)
    return True


def _from_distance_sensor(telemetry: dict[str, Any], msg: Any) -> bool:
    telemetry["lrfDistance"] = msg.current_distance / 100.0
    return True


def _from_mission_item_reached(telemetry: dict[str, Any], msg: Any) -> bool:
    # The mission protocol's arrival report is the MAVLink-side equivalent of the reach latch the
    # HTTP surface exposes as waypointReached/waypointSeq.
    telemetry["waypointReached"] = True
    telemetry["waypointSeq"] = msg.seq
    return True


#: One handler per message we translate. A table rather than a chain of branches, matching how
#: mavlink_listen.py dispatches, and so that adding a message is a single entry.
_TELEMETRY_HANDLERS: dict[str, Callable[[dict[str, Any], Any], bool]] = {
    "HEARTBEAT": _from_heartbeat,
    "GLOBAL_POSITION_INT": _from_global_position,
    "ATTITUDE": _from_attitude,
    "GPS_RAW_INT": _from_gps_raw,
    "SYS_STATUS": _from_sys_status,
    "BATTERY_STATUS": _from_battery_status,
    "HOME_POSITION": _from_home_position,
    "VFR_HUD": _from_vfr_hud,
    "CAMERA_CAPTURE_STATUS": _from_capture_status,
    "MISSION_CURRENT": _from_mission_current,
    "MISSION_ITEM_REACHED": _from_mission_item_reached,
    "GIMBAL_DEVICE_ATTITUDE_STATUS": _from_gimbal_attitude,
    "DISTANCE_SENSOR": _from_distance_sensor,
}

#: LYREBIRD_STATUS, from GroundStation/mavlink/lyrebird.xml. Decoded by hand rather than by
#: shipping a generated dialect: it is one message, and a generated module would have to be kept
#: in step in two repositories. The offsets below are mavgen's field order for that definition.
LYREBIRD_STATUS_ID = 42100
#: mavgen's unpacker format for LYREBIRD_STATUS, copied from the generated dialect rather than
#: worked out by hand, and pinned by a test that regenerates it from the XML.
LYREBIRD_STATUS_STRUCT = "<IiiffIIIHHHhhHHHBBB24s"
LYREBIRD_STATUS_SIZE = 75

LB_FLAG_MANUAL_OVERRIDE = 1
LB_FLAG_READY_TO_TAKEOFF = 2
LB_FLAG_HOME_SET = 4
LB_FLAG_LRF_TARGET_VALID = 8
LB_FLAG_WAYPOINT_REACHED = 16
LB_FLAG_YAW_REACHED = 32
LB_FLAG_ALTITUDE_REACHED = 64


def decode_lyrebird_status(payload: bytes) -> dict[str, Any]:
    """Turn a LYREBIRD_STATUS payload into the telemetry keys the HTTP stream uses."""
    # MAVLink 2 truncates trailing zeros, so pad before unpacking fixed offsets.
    padded = payload.ljust(LYREBIRD_STATUS_SIZE, b"\x00")
    (
        _time_boot_ms,
        lrf_lat,
        lrf_lon,
        lrf_alt,
        max_radius,
        waypoint_seq,
        yaw_seq,
        altitude_seq,
        time_to_home,
        time_to_land,
        total_time,
        joint_pitch,
        joint_roll,
        zoom_fl,
        optical_fl,
        hybrid_fl,
        battery_to_home,
        battery_to_land,
        flags,
        reason_bytes,
    ) = struct.unpack(LYREBIRD_STATUS_STRUCT, padded)
    reason = reason_bytes.split(b"\x00")[0].decode("ascii", "replace")

    status: dict[str, Any] = {
        "isManualOverrideActive": bool(flags & LB_FLAG_MANUAL_OVERRIDE),
        "readyToTakeoff": bool(flags & LB_FLAG_READY_TO_TAKEOFF),
        "homeSet": bool(flags & LB_FLAG_HOME_SET),
        "takeoffBlockReason": reason or "UNKNOWN",
        "maxRadiusCanFlyAndGoHome": max_radius,
        "timeNeededToGoHome": time_to_home,
        "timeNeededToLand": time_to_land,
        "totalTime": total_time,
        # DJI's own joint angles, not a derivation. Composing the two attitudes MAVLink already
        # carries recovers the right sign and slope but lands ~1.5 degrees out, because the joint
        # angles include a mounting offset the world attitude does not describe -- measured over a
        # hand-tilted sweep of a real aircraft.
        "_gimbalJointPitch": joint_pitch / 100.0,
        "_gimbalJointRoll": joint_roll / 100.0,
        # The wire uses 0 for "the payload does not report this"; the HTTP stream uses -1, and
        # a consumer switching wires should not see a focal length appear out of nowhere.
        "zoomFl": zoom_fl or -1,
        "opticalFl": optical_fl or -1,
        "hybridFl": hybrid_fl or -1,
        "batteryNeededToGoHome": battery_to_home,
        "batteryNeededToLand": battery_to_land,
        # Reported as state, so a ground station that missed a completion ack still converges on
        # the truth. The seq is what scopes each flag to the movement that earned it.
        "waypointReached": bool(flags & LB_FLAG_WAYPOINT_REACHED),
        "waypointSeq": waypoint_seq,
        "yawReached": bool(flags & LB_FLAG_YAW_REACHED),
        "yawSeq": yaw_seq,
        "altitudeReached": bool(flags & LB_FLAG_ALTITUDE_REACHED),
        "altitudeSeq": altitude_seq,
    }
    # Only a locked reading has a target; reporting 0,0 would put it off West Africa.
    status["lrfTarget"] = (
        [lrf_lat / 1e7, lrf_lon / 1e7, lrf_alt] if flags & LB_FLAG_LRF_TARGET_VALID else None
    )
    return status


def _derive(telemetry: dict[str, Any]) -> None:
    """Fill the keys that are a function of others rather than of a message.

    ``distanceToHome`` is the clear case: both endpoints are already on the wire, so asking the
    aircraft to send a third number that is computable from the first two would be a message
    defined to save a square root.
    """
    _derive_gimbal_joint(telemetry)

    location = telemetry.get("location") or {}
    home = telemetry.get("homeLocation") or {}
    # Gated on the coordinates being a real place rather than on the homeSet latch: the aircraft
    # knows where home is before the latch closes, and a distance is useful from that moment. The
    # (0, 0) exclusion is what keeps an unset home from reporting 2,559 km to null island.
    home_is_real = bool(home) and (home.get("latitude") or home.get("longitude"))
    if location and home_is_real:
        telemetry["distanceToHome"] = _haversine_m(
            float(location.get("latitude", 0.0)),
            float(location.get("longitude", 0.0)),
            float(home.get("latitude", 0.0)),
            float(home.get("longitude", 0.0)),
        )
    else:
        telemetry.setdefault("distanceToHome", 0.0)


#: Sign of the derived joint attitude, for an aircraft that does not report its own.
#:
#: Settled by measurement rather than left open: a hand-tilted sweep of a real aircraft, 48
#: samples with the gimbal stabilising, fitted DJI's pitch against this composition at a slope of
#: +1.03. The sign is positive, and what remains is a constant offset of about 1.5 degrees, which
#: is why the aircraft now reports its joint angles directly and this is only a fallback.
GIMBAL_JOINT_SIGN = 1.0


def _derive_gimbal_joint(telemetry: dict[str, Any]) -> None:
    """Assemble the gimbal's attitude relative to the aircraft.

    Two messages carry the halves. LYREBIRD_STATUS reports the pitch and roll DJI itself
    reports, because composing the two world attitudes recovers the right sign and slope but
    lands about 1.5 degrees out -- the joint angles include a mounting offset the world attitude
    does not describe. The standard gimbal message carries the yaw as delta_yaw, which is exactly
    what that field means, so there is no reason to duplicate it.

    The composition below stays as a fallback for an aircraft that reports neither.
    """
    # Read, not popped. This runs after every message that changes anything, so consuming the
    # reported values meant the next ATTITUDE arriving replaced a measurement with the derivation
    # of it -- the panel showed 0.1 where the aircraft had plainly said 1.6.
    pitch = telemetry.get("_gimbalJointPitch")
    roll = telemetry.get("_gimbalJointRoll")
    yaw = telemetry.get("_gimbalJointYaw")

    if pitch is not None and roll is not None:
        telemetry["gimbalJointAttitude"] = {
            "roll": roll,
            "pitch": pitch,
            "yaw": yaw if yaw is not None else 0.0,
        }
        return

    quaternion = telemetry.get("_gimbalQuaternion")
    attitude = telemetry.get("attitude")
    if quaternion is None:
        return
    if not attitude:
        telemetry["gimbalJointAttitude"] = dict(telemetry.get("gimbalAttitude", {}))
        return

    body = _euler_deg_to_quat(
        float(attitude.get("roll", 0.0)),
        float(attitude.get("pitch", 0.0)),
        float(attitude.get("yaw", 0.0)),
    )
    derived_roll, derived_pitch, derived_yaw = _quat_to_euler_deg(
        _quat_mul(_quat_conjugate(body), quaternion)
    )
    telemetry["gimbalJointAttitude"] = {
        "roll": derived_roll * GIMBAL_JOINT_SIGN,
        "pitch": derived_pitch * GIMBAL_JOINT_SIGN,
        "yaw": yaw if yaw is not None else derived_yaw * GIMBAL_JOINT_SIGN,
    }


def _euler_deg_to_quat(roll: float, pitch: float, yaw: float) -> tuple[float, ...]:
    cr, sr = math.cos(math.radians(roll) / 2), math.sin(math.radians(roll) / 2)
    cp, sp = math.cos(math.radians(pitch) / 2), math.sin(math.radians(pitch) / 2)
    cy, sy = math.cos(math.radians(yaw) / 2), math.sin(math.radians(yaw) / 2)
    return (
        cr * cp * cy + sr * sp * sy,
        sr * cp * cy - cr * sp * sy,
        cr * sp * cy + sr * cp * sy,
        cr * cp * sy - sr * sp * cy,
    )


def _quat_conjugate(q: tuple[float, ...]) -> tuple[float, ...]:
    return (q[0], -q[1], -q[2], -q[3])


def _quat_mul(a: tuple[float, ...], b: tuple[float, ...]) -> tuple[float, ...]:
    aw, ax, ay, az = a
    bw, bx, by, bz = b
    return (
        aw * bw - ax * bx - ay * by - az * bz,
        aw * bx + ax * bw + ay * bz - az * by,
        aw * by - ax * bz + ay * bw + az * bx,
        aw * bz + ax * by - ay * bx + az * bw,
    )


def _haversine_m(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    """Great-circle distance in metres, matching the aircraft's own calculation."""
    radius = 6_371_000.0
    phi1, phi2 = math.radians(lat1), math.radians(lat2)
    dphi = math.radians(lat2 - lat1)
    dlambda = math.radians(lon2 - lon1)
    a = math.sin(dphi / 2) ** 2 + math.cos(phi1) * math.cos(phi2) * math.sin(dlambda / 2) ** 2
    return 2 * radius * math.asin(min(1.0, math.sqrt(a)))


def apply_mavlink_message(telemetry: dict[str, Any], msg: Any) -> bool:
    """Fold one decoded MAVLink message into the telemetry dictionary.

    The keys written here are deliberately the ones Lyrebird's HTTP telemetry stream already
    produces, so a consumer cannot tell which wire filled them. Where MAVLink carries a field the
    HTTP stream does not, it is dropped rather than invented under a new name -- a consumer that
    learns to read a MAVLink-only key stops working the moment the transport changes back.

    Returns True when the message changed anything, which is what drives the sequence counter.
    """
    handler = _TELEMETRY_HANDLERS.get(msg.get_type())
    return handler(telemetry, msg) if handler is not None else False


class MavlinkTelemetrySource:
    """Listens for the aircraft's MAVLink stream and keeps a telemetry dictionary current.

    Runs its own receive thread, like the HTTP telemetry reader it stands in for, and writes
    through the callback it is given so the owning client keeps a single lock over its state.
    """

    def __init__(
        self,
        port: int = DEFAULT_MAVLINK_PORT,
        bind_host: str = "",
        on_update: Callable[[dict[str, Any]], None] | None = None,
        peer_host: str = "",
        peer_port: int = DEFAULT_MAVLINK_PORT,
    ):
        self.port = port
        #: Where the aircraft listens. Distinct from [port], which is where we listen.
        self.peer_port = peer_port
        self.bind_host = bind_host
        #: Only accept packets from this aircraft. A fleet is the reason: several aircraft
        #: streaming to one port would be folded into a single telemetry dictionary, and the
        #: result would look like one drone teleporting between positions rather than like an
        #: error. Empty means accept anything, which is right for a single aircraft.
        self.peer_host = peer_host
        self._on_update = on_update
        self._telemetry: dict[str, Any] = {}
        self._socket: socket.socket | None = None
        self._thread: threading.Thread | None = None
        self._running = False
        #: Address the last packet came from, so commands can be sent back to the aircraft even
        #: when its address was never configured.
        self.peer: tuple[str, int] | None = None
        self._heartbeat_frame: bytes | None = None
        #: Targets gathered for the cycle currently arriving, and which cycle that is.
        self._detection_targets: list[dict[str, Any]] = []
        self._detection_frame = -1

    def start(self) -> None:
        if self._running:
            return
        self._running = True
        self._thread = threading.Thread(target=self._receive_loop, daemon=True)
        self._thread.start()

    def stop(self) -> None:
        self._running = False
        if self._socket is not None:
            with suppress(OSError):
                self._socket.close()
        if self._thread is not None:
            self._thread.join(timeout=2)

    def _receive_loop(self) -> None:
        parser = self._open_socket()
        next_heartbeat = 0.0
        while self._running:
            if self.peer_host and time.time() >= next_heartbeat:
                self._send_heartbeat()
                next_heartbeat = time.time() + GCS_HEARTBEAT_PERIOD_S
            self._receive_once(parser)

    def _open_socket(self) -> Any:
        from pymavlink.dialects.v20 import common as mavlink_common

        parser = mavlink_common.MAVLink(None)
        parser.robust_parsing = True

        self._socket = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self._socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self._socket.bind((self.bind_host, self.port))
        self._socket.settimeout(1.0)
        return parser

    def _receive_once(self, parser: Any) -> None:
        """Take one datagram and fold it into the telemetry state."""
        sock = self._socket
        if sock is None:
            self._running = False
            return
        try:
            data, addr = sock.recvfrom(2048)
        except TimeoutError:
            return
        except OSError:
            self._running = False
            return

        if self.peer_host and addr[0] != self.peer_host:
            return
        self.peer = addr

        changed = self._apply_lyrebird_status(data)
        for msg in parser.parse_buffer(data) or []:
            changed |= apply_mavlink_message(self._telemetry, msg)
        if changed:
            _derive(self._telemetry)
            if self._on_update is not None:
                self._on_update(dict(self._telemetry))

    def _send_heartbeat(self) -> None:
        """Announce this ground station to the aircraft.

        Standard MAVLink practice -- a ground station heartbeats like anything else on the link --
        and load-bearing here for a reason that is not obvious: the aircraft starts publishing
        video to whichever ground station it has heard from, and a transport that only ever
        listens is never heard from. Telemetry arrived fine without this; video did not.
        """
        from pymavlink.dialects.v20 import common as mavlink_common

        if self._heartbeat_frame is None:
            sink = _FrameSink()
            mav = mavlink_common.MAVLink(sink, srcSystem=255, srcComponent=190)
            mav.heartbeat_send(
                mavlink_common.MAV_TYPE_GCS,
                mavlink_common.MAV_AUTOPILOT_INVALID,
                0,
                0,
                mavlink_common.MAV_STATE_ACTIVE,
            )
            self._heartbeat_frame = sink.buf
        sock = self._socket
        if sock is None:
            return
        with suppress(OSError):
            sock.sendto(self._heartbeat_frame, (self.peer_host, self.peer_port))

    def _collect_target(self, data: bytes) -> bool:
        """Hold a target until its cycle is complete."""
        if not _checksum_ok(data, AUTOSENSING_TARGET_CRC_EXTRA):
            return False
        try:
            decoded = decode_autosensing_target(data[10 : 10 + data[1]])
        except struct.error:
            return False
        if decoded["frameId"] != self._detection_frame:
            # A new cycle: whatever was being gathered belonged to the last one.
            self._detection_frame = decoded["frameId"]
            self._detection_targets = []
        self._detection_targets.append(decoded["target"])
        # Nothing is published yet — the status message closes the cycle.
        return False

    def _publish_detection_cycle(self, data: bytes) -> bool:
        """Publish the cycle the status message describes.

        The count is what makes a cycle whole. Publishing on the count rather than on the last
        target arriving is also what reports an empty cycle, which is the thing that clears a
        stale overlay: no target messages arrive at all, so nothing else could.
        """
        if not _checksum_ok(data, AUTOSENSING_STATUS_CRC_EXTRA):
            return False
        try:
            status = decode_autosensing_status(data[10 : 10 + data[1]])
        except struct.error:
            return False

        gathered = self._detection_targets if status["frameId"] == self._detection_frame else []
        # Short means packets were lost; report what arrived rather than nothing.
        self._telemetry["detectedTargets"] = list(gathered[: status["count"]])
        self._telemetry["autoSensingActive"] = status["autoSensingActive"]
        self._telemetry["detectionSource"] = status["detectionSource"]
        self._telemetry["edgeConfidenceThreshold"] = status["edgeConfidenceThreshold"]
        self._detection_targets = []
        return True

    def _apply_lyrebird_status(self, data: bytes) -> bool:
        """Decode LYREBIRD_STATUS straight off the wire.

        pymavlink only parses ids it has definitions for, and generating a dialect module would
        put a build step and a second copy of the definition into every consumer. One message is
        cheaper to unpack by hand -- and lyrebird.xml stays the single source of truth, checked
        against this code by a test.
        """
        if len(data) < 12 or data[0] != MAVLINK2_MAGIC:
            return False
        message_id = int.from_bytes(data[7:10], "little")
        if message_id == AUTOSENSING_TARGET_ID:
            return self._collect_target(data)
        if message_id == AUTOSENSING_STATUS_ID:
            return self._publish_detection_cycle(data)
        if message_id == LYREBIRD_CONFIG_ID:
            if not _checksum_ok(data, LYREBIRD_CONFIG_CRC_EXTRA):
                return False
            with suppress(struct.error):
                self._telemetry.update(decode_lyrebird_config(data[10 : 10 + data[1]]))
                return True
            return False
        if message_id != LYREBIRD_STATUS_ID:
            return False
        if not _checksum_ok(data, LYREBIRD_STATUS_CRC_EXTRA):
            # An aircraft running an older build sends an older layout of this message, and
            # padding it out and decoding anyway produces confident nonsense: a string read
            # twelve bytes late, focal lengths that are really ASCII pairs. The CRC_EXTRA is
            # derived from the field definitions, so it changes when they do -- which makes it
            # exactly the check that tells the two versions apart. Refusing is the honest
            # outcome; the fields simply stay absent until the aircraft is updated.
            return False
        payload = data[10 : 10 + data[1]]
        try:
            self._telemetry.update(decode_lyrebird_status(payload))
        except struct.error:
            return False
        return True


# --------------------------------------------------------------------------------------------
# Commands
# --------------------------------------------------------------------------------------------

# MAV_CMD numbers, matching the set MavlinkTelemetryEndpoint actually handles. Anything absent
# here is absent on the aircraft too, so listing more would only move the refusal one hop later.
CMD_NAV_RETURN_TO_LAUNCH = 20
CMD_NAV_LAND = 21
CMD_NAV_TAKEOFF = 22
CMD_CONDITION_YAW = 115
CMD_DO_REPOSITION = 192
CMD_MISSION_START = 300
CMD_DO_GIMBAL_MANAGER_PITCHYAW = 1000
CMD_IMAGE_START_CAPTURE = 2000
CMD_VIDEO_START_CAPTURE = 2500
CMD_VIDEO_STOP_CAPTURE = 2501
CMD_SET_CAMERA_ZOOM = 531

CMD_DO_SET_MODE = 176
CMD_CONDITION_CHANGE_ALT = 113
CMD_DO_GRIPPER = 211
CMD_USER_1 = 31010
CMD_USER_2 = 31011
CMD_MISSION_START = 300

#: PX4 packed custom_mode values the aircraft advertises, used to request a mode.
PX4_MODE_POSCTL = 3 << 16
PX4_MODE_OFFBOARD = 6 << 16

#: USER_1 / USER_2 selectors, carried in param1. Kept in step with Mav in MavlinkProtocol.kt.
USER1_GIMBAL_RELATIVE = 1.0
USER1_RELEASE_MANUAL_OVERRIDE = 2.0
USER1_AUTOSENSING_START = 3.0
USER1_AUTOSENSING_STOP = 4.0
USER1_RELEASE_SAFETY = 3.0
USER2_LRF_MEASURE = 1.0
USER2_CAPTURE_TEMPERATURE = 2.0
USER2_CAPTURE_THERMAL_IMAGE = 3.0

GRIPPER_ACTION_RELEASE = 0

MAV_RESULT_ACCEPTED = 0
MAV_RESULT_IN_PROGRESS = 5
MAV_RESULT_CANCELLED = 6

#: MAV_PARAM_EXT_TYPE_CUSTOM: the value is an opaque byte string.
PARAM_EXT_TYPE_CUSTOM = 11
PARAM_ACK_ACCEPTED = 0


def _trim(field: Any) -> str:
    """A MAVLink fixed-width char field as the string it carries."""
    raw = field.decode("ascii", "replace") if isinstance(field, bytes) else str(field)
    return raw.split("\x00")[0].strip()


#: Replies this channel waits on. Everything else in the stream belongs to the telemetry source.
_INTERESTING_REPLIES = frozenset(
    {
        "COMMAND_ACK",
        "PARAM_VALUE",
        "PARAM_EXT_ACK",
        "PARAM_EXT_VALUE",
        "MISSION_REQUEST_INT",
        "MISSION_REQUEST",
        "MISSION_ACK",
    }
)

#: Most recent replies kept while nothing is waiting for them.
_INBOX_LIMIT = 64


def _ack_matcher(command: int) -> Callable[[Any], bool]:
    def matches(msg: Any) -> bool:
        return msg.get_type() == "COMMAND_ACK" and msg.command == command

    return matches


#: How long to wait for a moving command to report arrival. A leg takes as long as the distance
#: and the speed say it does, so this is generous; the aircraft applies its own bound too.
COMPLETION_TIMEOUT_S = 300.0

#: MAV_FRAME_GLOBAL_RELATIVE_ALT_INT: altitudes are metres above the home point, which is what
#: DJI reports and what every Lyrebird altitude already means.
MAV_FRAME_GLOBAL_RELATIVE_ALT_INT = 6

#: Endpoints whose MAVLink form is a plain COMMAND_LONG. The builder turns the HTTP payload
#: string into the seven command parameters, so the mapping stays in one readable place.
_COMMAND_MAP: dict[str, Callable[[str], tuple[int, list[float]]]] = {}


def _numbers(payload: str) -> list[float]:
    """The comma-separated numbers in an HTTP command payload."""
    return [float(part) for part in str(payload).split(",") if part.strip()]


def _register(endpoint: str) -> Callable[..., Any]:
    def wrap(
        fn: Callable[[str], tuple[int, list[float]]],
    ) -> Callable[[str], tuple[int, list[float]]]:
        _COMMAND_MAP[endpoint] = fn
        return fn

    return wrap


@_register("/send/takeoff")
def _takeoff(payload: str) -> tuple[int, list[float]]:
    # param7 is the requested altitude; 0 tells the aircraft to use its own default.
    altitude = _numbers(payload)[0] if _numbers(payload) else 0.0
    return CMD_NAV_TAKEOFF, [0, 0, 0, 0, 0, 0, altitude]


@_register("/send/land")
def _land(_payload: str) -> tuple[int, list[float]]:
    return CMD_NAV_LAND, [0] * 7


@_register("/send/RTH")
def _rth(_payload: str) -> tuple[int, list[float]]:
    return CMD_NAV_RETURN_TO_LAUNCH, [0] * 7


@_register("/send/gotoYaw")
def _goto_yaw(payload: str) -> tuple[int, list[float]]:
    return CMD_CONDITION_YAW, [_numbers(payload)[0], 0, 0, 0, 0, 0, 0]


@_register("/send/camera/zoom")
def _zoom(payload: str) -> tuple[int, list[float]]:
    # param1 selects the zoom type (2 = absolute ratio), param2 carries the value.
    return CMD_SET_CAMERA_ZOOM, [2, _numbers(payload)[0], 0, 0, 0, 0, 0]


@_register("/send/camera/startRecording")
def _start_recording(_payload: str) -> tuple[int, list[float]]:
    return CMD_VIDEO_START_CAPTURE, [0] * 7


@_register("/send/camera/stopRecording")
def _stop_recording(_payload: str) -> tuple[int, list[float]]:
    return CMD_VIDEO_STOP_CAPTURE, [0] * 7


@_register("/send/capture")
def _capture(_payload: str) -> tuple[int, list[float]]:
    # One frame, no interval, sequence number 1.
    return CMD_IMAGE_START_CAPTURE, [0, 0, 1, 1, 0, 0, 0]


@_register("/send/gimbal/pitch")
def _gimbal_pitch(payload: str) -> tuple[int, list[float]]:
    values = _numbers(payload)
    pitch = values[1] if len(values) > 1 else 0.0
    return CMD_DO_GIMBAL_MANAGER_PITCHYAW, [pitch, float("nan"), 0, 0, 0, 0, 0]


@_register("/send/gimbal/yaw")
def _gimbal_yaw(payload: str) -> tuple[int, list[float]]:
    values = _numbers(payload)
    yaw = values[2] if len(values) > 2 else 0.0
    return CMD_DO_GIMBAL_MANAGER_PITCHYAW, [float("nan"), yaw, 0, 0, 0, 0, 0]


#: Commands whose position must survive the trip. MAVLink defines COMMAND_INT precisely for
#: these: it carries x/y as int32 degE7 instead of float32, which at mid latitudes is the
#: difference between roughly 0.6 m of rounding error and none.
POSITION_COMMANDS = frozenset({CMD_DO_REPOSITION})


def _waypoint(payload: str, nose_forward: bool) -> tuple[int, list[float]]:
    """A single goto, as DO_REPOSITION.

    param4 carries the heading with the same meaning it has in a mission item: NaN asks the
    aircraft for nose-along-path, a value asks for that heading to be held. The aircraft honours
    the distinction in both places, so a goto does not change character depending on whether it
    arrived as a reposition or as a one-item plan.
    """
    latitude, longitude, altitude, yaw, speed = (_numbers(payload) + [0] * 5)[:5]
    heading = float("nan") if nose_forward else yaw
    return CMD_DO_REPOSITION, [speed, 0, 0, heading, latitude, longitude, altitude]


@_register("/send/abortMission")
@_register("/send/abortAll")
@_register("/send/abort/DJIMission")
def _abort(_payload: str) -> tuple[int, list[float]]:
    """Every abort is a mode change to position hold.

    The HTTP surface has three with different scopes; MAVLink expresses an abort as a mode, which
    has one meaning. The aircraft does the union of the three, so widening is the only direction
    this can be wrong in.
    """
    # param1 is base_mode with CUSTOM_MODE_ENABLED, param2 the PX4 custom mode.
    return CMD_DO_SET_MODE, [1, PX4_MODE_POSCTL, 0, 0, 0, 0, 0]


@_register("/send/enableVirtualStick")
def _enable_virtual_stick(_payload: str) -> tuple[int, list[float]]:
    # Offboard is the standard name for the mode that accepts stick input.
    return CMD_DO_SET_MODE, [1, PX4_MODE_OFFBOARD, 0, 0, 0, 0, 0]


@_register("/send/gotoAltitude")
def _goto_altitude(payload: str) -> tuple[int, list[float]]:
    # param1 is the climb rate, which DJI chooses; param7 the target altitude.
    return CMD_CONDITION_CHANGE_ALT, [0, 0, 0, 0, 0, 0, _numbers(payload)[0]]


@_register("/send/deactivateManualOverride")
def _release_override(_payload: str) -> tuple[int, list[float]]:
    return CMD_USER_1, [USER1_RELEASE_MANUAL_OVERRIDE, 0, 0, 0, 0, 0, 0]


@_register("/releaseSafetyControl")
def _release_safety(_payload: str) -> tuple[int, list[float]]:
    # USER1_RELEASE_SAFETY: the signed counterpart of seizing control. The aircraft refuses it
    # unless the frame is signed with the Safety Computer's key, so this only works for a
    # signing-enabled ground station.
    return CMD_USER_1, [USER1_RELEASE_SAFETY, 0, 0, 0, 0, 0, 0]


@_register("/send/gimbal/rel_pitch")
def _gimbal_rel_pitch(payload: str) -> tuple[int, list[float]]:
    values = _numbers(payload)
    pitch = values[1] if len(values) > 1 else 0.0
    return CMD_USER_1, [USER1_GIMBAL_RELATIVE, pitch, 0, 0, 0, 0, 0]


@_register("/send/gimbal/rel_yaw")
def _gimbal_rel_yaw(payload: str) -> tuple[int, list[float]]:
    values = _numbers(payload)
    yaw = values[2] if len(values) > 2 else 0.0
    return CMD_USER_1, [USER1_GIMBAL_RELATIVE, 0, yaw, 0, 0, 0, 0]


@_register("/send/autoSensing/start")
def _autosensing_start(_payload: str) -> tuple[int, list[float]]:
    return CMD_USER_1, [USER1_AUTOSENSING_START, 0, 0, 0, 0, 0, 0]


@_register("/send/autoSensing/stop")
def _autosensing_stop(_payload: str) -> tuple[int, list[float]]:
    return CMD_USER_1, [USER1_AUTOSENSING_STOP, 0, 0, 0, 0, 0, 0]


@_register("/send/lrf/measure")
def _lrf_measure(_payload: str) -> tuple[int, list[float]]:
    return CMD_USER_2, [USER2_LRF_MEASURE, 0, 0, 0, 0, 0, 0]


@_register("/send/captureTemperature")
def _capture_temperature(_payload: str) -> tuple[int, list[float]]:
    return CMD_USER_2, [USER2_CAPTURE_TEMPERATURE, 0, 0, 0, 0, 0, 0]


@_register("/send/captureThermalImage")
def _capture_thermal_image(_payload: str) -> tuple[int, list[float]]:
    return CMD_USER_2, [USER2_CAPTURE_THERMAL_IMAGE, 0, 0, 0, 0, 0, 0]


@_register("/send/drop")
def _drop(_payload: str) -> tuple[int, list[float]]:
    # param1 is the gripper instance, param2 the action. Lyrebird has one drop port.
    return CMD_DO_GRIPPER, [1, GRIPPER_ACTION_RELEASE, 0, 0, 0, 0, 0]


_COMMAND_MAP["/send/gotoWaypointNoseForward"] = lambda p: _waypoint(p, nose_forward=True)
_COMMAND_MAP["/send/gotoWaypointHoldHeading"] = lambda p: _waypoint(p, nose_forward=False)


def _send_stick(channel: MavlinkCommandChannel, payload: str, _timeout: float) -> str:
    """Stick input, as MANUAL_CONTROL.

    A message rather than a command, because that is what MAVLink defines sticks to be, and it is
    why nothing is waited for: MANUAL_CONTROL has no ack, and a stream of stick samples that each
    blocked on a reply would not be a stream.
    """
    left_x, left_y, right_x, right_y = (_numbers(payload) + [0.0] * 4)[:4]
    channel.send_manual_control(roll=right_x, pitch=right_y, throttle=left_y, yaw=left_x)
    return "ACCEPTED via MAVLink MANUAL_CONTROL"


def _send_trajectory(channel: MavlinkCommandChannel, payload: str, timeout: float) -> str:
    """A DJI native trajectory, as a mission upload followed by MISSION_START.

    This is the one endpoint whose MAVLink form is a conversation rather than a message. It is
    also the one that stops being special once the aircraft is configured with
    ``LB_MISSION_EXEC=dji_native``: the plan uploaded here is an ordinary MAVLink plan, and the
    aircraft decides it should be flown by DJI's wayline engine.
    """
    parts = str(payload).split(";")
    if len(parts) < 3:
        return "REJECTED: need a speed and at least two waypoints"
    try:
        speed = float(parts[0])
        waypoints = [tuple(float(v) for v in leg.split(",")) for leg in parts[1:]]
    except ValueError:
        return "REJECTED: malformed trajectory"

    if not channel.upload_plan(waypoints, speed, timeout):
        return "REJECTED: mission upload was not accepted"
    result, _ = channel._send_command(CMD_MISSION_START, [0, 0, 0, 0, 0, 0, 0], timeout)
    if result != MAV_RESULT_ACCEPTED:
        return f"REJECTED: MISSION_START returned MAV_RESULT {result}"
    return f"ACCEPTED via MAVLink mission, {len(waypoints)} waypoints"


def _send_text_parameter(endpoint: str):
    """A settings endpoint whose value is a string rather than a number."""

    def send(channel: MavlinkCommandChannel, payload: str, timeout: float) -> str:
        return channel.set_text_parameter(
            TEXT_PARAM_ENDPOINTS[endpoint], str(payload).strip(), timeout
        )

    return send


def _send_setting_parameter(endpoint: str):
    """A settings endpoint whose MAVLink form is a parameter write."""

    def send(channel: MavlinkCommandChannel, payload: str, timeout: float) -> str:
        text = str(payload).strip()
        # Booleans arrive as "true"/"false" on the HTTP surface and as 1/0 in a float parameter.
        if text.lower() in ("true", "false"):
            value = 1.0 if text.lower() == "true" else 0.0
        else:
            values = _numbers(text)
            if not values:
                return f"REJECTED: {endpoint} needs a numeric value over MAVLink"
            value = values[0]
        return channel.set_parameter(SETTING_PARAM_ENDPOINTS[endpoint], value, timeout)

    return send


def _send_setting(channel: MavlinkCommandChannel, payload: str, timeout: float) -> str:
    """A named setting write, as PARAM_SET.

    The HTTP payload is ``name,value``. Only settings the aircraft publishes as writable are
    accepted; anything else comes back refused rather than silently ignored.
    """
    name, _, raw = str(payload).partition(",")
    try:
        value = float(raw)
    except ValueError:
        return f"REJECTED: {name} needs a numeric value over MAVLink"
    return channel.set_parameter(SETTING_PARAMETERS.get(name.strip(), name.strip()), value, timeout)


#: Endpoints whose MAVLink form is not a single COMMAND_LONG.
_SPECIAL_SENDERS: dict[str, Any] = {
    "/send/stick": _send_stick,
    "/send/navigateTrajectoryDJINative": _send_trajectory,
    "/send/setSetting": _send_setting,
}

#: Settings the aircraft accepts a write to, keyed by the endpoint the HTTP surface uses.
#:
#: Numeric only. PARAM_SET carries a float, and the string settings behind the rest of the
#: /send/set* surface -- drone name, video source, the MediaMTX address -- have no honest float
#: encoding, so they stay on HTTP rather than being smuggled through as magic numbers. In
#: mavlink-only mode those come back refused, which is the truthful answer.
PARAM_RTH_ALTITUDE = "LB_RTH_ALT"
SETTING_PARAM_ENDPOINTS = {
    "/send/setRTHAltitude": PARAM_RTH_ALTITUDE,
    "/send/setMaxFlightHeight": "LB_MAX_HEIGHT",
    "/send/setMaxFlightDistance": "LB_MAX_DIST",
    "/send/setDistanceLimitEnabled": "LB_DIST_LIMIT_EN",
    "/send/setWebRtcFps": "LB_RTC_FPS",
    "/send/setDetectionsEnabled": "LB_DETECT_EN",
    "/send/setEdgeConfidence": "LB_EDGE_CONF",
}

#: CRC_EXTRA for LYREBIRD_STATUS, from lyrebird.xml. Changes whenever the fields do, which is
#: what lets a frame from a mismatched build be recognised rather than misread.
LYREBIRD_STATUS_CRC_EXTRA = 196


#: LYREBIRD_CONFIG, from lyrebird.xml. Identity and service ports, streamed slowly because
#: none of it changes during a session.
LYREBIRD_CONFIG_ID = 42101
LYREBIRD_CONFIG_STRUCT = "<HHB20s16s12s"
LYREBIRD_CONFIG_SIZE = 53
LYREBIRD_CONFIG_CRC_EXTRA = 201
LB_CONFIG_FLAG_HAS_THERMAL = 1


def decode_lyrebird_config(payload: bytes) -> dict[str, Any]:
    """Turn a LYREBIRD_CONFIG payload into the keys GET /config returns.

    Same names as the HTTP answer, so a consumer cannot tell which wire told it.
    """
    padded = payload.ljust(LYREBIRD_CONFIG_SIZE, b"\x00")
    http_port, telemetry_port, flags, name, ip, video = struct.unpack(
        LYREBIRD_CONFIG_STRUCT, padded
    )
    return {
        "droneName": _trim(name),
        "ipAddress": _trim(ip),
        "httpPort": http_port,
        "telemetryPort": telemetry_port,
        "videoMode": _trim(video),
        "hasThermal": bool(flags & LB_CONFIG_FLAG_HAS_THERMAL),
    }


#: AUTOSENSING_STATUS and AUTOSENSING_TARGET, from lyrebird.xml.
#:
#: Targets arrive one message each rather than as a list, so they are gathered by cycle and only
#: published once the status message says how many the cycle held. Publishing them as they arrive
#: would show a half-built set, and a viewer would see targets flicker in one at a time.
AUTOSENSING_STATUS_ID = 42102
AUTOSENSING_STATUS_STRUCT = "<IIfBB16s"
AUTOSENSING_STATUS_SIZE = 30
AUTOSENSING_STATUS_CRC_EXTRA = 254

AUTOSENSING_TARGET_ID = 42103
AUTOSENSING_TARGET_STRUCT = "<IIfffffBB16s"
AUTOSENSING_TARGET_SIZE = 46
AUTOSENSING_TARGET_CRC_EXTRA = 83


def decode_autosensing_target(payload: bytes) -> dict[str, Any]:
    """One detected object, in the shape the HTTP surface reports it."""
    padded = payload.ljust(AUTOSENSING_TARGET_SIZE, b"\x00")
    (_boot, frame_id, left, top, right, bottom, confidence, index, count, kind) = struct.unpack(
        AUTOSENSING_TARGET_STRUCT, padded
    )
    target: dict[str, Any] = {
        "index": index,
        "type": _trim(kind),
        "rect": [left, top, right, bottom],
    }
    # NaN means the detector did not report one, which is not the same as zero confidence.
    if not math.isnan(confidence):
        target["confidence"] = confidence
    return {"frameId": frame_id, "count": count, "target": target}


def decode_autosensing_status(payload: bytes) -> dict[str, Any]:
    padded = payload.ljust(AUTOSENSING_STATUS_SIZE, b"\x00")
    _boot, frame_id, threshold, active, count, source = struct.unpack(
        AUTOSENSING_STATUS_STRUCT, padded
    )
    return {
        "frameId": frame_id,
        "count": count,
        "autoSensingActive": bool(active),
        "detectionSource": _trim(source),
        "edgeConfidenceThreshold": threshold,
    }


def _checksum_ok(data: bytes, crc_extra: int) -> bool:
    """Verify a MAVLink 2 frame's checksum, the way the standard parser would."""
    from pymavlink.generator.mavcrc import x25crc

    payload_length = data[1]
    end = 10 + payload_length
    if len(data) < end + 2:
        return False
    crc = x25crc(data[1:end])
    # As a byte, not as a character: CRC_EXTRA values above 127 encode as two bytes in UTF-8, so
    # accumulating them through a str silently checksums the wrong thing.
    crc.accumulate(bytes([crc_extra]))
    expected = data[end] | (data[end + 1] << 8)
    return crc.crc == expected


#: What a Lyrebird parameter reads when the aircraft has never reported it.
PARAM_UNKNOWN = -1.0

#: Endpoints whose completion raises one of Lyrebird's reach latches, so that callers polling
#: isWaypointReached / isYawReached / isAltitudeReached keep working unchanged over MAVLink.
REACH_LATCHES = {
    "/send/gotoWaypointNoseForward": ("waypointReached", "waypointSeq"),
    "/send/gotoWaypointHoldHeading": ("waypointReached", "waypointSeq"),
    "/send/gotoYaw": ("yawReached", "yawSeq"),
    "/send/gotoAltitude": ("altitudeReached", "altitudeSeq"),
}
#: Settings whose value is a string, carried by the extended parameter protocol.
#:
#: PARAM_SET's value is a float, so these have no honest encoding there. PARAM_EXT_SET carries
#: the value as bytes, which is what it exists for.
TEXT_PARAM_ENDPOINTS = {
    "/send/setDroneName": "LB_DRONE_NAME",
    "/send/setVideoSource": "LB_VIDEO_SRC",
    "/send/setMediamtxServer": "LB_MEDIAMTX",
    "/send/setDetectionSource": "LB_DETECT_SRC",
    "/send/setRcControlMode": "LB_RC_MODE",
    "/send/setWebRtcResolution": "LB_RTC_RES",
    "/send/streaming/mode": "LB_STREAM_MODE",
}

#: The same settings addressed by their webapp key, for requestSetSetting.
SETTING_PARAMETERS = {
    "rthAltitude": PARAM_RTH_ALTITUDE,
    "maxFlightHeight": "LB_MAX_HEIGHT",
    "maxFlightDistance": "LB_MAX_DIST",
    "distanceLimitEnabled": "LB_DIST_LIMIT_EN",
    "webrtcFps": "LB_RTC_FPS",
    "detectionsEnabled": "LB_DETECT_EN",
    "edgeConfidenceThreshold": "LB_EDGE_CONF",
}

#: String settings addressed by their webapp key.
TEXT_SETTING_PARAMETERS = {
    "droneName": "LB_DRONE_NAME",
    "videoSource": "LB_VIDEO_SRC",
    "mediamtxServer": "LB_MEDIAMTX",
    "detectionSource": "LB_DETECT_SRC",
    "rcControlMode": "LB_RC_MODE",
    "webrtcResolution": "LB_RTC_RES",
    "streamingMode": "LB_STREAM_MODE",
}


for _endpoint in SETTING_PARAM_ENDPOINTS:
    _SPECIAL_SENDERS[_endpoint] = _send_setting_parameter(_endpoint)

for _endpoint in TEXT_PARAM_ENDPOINTS:
    _SPECIAL_SENDERS[_endpoint] = _send_text_parameter(_endpoint)


#: Environment variable carrying the 64-hex MAVLink 2 signing key. When set, the command
#: channel signs every outbound frame, so the aircraft treats this ground station as the
#: Safety Computer (see MavlinkSigning on the aircraft side).
SIGNING_KEY_ENV = "LB_MAVLINK_SIGNING_KEY"

#: Fixed link id for this ground station's commands. The aircraft keys its replay protection on
#: (system id, component id, link id); one fixed value keeps the timestamp monotonic.
SIGNING_LINK_ID = 1

#: MAVLink 2 signing timestamps are 10-microsecond ticks since 2015-01-01 UTC.
SIGNING_EPOCH = 1420070400
SIGNING_INCOMPAT_FLAG = 0x01
SIGNING_TIMESTAMP_BYTES = 6
SIGNING_TAIL_BYTES = 6
SIGNING_KEY_BYTES = 32
SIGNING_TOTAL_BYTES = 13


def signing_key_from_env(environ: dict[str, str] | None = None) -> bytes | None:
    """The 32-byte signing key from ``LB_MAVLINK_SIGNING_KEY`` (64 hex), or None.

    Malformed is the same as absent, mirroring the aircraft's MavlinkSigning.parseKey: a typo
    must leave the ground station unsigned rather than signing with a key the aircraft refuses.
    """
    raw = (environ if environ is not None else os.environ).get(SIGNING_KEY_ENV, "").strip()
    if len(raw) != SIGNING_KEY_BYTES * 2:
        return None
    try:
        return bytes.fromhex(raw)
    except ValueError:
        return None


def _signing_timestamp(now: float | None = None) -> int:
    """48-bit MAVLink signing timestamp: 10us ticks since 2015-01-01 UTC."""
    base = time.time() if now is None else now
    return int((base - SIGNING_EPOCH) * 100_000) & 0xFFFFFFFFFFFF


def _crc_extra_for(message_id: int) -> int:
    """The MAVLink CRC extra byte for [message_id], from the dialect's own table."""
    from pymavlink.dialects.v20 import common as mavlink_common

    return mavlink_common.mavlink_map[message_id].crc_extra


def _x25crc(data: bytes) -> int:
    """MAVLink X.25 CRC, the same the dialect's own packer uses."""
    from pymavlink.mavutil import x25crc

    return x25crc(data).crc


def sign_frame(frame: bytes, key: bytes, timestamp: int, link_id: int = SIGNING_LINK_ID) -> bytes:
    """Append the MAVLink 2 packet signature to a pymavlink-built frame.

    Mirrors the aircraft's MavlinkSigning byte-for-byte: the signed incompat bit is set, the
    checksum is recomputed over the header that now includes it, and the six-byte signature is
    the start of SHA-256(key || frame || link_id || timestamp_le). The timestamp is 48-bit
    little-endian, 10us ticks since 2015-01-01.
    """
    if frame[0] != MAVLINK2_MAGIC:
        raise ValueError("Only MAVLink 2 frames can be signed")
    payload_length = frame[1]
    # The 10-byte MAVLink 2 header ends with the 24-bit message id at bytes 7..10.
    message_id = int.from_bytes(frame[7:10], "little")

    framed = bytearray(frame)
    framed[2] |= SIGNING_INCOMPAT_FLAG
    crc = _x25crc(bytes(framed[1 : 1 + 9 + payload_length]) + bytes([_crc_extra_for(message_id)]))
    framed[10 + payload_length : 12 + payload_length] = crc.to_bytes(2, "little")
    body = bytes(framed)

    timestamp_bytes = (timestamp & 0xFFFFFFFFFFFF).to_bytes(SIGNING_TIMESTAMP_BYTES, "little")
    digest = hashlib.sha256()
    digest.update(key)
    digest.update(body)
    digest.update(bytes([link_id]))
    digest.update(timestamp_bytes)
    return body + bytes([link_id]) + timestamp_bytes + digest.digest()[:SIGNING_TAIL_BYTES]


def verify_frame_signature(frame: bytes, key: bytes) -> bool:
    """Whether [frame] carries a valid signature under [key] (hash check only, no replay state).

    The mirror of the aircraft's MavlinkSigning hash check, used by tests to prove the signer
    agrees with the reference. Timestamp / replay enforcement is the aircraft's to make.
    """
    if len(frame) < 12 or frame[0] != MAVLINK2_MAGIC:
        return False
    if frame[2] & SIGNING_INCOMPAT_FLAG == 0:
        return False
    payload_length = frame[1]
    signature_start = 10 + payload_length + 2
    if len(frame) < signature_start + SIGNING_TOTAL_BYTES:
        return False
    digest = hashlib.sha256()
    digest.update(key)
    digest.update(frame[:signature_start])
    digest.update(frame[signature_start : signature_start + 1 + SIGNING_TIMESTAMP_BYTES])
    expected = digest.digest()[:SIGNING_TAIL_BYTES]
    return expected == frame[signature_start + 1 + SIGNING_TIMESTAMP_BYTES :]


class _FrameSigner:
    """Signs every frame a channel emits with one MAVLink 2 key.

    The timestamp is the MAVLink epoch-based 10us clock and only ever advances, so the
    aircraft's replay protection (which refuses a timestamp not newer than the last seen)
    accepts each frame in sequence.
    """

    def __init__(self, key: bytes, link_id: int = SIGNING_LINK_ID) -> None:
        self._key = key
        self._link_id = link_id
        self._last_timestamp = 0

    def sign(self, frame: bytes) -> bytes:
        timestamp = _signing_timestamp()
        if timestamp <= self._last_timestamp:
            timestamp = self._last_timestamp + 1
        self._last_timestamp = timestamp
        return sign_frame(frame, self._key, timestamp, self._link_id)


class _FrameSink:
    """Collects the bytes pymavlink writes, so a message can be framed without a live socket.

    With a [signer], every captured frame is signed in place: pymavlink writes exactly one
    whole frame per call, so the signature is applied to the complete packet before it is read.
    """

    def __init__(self, signer: _FrameSigner | None = None) -> None:
        self.buf = b""
        self._signer = signer

    def write(self, data: bytes) -> None:
        self.buf = self._signer.sign(data) if self._signer is not None else data


def _json_endpoint_reply(endpoint: str, result: int | None, value: int) -> str | None:
    """Render the JSON-shaped endpoints the same way the HTTP surface does.

    The LRF and thermal reads answer with JSON over HTTP, so their ack render keeps that shape.
    Returns None when [endpoint] is not one of these, letting the generic ack renderer continue.
    """
    if endpoint == "/send/lrf/measure":
        if result != MAV_RESULT_ACCEPTED:
            return '{"distance": null, "target": null, "state": null}'
        # The geo-referenced target arrives on the telemetry stream as lrfTarget, exactly as
        # it does over HTTP; the ack carries only the distance.
        return f'{{"distance": {value / 100.0}, "target": null, "state": "NORMAL"}}'

    if endpoint == "/send/captureTemperature":
        if result != MAV_RESULT_ACCEPTED:
            return '{"thermalMaxTemp": null}'
        return f'{{"thermalMaxTemp": {value / 100.0}}}'

    if endpoint == "/send/captureThermalImage":
        # Over HTTP this returns a JSON descriptor naming the stored per-lens files. MAVLink
        # has no room for that in an ack, so an accepted shutter is reported without it -- the
        # files are still fetched by name over the media surface.
        if result != MAV_RESULT_ACCEPTED:
            return f'{{"error": "REJECTED: thermal capture returned MAV_RESULT {result or 0}"}}'
        return '{"captured": true}'

    return None


class MavlinkCommandChannel:
    """Sends commands to the aircraft as MAVLink and waits for the COMMAND_ACK.

    The reply is rendered as the same kind of string the HTTP surface returns, because that is
    what every caller already parses — including ``_parseSeq``, which reads a ``seq=<n>`` out of a
    waypoint acceptance. MAVLink has no such per-command id, so the channel assigns one; it is
    unique per client and monotonic, which is all the stale-latch guard needs.
    """

    def __init__(
        self,
        host: str,
        port: int = DEFAULT_MAVLINK_PORT,
        target_system: int = 1,
        on_latch: Callable[[dict[str, Any]], None] | None = None,
        signing_key: bytes | None = None,
    ):
        #: Called when a moving command completes, with the reach-latch keys to merge into
        #: telemetry. The client wires this to its own telemetry state.
        self._on_latch = on_latch
        self.host = host
        self.port = port
        self.target_system = target_system
        self._socket = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self._lock = threading.Lock()
        self._seq = 0
        # Built lazily so importing this module does not require pymavlink until a command is
        # actually sent -- the telemetry-only paths do not need it at import time.
        self._mav: Any | None = None
        self._parser: Any | None = None
        self._reader: threading.Thread | None = None
        self._inbox: list[Any] = []
        self._inbox_ready = threading.Condition()
        # With a signing key every outbound frame is signed, so the aircraft reads this ground
        # station as the Safety Computer rather than the Pilot.
        self._sink = _FrameSink(_FrameSigner(signing_key) if signing_key is not None else None)

    def supports(self, endpoint: str) -> bool:
        return endpoint in _COMMAND_MAP

    def send(self, endpoint: str, payload: str, timeout: float = 3.0) -> str:
        """Send one command and render the reply the way the HTTP surface would.

        Callers parse command replies as strings — ``_parseSeq`` reads a ``seq=<n>`` out of a
        waypoint acceptance, the LRF and temperature callers parse JSON — so the shapes here match
        what the same endpoint returns over HTTP rather than inventing a MAVLink-flavoured one.
        """
        handler = _SPECIAL_SENDERS.get(endpoint)
        if handler is not None:
            return handler(self, payload, timeout)

        builder = _COMMAND_MAP.get(endpoint)
        if builder is None:
            return f"{UNSUPPORTED_PREFIX} {endpoint}"

        command, params = builder(payload)
        result, value = self._send_command(command, params, timeout)
        if result == MAV_RESULT_IN_PROGRESS:
            seq = self._next_seq()
            if endpoint in REACH_LATCHES:
                self._watch_completion(command, endpoint, seq)
            return f"ACCEPTED seq={seq} via MAVLink cmd={command}"
        return self._render(endpoint, command, result, value)

    def _next_seq(self) -> int:
        with self._lock:
            self._seq += 1
            return self._seq

    def _raise_latch(self, endpoint: str, seq: int) -> None:
        reached_key, seq_key = REACH_LATCHES[endpoint]
        if self._on_latch is not None:
            self._on_latch({reached_key: True, seq_key: seq})

    def _render(self, endpoint: str, command: int, result: int | None, value: int) -> str:
        """Turn a COMMAND_ACK into the text this endpoint returns over HTTP."""
        json_body = _json_endpoint_reply(endpoint, result, value)
        if json_body is not None:
            return json_body

        if result is None:
            # No ack arrived. Reported rather than assumed: a caller that waits on a reach latch
            # would otherwise sit there believing a command it never confirmed.
            return f"REJECTED: no COMMAND_ACK for {endpoint}"

        if result == MAV_RESULT_CANCELLED:
            # A newer command took over before this one finished. A ground station that re-issues
            # a goto every second expects the superseded leg, so this is bookkeeping, not failure.
            return f"SUPERSEDED: {endpoint} superseded by a newer command"

        if result != MAV_RESULT_ACCEPTED:
            return f"REJECTED: {endpoint} returned MAV_RESULT {result}"

        return f"ACCEPTED seq={self._next_seq()} via MAVLink cmd={command}"

    def _send_command(
        self, command: int, params: list[float], timeout: float
    ) -> tuple[int | None, int]:
        """Send a command and wait for its first ack.

        A command that moves the aircraft is acknowledged twice: MAV_RESULT_IN_PROGRESS when it is
        accepted, and again when it finishes. Only the first is waited for here, because the
        caller's contract is the HTTP one -- ``requestSendGoToWaypointNoseForward`` returns a seq
        immediately and the caller polls the reach latch. Blocking until arrival would stall a ROS
        callback for the whole flight, so the completion is picked up on a background thread and
        raises the latch there.
        """
        frame = self._frame_command(command, params)
        with self._lock:
            self._socket.sendto(frame, (self.host, self.port))

        ack = self._await(_ack_matcher(command), timeout)
        if ack is None:
            return None, 0
        return ack.result, getattr(ack, "result_param2", 0) or 0

    def _watch_completion(self, command: int, endpoint: str, seq: int) -> None:
        """Raise this endpoint's reach latch when the aircraft reports the command finished.

        [seq] is captured at send time rather than read when the latch rises: another command may
        have been issued in between, and reporting this arrival under that command's number is
        exactly the stale-latch confusion the seq exists to prevent.
        """

        def wait() -> None:
            ack = self._await(_ack_matcher(command), COMPLETION_TIMEOUT_S)
            if ack is not None and ack.result == MAV_RESULT_ACCEPTED:
                self._raise_latch(endpoint, seq)

        threading.Thread(target=wait, name=f"mavlink-complete-{command}", daemon=True).start()

    def _await(self, matches: Callable[[Any], bool], timeout: float) -> Any:
        """Wait for a message this channel cares about, ignoring the telemetry flowing past.

        Reads are funnelled through one reader thread rather than taken directly, because several
        waiters coexist: a command waiting for its first ack, and any number of completion
        watchers waiting for the second. Two threads calling recvfrom on one socket would each
        swallow messages the other was waiting for.
        """
        self._start_reader()
        deadline = time.time() + timeout
        with self._inbox_ready:
            while True:
                for index, msg in enumerate(self._inbox):
                    if matches(msg):
                        del self._inbox[index]
                        return msg
                remaining = deadline - time.time()
                if remaining <= 0:
                    return None
                self._inbox_ready.wait(remaining)

    def _start_reader(self) -> None:
        if self._reader is not None:
            return
        with self._lock:
            if self._reader is not None:
                return
            self._reader = threading.Thread(
                target=self._read_loop, name="mavlink-command-reader", daemon=True
            )
            self._reader.start()

    def _read_loop(self) -> None:
        parser = self._ensure_parser()
        self._socket.settimeout(1.0)
        while True:
            try:
                data, _ = self._socket.recvfrom(2048)
            except TimeoutError:
                continue
            except OSError:
                return
            for msg in parser.parse_buffer(data) or []:
                if msg.get_type() not in _INTERESTING_REPLIES:
                    continue
                with self._inbox_ready:
                    self._inbox.append(msg)
                    # Bounded: a reply nobody is waiting for must not accumulate forever.
                    del self._inbox[:-_INBOX_LIMIT]
                    self._inbox_ready.notify_all()

    def _ensure_parser(self):
        from pymavlink.dialects.v20 import common as mavlink_common

        if self._parser is None:
            self._parser = mavlink_common.MAVLink(None)
            self._parser.robust_parsing = True
        return self._parser

    def send_manual_control(self, roll: float, pitch: float, throttle: float, yaw: float) -> None:
        """Send one stick sample. Axes are -1..1 and are scaled to MAVLink's -1000..1000."""
        mav = self._ensure_mav()
        self._sink.buf = b""

        def axis(value: float) -> int:
            return round(max(-1.0, min(1.0, value)) * 1000)

        mav.manual_control_send(
            self.target_system, axis(pitch), axis(roll), axis(throttle), axis(yaw), 0
        )
        with self._lock:
            self._socket.sendto(self._sink.buf, (self.host, self.port))

    def set_parameter(self, name: str, value: float, timeout: float = 3.0) -> str:
        """Write one parameter and report what the aircraft says it now holds.

        MAVLink answers a write with a PARAM_VALUE carrying the current value, which is how a
        refused or clamped write is detected. Comparing rather than assuming is the point: an
        aircraft that refuses the write still replies, with the old value.
        """
        mav = self._ensure_mav()
        self._sink.buf = b""
        mav.param_set_send(
            self.target_system, COMP_ID_AUTOPILOT1, name.encode()[:16], float(value), 9
        )
        with self._lock:
            self._socket.sendto(self._sink.buf, (self.host, self.port))

        reply = self._await(
            lambda m: m.get_type() == "PARAM_VALUE" and m.param_id.rstrip("\x00") == name,
            timeout,
        )
        if reply is None:
            return f"REJECTED: no PARAM_VALUE for {name}"
        if reply.param_value == PARAM_UNKNOWN:
            # Some DJI keys are never reported spontaneously, so the aircraft genuinely does not
            # know what it holds and publishes -1. The write was accepted, but saying it was
            # confirmed would be a claim nothing supports.
            return f"ACCEPTED (unverified: the aircraft does not report {name})"
        if abs(reply.param_value - value) > 1e-3:
            return f"REJECTED: {name} is {reply.param_value}, not {value}"
        return f"{name} set to {reply.param_value}"

    def set_text_parameter(self, name: str, value: str, timeout: float = 3.0) -> str:
        """Write one string setting and report what the aircraft says it now holds.

        PARAM_EXT_ACK echoes the current value rather than the requested one, so a write that was
        rejected as out of range comes back showing the old value — detected here rather than
        needing a second read.
        """
        mav = self._ensure_mav()
        self._sink.buf = b""
        mav.param_ext_set_send(
            self.target_system,
            COMP_ID_AUTOPILOT1,
            name.encode()[:16],
            value.encode()[:128],
            PARAM_EXT_TYPE_CUSTOM,
        )
        with self._lock:
            self._socket.sendto(self._sink.buf, (self.host, self.port))

        reply = self._await(
            lambda m: m.get_type() == "PARAM_EXT_ACK" and _trim(m.param_id) == name,
            timeout,
        )
        if reply is None:
            return f"REJECTED: no PARAM_EXT_ACK for {name}"
        current = _trim(reply.param_value)
        if reply.param_result != PARAM_ACK_ACCEPTED:
            return f"REJECTED: {name} refused, still {current!r}"
        return f"{name} set to {current}"

    def upload_plan(
        self, waypoints: list[tuple[float, ...]], speed: float, timeout: float = 6.0
    ) -> bool:
        """Upload a plan: a speed item, then one waypoint per leg. True when acked ACCEPTED."""
        mav = self._ensure_mav()
        count = len(waypoints) + 1

        def transmit(fn, *args) -> None:
            self._sink.buf = b""
            fn(*args)
            with self._lock:
                self._socket.sendto(self._sink.buf, (self.host, self.port))

        transmit(mav.mission_count_send, self.target_system, COMP_ID_AUTOPILOT1, count, 0)
        for _ in range(count + 2):
            reply = self._await(
                lambda m: m.get_type() in ("MISSION_REQUEST_INT", "MISSION_REQUEST", "MISSION_ACK"),
                timeout,
            )
            if reply is None:
                return False
            if reply.get_type() == "MISSION_ACK":
                return reply.type == 0
            if reply.seq == 0:
                # DO_CHANGE_SPEED: param1 selects ground speed, param2 carries it.
                transmit(
                    mav.mission_item_int_send,
                    self.target_system,
                    COMP_ID_AUTOPILOT1,
                    0,
                    MAV_FRAME_GLOBAL_RELATIVE_ALT_INT,
                    178,
                    0,
                    1,
                    1.0,
                    speed,
                    -1.0,
                    0.0,
                    0,
                    0,
                    0.0,
                    0,
                )
            else:
                latitude, longitude, altitude = (list(waypoints[reply.seq - 1]) + [0.0] * 3)[:3]
                transmit(
                    mav.mission_item_int_send,
                    self.target_system,
                    COMP_ID_AUTOPILOT1,
                    reply.seq,
                    MAV_FRAME_GLOBAL_RELATIVE_ALT_INT,
                    16,
                    0,
                    1,
                    0.0,
                    0.0,
                    0.0,
                    float("nan"),
                    round(latitude * 1e7),
                    round(longitude * 1e7),
                    altitude,
                    0,
                )
        return False

    def _frame_command(self, command: int, params: list[float]) -> bytes:
        """Encode one command, as COMMAND_INT when it carries a position and COMMAND_LONG else."""
        mav = self._ensure_mav()
        self._sink.buf = b""
        values = [float(p) for p in (list(params) + [0.0] * 7)[:7]]

        if command in POSITION_COMMANDS:
            mav.command_int_send(
                self.target_system,
                COMP_ID_AUTOPILOT1,
                MAV_FRAME_GLOBAL_RELATIVE_ALT_INT,
                command,
                0,  # current
                0,  # autocontinue
                *values[:4],
                round(values[4] * 1e7),
                round(values[5] * 1e7),
                values[6],
            )
        else:
            mav.command_long_send(self.target_system, COMP_ID_AUTOPILOT1, command, 0, *values)
        return self._sink.buf

    def _ensure_mav(self) -> Any:
        """The pymavlink encoder, created on first use."""
        from pymavlink.dialects.v20 import common as mavlink_common

        if self._mav is None:
            self._mav = mavlink_common.MAVLink(self._sink, srcSystem=255, srcComponent=190)
        return self._mav
