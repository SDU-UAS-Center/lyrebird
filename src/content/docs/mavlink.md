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
| `lb_mav_0_sysid` | int | `0` (auto) | MAVLink system id — `1..99` is an explicit user/fleet assignment; `0` derives a stable serial-based id in `100..254` |
| `lb_mav_0_allow_flight` | bool | `true` | Allow commands that move the aircraft; disable it from the app settings when flight control must be blocked |
| `lb_mav_0_signing_key` | string | *(empty)* | 64 hex characters shared with the Safety Computer |
| `lb_mission_exec` | string | `onboard` | Who flies an uploaded plan: `onboard` or `dji_native` — see [Missions](/missions/) |

Flight commands are gated twice: `lb_mav_0_allow_flight` must be on, and the command must pass the same `ControlAuthority` check the HTTP surface uses. Anything that would fly the aircraft somewhere new is refused while the pilot holds the sticks; land, return and abort stay available, because those are the recovery actions.

## Protocols implemented

| Microservice | What works |
|---|---|
| **Telemetry** | Heartbeat, attitude, position, GPS, battery, VFR HUD, extended system state, home position, current mode, mission progress, gimbal attitude, rangefinder distance |
| **Command** | Take-off (with altitude), land, return, reposition, yaw, altitude, stick input, arm/disarm, camera, gimbal, payload release |
| **Mission** | Upload and download handshake, `MISSION_START`, progress and arrival reports. Per-waypoint hold time, acceptance radius, pass-through, heading, speed, camera/gimbal actions and region of interest are all honoured by one of two executors — see [Missions](/missions/) |
| **Parameter** | `PARAM_SET` for numeric settings; `PARAM_EXT_SET` for string settings such as the drone name, video source and MediaMTX address |
| **Camera / Gimbal** | Camera information, settings, capture status, video stream information, video recording start/stop; gimbal v2 manager (information, status, control hand-over) with gimbal attitude and pitch/yaw control |
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

A mode request also arrives as `MAV_CMD_DO_SET_MODE`, which carries PX4's mode in two parameters:
`param2` is the main mode and `param3` the sub mode — Return is `(4, 5)`. That is how MAVSDK asks
for Return, and for Hold `(4, 3)` before every `goto_location()`. The endpoint reads both forms, and
an already-packed number in `param2`, which is what Lyrebird's own Python client sends. PX4's
**Hold** is accepted as a position hold and reported as `POSCTL`: on a DJI aircraft the two mean the
same thing, so the mode list doesn't offer both.

What QGC shows as the current mode follows the aircraft, not the last button pressed: **Position**
while DJI holds position on its own (hovering, after Hold), **Offboard** while the app is flying the
aircraft (a go-to, an altitude change, an orbit), **Manual** while the manual-override latch is
set. The latch closes when the pilot moves an RC stick past 30 % during autonomous flight, or when DJI
starts a return home the app did not ask for; commands from a ground station are then refused
until the app's **MANUAL/AUTO** toggle is switched back to AUTO. Over MAVLink that currently
includes Return and Land (`DENIED`), so while the latch is set, bring the aircraft back with the
RC's own return button or the sticks.

**A version number QGC insists on.** QGC's initial-connect handshake explicitly requests
`AUTOPILOT_VERSION` by name and retries until it gets one — streaming it on a timer alone was not
enough (found by running QGC against the endpoint and reading `RequestAutopilotVersion: Max retries
exhausted` in its log). The reply reports PX4 firmware `1.15.0`: leaving the version at zero makes
QGC show two dialogs on every single connection — a compatibility notice and a "not running latest
stable firmware" warning — for no reason other than an absent version number.

**A protocol version, so QGC stays on MAVLink 2.** QGroundControl 5.0 asks for `PROTOCOL_VERSION`
during connect and, when a vehicle can't report one, assumes it speaks MAVLink 1 and switches its own
framing. The endpoint reads only MAVLink 2, so from that moment every request went unanswered:
parameters stalled and commands, takeoff included, got no reply. It now answers with version 2.0 as
its minimum, current and maximum — it really doesn't read MAVLink 1, so it says so. QGC 5.2 no longer
asks.

**Capabilities are only claimed once they exist.** The same message reports a capability bitmask;
today that is only `MAVLINK2` and `FTP`, matching the read-only MAVLink FTP server Lyrebird actually
serves. Claiming `MISSION_INT` or any other capability before the code behind it exists would be the
same class of mistake as claiming the wrong autopilot — QGC would assume behaviour that isn't there.

