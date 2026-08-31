#!/usr/bin/env python3
"""Lyrebird ROS topic monitor.

Watches the live ROS graph for `lyrebird_controller_*` nodes (one per drone,
namespaced under its name — see
GroundStation/ROS/lyrebird_bringup/launch/auto_discovery_native.launch.py),
subscribes to each drone's topics under its own namespace, tracks per-topic
publish metrics (rate, last value, freshness) per drone, probes each drone's
phone HTTP surface, and reports the result to the Lyrebird webapp via
POST /api/ros-status so the dashboard can show per-drone ROS bridge health.
"""

import json
import os
import time
import urllib.request
from datetime import datetime
from urllib.parse import urlparse

import rclpy
from rclpy.node import Node

from lyrebird_controller import topics as lyrebird_topics

WEBAPP_URL = os.environ.get("WEBAPP_URL", "http://127.0.0.1:8090")

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


REPORT_INTERVAL = float(os.environ.get("ROS_REPORT_INTERVAL", "3"))
SYNC_INTERVAL = float(os.environ.get("ROS_SYNC_INTERVAL", "2"))
# How often to retry the phone HTTP probe for a drone that isn't reachable yet
# (e.g. its ROS node appeared before the webapp resolved its IP). Reachable
# drones are never re-probed.
PHONE_RETRY_INTERVAL = 10.0

# Topic name -> message type, sourced from lyrebird_controller's own registry
# (the single source of truth DjiNode itself publishes/subscribes from) rather
# than a hand-mirrored copy that can drift out of sync with controller.py.
# Subscribed per-drone as f"/{namespace}/{topic}"; topic already carries its
# fmu/in/ or fmu/out/ prefix from the registry, so the app.js dashboard can
# tell commands and telemetry apart by that prefix alone.
TOPICS = {
    **{
        lyrebird_topics.topic_in(name): msg_type
        for name, (msg_type, _qos) in lyrebird_topics.IN_TOPICS.items()
    },
    **{
        lyrebird_topics.topic_out(name): msg_type
        for name, (msg_type, _qos) in lyrebird_topics.OUT_TOPICS.items()
    },
}


def _format_value(msg) -> str:
    if hasattr(msg, "data"):
        return str(msg.data)
    if hasattr(msg, "latitude"):
        return f"lat={msg.latitude:.6f} lon={msg.longitude:.6f}"
    if hasattr(msg, "x"):
        return f"({msg.x}, {msg.y}, {msg.z})"
    return str(msg)


def _fetch_drone_ips():
    """Best-effort name -> ip lookup from the webapp's own discovery state, used
    only to probe each drone's phone HTTP surface (not required for ROS)."""
    try:
        with _open_url(f"{WEBAPP_URL}/api/drones", 3) as resp:
            state = json.loads(resp.read().decode("utf-8"))
        return {
            drone["name"]: drone.get("ip") for drone in state.get("drones", []) if drone.get("ip")
        }
    except Exception:
        return {}


def _probe_phone(ip):
    if not ip:
        return False, "no ip known for this drone yet"
    try:
        with _open_url(f"http://{ip}:8080/config", 3) as resp:
            return resp.status == 200, ""
    except Exception as exc:
        return False, str(exc)[:120]


