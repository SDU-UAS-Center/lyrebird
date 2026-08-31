#!/usr/bin/env python3
import json
import os
import socket
import threading
import time
import urllib.error
import urllib.request
from datetime import datetime
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any
from urllib.parse import unquote, urlparse

from video_events import (
    build_event_entry,
    format_sse_message,
    serialize_ndjson_entry,
)

PORT = int(os.environ.get("PORT", "8090"))
TELEMETRY_PORT = int(os.environ.get("TELEMETRY_PORT", "8081"))
DRONE_HTTP_PORT = int(os.environ.get("DRONE_HTTP_PORT", "8080"))

# Phone settings route table: webapp key -> (phone HTTP path, value coercer)
SETTING_ROUTES = {
    "rthAltitude": ("/send/setRTHAltitude", int),
    "maxFlightHeight": ("/send/setMaxFlightHeight", int),
    "maxFlightDistance": ("/send/setMaxFlightDistance", int),
    "distanceLimitEnabled": ("/send/setDistanceLimitEnabled", bool),
    "droneName": ("/send/setDroneName", str),
    "videoSource": ("/send/setVideoSource", str),
    "webrtcResolution": ("/send/setWebRtcResolution", str),
    "webrtcFps": ("/send/setWebRtcFps", int),
    "detectionsEnabled": ("/send/setDetectionsEnabled", bool),
    "detectionSource": ("/send/setDetectionSource", str),
    "edgeConfidenceThreshold": ("/send/setEdgeConfidence", float),
    "mediamtxServer": ("/send/setMediamtxServer", str),
    "rcControlMode": ("/send/setRcControlMode", str),
}

# Read-write action endpoints (no value body) mirrored from the phone HTTP surface.
RC_ACTIONS = {
    "/rcPairing/start": "/send/rcPairing/start",
    "/rcPairing/stop": "/send/rcPairing/stop",
}

# One-shot camera actions (no value body): trip a shutter or read the thermal spot, so the
# dashboard can test pic-taking without a separate client.
CAPTURE_ACTIONS = {
    "/capture/photo": "/send/capture",
    "/capture/temperature": "/send/captureTemperature",
    "/capture/thermal": "/send/captureThermalImage",
}


def _coerce_setting_value(key, coerce, value):
    """Coerce a webapp setting value to the phone payload; raises ValueError on bad input."""
    if coerce is bool:
        if isinstance(value, bool):
            return value
        if isinstance(value, str):
            lowered = value.strip().lower()
            if lowered in ("true", "1", "on"):
                return True
            if lowered in ("false", "0", "off"):
                return False
        raise ValueError(f"Invalid boolean value for {key}")
    return coerce(value)


DISCOVERY_INTERVAL_MS = int(os.environ.get("DISCOVERY_INTERVAL_MS", "5000"))
MEDIAMTX_API_URL = os.environ.get("MEDIAMTX_API_URL", "http://127.0.0.1:9997").rstrip("/")
MEDIAMTX_WEBRTC_URL = os.environ.get("MEDIAMTX_WEBRTC_URL", "http://127.0.0.1:8889").rstrip("/")

ALLOWED_URL_SCHEMES = ("http", "https")


def _open_url(target, timeout):
    """urlopen restricted to HTTP(S).

    Every URL here is built from an environment variable, so whoever sets the environment could
    otherwise point these calls at a file:// path or a custom scheme and have the process read
    local files. Validating the scheme is what makes the call safe; the nosec records that it was
    checked rather than ignored, and keeping the check in one place means a new call site cannot
    quietly skip it.
    """
    url = target.full_url if isinstance(target, urllib.request.Request) else target
    scheme = urlparse(url).scheme
    if scheme not in ALLOWED_URL_SCHEMES:
        raise ValueError(f"refusing to open URL with scheme {scheme!r}: {url!r}")
    return urllib.request.urlopen(target, timeout=timeout)  # nosec B310 - scheme checked above


LOG_DIR = Path(os.environ.get("LOG_DIR", "/logs"))
TELEMETRY_IDLE_TIMEOUT_S = float(os.environ.get("TELEMETRY_IDLE_TIMEOUT_S", "5"))
BASE_DIR = Path(__file__).resolve().parent
PUBLIC_DIR = BASE_DIR / "public"

