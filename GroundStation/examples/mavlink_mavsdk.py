"""Take off, fly to a point, come back — with MAVSDK.

The same flight as every script in this folder:

    1. take off
    2. fly to a point --distance metres north of the take-off spot, --height metres up
    3. wait until the aircraft has arrived
    4. return home

MAVSDK is how most autonomy software talks to a MAVLink vehicle, and Lyrebird presents each DJI
aircraft as one (it reports itself as PX4), so the standard Action and Telemetry plugins drive it.

Needs an app build that reads MAVSDK's mode changes (2026-10-14 or later): goto_location() and
return_to_launch() both start by switching the PX4 mode (Hold, Return), which older builds
refused as UNSUPPORTED. Two Lyrebird specifics are handled here:

- set_takeoff_altitude() is not used: on PX4 it writes the MIS_TAKEOFF_ALT parameter, which
  Lyrebird does not have. The aircraft takes off to DJI's default height and the go-to climbs.
- goto_location() takes an "absolute" altitude, but Lyrebird's altitudes are heights above the
  take-off point (it reports 0 m on the ground), so --height is passed as it is.

Arrival is judged from the telemetry position rather than from goto_location()'s own result: the
aircraft acknowledges a go-to with IN_PROGRESS while flying and ACCEPTED on arrival, and how long
a MAVSDK version waits for that is not something to stake a flight on.

Safety: nothing moves unless you pass --fly; without it the script checks the aircraft and prints
the plan. The checks refuse to fly without a 3D GPS fix, a home position, enough battery, or with
the aircraft already in the air. Ctrl+C or a timeout sends the aircraft home. The RC sticks
override everything at any time — keep your hands on them.

    $PY GroundStation/examples/mavlink_mavsdk.py --ip <RC_IP>          # dry run
    $PY GroundStation/examples/mavlink_mavsdk.py --ip <RC_IP> --fly    # fly

Needs `mavsdk-grpc==3.17.4`. $PY is the examples' virtual environment — see README.md here.

mavsdk-grpc is MAVSDK's classic async API under its new package name; the plain "mavsdk"
package switched to a different native API with version 4.
"""

import argparse
import asyncio
import math
import sys
import time

from mavsdk_grpc import System
from mavsdk_grpc.telemetry import FixType, LandedState

MAVLINK_PORT = 14550
METRES_PER_DEGREE_LAT = 111_320.0

MIN_SATELLITES = 8
MIN_BATTERY_PERCENT = 30
# DJI's automatic take-off ends in a hover at about 1 m (MAVLink reports exactly 1.0, so "above
# 1 m" never comes). Take-off is over once the aircraft is above LIFTED_OFF_M and its height has
# held within SETTLED_WITHIN_M for SETTLED_FOR_S.
LIFTED_OFF_M = 0.5
SETTLED_WITHIN_M = 0.1
SETTLED_FOR_S = 2.0
ARRIVED_WITHIN_M = 2.0
CONNECT_TIMEOUT_S = 15.0
READ_TIMEOUT_S = 5.0
TAKEOFF_TIMEOUT_S = 30.0
ARRIVAL_TIMEOUT_S = 90.0


async def first(stream, timeout_s: float = READ_TIMEOUT_S):
    """The next value of a MAVSDK telemetry stream, or None if none arrives in time."""

    async def read():
        async for value in stream:
            return value

    try:
        return await asyncio.wait_for(read(), timeout_s)
    except asyncio.TimeoutError:
        return None


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


