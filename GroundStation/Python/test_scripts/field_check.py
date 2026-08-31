"""Walk the MAVLink implementation against a live aircraft, in the field.

Every defect this interface has had so far decoded cleanly and meant the wrong thing: a status
message read twelve bytes out of alignment, a gimbal quaternion transposed with its failure
flags, a checksum helper that made every genuine frame look corrupt. None of them were visible
by reading code, and none would have failed a unit test written by the same hand that wrote the
bug. They were only visible against a real aircraft, which is what this script is for.

The checks are grouped by what they can move, and the script will not cross a group boundary
without being told to:

    link     nothing is sent; the aircraft is only listened to        (default)
    ground   commands that cannot move the aircraft or the gimbal
    payload  the gimbal moves, the camera fires                       --move
    flight   the aircraft leaves the ground                           --fly, and a typed confirm

Usage:
    python field_check.py PHONE_IP                    # listen only, safe with props on
    python field_check.py PHONE_IP --phase ground
    python field_check.py PHONE_IP --phase payload --move
    python field_check.py PHONE_IP --phase flight --fly

Signing comes from --key or ``LB_MAVLINK_SIGNING_KEY``. With a key every frame is signed and the
aircraft reads this script as the Safety Computer rather than the Pilot, which is a different
authority and a different set of answers -- see signing_exercise.py, which tests that gate on its
own and is not repeated here.
"""

from __future__ import annotations

import argparse
import os
import sys
import threading
import time
from typing import Any

from lyrebird_groundstation.transport import (
    MavlinkCommandChannel,
    MavlinkTelemetrySource,
    mavlink_peer_port_from_env,
    mavlink_port_from_env,
)

PASS, FAIL, WARN, SKIP = "PASS", "FAIL", "WARN", "SKIP"
_MARK = {PASS: "  ok  ", FAIL: " FAIL ", WARN: " warn ", SKIP: " skip "}

#: Fields the aircraft should be reporting within a few seconds of power-up, with no flight and
#: no GPS lock required. Anything missing here is a decoder or a publisher fault, not weather.
CORE_FIELDS = (
    "battery",
    "flightMode",
    "heading",
    "roll",
    "pitch",
    "yaw",
    "gimbalPitch",
    "gimbalYaw",
    "isFlying",
    "droneName",
    "ipAddress",
    "httpPort",
)

#: Fields that need something from the world before they can be honest: a GPS fix, a home point,
#: a battery that has learned its discharge rate. Absent is a state of the aircraft, not a gap in
#: the interface, so these are reported but never failed.
CONDITIONAL_FIELDS = (
    "latitude",
    "longitude",
    "altitude",
    "satelliteCount",
    "homeLocation",
    "distanceToHome",
    "remainingFlightTime",
    "gimbalJointAttitude",
)


class Checks:
    """Collects results so the run ends with one verdict instead of a wall of prints."""

    def __init__(self) -> None:
        self.results: list[tuple[str, str, str]] = []

    def record(self, status: str, name: str, detail: str = "") -> None:
        self.results.append((status, name, detail))
        print(f"[{_MARK[status]}] {name}" + (f" -- {detail}" if detail else ""))

    def failed(self) -> int:
        return sum(1 for status, _, _ in self.results if status == FAIL)

    def summary(self) -> None:
        counts: dict[str, int] = {}
        for status, _, _ in self.results:
            counts[status] = counts.get(status, 0) + 1
        print()
        print("  ".join(f"{status}: {counts.get(status, 0)}" for status in (PASS, FAIL, WARN, SKIP)))
        if self.failed():
            print()
            print("Failures:")
            for status, name, detail in self.results:
                if status == FAIL:
                    print(f"  - {name}: {detail}")


def _listen(args: argparse.Namespace, seconds: float) -> tuple[dict[str, Any], int]:
    """Collect telemetry for a fixed window and report how many updates arrived."""
    latest: dict[str, Any] = {}
    updates = 0
    lock = threading.Lock()

    def on_update(telemetry: dict[str, Any]) -> None:
        nonlocal updates
        with lock:
            latest.clear()
            latest.update(telemetry)
            updates += 1

    source = MavlinkTelemetrySource(
        port=args.port, on_update=on_update, peer_host=args.host, peer_port=args.peer_port
    )
    source.start()
    try:
        time.sleep(seconds)
    finally:
        source.stop()
    with lock:
        return dict(latest), updates


