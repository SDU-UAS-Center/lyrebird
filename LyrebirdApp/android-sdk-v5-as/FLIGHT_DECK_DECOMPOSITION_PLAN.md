# Flight Deck Decomposition Plan

Date: 2026-09-15
Status: implementation in progress on `feature/dual-sdk-implementation`; changes are not committed.

## Implementation Checkpoint

The original measurements and region map below describe the starting revision, not the current
line numbers. Progress is tracked by responsibility and validation, not by lines removed.

| Step | Current implementation | Verification still required |
| --- | --- | --- |
| 0 | App Spotless now receives an explicit source-rooted `FileTree`; previously omitted packages are covered. The duplicate app MAVLink protocol was removed. | Live HTTP/TCP/MAVLink bench fixtures. |
| 1 | `LyrebirdSettings` owns typed preferences, validators, and the unchanged per-aircraft key set. `SettingsSnapshot` owns settings JSON; `StreamingMode` retains its original package in a separate file. | Live settings comparison. SDK effects and MAVLink parameter dispatch remain in their existing callers. |
| 2 | `SettingsDialogViews` owns dialog rendering; `FlightDeckSettingsPages` uses SDK-free `SettingsPageActions` and `FlightSettingsActions`. Display labels and settings JSON have regression tests. | Manual navigation through every settings page and dialog, including dismiss/recreation. |
| 3 | `DeviceStatusSource` owns application-context sensor/location subscriptions and a weak location listener. `DeviceStatusSnapshot` feeds the existing wire fields. Permissions and streaming Wi-Fi locks remain outside it. | Sensor/permission checks and LeakCanary on the target device. |
| 4 | `V5AircraftTelemetrySource` owns key reads, battery subscriptions, high-frequency telemetry, flight-state callbacks, and generation-scoped listener registration. Core `AircraftState` carries readings plus connection generation and observation time; shared projections feed TCP, MAVLink, and fleet output. | Samples are still obtained from SDK caches at each existing emission; video metadata retains its separate V5 telemetry provider. A future step may consolidate that provider and make freshness policy affect command acceptance. |
| 5 | HTTP command boundary exposes neutral media, detection, LRF, and flight ports; `LyrebirdHttpServer.kt` has no direct DJI, ViewModel, UXSDK, or `DroneController` dependency. V5 media, payload/camera, motion, and mission sinks now live in dedicated adapters; MAVLink flight policy is pure and tested. | Shared mission/controller policy and adapter contract tests can be broadened later; native V4 mission implementation remains future work. |
| 6 | Target selection, WHIP endpoint construction, V5 WebRTC streamer construction, and V5 native RTMP/RTSP/Agora/GB28181 lifecycle are extracted behind streaming helpers. The activity retains WHIP trigger policy and Wi-Fi-lock ownership. | Move publisher trigger state and Wi-Fi-lock ownership into a runtime coordinator. |
| 7 | Detection port and neutral source/active/model/threshold telemetry projection are extracted and tested; V5 AutoSensing and local TFLite provider lifecycles now live in dedicated providers. The activity retains eligibility checks, settings/file-pickers, and overlay rendering. | Move eligibility/overlay/frame coordination behind a runtime-aware detection coordinator. |
| 8 | Runtime startup now stops after a blocked/non-serving session, and activity teardown releases the network lease after fleet, streaming, MAVLink, and other runtime owners stop. SDK product-connection gating and activity-independent ownership remain. | Add a runtime owner independent of activity recreation and verify SDK initialization/lease ordering on-device. |
| 9 | Not started: final activity shell and flavor seams. | Explicit lifecycle review and device qualification. |

Automated checks at this checkpoint include both Android unit-test suites, both Spotless checks,
and `assembleCurrentDebug` plus `assembleDemoBiomassDebug`. The settings regression pins the
representative JSON bytes; telemetry tests compare units, axes, defaults, and preservation of
session authority/progress across projections. No connected Android device was available for UI,
SDK, flight, or streaming qualification. A green build is not a completed bench gate.

Latest results: 241 app tests, 81 core tests, and 138 Python tests passed, with no failures or
skips. The activity is 5,401 lines, down from 7,665. The documentation build passed.
`qualityLyrebird` generated reports with non-blocking findings; Android Lint reported no errors
in the new extracted files. Style/localization findings remain, including those carried with
the existing UI code. These are not being represented as clean static-analysis reports.

Since that checkpoint, the branch has also landed:

