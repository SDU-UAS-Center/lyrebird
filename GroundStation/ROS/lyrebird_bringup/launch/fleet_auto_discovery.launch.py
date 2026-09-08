from launch import LaunchDescription
from launch.actions import DeclareLaunchArgument, OpaqueFunction
from launch.substitutions import LaunchConfiguration
from launch_ros.actions import Node


def launch_setup(context, *args, **kwargs):
    # Pure-rename legacy topics only (video, lrf, settings, capture results...): a plain
    # ROS2 remap can't restore the topics that consolidate several old differently-typed
    # topics into one new struct message -- see lyrebird_controller/topics.py. Passed as
    # unqualified remap rules, so they apply to every DjiNode this manager creates inside its
    # own process, not just to the manager node itself.
    legacy_topics = LaunchConfiguration("legacy_topics").perform(context).lower() == "true"
    remappings = None
    if legacy_topics:
        from lyrebird_controller.topics import LEGACY_TOPIC_MAP

        remappings = list(LEGACY_TOPIC_MAP.items())

    # Only override the node's own defaults (fleet_settings.yaml, or its module constants) when
    # a launch argument was actually given, so leaving these alone keeps those defaults.
    parameters = []
    fleet_settings_file = LaunchConfiguration("fleet_settings_file").perform(context)
    if fleet_settings_file:
        parameters.append({"fleet_settings_file": fleet_settings_file})
    discovery_period_sec = LaunchConfiguration("discovery_period_sec").perform(context)
    if discovery_period_sec:
        parameters.append({"discovery_period_sec": float(discovery_period_sec)})

    return [
        Node(
            package="lyrebird_controller",
            executable="lyrebird_fleet_auto_discovery",
            name="fleet_auto_discovery_manager",
            output="screen",
            parameters=parameters,
            remappings=remappings,
        )
    ]


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
            DeclareLaunchArgument(
                "fleet_settings_file",
                default_value="",
                description=(
                    "Path to a fleet_settings.yaml to use instead of lyrebird_controller's "
                    "bundled config/fleet_settings.yaml (see that file for the full list of "
                    "keys: fleet-wide app settings, transport, rth_altitude_range, discovery "
                    "timing, MAVLink ports, and per-drone overrides)."
                ),
            ),
            DeclareLaunchArgument(
                "discovery_period_sec",
                default_value="",
                description=(
                    "Override fleet_settings.yaml's discovery_period_sec (seconds between "
                    "rescans) without editing the file -- e.g. a shorter period for testing."
                ),
            ),
            OpaqueFunction(function=launch_setup),
        ]
    )
