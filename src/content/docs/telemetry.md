---
title: Telemetry
description: The newline-delimited JSON telemetry stream on TCP port 8081, and drone auto-discovery.
breadcrumb: Interfaces
---

## Telemetry (TCP Socket — Port 8081)

Continuous newline-delimited JSON stream. Connect and read; the app pushes updates automatically.

**Telemetry fields:**

| Field | Type | Description |
|-------|------|-------------|
| `droneName` | `string` | Drone name (set via app UI) |
| `speed` | `{x, y, z}` | Velocity (m/s) |
| `heading` | `float` | Compass heading (degrees) |
| `attitude` | `{pitch, roll, yaw}` | Aircraft attitude (degrees) |
| `location` | `{latitude, longitude, altitude}` | GPS position |
| `phoneLocation` | `{latitude, longitude, heading, pressure, battery, wifiRssi}` | Operator phone/RC location and sensor data |
| `gimbalAttitude` | `{pitch, roll, yaw}` | Gimbal orientation (degrees) |
| `gimbalJointAttitude` | `{pitch, roll, yaw}` | Gimbal joint angles (degrees) |
| `zoomRatio` | `float` | Camera zoom ratio |
| `zoomFl` / `hybridFl` / `opticalFl` | `float` | Focal lengths (-1 if unavailable) |
| `batteryLevel` | `int` | Battery % (0–100) |
| `satelliteCount` | `int` | GPS satellite count |
| `homeLocation` | `{latitude, longitude}` | Home point coordinates |
| `homeSet` | `bool` | Home point set |
| `distanceToHome` | `float` | Distance to home (m) |
| `waypointReached` | `bool` | Final waypoint reached |
| `waypointSeq` / `yawSeq` / `altitudeSeq` | `int` | Sequence id of the waypoint / yaw / altitude command currently being executed. Match against the `seq` returned by the command to avoid acting on a stale reach flag |
| `intermediaryWaypointReached` | `bool` | Intermediate waypoint reached |
| `yawReached` | `bool` | Target yaw reached |
| `altitudeReached` | `bool` | Target altitude reached |
| `isRecording` | `bool` | Camera recording active |
| `flightMode` | `string` | GPS / ATTI / VIRTUAL_STICK / GO_HOME / AUTO_LANDING / WAYPOINT / MANUAL |
| `remainingFlightTime` | `int` | Remaining flight time (s) |
| `timeNeededToGoHome` | `float` | Time to return home (s) |
| `timeNeededToLand` | `float` | Time to land (s) |
| `totalTime` | `float` | Go-home + land time (s) |
| `maxRadiusCanFlyAndGoHome` | `float` | Max safe flyable radius (m) |
| `batteryNeededToGoHome` | `float` | Battery % needed for RTH |
| `batteryNeededToLand` | `float` | Battery % needed to land |
| `remainingCharge` | `int` | Raw remaining battery charge from SDK |
| `seriousLowBatteryThreshold` | `float` | Critical low battery % |
| `lowBatteryThreshold` | `float` | Low battery warning % |
| `isManualOverrideActive` | `bool` | Pilot has taken manual RC control |
| `readyToTakeoff` | `bool` | All preflight conditions satisfied |
| `takeoffBlockReason` | `string` | Why takeoff is blocked when `readyToTakeoff` is false |
| `lrfTarget` | `{latitude, longitude, altitude}` | Last geo-referenced laser rangefinder target, `null` until the laser locks |
| `autoSensingActive` | `bool` | On-device target detection running |
| `detectedTargets` | `array` | Detected targets from auto-sensing |
| `webRtc` | `object` | WHIP/WebRTC sender state, FPS, processing, drop, error, bitrate, and recovery metrics when video is active; `activeCamera` and `scaleMode` identify the surface path |

### Reading video metrics

The `webRtc` object lets the dashboard distinguish a source/encoder problem from a network problem:

| Field | Meaning |
|---|---|
| `activeCamera` | `surface` for the experimental DJI-to-MediaCodec path; the standard path reports its active DJI/phone/mock source |
| `scaleMode` | `surface` when MediaCodec receives the DJI surface directly; `native` or `fixed` for the regular frame-source path |
| `inputFps` / `outputFps` | Frames entering and leaving the sender path |
| `totalDroppedFrames` / `droppedFps` | Frames discarded by source pacing or backpressure |
| `qualityLimitationReason` | WebRTC's sender-side reason for limiting quality, when reported by the platform |
| `framesEncodedNotSent` | Encoded frames waiting behind the network sender |
| `sendBitrateBps` | Approximate outbound WebRTC bitrate |
| `recoveryCount` | Number of source-pipeline recovery attempts, including keyframe recovery after queue pressure |

On the surface path, a rising `recoveryCount`, falling `outputFps`, or non-zero `framesEncodedNotSent` is a reason to compare the same run with `lb_whip_surface_h264_encoder=false`. The comparison keeps the WHIP, MediaMTX, and browser path constant while changing only the camera-to-encoder stage.

## Drone Identity & Auto-Discovery

- **Custom naming**: Set drone name via the app UI (tap the name display). Examples: `"RedScout"`, `"Bravo"`.
- **UDP broadcast discovery**: `DJIInterface("")` broadcasts `DISCOVER_LYREBIRD` on port 30000; the app replies `LYREBIRD_HERE:{ip}`.
- **UDP multicast discovery**: The app announces over `239.255.42.99:30001` for LANs where multicast is available.
- **mDNS/Bonjour**: Lyrebird advertises `_lyrebird._tcp.` with service metadata.
- **Config endpoint**: `/config` returns drone name and connection metadata (used by ROS auto-discovery and the dashboard).
- **Dynamic ROS namespaces**: Nodes launch under the drone's name (e.g., `/RedScout/location`), eliminating manual IP-to-name mapping.