- neutral HTTP media/detection/LRF/flight ports, with no DJI/ViewModel/UXSDK/DroneController
  imports or direct controller calls remaining in `LyrebirdHttpServer.kt`;
- V5 media, detection, MAVLink payload/camera, motion, and mission adapters;
- pure MAVLink flight policy, WHIP target/endpoint policies, detection telemetry projection, and
  V5 WebRTC construction policies;
- blocked-runtime startup handling, lease-last teardown ordering, and a process-scoped lease gate
  before DJI SDK initialization.

These are still adapter extractions, not proof of activity-independent runtime ownership. Activity
recreation, background operation, process death, USB chooser behavior, and physical flight/video
qualification remain open device gates.

The formatting scope repair exposed 53 files with existing violations. Most changes outside the
extracted components are formatter output; small comment-placement fixes and controller naming
suppressions preserve behavior. The original interpolated-glob target was still vacuous, even
after adding packages; the explicit `FileTree` was verified by a failing check on those sources.

Do not treat the value snapshot as timestamped sensor evidence. `AircraftReadings` deliberately
preserves legacy wire fallback values and does not claim that independently sampled SDK cache
entries were observed simultaneously. Freshness/generation changes need their own tests and
review before they affect command acceptance or reconnect behavior.

## 1. Purpose and Scope

This is the **pre-step** to [DUAL_SDK_FLAVORS_PLAN.md](DUAL_SDK_FLAVORS_PLAN.md). That plan
requires shared session ownership, neutral contracts, and extracted SDK-free slices (§4, §6, §8
Phase 1-3). Those extractions are currently blocked by the fact that
[FlightDeckActivity](../lyrebird-app/src/main/java/com/lyrebird/rc/FlightDeckActivity.kt) is both
the flight-deck screen and most of the application runtime.

Goal: reduce the activity to a **view and lifecycle adapter**, and move application behavior into
named components with explicit ownership — *without* changing observable behavior, the HTTP/MAVLink
wire surfaces, or any safety policy.

The SDK dimension and the V4 flavor are **out of scope here**. This plan prepares the seams they
need and deliberately stops before adding a second SDK. It also does not attempt to finish the
`DroneController`, `Payload`, or `WaylineMissionHelper` conversions — those follow the flavor
split, not this pre-step.

Non-goals:

- No `sdk` flavor dimension, no V4 dependency, no `src/v4` source set.
- No wire-format change on HTTP, TCP telemetry, or MAVLink.
- No change to authority semantics, the RC-override latch, or the per-aircraft Safety persistence.
- No re-introduction of removed mock/phone video or mock telemetry.
- No controller retuning, UI redesign, or mission-executor behavior change.

## 2. Measured Current State

Measured at `fb394dc` (`feature/dual-sdk-implementation`), worktree clean.

| Fact | Value |
| --- | --- |
| `FlightDeckActivity.kt` size | **7,665 lines** — largest tracked source file in the repository |
| Next largest app Kotlin source | `DroneController.kt`, 2,033 lines |
| Member declarations in the activity | 378 |
| `findViewById` call sites | 43 |
| `sharedPreferences` references | 113 |
| `DJIKey` / `KeyManager` references | 67 |
| `KeyManager.getInstance().listen` call sites | 19 |
| App module Kotlin total | ~50,000 lines including tests and inherited DJI sample code |
| `:lyrebird-core` | ~5,600 lines of main source, already SDK-free (`mavlink/`, `telemetry/`) |

Line numbers below will drift as steps land. Treat them as region markers for locating code, not as
addresses to paste into a script.

## 3. Why This Matters More Than Line Count

Three findings from the review drive the ordering:

1. **Screen lifetime and runtime lifetime are the same object.** `onDestroy` (5035-5129) stops the
   session, MAVLink endpoint, streaming, executors, fleet mesh, obstacle guard, SDK listeners, and
   `DroneController`. Any "extraction" that keeps a reference to the activity preserves this
   coupling; the fix is ownership, not file size.