DRONE_NAMES = [
    name.strip() for name in os.environ.get("DRONE_NAMES", "").split(",") if name.strip()
]
FALLBACK_IPS = {}
for item in os.environ.get("DRONE_FALLBACKS", "").split(","):
    if "=" in item:
        name, ip = item.split("=", 1)
        FALLBACK_IPS[name.strip()] = ip.strip()

def local_now():
    """Return the host-local time used by the dashboard and its event log."""
    return datetime.now().astimezone()


def local_now_iso():
    return local_now().isoformat()


LOG_DIR.mkdir(parents=True, exist_ok=True)
EVENT_LOG = LOG_DIR / f"video-connection-test-{local_now().strftime('%Y%m%dT%H%M%S%z')}.ndjson"

lock = threading.RLock()
sse_clients: list[Any] = []
telemetry_threads: dict[str, threading.Thread] = {}
telemetry_stop_events: dict[str, threading.Event] = {}
# Latest report from the ros-monitor container (POST /api/ros-status).
ROS_STATUS: dict[str, Any] = {}


def ros_status_snapshot():
    with lock:
        return dict(ROS_STATUS)


# The MAVLink panel needs the GroundStation client and pymavlink. An image built before those
# were added must still serve the rest of the dashboard rather than failing to start, so the
# import is optional and the panel reports its own absence.
try:
    from mavlink_debug import ComparisonRegistry

    #: Samples both wires for the MAVLink debug panel. The listen port is separate from the
    #: aircraft's because QGroundControl or the ROS node may already hold 14550 on this machine.
    mavlink_comparisons: Any = ComparisonRegistry(
        mavlink_port=int(os.environ.get("LB_WEBAPP_MAVLINK_PORT", "14552")),
        peer_port=int(os.environ.get("LB_MAVLINK_PEER_PORT", "14550")),
    )
except ImportError as import_error:  # pragma: no cover - depends on how the image was built
    mavlink_comparisons = None
    MAVLINK_UNAVAILABLE = (
        f"MAVLink comparison needs pymavlink and the GroundStation client ({import_error})."
    )
else:
    MAVLINK_UNAVAILABLE = ""


def mavlink_coverage():
    """Which HTTP endpoints also have a MAVLink form.

    The HTTP catalogue is the older surface and MAVLink is replacing it endpoint by endpoint, so
    "which of these still needs HTTP" is the question anyone reading that tab actually has. It is
    answered from the transport's own tables rather than from a list kept alongside them, which
    would drift the moment an endpoint was mapped.
    """
    try:
        from lyrebird_groundstation.transport import (
            _COMMAND_MAP,
            _SPECIAL_SENDERS,
            _TELEMETRY_HANDLERS,
            decode_autosensing_status,
            decode_lyrebird_config,
            decode_lyrebird_status,
        )
    except ImportError:
        return {"available": False, "endpoints": [], "streamed": []}

    # Reads are not commands. MAVLink streams state rather than answering a poll, so an endpoint
    # that only reports something is covered when its value is on the telemetry stream -- not by
    # inventing a command to ask for it. Reporting those as simply absent conflated "we cannot do
    # this" with "we do this a different way".
    # Derived from the decoders themselves rather than a list kept beside them, so a field added
    # to a message shows up here without anyone remembering to mention it twice.
    streamed = set(decode_lyrebird_status(bytes(75)))
    streamed |= set(decode_lyrebird_config(bytes(53)))
    streamed |= set(decode_autosensing_status(bytes(30)))
    streamed |= {"detectedTargets"}
    streamed |= {
        "location",
        "altitude",
        "attitude",
        "speed",
        "heading",
        "batteryLevel",
        "satelliteCount",
        "flightMode",
        "isRecording",
        "zoomRatio",
        "homeLocation",
        "remainingFlightTime",
        "waypointReached",
        "waypointSeq",
        "gimbalAttitude",
        "gimbalJointAttitude",
        "lrfDistance",
        "distanceToHome",
    }
    streamed |= set(_TELEMETRY_HANDLERS)
    return {
        "available": True,
        "endpoints": sorted(set(_COMMAND_MAP) | set(_SPECIAL_SENDERS)),
        "streamed": sorted(k for k in streamed if not k.startswith("_")),
    }


