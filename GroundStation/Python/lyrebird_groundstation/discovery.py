"""Lyrebird drone discovery — one implementation for every consumer.

The shared client, the ROS controller and the video dashboard all discover
drones the same way: a UDP broadcast plus direct probes, with an optional
multicast sweep. Keeping the socket logic in one place means a fix to the
probe lands everywhere at once instead of drifting into three copies.

The aircraft replies ``LYREBIRD_HERE:<ip>:<name>`` (see
``LyrebirdDiscoveryManager.kt`` on the phone); parsing is delegated to
``dji_helpers.parse_discovery_response``.
"""

from __future__ import annotations

import re
import socket
import subprocess
import time
from collections.abc import Iterable
from contextlib import suppress

from lyrebird_groundstation.dji_helpers import (
    DiscoveryResponse,
    candidate_subnet_ips,
    parse_discovery_response_tuple,
)

DISCOVERY_MSG = b"DISCOVER_LYREBIRD"
DISCOVERY_PORT = 30000
MULTICAST_GROUP = "239.255.42.99"
MULTICAST_PORT = 30001
BROADCAST_ADDR = "<broadcast>"
#: How long a single direct UDP probe waits for a reply.
SUBNET_PROBE_TIMEOUT_S = 0.1


def _new_socket(timeout: float) -> socket.socket:
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
    sock.settimeout(timeout)
    return sock


def _parse_response(data: bytes, addr: tuple[str, int]) -> tuple[str, str] | None:
    return parse_discovery_response_tuple(data, fallback_ip=addr[0])


def _remember(found: dict[str, str], discovery: tuple[str, str] | None, verbose: bool) -> None:
    if not discovery:
        return
    drone_ip, drone_name = discovery
    if drone_ip in found:
        return
    if verbose:
        print(f"Found Lyrebird drone at {drone_ip} (Name: {drone_name})")
    found[drone_ip] = drone_name


def _collect(sock: socket.socket, timeout: float, found: dict[str, str], verbose: bool) -> None:
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            data, addr = sock.recvfrom(1024)
        except TimeoutError:
            continue
        _remember(found, _parse_response(data, addr), verbose)


def _broadcast_once(
    sock: socket.socket,
    target: str,
    port: int,
    timeout: float,
    found: dict[str, str],
    verbose: bool,
) -> None:
    try:
        sock.sendto(DISCOVERY_MSG, (target, port))
    except OSError as error:
        if verbose:
            print(f"Discovery probe to {target}:{port} failed: {error}")
        return
    _collect(sock, timeout, found, verbose)


def _probe_single(ip: str, timeout: float) -> tuple[str, str] | None:
    """One direct UDP probe of a single address."""
    sock = None
    try:
        sock = _new_socket(timeout)
        sock.sendto(DISCOVERY_MSG, (ip, DISCOVERY_PORT))
        data, addr = sock.recvfrom(1024)
        return _parse_response(data, addr)
    except (TimeoutError, OSError):
        return None
    finally:
        if sock is not None:
            with suppress(OSError):
                sock.close()


def local_ips() -> list[str]:
    """Every local IPv4 address, excluding loopback."""
    ip_list: list[str] = []
    try:
        for ip in socket.gethostbyname_ex(socket.gethostname())[2]:
            if not ip.startswith("127.") and ip not in ip_list:
                ip_list.append(ip)
    except OSError:
        pass

    try:
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.connect(("8.8.8.8", 80))
        ip = sock.getsockname()[0]
        sock.close()
        if ip not in ip_list and not ip.startswith("127."):
            ip_list.append(ip)
    except OSError:
        pass

    return ip_list


def subnet_broadcast_addresses() -> list[str]:
    """Broadcast address of every local interface, plus the global one."""
    broadcasts = ["255.255.255.255"]
    try:
        output = subprocess.check_output(["ip", "addr", "show"], text=True)
        for line in output.split("\n"):
            if "inet " in line and " brd " in line:
                match = re.search(r"brd\s+(\d+\.\d+\.\d+\.\d+)", line)
                if match and match.group(1) not in broadcasts:
                    broadcasts.append(match.group(1))
    except Exception:
        pass
    return broadcasts


