---
title: HTTP API
description: Control and status endpoints on port 8080, plus the two-computer safety authority model.
breadcrumb: Interfaces
---

All commands are HTTP POST requests to port 8080 under `/send/…`. Status is read through HTTP GET endpoints and the TCP telemetry stream.

HTTP runs alongside [MAVLink 2](/mavlink/), on by default. It's kept for compatibility with ground stations built against this project's predecessor, WildBridge; it covers what MAVLink doesn't yet (AI detections, live settings); and it's the fast path for big transfers — HTTP uses as much of the Wi-Fi link as it can, which is what media and video need, where MAVLink FTP stays deliberately slow and lightweight so it doesn't crowd the radio spectrum a swarm shares.

## Two-Computer Safety Authority

Two computers can drive the same drone over HTTP. Which one is in command is decided purely by the
`X-Safety-Token` header on each request:

| Request | Classified as |
|---------|---------------|
| carries the valid `X-Safety-Token` | **Safety Computer** |
| anything else (no header, wrong token) | **Pilot Computer** |

![Two-Computer Safety Control — request flow](../../../docs/images/two-computer-safety-control-Request%20flow.drawio.png)

*How a single HTTP command is arbitrated: the `X-Safety-Token` header classifies the request, `ControlAuthority` holds one in-memory latch, and the first Safety command seizes control and cancels whatever the Pilot was flying.*

Rules enforced by the app on every `/send/*` command:

- The Pilot Computer holds control at startup and flies the mission normally.
- The **first** command from the Safety Computer latches authority to SAFETY, cancels whatever
  autonomous loop the Pilot left running, and holds the aircraft in place.
- From then on every Pilot command is rejected with
  `REJECTED: Safety Computer is in control. Pilot commands are blocked.`
- The takeover is **persistent** — there is no timeout. If the Safety Computer goes silent, the
  Pilot does *not* regain control.
- The only way back is `POST /releaseSafetyControl`, which only the Safety Computer may call.
- An app restart resets to Pilot control ("restart == fresh mission"). State is in-memory only.

While the Safety Computer holds authority, a red **SAFETY COMPUTER IN CONTROL** banner is shown
over the video feed. Normal Pilot control shows no banner.

This axis is independent of the RC manual-override latch: that one tracks the physical pilot on
the sticks, this one tracks which computer commands the server.

```python
from lyrebird_groundstation.safety import DJIInterfaceSafety

safety = DJIInterfaceSafety("192.168.1.100")   # every command carries the token
safety.requestSendGotoAltitude(30.0)           # first command seizes control
safety.requestReleaseSafetyControl()           # hand authority back to the Pilot
```

| Method & Endpoint | Body | Description |
|-------------------|------|-------------|
| <span class="http-method post">POST</span>`/releaseSafetyControl` | — | Return authority to the Pilot Computer (Safety Computer only) |

## Control Endpoints (HTTP POST — Port 8080)

