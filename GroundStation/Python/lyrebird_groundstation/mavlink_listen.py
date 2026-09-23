"""Listen to a Lyrebird MAVLink 2 telemetry endpoint and print what arrives.

A field check for the MAVLink telemetry phase: confirms the aircraft is emitting well-formed
MAVLink 2 before anyone opens QGroundControl, and shows which messages are actually arriving and
at what rate. Read-only — it never transmits, so it cannot influence the aircraft.

Usage::

    lyrebird-mavlink-listen                 # listen on 0.0.0.0:14550
    lyrebird-mavlink-listen --port 14550    # same, explicit
    lyrebird-mavlink-listen --summary 5     # print a rate summary every 5 seconds
    lyrebird-mavlink-listen --modes         # ask for the flight-mode list and print it
    python -m lyrebird_groundstation.mavlink_listen --summary 5   # same, without the console script

Requires pymavlink (``pip install pymavlink``). It is not a dependency of the rest of the
GroundStation code, so this script degrades with a clear message rather than a traceback.
"""

from __future__ import annotations

import argparse
import collections
import socket
import struct
import sys
import time
from typing import Any

DEFAULT_PORT = 14550
# Only ever used for display and for pymavlink's connection string; bind() is given the empty
# string instead. Listening on every interface is the intended behaviour for a tool whose whole
# job is to receive telemetry from an aircraft whose route to us is not known in advance, and it
# transmits nothing except an optional message request. nosec is the accepted-finding marker, not
# a way of hiding one: narrow it at runtime with --bind.
ANY_INTERFACE = "0.0.0.0"  # nosec B104
# Empty means every interface, which is Python's own convention for INADDR_ANY. That is the right
# default here: the aircraft may reach us over WiFi, a tether, or loopback in a simulator, and
# which one is not known ahead of time. Narrow it with --bind when it matters.
DEFAULT_BIND = ""
DEFAULT_SUMMARY_INTERVAL_S = 5.0
MSG_ID_AVAILABLE_MODES = 435
MSG_ID_COMMAND_LONG = 76
CMD_REQUEST_MESSAGE = 512
CRC_EXTRA_COMMAND_LONG = 152
MAVLINK2_MAGIC = 0xFD
MAVLINK2_HEADER_BYTES = 10
AVAILABLE_MODES_PAYLOAD_BYTES = 47
GCS_SYSTEM_ID = 255
GCS_COMPONENT_ID = 190

# Names for MAV_STANDARD_MODE, so the list reads the same way a ground station would render it.
STANDARD_MODE_NAMES = {
    1: "Position",
    2: "Orbit",
    3: "Cruise",
    4: "Altitude",
    5: "Safe Recovery",
    6: "Mission",
    7: "Land",
    8: "Takeoff",
}

# Messages the telemetry phase emits, in the order most useful to see first.
EXPECTED_MESSAGES = (
    "HEARTBEAT",
    "SYS_STATUS",
    "GPS_RAW_INT",
    "ATTITUDE",
    "GLOBAL_POSITION_INT",
    "VFR_HUD",
    "BATTERY_STATUS",
    "HOME_POSITION",
    "AUTOPILOT_VERSION",
    "STATUSTEXT",
)

# Streamed by the aircraft but absent from this list on purpose: pymavlink's newest release
# predates CURRENT_MODE (436), so it cannot decode the frame and would always be reported missing.
# Use --modes to confirm the mode protocol instead.
UNDECODABLE_MESSAGES = ("CURRENT_MODE", "AVAILABLE_MODES")


def _mavlink_crc(buf: bytes, crc: int = 0xFFFF) -> int:
    """CRC-16/MCRF4XX, the checksum MAVLink calls X.25."""
    for byte in buf:
        tmp = byte ^ (crc & 0xFF)
        tmp = (tmp ^ (tmp << 4)) & 0xFF
        crc = ((crc >> 8) ^ (tmp << 8) ^ (tmp << 3) ^ (tmp >> 4)) & 0xFFFF
    return crc


def _request_message_frame(message_id: int) -> bytes:
    """A COMMAND_LONG carrying MAV_CMD_REQUEST_MESSAGE for one message id."""
    payload = struct.pack(
        "<fffffffHBBB", float(message_id), 0, 0, 0, 0, 0, 0, CMD_REQUEST_MESSAGE, 1, 1, 0
    )
    frame = bytearray(MAVLINK2_HEADER_BYTES + len(payload) + 2)
    frame[0] = MAVLINK2_MAGIC
    frame[1] = len(payload)
    frame[5] = GCS_SYSTEM_ID
    frame[6] = GCS_COMPONENT_ID
    frame[7] = MSG_ID_COMMAND_LONG & 0xFF
    frame[MAVLINK2_HEADER_BYTES : MAVLINK2_HEADER_BYTES + len(payload)] = payload
    crc = _mavlink_crc(bytes([CRC_EXTRA_COMMAND_LONG]), _mavlink_crc(bytes(frame[1:-2])))
    frame[-2] = crc & 0xFF
    frame[-1] = (crc >> 8) & 0xFF
    return bytes(frame)


