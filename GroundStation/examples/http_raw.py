"""Take off, fly to a point, come back — over plain HTTP and the TCP telemetry stream.

The same flight as every script in this folder:

    1. take off
    2. fly to a point --distance metres north of the take-off spot, --height metres up
    3. wait until the aircraft reports it has arrived
    4. return home

Nothing Lyrebird-specific is imported: commands are HTTP POSTs to port 8080 and telemetry is one
JSON object per line on TCP port 8081. That is the whole contract, so this is the script to read
before writing a client in another language. See the HTTP API page in the docs for every endpoint.

Safety: nothing moves unless you pass --fly; without it the script checks the aircraft and prints
the plan. The checks refuse to fly without a GPS fix, a home point, enough battery, or with the
app's manual override on; a DJI status warning is shown. Ctrl+C or a timeout sends the aircraft
home. The RC sticks override everything at any time — keep your hands on them.

    $PY GroundStation/examples/http_raw.py --ip <RC_IP>          # dry run
    $PY GroundStation/examples/http_raw.py --ip <RC_IP> --fly    # fly

Needs only `requests`. $PY is the examples' virtual environment — see README.md in this folder.
"""

import argparse
import json
import re
import socket
import sys
import threading
import time

import requests

COMMAND_PORT = 8080
TELEMETRY_PORT = 8081
METRES_PER_DEGREE_LAT = 111_320.0

MIN_SATELLITES = 8
MIN_BATTERY_PERCENT = 30
AIRBORNE_HEIGHT_M = 1.0
# DJI's automatic take-off ends in a hover at about 1 m (MAVLink reports exactly 1.0, so "above
# 1 m" never comes). Take-off is over once the aircraft is above LIFTED_OFF_M and its height has
# held within SETTLED_WITHIN_M for SETTLED_FOR_S.
LIFTED_OFF_M = 0.5
SETTLED_WITHIN_M = 0.1
SETTLED_FOR_S = 2.0
TAKEOFF_TIMEOUT_S = 30.0
ARRIVAL_TIMEOUT_S = 90.0


class Telemetry:
    """The latest telemetry object, kept up to date by a background reader."""

    def __init__(self, ip: str):
        self._latest: dict = {}
        self._lock = threading.Lock()
        self._sock = socket.create_connection((ip, TELEMETRY_PORT), timeout=5)
        threading.Thread(target=self._read, daemon=True).start()

    def _read(self) -> None:
        buffer = b""
        while True:
            try:
                chunk = self._sock.recv(65536)
            except OSError:
                return
            if not chunk:
                return
            buffer += chunk
            # One JSON object per line.
            *lines, buffer = buffer.split(b"\n")
            for line in lines:
                if line.strip():
                    try:
                        sample = json.loads(line)
                    except ValueError:
                        continue
                    with self._lock:
                        self._latest = sample

    def get(self) -> dict:
        with self._lock:
            return dict(self._latest)

    def close(self) -> None:
        self._sock.close()


def send(ip: str, endpoint: str, body: str = "") -> str:
    """POST one command; the body is plain text, the reply a short text or JSON string."""
    reply = requests.post(f"http://{ip}:{COMMAND_PORT}{endpoint}", data=body, timeout=10)
    reply.raise_for_status()
    return reply.text


def preflight(t: dict) -> list[str]:
    """Reasons not to fly; empty when the aircraft is ready."""
    if not t:
        return ["no telemetry yet — is the app running and on the same network?"]
    problems = []
    if t.get("satelliteCount", -1) < MIN_SATELLITES:
        problems.append(f"only {t.get('satelliteCount')} GPS satellites (need {MIN_SATELLITES})")
    if not t.get("homeSet", False):
        problems.append("home point not set yet — wait for the GPS fix")
    if t.get("batteryLevel", -1) < MIN_BATTERY_PERCENT:
        problems.append(f"battery at {t.get('batteryLevel')} % (need {MIN_BATTERY_PERCENT} %)")
    if t.get("altitude", 0.0) > AIRBORNE_HEIGHT_M:
        problems.append("aircraft is already airborne")
    if t.get("isManualOverrideActive", False):
        # The latch the sticks set: autonomous commands stay refused until it is cleared.
        problems.append("manual override is on — switch the app's MANUAL/AUTO toggle to AUTO")
    return problems


def dji_status(t: dict) -> str | None:
    """DJI's status banner when it is not NORMAL, e.g. CHLSTATUS_POOR (weak RC link).

    Most of these are warnings DJI still takes off with, so they are shown, not enforced; if one
    does stop the take-off, DJI refuses the command and the script gives up and sends it home.
    """
    if t.get("readyToTakeoff", True):
        return None
    return t.get("takeoffBlockReason", "UNKNOWN")


