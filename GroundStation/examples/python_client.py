"""Take off, fly to a point, come back — with Lyrebird's Python client (DJIInterface).

The same flight as every script in this folder:

    1. take off
    2. fly to a point --distance metres north of the take-off spot, --height metres up
    3. wait until the aircraft reports it has arrived
    4. return home

DJIInterface hides the wire: set LB_TRANSPORT to "http", "mavlink" or "both" (the default) and
this script does not change. It is the shortest path from Python to a flying aircraft.

Safety: nothing moves unless you pass --fly; without it the script checks the aircraft and prints
the plan. The checks refuse to fly without a GPS fix, a home point, enough battery, or with the
app's manual override on; a DJI status warning is shown. Ctrl+C or a timeout sends the aircraft
home. The RC sticks override everything at any time — keep your hands on them.

Run from the repository root ($PY is the examples' virtual environment — see README.md here):

    PYTHONPATH=GroundStation/Python $PY GroundStation/examples/python_client.py --ip <RC_IP>
    PYTHONPATH=GroundStation/Python $PY GroundStation/examples/python_client.py --ip <RC_IP> --fly

With QGroundControl on the same computer and a MAVLink transport, give this script its own UDP
port so the two do not compete for 14550: LB_MAVLINK_PORT=14551.
"""

import argparse
import math
import sys
import time

from lyrebird_groundstation.dji_client import DJIInterface, discover_drone

METRES_PER_DEGREE_LAT = 111_320.0

MIN_SATELLITES = 8
MIN_BATTERY_PERCENT = 30
AIRBORNE_HEIGHT_M = 1.0
# DJI's automatic take-off ends in a hover at about 1 m (MAVLink reports exactly 1.0, so "above
# 1 m" never comes). Take-off is over once the aircraft is above LIFTED_OFF_M and its height has
# held within SETTLED_WITHIN_M for SETTLED_FOR_S.
LIFTED_OFF_M = 0.5
SETTLED_WITHIN_M = 0.1
SETTLED_FOR_S = 2.0
TAKEOFF_TIMEOUT_S = 30.0
ARRIVAL_TIMEOUT_S = 90.0


def point_north_of(latitude: float, longitude: float, metres: float) -> tuple[float, float]:
    """The point `metres` due north. Flat-earth is exact to centimetres at these distances."""
    return latitude + metres / METRES_PER_DEGREE_LAT, longitude


def preflight(drone: DJIInterface) -> list[str]:
    """Reasons not to fly; empty when the aircraft is ready."""
    t = drone.getTelemetry()
    problems = []
    if not t:
        return ["no telemetry yet — is the app running and on the same network?"]
    if t.get("satelliteCount", -1) < MIN_SATELLITES:
        problems.append(f"only {t.get('satelliteCount')} GPS satellites (need {MIN_SATELLITES})")
    if not t.get("homeSet", False):
        problems.append("home point not set yet — wait for the GPS fix")
    if t.get("batteryLevel", -1) < MIN_BATTERY_PERCENT:
        problems.append(f"battery at {t.get('batteryLevel')} % (need {MIN_BATTERY_PERCENT} %)")
    if t.get("altitude", 0.0) > AIRBORNE_HEIGHT_M:
        problems.append("aircraft is already airborne")
    if t.get("isManualOverrideActive", False):
        # The latch the sticks set: autonomous commands stay refused until it is cleared.
        problems.append("manual override is on — switch the app's MANUAL/AUTO toggle to AUTO")
    return problems


def dji_status(t: dict) -> str | None:
    """DJI's status banner when it is not NORMAL, e.g. CHLSTATUS_POOR (weak RC link).

    Most of these are warnings DJI still takes off with, so they are shown, not enforced; if one
    does stop the take-off, DJI refuses the command and the script gives up and sends it home.
    """
    if t.get("readyToTakeoff", True):
        return None
    return t.get("takeoffBlockReason", "UNKNOWN")


