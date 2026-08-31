---
title: MAVLink 2 Interface
description: Lyrebird presents each aircraft as a MAVLink 2 vehicle on UDP port 14550, so QGroundControl, MAVSDK, and pymavlink fly it with no plugin.
breadcrumb: Interfaces
---

Lyrebird presents each aircraft as a **MAVLink 2 vehicle**, so a stock ground station — QGroundControl, MAVSDK, `pymavlink` — connects, sees telemetry and video, and flies it with no plugin and no configuration file.

This is a full control surface, not a telemetry feed. Every command the ROS ground station uses has a MAVLink form, and so does every `/send/set*` setting. The HTTP surface on port 8080 runs alongside it, unchanged — kept for compatibility with ground stations built against this project's predecessor, WildBridge, and as the API for what MAVLink doesn't cover yet. The two are kept in step deliberately, and the dashboard's **MAVLink** tab reads both at once so a disagreement between them is visible rather than inferred.

**On by default; flight motion remains explicitly gateable.** Following PX4's pattern of switching MAVLink instances on by parameter rather than by build, the endpoint itself starts automatically — MAVLink and HTTP run side by side out of the box, so QGroundControl and the HTTP API both work with no setup. Flight motion is enabled by default for fresh and migrated installations, while `lb_mav_0_allow_flight` remains the explicit settings gate that can block movement when required.

| Preference | Type | Default | Meaning |
|------------|------|---------|---------|
| `lb_mav_0_enabled` | bool | `true` | Start the endpoint at all |
| `lb_mav_0_host` | string | *(empty)* | Ground-station address. Empty means broadcast on the subnet |
| `lb_mav_0_port` | int | `14550` | Port to send to and listen on |
| `lb_mav_0_mode` | string | `normal` | Stream profile: `normal` or `minimal` |
| `lb_mav_0_sysid` | int | `1` | MAVLink system id — one per aircraft |
| `lb_mav_0_allow_flight` | bool | `true` | Allow commands that move the aircraft; disable it from the app settings when flight control must be blocked |
| `lb_mav_0_signing_key` | string | *(empty)* | 64 hex characters shared with the Safety Computer |
| `lb_mission_exec` | string | `dji_native` | Who flies an uploaded plan: `onboard` or `dji_native` — see [Missions](/missions/) |

Flight commands are gated twice: `lb_mav_0_allow_flight` must be on, and the command must pass the same `ControlAuthority` check the HTTP surface uses. Anything that would fly the aircraft somewhere new is refused while the pilot holds the sticks; land, return and abort stay available, because those are the recovery actions.

## Protocols implemented

| Microservice | What works |
|---|---|
| **Telemetry** | Heartbeat, attitude, position, GPS, battery, VFR HUD, extended system state, home position, current mode, mission progress, gimbal attitude, rangefinder distance |
| **Command** | Take-off (with altitude), land, return, reposition, yaw, altitude, stick input, arm/disarm, camera, gimbal, payload release |
| **Mission** | Upload and download handshake, `MISSION_START`, progress and arrival reports. Per-waypoint hold time, acceptance radius, pass-through, heading, speed, camera/gimbal actions and region of interest are all honoured by one of two executors — see [Missions](/missions/) |
| **Parameter** | `PARAM_SET` for numeric settings; `PARAM_EXT_SET` for string settings such as the drone name, video source and MediaMTX address |
| **Camera / Gimbal** | Camera information, settings, capture status, video stream information; gimbal attitude and pitch/yaw control |
| **File transfer** | Read-only MAVLink FTP for listing and reading the SD card |
| **Signing** | MAVLink 2 packet signing identifies the Safety Computer, mirroring `X-Safety-Token` on the HTTP surface |

### Why bulk media stays on HTTP

MAVLink FTP moves data in small chunks by design — a deliberately slow, lightweight transfer so one aircraft downloading a photo doesn't crowd out the telemetry and command traffic the rest of a swarm is sharing the same radio spectrum for. HTTP has no such restraint: it uses as much of the Wi-Fi link's capacity as it can get, which is exactly what a multi-megabyte thermal capture or a video file needs. That trade-off is why `/send/listMedia` and `/send/downloadMediaByName` stay on HTTP rather than moving to MAVLink FTP — see [HTTP API](/http-api/). MAVLink FTP remains the fallback for small reads on a MAVLink-only link.