def metres_between(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    north = (lat2 - lat1) * METRES_PER_DEGREE_LAT
    east = (lon2 - lon1) * METRES_PER_DEGREE_LAT * math.cos(math.radians(lat1))
    return math.hypot(north, east)


async def preflight(drone: System) -> list[str]:
    """Reasons not to fly; empty when the aircraft is ready."""
    problems = []
    gps = await first(drone.telemetry.gps_info())
    gps_issue = gps_problem(gps)
    if gps_issue:
        problems.append(gps_issue)
    if await first(drone.telemetry.home()) is None:
        problems.append("no home position yet — wait for the GPS fix")
    battery = await first(drone.telemetry.battery())
    battery_issue = battery_problem(battery)
    if battery_issue:
        problems.append(battery_issue)
    landed = await first(drone.telemetry.landed_state())
    if landed is not None and landed != LandedState.ON_GROUND:
        problems.append("aircraft is not on the ground")
    return problems


def gps_problem(gps) -> str | None:
    has_fix = gps is not None and gps.fix_type.value >= FixType.FIX_3D.value
    if not has_fix or gps.num_satellites < MIN_SATELLITES:
        sats = gps.num_satellites if gps else "no"
        return f"no 3D GPS fix with {MIN_SATELLITES}+ satellites (have {sats})"
    return None


def battery_problem(battery) -> str | None:
    percent = battery.remaining_percent if battery else -1
    if 0 <= percent <= 1:  # older MAVSDK versions report a fraction
        percent *= 100
    if percent < MIN_BATTERY_PERCENT:
        return f"battery at {percent:.0f} % (need {MIN_BATTERY_PERCENT} %)"
    return None


async def fly(drone: System, distance_m: float, height_m: float) -> None:
    start = await first(drone.telemetry.position())
    heading = await first(drone.telemetry.heading())
    target_lat = start.latitude_deg + distance_m / METRES_PER_DEGREE_LAT
    target_lon = start.longitude_deg

    print("Taking off ...")
    await drone.action.arm()  # PX4 expects it; DJI arms itself on take-off, so this is a no-op
    await drone.action.takeoff()
    loop = asyncio.get_running_loop()
    deadline = loop.time() + TAKEOFF_TIMEOUT_S
    latest_height = 0.0
    take_off_over = hovering(lambda: latest_height)
    async for position in drone.telemetry.position():
        latest_height = position.relative_altitude_m
        if take_off_over():
            break
        if loop.time() > deadline:
            raise TimeoutError("take-off did not happen in time")

    print(f"Flying to {target_lat:.6f}, {target_lon:.6f} at {height_m:.0f} m ...")
    goto = asyncio.create_task(
        drone.action.goto_location(target_lat, target_lon, height_m, heading.heading_deg)
    )
    deadline = loop.time() + ARRIVAL_TIMEOUT_S
    async for position in drone.telemetry.position():
        distance = metres_between(
            position.latitude_deg, position.longitude_deg, target_lat, target_lon
        )
        if distance < ARRIVED_WITHIN_M and abs(position.relative_altitude_m - height_m) < 1.0:
            break
        if goto.done() and goto.exception() is not None:
            # A refused go-to never moves the aircraft; say so instead of waiting it out.
            raise RuntimeError(f"the go-to was refused: {goto.exception()}")
        if loop.time() > deadline:
            raise TimeoutError("arrival at the target did not happen in time")
    print("Arrived.")


async def run(args) -> int:
    drone = System()  # starts MAVSDK's bundled mavsdk_server in the background
    # udpout: MAVSDK heartbeats to the aircraft, and Lyrebird streams back to whoever it has
    # heard from. Listening on 14550 instead would compete with QGroundControl for that port.
    await drone.connect(system_address=f"udpout://{args.ip}:{MAVLINK_PORT}")

    async def connected():
        async for state in drone.core.connection_state():
            if state.is_connected:
                return

    try:
        await asyncio.wait_for(connected(), CONNECT_TIMEOUT_S)
    except asyncio.TimeoutError:
        print(f"No MAVLink vehicle answered at {args.ip}:{MAVLINK_PORT}.")
        return 1
    print(f"Connected to the aircraft at {args.ip}")
    print(f"Plan: take off, fly {args.distance:.0f} m north at {args.height:.0f} m, come home.")
    problems = await preflight(drone)
    if problems:
        print("Not flying:\n  - " + "\n  - ".join(problems))
        return 1
    if not args.fly:
        print("Pre-flight checks passed. Dry run: add --fly to fly.")
        return 0

    try:
        await fly(drone, args.distance, args.height)
    except (asyncio.CancelledError, Exception) as reason:
        # CancelledError is how Ctrl+C reaches a coroutine under asyncio.run.
        print(f"Stopping: {reason or 'Ctrl+C'}")
    finally:
        # Every way out of the flight ends here — arrived, timed out, refused or interrupted.
        print("Returning home.")
        try:
            await drone.action.return_to_launch()
        except Exception as error:  # tell the pilot, whatever went wrong
            print(f"  Return to launch failed: {error} — use the RC's return button")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--ip", required=True, help="RC/phone IP")
    parser.add_argument("--distance", type=float, default=15.0, help="metres north (default 15)")
    parser.add_argument("--height", type=float, default=10.0, help="metres up (default 10)")
    parser.add_argument("--fly", action="store_true", help="fly; without it this is a dry run")
    return asyncio.run(run(parser.parse_args()))


if __name__ == "__main__":
    sys.exit(main())