def _check_fields(telemetry: dict[str, Any], checks: Checks) -> None:
    """A missing core field is a decoder fault; a missing conditional one is the weather."""
    # A CRC mismatch means the phone's dialect and this one disagree, which is exactly the
    # failure that once read a status message twelve bytes out of alignment and reported a
    # takeoff block reason of "ISION". A rejected frame is a mismatched build, not a bad link.
    missing = [field for field in CORE_FIELDS if telemetry.get(field) is None]
    if missing:
        checks.record(
            FAIL,
            "core telemetry decodes",
            f"missing {', '.join(missing)} -- if these are all Lyrebird-specific the phone is "
            f"on a build whose dialect CRC differs and the frames are being rejected",
        )
    else:
        checks.record(PASS, "core telemetry decodes", f"{len(CORE_FIELDS)} fields populated")

    present = [field for field in CONDITIONAL_FIELDS if telemetry.get(field) is not None]
    checks.record(
        PASS if present else WARN,
        "conditional fields",
        f"{len(present)}/{len(CONDITIONAL_FIELDS)} reported "
        f"(absent is a state of the aircraft, not a gap: no fix, no home point, no battery time)",
    )


def _check_config(telemetry: dict[str, Any], checks: Checks) -> None:
    mode = telemetry.get("flightMode")
    checks.record(
        PASS if mode else WARN,
        "flight mode names resolve",
        f"{mode!r}" if mode else "no heartbeat custom_mode yet",
    )

    name = telemetry.get("droneName")
    if name:
        checks.record(
            PASS,
            "config streams",
            f"{name} at {telemetry.get('ipAddress')}:{telemetry.get('httpPort')}, "
            f"video={telemetry.get('videoMode')}, thermal={telemetry.get('hasThermal')}",
        )
    else:
        checks.record(
            FAIL,
            "config streams",
            "no LYREBIRD_CONFIG in the window (it is slow -- try --listen 15 first)",
        )


def _check_position(telemetry: dict[str, Any], checks: Checks) -> None:
    lat, lon = telemetry.get("latitude"), telemetry.get("longitude")
    if lat is None or lon is None:
        checks.record(WARN, "position is real", "no position reported yet")
    elif abs(lat) < 1e-7 and abs(lon) < 1e-7:
        # (0, 0) is the marker DJI uses for "no fix", and it is a real coordinate in the Gulf of
        # Guinea, so a consumer that trusts it will draw the drone 5000km away.
        checks.record(WARN, "position is real", "(0, 0) -- no GPS fix yet, not a decode fault")
    else:
        checks.record(PASS, "position is real", f"{lat:.6f}, {lon:.6f}")


def phase_link(args: argparse.Namespace, checks: Checks) -> dict[str, Any]:
    """Listen only. Nothing is sent, so this is safe to run with the props on."""
    print(f"-- link (listening on :{args.port} for {args.listen:.0f}s, nothing is sent) --")
    telemetry, updates = _listen(args, args.listen)

    if not updates:
        checks.record(
            FAIL,
            "telemetry arrives",
            f"nothing on :{args.port}. Check the phone is on this network, that LB_MAVLINK_PORT "
            f"matches what the app streams to, and that QGC is not bound to the same port",
        )
        return telemetry
    checks.record(PASS, "telemetry arrives", f"{updates} updates in {args.listen:.0f}s")

    _check_fields(telemetry, checks)
    _check_config(telemetry, checks)
    _check_position(telemetry, checks)
    return telemetry


def _expect_ok(checks: Checks, name: str, reply: str, detail: str = "") -> bool:
    ok = not reply.upper().startswith(("REJECTED", "FAILED", "TIMEOUT", "ERROR"))
    checks.record(PASS if ok else FAIL, name, detail or reply)
    return ok


