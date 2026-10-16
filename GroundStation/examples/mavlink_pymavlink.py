"""Take off, fly to a point, come back — in raw MAVLink 2 with pymavlink.

The same flight as every script in this folder:

    1. take off
    2. fly to a point --distance metres north of the take-off spot, --height metres up
    3. wait until the aircraft reports it has arrived
    4. return home

This is the wire with nothing in between: the commands QGroundControl sends for the same actions
(NAV_TAKEOFF, DO_REPOSITION, NAV_RETURN_TO_LAUNCH), addressed by system id, with the
acknowledgements read back — including IN_PROGRESS, which the aircraft repeats while a go-to is
flying and follows with the final result when it arrives.

Two things worth knowing about Lyrebird's MAVLink:

- It streams telemetry only to ground stations it has heard from, so this script sends its own
  heartbeat once a second; it sends to the aircraft rather than listening on 14550, which leaves
  that port to QGroundControl.
- Altitudes are heights above the take-off point (the aircraft reports 0 m on the ground), so the
  altitude in DO_REPOSITION is simply --height.

Safety: nothing moves unless you pass --fly; without it the script checks the aircraft and prints
the plan. The checks refuse to fly without a 3D GPS fix, a home position, enough battery, or with
the aircraft already in the air. Ctrl+C or a timeout sends the aircraft home. The RC sticks
override everything at any time — keep your hands on them.

    $PY GroundStation/examples/mavlink_pymavlink.py --ip <RC_IP>          # dry run
    $PY GroundStation/examples/mavlink_pymavlink.py --ip <RC_IP> --fly    # fly

Needs `pymavlink`. $PY is the examples' virtual environment — see README.md in this folder.
"""

import argparse
import math
import os
import sys
import time

os.environ.setdefault("MAVLINK20", "1")  # Lyrebird reads only MAVLink 2 frames.
from pymavlink import mavutil

MAVLINK_PORT = 14550
OUR_SYSTEM_ID = 245  # a ground-station id; QGroundControl uses 255, so the two never clash
OUR_COMPONENT_ID = 190  # MAV_COMP_ID_MISSIONPLANNER
AUTOPILOT_COMPONENT = 1
METRES_PER_DEGREE_LAT = 111_320.0

MIN_SATELLITES = 8
MIN_BATTERY_PERCENT = 30
# DJI's automatic take-off ends in a hover at about 1 m (MAVLink reports exactly 1.0, so "above
# 1 m" never comes). Take-off is over once the aircraft is above LIFTED_OFF_M and its height has
# held within SETTLED_WITHIN_M for SETTLED_FOR_S.
LIFTED_OFF_M = 0.5
SETTLED_WITHIN_M = 0.1
SETTLED_FOR_S = 2.0
TAKEOFF_TIMEOUT_S = 30.0
ARRIVAL_TIMEOUT_S = 90.0
GPS_FIX_3D = 3


