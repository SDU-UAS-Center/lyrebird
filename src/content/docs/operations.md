---
title: Logs & Troubleshooting
description: Flight logging locations, common connection issues, and the repository layout.
breadcrumb: Operations
---

## Flight logging

Lyrebird logs flight data in JSONL format.

Storage locations are checked in order:

1. Removable microSD card: `Lyrebird/FlightLogs/YYYY-MM-DD/HH-mm-ss_<drone>.jsonl`
2. Documents folder: `Documents/lyrebird/FlightLogs/YYYY-MM-DD/`
3. App-external fallback: `Android/data/<pkg>/files/FlightLogs/YYYY-MM-DD/`

DJI SDK TXT flight records are copied to `Lyrebird/DJI_FlightRecords/` on app launch and after landing so they survive app reinstalls.

## Troubleshooting

**Connection refused:**

- Verify the Lyrebird app is running on the RC (servers start on launch).
- Check the RC is on the same LAN as the GS.
- Test with `curl http://{RC_IP}:8080/config` and `nc {RC_IP} 8081`.

**Drone does not respond to navigation commands:**

- Press **Enable Virtual Stick** in the app or call `/send/enableVirtualStick`.
- Check `isManualOverrideActive` in telemetry; call `/send/deactivateManualOverride` if needed.

**App shows "No drone detected" while a DJI app sees the aircraft:**

The RC connects to Android as a **USB accessory**, and only one app can hold it open at a time.
Opening DJI Fly (or any other DJI app) takes it: Lyrebird's SDK still registers, but no product
appears, so the aircraft link reads as dead — and the DJI app seeing the drone makes it look like
Lyrebird's fault.

- Close the other DJI app yourself — swipe it out of Recents, or use **App info → Force stop**.
  The home screen notices this state and says so; its button is **Re-open app**, which is the half
  it can do reliably (a fresh process re-attaches the SDK to the freed accessory).
- Order matters: close the other app **first**, then press Re-open. Re-opening while it still holds
  the accessory changes nothing, and the app says so again rather than pretending to have fixed it.
- Android will not kill a foreground app, and some OEM builds (OnePlus/ColorOS seen on the bench)
  restart DJI Fly in the background — it re-took the RC mid-session even after both approaches.
  On bench phones, disable DJI Fly's auto-start or keep it uninstalled.
- When Android asks which app should open the RC, choose Lyrebird and **Always**, so the chooser
  does not hand it to DJI Fly next time.
- Re-plugging the RC's USB-C cable also re-attaches the accessory.
- Over MAVLink this state is visible from the ground station as heartbeats with nothing else:
  `python GroundStation/Python/test_scripts/field_check.py <RC_IP>` reports telemetry arriving
  but no `batteryLevel`/`attitude`.

**Video not connecting:**

- Start the video-test stack with `docker compose -f GroundStation/video_test/compose.yaml up -d --build`.
- Open <http://localhost:8090> and verify the phone telemetry connection is active.
- Check MediaMTX paths with `curl http://localhost:9997/v3/paths/list`.
- Prefer clean 5 GHz Wi-Fi channels for multiple simultaneous video publishers.
- If the surface encoder is enabled, inspect the dashboard `webRtc.activeCamera`, `webRtc.outputFps`, `webRtc.recoveryCount`, and MediaMTX `inboundFramesInError`. Disable `lb_whip_surface_h264_encoder` in the app settings and restart to compare against the standard encoder.
- A black or frozen surface stream usually points to the camera-surface/MediaCodec handoff rather than WHEP. The standard WHIP encoder is the fallback for aircraft or Android devices where the experimental path is not stable.

**Android build:**

```bash
cd LyrebirdApp/android-sdk-v5-as
./gradlew :app:compileCurrentV5DebugKotlin
./gradlew :app:assembleCurrentV5Debug
./gradlew :app:assembleDemoBiomassV5Debug
```

## Project structure

```text
Lyrebird/
├── LyrebirdApp/
│   ├── android-sdk-v5-as/               # Main Android project (open this in Android Studio)
│   │   └── local.properties             # Place AIRCRAFT_API_KEY here
│   ├── lyrebird-app/                 # Lyrebird Android app (com.lyrebird.rc)
│   │   └── src/main/
│   │       └── java/com/lyrebird/rc/
│   │           ├── FlightDeckActivity.kt
│   │           ├── controller/          # DroneController, PID, autonomy helpers
│   │           ├── logger/              # Flight and DJI record logging
│   │           ├── server/              # HTTP, telemetry, and discovery services
│   │           └── webrtc/              # WHIP/WebRTC video publishing
│   └── android-sdk-v5-uxsdk/            # DJI UXSDK UI components
└── GroundStation/
    ├── Python/
    │   ├── lyrebird_groundstation/    # Canonical DJI client (HTTP + TCP telemetry)
    │   ├── djiInterfaceSafety.py        # Legacy import shim for lyrebird_groundstation.safety
    │   └── test_scripts/                # Authority and capture/download test scripts
    ├── Dockerfile                       # ros:humble + CycloneDDS container
    ├── entrypoint.sh                    # Container entry point
    ├── run_docker.sh                    # Docker run helper
    ├── video_test/                      # MediaMTX + multi-drone video dashboard
    │   └── compose.yaml                 # MediaMTX + dashboard compose stack
    └── ROS/
        ├── lyrebird_controller/       # ROS 2 control + telemetry node
        ├── lyrebird_videofeed/        # Video feed node
        └── lyrebird_bringup/          # Launch files and config
```
