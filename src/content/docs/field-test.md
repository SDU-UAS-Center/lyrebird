---
title: Field Test
description: The procedure for verifying the MAVLink implementation against a real aircraft.
breadcrumb: Operations
---

Everything here has been checked on a bench against a real aircraft **except the flight**. The
onboard mission sequencer, the arrival dwell, the take-off altitude climb and the re-evaluated
position check have each only ever run in a compiler. Treat the first flight as a test of the
sequencer, not of the waypoints.

Three tools, in the order you'll want them:

| Tool | What it's for |
|---|---|
| `field_check.py` | Walks the link, settings and payload checks and prints pass/fail |
| QGroundControl | Proves the aircraft is a *standard* MAVLink vehicle, not just one this ground station understands |
| The dashboard's **MAVLink** tab | Runs both wires side by side and shows where they disagree — the only place this project's bugs have ever been visible |

## Before leaving

```bash
export LB_MAVLINK_PORT=14551        # QGC keeps 14550; a shared port means each gets half
export LB_MAVLINK_PEER_PORT=14550   # where the aircraft listens
export LB_MAVLINK_SIGNING_KEY=...   # optional; with it you are the Safety Computer
```

Check the phone and the ground station are on the **same build of the dialect**. A mismatch is
refused on the checksum, so fields go *missing* rather than wrong — which reads like a weak link
and is not one.

## 1. Link only — safe with props on

```bash
cd GroundStation/Python
PYTHONPATH=. python test_scripts/field_check.py <PHONE_IP>
```

Sends nothing. Confirms telemetry arrives, that the core fields decode, that the config streams,
and that the position isn't `(0, 0)`.

**Read the warnings, don't just count the failures.** `homeLocation`, `remainingFlightTime` and
`distanceToHome` absent is a state of the aircraft — no home point, no battery estimate — not a
gap in the interface. The script never fails those.

## 2. QGroundControl sees a normal vehicle

Point QGC at UDP **14550** and confirm, without touching anything else:

- the vehicle appears and stays connected, no "waiting for vehicle"
- position, altitude, heading and battery on the HUD, all moving
- the flight mode reads a name, not a number
- **QGC and `field_check.py` run at the same time**, both live. If one freezes they're sharing a
  listen port — a UDP datagram reaches exactly one socket.

This is the check that matters most for interoperability: it proves the aircraft speaks MAVLink,
rather than speaking whatever this ground station happens to accept.

## 3. Settings, on the ground

```bash
PYTHONPATH=. python test_scripts/field_check.py <PHONE_IP> --phase ground
```

Writes the RTH altitude and reads it back **through telemetry**, not from the ack — the ack only
proves the message arrived. Also confirms a non-numeric setting comes back *refused* rather than
being smuggled through as a magic number.

Cross-check in QGC's parameter editor: `LB_RTH_ALT`, `LB_MAX_HEIGHT`, `LB_MAX_DIST`.

## 4. Payload — the gimbal will move

```bash
PYTHONPATH=. python test_scripts/field_check.py <PHONE_IP> --phase payload --move
```

Gimbal absolute and relative, photo, thermal, temperature, rangefinder, AutoSensing.

The gimbal check is worth watching directly. The sign convention was settled by a 48-sample
measured sweep, not by argument; if the read-back lands near **+45** after commanding **−45**,
the sign has inverted. A reading of **6553.5** is DJI's unset marker (`65535/10`), not an angle.

Point the camera at a person for the AutoSensing step — zero detections with an empty frame is
the correct answer and tells you nothing.

Then confirm the photos landed:

```bash
PYTHONPATH=. python test_scripts/ftp_exercise.py <PHONE_IP>
```

Lists the card over MAVLink FTP and cross-checks the sizes against HTTP `/send/listMedia`.

## 5. The signing gate

```bash
PYTHONPATH=. python test_scripts/signing_exercise.py <PHONE_IP> [KEY_HEX]
```

Proves an unsigned command is refused once a signed one has latched authority to the Safety
Computer, and that only a signed release hands it back. Verified against pymavlink's signer, but
no real Safety Computer has ever sent one.

## 6. Flight

```bash
PYTHONPATH=. python test_scripts/field_check.py <PHONE_IP> --phase flight --fly
```

prints this list. **Pilot's thumb on the sticks throughout.** Every command has a manual
override, and item 5 confirms the override works before anything later depends on it.

