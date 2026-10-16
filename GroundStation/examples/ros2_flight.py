"""Take off, fly to a point, come back — through ROS 2 and the lyrebird_controller node.

The same flight as every script in this folder:

    1. take off
    2. fly to a point --distance metres north of the take-off spot, --height metres up
    3. wait until the aircraft reports it has arrived
    4. return home

The controller (started by the ground-station container's fleet launch) gives each aircraft a
namespace and PX4-style topics: commands in on fmu/in/..., telemetry out on fmu/out/.... The
messages are lyrebird_msgs, which borrow PX4's names and many of its fields but are not px4_msgs;
the ROS 2 page of the docs has the comparison. This script uses:

    fmu/in/vehicle_command        take-off (22), return to launch (20)
    fmu/in/trajectory_setpoint    the go-to: a GPS position, speed and yaw mode
    fmu/out/vehicle_command_ack   the go-to's seq
    fmu/out/mission_result        waypoint_reached + waypoint_seq: arrival of that go-to
    fmu/out/vehicle_*, battery_status, home_position   the pre-flight checks

Safety: nothing moves unless you pass --fly; without it the script checks the aircraft and prints
the plan. The checks refuse to fly without a GPS fix, a home point, enough battery, or with the
app's manual override on; a DJI status warning is shown. Ctrl+C or a timeout sends the aircraft
home. The RC sticks override everything at any time — keep your hands on them.

Runs where lyrebird_msgs is built: inside the ground-station container. From the repository root,
with the container running (./GroundStation/run_docker.sh):

    docker exec -i $(docker ps -q --filter ancestor=lyrebird-gs) bash -c \\
        'source /opt/ros/$ROS_DISTRO/setup.bash && source /ros2_ws/install/setup.bash && \\
         python3 - --drone mini7' < GroundStation/examples/ros2_flight.py

Add --fly after --drone to fly. --drone is the aircraft's namespace: its name in the app.
"""

import argparse
import sys
import time

import rclpy
from lyrebird_msgs.msg import (
    BatteryStatus,
    HomePosition,
    MissionResult,
    TrajectorySetpoint,
    VehicleCommand,
    VehicleCommandAck,
    VehicleGlobalPosition,
    VehicleLocalPosition,
    VehicleStatus,
)
from rclpy.node import Node
from rclpy.qos import DurabilityPolicy, HistoryPolicy, QoSProfile, ReliabilityPolicy

METRES_PER_DEGREE_LAT = 111_320.0
MAV_CMD_NAV_WAYPOINT = 16  # what the controller acks a trajectory_setpoint as

MIN_SATELLITES = 8
MIN_BATTERY_FRACTION = 0.30
AIRBORNE_HEIGHT_M = 1.0
# DJI's automatic take-off ends in a hover at about 1 m. Take-off is over once the aircraft is
# above LIFTED_OFF_M and its height has held within SETTLED_WITHIN_M for SETTLED_FOR_S.
LIFTED_OFF_M = 0.5
SETTLED_WITHIN_M = 0.1
SETTLED_FOR_S = 2.0
TELEMETRY_TIMEOUT_S = 10.0
TAKEOFF_TIMEOUT_S = 30.0
ACK_TIMEOUT_S = 10.0
ARRIVAL_TIMEOUT_S = 90.0

# PX4's own split, which the controller follows: telemetry is best effort, commands must arrive.
QOS_TELEMETRY = QoSProfile(
    reliability=ReliabilityPolicy.BEST_EFFORT,
    durability=DurabilityPolicy.VOLATILE,
    history=HistoryPolicy.KEEP_LAST,
    depth=5,
)
QOS_COMMANDS = QoSProfile(
    reliability=ReliabilityPolicy.RELIABLE,
    durability=DurabilityPolicy.VOLATILE,
    history=HistoryPolicy.KEEP_LAST,
    depth=10,
)