def phase_ground(args: argparse.Namespace, channel: MavlinkCommandChannel, checks: Checks) -> None:
    """Commands that cannot move anything: parameter writes and their read-back."""
    print()
    print("-- ground (parameter writes; nothing moves) --")

    # LB_RTH_ALT is the one that proved a listener-only read returns -1 forever because nothing
    # seeds it, so read-back through telemetry is the check that matters, not the ack.
    reply = channel.send("/send/setRTHAltitude", "30", timeout=args.timeout)
    if _expect_ok(checks, "setRTHAltitude accepted", reply):
        telemetry, _ = _listen(args, 3.0)
        value = telemetry.get("rthAltitude")
        if value is None:
            checks.record(WARN, "RTH altitude reads back", "not in the stream yet")
        elif abs(float(value) - 30) < 1.0:
            checks.record(PASS, "RTH altitude reads back", f"{value}")
        else:
            checks.record(FAIL, "RTH altitude reads back", f"wrote 30, reads {value}")

    for endpoint, value in (
        ("/send/setMaxFlightHeight", "120"),
        ("/send/setDetectionsEnabled", "true"),
        ("/send/setEdgeConfidence", "0.5"),
    ):
        _expect_ok(checks, f"{endpoint.rsplit('/', 1)[-1]} accepted",
                   channel.send(endpoint, value, timeout=args.timeout))

    # A string setting has no honest float encoding, so PARAM_SET refuses it rather than
    # smuggling it through as a magic number. Being refused is the correct answer here.
    reply = channel.send("/send/setSetting", "droneName,notanumber", timeout=args.timeout)
    checks.record(
        PASS if reply.upper().startswith("REJECTED") else FAIL,
        "non-numeric setting is refused, not silently dropped",
        reply,
    )


def phase_payload(args: argparse.Namespace, channel: MavlinkCommandChannel, checks: Checks) -> None:
    """The gimbal moves and the camera fires. The aircraft stays on the ground."""
    print()
    print("-- payload (THE GIMBAL WILL MOVE) --")

    before, _ = _listen(args, 2.0)
    start_pitch = before.get("gimbalPitch")

    if _expect_ok(checks, "gimbal pitch accepted",
                  channel.send("/send/gimbal/pitch", "-45", timeout=args.timeout)):
        after, _ = _listen(args, 3.0)
        now = after.get("gimbalPitch")
        if now is None:
            checks.record(FAIL, "gimbal pitch reads back", "no gimbalPitch in the stream")
        elif abs(float(now) + 45) < 8.0:
            checks.record(PASS, "gimbal pitch reads back", f"commanded -45, reads {now:.1f}")
        else:
            # The sign convention here was settled by measurement, not argument: a 48-sample
            # sweep gave slope +1.03. A reading near +45 means the sign flipped back.
            checks.record(
                FAIL, "gimbal pitch reads back",
                f"commanded -45, reads {now:.1f} (from {start_pitch}) -- if this is near +45 the "
                f"sign convention has inverted",
            )
        checks.record(
            PASS if abs(float(now or 0)) < 6553.0 else FAIL,
            "gimbal value is not a DJI unset marker",
            f"{now} (6553.5 is 65535/10, DJI's 'axis saturated')",
        )

    _expect_ok(checks, "gimbal relative pitch accepted",
               channel.send("/send/gimbal/rel_pitch", "10", timeout=args.timeout))
    channel.send("/send/gimbal/pitch", "0", timeout=args.timeout)

    _expect_ok(checks, "photo capture accepted",
               channel.send("/send/capture", "", timeout=args.timeout))
    print("       -> confirm the new photo appears: python ftp_exercise.py", args.host)

    if before.get("hasThermal"):
        _expect_ok(checks, "thermal capture accepted",
                   channel.send("/send/captureThermalImage", "", timeout=args.timeout))
        _expect_ok(checks, "temperature capture accepted",
                   channel.send("/send/captureTemperature", "", timeout=args.timeout))
    else:
        checks.record(SKIP, "thermal", "this aircraft reports no thermal camera")

    _expect_ok(checks, "LRF measure accepted",
               channel.send("/send/lrf/measure", "", timeout=args.timeout))

    if _expect_ok(checks, "autoSensing start accepted",
                  channel.send("/send/autoSensing/start", "", timeout=args.timeout)):
        telemetry, _ = _listen(args, 5.0)
        active = telemetry.get("autoSensingActive")
        count = telemetry.get("detections")
        checks.record(
            PASS if active else WARN,
            "autoSensing reports back",
            f"active={active} detections={len(count) if isinstance(count, list) else count} "
            f"source={telemetry.get('detectionSource')} "
            f"(zero detections is correct if nothing is in frame -- point it at a person)",
        )
        channel.send("/send/autoSensing/stop", "", timeout=args.timeout)


