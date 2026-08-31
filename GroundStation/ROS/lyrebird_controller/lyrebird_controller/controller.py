"""
Authors: Edouard Rolland, Alejandro Jarabo-Peñas
Project: Lyrebird

This file was written as part of the Lyrebird project and implements a ROS 2 node for controlling a DJI drone
via the Lyrebird app. The node handles both command reception and telemetry publishing.

Topics follow PX4's uXRCE-DDS DDS naming convention (`fmu/in/...` / `fmu/out/...`,
see lyrebird_controller.topics) so far as DJI's telemetry/command surface allows it:
discrete actions are dispatched through one `fmu/in/vehicle_command` topic keyed by a
MAV_CMD-style command id, mirroring PX4's real VehicleCommand/COMMAND_LONG shape.
"""

import ast
import json
import math
import re
from concurrent.futures import ThreadPoolExecutor, TimeoutError

import numpy as np
import rclpy
from rclpy.node import Node
from rclpy.parameter import Parameter
from requests.exceptions import RequestException
from sensor_msgs.msg import NavSatFix
from std_msgs.msg import String

from lyrebird_controller import topics
from lyrebird_controller.dji_interface import DJIInterface, get_config
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

# How often to check for a new telemetry snapshot. This is the poll rate, not the publish rate:
# publishing happens only when the drone has sent something new, so a short period buys low
# latency rather than duplicate messages.
TELEMETRY_POLL_PERIOD_S = 0.05

# MAV_CMD_NAV_WAYPOINT, from MAVLink's common.xml. Used only to label
# vehicle_command_ack replies to a trajectory_setpoint (nose-forward) request:
# that request arrives on its own topic rather than as a vehicle_command, but
# still needs a MAV_CMD-shaped id to say what is being acknowledged.
_MAV_CMD_NAV_WAYPOINT = 16

# Command ack responses from the app have drifted across builds: newer ones embed
# the seq ('WAYPOINT_ACCEPTED seq=5 ...'), older ones return bare text
# ('Received: Altitude: 40.0'). Parse defensively — a malformed ack must never
# raise inside a subscription callback, which would kill the whole node.
_SEQ_RE = re.compile(r"\bseq=(\d+)", re.IGNORECASE)


def parse_ack_seq(response):
    """Extract the command seq from an app ack, or -1 when unknown/unparseable."""
    if response is None:
        return -1
    text = str(response).strip()
    if not text:
        return -1
    match = _SEQ_RE.search(text)
    if match:
        return int(match.group(1))
    try:
        return int(text)
    except (TypeError, ValueError):
        return -1


