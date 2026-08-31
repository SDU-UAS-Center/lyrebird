"""Lyrebird -- WHIP publish/stop soak test.

Repeatedly forces the app through a full WHIP publish teardown + restart cycle and
watches for the native crashes that were observed in the field (SIGILL in
``PeerConnection_nativeAddTrack``, SIGABRT "pthread_mutex_lock on a destroyed mutex").

Why this mechanism: attaching a telemetry client only starts streaming *once* per
client IP, and disconnecting clients does not stop the stream -- so connect/disconnect
alone never tears the publisher down. The app's own restart path does: every
``POST /send/streaming/mode webrtc`` calls ``restartActiveStreaming()``, which stops
the WebRTC streamer (full ``WhipPublisher.stop()`` -> ``teardown()``) and then starts a
brand-new publish. Driving that remotely over HTTP is exactly the publish/stop cycle.

Between cycles the script watches the Android crash buffer for new native crashes and
samples native/RSS memory from ``dumpsys meminfo``, so a regression prints
"soak FAILED at cycle N (crash)" instead of dying silently on a mission.

Usage::

    python3 soak_whip_publish_stop.py <DRONE_IP> [--cycles 30] [--hold 5]
        [--settle 3] [--start-timeout 20] [--serial <ADB_SERIAL>]
        [--mediamtx http://127.0.0.1:9997]

Requirements: adb on PATH, the app running and reachable at DRONE_IP:8080 (HTTP) and
DRONE_IP:8081 (telemetry). Run MediaMTX on this machine
(``docker compose -f GroundStation/video_test/compose.yaml up -d``) and pass --mediamtx for a true
end-to-end check (path goes live while publishing); without it the publish attempts
fail to reach a WHIP server but still exercise the same teardown/re-init path.
"""

from __future__ import annotations

import argparse
import json
import re
import socket
import subprocess
import sys
import time
import urllib.request
from typing import Any

PACKAGE = "com.lyrebird.rc"
HTTP_PORT = 8080
TELEMETRY_PORT = 8081
PUBLISH_STARTED_MARKER = "WHIP publishing started"
PUBLISH_STOPPED_MARKER = "WhipPublisher stopped"
MEDIAMTX_LIVE_STATES = {"ready", "running", "publishing"}

_MEMINFO_PATTERNS: dict[str, re.Pattern[str]] = {
    "native_heap_kb": re.compile(r"^\s*Native Heap\s+(\d+)", re.MULTILINE),
    "total_pss_kb": re.compile(r"^\s*TOTAL\s+PSS:\s+(\d+)", re.MULTILINE),
}


def adb(serial: str | None, *args: str) -> subprocess.CompletedProcess[str]:
    """Run an adb command against the device (optionally a specific serial)."""
    cmd = ["adb"]
    if serial:
        cmd += ["-s", serial]
    cmd += list(args)
    return subprocess.run(cmd, capture_output=True, text=True, timeout=60)


def device_pids(serial: str | None) -> set[str]:
    out = adb(serial, "shell", f"pidof {PACKAGE}").stdout.strip()
    return set(out.split()) if out else set()


def main_pid(serial: str | None) -> str:
    pids = sorted(device_pids(serial))
    if not pids:
        print(f"ERROR: {PACKAGE} is not running on the device; launch the app first.")
        sys.exit(2)
    return pids[0]


def crash_buffer(serial: str | None) -> str:
    return adb(serial, "logcat", "-b", "crash", "-d").stdout


def meminfo_kb(serial: str | None, pid: str) -> dict[str, int]:
    out = adb(serial, "shell", "dumpsys", "meminfo", pid).stdout
    result: dict[str, int] = {}
    for name, pattern in _MEMINFO_PATTERNS.items():
        match = pattern.search(out)
        if match:
            result[name] = int(match.group(1))
    return result


def clear_main_buffer(serial: str | None) -> None:
    adb(serial, "logcat", "-b", "main", "-c")


def wait_for_log_marker(serial: str | None, marker: str, timeout_s: float) -> bool:
    deadline = time.monotonic() + timeout_s
    while time.monotonic() < deadline:
        out = adb(serial, "logcat", "-b", "main", "-d", "-T", "200").stdout
        if marker in out:
            return True
        time.sleep(0.5)
    return False


def http_post(drone_ip: str, path: str, body: str) -> str:
    url = f"http://{drone_ip}:{HTTP_PORT}{path}"
    request = urllib.request.Request(url, data=body.encode("utf-8"), method="POST")
    with urllib.request.urlopen(request, timeout=10) as resp:  # nosec B310
        return resp.read().decode("utf-8").strip()


def mediamtx_paths(api_url: str) -> list[dict[str, Any]]:
    """List MediaMTX paths via its API (http://host:9997/v3/paths/list)."""
    with urllib.request.urlopen(f"{api_url}/v3/paths/list", timeout=3) as resp:  # nosec B310
        data = json.loads(resp.read().decode("utf-8"))
    return data.get("items", [])


def any_live_path(api_url: str) -> bool:
    return any(item.get("state") in MEDIAMTX_LIVE_STATES for item in mediamtx_paths(api_url))


