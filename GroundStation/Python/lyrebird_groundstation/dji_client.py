"""Canonical Lyrebird DJI HTTP/TCP client."""

from __future__ import annotations

import json
import os
import re
import socket
import threading
import time
from collections.abc import Callable
from contextlib import suppress
from datetime import datetime
from typing import Any

import requests

from lyrebird_groundstation.discovery import (
    discover_drone as _discover_drone,
)
from lyrebird_groundstation.dji_helpers import (
    build_command_url,
    parse_telemetry_chunk,
)
from lyrebird_groundstation.transport import (
    TCP_GAP_MODE_REQUEST,
    MavlinkCommandChannel,
    MavlinkTelemetrySource,
    Transport,
    mavlink_peer_port_from_env,
    mavlink_port_from_env,
    signing_key_from_env,
)

DISCOVERY_RESPONSE_PREFIX = "LYREBIRD_HERE:"
LENS_KEYS = ("thermal", "wide", "zoom")
SAVE_SUCCESS = "T_IMG_SAVE_SUCCESS"
SAVE_FAILURE = "T_IMG_SAVE_FAILURE"
CAP_FAILURE = "T_IMG_CAP_FAILURE"

EP_STICK = "/send/stick"
EP_ZOOM = "/send/camera/zoom"
EP_GIMBAL_SET_PITCH = "/send/gimbal/pitch"
EP_GIMBAL_SET_YAW = "/send/gimbal/yaw"
EP_TAKEOFF = "/send/takeoff"
EP_LAND = "/send/land"
EP_RTH = "/send/RTH"
EP_ENABLE_VIRTUAL_STICK = "/send/enableVirtualStick"
EP_ABORT_MISSION = "/send/abortMission"
EP_ABORT_ALL = "/send/abortAll"
EP_GOTO_YAW = "/send/gotoYaw"
EP_GOTO_ALTITUDE = "/send/gotoAltitude"
EP_CAMERA_START_RECORDING = "/send/camera/startRecording"
EP_CAMERA_STOP_RECORDING = "/send/camera/stopRecording"
EP_GOTO_TRAJECTORY_DJI_NATIVE = "/send/navigateTrajectoryDJINative"
EP_ABORT_DJI_NATIVE_MISSION = "/send/abort/DJIMission"
EP_SET_RTH_ALTITUDE = "/send/setRTHAltitude"
EP_DEACTIVATE_MANUAL_OVERRIDE = "/send/deactivateManualOverride"

# --- Lyrebird settings endpoints (mirror of the phone HTTP surface) ---
EP_SET_MAX_FLIGHT_HEIGHT = "/send/setMaxFlightHeight"
EP_SET_MAX_FLIGHT_DISTANCE = "/send/setMaxFlightDistance"
EP_SET_DISTANCE_LIMIT_ENABLED = "/send/setDistanceLimitEnabled"
EP_SET_DRONE_NAME = "/send/setDroneName"
EP_SET_VIDEO_SOURCE = "/send/setVideoSource"
EP_SET_WEBRTC_RESOLUTION = "/send/setWebRtcResolution"
EP_SET_WEBRTC_FPS = "/send/setWebRtcFps"
EP_SET_DETECTIONS_ENABLED = "/send/setDetectionsEnabled"
EP_SET_DETECTION_SOURCE = "/send/setDetectionSource"
EP_SET_EDGE_CONFIDENCE = "/send/setEdgeConfidence"
EP_SET_MEDIAMTX_SERVER = "/send/setMediamtxServer"
EP_STREAMING_MODE = "/send/streaming/mode"
EP_SET_RC_CONTROL_MODE = "/send/setRcControlMode"
EP_RC_PAIRING_START = "/send/rcPairing/start"
EP_RC_PAIRING_STOP = "/send/rcPairing/stop"
EP_GET_SETTINGS = "/config/settings"