def make_drone(name, ip=None):
    ip = ip or FALLBACK_IPS.get(name)
    return {
        "name": name,
        "streamName": name,
        "ip": ip,
        "discoveredIp": None,
        "status": "fallback" if ip else "missing",
        "telemetryConnected": False,
        "telemetryPackets": 0,
        "telemetryReconnects": 0,
        "lastTelemetryAt": None,
        "lastDiscoveryAt": None,
        "lastError": None,
        "lastTelemetry": None,
        "mediaMtx": None,
        "browserStats": None,
        "ignored": False,
    }


drones = {name: make_drone(name) for name in DRONE_NAMES}
for fallback_name in FALLBACK_IPS:
    drones.setdefault(fallback_name, make_drone(fallback_name))


def write_sse(payload):
    data = format_sse_message(payload["type"], payload["payload"])
    dead = []
    for handler in list(sse_clients):
        try:
            handler.wfile.write(data)
            handler.wfile.flush()
        except Exception:
            dead.append(handler)
    for handler in dead:
        if handler in sse_clients:
            sse_clients.remove(handler)


def public_state():
    with lock:
        return {
            "generatedAt": local_now_iso(),
            "mediamtxWebrtcUrl": MEDIAMTX_WEBRTC_URL,
            "logFile": str(EVENT_LOG),
            "dynamicDiscovery": not bool(DRONE_NAMES),
            "drones": [dict(drone) for drone in drones.values()],
        }


def emit_state():
    write_sse({"type": "state", "payload": public_state()})


def log_event(event_type, **payload):
    entry = build_event_entry(event_type, payload, local_now_iso)
    with EVENT_LOG.open("a", encoding="utf-8") as file:
        file.write(serialize_ndjson_entry(entry))
    write_sse({"type": event_type, "payload": entry})


def _existing_drone_name(found):
    if found["name"] in drones:
        return found["name"]
    for candidate, drone in drones.items():
        if drone.get("ip") == found["ip"] or drone.get("discoveredIp") == found["ip"]:
            return candidate
    return None


def _ignore_unconfigured_discovery(found):
    if not DRONE_NAMES or found["name"] in DRONE_NAMES:
        return False
    log_event("discovery_ignored", ip=found["ip"], name=found["name"], reason="not in DRONE_NAMES")
    return True


def _ensure_discovered_drone(name, found):
    if name in drones:
        return drones[name]
    drones[name] = make_drone(name, found["ip"])
    return drones[name]


def _apply_discovered_ip(name, found):
    drone = _ensure_discovered_drone(name, found)
    old_ip = drone.get("ip")
    drone["discoveredIp"] = found["ip"]
    drone["ip"] = found["ip"]
    if not drone.get("ignored"):
        drone["status"] = "discovered"
    drone["lastDiscoveryAt"] = local_now_iso()
    drone["lastError"] = None
    return old_ip


def upsert_discovered(found):
    with lock:
        name = _existing_drone_name(found)
        if name is None:
            if _ignore_unconfigured_discovery(found):
                return
            name = found["name"]
        old_ip = _apply_discovered_ip(name, found)
    if old_ip != found["ip"]:
        log_event(
            "drone_discovered",
            drone=name,
            reportedName=found["name"],
            ip=found["ip"],
            previousIp=old_ip,
        )
        stop_telemetry(name)
    connect_telemetry(name)
    emit_state()


def _known_drone_addresses():
    """Addresses worth probing directly: configured fallbacks and anything seen before."""
    addresses = set(FALLBACK_IPS.values())
    with lock:
        for drone in drones.values():
            if drone.get("ip"):
                addresses.add(drone["ip"])
    return {address for address in addresses if address}


def discover_now():
    log_event("discovery_started", drones=DRONE_NAMES or "any")

    # One shared sweep (lyrebird_groundstation.discovery): broadcast to every interface,
    # direct probes of known addresses, and the multicast group. Broadcast is not reliable on a
    # real network -- access points drop it, and a phone's Wi-Fi power-save can too -- which is
    # why anything previously seen or configured is probed directly as well. Imported lazily so
    # a webapp image built without the shared package still serves the rest of the dashboard.
    try:
        from lyrebird_groundstation.discovery import discover_all

        found = discover_all(
            timeout=1.5,
            verbose=False,
            probe_known=_known_drone_addresses(),
            per_interface_broadcast=True,
            multicast=True,
        )
    except Exception as exc:
        log_event("discovery_error", transport="all", error=str(exc))
        found = []

    for drone in found:
        upsert_discovered({"name": drone.name, "ip": drone.ip_address})

    for name in list(drones.keys()):
        connect_telemetry(name)
    emit_state()