class Vehicle:
    """One MAVLink conversation with one aircraft, plus the latest copy of each message."""

    def __init__(self, ip: str):
        self.link = mavutil.mavlink_connection(
            f"udpout:{ip}:{MAVLINK_PORT}",
            source_system=OUR_SYSTEM_ID,
            source_component=OUR_COMPONENT_ID,
        )
        self.system_id = None
        self.latest: dict[str, object] = {}
        self.acks: list = []  # every COMMAND_ACK, in order: several can arrive between reads
        self._next_heartbeat = 0.0

    def pump(self, seconds: float) -> None:
        """Read for `seconds`, keeping our heartbeat going and the latest messages stored."""
        deadline = time.time() + seconds
        while time.time() < deadline:
            if time.time() >= self._next_heartbeat:
                self.link.mav.heartbeat_send(
                    mavutil.mavlink.MAV_TYPE_GCS, mavutil.mavlink.MAV_AUTOPILOT_INVALID, 0, 0, 0
                )
                self._next_heartbeat = time.time() + 1.0
            msg = self.link.recv_match(blocking=True, timeout=0.1)
            if msg is None or msg.get_type() == "BAD_DATA":
                continue
            if msg.get_srcComponent() == AUTOPILOT_COMPONENT:
                if msg.get_type() == "HEARTBEAT" and self.system_id is None:
                    self.system_id = msg.get_srcSystem()
                if msg.get_type() == "COMMAND_ACK":
                    self.acks.append(msg)
                else:
                    self.latest[msg.get_type()] = msg

    def command_long(self, command: int, *params: float) -> None:
        p = (list(params) + [0.0] * 7)[:7]
        self.link.mav.command_long_send(self.system_id, AUTOPILOT_COMPONENT, command, 0, *p)

    def wait_ack(self, command: int, timeout_s: float, on_progress=None) -> int:
        """The final MAV_RESULT for `command`. IN_PROGRESS acks mean "still working on it"."""
        deadline = time.time() + timeout_s
        while time.time() < deadline:
            self.pump(0.1)
            while self.acks:
                ack = self.acks.pop(0)
                if ack.command != command:
                    continue
                if ack.result == mavutil.mavlink.MAV_RESULT_IN_PROGRESS:
                    if on_progress:
                        on_progress()
                    continue
                return ack.result
        raise TimeoutError(f"no final acknowledgement for command {command} in {timeout_s:.0f} s")

    def height_m(self) -> float:
        position = self.latest.get("GLOBAL_POSITION_INT")
        return position.relative_alt / 1000.0 if position else 0.0


def hovering(read_height):
    """A condition that is true once DJI's take-off is over: off the ground and holding height.

    DJI's automatic take-off climbs to about 1 m and hovers, and navigation commands sent while
    it is still climbing are ignored. "Over" is read from the height: above LIFTED_OFF_M and
    within SETTLED_WITHIN_M of where it was SETTLED_FOR_S ago.
    """
    samples: list[tuple[float, float]] = []

    def check() -> bool:
        now, height = time.time(), read_height()
        samples.append((now, height))
        earlier = [h for t, h in samples if t <= now - SETTLED_FOR_S]
        if not earlier:
            return False
        return height > LIFTED_OFF_M and abs(height - earlier[-1]) < SETTLED_WITHIN_M

    return check


def result_name(result: int) -> str:
    entry = mavutil.mavlink.enums["MAV_RESULT"].get(result)
    return entry.name if entry else str(result)


def preflight(v: Vehicle) -> list[str]:
    """Reasons not to fly; empty when the aircraft is ready."""
    problems = []
    gps = v.latest.get("GPS_RAW_INT")
    if gps is None or gps.fix_type < GPS_FIX_3D or gps.satellites_visible < MIN_SATELLITES:
        sats = gps.satellites_visible if gps else "no"
        problems.append(f"no 3D GPS fix with {MIN_SATELLITES}+ satellites (have {sats})")
    if "HOME_POSITION" not in v.latest:
        problems.append("no home position yet — wait for the GPS fix")
    status = v.latest.get("SYS_STATUS")
    battery = status.battery_remaining if status else -1
    if battery < MIN_BATTERY_PERCENT:
        problems.append(f"battery at {battery} % (need {MIN_BATTERY_PERCENT} %)")
    state = v.latest.get("EXTENDED_SYS_STATE")
    if state and state.landed_state != mavutil.mavlink.MAV_LANDED_STATE_ON_GROUND:
        problems.append("aircraft is not on the ground")
    return problems