def _broadcast_sweep(timeout: float, found: dict[str, str], verbose: bool) -> None:
    sock = _new_socket(timeout)
    try:
        _broadcast_once(sock, BROADCAST_ADDR, DISCOVERY_PORT, timeout, found, verbose)
    finally:
        sock.close()


def _per_interface_sweep(timeout: float, found: dict[str, str], verbose: bool) -> None:
    for target in subnet_broadcast_addresses():
        if target == "255.255.255.255":
            continue
        sock = _new_socket(timeout)
        try:
            _broadcast_once(sock, target, DISCOVERY_PORT, timeout, found, verbose)
        finally:
            sock.close()


def _probe_addresses(
    addresses: Iterable[str],
    found: dict[str, str],
    verbose: bool,
) -> None:
    for ip in addresses:
        _remember(found, _probe_single(ip, SUBNET_PROBE_TIMEOUT_S), verbose)


def _multicast_sweep(
    timeout: float,
    found: dict[str, str],
    verbose: bool,
    group: str,
    port: int,
) -> None:
    sock = _new_socket(timeout)
    try:
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        sock.bind(("", port))
        membership = socket.inet_aton(group) + socket.inet_aton("0.0.0.0")
        sock.setsockopt(socket.IPPROTO_IP, socket.IP_ADD_MEMBERSHIP, membership)
        _broadcast_once(sock, group, port, timeout, found, verbose)
    except OSError as error:
        if verbose:
            print(f"Multicast discovery failed: {error}")
    finally:
        sock.close()


def discover_all(
    timeout: float = 5.0,
    verbose: bool = True,
    *,
    probe_known: Iterable[str] = (),
    scan_subnet: bool = False,
    per_interface_broadcast: bool = False,
    multicast: bool = False,
    multicast_group: str = MULTICAST_GROUP,
    multicast_port: int = MULTICAST_PORT,
) -> list[DiscoveryResponse]:
    """Discover every Lyrebird drone reachable on the network.

    The plain broadcast is always sent; ``per_interface_broadcast``, the
    direct probes (``scan_subnet``/``probe_known``) and the multicast sweep
    are opt-in so each consumer pays only for the coverage it needs.
    """
    found: dict[str, str] = {}

    _broadcast_sweep(timeout, found, verbose)
    if per_interface_broadcast:
        _per_interface_sweep(timeout, found, verbose)
    if scan_subnet:
        _probe_addresses(candidate_subnet_ips(local_ips()), found, verbose)
    if probe_known:
        _probe_addresses(probe_known, found, verbose)
    if multicast:
        _multicast_sweep(timeout, found, verbose, multicast_group, multicast_port)

    return [DiscoveryResponse(ip_address=ip, name=name) for ip, name in found.items()]


def discover_drone(timeout: float = 5.0, verbose: bool = True) -> DiscoveryResponse | None:
    """Discover the first drone and stop at the first reply.

    The fast path used by the shared client's ``DJIInterface``; the full sweep
    (broadcast + direct probes + multicast) is ``discover_all``.
    """
    sock = _new_socket(timeout)
    try:
        sock.sendto(DISCOVERY_MSG, (BROADCAST_ADDR, DISCOVERY_PORT))
        if verbose:
            print(f"Broadcasting discovery message on port {DISCOVERY_PORT}...")
        deadline = time.time() + timeout
        while time.time() < deadline:
            try:
                data, addr = sock.recvfrom(1024)
            except TimeoutError:
                break
            parsed = _parse_response(data, addr)
            if parsed:
                if verbose:
                    print(f"Found Lyrebird drone at {parsed[0]}")
                return DiscoveryResponse(ip_address=parsed[0], name=parsed[1])
    except OSError as error:
        if verbose:
            print(f"Discovery error: {error}")
    finally:
        sock.close()
    return None