2. **The recent session extraction is narrower than the plan assumed.** `startServers()` (4899-5006)
   takes the lease and binds HTTP/telemetry, but then unconditionally continues into fleet mesh,
   obstacle guard, MAVLink, and streamer creation — so a session blocked by the other APK still
   starts everything else. SDK initialization happens earlier still, in
   [DJIApplication.onCreate](../lyrebird-app/src/main/java/com/lyrebird/rc/DJIApplication.kt#L24),
   before any lease exists. On the way out, the lease is released inside `session.stop()` *before*
   streaming, MAVLink, and controller teardown finish.
3. **The protocol boundary still carries V5 types.** `LyrebirdCommandHost`
   ([LyrebirdHttpServer.kt](../lyrebird-app/src/main/java/com/lyrebird/rc/LyrebirdHttpServer.kt#L52))
   exposes `DJIKey`, `MediaVM`, `PayloadWidgetVM`, `LocationCoordinate3D`, and UXSDK
   `DetectedTarget`. The HTTP surface cannot be shared with a second SDK until these become neutral
   operations.

Two additional defects were found while inventorying, and belong to Step 0:

- **A stale duplicate shadows core.** `lyrebird-app/src/main/java/com/lyrebird/rc/mavlink/MavlinkProtocol.kt`
  (511 lines) duplicates `lyrebird-core/.../mavlink/MavlinkProtocol.kt`. They differ only in
  visibility modifiers, member order for two constants, and formatting. Because both declare
  `package com.lyrebird.rc.mavlink`, the app's `internal object Mav` wins inside the app module, so
  the activity's `Mav.CMD_*` references resolve to the **copy in the app**, not to core. Nothing
  else in the app references it.
- **The Spotless/Detekt include list does not cover several Lyrebird-owned packages.**
  `lyrebirdSourceIncludes` (build.gradle, lines 43-53) covers `controller/`, `edge/`, `formation/`,
  `logger/`, `server/`, `webrtc/`, plus three root files. Missing: `fleet/`, `perception/`,
  `settings/`, `telemetry/`, and `mavlink/`. New packages created by this plan would be **silently
  ungated** unless the list is extended in the same commit — the same class of vacuous gate that was
  already repaired once for `:app` and `:lyrebird-core`.

## 4. Region Map

Sections as they exist today. Region markers come from the file's own `// ====` banners.

| Lines | Region | Target owner |
| --- | --- | --- |
| 178-431 | `StreamingMode`, `StreamResolutionPreset`, `DetectionSource`, ~120 pref/param constants | Settings (shared), parameter names stay with the MAVLink surface |
| 433-597 | Runtime fields: view models, session, MAVLink, executors, streamer, phone services | Runtime owner; device status source |
| 598-782 | Detection fields, `DataProcessor`s, ~30 DJI keys, idle detection | Aircraft telemetry adapter (keys), detection coordinator |
| 783-934 | Loading overlay; `onCreate` startup sequence | Activity (overlay), runtime owner (startup) |
| 935-1029 | Manual override checkbox; authority banner | Presenter; shared authority wiring |
| 1031-1225 | WebRTC options; streaming/RTSP/RTMP/Agora/GB prefs; edge model file selection | Settings repository; streaming coordinator |
| 1227-1483 | Map expand; action-row adapter; edge label discovery and file pickers | UI presenter; activity file pickers |
| 1484-1794 | M400 PORT_3 gimbal rebinding, thermal arming, settings JSON, `set*` validators, thermal read | V5 aircraft adapter; settings repository |
| 1796-1945 | Connection listener, detected-profile handling, metrics view | Runtime owner; UI presenter |
| 1946-2269 | WebRTC metrics JSON, Wi-Fi lock, active streaming start/stop, per-client streaming | Streaming coordinator |
| 2271-2730 | AutoSensing toggle; edge-detection toggle | Detection coordinator (+ V5 onboard adapter) |
| 2732-2798 | Drone status view | UI presenter |
| 2800-2890 | Settings backup, DJI flight-log sync, altitude/MAVLink status views, name display | Settings/lifecycle helpers; UI presenter |
| 2892-3930 | Settings cockpit and branded subpages (~1,000 lines) | Settings UI presenters |
| 3930-4018 | Key listeners: battery/RTH, storage, flight state, RTH mode override | Aircraft telemetry adapter |
| 4019-4154 | Idle monitor; loading overlay; telemetry listeners | Runtime owner; telemetry adapter |
| 4155-4248 | Drone name load/default and dialog | Settings; UI |
| 4249-4660 | Config dialogs (MediaMTX, FPS, resolution, stream, RTMP, RTSP, Agora, GB28181, confidence, format) | Settings UI |
| 4662-4818 | Default camera recording, storage status and format | V5 camera adapter |
| 4819-4898 | WHIP URL construction; location/sensor updates | Streaming coordinator; device status source |
| 4899-5034 | `startServers`, telemetry server wiring, server info toast | Runtime owner |
| 5035-5153 | `onDestroy`, `onPause`, `onResume`, HSI detach | Runtime owner + activity |
| 5154-5262 | Options menu; `fetchDroneSerialNumber` | UI; V5 identity adapter |
| 5263-5560 | Telemetry accessors, ROI tracking loop, `rebuildTelemetryCache` | V5 telemetry adapter; shared ROI control |
| 5563-5903 | MAVLink config/params, fleet mesh, obstacle guard, fleet beacon | Runtime owner; shared |
| 5904-6456 | MAVLink snapshot, parameter writes, `mavlinkParameters`, command sink | Command layer; V5 camera/gimbal ops |
| 6457-6543 | `awaitAction`, `mavlinkFlightGate`, `supersedeMission` | Shared policy; V5 action runner |
| 6544-6920 | Motion sink; `climbAfterTakeoff` | Shared sink; V5 flight ops |
| 6955-7469 | Mission sink: native WPMZ path and app-executed sequencer | Shared sequencer; V5 native-mission compiler |
| 7470-7550 | MAVLink endpoint start/restart | Runtime owner |
| 7550-7665 | `rebuildRealTelemetryCache` | V5 telemetry adapter |

## 5. Target Architecture

```mermaid
flowchart TD
    UI[FlightDeckActivity: views, permissions, pickers] --> P[Presenters]
    UI --> RT[LyrebirdRuntime]
    P --> SET[Settings repository]
    RT --> SESS[LyrebirdSession: lease + servers]
    RT --> MAV[MAVLink endpoint and FTP]
    RT --> STR[Streaming coordinator]
    RT --> DET[Detection coordinator]
    RT --> FLEET[Fleet mesh and obstacle guard]
    RT --> TEL[Aircraft telemetry source]
    TEL --> NEU[Neutral aircraft state]
    NEU --> COORD[TelemetryCoordinator]
    COORD --> MAV
    COORD --> SESS
    CMD[Command and mission layer] --> OPS[Aircraft ops ports]
    OPS --> V5[V5 adapter: keys, WPMZ, media]
    CMD --> MAV
```

The activity keeps: view binding, menu and dialog presentation, permission and file-picker results,
lifecycle callbacks, and forwarding user intent to presenters. It does not own sockets, workers,
mission sequencing, or SDK subscriptions.

### Ownership rules (hard constraints)

- **No session-scoped component retains the activity.** Use application context for platform
  services and detachable observers for UI notifications. Dialog renderers are explicitly
  activity-scoped and may use the themed activity context; dismiss them at screen destruction.
  A narrow interface alone does not remove a reference to an activity implementing it.
- **Components are testable without an aircraft.** Anything holding SDK types stays in the adapter
  layer, and anything with logic moves to a pure class with an existing test style (`RoiControl`,
  `DistanceTrigger`, `AuthorityLatch`, `ObstacleBrake`).
- **UI-visible state is observed, not pushed.** Presenters expose state; the activity renders it.
  Telemetry, streaming metrics, and authority already behave this way.
- **SDK and lease lifetime stay independent of the screen.** A blocked session must start nothing
  else; a recreation must not drop a live session; process death releases the lease but never
  resumes a mission.
- **Behavior-preserving means observable-preserving.** HTTP responses, TCP telemetry frames,
  MAVLink messages, settings JSON, and logged outcomes must be identical after each step.

## 6. Steps

Each step is one reviewable change (or a small series), with the gates below run
before moving on. Keep V5 working throughout; never combine a mechanical extraction with a behavior
change.

### Step 0 — Baseline, gate repair, duplicate removal

- Extend `lyrebirdSourceIncludes` in `LyrebirdApp/android-sdk-v5-as/build.gradle` to cover
  `com/lyrebird/rc/fleet/**`, `com/lyrebird/rc/perception/**`, `com/lyrebird/rc/settings/**`,
  `com/lyrebird/rc/telemetry/**`, and `com/lyrebird/rc/mavlink/**`.
- Prove the extended gate is real, not vacuous: add a deliberate ktlint violation to a file in each
  newly covered package, confirm the check fails, then revert. (The vacuous-gate failure was already
  caught once by exactly this probe; do not skip it.)
- Delete the app-side duplicate `mavlink/MavlinkProtocol.kt` and confirm the app still compiles
  against core's public `Mav`, `MavlinkMsgId`, `PayloadWriter`, and `MavlinkFramer`. Run Spotless on
  the resulting app sources.
- Record baseline behavior for comparison: `GET /config` and `GET /config/settings` bodies, one full
  and one gap telemetry frame, and a MAVLink heartbeat plus `LYREBIRD_STATUS` at the bench. Store
  the frames as test fixtures where a fixture does not already exist.

Gate: `:app` and `:lyrebird-core` compile and pass unit tests; Spotless passes on both; the probe
demonstrated the gate fails when violated; fixtures captured.

### Step 1 — Settings repository

Move settings *state*, leaving dialogs where they are.

- New `settings/` types: a `SettingsStore` interface over `SharedPreferences`, a typed
  `LyrebirdSettings` facade (streaming, resolution, FPS, detection, MediaMTX, RTSP/RTMP/Agora/GB
  endpoints, drone name, MAVLink system id and flight-allowed flag), and the JSON serializer for
  `/config/settings`.
- Sources: preference constants (196-400), all `PREF_*` getter/setter pairs (1031-1225), the
  `set*` validators (1684-1763), `readSettingsJson` (1623-1683), and the writable-parameter
  allowlist mapping in `applyMavlinkParameter` (6009-6092).
- Keep `DroneSettingsProfiles`, `LyrebirdSettingsBackup`, and `LyrebirdOnboarding` as they are; they
  consume the same keys and must keep working. `PER_DRONE_PROFILE_KEYS` moves next to the
  repository.

Tests: extend `DroneSettingsProfilesTest`; add `LyrebirdSettingsTest` covering defaults, validation
rejections, JSON key/group shape, and the system-id resolution edge cases (unknown serial, manual
id).

Gate: `/config/settings` and `/config` bodies unchanged against the Step 0 fixtures; the `set*`
methods return the same booleans for the same inputs.

Risk: low for behavior, moderate for volume (113 call sites). Mechanical and compiler-verified.

### Step 2 — Settings UI presenters

The single largest line reduction (~1,500 lines), and the safest kind: presentation only.

- Split the cockpit page (2892-3240), branded subpages (3240-3930), the config dialogs (4189-4660),
  the options menu (5154-5205), and the name/status displays (2860-2890).
- Introduce a `SettingsScreenHost` describing what the presenters may do to the screen (show a page,
  show a subpage, show a dialog, close), and a small row/cell model so section and row construction
  is pure and testable.
- The activity keeps inflation, view lookup, and the dialog lifecycle it already tracks through
  `lyrebirdSettingsDialog`.

Tests: new presenter tests for section/row composition and value formatting (`formatCockpitLimit`,
`formatCapacity`, `formatDuration`, storage summaries). Manual UI pass for each page.

Gate: every page, value, toggle, and overflow action behaves identically; no dialogs lost.

### Step 3 — Device status source

Phone location, heading, pressure, battery, and Wi-Fi are real device readings and stay.

- New `DeviceStatusSource` owning `LocationManager`, `SensorManager`, `BatteryManager`, the Wi-Fi
  manager and multicast lock, and the orientation math (516-595, 4849-4898, 5551-5560).
- Expose a snapshot plus a change callback; keep the `WeakReference` listener pattern for location,
  and keep the application-context choice — both exist to prevent the ~7.8 MB activity leak.

Tests: orientation/azimuth math extracted as a pure function and unit tested. Manual: telemetry
`phoneLocation` unchanged.

Gate: the phone block of the telemetry frame is unchanged; no new leak reported by LeakCanary.

### Step 4 — Aircraft telemetry source (first SDK boundary)

This is the step that makes the telemetry path shareable with a second SDK.

- Define a neutral `AircraftState` in the shared layer: the values currently read through DJI
  accessors, each with freshness/validity and a connection generation, per the dual-SDK plan's
  contract requirements (§4, "Contract details").
- New V5 adapter implementing it: the ~30 keys (659-780), the accessors (5265-5467), the listeners
  (3930-4018, 4135-4154), the SDK-to-neutral conversion (5501-5560, 7550-7665), the MAVLink snapshot
  builder (5904-5997), and the fleet beacon builder (5739-5780).
- `TelemetryCoordinator` is populated from the neutral state in one place. Both the TCP stream and
  the MAVLink snapshot read the *same* state object, so the two surfaces cannot report different
  numbers for one instant — the property the current code achieves by convention.
- Preserve deliberately: AGL exists once (`TelemetryCoordinator.altitudeAGL`), ASL lives in
  `GeoPosition`, `sanitisedAttitude` still replaces DJI's 6553.5 marker, and the home-set latch and
  distance-to-home zeroing stay as they are.

Tests: adapter mapping tests against a fake key source (no aircraft); keep
`TelemetryWireFixtureTest`, `TelemetryCoordinatorTest`, and core `TelemetryReadingsTest` /
`TelemetryWireTest` green; add cases for stale readings and missing values.

Gate: full and gap telemetry frames byte-identical to the Step 0 fixtures; MAVLink snapshot fields
unchanged; `MavlinkSnapshot.altitudeAglM` and `FleetBeacon.altitudeAglM` preserved.

### Step 5 — Command and mission layer

The highest-risk step; split it into at least three commits (ports, sinks, native mission).

- Define narrow neutral ports: `AircraftFlightOps` (take-off, land, RTH, virtual stick, goto, yaw,
  altitude, cancel), `CameraGimbalOps` (gimbal rotate, zoom, record, capture, thermal, LRF, payload
  drop), and `NativeMissionCompiler`.
- Move the sinks out of the activity into their own files: `mavlinkCommandSink` (6184-6456),
  `mavlinkMotionSink` (6544-6920), `mavlinkMissionSink` (6955-7469), plus `mavlinkFlightGate` (6487),
  `supersedeMission` (6527), `climbAfterTakeoff` (6921), and the ROI loop (5302-5410).
- The **app-executed sequencer becomes shared and tested**: leg sequencing, seq-tracked reach
  latches, `DistanceTrigger`, cancellation, `supersedeMission`, and progress reporting. The
  **WPMZ/native path stays V5** behind `NativeMissionCompiler`, and the `dji_native` executor remains
  selectable exactly as today.
- Replace the DJI types in `LyrebirdCommandHost` with the neutral ports. This is what makes the HTTP
  handler independent of V5; keep every route's response text identical.
- Keep `awaitAction`/`awaitParameterWrite` bounded waits as they are, and keep the single-flight
  shutter and two-thread FTP executors' semantics.

Tests: sequencer tests with fake ops (arrival, refusal, cancellation, supersede, distance-triggered
capture); parameter allowlist/refusal behavior; authority and RC-override rejection paths. Existing
`LyrebirdHttpCommandParserTest`, `RoiControlTest`, `OrbitControlTest`, `WaypointArrivalTest`,
`WaypointControlTest`, `ControlLoopContinuationTest` stay green.

Gate: HTTP and MAVLink produce the same outcomes for the same command on the dashboard MAVLink tab;
Safety takeover, RC override, and `lb_mav_0_allow_flight` behavior unchanged; a refused waypoint is
still reported as refused on both surfaces.

### Step 6 — Streaming coordinator

- New `StreamingCoordinator` owning mode selection, WHIP URL construction, publisher lifecycle,
  retry and the override-failure fallback, the Wi-Fi low-latency lock, streamer listener wiring, and
  per-client start suppression (1946-2269, 4819-4848, 4899-5006 streamer portion).
- Contracts: `StreamingPublisher` (start/stop/change options/state) and a frame-source provider.
  `WebRTCStreamer`, `WhipPublisher`, and `SharedDJIFrameSource` stay where they are for now; only
  their orchestration moves. The frame-source seam is what Phase 5 of the dual-SDK plan later
  implements for V4.
- Preserve: the anti-hijack retarget rule, the "no dead RTSP URL advertised" rule, and that
  streaming starts on the first telemetry client.

Tests: keep `WhipPublisherHelpersTest`, `MediaMtxConsumerWatcherTest`, `AdaptiveFrameRatePolicyTest`,
`FrameMetadataTest`, `PeriodicKeyframeEncoderFactoryTest`, `WebRTCStreamMetricsTest`. Add
coordinator tests with a fake publisher for the retarget and start-once rules.

Gate: WHIP publish to MediaMTX and WHEP playback; reconnect after a publisher failure; no stream
started when the session is blocked.

### Step 7 — Detection coordinator

- Move AutoSensing (2271-2560), edge detection (2562-2730), the overlay wiring, and the model/label
  file selection (1194-1225, 1345-1483) behind a `DetectionProvider` seam: DJI onboard AutoSensing
  as one V5 implementation, local TFLite inference as another, `NONE` as the default.
- Capability-gated, so a future V4 build reports onboard detection as unsupported rather than
  no-op'ing.
- Preserve the detections telemetry JSON exactly, including `source`, `selectedSource`, `active`,
  and the target array.

Tests: capability and toggle-state tests with fakes; keep `LetterboxTransformTest` and the edge
detector tests green.

Gate: detection telemetry block unchanged; toggles behave identically; detection requires a live
aircraft for the onboard source, as today.

### Step 8 — Runtime owner and session lifecycle

The behavioral step. Do it only after the components exist.

- New runtime component owning: SDK connection gating, the network session, the MAVLink endpoint and
  FTP server, fleet mesh, obstacle guard, streaming coordinator, executors, idle monitor, and the
  teardown order.
- **Fix the ordering gaps:**
  - Acquire the session lease *before* any SDK operation that can start a product connection, so
    the inactive app does not initialize a competing connection, control loops, or publishers.
    Do not assume registration is separable from automatic connection: validate that boundary for
    the selected SDK before leaving any initialization or registration outside the lease.
  - A blocked session must skip fleet mesh, obstacle guard, MAVLink, and streaming. Current
    `startServers()` continues past the block.
  - Release the lease **last**, after publishers, servers, listeners, and control loops have stopped,
    rather than inside `session.stop()` before the rest of teardown runs.
- Make the runtime's lifetime independent of the activity's: an explicit stop path for real
  shutdown, and recreation that re-attaches to a live runtime instead of tearing it down. If a
  foreground service is required for background operation, that is a separate, explicitly reviewed
  change — do not smuggle it in here.
- Keep the safety invariants: no automatic command or mission resume after process death, no
  authority release on app switch, and the banner showing a restored SAFETY state before the
  aircraft connects.

Tests: extend `LyrebirdSessionTest` and `SessionLeaseTest` with ordering, idempotency, and
"blocked session starts nothing" cases using fakes; add a recreation test (Robolectric if it fits
the existing `returnDefaultValues` setup, otherwise a bench procedure).

Gate for this pre-step: injected competing-owner tests and a V5 device test prove that recreation
keeps the session and stream alive, a blocked session starts nothing, and teardown releases the
lease last. Both-APK launch-order and USB tests belong to the later flavor qualification, when
the second SDK app actually exists; they cannot be claimed as completed by this pre-step.

### Step 9 — Activity shell, flavor seams, documentation

- Register pages and trim the activity to the UI shell described in §5; verify no remaining
  `KeyManager`, socket, or executor ownership in it.
- Prepare the flavor seam without adding a flavor: confirm the new components sit in packages that
  can move to `src/v5` wholesale, and that nothing shared imports DJI types. `DefaultLayoutActivity`
  inheritance stays in what will become the V5 source set.
- Update `src/content/docs/android-app.md` (runtime/session behavior if Step 8 changed ordering) and
  record the extraction in the quality plan.

Gate: build, tests, and Spotless green; a documented list of what remains V5-bound, to hand over to
Phase 3 of the dual-SDK plan.

## 7. Shared vs Flavor-Specific Placement

| Component | Home after this pre-step | After the flavor split |
| --- | --- | --- |
| Settings repository, JSON shape, validation | app `settings/` | shared app source |
| Settings UI presenters | app `settings/` | shared app source |
| Device status source | app package | shared app source (Android, SDK-free) |
| Neutral aircraft state, telemetry wire | `:lyrebird-core` `telemetry/` | unchanged |
| V5 aircraft telemetry adapter | app `adapter/` | `src/v5` |
| Command/mission layer, sinks, sequencer | shared app package | shared or core |
| Native WPMZ compiler and executor | app `controller/` | `src/v5` |
| ROI, orbit, PID, waypoint geometry | `controller/` (already pure) | core / shared |
| Streaming coordinator | shared app package | shared |
| Frame source and publisher | `webrtc/` (V5-bound today) | V5 adapter behind the frame-source seam |
| Detection coordinator | shared app package | shared |
| DJI onboard AutoSensing | app `adapter/` | `src/v5`, capability-gated |
| Runtime owner and session | shared app package | shared, with flavor-supplied SDK hooks |
| Activity and UXSDK widget integration | app root | `src/v5` |

Rule of thumb for this pre-step: if a file imports a `dji.*` type or an SDK view model, it belongs
in an `adapter/` package or stays in the activity — nowhere else. That single rule is what makes the
later move mechanical.

## 8. Quality Gates and Verification

Run from `LyrebirdApp/android-sdk-v5-as` after every step:

```bash
./gradlew :app:compileCurrentDebugKotlin :app:testCurrentDebugUnitTest
./gradlew :lyrebird-core:testDebugUnitTest
./gradlew :app:spotlessKotlinCheck :lyrebird-core:spotlessKotlinCheck
./gradlew :app:assembleCurrentDebug
```

Manual pre-commit hooks (tests are in the manual stage locally, always run in CI):

```bash
pre-commit run groundstation-tests android-tests --hook-stage manual
```

From the repository root, when a shared client contract is touched:

```bash
.venv/bin/python -m pytest GroundStation/tests -q
```

App unit-test reports land at
`LyrebirdApp/lyrebird-app/build/test-results/testCurrentDebugUnitTest/`; core reports land at
`LyrebirdApp/lyrebird-core/build/test-results/testDebugUnitTest/`. Neither is under the Gradle root
because `settings.gradle` remaps `projectDir`. The app variant remains `currentDebug`, not
`currentV5Debug`, until the SDK dimension exists.

Baseline at the start of this work: 229 app tests, 81 core tests, 138 Python tests, Spotless and
debug assembly passing. Test counts may rise but must never fall silently — a dropped test in an
extraction means coverage was deleted along with code.

Per-step evidence, not just a green build:

| Step | Evidence required |
| --- | --- |
| 0 | Gate probe fails when violated; fixtures captured |
| 1 | `/config` and `/config/settings` diff clean against fixtures |
| 2 | Manual pass over every settings page |
| 3 | Phone block of the telemetry frame unchanged |
| 4 | Full and gap frames byte-identical; MAVLink snapshot fields unchanged |
| 5 | Dashboard MAVLink tab vs HTTP parity; authority and RC-override tests |
| 6 | WHIP to MediaMTX and WHEP playback; blocked session starts no stream |
| 7 | Detection block unchanged; onboard source still requires the aircraft |
| 8 | Competing-owner tests and V5 bench: recreation keeps the session; lease released last; two-SDK qualification follows later |
| 9 | Remaining V5-bound list documented |

## 9. Risks and Mitigations

| Risk | Mitigation |
| --- | --- |
| Scripted deletion of Kotlin methods corrupts the file — this already cost a full revert once | Never delete by brace matching. Expression-bodied functions have no braces and the naive "find next `{`" swallows the following method. Delete by explicit region with a boundary assertion, then compile immediately. |
| Line numbers shift between steps, so a prepared edit targets the wrong text | Re-locate by region marker and function name before each edit; re-read a file after `spotlessKotlinApply`, which reformats exact text. |
| A component extracted "into a class" still holds the activity | Enforce the interface rule in §5. A constructor taking `FlightDeckActivity` is a failed extraction, not a refactor. |
| Wire behavior drifts during a mechanical move | Fixture comparison at every step that touches telemetry or settings, and dashboard parity for every step that touches commands. |
| A new package is added without gate coverage — the current include list already has five such gaps | Step 0 extends the list, and each step that creates a Lyrebird-owned package extends it in the same commit. |
| The activity loses UI state on recreation while the runtime survives | Presenters read from settings and coordinator state rather than activity fields; verify against the recreation gate in Step 8. |
| Step 5 or 8 changes safety behavior by accident | Treat both as safety-critical: explicit review, the invariant list in §5, and the existing authority/override tests as a hard gate. |

## 10. Sequencing Summary

```text
Step 0  baseline, gate repair, duplicate removal   (no behavior change)
Step 1  settings repository                        (state)
Step 2  settings UI presenters                     (~1,000-1,600 lines out, presentation only)
Step 3  device status source                       (Android, SDK-free)
Step 4  aircraft telemetry source + neutral state   (first SDK boundary)
Step 5  command and mission layer                   (highest risk, 3+ commits)
Step 6  streaming coordinator
Step 7  detection coordinator
Step 8  runtime owner and session lifecycle         (behavioral; fixes ordering)
Step 9  activity shell, flavor seams, documentation
```

Steps 1-4 are behavior-preserving and can proceed quickly. Steps 5 and 8 are the two that require
real review and bench time. After Step 9, the dual-SDK plan's Phase 1 (contracts and neutral state)
is substantially complete and Phase 3 (flavors and SDK-bound UI isolation) becomes a packaging
exercise rather than an excavation.

## 11. Commit Discipline

- Use the current branch unless a new one is requested; reviewable commit series use subjects matching the existing
  history (`Telemetry: …`, `Session: …`, `Safety: …`).
- Preserve unrelated worktree changes; read `git status` and the diff before editing a modified file.
- No AI `Co-Authored-By:` trailer. If acknowledging assistance, append a plain-text footer such as
  `AI-assisted by DeepSeek V4.1 Flash.` at the very end of the message.
- Each step's gate runs before the next step starts. A failing local hook is part of finishing the
  change, not a follow-up.