| Method & Endpoint | Body | Description |
|-------------------|------|-------------|
| <span class="http-method post">POST</span>`/send/takeoff` | — | Takeoff |
| <span class="http-method post">POST</span>`/send/land` | — | Land |
| <span class="http-method post">POST</span>`/send/RTH` | — | Return to home (aborts active mission + disables VS first) |
| <span class="http-method post">POST</span>`/send/enableVirtualStick` | — | Enable Virtual Stick mode |
| <span class="http-method post">POST</span>`/send/abortMission` | — | Stop mission + disable Virtual Stick |
| <span class="http-method post">POST</span>`/send/abortAll` | — | Stop all active missions (DJI native + Virtual Stick) |
| <span class="http-method post">POST</span>`/send/abort/DJIMission` | — | Stop DJI native mission only |
| <span class="http-method post">POST</span>`/send/stick` | `leftX,leftY,rightX,rightY` | Direct AVS velocity input (values ∈ [-1, 1]) |
| <span class="http-method post">POST</span>`/send/gotoWaypointNoseForward` | `lat,lon,alt,yaw[,speed]` | Turn to face the leg, fly forward, rotate to `yaw` on arrival (`yaw` = final heading) |
| <span class="http-method post">POST</span>`/send/gotoWaypointHoldHeading` | `lat,lon,alt,yaw[,speed]` | Hold `yaw` for the whole flight (drone crabs sideways), tighter arrival tolerance |
| <span class="http-method post">POST</span>`/send/navigateTrajectoryDJINative` | `speed;lat,lon,alt;…` | DJI native KMZ mission (≥ 2 waypoints) |
| <span class="http-method post">POST</span>`/send/gotoYaw` | `yaw_degrees` | Rotate to heading |
| <span class="http-method post">POST</span>`/send/gimbal/rel_pitch` | `roll,pitch,yaw` | Gimbal pitch **relative** to current angle (degrees) |
| <span class="http-method post">POST</span>`/send/gimbal/rel_yaw` | `roll,pitch,yaw` | Gimbal yaw **relative** to current angle (degrees) |
| <span class="http-method post">POST</span>`/send/captureThermalImage` | — | Capture on the thermal lens. Returns a JSON capture descriptor |
| <span class="http-method post">POST</span>`/send/captureTemperature` | — | Read the thermal max temperature. Returns `{"thermalMaxTemp": <float or null>}` |
| <span class="http-method post">POST</span>`/send/listMedia` | — | List every file on the SD card as JSON |
| <span class="http-method post">POST</span>`/send/downloadMediaByName` | `<fileName>` | Download one media file by name; responds with the raw file bytes |
| <span class="http-method post">POST</span>`/send/lrf/measure` | — | Fire the laser rangefinder. Returns distance, geo-referenced target, and laser state as JSON. `distance` and target are non-null only when the laser locks (state `NORMAL`, needs a GPS fix) |
| <span class="http-method post">POST</span>`/send/drop` | — | Release the payload on airframes with a drop port configured in the active profile; rejected otherwise |
| <span class="http-method post">POST</span>`/send/gotoAltitude` | `altitude_m` | Change altitude |
| <span class="http-method post">POST</span>`/send/gimbal/pitch` | `roll,pitch,yaw` | Set gimbal pitch |
| <span class="http-method post">POST</span>`/send/gimbal/yaw` | `roll,pitch,yaw` | Set gimbal yaw joint angle |
| <span class="http-method post">POST</span>`/send/camera/zoom` | `zoom_ratio` | Set camera zoom |
| <span class="http-method post">POST</span>`/send/camera/startRecording` | — | Start recording |
| <span class="http-method post">POST</span>`/send/camera/stopRecording` | — | Stop recording |
| <span class="http-method post">POST</span>`/send/setRTHAltitude` | `altitude_m` | Set RTH altitude |
| <span class="http-method post">POST</span>`/send/deactivateManualOverride` | — | Re-enable autonomous commands after pilot override |
| <span class="http-method post">POST</span>`/send/autoSensing/start` | — | Enable DJI AutoSensing object detection where supported |
| <span class="http-method post">POST</span>`/send/autoSensing/stop` | — | Disable DJI AutoSensing object detection |

## Status Endpoints (HTTP GET — Port 8080)

| Method & Endpoint | Returns | Description |
|-------------------|---------|-------------|
| <span class="http-method get">GET</span>`/config` | JSON | Drone name, IP, HTTP/telemetry ports, and current video mode |
| <span class="http-method get">GET</span>`/get/isManualOverrideActive` | JSON/text | Manual override state |
| <span class="http-method get">GET</span>`/get/autoSensing/status` | JSON | AI detection status and target count |
| <span class="http-method get">GET</span>`/get/autoSensing/targets` | JSON | Current detected targets with bounding boxes |

> All other flight state data is available via the TCP telemetry stream on port 8081. Use `GET /config` for connection metadata and auto-discovery.
