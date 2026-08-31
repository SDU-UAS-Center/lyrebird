---
title: Android App
description: What the Lyrebird Android app is, everything it can do on the aircraft, and how to build, install, and run it on a DJI RC or a phone connected to one.
breadcrumb: Start here
---

The Android app is the part of Lyrebird that actually runs in the field: a Kotlin app (DJI Mobile SDK V5) that runs on Android, not on a separate ground-station computer. Which Android device that is depends on the controller — the DJI RC Pro and RC Plus have Android built in, so Lyrebird installs directly on the controller; the RC-N3 has no display or OS of its own, so Lyrebird instead runs on the Android phone connected to it. Either way, launch it and that device becomes a networked drone server — every other piece of Lyrebird (the Python client, ROS 2, QGroundControl, the video dashboard) talks to *this* app over Wi-Fi.

## Architecture: what actually runs on the RC

Everything below starts automatically the moment the app launches — there is no "enable the server" step. Concretely, the process that comes up is:

| Service | Port / channel | Started | Purpose |
|---|---|---|---|
| HTTP command & status server | TCP 8080 | Always | `POST /send/...` commands, `GET /config` and status endpoints — see [HTTP API](/http-api/) |
| TCP telemetry stream | TCP 8081 | Always | Newline-delimited JSON, one line per tick, to every connected socket — see [Telemetry](/telemetry/) |
| MAVLink 2 endpoint | UDP 14550 | On by default (`lb_mav_0_enabled`) | Full MAVLink 2 vehicle: telemetry, commands, missions, parameters, FTP — see [MAVLink 2](/mavlink/) |
| UDP discovery responder | UDP 30000 | Always | Answers broadcast discovery requests with the aircraft's name and IP, plus mDNS and subnet-scan fallbacks |
| WHIP video publisher | via MediaMTX | Once a ground station connects | Publishes the DJI camera feed for WHEP playback — see [Ground Station](/groundstation/#groundstation-video-dashboard) |

Two of these are worth calling out specifically because of how they behave when nobody is using them: the MAVLink endpoint never broadcasts its full telemetry stream onto the subnet — only its 1&nbsp;Hz heartbeat does, until a real ground station has been heard from — and the TCP telemetry stream sends nothing at all to sockets nobody has opened. An idle aircraft with both protocols enabled costs the network almost nothing.

## Core capabilities

### Flight and navigation

Take-off (to a requested altitude), land, return-to-home (which first aborts any running mission and disables Virtual Stick), abort (mission only, Virtual Stick only, or both), direct heading and altitude changes, and two waypoint-navigation modes: **nose-forward** (the aircraft turns to face the leg, flies it, then rotates to the final heading on arrival) and **hold-heading** (the nose stays on the commanded heading the whole way, so the aircraft crabs sideways, with a tighter arrival tolerance). Raw virtual-stick input (`leftX/leftY/rightX/rightY`, saturated to ±0.3) is also exposed directly, for a ground station that wants to fly the low-level control loop itself. Every one of these has both an HTTP form and a MAVLink form — see the command tables in [HTTP API](/http-api/) and [MAVLink 2](/mavlink/).

### Missions

A ground station (QGroundControl, MAVSDK, or the HTTP `navigateTrajectoryDJINative` endpoint) uploads a plan and the app flies it on one of two executors, chosen by the `lb_mission_exec` preference: DJI's own native wayline engine (survives the phone losing focus or being backgrounded mid-flight — the default), or Lyrebird's own onboard PID sequencer (needed for behavior DJI's wayline engine can't express, such as a continuously-tracking region of interest). Per-waypoint hold time, acceptance radius, pass-through vs. settle, heading mode, speed, and camera/gimbal actions are all honoured. See [Missions](/missions/) for exactly how each field maps onto the two executors.

### Camera, gimbal, and payload

Zoom (absolute ratio), start/stop recording, gimbal pitch and yaw control (both absolute and relative to the current angle), thermal image capture and max-temperature readout on thermal-equipped airframes, laser-rangefinder measurement (distance plus a geo-referenced target when the laser locks with a GPS fix), and payload release on airframes with a drop port configured in their active control profile. Media (photos, thermal captures, videos) can be listed and downloaded from the SD card over HTTP — deliberately kept off MAVLink FTP, which is too slow for multi-megabyte files by design; see [why](/mavlink/#why-bulk-media-stays-on-http).

### Detection

Two independent object-detection sources, switchable from the in-app menu: DJI's own onboard **AutoSensing**, or a **YOLO model running on the phone/RC** itself. Whichever is active, detections are reported the same way on both wires — the `detections`/`detectedTargets` telemetry keys over HTTP, and `AUTOSENSING_STATUS`/`AUTOSENSING_TARGET` custom MAVLink messages.

### Video

WHIP publishing to MediaMTX is the supported public path (browser playback through WHEP — see [Ground Station](/groundstation/#groundstation-video-dashboard)). The app has two WHIP encoder paths and several alternative DJI streaming modes:

| Option | Source and route | Strength | Tradeoff |
|---|---|---|---|
| WHIP / WebRTC, standard encoder | DJI raw frame callback -> libwebrtc H264 -> WHIP -> MediaMTX -> WHEP | Default and most portable path | Uses CPU for raw-frame conversion and encoding |
| WHIP / WebRTC, surface H264 | DJI camera surface -> Android MediaCodec hardware H264 -> WHIP -> MediaMTX -> WHEP | Less CPU-side pixel work and a more direct native-resolution path | Experimental; device and aircraft surface support must be tested |
| DJI direct RTSP pull | DJI's always-on RTSP server at `rtsp://<phone-ip>:8554/streaming/live/1` | No setup and useful for the ROS video node | Field tests show much higher latency, up to about 10 seconds |
| RTSP push, RTMP, Agora.io, or GB28181 | DJI's internal live-streaming implementation | Integrates with systems that already require one of these protocols | DJI owns the encode/mux/transport path, so latency and tuning are less controllable |

The surface encoder is selected by `lb_whip_surface_h264_encoder`, or from the Surface H264 toggle in the Stream / WebRTC settings. It is off by default for a fresh installation and requires an app restart after changing. This preference changes only the encoder underneath WHIP; the public WHIP and WHEP URLs do not change. The standard raw-frame/libwebrtc path remains the fallback when the surface path is unavailable or unsuitable for a particular aircraft.

The surface pipeline is deliberately asynchronous: DJI writes into a `MediaCodec` input surface, MediaCodec emits H264 buffers on a drain thread, and Lyrebird forwards owned copies to WebRTC. Codec configuration (SPS/PPS), timestamps, keyframe recovery, and bounded queue handling are required for a decoder to recover cleanly after pressure or a late join. This is why the option can reduce CPU work without changing the dashboard, MediaMTX, or WHEP side of the system.

Separately, and needing no configuration at all, DJI's SDK runs its own RTSP server on the phone the whole time the app is running: `rtsp://<phone-ip>:8554/streaming/live/1` (`lyrebird_videofeed`'s ROS 2 node pulls exactly this). It's the simplest way to get a picture with zero setup, but it is noticeably higher latency than the WHIP/MediaMTX path — up to around 10 seconds in field testing, versus typically under a second through WHIP/WHEP (which can drop frames instead, rather than accumulate delay). See [Why MediaMTX?](/groundstation/#why-mediamtx) for the full comparison.

### Safety and identity

A Safety Computer can seize command authority from the Pilot Computer at any time — over HTTP via the `X-Safety-Token` header, over MAVLink via [packet signing](/mavlink/#how-packet-signing-works) — and only it can hand control back; the takeover is persistent and shown on screen with a red **SAFETY COMPUTER IN CONTROL** banner. See [the two-computer safety model](/http-api/#two-computer-safety-authority). Separately, UDP broadcast auto-discovery (port 30000), mDNS, and subnet scanning mean a ground station never has to be told the aircraft's IP by hand, and every command executed over either wire is written to the on-device flight log (JSONL; see [Logs & Troubleshooting](/operations/#flight-logging) for where).

## Supported hardware

DJI Mini 3 / Mini 4 Pro, Mavic 3 Enterprise, Matrice 30 / 300 RTK / 350 RTK / 4 Thermal — flown from the DJI RC Pro, RC Plus, or RC-N3. The RC Pro and RC Plus have Android built in, so the app installs directly on the controller; the RC-N3 has no display or OS of its own, so the app runs instead on the Android phone connected to it. See DJI's [Mobile SDK compatibility list](https://developer.dji.com/doc/mobile-sdk-tutorial/en/) for the full picture of what the underlying SDK supports.

### Control profiles

Each supported airframe gets its own control profile — distinct max speed/acceleration, distance-controller PID gains, yaw rate limit, and (where applicable) payload-drop wiring — auto-selected from the aircraft's detected product type the moment it connects, with the Mavic 3 Enterprise profile as the fallback for anything unrecognized. The GroundStation [Settings tab](/groundstation/#groundstation-video-dashboard) shows which profile is active per drone without needing to open the app on the phone.

The distance controller's integral gain is pinned to zero on every profile, deliberately: distance-to-waypoint is sign-definite (it's never negative), so an integral term can only wind up as the drone approaches and never unwind — it would dump accumulated output into the controller right as the aircraft nears the waypoint, overshooting it. Proportional and derivative gains alone avoid that failure mode.

Payload-drop wiring also differs by hardware generation, not just by whether a drop port exists at all: the Matrice 300/350's SkyPort release payload (TH4) is armed and fired through config-interface switch/button indices 3 and 5, while the Matrice 400 and the original PORT_3-based rig use indices 0 and 1. Getting this wrong doesn't fail loudly — it fires the wrong widget on the SkyPort — which is why it lives per-profile instead of as one global constant.

## Install and build

### Prerequisites

1. A DJI drone plus a compatible RC, and a 5 GHz Wi-Fi access point for it to join.
2. [Android Studio Quail 3 (2026.1.3) or newer](https://developer.android.com/studio) — the project builds with Android Gradle Plugin 9.3.2 and Gradle 9.7.1, which need a current release.
3. A DJI developer account and API key from [developer.dji.com](https://developer.dji.com/).

### Build in Android Studio

```bash
git clone https://github.com/SDU-UAS-Center/lyrebird.git
```

1. Open `Lyrebird/LyrebirdApp/android-sdk-v5-as` in Android Studio.
2. Copy the local-config template and set your Android SDK path:

   ```bash
   cd Lyrebird/LyrebirdApp/android-sdk-v5-as
   cp local.properties.example local.properties
   ```

   ```properties
   sdk.dir=/home/your-user/Android/Sdk
   ```

3. Add your DJI API key to the same file:

   ```properties
   AIRCRAFT_API_KEY="Your_App_Key"
   ```

4. Enable Developer Mode and USB Debugging on the RC, then build and deploy from Android Studio.

### Build from the command line

```bash
cd Lyrebird/LyrebirdApp/android-sdk-v5-as
./gradlew :app:assembleCurrentDebug        # the "current" variant
./gradlew :app:assembleDemoBiomassDebug    # the "demo_biomass" variant
```

The two Gradle product flavors are the same app: `current` is the default, and `demo_biomass` only differs by application-id suffix and (optionally) a separate `AIRCRAFT_API_KEY_DEMO_BIOMASS` key, for running a demo build side by side with a production install on the same device.

Debug APKs land at:

```text
LyrebirdApp/lyrebird-app/build/outputs/apk/current/debug/Lyrebird-debug.apk
LyrebirdApp/lyrebird-app/build/outputs/apk/demoBiomass/debug/Lyrebird-debug.apk
```

With a device connected over ADB, `auto_install_on_connect.sh` builds, picks the right APK, and installs it in one step:

```bash
./auto_install_on_connect.sh current --build
./auto_install_on_connect.sh demo_biomass --build
./auto_install_on_connect.sh current --check   # just report which APK would be used
```

## Running it on the RC

Launch the app on the default layout and every service in the architecture table above comes up on its own — nothing to enable by hand for a first connection.

**Finding the aircraft.** The Device IP is shown on screen, returned by `GET /config`, or found automatically by a ground station's discovery step (`discover_drone()` in the Python client, or QGroundControl's own UDP listener for MAVLink).

**The settings menu** (the gear icon next to the drone name) covers everything meant to be changed in the field without a computer:

| Item | What it does |
|---|---|
| Change Drone Name | Renames the aircraft — also used to derive its MAVLink system id and ROS namespace |
| Configure Stream/WebRTC… | Picks the streaming protocol (WHIP/WebRTC, RTSP, RTMP, Agora.io, or GB28181) and its connection details |
| Detection source toggle | Switches onboard object detection on/off (checkable, reflects current state) |
| Detection Settings… | Picks the detection source (None / DJI onboard / YOLO on phone) and its confidence threshold |
| MAVLink Flight Allowed toggle | Turns `lb_mav_0_allow_flight` on or off — confirmed with a dialog when turning it *on*, since that lets any MAVLink ground station command takeoff, landing, RTH, and missions; turning it off is immediate |
| Format SD card / Format Internal | Wipes the corresponding storage location, reported with its live free-space status in the menu label |

**Before flying by navigation command** (`gotoWaypoint*`, virtual-stick input, or the equivalent MAVLink commands), Virtual Stick mode needs to be enabled — either with `POST /send/enableVirtualStick`, its MAVLink form, or from the DJI stock flight-control panel the app embeds.

**While a Safety Computer holds control**, a red **SAFETY COMPUTER IN CONTROL** banner appears over the video feed; normal Pilot control shows no banner. See [the two-computer safety model](/http-api/#two-computer-safety-authority) for exactly how authority is decided.

## Advanced / field configuration

Most day-to-day settings live in the in-app menu above, but the MAVLink endpoint's own preferences — its host/port, stream profile, system id, signing key, and mission executor — are plain Android shared preferences rather than in-app UI, on the theory that a second control surface onto a flying aircraft is worth configuring deliberately rather than through a menu tap. In the field, that means reading and writing them through `adb shell` (`run-as` on a debug build) or restoring a prepared settings-backup file — not tapping through a settings screen. The full preference table — names, types, and defaults — lives on the [MAVLink 2](/mavlink/#protocols-implemented) page; the one every field operator eventually touches is `lb_mav_0_allow_flight`, which is *also* reachable from the in-app menu precisely so a fresh install doesn't need a computer to fly again.

## Next steps

- Fly from QGroundControl or MAVSDK: [MAVLink 2](/mavlink/).
- Drive it from a script: [Ground Station](/groundstation/) and [HTTP API](/http-api/).
- Build and fly a plan: [Missions](/missions/).
- Verify a build against a real aircraft: [Field Test](/field-test/).
- Find a flight log or debug a connection issue: [Logs & Troubleshooting](/operations/).
