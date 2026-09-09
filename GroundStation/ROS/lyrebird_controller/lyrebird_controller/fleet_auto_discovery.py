"""
Authors: Alejandro Jarabo-Peñas
Project: Lyrebird

Generic multi-drone auto-discovery for lyrebird_controller.

lyrebird_controller's usual deployment (per DjiNode's own docstring) is one drone per OS
process, launched via a fixed set of `ros2 launch` Node actions -- one per known drone. That
does not work when the fleet size is not known ahead of time: you cannot declare N static
Node actions for a number discovered at runtime. FleetAutoDiscoveryManager is the standard
ROS 2 answer to that -- one node that, on a timer, discovers drones and spins up a DjiNode for
each newly-seen one inside a single shared MultiThreadedExecutor.

Packing several DjiNodes into one process means they share one network namespace. The fleet uses
one shared MAVLink UDP listener and demultiplexes aircraft by MAVLink system id; the discovered IP
and name remain the provisional route and ROS identity until the first autopilot heartbeat binds
the system id.

On connecting, each drone is also pushed the fleet_settings/drone_settings loaded from a YAML
file (config/fleet_settings.yaml by default -- see that file for the full list of keys and
valid values), the same settings the app's own cockpit settings menu can change, so a fleet can
be configured uniformly without opening the app on each aircraft. That file also selects the
transport (http/mavlink/both) every discovered drone is commanded over, can optionally
auto-assign each drone its own rthAltitude spaced out across a configured range instead of one
fixed value for the whole fleet, and centralizes the discovery timing and MAVLink ports this
manager would otherwise only take from LB_MAVLINK_PORT/LB_MAVLINK_PEER_PORT environment
variables, so the whole fleet's behaviour lives in one file instead of being split across env
vars and launch-file arguments.

This also replaces lyrebird_bringup's older auto_discovery_native.launch.py, which took the
one-OS-process-per-drone route instead (a `TimerAction` rescanning and spawning a fresh
`ros2 launch` Node action per newly-seen drone) -- functionally similar, but unable to maintain
one shared in-process router or push any settings.

Beyond that, this manager has no opinion on what else a caller wants to do with a
newly-discovered drone (e.g. announcing it to some external fleet registry). Subclass it and
override on_drone_discovered() for that.
"""

import re
from pathlib import Path

import yaml
from ament_index_python.packages import get_package_share_directory
from lyrebird_groundstation.transport import (
    MavlinkRouter,
    Transport,
    mavlink_peer_port_from_env,
    mavlink_port_from_env,
)
from rclpy.node import Node

from lyrebird_controller.controller import DjiNode
from lyrebird_controller.dji_interface import discover_all_drones

DEFAULT_DISCOVERY_PERIOD_SEC = 30.0
DEFAULT_DISCOVERY_TIMEOUT_SEC = 5.0


def _default_fleet_settings_file():
    """This package's bundled config/fleet_settings.yaml, or "" outside an installed workspace
    (e.g. running the module directly from a source checkout)."""
    try:
        share_dir = get_package_share_directory("lyrebird_controller")
    except Exception:
        return ""
    return str(Path(share_dir) / "config" / "fleet_settings.yaml")


def _clean_namespace(name, index):
    namespace = re.sub(r"[^a-zA-Z0-9_]", "_", name or "")
    if not namespace or namespace.upper() == "UNKNOWN":
        namespace = f"drone_{index}"
    return namespace


def _setting_result_is_success(result):
    text = str(result or "").strip().lower()
    failure_prefixes = ("rejected:", "invalid", "failed:", "error:", "refused:")
    return bool(text) and text != "null" and not text.startswith(failure_prefixes)


