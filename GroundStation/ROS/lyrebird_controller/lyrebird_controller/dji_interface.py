"""
Lyrebird - DJI Interface Module

ROS lyrebird_controller compatibility wrapper around the shared GroundStation DJI client.

Authors: Edouard G.A. Rolland, Kilian Meier, Alejandro Jarabo-Peñas
Project: Lyrebird
Institution: University of Bristol, University of Southern Denmark (SDU)
License: MIT

For more information, visit: https://github.com/SDU-UAS-Center/lyrebird
"""

import time

from lyrebird_groundstation.discovery import discover_all
from lyrebird_groundstation.dji_client import *  # noqa: F403
from lyrebird_groundstation.dji_client import DJIInterface as _SharedDJIInterface
from lyrebird_groundstation.transport import Transport


def discover_all_drones(timeout=5.0, verbose=True):
    """Discover every Lyrebird drone; list of (ip, name) tuples.

    Delegates to the shared lyrebird_groundstation.discovery implementation;
    the broadcast is backed by a subnet scan when it finds nothing.
    """
    return [
        (drone.ip_address, drone.name) for drone in discover_all(timeout, verbose, scan_subnet=True)
    ]


def discover_drone(timeout=5.0, verbose=True):
    """Discover the first drone as a (ip, name) tuple for the DJI callback."""
    drones = discover_all(timeout, verbose, scan_subnet=True)
    if not drones:
        return None, None
    return drones[0].ip_address, drones[0].name


class DJIInterface(_SharedDJIInterface):
    """Backward-compatible ROS lyrebird_controller DJI client."""

    def __init__(self, IP_RC="", *, mavlink_port=None, mavlink_peer_port=None, transport=None):
        if isinstance(transport, str):
            transport = Transport(transport)
        super().__init__(
            IP_RC,
            discover_callback=discover_drone,
            mavlink_port=mavlink_port,
            mavlink_peer_port=mavlink_peer_port,
            transport=transport,
        )


if __name__ == "__main__":
    import sys

    IP_RC = "10.102.252.30"
    if len(sys.argv) > 1:
        IP_RC = sys.argv[1]

    print(f"Connecting to {IP_RC}...")
    dji = DJIInterface(IP_RC)
    print("Starting telemetry stream...")
    dji.startTelemetryStream()
    try:
        while True:
            telemetry = dji.getTelemetry()
            print(telemetry or "Waiting for telemetry data...")
            time.sleep(0.1)
    except KeyboardInterrupt:
        dji.stopTelemetryStream()