def stop_telemetry(name):
    event = telemetry_stop_events.pop(name, None)
    if event:
        event.set()
    telemetry_threads.pop(name, None)


def connect_telemetry(name):
    with lock:
        drone = drones.get(name)
        if not drone or drone.get("ignored") or not drone.get("ip") or name in telemetry_threads:
            return
        stop_event = threading.Event()
        telemetry_stop_events[name] = stop_event
    thread = threading.Thread(target=telemetry_loop, args=(name, stop_event), daemon=True)
    telemetry_threads[name] = thread
    thread.start()


def _mark_telemetry_connected(name, ip):
    with lock:
        drone = drones[name]
        drone["telemetryConnected"] = True
        drone["status"] = "telemetry_connected"
        drone["lastError"] = None
    log_event("telemetry_connected", drone=name, ip=ip, port=TELEMETRY_PORT)
    emit_state()


def configure_mediamtx_path(drone_name, mode, source_url=None):
    try:
        normalized_mode = (mode or "").lower()
        if normalized_mode == "rtsp" and source_url:
            body = json.dumps({"source": source_url}).encode("utf-8")
        elif normalized_mode in {"rtmp", "webrtc"}:
            body = json.dumps({"source": "publisher"}).encode("utf-8")
        else:
            # Other modes are not relayed through MediaMTX.
            return

        # Try to patch first
        req = urllib.request.Request(
            f"{MEDIAMTX_API_URL}/v3/config/paths/patch/{drone_name}",
            data=body,
            method="PATCH",
            headers={"Content-Type": "application/json"},
        )
        try:
            # The PATCH result is not inspected; a 404 below is what tells us the path is new.
            _open_url(req, 2).close()
        except urllib.error.HTTPError as e:
            if e.code == 404:  # Doesn't exist yet, try to add
                req = urllib.request.Request(
                    f"{MEDIAMTX_API_URL}/v3/config/paths/add/{drone_name}",
                    data=body,
                    method="POST",
                    headers={"Content-Type": "application/json"},
                )
                # Fire and forget: the endpoint's status is not consulted, only that the
                # request was delivered without raising.
                _open_url(req, 2).close()
            else:
                raise
    except Exception as exc:
        log_event("mediamtx_config_error", drone=drone_name, error=str(exc))


def _rtsp_source_url(name, streaming):
    """Work out the RTSP URL to pull a drone's stream from.

    Prefers the consumptionPath the drone reports, normalising the two shapes DJI's own RTSP
    server produces, and falls back to building the URL from the drone's IP and credentials.
    """
    source_url = streaming.get("consumptionPath")
    if isinstance(source_url, str):
        source_url = source_url.strip()
        if source_url.endswith("/live"):
            source_url = f"{source_url}/livestream"
        if source_url.endswith("/live/livestream"):
            source_url = source_url[: -len("/live/livestream")] + "/streaming/live/1"
    if source_url and source_url.startswith("rtsp://"):
        return source_url

    ip = drones[name].get("ip")
    port = streaming.get("rtspPort", 8554)
    user = streaming.get("rtspUser", "")
    pwd = streaming.get("rtspPwd", "")
    credentials = f"{user}:{pwd}@" if user and pwd else ""
    return f"rtsp://{credentials}{ip}:{port}/streaming/live/1"


def _apply_streaming_config(name, streaming):
    """Point MediaMTX at whatever the drone says it is publishing.

    Only calls the MediaMTX API when the desired state actually changed — telemetry arrives
    several times a second and reconfiguring the path on every packet would be pointless churn.
    """
    if not streaming:
        return
    mode = (streaming.get("mode") or "").lower()
    if mode == "rtsp":
        source_url = _rtsp_source_url(name, streaming)
        desired_state = f"rtsp:{source_url}"
        configure_args = ("rtsp", source_url)
    elif mode in {"rtmp", "webrtc"}:
        desired_state = f"{mode}:publisher"
        configure_args = (mode,)
    else:
        return

    if drones[name].get("last_applied_stream_source") == desired_state:
        return
    drones[name]["last_applied_stream_source"] = desired_state
    configure_mediamtx_path(name, *configure_args)