class Flight(Node):
    """The latest of each telemetry message, plus the two command publishers."""

    def __init__(self, drone: str):
        super().__init__("lyrebird_example_flight")
        ns = f"/{drone}/fmu"
        self.latest: dict[str, object] = {}
        self.acks: list[VehicleCommandAck] = []
        for name, msg_type in (
            ("vehicle_global_position", VehicleGlobalPosition),
            ("vehicle_local_position", VehicleLocalPosition),
            ("vehicle_status", VehicleStatus),
            ("battery_status", BatteryStatus),
            ("home_position", HomePosition),
            ("mission_result", MissionResult),
        ):
            self.create_subscription(
                msg_type, f"{ns}/out/{name}", self._keeper(name), QOS_TELEMETRY
            )
        self.create_subscription(
            VehicleCommandAck, f"{ns}/out/vehicle_command_ack", self.acks.append, QOS_COMMANDS
        )
        self.commands = self.create_publisher(
            VehicleCommand, f"{ns}/in/vehicle_command", QOS_COMMANDS
        )
        self.setpoints = self.create_publisher(
            TrajectorySetpoint, f"{ns}/in/trajectory_setpoint", QOS_COMMANDS
        )

    def _keeper(self, name: str):
        def keep(msg) -> None:
            self.latest[name] = msg

        return keep

    def now_us(self) -> int:
        return self.get_clock().now().nanoseconds // 1000

    def command(self, command: int) -> None:
        self.commands.publish(VehicleCommand(timestamp=self.now_us(), command=command))

    def height_m(self) -> float:
        local = self.latest.get("vehicle_local_position")
        return local.alt if local else 0.0

    def wait_until(self, condition, timeout_s: float, what: str, show_height: bool = False) -> None:
        """Spin until `condition` is true, or raise TimeoutError naming `what`."""
        deadline = time.time() + timeout_s
        shown = False
        try:
            while not condition():
                if time.time() > deadline:
                    raise TimeoutError(f"{what} did not happen within {timeout_s:.0f} s")
                if show_height:
                    print(f"\r   ... {self.height_m():.1f} m ", end="", flush=True)
                    shown = True
                rclpy.spin_once(self, timeout_sec=0.2)
        finally:
            if shown:
                print()


def hovering(read_height):
    """A condition that is true once DJI's take-off is over: off the ground and holding height."""
    samples: list[tuple[float, float]] = []

    def check() -> bool:
        now, height = time.time(), read_height()
        samples.append((now, height))
        earlier = [h for t, h in samples if t <= now - SETTLED_FOR_S]
        if not earlier:
            return False
        return height > LIFTED_OFF_M and abs(height - earlier[-1]) < SETTLED_WITHIN_M

    return check


def preflight(f: Flight) -> list[str]:
    """Reasons not to fly; empty when the aircraft is ready."""
    position = f.latest["vehicle_global_position"]
    problems = []
    if position.satellite_count < MIN_SATELLITES or (position.lat == 0.0 and position.lon == 0.0):
        problems.append(
            f"no GPS position with {MIN_SATELLITES}+ satellites (have {position.satellite_count})"
        )
    if not f.latest["home_position"].valid_hpos:
        problems.append("home point not set yet — wait for the GPS fix")
    battery = f.latest["battery_status"].remaining
    if battery < MIN_BATTERY_FRACTION:
        problems.append(f"battery at {battery:.0%} (need {MIN_BATTERY_FRACTION:.0%})")
    if f.height_m() > AIRBORNE_HEIGHT_M:
        problems.append("aircraft is already airborne")
    if f.latest["vehicle_status"].manual_override_active:
        problems.append("manual override is on — switch the app's MANUAL/AUTO toggle to AUTO")
    return problems