# Maps the webapp/dashboard setting key to the phone HTTP endpoint that writes it.
# Every value is sent as the raw request body, which is what the phone parses.
SETTING_ENDPOINTS: dict[str, str] = {
    "rthAltitude": EP_SET_RTH_ALTITUDE,
    "maxFlightHeight": EP_SET_MAX_FLIGHT_HEIGHT,
    "maxFlightDistance": EP_SET_MAX_FLIGHT_DISTANCE,
    "distanceLimitEnabled": EP_SET_DISTANCE_LIMIT_ENABLED,
    "droneName": EP_SET_DRONE_NAME,
    "videoSource": EP_SET_VIDEO_SOURCE,
    "webrtcResolution": EP_SET_WEBRTC_RESOLUTION,
    "webrtcFps": EP_SET_WEBRTC_FPS,
    "detectionsEnabled": EP_SET_DETECTIONS_ENABLED,
    "detectionSource": EP_SET_DETECTION_SOURCE,
    "edgeConfidenceThreshold": EP_SET_EDGE_CONFIDENCE,
    "mediamtxServer": EP_SET_MEDIAMTX_SERVER,
    "streamingMode": EP_STREAMING_MODE,
    "rcControlMode": EP_SET_RC_CONTROL_MODE,
}

# --- payload, thermal, media and waypoint endpoints from the XPRIZE release ---
EP_CAPTURE_THERMAL_IMAGE = "/send/captureThermalImage"
EP_CAPTURE_TEMPERATURE = "/send/captureTemperature"  # temperature-only read, no shutter
EP_GIMBAL_SET_REL_PITCH = "/send/gimbal/rel_pitch"
EP_GIMBAL_SET_REL_YAW = "/send/gimbal/rel_yaw"
EP_GOTO_WP_NOSE_FORWARD = "/send/gotoWaypointNoseForward"
EP_LRF_MEASURE = "/send/lrf/measure"
EP_LIST_MEDIA = "/send/listMedia"
EP_DOWNLOAD_MEDIA_BY_NAME = "/send/downloadMediaByName"
EP_GOTO_WP_HOLD_HEADING = "/send/gotoWaypointHoldHeading"
EP_PAYLOAD_DROP = "/send/drop"
EP_GET_MANUAL_OVERRIDE = "/get/isManualOverrideActive"

DiscoveryResult = str | tuple[str | None, str | None] | None


def telemetry_timestamp() -> str:
    return datetime.now().strftime("%Y-%m-%d_%H-%M-%S.%f")


def get_config(ip_address: str) -> dict[str, Any] | None:
    """Query drone configuration via HTTP GET /config endpoint."""
    try:
        response = requests.get(f"http://{ip_address}:8080/config", timeout=2.0)
        if response.status_code == 200:
            return json.loads(response.text)
    except Exception as exc:
        print(f"Failed to get config from {ip_address}: {exc}")
    return None


def get_settings(ip_address: str) -> dict[str, Any] | None:
    """Query the full Lyrebird settings JSON via HTTP GET /config/settings."""
    try:
        response = requests.get(f"http://{ip_address}:8080/config/settings", timeout=2.0)
        if response.status_code == 200:
            return json.loads(response.text)
    except Exception as exc:
        print(f"Failed to get settings from {ip_address}: {exc}")
    return None


def discover_drone(timeout: float = 5.0) -> str | None:
    """Discover the first Lyrebird drone on the local network using UDP broadcast."""
    found = _discover_drone(timeout, verbose=True)
    return found.ip_address if found else None


def _normalize_discovery_result(result: DiscoveryResult) -> tuple[str, str]:
    if result is None:
        return "", "UNKNOWN"
    if isinstance(result, tuple):
        ip_address, drone_name = result
        return ip_address or "", drone_name or "UNKNOWN"
    return result, "UNKNOWN"