## How packet signing works

A signed frame is checked, not just noticed. A frame signed with the key configured in `lb_mav_0_signing_key` is trusted as the Safety Computer; anything unsigned is treated as the Pilot — the same behaviour every frame had before signing existed, so an installation that leaves the key blank is unaffected. A signature that fails to verify is refused outright rather than falling back to Pilot: accepting it as unsigned traffic would let anyone who can't forge a signature simply strip it off, and a check that can be bypassed by deleting it isn't a check.

Two details make it hold up against an adversary, not just a misconfiguration:

- **Constant-time comparison.** The signature is compared byte by byte with no early return, so how much of a forged signature happened to match can't leak through timing.
- **Replay protection.** MAVLink's signing timestamp must strictly increase per `(system, component, link)`. A resent frame carries a technically valid signature by construction — it's a real frame, just an old one — so the timestamp is the only thing stopping a captured "land now" from being replayed and obeyed twice.

A malformed key (wrong length, non-hex characters) is treated exactly like no key configured, on purpose: a typo must degrade the link back to the un-signed state it started in, not lock out the real Safety Computer.

## Why it looks like PX4 to QGroundControl

Lyrebird's heartbeat claims `MAV_AUTOPILOT_PX4`. It does not run PX4 firmware — DJI's own flight
controller does everything — but MAVLink identity is what a ground station uses to decide which UI
to show, and getting that identity right is what makes QGroundControl's Fly View and Plan view
(including mission upload and start) work with zero configuration, rather than a telemetry-only
read-out with no way to fly the aircraft.

**Why PX4 specifically.** QGroundControl only enables its Fly View action buttons (Takeoff, Land,
RTL) and its Plan view's mission controls for firmware plugins that declare guided-mode capability.
Its Generic plugin — what `MAV_AUTOPILOT_INVALID`/`_GENERIC` gets — declares none. Between QGC's two
vendor plugins, PX4's mode list is the closer match to what a DJI aircraft can actually do: Takeoff,
Land and Return exist as named PX4 modes, where ArduCopter's mode list has no equivalents.

**Modes are PX4's packed numbers, not names.** Claiming PX4 has a cost: QGC's PX4 plugin renders
`HEARTBEAT.custom_mode` through PX4's own mode enum — a packed `(main_mode << 16) | (sub_mode << 24)`
integer, not a string. `MavlinkFlightMode` maps each of Lyrebird's own modes onto the PX4 mode
number whose *meaning* matches:

| Lyrebird mode | Reported as PX4 mode |
|---|---|
| Position hold | `POSCTL` |
| Altitude hold | `ALTCTL` |
| Offboard (virtual stick) | `OFFBOARD` |
| Mission | `MISSION` |
| Take-off | `TAKEOFF` |
| Land | `LAND` |
| Return | `RTL` |
| Orbit | `ORBIT` |
| Manual | `MANUAL` |
| DJI intelligent-flight modes | `FOLLOW_TARGET` |

This works in both directions: when an operator presses Land or RTL in QGC's Fly View, QGC sends
`SET_MODE` with PX4's number for that mode, and the endpoint maps it straight back to the Lyrebird
action it named. The same modes are also advertised through the newer, portable `MAV_STANDARD_MODE`
protocol (`AVAILABLE_MODES`/`CURRENT_MODE`) wherever a standard identity exists, so a ground station
that understands standard modes doesn't need any PX4-specific knowledge at all.

**A version number QGC insists on.** QGC's initial-connect handshake explicitly requests
`AUTOPILOT_VERSION` by name and retries until it gets one — streaming it on a timer alone was not
enough (found by running QGC against the endpoint and reading `RequestAutopilotVersion: Max retries
exhausted` in its log). The reply reports PX4 firmware `1.15.0`: leaving the version at zero makes
QGC show two dialogs on every single connection — a compatibility notice and a "not running latest
stable firmware" warning — for no reason other than an absent version number.

