# Flight examples

Five scripts that fly the same short flight, each through a different interface:

**take off → fly 15 m north at 10 m height → return home**

Do the steps below once, in order. Every command runs on your computer, **from the repository
root** — the script paths are relative to it, so start every session with:

```bash
cd ~/Dev/Lyrebird/lyrebird        # wherever you cloned the repository
```

Running from anywhere else fails with `can't open file '.../GroundStation/examples/...'`.

## 1. Install (once per computer)

```bash
python3 -m venv ~/.venvs/lyrebird-examples
~/.venvs/lyrebird-examples/bin/pip install requests pymavlink mavsdk-grpc==3.17.4
```

This makes a separate Python environment for the examples. (Ubuntu refuses `pip install` into its
own Python, which is why the examples get their own.)

## 2. Before every flight

- The RC and your computer are on the **same Wi-Fi**, and the Lyrebird app is open on the RC.
- The drone is **on the ground, outside, with a GPS fix** (home point set in the app).
- The **return-to-home height** suits the site — every script ends with return home, and the drone
  climbs to that height first.
- The app's **MANUAL/AUTO** toggle shows **AUTO**. In MANUAL the app refuses autonomous commands.
- **Your hand is on the RC sticks.** Moving them takes over from the script at any time (and
  switches the app to MANUAL).

Find the RC's IP address: it is shown in the app, or, with the dashboard running,
`curl http://localhost:8090/api/drones`.

## 3. Dry run — checks only, nothing moves

```bash
~/.venvs/lyrebird-examples/bin/python GroundStation/examples/http_raw.py --ip <RC_IP>
```

You should see:

```text
Aircraft mini8 at 192.168.50.75 over HTTP
Plan: take off, fly 15 m north at 10 m, come home.
Pre-flight checks passed. Dry run: add --fly to fly.
```

If it prints **Not flying** instead, it lists why — no GPS fix, battery too low, the app in manual
override — fix that and run it again.

## 4. Fly

The same command with `--fly`:

```bash
~/.venvs/lyrebird-examples/bin/python GroundStation/examples/http_raw.py --ip <RC_IP> --fly
```

It takes off (DJI's automatic take-off, to about 1 m), waits until the drone hovers, flies to the
point while climbing to the height, prints `Arrived.`, and sends the drone home. To stop early press
**Ctrl+C** — the drone returns home — or take over with the sticks.

## 5. The other scripts

Repeat steps 3 and 4 with each script. Recommended order:

| Script | Interface | Command (add `--fly` to fly) |
|---|---|---|
| `http_raw.py` | plain HTTP | `~/.venvs/lyrebird-examples/bin/python GroundStation/examples/http_raw.py --ip <RC_IP>` |
| `python_client.py` | Lyrebird's Python client | `PYTHONPATH=GroundStation/Python ~/.venvs/lyrebird-examples/bin/python GroundStation/examples/python_client.py --ip <RC_IP>` |
| `mavlink_pymavlink.py` | raw MAVLink | `~/.venvs/lyrebird-examples/bin/python GroundStation/examples/mavlink_pymavlink.py --ip <RC_IP>` |
| `mavlink_mavsdk.py` | MAVSDK | `~/.venvs/lyrebird-examples/bin/python GroundStation/examples/mavlink_mavsdk.py --ip <RC_IP>` |

These four were flown with a DJI Mini 4 Pro on 2026-10-14. `mavlink_mavsdk.py` needs an app build
that reads MAVSDK's mode changes (from 2026-10-14 on); with an older one it stops with `UNSUPPORTED`.

### ROS 2: `ros2_flight.py`

The same flight through ROS 2, using the `lyrebird_controller` node and `lyrebird_msgs` (PX4-style
topics, not `px4_msgs` — the ROS 2 page of the docs has the comparison). Flown with a DJI Mini 4
Pro on 2026-10-15, in the Jazzy container.

It runs inside the ground-station container, where `lyrebird_msgs` is built. The container must be
built from this checkout, so it includes the current Python client:

1. Stop the dashboard's ROS node if the video stack is running — it uses the same MAVLink port and
   ROS domain: `docker compose -f GroundStation/video_test/compose.yaml stop ros-monitor`
2. Terminal 1, start the container (it finds the aircraft and starts its controller under the
   aircraft's name): `ROS_DISTRO=jazzy ./GroundStation/run_docker.sh`
3. Terminal 2, from the repository root (`--drone` is the aircraft's name in the app; add `--fly`
   after it to fly):

   ```bash
   docker exec -i $(docker ps -q --filter ancestor=lyrebird-gs) bash -c \
       'source /opt/ros/$ROS_DISTRO/setup.bash && source /ros2_ws/install/setup.bash && \
        python3 - --drone mini7' < GroundStation/examples/ros2_flight.py
   ```

Afterwards: `docker compose -f GroundStation/video_test/compose.yaml start ros-monitor`.

Optional arguments for all of them: `--distance 15` (metres north), `--height 10` (metres above
take-off), `--speed 3` (m/s; not in the MAVSDK one).

## If something goes wrong

| You see | Do |
|---|---|
| `can't open file '.../GroundStation/examples/...'` | You are not in the repository root: `cd ~/Dev/Lyrebird/lyrebird` and run it again. |
| `No aircraft found` / `No MAVLink vehicle answered` | Wrong IP, or the RC is on another network. Check step 2. |
| `Not flying: ... manual override is on` | The sticks were moved during an earlier autonomous flight and the app now refuses autonomous commands. Switch the **MANUAL/AUTO** toggle in the app back to **AUTO**. |
| `DJI status: CHLSTATUS_POOR` (or another name) | DJI's warning banner, shown for information — `CHLSTATUS_POOR` is a weak RC-to-drone link. The script still flies; DJI refuses take-off itself if the problem is serious. |
| `Return to home was not confirmed`, or `DENIED` after `Returning home.` | The app is in MANUAL (you switched it, or moved the sticks), so it refuses the return. Bring the drone home with the RC, then switch back to **AUTO**. |
| `UNSUPPORTED` from `mavlink_mavsdk.py` | The app on the RC is too old for MAVSDK's mode changes. Install a current build. |
| `... did not happen within ... s` | The script gave up waiting and sent the drone home. Note the time and keep the capture. |
| `python_client.py` and QGroundControl on the same computer | Run it as `LB_TRANSPORT=http PYTHONPATH=GroundStation/Python ...` so the two don't share UDP port 14550. |

To find out afterwards what happened, keep a capture running during the flight:
`sudo tcpdump -i <wifi interface> -w ~/flight.pcap udp port 14550`.

## Which one to build on

- **`http_raw.py`** — plain HTTP and a JSON telemetry stream. Read this one to write a client in
  any other language.
- **`python_client.py`** — the shortest Python: Lyrebird's client does the HTTP/MAVLink work.
- **`mavlink_pymavlink.py`** — exactly what goes over MAVLink, the same commands QGroundControl
  sends. Useful for debugging and other MAVLink tools.
- **`mavlink_mavsdk.py`** — MAVSDK, the usual library for autonomy software on MAVLink drones.
- **`ros2_flight.py`** — ROS 2, for a robotics stack; the topic layout is PX4's.

Heights in all of them are **above the take-off point**: Lyrebird reports 0 m on the ground.