def wait_until(condition, timeout_s: float, what: str, drone: DJIInterface) -> None:
    """Poll `condition` until it is true, or raise TimeoutError naming `what`.

    Prints the height and how many telemetry updates have arrived once a second, so a wait
    that runs out shows whether the aircraft was slow or the telemetry had stopped.
    """
    deadline = time.time() + timeout_s
    next_report = time.time() + 1.0
    while not condition():
        if time.time() > deadline:
            raise TimeoutError(f"{what} did not happen within {timeout_s:.0f} s")
        if time.time() >= next_report:
            height = drone.getTelemetry().get("altitude", 0.0)
            print(f"   ... {height:.1f} m  (telemetry #{drone.getTelemetrySequence()})")
            next_report += 1.0
        time.sleep(0.2)

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


def fly(drone: DJIInterface, distance_m: float, height_m: float, speed_mps: float) -> None:
    here = drone.getLocation()
    target_lat, target_lon = point_north_of(here["latitude"], here["longitude"], distance_m)
    heading = drone.getHeading()

    print("Taking off ...")
    reply = drone.requestSendTakeOff()
    print("  ", reply)
    if reply.startswith("REJECTED") or not reply:
        raise RuntimeError(f"take-off was not accepted: {reply or 'no reply'}")
    wait_until(
        hovering(lambda: drone.getTelemetry().get("altitude", 0.0)),
        TAKEOFF_TIMEOUT_S,
        "take-off",
        drone,
    )

    print(f"Flying to {target_lat:.6f}, {target_lon:.6f} at {height_m:.0f} m ...")
    # Navigation over HTTP needs virtual stick control; over MAVLink the app takes it itself.
    drone.requestSendEnableVirtualStick()
    # Arrival heading = the current heading, so the aircraft does not spin round when it gets there.
    seq = drone.requestSendGoToWaypointNoseForward(
        target_lat, target_lon, height_m, heading, speed=speed_mps
    )
    if seq is None:
        raise RuntimeError("the go-to was refused — see the app's message on the RC")
    # Arrival is matched on the command's own seq, so a "reached" left over from an earlier
    # command can never be read as this one.
    wait_until(
        lambda: drone.isWaypointReached(seq), ARRIVAL_TIMEOUT_S, "arrival at the target", drone
    )
    print("Arrived.")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--ip", default="", help="RC/phone IP; discovered when omitted")
    parser.add_argument("--distance", type=float, default=15.0, help="metres north (default 15)")
    parser.add_argument("--height", type=float, default=10.0, help="metres up (default 10)")
    parser.add_argument("--speed", type=float, default=3.0, help="m/s (default 3)")
    parser.add_argument("--fly", action="store_true", help="fly; without it this is a dry run")
    args = parser.parse_args()

    drone = DJIInterface(args.ip, discover_callback=discover_drone, query_config_name=True)
    if not drone.IP_RC:
        print("No aircraft found. Pass --ip <RC_IP>.")
        return 1
    drone.startTelemetryStream()
    try:
        time.sleep(2.0)  # let the first telemetry arrive
        problems = preflight(drone)
        print(f"Aircraft {drone.drone_name} at {drone.IP_RC} over {drone.transport.value}")
        print(f"Plan: take off, fly {args.distance:.0f} m north at {args.height:.0f} m, come home.")
        status = dji_status(drone.getTelemetry())
        if status:
            print(f"DJI status: {status} — check the RC screen; DJI decides whether it takes off.")
        if problems:
            print("Not flying:\n  - " + "\n  - ".join(problems))
            return 1
        if not args.fly:
            print("Pre-flight checks passed. Dry run: add --fly to fly.")
            return 0

        try:
            fly(drone, args.distance, args.height, args.speed)
        except (KeyboardInterrupt, TimeoutError, RuntimeError) as reason:
            print(f"Stopping: {reason or 'Ctrl+C'}")
        finally:
            # Every way out of the flight ends here — arrived, timed out, refused or interrupted.
            print("Returning home.")
            reply = drone.requestSendRTH()
            print("  ", reply or "no reply")
            if not reply or reply.startswith("REJECTED"):
                print("   Return to home was not confirmed — use the RC's return button.")
        return 0
    finally:
        drone.stopTelemetryStream()


if __name__ == "__main__":
    sys.exit(main())
