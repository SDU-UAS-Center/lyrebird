import re
import subprocess

from launch import LaunchDescription
from launch.actions import DeclareLaunchArgument, OpaqueFunction
from launch.substitutions import LaunchConfiguration
from launch_ros.actions import Node


def get_ip_from_mac(mac_addr):
    """
    Queries the ARP table via `ip neigh` to find the IP address corresponding to the given MAC.
    Returns the first matching IP, or None if not found.
    """
    try:
        # Execute the "ip neigh show" command
        output = subprocess.check_output(["ip", "neigh", "show"]).decode("utf-8")
    except subprocess.CalledProcessError:
        return None

    pattern = re.compile(
        r"(?P<ip>\d+\.\d+\.\d+\.\d+)\s+dev\s+\S+\s+lladdr\s+" + re.escape(mac_addr), re.IGNORECASE
    )
    for line in output.splitlines():
        m = pattern.search(line)
        if m:
            return m.group("ip")
    return None


def launch_setup(context, *args, **kwargs):
    # Pure-rename legacy topics only (video, lrf, settings, capture results...):
    # a plain ROS2 remap can't restore the topics that consolidate several old
    # differently-typed topics into one new struct message (vehicle_command,
    # battery_status, etc.) -- see lyrebird_controller/topics.py.
    legacy_topics = LaunchConfiguration("legacy_topics").perform(context).lower() == "true"
    remappings = None
    if legacy_topics:
        from lyrebird_controller.topics import LEGACY_TOPIC_MAP

        remappings = list(LEGACY_TOPIC_MAP.items())

    ip_drone_1 = get_ip_from_mac("58:79:e0:09:f7:3a") or "10.222.241.32"

    drones = [
        {"namespace": "drone_1", "ip_rc": ip_drone_1},
        {"namespace": "drone_2", "ip_rc": "192.168.154.28"},
        {"namespace": "drone_3", "ip_rc": "192.168.137.106"},
    ]

    actions = []

    # Add RTSP streaming node
    drone = drones[0]
    actions.append(
        Node(
            package="lyrebird_videofeed",
            executable="lyrebird_videofeed",
            namespace=drone["namespace"],
            parameters=[{"ip_rc": drone["ip_rc"]}],
            remappings=remappings,
        )
    )

    # Add drone nodes dynamically
    for drone in drones:
        actions.append(
            Node(
                package="lyrebird_controller",
                executable="lyrebird_controller",
                namespace=drone["namespace"],
                parameters=[{"ip_rc": drone["ip_rc"]}],
                remappings=remappings,
            )
        )

    return actions


def generate_launch_description():
    return LaunchDescription(
        [
            DeclareLaunchArgument(
                "legacy_topics",
                default_value="false",
                description=(
                    "Also expose old flat topic names (pure renames only -- see "
                    "lyrebird_controller/topics.py) alongside the new fmu/in|out/ ones."
                ),
            ),
            OpaqueFunction(function=launch_setup),
        ]
    )