def _load_mavlink() -> Any:
    try:
        from pymavlink import mavutil
    except ImportError:
        print(
            "pymavlink is not installed. Install it with:\n    pip install pymavlink",
            file=sys.stderr,
        )
        raise SystemExit(1) from None
    return mavutil


def _fmt_heartbeat(m: Any) -> str:
    return (
        f"sysid={m.get_srcSystem()} custom_mode={m.custom_mode} "
        f"base_mode=0x{m.base_mode:02X} state={m.system_status} "
        f"autopilot={m.autopilot} type={m.type}"
    )


def _fmt_global_position(m: Any) -> str:
    return (
        f"lat={m.lat / 1e7:.7f} lon={m.lon / 1e7:.7f} "
        f"amsl={m.alt / 1000:.1f}m agl={m.relative_alt / 1000:.1f}m "
        f"hdg={m.hdg / 100:.1f}deg"
    )


def _fmt_vfr_hud(m: Any) -> str:
    return (
        f"groundspeed={m.groundspeed:.1f}m/s alt={m.alt:.1f}m "
        f"climb={m.climb:.1f}m/s heading={m.heading}deg"
    )


def _fmt_current_mode(m: Any) -> str:
    return (
        f"custom_mode={m.custom_mode} standard_mode={m.standard_mode} "
        f"intended={m.intended_custom_mode}"
    )


# One formatter per message worth reading at a glance. A table rather than a chain of ifs so
# adding a message stays a one-line change and the complexity gate stays happy.
DESCRIBERS: dict[str, Any] = {
    "HEARTBEAT": _fmt_heartbeat,
    "GLOBAL_POSITION_INT": _fmt_global_position,
    "GPS_RAW_INT": lambda m: f"fix={m.fix_type} sats={m.satellites_visible}",
    "ATTITUDE": lambda m: f"roll={m.roll:.3f} pitch={m.pitch:.3f} yaw={m.yaw:.3f} (rad)",
    "VFR_HUD": _fmt_vfr_hud,
    "BATTERY_STATUS": lambda m: (
        f"remaining={m.battery_remaining}% time_remaining={getattr(m, 'time_remaining', None)}s"
    ),
    "HOME_POSITION": lambda m: f"lat={m.latitude / 1e7:.7f} lon={m.longitude / 1e7:.7f}",
    "STATUSTEXT": lambda m: f"[{m.severity}] {m.text}",
    "AUTOPILOT_VERSION": lambda m: f"capabilities=0x{m.capabilities:X}",
    "CURRENT_MODE": _fmt_current_mode,
}


def describe(message: Any) -> str:
    """One-line summary of the messages worth reading at a glance."""
    formatter = DESCRIBERS.get(message.get_type())
    return formatter(message) if formatter else ""


def _decode_available_modes(
    raw: bytes,
) -> tuple[int, int, int, int, int, str] | None:
    """Decode one AVAILABLE_MODES frame straight from the wire.

    Done by hand rather than through pymavlink because the newest release still predates this
    message, so its dialect cannot parse it and reports the frame as BAD_DATA. Decoding it here
    keeps the tool useful with the pymavlink people actually have installed, and means this check
    does not silently start passing for the wrong reason when the dialect catches up.

    Field order is the MAVLink wire order: custom_mode(u32), properties(u32), number_modes(u8),
    mode_index(u8), standard_mode(u8), mode_name(char[35]).
    """
    if len(raw) < MAVLINK2_HEADER_BYTES or raw[0] != MAVLINK2_MAGIC:
        return None
    payload_len = raw[1]
    message_id = raw[7] | (raw[8] << 8) | (raw[9] << 16)
    if message_id != MSG_ID_AVAILABLE_MODES:
        return None
    # MAVLink 2 truncates trailing zero bytes, so pad before unpacking fixed offsets.
    payload = raw[MAVLINK2_HEADER_BYTES : MAVLINK2_HEADER_BYTES + payload_len]
    payload = payload.ljust(AVAILABLE_MODES_PAYLOAD_BYTES, b"\x00")
    custom_mode, properties, number_modes, mode_index, standard_mode = struct.unpack_from(
        "<IIBBB", payload, 0
    )
    name = payload[11:46].split(b"\x00")[0].decode("utf-8", errors="replace")
    return custom_mode, properties, number_modes, mode_index, standard_mode, name


