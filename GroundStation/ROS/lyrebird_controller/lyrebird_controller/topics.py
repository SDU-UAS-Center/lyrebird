"""Central registry of Lyrebird's ROS 2 topic names, types, and QoS profiles.

Single source of truth for the PX4-DDS-style `/fmu/in/...` (ground -> drone) and
`/fmu/out/...` (drone -> ground) topics, so `controller.py` and `ros_monitor.py`
(a separate container image, see GroundStation/video_test/ros_monitor/Dockerfile)
no longer hand-duplicate the same topic list -- they both import this module.

LEGACY_TOPIC_MAP also drives the `legacy_topics` launch argument in
lyrebird_bringup: when enabled, each node remaps every new name below to its
old flat name so pre-migration consumers keep working unchanged.

A plain ROS2 `remappings=` entry only renames a topic -- it cannot change what
message type flows through it. That's enough for every topic whose *type*
didn't change (video, lrf_*, settings, capture/media/download results,
goto_trajectory_dji_native, set_setting, download_media), so those are listed
in LEGACY_TOPIC_MAP for the bringup launch files to remap directly. It is
*not* enough for the topics that consolidate several old differently-typed
scalar topics into one new struct message (vehicle_command,
trajectory_setpoint, manual_control_setpoint, and every bundled telemetry
topic below) -- renaming "battery_status" to "battery_level" would still put a
BatteryStatus message on a topic old subscribers expect to carry a Float64.
For those, controller.py's own `legacy_topics` node parameter drives a small
compatibility shim that publishes/subscribes the exact old topics and types
alongside the new ones, sourced from the same underlying data.
"""

from rclpy.qos import DurabilityPolicy, HistoryPolicy, QoSProfile, ReliabilityPolicy

from lyrebird_msgs.msg import (
    BatteryStatus,
    CameraStatus,
    GimbalStatus,
    HomePosition,
    ManualControlSetpoint,
    MissionResult,
    TrajectorySetpoint,
    VehicleCommand,
    VehicleCommandAck,
    VehicleGlobalPosition,
    VehicleLocalPosition,
    VehicleStatus,
)
from sensor_msgs.msg import Image, NavSatFix
from std_msgs.msg import String

IN_PREFIX = "fmu/in/"
OUT_PREFIX = "fmu/out/"

# Matches PX4's own rationale: telemetry is high-rate and lossy-tolerant
# (best effort, volatile, short history); commands and acks must arrive.
QOS_SENSOR = QoSProfile(
    reliability=ReliabilityPolicy.BEST_EFFORT,
    durability=DurabilityPolicy.VOLATILE,
    history=HistoryPolicy.KEEP_LAST,
    depth=5,
)
QOS_RELIABLE = QoSProfile(
    reliability=ReliabilityPolicy.RELIABLE,
    durability=DurabilityPolicy.VOLATILE,
    history=HistoryPolicy.KEEP_LAST,
    depth=10,
)

# topic constant -> (type, qos)
IN_TOPICS = {
    "vehicle_command": (VehicleCommand, QOS_RELIABLE),
    "trajectory_setpoint": (TrajectorySetpoint, QOS_RELIABLE),
    "manual_control_setpoint": (ManualControlSetpoint, QOS_RELIABLE),
    "goto_trajectory_dji_native": (String, QOS_RELIABLE),
    "set_setting": (String, QOS_RELIABLE),
    "download_media": (String, QOS_RELIABLE),
}

OUT_TOPICS = {
    "vehicle_command_ack": (VehicleCommandAck, QOS_RELIABLE),
    "vehicle_global_position": (VehicleGlobalPosition, QOS_SENSOR),
    "home_position": (HomePosition, QOS_SENSOR),
    "vehicle_local_position": (VehicleLocalPosition, QOS_SENSOR),
    "battery_status": (BatteryStatus, QOS_SENSOR),
    "vehicle_status": (VehicleStatus, QOS_SENSOR),
    "mission_result": (MissionResult, QOS_SENSOR),
    "gimbal_status": (GimbalStatus, QOS_SENSOR),
    "camera_status": (CameraStatus, QOS_SENSOR),
    "camera/capture_result": (String, QOS_RELIABLE),
    "camera/media_list": (String, QOS_RELIABLE),
    "camera/download_result": (String, QOS_RELIABLE),
    "lrf_target": (NavSatFix, QOS_SENSOR),
    "lrf_measurement": (String, QOS_RELIABLE),
    "settings": (String, QOS_RELIABLE),
    "video": (Image, QOS_SENSOR),
}


def topic_in(name: str) -> str:
    return IN_PREFIX + name


def topic_out(name: str) -> str:
    return OUT_PREFIX + name


# New name -> old flat name, for topics whose message TYPE is unchanged (pure
# renames only -- see the module docstring for why bundled/consolidated
# topics can't go through a plain remap). Used by lyrebird_bringup's
# `legacy_topics` launch argument to build `remappings=`.
LEGACY_TOPIC_MAP = {
    topic_in("goto_trajectory_dji_native"): "command/goto_trajectory_dji_native",
    topic_in("set_setting"): "command/set_setting",
    topic_in("download_media"): "command/camera/download_media",
    topic_out("camera/capture_result"): "camera/capture_result",
    topic_out("camera/media_list"): "camera/media_list",
    topic_out("camera/download_result"): "camera/download_result",
    topic_out("lrf_target"): "lrf/target",
    topic_out("lrf_measurement"): "lrf/measurement",
    topic_out("settings"): "state/settings",
    topic_out("video"): "video_frames",
}