def wait_until(condition, timeout_s: float, what: str) -> None:
    deadline = time.time() + timeout_s
    while not condition():
        if time.time() > deadline:
            raise TimeoutError(f"{what} did not happen within {timeout_s:.0f} s")
        time.sleep(0.2)


def hovering(read_height):
    """A condition that is true once DJI's take-off is over: off the ground and holding height.

    DJI's automatic take-off climbs to about 1 m and hovers, and navigation commands sent while
    it is still climbing are ignored. "Over" is read from the height: above LIFTED_OFF_M and
    within SETTLED_WITHIN_M of where it was SETTLED_FOR_S ago.
    """
    samples: list[tuple[float, float]] = []

    def check() -> bool:
        now, height = time.time(), read_height()
        samples.append((now, height))
        earlier = [h for t, h in samples if t <= now - SETTLED_FOR_S]
        if not earlier:
            return False
        return height > LIFTED_OFF_M and abs(height - earlier[-1]) < SETTLED_WITHIN_M

    return check


def fly(ip: str, telemetry: Telemetry, distance_m: float, height_m: float, speed: float) -> None:
    start = telemetry.get()
    target_lat = start["location"]["latitude"] + distance_m / METRES_PER_DEGREE_LAT
    target_lon = start["location"]["longitude"]
    heading = start.get("heading", 0.0)

    print("Taking off ...")
    print("  ", send(ip, "/send/takeoff"))
    wait_until(
        hovering(lambda: telemetry.get().get("altitude", 0.0)),
        TAKEOFF_TIMEOUT_S,
        "take-off",
    )

    print(f"Flying to {target_lat:.6f}, {target_lon:.6f} at {height_m:.0f} m ...")
    # Navigation commands need the app to hold virtual stick control first.
    print("  ", send(ip, "/send/enableVirtualStick"))
    # Body: lat,lon,altitude,final heading,max speed. The reply names the command's seq.
    reply = send(
        ip,
        "/send/gotoWaypointNoseForward",
        f"{target_lat},{target_lon},{height_m},{heading},{speed}",
    )
    print("  ", reply)
    match = re.search(r"WAYPOINT_ACCEPTED seq=(\d+)", reply)
    if not match:
        raise RuntimeError(f"the go-to was refused: {reply}")
    seq = int(match.group(1))

    # Telemetry's waypointReached is a latch shared by every go-to; matching waypointSeq to this
    # command's seq is what keeps a "reached" from an earlier command from ending this one.
    def arrived() -> bool:
        t = telemetry.get()
        return bool(t.get("waypointReached")) and t.get("waypointSeq") == seq

    wait_until(arrived, ARRIVAL_TIMEOUT_S, "arrival at the target")
    print("Arrived.")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--ip", required=True, help="RC/phone IP")
    parser.add_argument("--distance", type=float, default=15.0, help="metres north (default 15)")
    parser.add_argument("--height", type=float, default=10.0, help="metres up (default 10)")
    parser.add_argument("--speed", type=float, default=3.0, help="m/s (default 3)")
    parser.add_argument("--fly", action="store_true", help="fly; without it this is a dry run")
    args = parser.parse_args()

    telemetry = Telemetry(args.ip)
    try:
        time.sleep(2.0)  # let the first telemetry arrive
        t = telemetry.get()
        print(f"Aircraft {t.get('droneName', '?')} at {args.ip} over HTTP")
        print(f"Plan: take off, fly {args.distance:.0f} m north at {args.height:.0f} m, come home.")
        problems = preflight(t)
        status = dji_status(t)
        if status:
            print(f"DJI status: {status} — check the RC screen; DJI decides whether it takes off.")
        if problems:
            print("Not flying:\n  - " + "\n  - ".join(problems))
            return 1
        if not args.fly:
            print("Pre-flight checks passed. Dry run: add --fly to fly.")
            return 0

        try:
            fly(args.ip, telemetry, args.distance, args.height, args.speed)
        except (KeyboardInterrupt, TimeoutError, RuntimeError, requests.RequestException) as reason:
            print(f"Stopping: {reason or 'Ctrl+C'}")
        finally:
            # Every way out of the flight ends here — arrived, timed out, refused or interrupted.
            print("Returning home.")
            print("  ", send(args.ip, "/send/RTH"))
        return 0
    finally:
        telemetry.close()


if __name__ == "__main__":
    sys.exit(main())