def fly(f: Flight, distance_m: float, height_m: float, speed: float) -> None:
    start = f.latest["vehicle_global_position"]
    target_lat = start.lat + distance_m / METRES_PER_DEGREE_LAT
    target_lon = start.lon
    heading = f.latest["vehicle_local_position"].heading

    print("Taking off ...")
    f.command(VehicleCommand.VEHICLE_CMD_NAV_TAKEOFF)
    f.wait_until(hovering(f.height_m), TAKEOFF_TIMEOUT_S, "take-off", show_height=True)

    print(f"Flying to {target_lat:.6f}, {target_lon:.6f} at {height_m:.0f} m ...")
    f.acks.clear()
    f.setpoints.publish(
        TrajectorySetpoint(
            timestamp=f.now_us(),
            latitude=target_lat,
            longitude=target_lon,
            altitude=float(height_m),
            # Nose-forward: the aircraft turns to face the leg and, on arrival, back to `yaw` —
            # the current heading, so it does not spin round at the end.
            yaw=float(heading),
            speed=float(speed),
            yaw_mode=TrajectorySetpoint.YAW_MODE_NOSE_FORWARD,
        )
    )
    f.wait_until(
        lambda: any(a.command == MAV_CMD_NAV_WAYPOINT for a in f.acks),
        ACK_TIMEOUT_S,
        "the go-to's acknowledgement",
    )
    seq = next(a.seq for a in f.acks if a.command == MAV_CMD_NAV_WAYPOINT)
    if seq < 0:
        raise RuntimeError("the go-to was refused — see the app's message on the RC")

    # Arrival is matched on this go-to's own seq: mission_result's flag is shared by every go-to,
    # so a "reached" left over from an earlier one must not end this one.
    def arrived() -> bool:
        result = f.latest.get("mission_result")
        return bool(result and result.waypoint_reached and result.waypoint_seq == seq)

    f.wait_until(arrived, ARRIVAL_TIMEOUT_S, "arrival at the target", show_height=True)
    print("Arrived.")


def run(args) -> int:
    f = Flight(args.drone)
    needed = ("vehicle_global_position", "vehicle_local_position", "vehicle_status",
              "battery_status", "home_position")
    try:
        f.wait_until(lambda: all(n in f.latest for n in needed), TELEMETRY_TIMEOUT_S, "telemetry")
    except TimeoutError:
        print(f"No telemetry on /{args.drone}/fmu/out/... — is the fleet container running, "
              f"and is the aircraft's name in the app '{args.drone}'?")
        return 1
    print(f"Aircraft {args.drone} over ROS 2")
    print(f"Plan: take off, fly {args.distance:.0f} m north at {args.height:.0f} m, come home.")
    status = f.latest["vehicle_status"]
    if not status.ready_to_takeoff:
        print(f"DJI status: {status.takeoff_block_reason} — check the RC screen; DJI decides "
              "whether it takes off.")
    problems = preflight(f)
    if problems:
        print("Not flying:\n  - " + "\n  - ".join(problems))
        return 1
    if not args.fly:
        print("Pre-flight checks passed. Dry run: add --fly to fly.")
        return 0

    try:
        fly(f, args.distance, args.height, args.speed)
    except (KeyboardInterrupt, TimeoutError, RuntimeError) as reason:
        print(f"Stopping: {reason or 'Ctrl+C'}")
    finally:
        # Every way out of the flight ends here — arrived, timed out, refused or interrupted.
        print("Returning home.")
        f.command(VehicleCommand.VEHICLE_CMD_NAV_RETURN_TO_LAUNCH)
        # Let the reliable publisher deliver it before the node goes away.
        for _ in range(5):
            rclpy.spin_once(f, timeout_sec=0.1)
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--drone", required=True, help="the aircraft's namespace (its app name)")
    parser.add_argument("--distance", type=float, default=15.0, help="metres north (default 15)")
    parser.add_argument("--height", type=float, default=10.0, help="metres up (default 10)")
    parser.add_argument("--speed", type=float, default=3.0, help="m/s (default 3)")
    parser.add_argument("--fly", action="store_true", help="fly; without it this is a dry run")
    args = parser.parse_args()
    rclpy.init()
    try:
        return run(args)
    finally:
        rclpy.shutdown()


if __name__ == "__main__":
    sys.exit(main())