| # | Check | What proves it |
|---|---|---|
| 1 | Take-off | `isFlying` true, altitude climbs |
| 2 | Take-off **altitude** | Ask for a height in `param7` and get *that* height. The climb runs after DJI's fixed-height auto-take-off, so watch for an overshoot |
| 3 | `gotoAltitude` / `gotoYaw` | Each acks `IN_PROGRESS`, then again on arrival |
| 4 | Manual override | Take the sticks mid-command: motion stops, and the next command is *refused* rather than silently ignored. Land, RTH and abort must stay available — those are the recovery actions |
| 5 | Release override | Control returns |
| 6 | Single waypoint | Flies there, and `intermediaryWaypointReached` latches **once**, with the right `seq` |
| 7 | Arrival dwell | It latches where the aircraft actually is, not on one noisy fix at the edge of the box, and not repeatedly. Three ticks by default, per-waypoint from the plan |
| 8 | Hold heading | The ROS ground station uses hold-heading, not nose-forward: the aircraft should keep its heading rather than yaw into the leg |
| 9 | **Multi-leg plan** | Two or three legs, low. Each completes in order, `MISSION_CURRENT` advances, nothing is skipped. *The single largest untested path* |
| 10 | Settle vs pass through | It settles where the plan says to settle, and flies through where `param3` says to pass |
| 11 | Supersede | A new plan mid-leg makes the old one report `SUPERSEDED`, not `FAILED` |
| 12 | Abort | Stops where it is, acks `CANCELLED` |
| 13 | RTH | Returns and lands at the home point, at the altitude written in step 3 |
| 14 | ROS ground station on `LB_TRANSPORT=mavlink` | An action completes end to end. Fall back to `both` if something is unmapped — that keeps MAVLink telemetry and HTTP for the rest |

### Re-test first: the two QGC guided commands

Both failed on the first field attempt and both are fixed, so these are the ones to fly before
anything else depends on them.

**QGC → Go to location.** It flew *away* from the point. `MAV_CMD_DO_REPOSITION`'s ground-speed
parameter is documented as "less than 0 (-1) for default" and QGC sends `-1`; that reached the
waypoint loop as the speed *ceiling*, so the commanded speed was −1 m/s and the aircraft retreated
at walking pace. It could never recover on its own — the along-track term stays positive while it
retreats, so nothing reversed the sign. Confirm it now flies *toward* the point, at the profile
cruise speed.

**QGC → Change Altitude.** Nothing happened. QGC expresses it as the same `DO_REPOSITION` with
latitude and longitude `NaN`, meaning "hold position, change only altitude". Those were flown as
coordinates: over `COMMAND_INT` NaN scales to `0`, which is a real position in the Gulf of Guinea.
It now routes to the altitude controller. Confirm the aircraft climbs or descends **without
rotating** — a zero-length leg has no bearing, and the nose-forward controller would have read
`atan2(0, 0)` and turned to north first.

Also worth watching: a goto sends `yaw = NaN`, "don't change yaw". The arrival heading was
hardcoded to zero, so the aircraft used to finish by rotating to north. It should now hold the
heading it already had.

## Keep this open while flying

The dashboard's **MAVLink** tab, with *cross-check against HTTP* on.

Not one MAVLink defect in this work was found by reading code. Every one was a frame that decoded
cleanly and meant the wrong thing — a camera heartbeat overwriting the flight mode, two gimbal
fields transposed because MAVLink packs largest-first, a status message read twelve bytes late.
A compiler can't see any of those, and neither can a reviewer.

**A disagreement that appears only in the air is the interesting one.** Everything above has been
checked at rest, so anything new belongs to motion: a value that's fine stationary and wrong at
speed, or one that saturates.

MAVLink commands are now recorded in the flight log alongside the HTTP ones, with their raw
parameters, in `/sdcard/Documents/lyrebird/FlightLogs/<date>/`. They were not, which is why the
first two field failures had to be diagnosed from the source rather than from evidence — a
mission flown over MAVLink left no record of having been commanded at all. Both defects are a
sentinel value flown as a real one, and both are plain in a line reading `p1=-1.0` or a `NaN`
latitude. Pull the log after flying:

```bash
adb pull /sdcard/Documents/lyrebird/FlightLogs/$(date +%F)/
```
