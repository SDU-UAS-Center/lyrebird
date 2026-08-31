---
title: ROS 2 Integration
description: The lyrebird_controller node follows PX4's uXRCE-DDS topic convention -- fmu/in/... commands, fmu/out/... telemetry -- so PX4 ROS 2 experience carries straight over to Lyrebird's DJI-backed ground station.
breadcrumb: Interfaces
---

Full ROS 2 Humble package. `lyrebird_controller` follows PX4's real uXRCE-DDS topic convention as
closely as DJI's telemetry/command surface allows: telemetry is grouped into typed messages under
`fmu/out/...`, and discrete actions are dispatched through one `fmu/in/vehicle_command` topic keyed
by a MAV_CMD-style command id -- the same shape as PX4's real `VehicleCommand`/MAVLink
`COMMAND_LONG`. A developer who already knows PX4's ROS 2 API should recognize the topic layout,
the command vocabulary, and most message field names immediately.

Every command id and message field below was checked against MAVLink's `common.xml` and
PX4-Autopilot's real `msg/versioned/*.msg` sources, not approximated from memory. Where DJI
telemetry has no PX4 equivalent to reuse (gimbal/camera status, LRF), the message is Lyrebird's own
but keeps PX4's snake_case naming style.

It checks for new telemetry at 20 Hz but publishes only when the drone has actually sent a new
snapshot, so the topic rate follows the aircraft's telemetry interval (default ~2 Hz) rather than
repeating each sample.

## Package structure

```text
GroundStation/ROS/
├── lyrebird_msgs/         # PX4-DDS-style .msg definitions (VehicleCommand, BatteryStatus, ...)
├── lyrebird_controller/   # Main control + telemetry node
│   ├── controller.py        # DjiNode: dispatches fmu/in/vehicle_command, publishes fmu/out/*
│   ├── topics.py             # Central topic name / QoS / legacy-remap registry
│   └── dji_interface.py
├── lyrebird_videofeed/    # RTSP video feed -> sensor_msgs/Image on fmu/out/video
└── lyrebird_bringup/
    ├── auto_discovery_native.launch.py  # one namespaced lyrebird_controller per discovered drone, re-scans periodically
    ├── swarm_connection.launch.py
    └── config/parameters.yaml
```

`lyrebird_controller/topics.py` is the single source of truth for every topic name, type, and QoS
profile -- both `controller.py` and the `ros_monitor` container's dashboard bridge import it,
instead of each maintaining its own hand-written topic list.

## Namespacing

Exactly as before: each drone gets its own ROS namespace at launch (e.g. `/mini1/`), with
`fmu/out/...` and `fmu/in/...` nested inside it -- e.g. `/mini1/fmu/out/battery_status` -- matching
PX4's own per-vehicle namespacing pattern.

## Fleet auto-scaling

`auto_discovery_native.launch.py` doesn't just launch whatever it finds at startup and stop looking.
After the initial scan spawns one namespaced `lyrebird_controller` node per drone found, a
`TimerAction` reruns discovery every `ROS_DISCOVERY_PERIOD` seconds (default 15, environment
variable) for the life of the launch, and any drone that wasn't there yet gets its own node spawned
live -- no restart of the ROS 2 stack needed to pick up a drone powered on after launch. A
`known_namespaces` set carried across every rescan is what makes this safe: it stops an
already-running drone from being launched a second time, and keeps namespace assignment stable as
the fleet grows -- the second drone discovered is always the same namespace scan after scan, never
reassigned out from under a node that's already running.

## Commands (`fmu/in/...`)

| Topic | Type | Body |
|-------|------|------|
| `fmu/in/vehicle_command` | `lyrebird_msgs/VehicleCommand` | `command` id + `param1..param7`, see table below |
| `fmu/in/trajectory_setpoint` | `lyrebird_msgs/TrajectorySetpoint` | `latitude, longitude, altitude, yaw, speed, yaw_mode` |
| `fmu/in/manual_control_setpoint` | `lyrebird_msgs/ManualControlSetpoint` | `roll, pitch, throttle, yaw` ∈ [-1,1] (virtual stick) |
| `fmu/in/goto_trajectory_dji_native` | `String` | `"(speed, [(lat,lon,alt),...])"` |
| `fmu/in/set_setting` | `String` | `"key=value"` (webapp setting keys) |
| `fmu/in/download_media` | `String` | on-camera file name |

