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
    ├── fleet_auto_discovery.launch.py  # one FleetAutoDiscoveryManager, re-scans + settings/ports for the whole fleet
    ├── swarm_connection.launch.py
    └── config/parameters.yaml
```

`lyrebird_controller/fleet_auto_discovery.py` also ships its own
`config/fleet_settings.yaml`, loaded by `FleetAutoDiscoveryManager` at startup -- see
[Fleet auto-scaling](#fleet-auto-scaling) below.

`lyrebird_controller/topics.py` is the single source of truth for every topic name, type, and QoS
profile -- both `controller.py` and the `ros_monitor` container's dashboard bridge import it,
instead of each maintaining its own hand-written topic list.

## Namespacing

Exactly as before: each drone gets its own ROS namespace at launch (e.g. `/mini1/`), with
`fmu/out/...` and `fmu/in/...` nested inside it -- e.g. `/mini1/fmu/out/battery_status` -- matching
PX4's own per-vehicle namespacing pattern.

## Fleet auto-scaling

`fleet_auto_discovery.launch.py` doesn't just launch whatever it finds at startup and stop
looking. It runs one `FleetAutoDiscoveryManager` node (`lyrebird_controller/fleet_auto_discovery.py`),
which rescans the network on a timer (`discovery_period_sec`, default 30s) for the life of the
launch, and spins up a namespaced `lyrebird_controller_<name>` `DjiNode` for any drone that
wasn't there yet -- no restart of the ROS 2 stack needed to pick up a drone powered on after
launch. Every `DjiNode` it creates lives inside this one process, sharing one
`MultiThreadedExecutor`, since the fleet size isn't known ahead of time and a fixed set of
`ros2 launch` `Node` actions can't be declared for it.

Because they share one process, they also share one network namespace, so each drone is given
its own MAVLink listen port (offset from `mavlink_port_base`, stably keyed by the drone's own
name so a reconnecting drone keeps its port). On connecting, each drone is also pushed the
settings in `lyrebird_controller/config/fleet_settings.yaml` -- the same settings the app's
cockpit settings menu edits, plus which transport (HTTP/MAVLink/both) to use and an optional
auto-assigned `rthAltitude` range spaced across the fleet -- so a fleet can be brought up with
consistent settings without opening the app on each aircraft.

This replaced an older mechanism (`auto_discovery_native.launch.py`, one `ros2 launch` `Node`
action per drone via a rescanning `TimerAction`) that could not assign MAVLink ports or push any
settings, since each drone got its own OS process rather than sharing one.

### `fleet_settings.yaml` reference

Point a launch file at a different copy of this file via the `fleet_settings_file` ROS
parameter to run a different profile per deployment (e.g.
`ros2 launch lyrebird_bringup fleet_auto_discovery.launch.py fleet_settings_file:=/path/to/fleet_settings.yaml`).
Any key below can also be set the normal ROS way (a launch file's own `parameters=[...]`, or
`--ros-args -p`), which always wins over the file. Comment out a key to leave that setting alone
on the drone instead of overwriting it.

Top-level (ground-station-side, not pushed to the drone):

| Key | Default | Meaning |
|---|---|---|
| `transport` | `both` | Which wire every discovered drone is commanded over: `http` \| `mavlink` \| `both`. Independent of everything below -- this is how the ground station talks to the aircraft, not an app setting. Keep it `both` (MAVLink carries what it can, HTTP fills the rest) unless you have a specific reason to run one wire only. |
| `discovery_period_sec` | `30.0` | Seconds between rescans for newly joined drones. |
| `discovery_timeout_sec` | `5.0` | How long each scan waits for answers. |
| `mavlink_port_base` | `14550` | This ground station's own MAVLink listen port for the first discovered drone; later drones get `base + <stable per-drone offset>` so they don't collide on the same UDP port. Otherwise comes from `LB_MAVLINK_PORT`. |
| `mavlink_peer_port` | `14550` | The UDP port every aircraft listens on. Fleet-wide only -- no per-drone override yet. Otherwise comes from `LB_MAVLINK_PEER_PORT`. |

`fleet_settings` (pushed to every drone on connection -- the same values the app's cockpit
settings menu edits):

| Key | Default | Valid values |
|---|---|---|
| `maxFlightHeight` | `120` | Meters AGL flight ceiling. |
| `maxFlightDistance` | `500` | Meters, max distance from the home point. |
| `distanceLimitEnabled` | `true` | `true` \| `false` -- whether `maxFlightDistance` is enforced. |
| `videoSource` | `drone` | `drone` \| `phone` \| `mock` -- `mock` is the built-in Mock MP4 test pattern, useful for testing `streamingMode` end-to-end without a live feed. |
| `streamingMode` | `webrtc` | `webrtc` \| `rtmp` \| `rtsp` \| `agora` \| `gb28181` -- `webrtc` means WebRTC via WHIP push (DJI SDK naming, not a separate "whip" mode). |
| `webrtcResolution` | `auto` | `auto` \| `1080p` \| `720p` \| `480p` (only used when `streamingMode: webrtc`). |
| `webrtcFps` | `20` | `5` \| `10` \| `15` \| `20` \| `25` \| `30` (only used when `streamingMode: webrtc`). |
| `detectionsEnabled` | `false` | `true` \| `false` -- on-board object detection. |
| `detectionSource` | `none` | `none` \| `dji_onboard` \| `yolo_on_phone` (only used when `detectionsEnabled: true`). |
| `edgeConfidenceThreshold` | `0.30` | `0.10`-`0.70` in `0.05` steps -- minimum confidence for a detection to be reported. |
| `rcControlMode` | `usa` | `jp` \| `usa` \| `ch` \| `custom` -- RC stick mapping. |
| `mediamtxServer` | *(unset)* | MediaMTX relay server URL, only used by some streaming modes (an rtsp relay or gb28181 bridging). Leave unset to use the phone's own local server. |
| `surfaceH264Encoder` | `false` | `true` \| `false` -- experimental: encode WebRTC video straight from a DJI SDK surface instead of the default encoder. Takes effect on the phone's next app restart, not immediately. |

`rth_altitude_range` (optional; overrides `rthAltitude` in `fleet_settings` when present):

| Key | Meaning |
|---|---|
| `min` | RTH altitude (meters) for the first discovered drone. |
| `max` | Ceiling: no drone is assigned above this. |
| `step` | Meters added per drone, by the same stable per-drone identity `mavlink_port_base` offsets by (so a reconnecting drone keeps its altitude), capped at `max` once the fleet outgrows the range. |

`drone_settings` maps a drone's discovered name (e.g. `mini3`) to its own settings dict,
overriding `fleet_settings`/`rth_altitude_range` for that drone only -- e.g. `{"mini3":
{"rthAltitude": 60, "droneName": "Mini 3"}}`. `droneName` is deliberately not a `fleet_settings`
key (setting it fleet-wide would give every drone the same name) but is valid here.

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
ros2 launch lyrebird_bringup fleet_auto_discovery.launch.py
# or, to also expose the pure-rename legacy topics:
ros2 launch lyrebird_bringup fleet_auto_discovery.launch.py legacy_topics:=true
# or, to use a fleet_settings.yaml other than lyrebird_controller's bundled default:
ros2 launch lyrebird_bringup fleet_auto_discovery.launch.py fleet_settings_file:=/path/to/fleet_settings.yaml

# Example commands (namespace is the drone's own name, e.g. "mini1")
ros2 topic pub /mini1/fmu/in/vehicle_command lyrebird_msgs/msg/VehicleCommand \
  "{command: 22}"   # MAV_CMD_NAV_TAKEOFF
ros2 topic pub /mini1/fmu/in/trajectory_setpoint lyrebird_msgs/msg/TrajectorySetpoint \
  "{latitude: 49.306254, longitude: 4.593728, altitude: 20.0, yaw: 90.0, speed: 5.0, yaw_mode: 0}"
```