class RosMonitor(Node):
    def __init__(self):
        super().__init__("ros_monitor")
        # namespace -> {topic: {count, last_value, last_time, type}}
        self.drone_stats = {}
        # namespace -> {topic: Subscription}
        self.drone_subs = {}
        # namespace -> {"reachable": bool, "error": str}
        self.drone_phone = {}
        self.last_reset = time.time()
        self._sync_drones()
        self.create_timer(SYNC_INTERVAL, self._sync_drones)
        self.create_timer(REPORT_INTERVAL, self._report)

    def _sync_drones(self):
        try:
            nodes = self.get_node_names_and_namespaces()
        except Exception:
            return
        active = {
            ns.strip("/")
            for name, ns in nodes
            if name.startswith("lyrebird_controller") and ns.strip("/")
        }
        for namespace in active - self.drone_stats.keys():
            self._attach_drone(namespace)
        for namespace in self.drone_stats.keys() - active:
            self._detach_drone(namespace)
        self._refresh_phone_reachability(active)

    def _attach_drone(self, namespace):
        self.get_logger().info(f"Attaching drone namespace: {namespace}")
        stats = {}
        subs = {}
        for topic, msg_type in TOPICS.items():
            stats[topic] = {
                "count": 0,
                "last_value": None,
                "last_time": None,
                "type": msg_type.__name__,
            }
            subs[topic] = self.create_subscription(
                msg_type, f"/{namespace}/{topic}", self._make_callback(namespace, topic), 10
            )
        self.drone_stats[namespace] = stats
        self.drone_subs[namespace] = subs
        self.drone_phone[namespace] = {
            "reachable": False,
            "error": "not probed yet",
            "checked_at": 0.0,
        }

    def _detach_drone(self, namespace):
        self.get_logger().info(f"Detaching drone namespace: {namespace}")
        for sub in self.drone_subs.pop(namespace, {}).values():
            self.destroy_subscription(sub)
        self.drone_stats.pop(namespace, None)
        self.drone_phone.pop(namespace, None)

    def _refresh_phone_reachability(self, namespaces):
        now = time.time()
        due = [
            ns
            for ns in namespaces
            if not self.drone_phone.get(ns, {}).get("reachable")
            and now - self.drone_phone.get(ns, {}).get("checked_at", 0.0) >= PHONE_RETRY_INTERVAL
        ]
        if not due:
            return
        ip_by_name = _fetch_drone_ips()
        for namespace in due:
            reachable, error = _probe_phone(ip_by_name.get(namespace))
            self.drone_phone[namespace] = {
                "reachable": reachable,
                "error": error,
                "checked_at": now,
            }

    def _make_callback(self, namespace, topic):
        def callback(msg):
            entry = self.drone_stats.get(namespace, {}).get(topic)
            if entry is None:
                return
            entry["count"] += 1
            entry["last_value"] = _format_value(msg)[:120]
            entry["last_time"] = time.time()

        return callback

    def _report(self):
        now = time.time()
        elapsed = max(now - self.last_reset, 1e-6)
        self.last_reset = now

        drones = {}
        for namespace, stats in self.drone_stats.items():
            topics = {}
            for topic, entry in stats.items():
                rate = entry["count"] / elapsed if entry["count"] else 0.0
                topics[topic] = {
                    "type": entry["type"],
                    "rate_hz": round(rate, 2),
                    "last_value": entry["last_value"],
                    "seconds_ago": (
                        round(now - entry["last_time"], 1) if entry["last_time"] else None
                    ),
                    "seen": entry["count"] > 0,
                }
                entry["count"] = 0
            phone = self.drone_phone.get(namespace, {"reachable": False, "error": ""})
            drones[namespace] = {
                "controllerAlive": True,
                "phoneReachable": phone["reachable"],
                "phoneError": phone["error"],
                "topicCount": len(topics),
                "topics": topics,
            }

        payload = {
            "generatedAt": datetime.now().astimezone().isoformat(),
            "droneCount": len(drones),
            "drones": drones,
        }
        try:
            req = urllib.request.Request(
                f"{WEBAPP_URL}/api/ros-status",
                data=json.dumps(payload).encode("utf-8"),
                headers={"Content-Type": "application/json"},
                method="POST",
            )
            with _open_url(req, 3):
                pass
        except Exception:
            pass


def main(args=None):
    rclpy.init(args=args)
    node = RosMonitor()
    try:
        rclpy.spin(node)
    finally:
        node.destroy_node()
        rclpy.shutdown()


if __name__ == "__main__":
    main()