### `vehicle_command` command ids

Real MAV_CMD ids (from MAVLink `common.xml` / PX4's `VehicleCommand.msg`) are reused wherever the
semantics match, so `command: 22` really is `MAV_CMD_NAV_TAKEOFF`:

| Command | id | Params |
|---|---|---|
| `VEHICLE_CMD_NAV_TAKEOFF` | 22 | — |
| `VEHICLE_CMD_NAV_LAND` | 21 | — |
| `VEHICLE_CMD_NAV_RETURN_TO_LAUNCH` | 20 | — |
| `VEHICLE_CMD_CONDITION_YAW` | 115 | param1=yaw angle (deg) |
| `VEHICLE_CMD_DO_CHANGE_ALTITUDE` | 186 | param1=altitude (m) |
| `VEHICLE_CMD_DO_GIMBAL_MANAGER_PITCHYAW` | 1000 | param1=pitch (deg), param2=yaw (deg); NaN = unset (send one axis at a time by NaN-ing the other) |
| `VEHICLE_CMD_SET_CAMERA_ZOOM` | 531 | param2=zoom ratio |
| `VEHICLE_CMD_VIDEO_START_CAPTURE` | 2500 | — |
| `VEHICLE_CMD_VIDEO_STOP_CAPTURE` | 2501 | — |
| `VEHICLE_CMD_IMAGE_START_CAPTURE` | 2000 | — |

DJI-only actions with no MAV_CMD equivalent use `VEHICLE_CMD_LYREBIRD_*` ids in MAVLink's own
documented user-command range (31000-31999), so they can never collide with a real MAV_CMD:

| Command | id | Params |
|---|---|---|
| `VEHICLE_CMD_LYREBIRD_ABORT_MISSION` | 31015 | — |
| `VEHICLE_CMD_LYREBIRD_ABORT_ALL` | 31016 | — |
| `VEHICLE_CMD_LYREBIRD_ENABLE_VIRTUAL_STICK` | 31017 | — |
| `VEHICLE_CMD_LYREBIRD_ABORT_DJI_NATIVE_MISSION` | 31018 | — |
| `VEHICLE_CMD_LYREBIRD_DEACTIVATE_MANUAL_OVERRIDE` | 31019 | — |
| `VEHICLE_CMD_LYREBIRD_GIMBAL_REL_PITCH` | 31020 | param1=degrees relative to current pitch |
| `VEHICLE_CMD_LYREBIRD_GIMBAL_REL_YAW` | 31021 | param1=degrees relative to current yaw |
| `VEHICLE_CMD_LYREBIRD_SET_RTH_ALTITUDE` | 31022 | param1=altitude (m) |
| `VEHICLE_CMD_LYREBIRD_CAPTURE_TEMPERATURE` | 31023 | — |
| `VEHICLE_CMD_LYREBIRD_LIST_MEDIA` | 31024 | — |
| `VEHICLE_CMD_LYREBIRD_LRF_MEASURE` | 31025 | — |
| `VEHICLE_CMD_LYREBIRD_DROP` | 31026 | — |

Camera, media, and LRF commands block for as long as the aircraft takes to answer (up to 120 s for
a download), so they run on a single-worker thread pool and answer asynchronously on their own
`fmu/out/camera/*` and `fmu/out/lrf_*` result topics rather than stalling the telemetry loop.

## Telemetry (`fmu/out/...`)

| Topic | Type | Replaces (old flat topics) |
|-------|------|------|
| `fmu/out/vehicle_command_ack` | `VehicleCommandAck` | `command_ack/waypoint_seq`, `command_ack/yaw_seq`, `command_ack/altitude_seq` |
| `fmu/out/vehicle_global_position` | `VehicleGlobalPosition` | `location`, `satellite_count` |
| `fmu/out/home_position` | `HomePosition` | `home_location`, `home_set` |
| `fmu/out/vehicle_local_position` | `VehicleLocalPosition` | `speed`, `speed_vector`, `heading`, `altitude`, `attitude` |
| `fmu/out/battery_status` | `BatteryStatus` | `battery_level`, `distance_to_home`, `remaining_flight_time`, `time_needed_to_go_home`, `time_needed_to_land`, `time_to_landing_spot`, `max_radius_can_fly_and_go_home`, `battery_needed_to_go_home`, `battery_needed_to_land` |
| `fmu/out/vehicle_status` | `VehicleStatus` | `flight_mode`, `manual_override_active`, `ready_to_takeoff`, `takeoff_block_reason` |
| `fmu/out/mission_result` | `MissionResult` | `waypoint_reached`, `intermediary_waypoint_reached`, `altitude_reached`, `yaw_reached`, `waypoint_seq`, `altitude_seq`, `yaw_seq` |
| `fmu/out/gimbal_status` | `GimbalStatus` | `gimbal_attitude`, `gimbal_joint_attitude`, `gimbal_yaw`, `gimbal_pitch` |
| `fmu/out/camera_status` | `CameraStatus` | `zoom_fl`, `hybrid_fl`, `optical_fl`, `zoom_ratio`, `camera/is_recording`, `camera/thermal_max_temp` |
| `fmu/out/camera/capture_result` | `String` | `camera/capture_result` (unchanged payload) |
| `fmu/out/camera/media_list` | `String` | `camera/media_list` (unchanged payload) |
| `fmu/out/camera/download_result` | `String` | `camera/download_result` (unchanged payload) |
| `fmu/out/lrf_target` | `NavSatFix` | `lrf/target` (unchanged type) |
| `fmu/out/lrf_measurement` | `String` | `lrf/measurement` (unchanged type) |
| `fmu/out/settings` | `String` | `state/settings` (unchanged type) |
| `fmu/out/video` | `sensor_msgs/Image` | `video_frames` (in `lyrebird_videofeed`) |

Field names inside each message reuse PX4's real field names wherever DJI telemetry can actually
populate them (e.g. `remaining`/`time_remaining_s` on `BatteryStatus`, `lat`/`lon`/`alt` on
`VehicleGlobalPosition`), and simply omit PX4 fields we have no data for rather than fabricating
values. `VehicleStatus.flight_mode` stays a free-form string of DJI's own mode names -- it does not
claim compatibility with PX4's numeric `nav_state` enum, since the mode sets aren't equivalent.

## QoS

Matches PX4's own rationale: `fmu/out/*` telemetry uses the best-effort/volatile "sensor data"
profile (depth 5) since occasional missed samples are fine and low latency matters more; `fmu/in/*`
commands/setpoints and `fmu/out/vehicle_command_ack` use a reliable profile (depth 10) since those
must arrive.

## Legacy topics

`legacy_topics:=true` on either bringup launch file restores the old flat topic names for anything
whose message **type** didn't change in this redesign (video, LRF, settings, capture/media/download
results, and the three remaining string-payload command topics) via a plain ROS2 topic remap, at no
extra runtime cost. It does **not** restore the ~20 topics that consolidated into
`vehicle_command`, `trajectory_setpoint`, `manual_control_setpoint`, or any of the bundled
telemetry messages above -- a remap can only rename a topic, it can't split one new struct message
back out into several old differently-typed scalar topics. Consumers of those old topics need to
migrate to the new ones; see `lyrebird_controller/topics.py` for the exact remap list.

## Usage

**Docker (single-drone, auto-discovery):**

```bash
cd GroundStation
docker build -t lyrebird-ros .
docker run --rm --network=host lyrebird-ros
```

The image is based on `ros:humble` with CycloneDDS, `cv-bridge`, `vision-opencv`, `image-transport`, plus all Python dependencies.

**Manual multi-drone launch:**

```bash
cd GroundStation/ROS
colcon build --symlink-install && source install/setup.bash
ros2 launch lyrebird_bringup auto_discovery_native.launch.py
# or, to also expose the pure-rename legacy topics:
ros2 launch lyrebird_bringup auto_discovery_native.launch.py legacy_topics:=true

# Example commands (namespace is the drone's own name, e.g. "mini1")
ros2 topic pub /mini1/fmu/in/vehicle_command lyrebird_msgs/msg/VehicleCommand \
  "{command: 22}"   # MAV_CMD_NAV_TAKEOFF
ros2 topic pub /mini1/fmu/in/trajectory_setpoint lyrebird_msgs/msg/TrajectorySetpoint \
  "{latitude: 49.306254, longitude: 4.593728, altitude: 20.0, yaw: 90.0, speed: 5.0, yaw_mode: 0}"
```