FLIGHT_CHECKLIST = """
-- flight --

These are the ones that have never run. The onboard mission sequencer has not executed a single
mission against a real aircraft, so treat the first flight as a test of the sequencer and not of
the waypoints.

Fly with the RC in hand and the pilot's thumb on the sticks throughout. Every command below has
a manual override, and the point of the first flight is to confirm the override works before
trusting anything that follows it.

  1. takeoff              /send/takeoff           -> isFlying goes true, altitude climbs
  2. hold                 (nothing)               -> position is stable, heading steady
  3. gotoAltitude         /send/gotoAltitude 15   -> climbs and holds, does not overshoot
  4. gotoYaw              /send/gotoYaw 90        -> rotates on the spot, heading reads 90
  5. manual override      move a stick            -> the aircraft answers the stick immediately,
                                                     and telemetry shows the override latched
  6. release override     /send/deactivateManualOverride
  7. single waypoint      /send/navigateTrajectoryDJINative
                                                  -> flies there, and intermediaryWaypointReached
                                                     latches ONCE, with the right seq
  8. arrival dwell        watch the latch         -> it latches on arrival, not while still
                                                     approaching, and not repeatedly
  9. nose-forward vs hold heading                 -> the ROS ground station uses hold-heading;
                                                     confirm the aircraft keeps its heading
                                                     rather than yawing into the leg
 10. multi-leg mission    upload_plan             -> each leg completes in order, mission_current
                                                     advances, no leg is skipped
 11. supersede a mission  send a new one mid-leg  -> the old one reports SUPERSEDED, not FAILED
 12. abort                /send/abortMission      -> stops where it is, reports CANCELLED
 13. RTH                  /send/RTH               -> returns and lands at the home point, and the
                                                     RTH altitude is the 30 written on the ground
 14. QGC alongside        QGC on 14550, this on 14551
                                                  -> both see full telemetry at once, neither
                                                     stutters (a shared port means each gets
                                                     roughly half, which looks like a freeze)
 15. signing gate in air  python signing_exercise.py PHONE_IP
                                                  -> an unsigned flight command is refused once
                                                     the Safety Computer has latched authority

Land, then check the card: python ftp_exercise.py PHONE_IP
"""


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("host", help="the phone's IP address")
    parser.add_argument(
        "--phase", default="link", choices=("link", "ground", "payload", "flight", "all")
    )
    parser.add_argument("--key", default=os.environ.get("LB_MAVLINK_SIGNING_KEY", ""))
    parser.add_argument("--port", type=int, default=mavlink_port_from_env())
    parser.add_argument("--peer-port", type=int, default=mavlink_peer_port_from_env())
    parser.add_argument("--listen", type=float, default=8.0, help="telemetry window, seconds")
    parser.add_argument("--timeout", type=float, default=5.0, help="command ack timeout")
    parser.add_argument("--move", action="store_true", help="allow the gimbal and camera to move")
    parser.add_argument("--fly", action="store_true", help="show the flight checklist")
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    key = bytes.fromhex(args.key) if args.key else None
    checks = Checks()

    print(f"phone={args.host} listen=:{args.port} send=:{args.peer_port} "
          f"signing={'on (Safety Computer)' if key else 'off (Pilot)'}")
    print()

    telemetry = phase_link(args, checks)
    wants = args.phase

    if wants in ("ground", "payload", "flight", "all"):
        if not telemetry:
            checks.record(SKIP, "everything past link", "no telemetry, so nothing else is testable")
        else:
            channel = MavlinkCommandChannel(args.host, port=args.peer_port, signing_key=key)
            phase_ground(args, channel, checks)

            if wants in ("payload", "all"):
                if args.move:
                    phase_payload(args, channel, checks)
                else:
                    checks.record(SKIP, "payload", "pass --move to let the gimbal and camera move")

    checks.summary()

    if wants in ("flight", "all"):
        if args.fly:
            print(FLIGHT_CHECKLIST)
        else:
            print()
            print("Flight checklist withheld. Pass --fly to print it.")

    sys.exit(1 if checks.failed() else 0)


if __name__ == "__main__":
    main()
