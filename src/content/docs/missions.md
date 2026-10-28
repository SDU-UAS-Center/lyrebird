---
title: Missions
description: How a QGroundControl-uploaded plan is stored, translated, and flown — either by Lyrebird's own PID sequencer or by DJI's native wayline engine.
breadcrumb: Interfaces
---

Lyrebird accepts a mission the way any MAVLink autopilot does: QGroundControl's Plan view uploads a
list of `MISSION_ITEM_INT` messages, and pressing Start flies it. What happens between those two
moments — how the plan is stored, and how it is actually flown on an airframe with no MAVLink
autopilot underneath — is what this page covers. [Why QGC shows the Plan and Start controls at
all](/mavlink/#why-it-looks-like-px4-to-qgroundcontrol) is a separate, prerequisite trick; this page
assumes that part already works and focuses on the mission itself.

## Flying a plan from QGroundControl

1. Enable `lb_mav_0_enabled` and `lb_mav_0_allow_flight` (both preferences, off by default — see
   [MAVLink 2 Interface](/mavlink/)). The RC and QGC need to share a LAN; QGC listens on UDP 14550.
2. Build a plan in QGC's **Plan** view: waypoints, and optionally a leading **Takeoff**, a trailing
   **Land** or **RTL**, **Camera** (photo/video) items, **Gimbal Pitch** items, a **Change Speed**
   item, and a **ROI Location** item.
3. **Upload** it. Lyrebird's upload handshake (`MavlinkMissionStore`) requests each item, refuses
   the whole transfer if any item's command is not one it understands, and — deliberately — stores
   every accepted item **exactly as MAVLink sent it**, in wire format. Translation to DJI's shape
   happens only when the mission starts, on a copy, which is what lets a later download return
   precisely what was uploaded regardless of how it was flown.
4. Arm and switch to **Mission mode** (or press QGC's **Start Mission**). `SET_MODE(AUTO.MISSION)`
   and the `MISSION_START` that follows both route to whichever executor `lb_mission_exec` selects.
5. Watch progress in QGC as usual: `MISSION_CURRENT` and `MISSION_ITEM_REACHED` are reported by
   both executors, though not with identical precision — see the comparison below.

## Two executors

MAVLink has one mission protocol; Lyrebird has two ways to fly what it uploads. Which one runs is a
preference (`lb_mission_exec`, values `onboard` or `dji_native`), because a ground station has no
MAVLink concept for picking one — there is nothing to negotiate over the wire.

| | `dji_native` | `onboard` (default) |
| --- | --- | --- |
| Who flies it | DJI's own wayline engine, on the flight controller | Lyrebird's app, sequencing legs through its PID waypoint controllers |
| Survives the phone losing focus / being backgrounded | Yes — the mission lives on the aircraft once pushed | No — the sequencing loop lives in the app |
| Take-off, land, RTL | Handled by DJI, from the plan's leading `NAV_TAKEOFF` altitude and trailing `NAV_LAND`/`NAV_RETURN_TO_LAUNCH` | Handled item-by-item like any other plan item |
| Per-item heading (`param4`) | Honoured, as a fixed wayline yaw | Honoured |
| Per-leg speed (`DO_CHANGE_SPEED`) | Honoured, as a per-waypoint speed | Honoured |
| Camera / gimbal actions | **Refused** (see below) | Executed directly, in plan order |
| Distance-triggered capture (`DO_SET_CAM_TRIGG_DIST`) | **Refused** (see below) | Ground covered is accumulated while flying, and the shutter trips each time the distance is crossed |
| Region of interest (`DO_SET_ROI*`) | Static target only — see below | Full continuous tracking |
| Progress reporting | `MISSION_CURRENT` per waypoint reached; no per-item `MISSION_ITEM_REACHED` | Exact `MISSION_ITEM_REACHED` per item |
| `MAV_CMD_SET_CAMERA_MODE` | No wayline equivalent — skipped | Honoured |

**Default is `onboard`, for now.** In field tests with a DJI Mini 4 Pro (October 2026) it flew every
item of a QGroundControl plan — take-off, waypoints, a photo, a video start and stop, return.

**`dji_native` refuses camera and gimbal items.** DJI's wayline generator (wpmzsdk 1.0.5.0 with MSDK
5.18) did not carry them on that aircraft: it kept at most one action per waypoint and none at the
last waypoint, and a waypoint with two actions crashed it. A plan with a photo, video, gimbal or
distance-trigger item is therefore refused rather than flown without its photos, and the aircraft
does not take off. QGroundControl asks for Mission mode before it starts a mission, and that request
has no acknowledgement, so all it shows is **"Unable to start mission: Vehicle not changing to
Mission flight mode"**. If you see that with `dji_native` selected, remove the camera and gimbal
items or switch the executor to `onboard`. The app's log has the reason under the tag
`LyrebirdDefaultLayout` ("Native mission refused: plan item N (command C)"). A ground station that
sends `MISSION_START` itself gets a denied `COMMAND_ACK` and a `STATUSTEXT`: "Camera/gimbal items
need lb_mission_exec=onboard". A plan of waypoints, speed changes and a final land or return flies natively. If building and
uploading the native mission takes longer than 10 seconds, it is not started: by then the ground
station has reported the start as failed, and a take-off that late would surprise the pilot (in a
field test DJI's generator once took 46 s for a three-waypoint plan).
The generator may behave differently with the enterprise aircraft its format names (Matrice,
Mavic 3 Enterprise); that is untested.

`dji_native` keeps the stronger property: the mission lives on the aircraft, so it is not at the
mercy of the app being backgrounded, the phone locking, or Android killing the process mid-flight —
the failure mode that matters most for an unattended or long mission. It is the intended default
once it carries a plan as completely as `onboard`. Until then, choose it for a plan of waypoints
alone where that robustness matters.

**Switching it.** In the app: the settings button at the bottom left of the Flight Deck → first
column, FLEET → **Mission executor**. The choice is read at each mission start, so it applies to the
next plan without restarting the app or uploading again. It is stored as `lb_mission_exec` in the
app's preferences (`onboard` or `dji_native`).

## What the native path translates

> The camera, gimbal and distance-trigger translations below are in the code, but a plan using
> them is currently refused (see [Two executors](#two-executors)): on a Mini 4 Pro, DJI's generator
> did not keep them. They are described for when that changes.

Pushing a plan to DJI's wayline engine means building a
[WPML](https://github.com/dji-sdk/Cloud-API-Doc/blob/master/docs/en/60.api-reference/00.dji-wpml/30.waylines-wpml.md)
mission (a KMZ file), not reusing MAVLink's own item list, so each plan item becomes whichever
WPML construct has the matching meaning:

| MAVLink item | Wayline construct |
| --- | --- |
| `NAV_WAYPOINT` | A `wpml:waypoint`, at the item's lat/lon/alt |
| `NAV_TAKEOFF` (leading item, altitude) | Mission config `securityTakeOffHeight` — DJI climbs to it before flying to the first waypoint, on its own, as part of every wayline mission |
| `NAV_LAND` / `NAV_RETURN_TO_LAUNCH` (trailing item) | Mission config `finishAction`: `autoLand` / `goHome` |
| `param4` (heading) | Waypoint yaw mode `FIXED`, holding that heading |
| `DO_CHANGE_SPEED` | The waypoint's own `speed` field, applying to the legs that follow it, exactly like the onboard executor's running-speed variable |
| `IMAGE_START_CAPTURE` / `VIDEO_START_CAPTURE` / `VIDEO_STOP_CAPTURE` | A `takePhoto` / `startRecord` / `stopRecord` wayline action, triggered on reaching the waypoint the item sits after |
| `DO_GIMBAL_MANAGER_PITCHYAW` | A `gimbalRotate` wayline action, absolute pitch/yaw |
| `DO_SET_ROI_LOCATION` / `DO_SET_ROI` (location mode) | Waypoint yaw mode `towardPOI` + gimbal heading mode `towardPOI`, both pointed at the ROI coordinate — see below |
| `DO_SET_ROI_NONE` / `DO_SET_ROI` (non-location mode) | Clears the active ROI for waypoints that follow |
| `DO_SET_CAM_TRIGG_DIST` | A `MULTIPLE_DISTANCE` trigger whose `distanceInterval` is the item's distance, carrying one `takePhoto` action, attached to the waypoints it covers — see below |
| `SET_CAMERA_MODE` | No wayline equivalent — skipped |

An action item with no later waypoint to attach to (one that sits after the last leg, before a
trailing land) rides along with the last waypoint instead of being lost.

### Distance-triggered capture

`DO_SET_CAM_TRIGG_DIST` says "photograph every N metres". MAVLink expresses that as a plan item and
WPML as a trigger, so the two executors implement it differently and both, in each case, in the form
their engine actually has:

- **`onboard`** accumulates the ground covered between position fixes while a leg is being flown and
  trips one shutter each time the triggered distance is completed, so a survey photographs a grid
  without the plan carrying a photo item per frame.
- **`dji_native`** compiles it into a WPML `MULTIPLE_DISTANCE` trigger on a `takePhoto` action group
  covering the waypoints the item applies to, and DJI's own engine fires it along the span.

Either way the item is modal, exactly like the ROI above: the distance stays in force for everything
after it until another `DO_SET_CAM_TRIGG_DIST` changes it. A distance of zero or less is MAVLink's
way of turning the trigger off again, and is treated as such rather than ignored.

### Region of interest, compiled rather than dropped

MAVLink's ROI is modal: `DO_SET_ROI_LOCATION` puts the vehicle into "point at this coordinate" until
a later `DO_SET_ROI_NONE` (or a non-location `DO_SET_ROI`) clears it — the same kind of state a
ground station itself has to track across a plan. The native translator carries the same state
across the item list while it builds waypoints, and every waypoint built while an ROI is active gets
DJI's own `towardPOI` yaw mode plus `towardPOI` gimbal heading mode, both referencing the same target
coordinate.

That is a real, native DJI mechanism, not an approximation: `towardPOI` computes the aircraft's
heading and the gimbal's pitch/yaw toward the target **continuously while flying the leg**, on the
flight controller, the same way it would for a manually-built wayline mission in DJI's own app — not
a value calculated once and held fixed until the next waypoint. What it cannot do is track a *moving*
target: a wayline mission is compiled once before flight, so an ROI that MAVLink would keep updating
in real time can only be captured as wherever it was when the plan was built. A mission that needs a
moving ROI has to use `onboard`, which re-reads the live ROI command like a normal autonomous action.

## Onboard, for comparison

The `onboard` executor is the simpler of the two conceptually: a loop in the app walks the item list
in order, flies each waypoint through Lyrebird's own PID controllers (`flyToWaypointNoseForward` for
`param4 = NaN`, `flyToWaypointHoldHeading` otherwise), and executes every non-waypoint item in place
— camera, gimbal, ROI, speed change — before moving to the next leg. `NAV_TAKEOFF` blocks the
sequencer until the aircraft is confirmed airborne before it lets the loop continue to the first
waypoint, the same transition [`climbAfterTakeoff`](https://github.com/SDU-UAS-Center/lyrebird/blob/main/LyrebirdApp/lyrebird-app/src/main/java/com/lyrebird/rc/FlightDeckActivity.kt)
waits for elsewhere in the app — issuing a waypoint leg while DJI's own take-off climb is still
running would have the two fight each other for the sticks. Because the loop lives in the app, it
reports an exact `MISSION_CURRENT`/`MISSION_ITEM_REACHED` for every single item, and it is the only
executor that can track a live-updating ROI target — at the cost of the mission depending on the app
staying alive and in the foreground for its whole duration.

## Verifying a translation

Neither executor changes what a download reports: `MavlinkMissionStore` always answers with the
verbatim uploaded items, regardless of which executor flew them or what it turned them into. Reading
a plan back after flying it is the fastest way to confirm nothing about the *stored* mission changed
— the translation lives entirely between `MISSION_START` and the aircraft's motion.

## Native missions on consumer aircraft: experimental

`dji_native` stays available as an opt-in, but on the aircraft it was tested with — a DJI Mini 4 Pro,
MSDK 5.18, wpmzsdk 1.0.5.0 — DJI's wayline generator was not reliable enough to be the default. The
findings, for anyone who wants to take it further:

- **Actions.** In the generated `waylines.wpml` (what the aircraft flies), only one action group per
  waypoint survived and none at the last waypoint, although `template.kml` in the same KMZ had them
  all. A single action group holding two or more actions made the generator ask for a 312–671 MB
  buffer and crash the app (an `OutOfMemoryError` in `ActionMegaphoneParam.fromBytes`, or a native
  abort in `PPALController.generate`). Numbering the actions, giving each its own group, and
  describing the payload (`payloadInfo`/`payloadParam`) did not fix it.
- **Build time.** The same generator built most plans in about 0.1 s but took 43–46 s for others
  (three action groups in both cases), while the phone ran short of memory. A native start that takes
  longer than 10 seconds is now abandoned rather than started late.
- **Aircraft model.** WPML's drone and payload enums (`WaylineDroneType`, `WaylinePayloadType`) list
  enterprise aircraft (Matrice, Mavic 3 Enterprise) but no Mini. The generator may behave better on
  an aircraft it names; that is untested.

To investigate: every native start logs each step with its timing and native-heap use under the
logcat tag `LyrebirdNativeMission`, and the generated KMZ is kept on the phone in
`Android/data/com.lyrebird.rc/files/kmz/`. Comparing the action groups in its `template.kml` with
those in its `waylines.wpml` shows what DJI's generator kept. `V5NativeMissionAdapter` (plan → WPML
models) and `WaylineMissionHelper` (KMZ build and push) are where to start.
