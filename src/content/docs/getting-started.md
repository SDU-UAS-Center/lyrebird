---
title: Getting Started
description: Install Lyrebird on your DJI RC, build it from source, and connect your first ground station.
breadcrumb: Start here
---

This guide takes you from a stock DJI drone and remote controller to a live telemetry stream and your first command — over MAVLink 2, HTTP, or both, since both are on by default.

## Prerequisites

1. DJI drone + compatible RC, 5 GHz Wi-Fi access point, ground station computer
2. [Android Studio Quail 3 (2026.1.3) or newer](https://developer.android.com/studio) — the project builds with Android Gradle Plugin 9.3.2 and Gradle 9.7.1, which need a current release
3. DJI developer account + API key from [developer.dji.com](https://developer.dji.com/)

## Install the app

```bash
git clone https://github.com/SDU-UAS-Center/lyrebird.git
```

1. Open `Lyrebird/LyrebirdApp/android-sdk-v5-as` in Android Studio.
2. Create `local.properties` from the template and set the Android SDK path for your machine:

   ```bash
   cd Lyrebird/LyrebirdApp/android-sdk-v5-as
   cp local.properties.example local.properties
   ```

   Example for a default Linux Android Studio install:

   ```properties
   sdk.dir=/home/your-user/Android/Sdk
   ```

3. Add your DJI API key to `local.properties`:

   ```properties
   AIRCRAFT_API_KEY="Your_App_Key"
   ```

4. Build and deploy to your RC or Android phone (enable Developer Mode + USB Debugging first).

## Command-line build

```bash
cd Lyrebird/LyrebirdApp/android-sdk-v5-as
./gradlew :app:assembleCurrentDebug
./gradlew :app:assembleDemoBiomassDebug
```

The debug APKs are written to:

```text
LyrebirdApp/lyrebird-app/build/outputs/apk/current/debug/Lyrebird-debug.apk
LyrebirdApp/lyrebird-app/build/outputs/apk/demoBiomass/debug/Lyrebird-debug.apk
```

To build/install a selected variant when an Android device is connected over ADB:

```bash
cd LyrebirdApp/android-sdk-v5-as
./auto_install_on_connect.sh current --build
./auto_install_on_connect.sh demo_biomass --build
```

To only check which APK will be used:

```bash
./auto_install_on_connect.sh current --check
./auto_install_on_connect.sh demo_biomass --check
```

## Start the server

1. Launch the Lyrebird app on the RC — servers start automatically on the default layout.
2. Note the Device IP shown in the app, call `/config`, or use auto-discovery.
3. Press **Enable Virtual Stick** (via `/send/enableVirtualStick` or the app UI) before sending navigation commands.

## Ground station dependencies

```bash
pip install -e GroundStation/Python                     # Python interface
pip install -r GroundStation/ROS/requirements.txt      # ROS 2 interface
```

## Connect and control

Both interfaces are enabled by default — pick whichever fits, or run both side by side.

### MAVLink 2 (QGroundControl, MAVSDK, pymavlink — UDP 14550)

Point QGroundControl at the RC's IP address and it connects automatically — Fly View and Plan view both light up with no plugin and no configuration file. To check the link without QGroundControl:

```bash
pip install pymavlink
lyrebird-mavlink-listen --summary 5
```

This is the lighter-weight path for flight and telemetry. See [MAVLink 2](/mavlink/) for the full command and mission surface.

### HTTP + TCP (any language)

Kept alongside MAVLink for compatibility with ground stations built against this project's predecessor, WildBridge, and as the API for what MAVLink doesn't cover yet — AI detections, live settings, and big transfers. HTTP uses as much of the Wi-Fi link as it can, which is what media and video need; MAVLink FTP is deliberately slow and lightweight instead, so one aircraft's download doesn't crowd the radio spectrum a whole swarm shares.

#### Telemetry (TCP, port 8081)

```python
import socket, json

sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
sock.connect(("192.168.1.100", 8081))
buffer = ""
while True:
    buffer += sock.recv(4096).decode('utf-8')
    while '\n' in buffer:
        line, buffer = buffer.split('\n', 1)
        if line.strip():
            t = json.loads(line)
            print(f"Battery: {t['batteryLevel']}%  Alt: {t['location']['altitude']:.1f}m  Sats: {t['satelliteCount']}")
```

#### Commands (HTTP POST, port 8080)

```python
import requests

rc = "192.168.1.100"
requests.post(f"http://{rc}:8080/send/takeoff")
requests.post(f"http://{rc}:8080/send/gotoWaypointNoseForward", data="49.306254,4.593728,20,90,5.0")
requests.post(f"http://{rc}:8080/send/navigateTrajectoryDJINative",
              data="10.0;49.306,4.593,20;49.307,4.594,25;49.308,4.595,20")
requests.post(f"http://{rc}:8080/send/RTH")
```

### Video (WHIP/WHEP through MediaMTX)

```bash
docker compose -f GroundStation/video_test/compose.yaml up -d --build
```

Open the dashboard at <http://localhost:8090>. When the dashboard connects to a phone telemetry stream, the app builds a WHIP publish URL such as:

```text
http://<ground-station-ip>:8889/<drone_name>/whip
```

MediaMTX exposes the matching browser playback endpoint:

```text
http://<ground-station-ip>:8889/<drone_name>/whep
```

The first WHIP connection uses the standard raw-frame/libwebrtc encoder. The app also has an opt-in Surface H264 mode (`lb_whip_surface_h264_encoder`) that sends DJI camera frames to an Android hardware MediaCodec encoder before the same WHIP request. It can reduce CPU-side pixel conversion, but it is experimental and requires an app restart after enabling it in Stream / WebRTC settings. If the surface path is unstable on a particular aircraft or controller, turn it off and use the standard encoder; the WHIP, MediaMTX, and WHEP URLs stay the same.

The supported public video example is defined by [compose.yaml](https://github.com/SDU-UAS-Center/lyrebird/blob/main/GroundStation/video_test/compose.yaml), [mediamtx.yml](https://github.com/SDU-UAS-Center/lyrebird/blob/main/GroundStation/video_test/mediamtx.yml), and the webapp in `GroundStation/video_test/webapp`. The older direct WebSocket-signaling viewer/server path has been removed from the public app and ground-station tooling.

## Next steps

- Fly from QGroundControl or MAVSDK: [MAVLink 2](/mavlink/).
- Use the high-level Python client instead of raw sockets: [Ground Station](/groundstation/).
- Browse every command endpoint: [HTTP API](/http-api/).