def request_modes(port: int, bind: str = DEFAULT_BIND, timeout_s: float = 10.0) -> None:
    """Ask the aircraft for its flight-mode list and print it.

    A ground station will not offer modes it cannot select, and Lyrebird marks every mode
    NOT_USER_SELECTABLE while mode changes are unimplemented — so the list is invisible in
    QGroundControl by design. This prints it anyway, which is how to confirm the standard-modes
    protocol works before any of it reaches a UI.
    """
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.bind((bind, port))
    sock.settimeout(1.0)

    modes: dict[int, tuple[int, int, str]] = {}
    total = 0
    deadline = time.monotonic() + timeout_s
    peer: tuple[str, int] | None = None

    while time.monotonic() < deadline:
        try:
            data, addr = sock.recvfrom(4096)
        except (TimeoutError, OSError):
            continue
        if peer is None:
            peer = addr
            sock.sendto(_request_message_frame(MSG_ID_AVAILABLE_MODES), peer)
        decoded = _decode_available_modes(data)
        if decoded is None:
            continue
        custom_mode, _properties, number_modes, mode_index, standard_mode, name = decoded
        total = number_modes
        modes[mode_index] = (custom_mode, standard_mode, name)
        if len(modes) >= number_modes:
            break

    sock.close()

    if not modes:
        print(
            "No AVAILABLE_MODES received. Either the aircraft is not streaming, or it predates "
            "the flight-mode work — try a plain run first.",
            file=sys.stderr,
        )
        return

    print(f"\nFlight modes ({len(modes)} of {total}):")
    for index in sorted(modes):
        custom_mode, standard_mode, name = modes[index]
        label = name or STANDARD_MODE_NAMES.get(standard_mode, f"standard {standard_mode}")
        origin = "standard" if standard_mode else "Lyrebird"
        print(f"  {index:2}. custom_mode={custom_mode:<3} {label:<16} ({origin})")
    print("\nAll are reported NOT_USER_SELECTABLE, so a ground station shows the active one but")
    print("offers no picker. That changes when mode commands are implemented.")


def print_summary(counts: collections.Counter, elapsed_s: float) -> None:
    print(f"\n--- {elapsed_s:.1f}s ---")
    for name in EXPECTED_MESSAGES:
        count = counts.get(name, 0)
        rate = count / elapsed_s if elapsed_s > 0 else 0.0
        mark = " " if count else "!"
        print(f" {mark} {name:22} {count:6}  {rate:6.2f} Hz")
    unexpected = sorted(set(counts) - set(EXPECTED_MESSAGES))
    if unexpected:
        print("   (also seen)")
    for name in unexpected:
        print(f"   {name:22} {counts[name]:6}  (not part of the telemetry phase)")
    print()


def listen(port: int, summary_interval_s: float, bind: str = DEFAULT_BIND) -> None:
    mavutil = _load_mavlink()
    address = bind or ANY_INTERFACE
    connection = mavutil.mavlink_connection(f"udpin:{address}:{port}", dialect="common")
    print(f"Listening for MAVLink 2 on udp:{address}:{port} (Ctrl-C to stop)")

    counts: collections.Counter = collections.Counter()
    seen: set[str] = set()
    started = time.monotonic()
    last_summary = started

    while True:
        message = connection.recv_match(blocking=True, timeout=1.0)
        now = time.monotonic()

        if message is not None:
            kind = message.get_type()
            if kind == "BAD_DATA":
                # A malformed frame is the whole point of running this: report it loudly rather
                # than silently dropping it, because it means the sender and this dialect disagree.
                print(f"BAD_DATA: {message.reason if hasattr(message, 'reason') else message}")
            else:
                counts[kind] += 1
                if kind not in seen:
                    seen.add(kind)
                    detail = describe(message)
                    print(f"first {kind:22} {detail}")

        if summary_interval_s > 0 and now - last_summary >= summary_interval_s:
            print_summary(counts, now - started)
            last_summary = now


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--port", type=int, default=DEFAULT_PORT, help="UDP port to listen on")
    parser.add_argument(
        "--summary",
        type=float,
        default=DEFAULT_SUMMARY_INTERVAL_S,
        dest="summary_interval_s",
        help="seconds between rate summaries, 0 to disable",
    )
    parser.add_argument(
        "--bind",
        default=DEFAULT_BIND,
        help="local address to listen on (default: every interface)",
    )
    parser.add_argument(
        "--modes",
        action="store_true",
        help="request the flight-mode list, print it, and exit",
    )
    args = parser.parse_args()

    try:
        if args.modes:
            request_modes(args.port, args.bind)
        else:
            listen(args.port, args.summary_interval_s, args.bind)
    except KeyboardInterrupt:
        print("\nStopped.")


if __name__ == "__main__":
    main()