def fly(v: Vehicle, distance_m: float, height_m: float, speed: float) -> None:
    position = v.latest["GLOBAL_POSITION_INT"]
    target_lat = position.lat / 1e7 + distance_m / METRES_PER_DEGREE_LAT
    target_lon = position.lon / 1e7

    print("Taking off ...")
    # param7 (take-off height) is left NaN: DJI's own take-off to about 1 m, and the go-to below
    # climbs to --height on the way. With a height here the app climbs after take-off on its own,
    # and that climb, starting late, replaces a go-to sent in the meantime.
    v.command_long(mavutil.mavlink.MAV_CMD_NAV_TAKEOFF, math.nan, 0, 0, math.nan, 0, 0, math.nan)
    result = v.wait_ack(mavutil.mavlink.MAV_CMD_NAV_TAKEOFF, 5.0)
    if result != mavutil.mavlink.MAV_RESULT_ACCEPTED:
        raise RuntimeError(f"take-off refused: {result_name(result)}")
    deadline = time.time() + TAKEOFF_TIMEOUT_S
    take_off_over = hovering(v.height_m)
    while not take_off_over():
        if time.time() > deadline:
            raise TimeoutError("take-off did not happen in time")
        v.pump(0.2)

    print(f"Flying to {target_lat:.6f}, {target_lon:.6f} at {height_m:.0f} m ...")
    # COMMAND_INT carries the position as scaled integers: full precision, where COMMAND_LONG's
    # float params would round it to about half a metre.
    v.link.mav.command_int_send(
        v.system_id,
        AUTOPILOT_COMPONENT,
        mavutil.mavlink.MAV_FRAME_GLOBAL_RELATIVE_ALT_INT,
        mavutil.mavlink.MAV_CMD_DO_REPOSITION,
        0,
        0,
        speed,  # param1: ground speed, m/s
        mavutil.mavlink.MAV_DO_REPOSITION_FLAGS_CHANGE_MODE,  # param2
        0,  # param3: reserved
        math.nan,  # param4: yaw; NaN = keep the aircraft's own heading mode
        int(target_lat * 1e7),
        int(target_lon * 1e7),
        height_m,
    )
    # The aircraft answers IN_PROGRESS at once and about twice a second while it flies, then
    # ACCEPTED when it has arrived — so the final ack is the arrival.
    result = v.wait_ack(
        mavutil.mavlink.MAV_CMD_DO_REPOSITION,
        ARRIVAL_TIMEOUT_S,
        on_progress=lambda: print(f"   ... {v.height_m():.1f} m", end="\r"),
    )
    if result != mavutil.mavlink.MAV_RESULT_ACCEPTED:
        raise RuntimeError(f"go-to ended with {result_name(result)}")
    print("\nArrived.")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--ip", required=True, help="RC/phone IP")
    parser.add_argument("--distance", type=float, default=15.0, help="metres north (default 15)")
    parser.add_argument("--height", type=float, default=10.0, help="metres up (default 10)")
    parser.add_argument("--speed", type=float, default=3.0, help="m/s (default 3)")
    parser.add_argument("--fly", action="store_true", help="fly; without it this is a dry run")
    args = parser.parse_args()

    v = Vehicle(args.ip)
    v.pump(3.0)  # heartbeat in, telemetry starts flowing to us
    if v.system_id is None:
        print(f"No MAVLink vehicle answered at {args.ip}:{MAVLINK_PORT}.")
        return 1
    print(f"Vehicle system id {v.system_id} at {args.ip}")
    print(f"Plan: take off, fly {args.distance:.0f} m north at {args.height:.0f} m, come home.")
    problems = preflight(v)
    if problems:
        print("Not flying:\n  - " + "\n  - ".join(problems))
        return 1
    if not args.fly:
        print("Pre-flight checks passed. Dry run: add --fly to fly.")
        return 0

    try:
        fly(v, args.distance, args.height, args.speed)
    except (KeyboardInterrupt, TimeoutError, RuntimeError) as reason:
        print(f"\nStopping: {reason or 'Ctrl+C'}")
    finally:
        # Every way out of the flight ends here — arrived, timed out, refused or interrupted.
        print("Returning home.")
        v.command_long(mavutil.mavlink.MAV_CMD_NAV_RETURN_TO_LAUNCH)
        try:
            print("  ", result_name(v.wait_ack(mavutil.mavlink.MAV_CMD_NAV_RETURN_TO_LAUNCH, 5.0)))
        except TimeoutError as error:
            print("  ", error, "— use the RC's return button")
    return 0


if __name__ == "__main__":
    sys.exit(main())