class FleetAutoDiscoveryManager(Node):
    """Discovers Lyrebird drones on a timer and gives each a logical DjiNode.

    Run this node inside the same MultiThreadedExecutor passed to its constructor -- newly
    discovered DjiNodes are added directly to that executor as they connect.

    fleet_settings, drone_settings, transport, rth_altitude_range, discovery_period_sec,
    discovery_timeout_sec, mavlink_port and mavlink_peer_port are all loaded from the YAML
    file named by the fleet_settings_file ROS parameter (defaulting to this package's
    config/fleet_settings.yaml) -- see that file for the full list of keys, their valid values,
    and the drone_settings/rth_altitude_range shapes. Any of these can still be overridden the
    normal ROS way (a launch file's own `parameters=[...]`, or `--ros-args -p`), which always
    wins over the file. Passing fleet_settings and/or drone_settings dicts directly to the
    constructor instead skips the file entirely (transport and rth_altitude_range are then left
    unset); it exists for tests and programmatic callers, not as the normal way to configure
    this node.
    """

    def __init__(
        self,
        executor,
        node_name="fleet_auto_discovery_manager",
        fleet_settings=None,
        drone_settings=None,
    ):
        super().__init__(node_name)
        self._executor = executor
        self._drones = {}  # ip or name -> namespace, to skip drones already under management
        self._nodes = {}  # namespace -> DjiNode
        # RTH altitude slots are independent of MAVLink networking. The router below owns one
        # shared listener for the whole fleet, so there is no per-aircraft port bookkeeping.
        self._rth_slots = {}

        if fleet_settings is not None or drone_settings is not None:
            config = {}
            self._fleet_settings = fleet_settings or {}
            self._drone_settings = drone_settings or {}
        else:
            self.declare_parameter("fleet_settings_file", _default_fleet_settings_file())
            settings_file = (
                self.get_parameter("fleet_settings_file").get_parameter_value().string_value
            )
            config = self._load_config_file(settings_file)
            self._fleet_settings = config.get("fleet_settings") or {}
            self._drone_settings = config.get("drone_settings") or {}

        self._transport = config.get("transport")
        self._rth_altitude_range = config.get("rth_altitude_range")

        self.declare_parameter(
            "discovery_period_sec",
            config.get("discovery_period_sec", DEFAULT_DISCOVERY_PERIOD_SEC),
        )
        self.declare_parameter(
            "discovery_timeout_sec",
            config.get("discovery_timeout_sec", DEFAULT_DISCOVERY_TIMEOUT_SEC),
        )
        period = self.get_parameter("discovery_period_sec").value
        self._timeout = self.get_parameter("discovery_timeout_sec").value

        self.declare_parameter(
            "mavlink_port_base",
            config.get("mavlink_port", config.get("mavlink_port_base", mavlink_port_from_env())),
        )
        self.declare_parameter(
            "mavlink_peer_port", config.get("mavlink_peer_port", mavlink_peer_port_from_env())
        )
        self._mavlink_port_base = self.get_parameter("mavlink_port_base").value
        self._mavlink_peer_port = self.get_parameter("mavlink_peer_port").value
        configured_transport = self._transport or Transport.from_env()
        if isinstance(configured_transport, str):
            configured_transport = Transport(configured_transport)
        self._mavlink_router = (
            MavlinkRouter(
                port=self._mavlink_port_base,
                peer_port=self._mavlink_peer_port,
                logger=self.get_logger().warning,
                debug_logger=self.get_logger().debug,
            )
            if configured_transport.uses_mavlink
            else None
        )

        self.create_timer(period, self._discover)

        self.get_logger().info(f"Auto-discovery enabled; rescanning every {period:g} seconds")
        self._discover()

    def _load_config_file(self, path):
        if not path:
            return {}
        try:
            with open(path) as settings_file:
                return yaml.safe_load(settings_file) or {}
        except (OSError, yaml.YAMLError) as error:
            self.get_logger().warning(f"Could not load fleet settings file {path!r}: {error}")
            return {}

    @property
    def nodes(self):
        """namespace -> DjiNode for every drone currently under management."""
        return dict(self._nodes)

    def on_drone_discovered(self, namespace, node, ip, name):
        """Hook for subclasses: called once a newly discovered drone's DjiNode is connected and
        added to the executor. Override to add fleet-specific bookkeeping (e.g. announcing the
        drone to an external registry). No-op by default."""

    def _discover(self):
        try:
            drones = discover_all_drones(timeout=self._timeout, verbose=False)
        except Exception as error:
            self.get_logger().warning(f"Drone discovery failed: {error}")
            return

        for ip, name in drones:
            known_name = name and name.upper() != "UNKNOWN"
            if ip in self._drones or (known_name and name in self._drones):
                continue

            namespace = _clean_namespace(name, len(self._nodes) + 1)
            if namespace in self._nodes:
                namespace = f"{namespace}_{len(self._nodes) + 1}"

            rth_slot = self._rth_slots.setdefault(namespace, len(self._rth_slots))
            self.get_logger().info(
                f"Found new drone: {name} at {ip} -- registering it with the shared MAVLink "
                f"listener on port {self._mavlink_port_base}"
            )
            node = DjiNode(
                ip_rc=ip,
                # Matches auto_discovery_native.launch.py's naming (see ros_monitor.py), which
                # this manager replaces -- so tooling that watches for lyrebird_controller_*
                # nodes keeps working unchanged.
                node_name=f"lyrebird_controller_{namespace}",
                namespace=namespace,
                mavlink_port=self._mavlink_port_base,
                mavlink_peer_port=self._mavlink_peer_port,
                transport=self._transport,
                mavlink_router=self._mavlink_router,
                mavlink_vehicle_name=name,
            )
            if not node.connection_ready:
                node.destroy_node()
                self.get_logger().warning(f"Could not connect to discovered drone at {ip}")
                continue

            self._apply_settings(node, name, rth_slot)
            self._executor.add_node(node)
            self._nodes[namespace] = node
            self._drones[ip] = namespace
            if name:
                self._drones[name] = namespace
            self.on_drone_discovered(namespace, node, ip, name)

    def _apply_settings(self, node, name, rth_slot):
        """Push fleet_settings to a newly connected drone, with rth_altitude_range (if
        configured) filling in a per-drone rthAltitude before drone_settings[name] is applied
        on top -- see config/fleet_settings.yaml for both shapes."""
        settings = dict(self._fleet_settings)
        if self._rth_altitude_range is not None:
            settings["rthAltitude"] = self._auto_rth_altitude(rth_slot)
        drone_specific_settings = self._drone_settings.get(name, {})
        settings.update(drone_specific_settings)
        if "mavlinkSystemId" in drone_specific_settings:
            value = drone_specific_settings["mavlinkSystemId"]
            try:
                valid_manual_id = 1 <= int(value) <= 99 and int(value) == value
            except (TypeError, ValueError):
                valid_manual_id = False
            if not valid_manual_id:
                self.get_logger().warning(
                    f"Ignoring invalid mavlinkSystemId={value!r} for {name!r}; use 1..99"
                )
                settings.pop("mavlinkSystemId", None)
        for key, value in settings.items():
            result = node.dji_interface.requestSetSetting(key, value)
            if not _setting_result_is_success(result):
                self.get_logger().warning(
                    f"Failed to apply setting {key}={value} to {name!r}: response={result!r}"
                )

    def _auto_rth_altitude(self, slot):
        """Assign a fleet RTH altitude slot, capped for an oversized fleet."""
        min_alt = self._rth_altitude_range["min"]
        max_alt = self._rth_altitude_range["max"]
        step = self._rth_altitude_range.get("step", 5)
        return min(min_alt + slot * step, max_alt)

    def destroy_nodes(self):
        for node in self._nodes.values():
            node.destroy_node()
        self._nodes.clear()
        if self._mavlink_router is not None:
            self._mavlink_router.stop()


def main(args=None):
    import rclpy
    from rclpy.executors import MultiThreadedExecutor

    rclpy.init(args=args)
    executor = MultiThreadedExecutor()
    manager = FleetAutoDiscoveryManager(executor)
    executor.add_node(manager)
    try:
        executor.spin()
    finally:
        manager.destroy_nodes()
        manager.destroy_node()
        executor.shutdown()
        rclpy.shutdown()


if __name__ == "__main__":
    main()
