import os

from launch import LaunchDescription
from launch.actions import DeclareLaunchArgument, OpaqueFunction, TimerAction
from launch.substitutions import LaunchConfiguration
from launch_ros.actions import Node

# How often (seconds) to re-scan the network for newly joined drones after
# the initial launch, so a drone powered on later still gets picked up
# without restarting the whole ROS 2 stack.
DISCOVERY_PERIOD = float(os.environ.get("ROS_DISCOVERY_PERIOD", "15.0"))


def _load_discovery_function():
    # Runs from a sourced colcon workspace (GroundStation container, ros-monitor image),
    # where lyrebird_controller is importable without manual sys.path surgery.
    from lyrebird_controller.dji_interface import discover_all_drones

    return discover_all_drones


def _load_legacy_topic_map():
    from lyrebird_controller.topics import LEGACY_TOPIC_MAP

    return LEGACY_TOPIC_MAP


def _namespace_for(name, index):
    clean_name = "".join(c if c.isalnum() or c == "_" else "_" for c in name)
    return f"drone_{index + 1}" if not clean_name or clean_name == "UNKNOWN" else clean_name


def _create_controller_node(ip, name, index, remappings):
    namespace = _namespace_for(name, index)
    return Node(
        package="lyrebird_controller",
        executable="lyrebird_controller",
        name=f"lyrebird_controller_{namespace}",
        namespace=namespace,
        output="screen",
        parameters=[{"ip_rc": ip}],
        remappings=remappings,
    )


def _scan_for_new_drones(
    discover_all_drones, known_namespaces, next_index, remappings, verbose=True
):
    """Runs one discovery pass and returns Node actions for any drone not
    already launched. `known_namespaces`/`next_index` are mutated in place so
    repeated calls keep assigning stable, non-overlapping namespaces."""
    try:
        drones = discover_all_drones(timeout=5.0, verbose=verbose)
    except Exception as exc:
        print(f"Drone discovery failed: {exc}")
        return []

    actions = []
    for ip, name in drones:
        namespace = _namespace_for(name, next_index[0])
        if namespace in known_namespaces:
            continue
        print(f"Found drone: {name} at {ip} (namespace: {namespace})")
        actions.append(_create_controller_node(ip, name, next_index[0], remappings))
        known_namespaces.add(namespace)
        next_index[0] += 1
    return actions


def _periodic_rescan(context, discover_all_drones, known_namespaces, next_index, remappings):
    actions = _scan_for_new_drones(
        discover_all_drones, known_namespaces, next_index, remappings, verbose=False
    )
    actions.append(
        TimerAction(
            period=DISCOVERY_PERIOD,
            actions=[
                OpaqueFunction(
                    function=lambda ctx: _periodic_rescan(
                        ctx, discover_all_drones, known_namespaces, next_index, remappings
                    )
                )
            ],
        )
    )
    return actions


def launch_setup(context, *args, **kwargs):
    try:
        discover_all_drones = _load_discovery_function()
    except ImportError:
        print("Could not import lyrebird_controller.dji_interface.")
        return []

    # Pure-rename legacy topics only (video, lrf, settings, capture results...):
    # a plain ROS2 remap can't restore the topics that consolidate several old
    # differently-typed topics into one new struct message (vehicle_command,
    # battery_status, etc.) -- see lyrebird_controller/topics.py.
    legacy_topics = LaunchConfiguration("legacy_topics").perform(context).lower() == "true"
    remappings = list(_load_legacy_topic_map().items()) if legacy_topics else None

    print("Discovering drones...")
    known_namespaces = set()
    next_index = [0]
    actions = _scan_for_new_drones(discover_all_drones, known_namespaces, next_index, remappings)
    if not actions:
        print("No drones found via auto-discovery; will keep looking periodically.")

    # Keep discovering newly-joined drones after the initial launch instead
    # of only ever launching whatever this first scan happened to catch.
    actions.append(
        TimerAction(
            period=DISCOVERY_PERIOD,
            actions=[
                OpaqueFunction(
                    function=lambda ctx: _periodic_rescan(
                        ctx, discover_all_drones, known_namespaces, next_index, remappings
                    )
                )
            ],
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