**Capabilities are only claimed once they exist.** The same message reports a capability bitmask;
today that is only `MAVLINK2` and `FTP`, matching the read-only MAVLink FTP server Lyrebird actually
serves. Claiming `MISSION_INT` or any other capability before the code behind it exists would be the
same class of mistake as claiming the wrong autopilot — QGC would assume behaviour that isn't there.

**Where honesty resumes.** The identity claim stops at the wire-protocol level. Any telemetry value
DJI genuinely does not provide is reported using MAVLink's own "unknown" conventions (`NaN`,
`INT32_MAX`, and similar), not a plausible-looking zero that would read as real data.

## Two conventions worth knowing

**Heading, per waypoint.** `NAV_WAYPOINT.param4` is `NaN` for "use the vehicle's own heading mode" and a value for "hold this heading". That is exactly the difference between Lyrebird's two waypoint controllers, so one plan can mix them, and `DO_REPOSITION` reads it the same way. No custom mission item was needed.

**Arrival, per waypoint.** `param1` (hold time), `param2` (acceptance radius) and `param3` (pass through) come from the plan, because only the plan knows which leg is the last one. A leg marked pass-through is flown through; the final one settles.

## Quiet until someone is listening

Only the 1 Hz heartbeat broadcasts onto the subnet unconditionally — that's the standard MAVLink discovery beacon, and it's how QGroundControl or `pymavlink` finds the aircraft with no configuration at all. The full telemetry stream never broadcasts: it only starts flowing once a ground station has been heard from (its own heartbeat, on connect) or `lb_mav_0_host` is set to a specific address. A `lb_mav_0_enabled` aircraft that nobody has connected to yet puts one small heartbeat a second on the air, not the full message set — the point being not to load a shared radio link with a stream nobody asked for, which matters most exactly where it would hurt most: several aircraft sharing spectrum in a swarm.

## Running more than one ground station

A UDP datagram goes to exactly one socket, so each ground station needs its own listen port; the aircraft fans telemetry out to every station it has heard from.

| | Listens | Sends to aircraft |
|---|---|---|
| QGroundControl | 14550 | 14550 |
| ROS drone nodes (`LB_MAVLINK_PORT`) | 14551 | 14550 |
| Dashboard MAVLink tab (`LB_WEBAPP_MAVLINK_PORT`) | 14552 | 14550 |

A fleet needs one port per aircraft for the same reason.

## Verify without QGroundControl

```bash
pip install pymavlink
lyrebird-mavlink-listen --summary 5
```

It prints the first instance of each message with decoded values, then a rate summary, and reports malformed frames loudly as `BAD_DATA`. It never transmits.

**Connect QGroundControl:** it listens on UDP 14550 by default and adds a link on receiving traffic. If the RC and the ground station share a LAN, enabling `lb_mav_0_enabled` is all that is required — see [above](#why-it-looks-like-px4-to-qgroundcontrol) for why QGC's action buttons and mission controls light up with no plugin.

## Field testing

The [Field Test](/field-test/) page is the procedure for verifying all of this against a real aircraft. The ground half runs itself:

```bash
cd GroundStation/Python
python test_scripts/field_check.py <PHONE_IP>                    # listens only
python test_scripts/field_check.py <PHONE_IP> --phase ground     # parameter writes
python test_scripts/field_check.py <PHONE_IP> --phase payload --move
python test_scripts/field_check.py <PHONE_IP> --phase flight --fly
```

Checks are grouped by what they can move, and the script will not cross a group boundary without being told to: it sends nothing at all by default, needs `--move` before the gimbal or camera responds, and only prints the flight list under `--fly`.

## Video streaming

Lyrebird's supported public video path is WebRTC publishing through WHIP to MediaMTX, with browser playback through WHEP. The app publishes DJI camera frames to a WHIP URL selected by the ground station, normally:

```text
http://<ground-station-ip>:8889/<drone_name>/whip
```

The GroundStation video dashboard then watches the matching WHEP URL:

```text
http://<ground-station-ip>:8889/<drone_name>/whep
```

This replaces the older direct RC-hosted WebSocket-signaling viewer. That sample viewer/server path is no longer part of the public app or ground-station tooling.