class DjiNode(Node):
    def __init__(self, ip_rc=None, node_name="DjiNode", namespace=None):
        """Create a node for one drone.

        The arguments exist so several drones can be run inside one process, each as its own
        node under its own namespace. Launched on its own the defaults reproduce the previous
        single-drone behaviour exactly: no name, no namespace, IP from the ROS parameter.
        """
        super().__init__(node_name, namespace=namespace)
        self.get_logger().info("Node Initialisation")

        # False until the drone answers. A caller running several of these in one process checks
        # this and destroys the node itself, rather than the node tearing down the whole context.
        self.connection_ready = False

        # Retrieve the drone's IP address from the parameter server
        self.declare_parameter("ip_rc", ip_rc or "")  # Default IP (empty for auto-discovery)
        self.ip_rc = ip_rc or self.get_parameter("ip_rc").get_parameter_value().string_value

        # Initialize the DJI drone interface
        self.dji_interface = DJIInterface(self.ip_rc)

        # Update IP if discovered and set the ROS2 parameter so other nodes can query it
        if not self.ip_rc and self.dji_interface.IP_RC:
            self.ip_rc = self.dji_interface.IP_RC
            # Update the ROS2 parameter so bridge can query it
            self.set_parameters([Parameter("ip_rc", Parameter.Type.STRING, self.ip_rc)])
            self.get_logger().info(f"Discovered drone at {self.ip_rc}, updated ip_rc parameter")

        # Verify the connection to the drone
        if not self.verify_connection():
            self.get_logger().error(f"Unable to connect to the drone at IP: {self.ip_rc}.")
            # Deliberately not rclpy.shutdown(): this node may be one of several created in the
            # same process, and tearing down the context would kill every other drone's node
            # over one unreachable aircraft. The caller checks connection_ready and destroys
            # this node on its own.
            return

        self.connection_ready = True

        # Start the telemetry stream (TCP socket on port 8081)
        self.dji_interface.startTelemetryStream()

        self._command_handlers = {
            VehicleCommand.VEHICLE_CMD_NAV_TAKEOFF: self._cmd_takeoff,
            VehicleCommand.VEHICLE_CMD_NAV_LAND: self._cmd_land,
            VehicleCommand.VEHICLE_CMD_NAV_RETURN_TO_LAUNCH: self._cmd_rth,
            VehicleCommand.VEHICLE_CMD_CONDITION_YAW: self._cmd_goto_yaw,
            VehicleCommand.VEHICLE_CMD_DO_CHANGE_ALTITUDE: self._cmd_goto_altitude,
            VehicleCommand.VEHICLE_CMD_DO_GIMBAL_MANAGER_PITCHYAW: self._cmd_gimbal_pitchyaw,
            VehicleCommand.VEHICLE_CMD_SET_CAMERA_ZOOM: self._cmd_zoom_ratio,
            VehicleCommand.VEHICLE_CMD_VIDEO_START_CAPTURE: self._cmd_start_recording,
            VehicleCommand.VEHICLE_CMD_VIDEO_STOP_CAPTURE: self._cmd_stop_recording,
            VehicleCommand.VEHICLE_CMD_IMAGE_START_CAPTURE: self._cmd_capture,
            VehicleCommand.VEHICLE_CMD_LYREBIRD_ABORT_MISSION: self._cmd_abort_mission,
            VehicleCommand.VEHICLE_CMD_LYREBIRD_ABORT_ALL: self._cmd_abort_all,
            VehicleCommand.VEHICLE_CMD_LYREBIRD_ENABLE_VIRTUAL_STICK: self._cmd_enable_virtual_stick,
            VehicleCommand.VEHICLE_CMD_LYREBIRD_ABORT_DJI_NATIVE_MISSION: (
                self._cmd_abort_dji_native_mission
            ),
            VehicleCommand.VEHICLE_CMD_LYREBIRD_DEACTIVATE_MANUAL_OVERRIDE: (
                self._cmd_deactivate_manual_override
            ),
            VehicleCommand.VEHICLE_CMD_LYREBIRD_GIMBAL_REL_PITCH: self._cmd_gimbal_rel_pitch,
            VehicleCommand.VEHICLE_CMD_LYREBIRD_GIMBAL_REL_YAW: self._cmd_gimbal_rel_yaw,
            VehicleCommand.VEHICLE_CMD_LYREBIRD_SET_RTH_ALTITUDE: self._cmd_set_rth_altitude,
            VehicleCommand.VEHICLE_CMD_LYREBIRD_CAPTURE_TEMPERATURE: self._cmd_capture_temperature,
            VehicleCommand.VEHICLE_CMD_LYREBIRD_LIST_MEDIA: self._cmd_list_media,
            VehicleCommand.VEHICLE_CMD_LYREBIRD_LRF_MEASURE: self._cmd_lrf_measure,
            VehicleCommand.VEHICLE_CMD_LYREBIRD_DROP: self._cmd_drop,
        }

        # Single consolidated command topic, PX4-vehicle_command style.
        self.create_subscription(
            VehicleCommand,
            topics.topic_in("vehicle_command"),
            self.vehicle_command_callback,
            topics.QOS_RELIABLE,
        )
        self.create_subscription(
            TrajectorySetpoint,
            topics.topic_in("trajectory_setpoint"),
            self.trajectory_setpoint_callback,
            topics.QOS_RELIABLE,
        )
        self.create_subscription(
            ManualControlSetpoint,
            topics.topic_in("manual_control_setpoint"),
            self.manual_control_setpoint_callback,
            topics.QOS_RELIABLE,
        )
        self.create_subscription(
            String,
            topics.topic_in("goto_trajectory_dji_native"),
            self.goto_trajectory_dji_native_callback,
            topics.QOS_RELIABLE,
        )
        # Generic settings write: payload is 'key=value' (webapp setting keys)
        self.create_subscription(
            String, topics.topic_in("set_setting"), self.set_setting_callback, topics.QOS_RELIABLE
        )
        self.create_subscription(
            String,
            topics.topic_in("download_media"),
            self.download_media_callback,
            topics.QOS_RELIABLE,
        )

        # Publishers for telemetry, grouped PX4-style instead of one topic per scalar field.
        self.vehicle_command_ack_pub = self.create_publisher(
            VehicleCommandAck, topics.topic_out("vehicle_command_ack"), topics.QOS_RELIABLE
        )
        self.vehicle_global_position_pub = self.create_publisher(
            VehicleGlobalPosition, topics.topic_out("vehicle_global_position"), topics.QOS_SENSOR
        )
        self.home_position_pub = self.create_publisher(
            HomePosition, topics.topic_out("home_position"), topics.QOS_SENSOR
        )
        self.vehicle_local_position_pub = self.create_publisher(
            VehicleLocalPosition, topics.topic_out("vehicle_local_position"), topics.QOS_SENSOR
        )
        self.battery_status_pub = self.create_publisher(
            BatteryStatus, topics.topic_out("battery_status"), topics.QOS_SENSOR
        )
        self.vehicle_status_pub = self.create_publisher(
            VehicleStatus, topics.topic_out("vehicle_status"), topics.QOS_SENSOR
        )
        self.mission_result_pub = self.create_publisher(
            MissionResult, topics.topic_out("mission_result"), topics.QOS_SENSOR
        )
        self.gimbal_status_pub = self.create_publisher(
            GimbalStatus, topics.topic_out("gimbal_status"), topics.QOS_SENSOR
        )
        self.camera_status_pub = self.create_publisher(
            CameraStatus, topics.topic_out("camera_status"), topics.QOS_SENSOR
        )

        # LRF / thermal / media result publishers (unchanged payload shape, renamed only)
        self.lrf_target_pub = self.create_publisher(
            NavSatFix, topics.topic_out("lrf_target"), topics.QOS_SENSOR
        )
        self.lrf_measurement_pub = self.create_publisher(
            String, topics.topic_out("lrf_measurement"), topics.QOS_RELIABLE
        )
        self.capture_result_pub = self.create_publisher(
            String, topics.topic_out("camera/capture_result"), topics.QOS_RELIABLE
        )
        self.media_list_pub = self.create_publisher(
            String, topics.topic_out("camera/media_list"), topics.QOS_RELIABLE
        )
        self.download_result_pub = self.create_publisher(
            String, topics.topic_out("camera/download_result"), topics.QOS_RELIABLE
        )

        # Last thermal reading: an on-demand result (capture_temperature has no periodic source in
        # DJI's telemetry), folded into the periodic camera_status publish rather than its own
        # one-shot topic so subscribers always see the latest known value.
        self._last_thermal_max_temp = float("nan")

        # Capture / listMedia / download block for up to 2 minutes on the HTTP call, so they run
        # off the executor thread — otherwise they stall the telemetry timer.
        # ponytail: single worker serializes them, which is what the camera wants anyway.
        self.blocking_calls = ThreadPoolExecutor(max_workers=1)

        # Directory downloaded media is written to
        self.declare_parameter("media_dir", "media")
        self.media_dir = self.get_parameter("media_dir").get_parameter_value().string_value

        # Poll for telemetry at 20 Hz but publish only when a new snapshot has actually arrived.
        # The drone's TCP telemetry interval is configurable and currently ~2 Hz, so publishing on
        # every tick republished each sample about ten times across 45-plus topics. The poll stays
        # fast so a fresh sample reaches subscribers within 50 ms; the publish rate now follows
        # the aircraft, and rises on its own if the drone's interval is shortened.
        self._last_telemetry_seq = -1
        self.create_timer(TELEMETRY_POLL_PERIOD_S, self.publish_states)

        # Settings snapshot at 1 Hz (settings change rarely)
        self.settings_pub = self.create_publisher(String, topics.topic_out("settings"), 10)
        self.create_timer(1.0, self.publish_settings)

        self.get_logger().info(f"DroneNode initialized and connected to IP: {self.ip_rc}")

    ##############################
    # Connection Verification    #
    ##############################

    def verify_connection(self):
        """Verify the connection to the drone by sending a test request."""
        timeout_duration = 5  # Timeout in seconds

        def connection_attempt():
            try:
                # Try to get config to verify connection (cleaner than probing /)
                config = get_config(self.ip_rc)
                if config:
                    self.get_logger().info(f"Connection verified. Drone config: {config}")
                    return True

                # Fallback to old method if config fails but maybe server is up
                response = self.dji_interface.requestSend("/", "", verbose=False)
                if response:
                    self.get_logger().info("Connection verified (via fallback probe).")
                    return True
                return False
            except RequestException as e:
                self.get_logger().error(f"Connection failed: {e}")
                return False
            except Exception as e:
                self.get_logger().error(f"Connection failed with unexpected error: {e}")
                return False

        with ThreadPoolExecutor(max_workers=1) as executor:
            future = executor.submit(connection_attempt)
            try:
                return future.result(timeout=timeout_duration)
            except TimeoutError:
                self.get_logger().error(
                    f"Connection to {self.ip_rc} timed out after {timeout_duration} seconds."
                )
                return False

    def _now_us(self):
        return self.get_clock().now().nanoseconds // 1000

    def _publish_command_ack(
        self, command, seq, result=VehicleCommandAck.VEHICLE_CMD_RESULT_ACCEPTED
    ):
        self.vehicle_command_ack_pub.publish(
            VehicleCommandAck(
                timestamp=self._now_us(),
                command=command,
                result=result,
                seq=seq,
                target_system=0,
            )
        )

    ###########################################
    # vehicle_command dispatch (fmu/in/vehicle_command) #
    ###########################################

    def vehicle_command_callback(self, msg: VehicleCommand):
        handler = self._command_handlers.get(msg.command)
        if handler is None:
            self.get_logger().warning(f"Unknown vehicle_command id: {msg.command}")
            return
        handler(msg)

    def _cmd_takeoff(self, msg: VehicleCommand):
        self.get_logger().info("Received takeoff command.")
        self.dji_interface.requestSendTakeOff()

    def _cmd_land(self, msg: VehicleCommand):
        self.get_logger().info("Received land command.")
        self.dji_interface.requestSendLand()

    def _cmd_rth(self, msg: VehicleCommand):
        self.get_logger().info("Received return to home command.")
        self.dji_interface.requestSendRTH()

    def _cmd_abort_mission(self, msg: VehicleCommand):
        self.get_logger().info("Received abort mission command.")
        self.dji_interface.requestAbortMission()

    def _cmd_abort_all(self, msg: VehicleCommand):
        self.get_logger().info("Received abort ALL command - stopping all missions.")
        self.dji_interface.requestAbortAll()

    def _cmd_enable_virtual_stick(self, msg: VehicleCommand):
        self.get_logger().info("Received enable virtual stick command.")
        self.dji_interface.requestSendEnableVirtualStick()

    def _cmd_abort_dji_native_mission(self, msg: VehicleCommand):
        self.get_logger().info("Received abort DJI native mission command.")
        self.dji_interface.requestAbortDJINativeMission()

    def _cmd_deactivate_manual_override(self, msg: VehicleCommand):
        self.get_logger().info("Received deactivate manual override command.")
        self.dji_interface.requestDeactivateManualOverride()

    def _cmd_goto_yaw(self, msg: VehicleCommand):
        self.get_logger().info("Received goto yaw command.")
        seq = self.dji_interface.requestSendGotoYaw(msg.param1)
        self._publish_command_ack(VehicleCommand.VEHICLE_CMD_CONDITION_YAW, parse_ack_seq(seq))

    def _cmd_goto_altitude(self, msg: VehicleCommand):
        self.get_logger().info("Received goto altitude command.")
        seq = self.dji_interface.requestSendGotoAltitude(msg.param1)
        self._publish_command_ack(
            VehicleCommand.VEHICLE_CMD_DO_CHANGE_ALTITUDE, parse_ack_seq(seq)
        )

    def _cmd_gimbal_pitchyaw(self, msg: VehicleCommand):
        """param1=pitch[deg], param2=yaw[deg]; NaN on either means "leave unset", per PX4's
        real DO_GIMBAL_MANAGER_PITCHYAW convention."""
        self.get_logger().info("Received gimbal pitch/yaw command.")
        if not math.isnan(msg.param1):
            self.dji_interface.requestSendGimbalPitch(msg.param1)
        if not math.isnan(msg.param2):
            self.dji_interface.requestSendGimbalYaw(msg.param2)

    def _cmd_gimbal_rel_pitch(self, msg: VehicleCommand):
        self.get_logger().info("Received gimbal relative pitch command.")
        self.dji_interface.requestSendGimbalRelPitch(msg.param1)

    def _cmd_gimbal_rel_yaw(self, msg: VehicleCommand):
        self.get_logger().info("Received gimbal relative yaw command.")
        self.dji_interface.requestSendGimbalRelYaw(msg.param1)

    def _cmd_zoom_ratio(self, msg: VehicleCommand):
        self.get_logger().info("Received zoom ratio command.")
        self.dji_interface.requestSendZoomRatio(msg.param2)

    def _cmd_set_rth_altitude(self, msg: VehicleCommand):
        self.get_logger().info("Received set RTH altitude command.")
        self.dji_interface.requestSetRTHAltitude(msg.param1)

    def _cmd_start_recording(self, msg: VehicleCommand):
        self.get_logger().info("Received start recording command.")
        response = self.dji_interface.requestCameraStartRecording()
        if response:
            self.get_logger().info("Camera recording started successfully.")
        else:
            self.get_logger().error("Failed to start camera recording.")

    def _cmd_stop_recording(self, msg: VehicleCommand):
        self.get_logger().info("Received stop recording command.")
        response = self.dji_interface.requestCameraStopRecording()
        if response:
            self.get_logger().info("Camera recording stopped successfully.")
        else:
            self.get_logger().error("Failed to stop camera recording.")

    def _cmd_capture(self, msg: VehicleCommand):
        """Trip one shutter. Publishes the JSON capture descriptor (per-lens filenames)."""
        self.get_logger().info("Received capture command.")
        self.blocking_calls.submit(self._capture)

    def _capture(self):
        info = self.dji_interface.requestCapture()
        if not info:
            self.get_logger().error("Capture failed.")
            self.capture_result_pub.publish(String(data='{"error":"capture failed"}'))
            return
        self.get_logger().info(f"Capture: {info}")
        self.capture_result_pub.publish(String(data=json.dumps(info)))

    def _cmd_capture_temperature(self, msg: VehicleCommand):
        """Read the hottest point on the thermal feed (no shutter, no download)."""
        self.get_logger().info("Received capture temperature command.")
        response = self.dji_interface.requestCaptureTemperature()
        try:
            temp = json.loads(response).get("thermalMaxTemp")
        except (ValueError, AttributeError):
            temp = None
        if temp is None:
            self.get_logger().warning(f"No thermal temperature available: {response!r}")
            return
        self._last_thermal_max_temp = float(temp)

    def _cmd_list_media(self, msg: VehicleCommand):
        """List the camera SD card. Publishes the JSON file list on camera/media_list."""
        self.get_logger().info("Received list media command.")
        self.blocking_calls.submit(self._list_media)

    def _list_media(self):
        files = self.dji_interface.listMedia()
        if files is False:
            self.get_logger().error("listMedia failed.")
            return
        self.get_logger().info(f"listMedia: {len(files)} file(s)")
        self.media_list_pub.publish(String(data=json.dumps(files)))

    def _cmd_lrf_measure(self, msg: VehicleCommand):
        """Fire the laser range finder once. Publishes the raw JSON reading."""
        self.get_logger().info("Received LRF measure command.")
        reading = self.dji_interface.requestLRFMeasure()
        self.lrf_measurement_pub.publish(String(data=json.dumps(reading)))

    def _cmd_drop(self, msg: VehicleCommand):
        self.get_logger().info("Received payload drop command.")
        self.dji_interface.requestDrop()

    ################################################
    # Other command topics (non-numeric payloads)  #
    ################################################

    def trajectory_setpoint_callback(self, msg: TrajectorySetpoint):
        """Navigate to a waypoint. `yaw_mode` selects between turning to face the leg (nose
        forward, `yaw` = final arrival heading) and crabbing sideways while holding `yaw` for the
        whole flight — replaces the old goto_waypoint(_nose_forward)/goto_waypoint_hold_heading
        topic split."""
        self.get_logger().info(
            f"Received trajectory setpoint: lat={msg.latitude}, lon={msg.longitude}, "
            f"alt={msg.altitude}, yaw={msg.yaw}, speed={msg.speed}, yaw_mode={msg.yaw_mode}"
        )
        if msg.yaw_mode == TrajectorySetpoint.YAW_MODE_HOLD_HEADING:
            self.dji_interface.requestSendGoToWaypointHoldHeading(
                msg.latitude, msg.longitude, msg.altitude, msg.yaw, msg.speed
            )
            return

        seq = self.dji_interface.requestSendGoToWaypointNoseForward(
            msg.latitude, msg.longitude, msg.altitude, msg.yaw, msg.speed
        )
        self._publish_command_ack(_MAV_CMD_NAV_WAYPOINT, parse_ack_seq(seq))

    def manual_control_setpoint_callback(self, msg: ManualControlSetpoint):
        """Virtual stick control, PX4-ManualControlSetpoint-style. roll/pitch are the right
        stick, throttle/yaw the left stick, each in [-1, 1]."""
        self.dji_interface.requestSendStick(msg.yaw, msg.throttle, msg.roll, msg.pitch)

    def goto_trajectory_dji_native_callback(self, msg: String):
        """Navigate using DJI's native waypoint mission system.
        Expected format: "(speed, [(lat, lon, alt), (lat, lon, alt), ...])"
        or legacy format: "[(lat, lon, alt), (lat, lon, alt), ...]"
        """
        self.get_logger().info("Received DJI native trajectory command.")
        try:
            data = ast.literal_eval(msg.data)
        except (ValueError, SyntaxError, TypeError) as exc:
            self.get_logger().warning(f"Malformed DJI native trajectory payload, ignoring: {exc}")
            return

        # Support both formats: (speed, waypoints) tuple or just waypoints list
        if isinstance(data, tuple) and len(data) == 2:
            speed, waypoints = data
        else:
            # Legacy format: just waypoints, use default speed
            waypoints = data
            speed = 10.0

        self.get_logger().info(f"Received DJI native waypoints: {waypoints}, speed: {speed} m/s")
        self.dji_interface.requestSendNavigateTrajectoryDJINative(waypoints, speed)

    def set_setting_callback(self, msg):
        """Set a drone/app setting. Payload: 'key=value' (webapp setting keys)."""
        self.get_logger().info(f"Received set setting command: {msg.data}")
        text = msg.data.strip()
        key, sep, value = text.partition("=")
        key = key.strip()
        value = value.strip()
        if not sep or not key or not value:
            self.get_logger().warning(
                f"Invalid set_setting payload: {msg.data!r}; expected 'key=value'"
            )
            return
        result = self.dji_interface.requestSetSetting(key, value)
        if result:
            self.get_logger().info(f"Setting {key} updated: {result}")
        else:
            self.get_logger().error(f"Failed to set {key}")

    def download_media_callback(self, msg: String):
        """Download one file by its on-camera name. Publishes the saved path, '' on failure."""
        self.get_logger().info(f"Received download media command: {msg.data}")
        self.blocking_calls.submit(self._download_media, msg.data)

    def _download_media(self, file_name):
        path = self.dji_interface.downloadByName(file_name, out_dir=self.media_dir)
        if path is None:
            self.get_logger().error(f"Download failed: {file_name}")
        self.download_result_pub.publish(String(data=path or ""))

    def publish_settings(self):
        """Publish the current settings JSON on fmu/out/settings."""
        settings = self.dji_interface.getSettings()
        if settings is not None:
            self.settings_pub.publish(String(data=json.dumps(settings)))

    ##############################
    # Telemetry Publishers       #
    ##############################

    def publish_states(self):
        try:
            # Only republish when the drone has actually sent something new — see the timer
            # comment in __init__.
            sequence, telemetry = self.dji_interface.getTelemetryUpdate(self._last_telemetry_seq)

            if not telemetry:
                return  # Nothing new since the last publish
            self._last_telemetry_seq = sequence
            now = self._now_us()

            # Local kinematic state: speed (scalar and vector), heading, altitude, attitude
            speed_data = telemetry.get("speed", {})
            speed_x = float(speed_data.get("x", 0.0))
            speed_y = float(speed_data.get("y", 0.0))
            speed_z = float(speed_data.get("z", 0.0))
            speed = float(np.sqrt(speed_x**2 + speed_y**2 + speed_z**2))

            self.vehicle_local_position_pub.publish(
                VehicleLocalPosition(
                    timestamp=now,
                    vx=speed_x,
                    vy=speed_y,
                    vz=speed_z,
                    speed=speed,
                    heading=float(telemetry.get("heading", 0.0)),
                    # Barometric altitude relative to takeoff (app's KeyAltitude — the same
                    # value the app's own altitude widget displays)
                    alt=float(telemetry.get("altitude", 0.0)),
                    attitude=str(telemetry.get("attitude", {})),
                )
            )

            # Global position + satellite count
            location = telemetry.get("location", {})
            self.vehicle_global_position_pub.publish(
                VehicleGlobalPosition(
                    timestamp=now,
                    lat=float(location.get("latitude", 0.0)),
                    lon=float(location.get("longitude", 0.0)),
                    alt=float(location.get("altitude", 0.0)),
                    satellite_count=int(telemetry.get("satelliteCount", -1)),
                )
            )

            # Home position
            home_location = telemetry.get("homeLocation", {})
            self.home_position_pub.publish(
                HomePosition(
                    timestamp=now,
                    lat=float(home_location.get("latitude", 0.0)),
                    lon=float(home_location.get("longitude", 0.0)),
                    valid_hpos=bool(telemetry.get("homeSet", False)),
                )
            )

            # Battery / endurance
            self.battery_status_pub.publish(
                BatteryStatus(
                    timestamp=now,
                    remaining=float(telemetry.get("batteryLevel", -1)) / 100.0,
                    time_remaining_s=float(telemetry.get("remainingFlightTime", 0)),
                    distance_to_home_m=float(telemetry.get("distanceToHome", 0.0)),
                    time_needed_to_go_home_s=float(telemetry.get("timeNeededToGoHome", 0)),
                    time_needed_to_land_s=float(telemetry.get("timeNeededToLand", 0)),
                    time_to_landing_spot_s=float(telemetry.get("totalTime", 0)),
                    max_radius_can_fly_and_go_home_m=float(
                        telemetry.get("maxRadiusCanFlyAndGoHome", 0)
                    ),
                    battery_needed_to_go_home=float(telemetry.get("batteryNeededToGoHome", 0)),
                    battery_needed_to_land=float(telemetry.get("batteryNeededToLand", 0)),
                )
            )

            # Vehicle mode / readiness
            self.vehicle_status_pub.publish(
                VehicleStatus(
                    timestamp=now,
                    flight_mode=telemetry.get("flightMode", "UNKNOWN"),
                    manual_override_active=bool(telemetry.get("isManualOverrideActive", False)),
                    ready_to_takeoff=bool(telemetry.get("readyToTakeoff", False)),
                    takeoff_block_reason=telemetry.get("takeoffBlockReason", "UNKNOWN"),
                )
            )

            # Mission progress (distinct from the one-shot vehicle_command_ack)
            self.mission_result_pub.publish(
                MissionResult(
                    timestamp=now,
                    waypoint_reached=bool(telemetry.get("waypointReached", False)),
                    intermediary_waypoint_reached=bool(
                        telemetry.get("intermediaryWaypointReached", False)
                    ),
                    altitude_reached=bool(telemetry.get("altitudeReached", False)),
                    yaw_reached=bool(telemetry.get("yawReached", False)),
                    waypoint_seq=int(telemetry.get("waypointSeq", -1)),
                    altitude_seq=int(telemetry.get("altitudeSeq", -1)),
                    yaw_seq=int(telemetry.get("yawSeq", -1)),
                )
            )

            # Gimbal
            gimbal_attitude = telemetry.get("gimbalAttitude", {})
            self.gimbal_status_pub.publish(
                GimbalStatus(
                    timestamp=now,
                    attitude=str(gimbal_attitude),
                    joint_attitude=str(telemetry.get("gimbalJointAttitude", {})),
                    yaw=float(gimbal_attitude.get("yaw", 0.0)),
                    pitch=float(gimbal_attitude.get("pitch", 0.0)),
                )
            )

            # Camera (zoom/lens/recording state + last known thermal reading)
            self.camera_status_pub.publish(
                CameraStatus(
                    timestamp=now,
                    zoom_focal_length=float(telemetry.get("zoomFl", -1)),
                    hybrid_focal_length=float(telemetry.get("hybridFl", -1)),
                    optical_focal_length=float(telemetry.get("opticalFl", -1)),
                    zoom_ratio=float(telemetry.get("zoomRatio", 1.0)),
                    is_recording=bool(telemetry.get("isRecording", False)),
                    thermal_max_temp=self._last_thermal_max_temp,
                )
            )

            # Last LRF-locked target (only published once the LRF has locked something)
            lrf_target = telemetry.get("lrfTarget")
            if lrf_target:
                self.lrf_target_pub.publish(
                    NavSatFix(
                        latitude=float(lrf_target.get("latitude", 0.0)),
                        longitude=float(lrf_target.get("longitude", 0.0)),
                        altitude=float(lrf_target.get("altitude", 0.0)),
                    )
                )

        except Exception as e:
            self.get_logger().error(f"Error while publishing states: {e}")


def main(args=None):
    rclpy.init(args=args)
    node = DjiNode()
    if not node.connection_ready:
        # The drone did not answer. Tear down just this node and the context we created.
        node.destroy_node()
        rclpy.shutdown()
        return
    try:
        rclpy.spin(node)
    finally:
        # Guard against a double shutdown (rclpy raises if the context is not
        # initialized); the pool may not exist if init failed late.
        if getattr(node, "blocking_calls", None):
            node.blocking_calls.shutdown(wait=False)
        if rclpy.ok():
            node.destroy_node()
            rclpy.shutdown()


if __name__ == "__main__":
    main()