def wait_for_mediamtx_live(api_url: str, timeout_s: float) -> bool:
    deadline = time.monotonic() + timeout_s
    while time.monotonic() < deadline:
        try:
            if any_live_path(api_url):
                return True
        except (OSError, ValueError):
            pass
        time.sleep(0.5)
    return False


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Soak-test WHIP publish/stop cycles against the Lyrebird aircraft app."
    )
    parser.add_argument("drone_ip", help="IP of the phone running the app")
    parser.add_argument("--cycles", type=int, default=30, help="number of publish/stop cycles")
    parser.add_argument("--hold", type=float, default=5.0, help="seconds to hold each publish open")
    parser.add_argument(
        "--settle", type=float, default=3.0, help="seconds after teardown before checks"
    )
    parser.add_argument(
        "--start-timeout", type=float, default=20.0, help="max seconds to wait for a publish cycle"
    )
    parser.add_argument("--serial", help="adb serial; auto-detects the single device when omitted")
    parser.add_argument(
        "--mediamtx",
        help="MediaMTX API base URL (e.g. http://127.0.0.1:9997) to verify end-to-end path lifecycle",
    )
    parser.add_argument(
        "--max-native-growth-mb",
        type=float,
        default=25.0,
        help="native heap growth over the run that fails the soak (MB)",
    )
    parser.add_argument(
        "--max-total-growth-mb",
        type=float,
        default=40.0,
        help="total PSS growth over the run that fails the soak (MB)",
    )
    return parser.parse_args()


def summarize(
    cycles_run: int,
    first_sample: dict[str, int],
    last_sample: dict[str, int],
    max_native_mb: float,
    max_total_mb: float,
) -> int:
    native_growth_mb = (
        last_sample.get("native_heap_kb", 0) - first_sample.get("native_heap_kb", 0)
    ) / 1024.0
    total_growth_mb = (
        last_sample.get("total_pss_kb", 0) - first_sample.get("total_pss_kb", 0)
    ) / 1024.0
    print("\n" + "=" * 62)
    print(f"Soak finished: {cycles_run} clean publish/stop cycles")
    print(f"  native heap growth : {native_growth_mb:+.1f} MB (limit {max_native_mb:.0f} MB)")
    print(f"  total PSS growth   : {total_growth_mb:+.1f} MB (limit {max_total_mb:.0f} MB)")
    if native_growth_mb > max_native_mb or total_growth_mb > max_total_mb:
        print("RESULT: FAIL (memory growth)")
        return 1
    print("RESULT: PASS")
    return 0


def run_cycle(
    args: argparse.Namespace, serial: str | None, pid: str, cycle: int
) -> tuple[int, str] | None:
    """Force one publish/stop cycle; returns a (cycle, reason) failure or None on success."""
    print(f"\ncycle {cycle}/{args.cycles}: POST /send/streaming/mode webrtc ...")
    clear_main_buffer(serial)
    response = http_post(args.drone_ip, "/send/streaming/mode", "webrtc")
    if "REJECTED" in response or "Invalid" in response:
        return cycle, f"streaming mode POST rejected: {response!r}"
    if not wait_for_log_marker(serial, PUBLISH_STARTED_MARKER, args.start_timeout):
        return cycle, "WHIP publish did not restart after POST"
    if not wait_for_log_marker(serial, PUBLISH_STOPPED_MARKER, args.start_timeout):
        return cycle, "previous WhipPublisher was not stopped (teardown skipped?)"
    print("  publish restarted (teardown + fresh publish)")
    if args.mediamtx and not wait_for_mediamtx_live(args.mediamtx, args.start_timeout):
        return cycle, "MediaMTX never showed a live path"

    time.sleep(args.hold)
    print(f"  holding {args.hold}s; waiting {args.settle}s ...")
    time.sleep(args.settle)

    crash = crash_buffer(serial)
    if crash.strip():
        return cycle, f"crash buffer is non-empty:\n{crash.strip()[-2000:]}"

    current_pids = device_pids(serial)
    if pid not in current_pids:
        return cycle, f"app process died/restarted: {sorted(current_pids)}"

    sample = meminfo_kb(serial, pid)
    print(f"  memory (kB): {sample}")
    return None


def main() -> int:
    args = parse_args()
    serial = args.serial
    pid = main_pid(serial)
    print(f"Device PID {pid}; clearing crash buffer (baseline) ...")
    adb(serial, "logcat", "-b", "crash", "-c")

    first_sample = meminfo_kb(serial, pid)
    print(f"Baseline memory (kB): {first_sample}")

    failures: list[tuple[int, str]] = []
    cycles_run = 0
    try:
        # Phase A: the first telemetry client starts streaming (once per client IP).
        # Keep the socket open for the whole soak so the client stays attached.
        print(f"Attaching first telemetry client via {args.drone_ip}:{TELEMETRY_PORT} ...")
        socket.create_connection((args.drone_ip, TELEMETRY_PORT), timeout=10)
        if not wait_for_log_marker(serial, PUBLISH_STARTED_MARKER, args.start_timeout):
            failures.append((0, "initial WHIP publish did not start after telemetry attach"))
        else:
            print("  initial WHIP publish started")

        for cycle in range(1, args.cycles + 1):
            failure = run_cycle(args, serial, pid, cycle)
            if failure is not None:
                failures.append(failure)
                break
            cycles_run = cycle
    except KeyboardInterrupt:
        print("\nInterrupted by user.")
    except OSError as error:
        failures.append((cycles_run + 1, f"socket/adb error: {error}"))

    if failures:
        cycle, reason = failures[0]
        print(f"\nSOAK FAILED at cycle {cycle}: {reason}")
        return 1

    last_sample = meminfo_kb(serial, pid)
    return summarize(
        cycles_run,
        first_sample,
        last_sample,
        args.max_native_growth_mb,
        args.max_total_growth_mb,
    )


if __name__ == "__main__":
    sys.exit(main())