def _handle_telemetry_line(name, line, last_sample_log):
    with lock:
        drone = drones[name]
        drone["telemetryPackets"] += 1
        drone["lastTelemetryAt"] = local_now_iso()
        packet_count = drone["telemetryPackets"]
    try:
        telemetry = json.loads(line)
    except json.JSONDecodeError as exc:
        log_event("telemetry_parse_error", drone=name, error=str(exc), sample=line[:200])
        return last_sample_log

    with lock:
        drones[name]["lastTelemetry"] = telemetry

    _apply_streaming_config(name, telemetry.get("streaming"))

    now = time.time()
    if now - last_sample_log <= 1:
        return last_sample_log

    log_event(
        "telemetry_sample",
        drone=name,
        packets=packet_count,
        batteryLevel=telemetry.get("batteryLevel"),
        flightMode=telemetry.get("flightMode"),
    )
    return now


def _read_telemetry_socket(name, stop_event, sock):
    buffer = ""
    last_sample_log = 0
    last_rx = time.time()
    while not stop_event.is_set():
        try:
            chunk = sock.recv(8192)
        except TimeoutError:
            if time.time() - last_rx > TELEMETRY_IDLE_TIMEOUT_S:
                raise TimeoutError(f"no telemetry for {TELEMETRY_IDLE_TIMEOUT_S:.1f}s") from None
            continue
        if not chunk:
            break
        last_rx = time.time()
        buffer += chunk.decode("utf-8", errors="ignore")
        while "\n" in buffer:
            line, buffer = buffer.split("\n", 1)
            if line.strip():
                last_sample_log = _handle_telemetry_line(name, line.strip(), last_sample_log)


def _record_telemetry_error(name, ip, error):
    with lock:
        drones[name]["lastError"] = str(error)
    log_event("telemetry_error", drone=name, ip=ip, error=str(error))


def _mark_telemetry_disconnected(name, ip):
    with lock:
        drone = drones[name]
        drone["telemetryConnected"] = False
        drone["telemetryReconnects"] += 1
        if drone.get("ignored"):
            drone["status"] = "ignored"
        elif drone["status"] != "missing":
            drone["status"] = "telemetry_disconnected"
        reconnects = drone["telemetryReconnects"]
    log_event("telemetry_disconnected", drone=name, ip=ip, reconnects=reconnects)
    emit_state()


def telemetry_loop(name, stop_event):
    while not stop_event.is_set():
        with lock:
            drone = drones[name]
            ip = drone.get("ip")
        try:
            with socket.create_connection((ip, TELEMETRY_PORT), timeout=5) as sock:
                sock.settimeout(2)
                _mark_telemetry_connected(name, ip)
                _read_telemetry_socket(name, stop_event, sock)
        except Exception as exc:
            _record_telemetry_error(name, ip, exc)
        finally:
            _mark_telemetry_disconnected(name, ip)
        stop_event.wait(2)
    with lock:
        telemetry_threads.pop(name, None)


def poll_mediamtx_loop():
    while True:
        try:
            with _open_url(f"{MEDIAMTX_API_URL}/v3/paths/list", 2) as response:
                body = json.loads(response.read().decode("utf-8"))
            items = body.get("items") or []
            with lock:
                for drone in drones.values():
                    drone["mediaMtx"] = next(
                        (item for item in items if item.get("name") == drone["streamName"]), None
                    )
            log_event(
                "mediamtx_paths",
                paths=[
                    {
                        "name": item.get("name"),
                        "ready": item.get("ready"),
                        "readers": len(item.get("readers") or []),
                    }
                    for item in items
                ],
            )
            emit_state()
        except Exception as exc:
            log_event("mediamtx_api_error", error=str(exc))
        time.sleep(2)


def set_drone_ignored(name, ignored):
    with lock:
        drone = drones.get(name)
        if not drone:
            return False
        drone["ignored"] = ignored
        if ignored:
            drone["status"] = "ignored"
        elif drone.get("ip"):
            drone["status"] = "discovered"
    if ignored:
        stop_telemetry(name)
        log_event("drone_ignored", drone=name)
    else:
        log_event("drone_unignored", drone=name)
        connect_telemetry(name)
    emit_state()
    return True


