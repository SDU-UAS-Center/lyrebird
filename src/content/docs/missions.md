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

| | `dji_native` (default) | `onboard` |
| --- | --- | --- |
| Who flies it | DJI's own wayline engine, on the flight controller | Lyrebird's app, sequencing legs through its PID waypoint controllers |
| Survives the phone losing focus / being backgrounded | Yes — the mission lives on the aircraft once pushed | No — the sequencing loop lives in the app |
| Take-off, land, RTL | Handled by DJI, from the plan's leading `NAV_TAKEOFF` altitude and trailing `NAV_LAND`/`NAV_RETURN_TO_LAUNCH` | Handled item-by-item like any other plan item |
| Per-item heading (`param4`) | Honoured, as a fixed wayline yaw | Honoured |
| Per-leg speed (`DO_CHANGE_SPEED`) | Honoured, as a per-waypoint speed | Honoured |
| Camera / gimbal actions | Translated to wayline actions, triggered on reaching the waypoint they sit after | Executed directly, in plan order |
| Region of interest (`DO_SET_ROI*`) | Static target only — see below | Full continuous tracking |
| Progress reporting | `MISSION_CURRENT` per waypoint reached; no per-item `MISSION_ITEM_REACHED` | Exact `MISSION_ITEM_REACHED` per item |
| `MAV_CMD_SET_CAMERA_MODE` | No wayline equivalent — skipped | Honoured |

**Default is `dji_native`.** A mission that keeps running on DJI's own flight controller is not at
the mercy of the app being backgrounded, the phone locking, or Android killing a background
process mid-flight — the failure mode that matters most for an unattended or long mission. Switch
to `onboard` for a plan that leans on something only it can do: a continuously-tracking ROI, or
exact per-item arrival reporting.

**Switching it.** `lb_mission_exec` is an Android `SharedPreferences` value (file `LyrebirdPrefs`,
package `com.lyrebird.rc`), the same way every other `lb_*` preference in this app is set — by hand
in the field, via `adb` or the settings backup file, not through a settings screen or a MAVLink
parameter. There is nothing to edit for the default.

## What the native path translates

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
| `SET_CAMERA_MODE` | No wayline equivalent — skipped |

An action item with no later waypoint to attach to (one that sits after the last leg, before a
trailing land) rides along with the last waypoint instead of being lost.

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