**Where honesty resumes.** The identity claim stops at the wire-protocol level. Any telemetry value
DJI genuinely does not provide is reported using MAVLink's own "unknown" conventions (`NaN`,
`INT32_MAX`, and similar), not a plausible-looking zero that would read as real data.

## How commands are acknowledged

Every command gets a `COMMAND_ACK`, and the result is what actually happened rather than what was
dispatched:

- **Take-off** is acknowledged `IN_PROGRESS` straight away and then with DJI's verdict, typically
  within a fraction of a second: `ACCEPTED`, or `FAILED` with DJI's reason as a `STATUSTEXT`
  ("DJI refused take-off: …"), which QGroundControl shows. A take-off with an altitude (`param7`)
  climbs to it once DJI's own take-off has finished — unless another movement command arrived
  in the meantime, which then takes precedence instead of being replaced by the climb.
- **Movements that take time** — a go-to (`DO_REPOSITION`), an altitude or yaw change — are
  acknowledged `IN_PROGRESS` and the acknowledgement is repeated about twice a second while the
  aircraft flies, as MAVLink's command protocol asks; the final acknowledgement is `ACCEPTED` on
  arrival. A ground station waiting for the result must skip the repeated `IN_PROGRESS` and wait for
  the first one that isn't.
- **Replaced is not failed.** A movement that another movement command replaces — a newer go-to, or
  a return, land or abort — ends `CANCELLED` at once. A movement the pilot ends by taking the
  sticks (the manual-override latch, which DJI's own return home also sets) ends `FAILED`.
- Payload actions that take DJI a moment, such as starting a recording, also answer `IN_PROGRESS`
  first, so QGroundControl 5.2's 1.2 s acknowledgement timeout is never hit.

## Two conventions worth knowing

**Heading, per waypoint.** `NAV_WAYPOINT.param4` is `NaN` for "use the vehicle's own heading mode" and a value for "hold this heading". That is exactly the difference between Lyrebird's two waypoint controllers, so one plan can mix them, and `DO_REPOSITION` reads it the same way. No custom mission item was needed.

**Altitude, relative to take-off.** DJI reports the aircraft's height above its take-off point, and
that is what the position messages carry: the aircraft reads about 0 m on the ground and home sits at
0 m. QGroundControl's altitude arithmetic (take-off height, Go to location, Change altitude) therefore
works out as heights above take-off throughout. Plan items are read the same way and their altitude
frame is not consulted, so keep a plan's altitude mode at QGroundControl's default, *Relative to
launch* — an AMSL or terrain-following altitude would be flown as a height above take-off.

**Arrival, per waypoint.** `param1` (hold time), `param2` (acceptance radius) and `param3` (pass through) come from the plan, because only the plan knows which leg is the last one. A leg marked pass-through is flown through; the final one settles.

## Quiet until someone is listening

Only the 1 Hz heartbeat broadcasts onto the subnet unconditionally — that's the standard MAVLink discovery beacon, and it's how QGroundControl or `pymavlink` finds the aircraft with no configuration at all. The full telemetry stream never broadcasts: it only starts flowing once a ground station has been heard from (its own heartbeat, on connect) or `lb_mav_0_host` is set to a specific address. A `lb_mav_0_enabled` aircraft that nobody has connected to yet puts one small heartbeat a second on the air, not the full message set — the point being not to load a shared radio link with a stream nobody asked for, which matters most exactly where it would hurt most: several aircraft sharing spectrum in a swarm.

## Running more than one ground station

A UDP datagram goes to exactly one socket, so each ground station needs its own listen port; the aircraft fans telemetry out to every station it has heard from.

| | Listens | Sends to aircraft |
|---|---|---|
| QGroundControl | 14550 | 14550 |
| Lyrebird fleet router (`mavlink_port` in `fleet_settings.yaml`) | 14561 | 14550 |
| Dashboard MAVLink tab (`LB_WEBAPP_MAVLINK_PORT`) | 14552 | 14550 |

A Lyrebird fleet uses one shared local listener for all aircraft. The router registers each
aircraft by its discovered IP, binds the route to the first autopilot heartbeat's `sysid`, and
uses that `sysid` for telemetry and command demultiplexing. The aircraft name from discovery
continues to define the ROS namespace; it is not reconstructed from `sysid`. An active duplicate
`sysid` is rejected and reported, while a stale route may be rebound after reconnect.

QGroundControl and Lyrebird should not bind the same local UDP port. The aircraft can send to
both destinations, or Lyrebird can explicitly forward a copy to QGroundControl in a future relay
configuration. If the aircraft sends only to Lyrebird, QGroundControl will not see that stream.

## Verify without QGroundControl

```bash
uv run --locked --group mavlink lyrebird-mavlink-listen --summary 5
```

It prints the first instance of each message with decoded values, then a rate summary, and reports malformed frames loudly as `BAD_DATA`. It never transmits.

## Using QGroundControl

**Connect.** QGroundControl listens on UDP 14550 by default and adds a link on receiving traffic. If the RC and the ground station share a LAN, enabling `lb_mav_0_enabled` is all that is required — see [above](#why-it-looks-like-px4-to-qgroundcontrol) for why QGC's action buttons and mission controls light up with no plugin. Tested with QGroundControl 5.0.8 and 5.2.0; both load the parameters and finish connecting within a couple of seconds.

**Fly.** Take-off, Change altitude, Go to location, Orbit, Pause, Land and Return in Fly View have been flown from QGroundControl 5.2 against a DJI Mini 4 Pro. Heights are above the take-off point (see [Altitude](#two-conventions-worth-knowing)). The RC sticks always take over from an automated action.

**"Command failed" during a go-to** means the go-to ended before it arrived because the pilot took over — the sticks, or DJI starting its own return home, for example on low battery. QGC's mode then reads **Manual**; the app's MANUAL/AUTO toggle hands control back. See [How commands are acknowledged](#how-commands-are-acknowledged).

**Video.** QGroundControl plays RTSP, not WebRTC, so its video comes through MediaMTX: the aircraft publishes to MediaMTX over WHIP, MediaMTX republishes the same stream on RTSP, and the aircraft advertises that address in `VIDEO_STREAM_INFORMATION` — QGC picks it up on its own, with nothing to configure. The [video dashboard](/groundstation/#groundstation-video-dashboard) stack has to be running for this, because it is what tells the aircraft where to publish. QGC reads the address once per connection: after renaming the drone (the name is the stream path), restart QGroundControl so it picks up the new one. The dashboard follows a rename by itself.

**Gimbal.** The camera component acts as a MAVLink gimbal manager, so a gimbal indicator appears in QGC's top toolbar with **Tilt 90** and **Center**. For finer aiming, open the indicator's expanded panel and switch **On-Screen Control** on:

- *Click and drag off* — click a point in the video and the camera aims there. The angle comes from the **Horizontal/Vertical FOV** settings in the same panel, so set them to your camera's field of view for accurate aiming.
- *Click and drag on* — press, hold and drag on the video; the gimbal moves while you drag, faster the further you go.

QGC takes control of the gimbal (`DO_GIMBAL_MANAGER_CONFIGURE`) before it sends a setpoint, and the aircraft reports it in control. Other ground stations can still move the gimbal without doing so. A gimbal that cannot yaw (a Mini) simply holds its yaw: if DJI refuses a rotation only because of its yaw, the pitch is applied on its own.

**Camera.** Start and stop recording from QGC's camera controls. Because the aircraft reports its camera in video mode, QGC's photo button saves a still from the video stream on the ground station rather than triggering the aircraft's shutter; for a photo on the aircraft, use the dashboard's **Photo** button or `/send/capture` on the [HTTP API](/http-api/).

## Using MAVSDK

MAVSDK's Action and Telemetry plugins drive the aircraft as a PX4 vehicle: `arm()` (a no-op — DJI
arms on take-off), `takeoff()`, `goto_location()`, `return_to_launch()`, and the position, GPS,
battery, home and landed-state streams. Two Lyrebird specifics:

- `set_takeoff_altitude()` writes PX4's `MIS_TAKEOFF_ALT` parameter, which Lyrebird does not have.
  The aircraft takes off to DJI's default height; give the height to the following `goto_location()`.
- `goto_location()` takes an "absolute" altitude, but Lyrebird's altitudes are heights above take-off
  (see [Altitude](#two-conventions-worth-knowing)), so pass the height as it is.

The Python package with MAVSDK's familiar async API is `mavsdk-grpc` (tested with 3.17.4); the plain
`mavsdk` package switched to a different API with version 4. The flight examples in
`GroundStation/examples/` include a complete MAVSDK flight — see [Ground Station](/groundstation/#flight-examples).

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