class Handler(SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=str(PUBLIC_DIR), **kwargs)

    def log_message(self, fmt, *args):
        return

    def end_headers(self):
        # The dashboard is rebuilt frequently; never let the browser serve stale
        # JS/CSS/HTML after a redeploy.
        self.send_header("Cache-Control", "no-store")
        super().end_headers()

    def send_json(self, status, payload):
        body = json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("content-type", "application/json")
        self.send_header("content-length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def read_json_body(self):
        length = int(self.headers.get("content-length", "0"))
        return json.loads(self.rfile.read(length).decode("utf-8") or "{}")

    def handle_ignore_post(self, path):
        parts = [part for part in path.split("/") if part]
        if len(parts) != 4:
            self.send_error(404)
            return
        name = unquote(parts[2])
        body = self.read_json_body()
        if set_drone_ignored(name, bool(body.get("ignored"))):
            self.send_json(200, public_state())
        else:
            self.send_json(404, {"error": "Drone not found"})

    def handle_streaming_mode_post(self, path):
        parts = [part for part in path.split("/") if part]
        if len(parts) != 5:  # /api/drones/<name>/streaming/mode
            self.send_error(404)
            return
        name = unquote(parts[2])
        body = self.read_json_body()
        mode = body.get("mode")
        if not mode:
            self.send_json(400, {"error": "Missing mode parameter"})
            return

        with lock:
            drone = drones.get(name)
            if not drone:
                self.send_json(404, {"error": "Drone not found"})
                return
            ip = drone.get("ip")

        if not ip:
            self.send_json(400, {"error": "Drone IP not available (not discovered yet)"})
            return

        try:
            req = urllib.request.Request(
                f"http://{ip}:8080/send/streaming/mode",
                data=mode.encode("utf-8"),
                method="POST",
                headers={"Content-Type": "text/plain"},
            )
            with _open_url(req, 3) as resp:
                reply = resp.read().decode("utf-8")

            with lock:
                if not drones[name].get("lastTelemetry"):
                    drones[name]["lastTelemetry"] = {}
                if not drones[name]["lastTelemetry"].get("streaming"):
                    drones[name]["lastTelemetry"]["streaming"] = {}
                drones[name]["lastTelemetry"]["streaming"]["mode"] = mode.lower()
            emit_state()

            self.send_json(200, {"ok": True, "message": reply})
        except Exception as exc:
            self.send_json(502, {"error": f"Failed to send command to phone: {exc!s}"})

    def _drone_ip(self, name):
        with lock:
            drone = drones.get(name)
            return drone.get("ip") if drone else None

    def handle_config_get(self, path):
        parts = [part for part in path.split("/") if part]
        if len(parts) != 4:  # /api/drones/<name>/config
            self.send_error(404)
            return
        name = unquote(parts[2])
        ip = self._drone_ip(name)
        if not ip:
            self.send_json(400, {"error": "Drone IP not available (not discovered yet)"})
            return
        try:
            req = urllib.request.Request(f"http://{ip}:{DRONE_HTTP_PORT}/config")
            with _open_url(req, 3) as resp:
                raw = resp.read().decode("utf-8")
            try:
                config = json.loads(raw)
            except json.JSONDecodeError:
                config = {"raw": raw}
            self.send_json(200, {"ok": True, "config": config})
        except Exception as exc:
            self.send_json(502, {"error": f"Failed to read config from phone: {exc!s}"})

    def handle_settings_get(self, path):
        parts = [part for part in path.split("/") if part]
        if len(parts) != 4:  # /api/drones/<name>/settings
            self.send_error(404)
            return
        name = unquote(parts[2])
        ip = self._drone_ip(name)
        if not ip:
            self.send_json(400, {"error": "Drone IP not available (not discovered yet)"})
            return
        try:
            req = urllib.request.Request(f"http://{ip}:{DRONE_HTTP_PORT}/config/settings")
            with _open_url(req, 3) as resp:
                raw = resp.read().decode("utf-8")
            try:
                settings = json.loads(raw)
            except json.JSONDecodeError:
                settings = {"raw": raw}
            self.send_json(200, {"ok": True, "settings": settings})
        except Exception as exc:
            self.send_json(502, {"error": f"Failed to read settings from phone: {exc!s}"})

    def handle_settings_post(self, path):
        parts = [part for part in path.split("/") if part]
        if len(parts) != 5:  # /api/drones/<name>/settings/<key>
            self.send_error(404)
            return
        name = unquote(parts[2])
        key = parts[4]
        route = SETTING_ROUTES.get(key)
        if not route:
            self.send_json(400, {"error": f"Unknown setting key: {key}"})
            return
        phone_path, coerce = route
        body = self.read_json_body()
        value = body.get("value")
        if value is None:
            self.send_json(400, {"error": "Missing value parameter"})
            return
        try:
            normalized = _coerce_setting_value(key, coerce, value)
        except ValueError as exc:
            self.send_json(400, {"error": str(exc)})
            return
        payload = str(normalized).lower() if isinstance(normalized, bool) else str(normalized)
        ip = self._drone_ip(name)
        if not ip:
            self.send_json(400, {"error": "Drone IP not available (not discovered yet)"})
            return
        try:
            req = urllib.request.Request(
                f"http://{ip}:{DRONE_HTTP_PORT}{phone_path}",
                data=payload.encode("utf-8"),
                method="POST",
                headers={"Content-Type": "text/plain"},
            )
            with _open_url(req, 3) as resp:
                reply = resp.read().decode("utf-8")
            self.send_json(200, {"ok": True, "key": key, "value": payload, "message": reply})
        except Exception as exc:
            self.send_json(502, {"error": f"Failed to send command to phone: {exc!s}"})

    def handle_rc_action_post(self, path):
        """POST /api/drones/<name>/rcPairing/start|stop -> phone action endpoint."""
        self._forward_phone_action(path, RC_ACTIONS)

    def handle_capture_action_post(self, path):
        """POST /api/drones/<name>/capture/photo|temperature|thermal -> phone capture endpoint."""
        # Photo capture trips a shutter and waits for the file to land; the phone's own capture
        # machinery budgets roughly 40s for that, so the proxy must not give up at the 3s default.
        self._forward_phone_action(path, CAPTURE_ACTIONS, timeout=50.0)

    def _forward_phone_action(self, path, actions, timeout=3.0):
        """Forward a no-body POST to one of the phone's plain action endpoints."""
        action = None
        name = None
        for suffix, phone_path in actions.items():
            if path.endswith(suffix):
                action = phone_path
                name = path[: -len(suffix)].rstrip("/").rsplit("/", 1)[-1]
                break
        if action is None or not name:
            self.send_error(404)
            return
        ip = self._drone_ip(name)
        if not ip:
            self.send_json(400, {"error": "Drone IP not available (not discovered yet)"})
            return
        try:
            req = urllib.request.Request(
                f"http://{ip}:{DRONE_HTTP_PORT}{action}",
                data=b"",
                method="POST",
                headers={"Content-Type": "text/plain"},
            )
            with _open_url(req, timeout) as resp:
                reply = resp.read().decode("utf-8")
            self.send_json(200, {"ok": True, "action": action, "message": reply})
        except Exception as exc:
            self.send_json(502, {"error": f"Failed to send command to phone: {exc!s}"})

    def handle_client_stats_post(self):
        try:
            body = self.read_json_body()
            drone_name = body.get("drone")
            with lock:
                if drone_name in drones:
                    drones[drone_name]["browserStats"] = body
            log_event("browser_stats", **body)
            self.send_response(204)
            self.end_headers()
        except Exception as exc:
            self.send_json(400, {"error": str(exc)})

    def handle_ros_status_post(self):
        """Store the latest ros-monitor report (posted by the container)."""
        try:
            body = self.read_json_body()
            with lock:
                ROS_STATUS.clear()
                ROS_STATUS.update(body)
            self.send_response(204)
            self.end_headers()
        except Exception as exc:
            self.send_json(400, {"error": str(exc)})

    def do_GET(self):
        path = urlparse(self.path).path
        if self._serve_api_get(path):
            return
        super().do_GET()

    def _serve_api_get(self, path):
        """The JSON routes. True when one of them answered.

        A table rather than a branch chain, so adding a route is an entry rather than another
        limb. The event stream is not in it because it holds its connection open for the life of
        the page instead of returning a body.
        """
        if path == "/api/events":
            self._stream_events()
            return True

        exact = {
            "/api/drones": lambda: self.send_json(200, public_state()),
            "/api/logs": lambda: self.send_json(200, {"eventLog": str(EVENT_LOG)}),
            "/api/ros-status": lambda: self.send_json(200, ros_status_snapshot()),
            "/api/mavlink-coverage": lambda: self.send_json(200, mavlink_coverage()),
        }
        handler = exact.get(path)
        if handler is not None:
            handler()
            return True

        suffixed = {
            "/mavlink": self.handle_mavlink_get,
            "/config": self.handle_config_get,
            "/settings": self.handle_settings_get,
        }
        if path.startswith("/api/drones/"):
            for suffix, drone_handler in suffixed.items():
                if path.endswith(suffix):
                    drone_handler(path)
                    return True
        return False

    def _stream_events(self):
        """Server-sent events, held open until the page goes away."""
        self.send_response(200)
        self.send_header("content-type", "text/event-stream")
        self.send_header("cache-control", "no-cache")
        self.send_header("connection", "keep-alive")
        self.end_headers()
        sse_clients.append(self)
        try:
            self.wfile.write(format_sse_message("state", public_state()))
            self.wfile.flush()
            while self in sse_clients:
                time.sleep(1)
        finally:
            if self in sse_clients:
                sse_clients.remove(self)

    def handle_mavlink_get(self, path):
        """Both wires read against one drone, field by field.

        Deliberately not cached: the point of this view is what the two transports say *right
        now*, and a stale answer would hide exactly the disagreements it exists to show.
        """
        if mavlink_comparisons is None:
            self.send_json(501, {"error": MAVLINK_UNAVAILABLE})
            return
        name = unquote(path.split("/")[3])
        drone = drones.get(name)
        if drone is None or not drone.get("ip"):
            self.send_json(404, {"error": f"No address known for {name}"})
            return
        try:
            self.send_json(200, mavlink_comparisons.snapshot(name, drone["ip"]))
        except OSError as error:
            # Almost always the MAVLink port already being held by another ground station.
            self.send_json(503, {"error": str(error)})

    def handle_discover_post(self):
        discover_now()
        self.send_json(202, {"ok": True})

    def _drone_post_handler(self, path):
        """Pick the handler for a /api/drones/... POST, or None if nothing matches.

        Order matters and mirrors the original chain: the specific suffixes are checked before
        the general /settings/ case, which would otherwise swallow them.
        """
        if not path.startswith("/api/drones/"):
            return None
        if path.endswith("/ignore"):
            return self.handle_ignore_post
        if path.endswith("/streaming/mode"):
            return self.handle_streaming_mode_post
        if any(path.endswith(action) for action in RC_ACTIONS):
            return self.handle_rc_action_post
        if any(path.endswith(action) for action in CAPTURE_ACTIONS):
            return self.handle_capture_action_post
        if "/settings/" in path:
            return self.handle_settings_post
        return None

    def do_POST(self):
        path = urlparse(self.path).path

        exact_routes = {
            "/api/discover": self.handle_discover_post,
            "/api/ros-status": self.handle_ros_status_post,
            "/api/client-stats": self.handle_client_stats_post,
        }
        handler = exact_routes.get(path)
        if handler is not None:
            handler()
            return

        drone_handler = self._drone_post_handler(path)
        if drone_handler is not None:
            drone_handler(path)
            return

        self.send_error(404)


def discovery_loop():
    discover_now()
    while True:
        time.sleep(DISCOVERY_INTERVAL_MS / 1000)
        discover_now()


if __name__ == "__main__":
    log_event("video_grid_started", port=PORT, logFile=str(EVENT_LOG), drones=DRONE_NAMES or "any")
    threading.Thread(target=discovery_loop, daemon=True).start()
    threading.Thread(target=poll_mediamtx_loop, daemon=True).start()
    server = ThreadingHTTPServer(("0.0.0.0", PORT), Handler)  # nosec B104
    print(f"Lyrebird video grid listening on http://localhost:{PORT}")
    print(f"Logging diagnostics to {EVENT_LOG}")
    server.serve_forever()