class DJIInterface:
    """Interface for DJI drone control via HTTP commands and TCP telemetry."""

    def __init__(
        self,
        IP_RC: str = "",
        *,
        discover_callback: Callable[[], DiscoveryResult] | None = None,
        config_loader: Callable[[str], dict[str, Any] | None] | None = None,
        query_config_name: bool = False,
        timestamp_factory: Callable[[], str] = telemetry_timestamp,
        transport: Transport | None = None,
        mavlink_port: int | None = None,
        mavlink_peer_port: int | None = None,
    ):
        self.drone_name = "UNKNOWN"
        self._timestamp_factory = timestamp_factory
        config_loader = config_loader or get_config

        if not IP_RC and discover_callback is not None:
            print("No IP provided, attempting to discover drone...")
            discovered_ip, discovered_name = _normalize_discovery_result(discover_callback())
            if discovered_ip:
                self.IP_RC = discovered_ip
                self.drone_name = discovered_name
            else:
                print("Drone discovery failed.")
                self.IP_RC = ""
        else:
            self.IP_RC = IP_RC

        if self.IP_RC and query_config_name:
            config = config_loader(self.IP_RC)
            if config and "droneName" in config:
                self.drone_name = str(config["droneName"])
                print(f"Retrieved drone name from config: {self.drone_name}")

        self.baseCommandUrl = f"http://{self.IP_RC}:8080"
        self.telemetryPort = 8081
        self.videoSource = f"rtsp://aaa:aaa@{self.IP_RC}:8554/streaming/live/1"

        self._telemetry: dict[str, Any] = {}
        # Incremented once per snapshot received. Lets a consumer tell a fresh sample from the
        # same one read again, so it can publish at the rate the drone actually sends rather than
        # at whatever rate its own timer happens to fire.
        self._telemetry_seq = 0
        self._telemetry_lock = threading.Lock()
        self._telemetry_socket = None
        self._telemetry_thread = None
        self._running = False

        self._configure_transport(transport, mavlink_port, mavlink_peer_port)

    def _configure_transport(self, transport, mavlink_port, mavlink_peer_port=None):
        """Choose the wire this client talks over.

        Defaults to the environment so a whole stack -- scripts, the ROS node, the safety wrapper
        -- can be switched with one variable rather than each caller growing an argument.
        """
        self.transport = transport if transport is not None else Transport.from_env()
        self.mavlink_port = mavlink_port if mavlink_port is not None else mavlink_port_from_env()
        self.mavlink_peer_port = (
            mavlink_peer_port if mavlink_peer_port is not None else mavlink_peer_port_from_env()
        )
        self._mavlink_telemetry: MavlinkTelemetrySource | None = None
        self._mavlink_commands: MavlinkCommandChannel | None = None
        if self.transport.uses_mavlink:
            self._mavlink_commands = MavlinkCommandChannel(
                self.IP_RC,
                port=self.mavlink_peer_port,
                # A completed goto raises the same reach latch the HTTP surface exposes, so
                # isWaypointReached(seq) and friends keep working without the caller knowing
                # which wire the answer came from.
                on_latch=self._apply_mavlink_telemetry,
                # Sign every command with LB_MAVLINK_SIGNING_KEY when configured, so the
                # aircraft treats this ground station as the Safety Computer.
                signing_key=signing_key_from_env(),
            )
            print(
                f"Transport: {self.transport.value} "
                f"(listening on udp/{self.mavlink_port}, aircraft on udp/{self.mavlink_peer_port})"
            )

    def getVideoSource(self):
        if self.IP_RC == "":
            return ""
        return self.videoSource

    def startTelemetryStream(self):
        """Start receiving telemetry data via TCP socket connection."""
        if self._running:
            return

        self._running = True
        if self.transport.uses_mavlink:
            self._mavlink_telemetry = MavlinkTelemetrySource(
                # Where we listen, which need not be where the aircraft listens: another ground
                # station on this machine may already hold 14550.
                port=self.mavlink_port,
                on_update=self._apply_mavlink_telemetry,
                # Only this aircraft's stream. In a fleet each aircraft needs its own UDP port
                # as well, since one socket per port is all the OS will hand packets to.
                peer_host=self.IP_RC,
                peer_port=self.mavlink_peer_port,
            )
            self._mavlink_telemetry.start()
        if self.transport is not Transport.MAVLINK:
            self._telemetry_thread = threading.Thread(target=self._telemetry_receiver, daemon=True)
            self._telemetry_thread.start()

    def stopTelemetryStream(self):
        """Stop the telemetry stream and close the socket."""
        self._running = False
        if self._mavlink_telemetry is not None:
            self._mavlink_telemetry.stop()
            self._mavlink_telemetry = None
        if self._telemetry_socket:
            self._close_telemetry_socket()
        if self._telemetry_thread:
            self._telemetry_thread.join(timeout=2)

    def _connect_telemetry_socket(self):
        self._telemetry_socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self._telemetry_socket.settimeout(5.0)
        self._telemetry_socket.connect((self.IP_RC, self.telemetryPort))
        if self.transport is Transport.BOTH:
            # MAVLink already carries the bulk of this; ask the aircraft to skip re-sending it
            # over TCP too, rather than getting the same state twice on two wires.
            with suppress(OSError):
                self._telemetry_socket.sendall(TCP_GAP_MODE_REQUEST)

    def _process_telemetry_data(self, buffer, data):
        buffer, telemetry_items = parse_telemetry_chunk(
            buffer,
            data,
            timestamp_factory=self._timestamp_factory,
        )
        for telemetry in telemetry_items:
            with self._telemetry_lock:
                if telemetry.get("telemetryMode") == "gap":
                    # A trimmed payload carries only the fields MAVLink can't; merge it, matching
                    # _apply_mavlink_telemetry, so it doesn't erase MAVLink-sourced fields. Decided
                    # from the payload's own marker rather than self.transport, so this stays
                    # correct even against an aircraft build that predates gap mode and ignores
                    # the request, sending full, unmarked objects regardless.
                    self._telemetry.update(telemetry)
                else:
                    self._telemetry = telemetry
                self._telemetry_seq += 1
        return buffer

    def _read_telemetry_stream(self, buffer):
        while self._running:
            data = self._telemetry_socket.recv(4096)
            if not data:
                break
            buffer = self._process_telemetry_data(buffer, data)
        return buffer

    def _close_telemetry_socket(self):
        with suppress(OSError):
            self._telemetry_socket.close()

    def _telemetry_receiver(self):
        """Background thread that receives telemetry data from TCP socket."""
        buffer = ""
        while self._running:
            try:
                self._connect_telemetry_socket()
                buffer = self._read_telemetry_stream(buffer)
            except TimeoutError:
                continue
            except Exception as exc:
                print(f"Telemetry connection error: {exc}")
                time.sleep(1)
            finally:
                if self._telemetry_socket:
                    self._close_telemetry_socket()

    def _apply_mavlink_telemetry(self, telemetry):
        """Merge a MAVLink-derived snapshot into the same state the HTTP reader fills.

        Merged rather than replaced: in ``both`` mode the two wires carry different subsets --
        MAVLink has no zoom ratio or thermal state, HTTP has no mission progress -- and a consumer
        should see the union rather than whichever arrived last.
        """
        with self._telemetry_lock:
            self._telemetry.update(telemetry)
            self._telemetry_seq += 1

    def getTelemetry(self):
        """Get the latest telemetry data."""
        with self._telemetry_lock:
            return self._telemetry.copy()

    def getTelemetrySequence(self):
        """How many telemetry snapshots have arrived since this client started."""
        with self._telemetry_lock:
            return self._telemetry_seq

    def getTelemetryUpdate(self, last_sequence):
        """Latest telemetry, but only when it is newer than ``last_sequence``.

        Returns ``(sequence, telemetry)``, with ``telemetry`` None when nothing new has arrived.
        Reading the sequence and the snapshot under one lock matters: taking them separately
        could pair a sequence with a snapshot from the next sample.

        This exists so a consumer's publish rate follows the drone rather than its own timer. The
        aircraft's TCP telemetry interval is configurable and currently ~2 Hz, so a 20 Hz poller
        that published unconditionally sent every sample about ten times over.
        """
        with self._telemetry_lock:
            if self._telemetry_seq == last_sequence:
                return last_sequence, None
            return self._telemetry_seq, self._telemetry.copy()

    def requestAllStates(self, verbose=False):
        """Get all aircraft states from telemetry."""
        telemetry = self.getTelemetry()
        if verbose and telemetry:
            print("Telemetry:", json.dumps(telemetry, indent=2))
        return telemetry

    def getSpeed(self):
        return self.getTelemetry().get("speed", {})

    def getHeading(self):
        return self.getTelemetry().get("heading", 0.0)

    def getAttitude(self):
        return self.getTelemetry().get("attitude", {})

    def getLocation(self):
        return self.getTelemetry().get("location", {})

    def getGimbalAttitude(self):
        return self.getTelemetry().get("gimbalAttitude", {})

    def getGimbalJointAttitude(self):
        return self.getTelemetry().get("gimbalJointAttitude", {})

    def getZoomFocalLength(self):
        return self.getTelemetry().get("zoomFl", -1)

    def getHybridFocalLength(self):
        return self.getTelemetry().get("hybridFl", -1)

    def getOpticalFocalLength(self):
        return self.getTelemetry().get("opticalFl", -1)

    def getZoomRatio(self):
        return self.getTelemetry().get("zoomRatio", 1.0)

    def getBatteryLevel(self):
        return self.getTelemetry().get("batteryLevel", -1)

    def getSatelliteCount(self):
        return self.getTelemetry().get("satelliteCount", -1)

    def getHomeLocation(self):
        return self.getTelemetry().get("homeLocation", {})

    def getDistanceToHome(self):
        return self.getTelemetry().get("distanceToHome", 0.0)

    def isIntermediaryWaypointReached(self):
        return self.getTelemetry().get("intermediaryWaypointReached", False)

    def isYawReached(self):
        return self.getTelemetry().get("yawReached", False)

    def isAltitudeReached(self):
        return self.getTelemetry().get("altitudeReached", False)

    def isCameraRecording(self):
        return self.getTelemetry().get("isRecording", False)

    def isHomeSet(self):
        return self.getTelemetry().get("homeSet", False)

    def getRemainingFlightTime(self):
        return self.getTelemetry().get("remainingFlightTime", 0)

    def getTimeNeededToGoHome(self):
        return self.getTelemetry().get("timeNeededToGoHome", 0)

    def getTimeNeededToLand(self):
        return self.getTelemetry().get("timeNeededToLand", 0)

    def getTotalTime(self):
        return self.getTelemetry().get("totalTime", 0)

    def getMaxRadiusCanFlyAndGoHome(self):
        return self.getTelemetry().get("maxRadiusCanFlyAndGoHome", 0)

    def getRemainingCharge(self):
        return self.getTelemetry().get("remainingCharge", 0)

    def getBatteryNeededToLand(self):
        return self.getTelemetry().get("batteryNeededToLand", 0)

    def getBatteryNeededToGoHome(self):
        return self.getTelemetry().get("batteryNeededToGoHome", 0)

    def getSeriousLowBatteryThreshold(self):
        return self.getTelemetry().get("seriousLowBatteryThreshold", 0)

    def getLowBatteryThreshold(self):
        return self.getTelemetry().get("lowBatteryThreshold", 0)

    def getFlightMode(self):
        return self.getTelemetry().get("flightMode", "UNKNOWN")

    def isManualOverrideActive(self):
        return self.getTelemetry().get("isManualOverrideActive", False)

    def _post(self, endPoint, data="", timeout=5, **kwargs):
        """Single HTTP POST chokepoint for every command this client sends.

        All outbound commands go through here so a subclass can authenticate the whole
        surface by overriding one method. DJIInterfaceSafety does exactly that to attach
        the X-Safety-Token header; methods that call requests.post directly would bypass
        it and be rejected as Pilot traffic once the Safety Computer holds authority.
        """
        return requests.post(
            build_command_url(self.baseCommandUrl, endPoint), data, timeout=timeout, **kwargs
        )

    def requestSend(self, endPoint, data, verbose=False):
        """Send a POST request to the drone."""
        if self.IP_RC == "":
            print(f"No IP_RC provided, returning empty string for request at {endPoint}")
            return ""
        if self._mavlink_commands is not None and self._mavlink_commands.supports(endPoint):
            response = self._mavlink_commands.send(endPoint, str(data))
            if verbose:
                print("EP : " + endPoint + "\t" + response)
            return response
        if not self.transport.allows_http_fallback:
            # Deliberately not falling back. In mavlink-only mode a gap must be visible.
            message = f"REJECTED: no MAVLink equivalent for {endPoint}"
            if verbose:
                print("EP : " + endPoint + "\t" + message)
            return message

        try:
            response = self._post(endPoint, str(data))
            if verbose:
                print("EP : " + endPoint + "\t" + str(response.content, encoding="utf-8"))
            return response.content.decode("utf-8")
        except requests.exceptions.RequestException as exc:
            print(f"Request error at {endPoint}: {exc}")
            return ""

    def requestSendStick(self, leftX=0, leftY=0, rightX=0, rightY=0):
        s = 0.3
        leftX = max(-s, min(s, leftX))
        leftY = max(-s, min(s, leftY))
        rightX = max(-s, min(s, rightX))
        rightY = max(-s, min(s, rightY))
        return self.requestSend(EP_STICK, f"{leftX:.4f},{leftY:.4f},{rightX:.4f},{rightY:.4f}")

    def requestSendGimbalPitch(self, pitch=0):
        return self.requestSend(EP_GIMBAL_SET_PITCH, f"0,{pitch},0")

    def requestSendGimbalYaw(self, yaw=0):
        return self.requestSend(EP_GIMBAL_SET_YAW, f"0,0,{yaw}")

    def requestSendZoomRatio(self, zoomRatio=1):
        return self.requestSend(EP_ZOOM, zoomRatio)

    def requestSendTakeOff(self):
        return self.requestSend(EP_TAKEOFF, "")

    def requestSendLand(self):
        return self.requestSend(EP_LAND, "")

    def requestSendRTH(self):
        self.requestAbortMission()
        return self.requestSend(EP_RTH, "")

    def requestSendNavigateTrajectoryDJINative(self, waypoints, speed: float = 10.0):
        if not waypoints:
            raise ValueError("No waypoints provided")
        if len(waypoints) < 2:
            raise ValueError("Need at least 2 waypoints for DJI native mission")

        segments = [str(speed)]
        for lat, lon, alt in waypoints:
            segments.append(f"{lat},{lon},{alt}")

        return self.requestSend(EP_GOTO_TRAJECTORY_DJI_NATIVE, ";".join(segments))

    def requestAbortDJINativeMission(self):
        return self.requestSend(EP_ABORT_DJI_NATIVE_MISSION, "")

    def requestAbortMission(self):
        return self.requestSend(EP_ABORT_MISSION, "")

    def requestAbortAll(self):
        return self.requestSend(EP_ABORT_ALL, "")

    def requestSendEnableVirtualStick(self):
        return self.requestSend(EP_ENABLE_VIRTUAL_STICK, "")

    def requestSendGotoYaw(self, yaw):
        self.requestSendEnableVirtualStick()
        return self.requestSend(EP_GOTO_YAW, f"{yaw}")

    def requestSendGotoAltitude(self, altitude):
        self.requestSendEnableVirtualStick()
        return self.requestSend(EP_GOTO_ALTITUDE, f"{altitude}")

    def requestCameraStartRecording(self):
        return self.requestSend(EP_CAMERA_START_RECORDING, "")

    def requestCameraStopRecording(self):
        return self.requestSend(EP_CAMERA_STOP_RECORDING, "")

    def requestSetRTHAltitude(self, altitude):
        return self.requestSend(EP_SET_RTH_ALTITUDE, str(altitude))

    def requestSetSetting(self, key: str, value) -> str:
        """Set a single Lyrebird setting by webapp key.

        The phone parses the raw request body for every /send/set* endpoint, so the
        value is sent as a string body (same as requestSetRTHAltitude). Returns the
        phone's response text, or "" for an unknown key or a failed request.
        """
        endpoint = SETTING_ENDPOINTS.get(key)
        if endpoint is None:
            print(f"Unknown setting key: {key}")
            return ""
        return self.requestSend(endpoint, str(value))

    def getSettings(self) -> dict[str, Any] | None:
        """Read the full settings JSON via GET /config/settings."""
        if self.IP_RC == "":
            return None
        return get_settings(self.IP_RC)

    def requestRcPairingStart(self) -> str:
        """Start RC pairing (RC <-> aircraft link)."""
        return self.requestSend(EP_RC_PAIRING_START, "")

    def requestRcPairingStop(self) -> str:
        """Stop RC pairing (RC <-> aircraft link)."""
        return self.requestSend(EP_RC_PAIRING_STOP, "")

    def requestDeactivateManualOverride(self):
        return self.requestSend(EP_DEACTIVATE_MANUAL_OVERRIDE, "")

    def requestSticks(self):
        print("Warning: requestSticks() is deprecated. Use getTelemetry() instead.")
        return ""

    def requestWaypointStatus(self):
        return str(self.isWaypointReached()).lower()

    def requestIntermediaryWaypointStatus(self):
        return str(self.isIntermediaryWaypointReached()).lower()

    def requestYawStatus(self):
        return str(self.isYawReached()).lower()

    def requestAltitudeStatus(self):
        return str(self.isAltitudeReached()).lower()

    def requestHomePosition(self):
        return self.getHomeLocation()

    def requestCameraIsRecording(self):
        return self.isCameraRecording()

    def isWaypointReached(self, seq=None):
        """Check if a commanded waypoint has been reached."""
        telemetry = self.getTelemetry()
        reached = telemetry.get("waypointReached", False)
        if seq is None:
            return reached
        return reached and telemetry.get("waypointSeq", -1) == seq

    @staticmethod
    def _parseSeq(response):
        """Extract the integer seq from an '<X>_ACCEPTED seq=<n> ...' response, else None."""
        match = re.search(r"seq=(\d+)", str(response))
        return int(match.group(1)) if match else None

    def requestSendGoToWaypointHoldHeading(
        self, latitude, longitude, altitude, yaw, speed: float = 5.0
    ):
        """Navigate to a waypoint holding a fixed heading for the whole flight.

        CONTRACT: the nose stays on `yaw` from start to arrival — the drone crabs sideways or
        diagonally instead of turning to face where it is going. Use this when the payload must
        keep looking at one bearing while repositioning. Tighter arrival tolerance than
        requestSendGoToWaypointNoseForward, which turns the nose along the leg instead.

        Args:
            latitude, longitude, altitude: Target position
            yaw: Heading (deg) held for the entire flight, not just on arrival
            speed: Max speed in m/s (default 5.0)

        Returns:
            int: the seq id parsed from "WAYPOINT_ACCEPTED seq=<n> ...", or None if rejected.
        """
        response = self.requestSend(
            EP_GOTO_WP_HOLD_HEADING, f"{latitude},{longitude},{altitude},{yaw},{speed}"
        )
        return self._parseSeq(response)

    def requestSendGoToWaypointNoseForward(
        self, latitude, longitude, altitude, yaw, speed: float = 20.0
    ):
        """Navigate to a waypoint with PID control (nose-follows-path, final-heading).

        CONTRACT: during travel the drone faces its direction of motion — the bridge forces the
        travel heading to bearing(current->waypoint). The `yaw` argument is the FINAL arrival
        heading: once the drone reaches the waypoint it rotates in place to `yaw` (Phase 3), and
        only then is the waypoint reported reached. If you instead need the nose pointed at `yaw`
        *while* translating, use requestSendGoToWaypointHoldHeading, which projects the to-waypoint
        vector into the body frame.

        Args:
            latitude: Target latitude
            longitude: Target longitude
            altitude: Target altitude
            yaw: Final arrival heading (deg). Drone rotates to this in place after reaching the WP;
                 it does NOT set the travel heading (that is auto = bearing to waypoint).
            speed: Max speed in m/s (default 20.0)

        Returns:
            int: the sequence id the app assigned to this request (parsed from the
                 "WAYPOINT_ACCEPTED seq=<n> ..." response). Pass it to
                 isWaypointReached(seq) to avoid the stale-latch race.
            None: if the command was rejected or the response had no seq.
        """
        response = self.requestSend(
            EP_GOTO_WP_NOSE_FORWARD, f"{latitude},{longitude},{altitude},{yaw},{speed}"
        )
        return self._parseSeq(response)

    @staticmethod
    def requestSendGimbalRelPitch(self, rel_pitch=0):
        """Adjust gimbal pitch by a relative angle."""
        return self.requestSend(EP_GIMBAL_SET_REL_PITCH, f"0,{rel_pitch},0")

    def requestSendGimbalRelYaw(self, rel_yaw=0):
        """Adjust gimbal yaw by a relative angle."""
        return self.requestSend(EP_GIMBAL_SET_REL_YAW, f"0,0,{rel_yaw}")

    def requestCapture(self):
        """Trigger ONE H20T shutter (no image download). Returns the capture descriptor.
        Returns:
            dict {"thermal": fn|None, "wide": fn|None, "zoom": fn|None} on success (fn is the
            on-camera filename, None if that lens was not stored), else False.

        Download any returned filename with downloadByName().
        For the thermal max temperature (no shutter), use requestCaptureTemperature().
        """

        if self.IP_RC == "":
            print("No IP_RC provided, cannot capture image")
            return False
        # Trip the shutter. The bridge returns a JSON descriptor naming the on-camera filename
        # of each lens the H20T stored (no image yet).
        try:
            # Generous timeout: the very first capture after connect can be cold (the bridge builds
            # the full SD-card list once), so allow well past the server's internal resolution cap.
            response = self._post(EP_CAPTURE_THERMAL_IMAGE, timeout=60)
        except requests.exceptions.RequestException as e:
            print(f"Error capturing image: {e}")
            return False
        try:
            info = response.json()
        except ValueError:
            print(
                f"Capture returned non-JSON: HTTP {response.status_code}, "
                f"body={response.text[:200]!r}"
            )
            return False
        if info.get("error") or not info.get("thermal"):
            print(f"Capture failed: {info}")
            return False
        return info

    def requestCaptureTemperature(self):
        """Read the highest temperature (deg C) on the thermal feed. No shutter, no download.

        Returns the bridge's raw JSON response body, e.g. '{"thermalMaxTemp":21.5}'
        (thermalMaxTemp is null if no radiometric value was available).
        """
        return self.requestSend(EP_CAPTURE_TEMPERATURE, "")

    def listMedia(self):
        """List every file on the camera's SD card (robust path — source of truth, not the
        bounded recent-capture cache).

        Returns:
            list of dicts {"name": str, "index": int, "size": int, "type": str} on success,
            else False.
        """
        if self.IP_RC == "":
            print("No IP_RC provided, cannot list media")
            return False
        try:
            response = self._post(EP_LIST_MEDIA, timeout=30)
        except requests.exceptions.RequestException as e:
            print(f"Error listing media: {e}")
            return False
        try:
            info = response.json()
        except ValueError:
            print(
                f"listMedia returned non-JSON: HTTP {response.status_code}, "
                f"body={response.text[:200]!r}"
            )
            return False
        return info.get("files", [])

    def downloadByName(self, file_name, save_path=None, out_dir="."):
        """Download ANY file from the SD card by its on-camera filename. Works for any file the
        camera ever wrote, regardless of how many captures happened since — no dependence on the
        bounded recent-capture cache.

        Args:
            file_name: the on-camera filename (e.g. from listMedia() or a capture descriptor).
            save_path: full output path; defaults to out_dir/file_name.
            out_dir: directory used when save_path is not given (created if missing).

        Returns:
            the saved path, or None on failure.
        """
        if self.IP_RC == "":
            print("No IP_RC provided, cannot download image")
            return None
        if not file_name:
            print("Download error: no file_name")
            return None
        if save_path is None:
            os.makedirs(out_dir, exist_ok=True)
            save_path = os.path.join(out_dir, file_name)
        try:
            response = self._post(EP_DOWNLOAD_MEDIA_BY_NAME, data=file_name, timeout=120)
        except requests.exceptions.RequestException as e:
            print(f"{file_name}: download error: {e}")
            return None
        content_type = response.headers.get("Content-Type", "")
        if response.status_code != 200 or not content_type.startswith("image/"):
            print(
                f"{file_name}: download failed (HTTP {response.status_code}, "
                f"Content-Type={content_type!r}, body={response.text[:200]!r})"
            )
            return None
        with open(save_path, "wb") as f:
            f.write(response.content)
        print(f"{file_name} saved to: {save_path} ({len(response.content)} bytes)")
        return save_path

    def requestLRFMeasure(self):
        """Fire the H20T laser range finder once and return its reading."""
        response = self.requestSend(EP_LRF_MEASURE, "")
        if not response:
            return {"distance": None, "target": None, "state": None}
        try:
            print(response)
            return json.loads(response)
        except ValueError:
            print(f"LRF: could not parse response: {response!r}")
            return {"distance": None, "target": None, "state": None}

    def getLRFTarget(self):
        """Get the last LRF-locked target position (latitude, longitude, altitude)."""
        return self.getTelemetry().get("lrfTarget")

    def requestDrop(self):
        """Drop the payload."""
        return self.requestSend(EP_PAYLOAD_DROP, "")

    def getWaypointSeq(self):
        """Id of the waypoint the streamed 'waypointReached' currently refers to.

        Mirrors DroneController._waypointSeq, incremented by the app for every
        requestSendGoToWaypointNoseForward. Returns -1 if telemetry hasn't reported it yet.
        """
        return self.getTelemetry().get("waypointSeq", -1)

    def getYawSeq(self):
        """Id of the gotoYaw command the streamed 'yawReached' refers to (-1 if unknown)."""
        return self.getTelemetry().get("yawSeq", -1)

    def getAltitudeSeq(self):
        """Id of the gotoAltitude command the streamed 'altitudeReached' refers to (-1 if unknown)."""
        return self.getTelemetry().get("altitudeSeq", -1)

    def isReadyToTakeoff(self):
        """Whether the drone is ready to take off / arm (derived on the aircraft side)."""
        return self.getTelemetry().get("readyToTakeoff", False)

    def getTakeoffBlockReason(self):
        """Reason the drone cannot take off: DJIDeviceStatus name, 'NONE', or 'UNKNOWN'."""
        return self.getTelemetry().get("takeoffBlockReason", "UNKNOWN")
