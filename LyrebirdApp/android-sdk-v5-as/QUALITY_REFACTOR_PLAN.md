# Lyrebird Android Quality Refactor Plan

This note explains the main quality issues found by Spotless/ktlint, Detekt, and Android Lint, why they matter, and how we should fix them without mixing our code with DJI/vendor code.

Run commands from `LyrebirdApp/android-sdk-v5-as`.

## Scope

We should treat these as two separate streams:

- Lyrebird-owned code: `../lyrebird-app/src/main/java/com/lyrebird/rc/` custom packages such as `webrtc`, `edge`, `controller`, `formation`, `logger`, `server`, and `FlightDeckActivity.kt`.
- DJI/vendor code: `../android-sdk-v5-uxsdk/` and DJI sample areas that we mostly inherit. We still report on them, but we do not spend refactor effort there unless we intentionally maintain a local patch.

The important tasks are:

```sh
./gradlew qualityLyrebird
./gradlew qualityDji
./gradlew :app:testDebugUnitTest
./gradlew :app:compileDebugKotlin
```

## Current Baseline

The first stable Lyrebird loop now works:

```sh
./gradlew --continue :app:spotlessKotlinCheck :app:compileDebugKotlin qualityLyrebird
```

Focused refactors completed so far:

- `SdpUtils`: now pure Kotlin, covered by unit tests, and no longer appears in the Lyrebird Detekt findings.
- `AdaptiveFrameRatePolicy`: extracted from `WebRTCStreamer`, covered by unit tests, and removed the `maybeAdaptFrameRate` complexity findings from `WebRTCStreamer`.
- `LetterboxTransform`: extracted from `YoloTfliteDetector`, covered by unit tests, and removed the detector's coordinate-mapping Detekt findings.
- `PID`: anti-windup limit checks are now explicit, covered by unit tests, and no longer appear in the Lyrebird Detekt findings.
- `MockTelemetryOrigin`: extracted from `TelemetryProvider`, covered by unit tests, and removed mock-location condition complexity from telemetry setup.
- `shouldSwitchToDroneVideoSource`: extracted from `FlightDeckActivity`, making aircraft connection source switching explicit and removing its condition complexity finding.
- `TelemetryProvider.captureMetadata`: split into mock and cached metadata builders, removing the telemetry long-method finding.
- `WebRTCStreamer.startWhip`: split publisher reuse, listener wiring, and source-loss checks into helpers; local IP lookup was flattened and now catches `SocketException`.
- `WebRTCStreamMetrics.compactLabel`: split the status label into small helpers and removed the metrics file's Detekt formatting findings.
- `FrameMetadata.fromJson`: moved detection compatibility parsing into named helpers, removing the metadata parser's Detekt formatting findings.
- `EdgeDetectionController`: introduced `EdgeDetectionConfig`, shared frame-admission logic, and `runCatching` inference paths; the controller is now absent from the Lyrebird Detekt report.
- Empty placeholder files in `aircraft/formation` were removed; active formation logic remains in `aircraft/controller/FormationController.kt`.
- Cleared remaining `NewLineAtEndOfFile` findings in small Lyrebird Kotlin files.
- `DroneControlProfile`: grouped speed limits, distance PID gains, and yaw control into small value objects while preserving existing accessors.
- `SharedPhoneCameraFrameSource`: simplified phone-frame eligibility and moved NV21 conversion into a focused helper, clearing the source from Detekt.
- `DJIV5VideoCapturer`: split frame processing/delivery boundaries and removed its long-line and broad-catch findings.
- `WhipPublisher`: replaced the WebRTC wildcard import and split several long diagnostic/control lines before the larger publish-flow extraction.
- `TelemetryServer`: split client accept, broadcast, disconnect cleanup, and interruption handling; the server no longer appears in Detekt.
- `MockMp4VideoCapturer`: replaced ad-hoc throws/catches with precondition helpers and `runCatching`, and cleared its targeted formatting findings.
- `SharedDJIFrameSource`: simplified camera-index fallback and cleaned listener registration/recovery logging before tackling the larger frame-broadcast method.
- `FlightDeckActivity.shouldAllowMockVideo`: removed the constant helper and used the mock source-mode check directly.
- `LyrebirdFlightLogger`: split DJI log syncing and storage resolution out of the logger object, flattened directory fallback handling, and removed the logger from Detekt.
- `FormationController`: removed unused private formation-position/collision helpers and cleaned import/exception style, leaving only the larger controller split for later.
- `WhipPublisher`: converted required no-op WebRTC callbacks to explicit `Unit` expression bodies so the remaining findings focus on publish-flow complexity.
- `SharedDJIFrameSource`: introduced an `Nv21Frame` value and private frame processor so the hot DJI frame callback only snapshots recipients and delegates delivery.
- `WhipPublisher`: replaced ad-hoc publish precondition throws, flattened WHIP POST handling, and simplified reconnect-loop control.
- `DroneController`: fixed a small constant declaration and wrapped mission/trajectory lines before the larger controller split.
- `FlightDeckActivity`: converted required listener no-ops to explicit `Unit` bodies and removed an unused edge-detection setter.
- Added characterization tests for refactored WebRTC metadata parsing, stream metrics labels, and WHIP resource URL handling; fixed relative WHIP resource URLs for endpoints without explicit ports.
- `WhipPublisher`: added characterization tests for first-frame wait/recovery behavior, extracted the WHIP first-frame gate, and split publish setup into named peer-connection, SDP offer, remote-answer, and connection-wait helpers. The `publish` method no longer appears as a Lyrebird Detekt `LongMethod` finding.
- `DroneController`: replaced wildcard imports with explicit SDK/math imports, made required no-op callbacks explicit, logged best-effort abort cleanup failures, and wrapped the remaining simple long lines. Remaining DroneController findings are now structural control-loop size/return-count items that should be handled with focused tests or pure helper extraction first.
- `DroneController`: extracted the control-loop continuation decision into `ControlLoopContinuation`, covered the cancellation, stale-loop, manual-override, grace-period, and virtual-stick-disabled branches with JVM tests, and removed `shouldControlLoopContinue` from the Detekt return-count findings.
- `DroneController`: extracted pure trajectory progress, lookahead, speed limiting, yaw scaling, and final-waypoint decisions into `TrajectoryControl` with JVM tests. **Superseded:** the XPRIZE release replaced the virtual-stick trajectory controller with the nose-forward and hold-heading waypoint modes, so `TrajectoryControl` and its tests were removed during the integration.
- `DroneController`: extracted waypoint speed limiting, body-frame velocity conversion, arrival checks, and waypoint hold-cooldown decisions into `WaypointControl` with JVM tests. The waypoint PID `run` body no longer appears as a Detekt `LongMethod` or `ReturnCount` finding.
- `FlightDeckActivity`: extracted pure HTTP command parsing for stick, gimbal, waypoint, and trajectory POST payloads into `LyrebirdHttpCommandParser` with JVM tests, then moved POST command routing into a route-map based `LyrebirdHttpCommandHandler`. `handlePostRequest` no longer appears as a Detekt `LongMethod`, `CyclomaticComplexMethod`, `NestedBlockDepth`, or `ReturnCount` finding, and `SimpleHttpServer` no longer appears as `TooManyFunctions`; remaining HTTP debt is the broad command-handler safety catch and the larger Activity ownership boundary.
- `FlightDeckActivity`: collapsed duplicate options-menu branches into grouped actions. `handleLyrebirdMenuItem` no longer appears as a Detekt `LongMethod` or `CyclomaticComplexMethod` finding.
- `FlightDeckActivity`: flattened device IP lookup into top-level network-interface candidate helpers. `getDeviceIpAddress` no longer appears as a Detekt `NestedBlockDepth` or `ReturnCount` finding.
- `FlightDeckActivity`: split edge-detection startup into explicit precheck, failure handling, controller creation, overlay configuration, and source attachment helpers. `startEdgeDetection` no longer appears as a Detekt `LongMethod` or `ReturnCount` finding.
- `FlightDeckActivity`: decomposed SDK key listener registration into focused setup stages (battery/RTH, storage, flight-state, telemetry). `setupKeyListeners` no longer appears as a Detekt `CyclomaticComplexMethod` finding.
- `FlightDeckActivity`: simplified `isHomeSet` into a single return path while preserving latch semantics. `isHomeSet` no longer appears as a Detekt `ReturnCount` finding.
- `FlightDeckActivity`: simplified phone camera preview startup and phone inference frame gating to reduce early-return branching. `startPhoneCameraPreview` and `handlePhoneInferenceImage` no longer appear as Detekt `ReturnCount` findings.
- `FlightDeckActivity`: flattened sibling labels URI resolution into one return path while preserving direct-sibling and folder-scan fallback behavior. `findSiblingLabelsUri` no longer appears as a Detekt `ReturnCount` finding.

Reports to inspect:

- Lyrebird Detekt: `build/reports/detekt/lyrebird.html`
- DJI/vendor Detekt: `build/reports/detekt/dji.html`
- Android Lint: `../lyrebird-app/build/reports/lint-results-debug.html`
- UXSDK Android Lint: `../android-sdk-v5-uxsdk/build/reports/lint-results-debug.html`

## Main Issues To Fix

### 1. Very Large Activity

Primary file:

- `../lyrebird-app/src/main/java/com/lyrebird/rc/FlightDeckActivity.kt`

Main symptoms:

- `LargeClass`
- `TooManyFunctions`
- long methods
- complex methods
- many long lines
- broad responsibilities in one Android Activity

Why this matters:

The Activity currently owns UI state, video controls, telemetry display, HTTP/control endpoints, edge detection state, network discovery, preferences, and streaming coordination. When one file owns that much, every change becomes risky because it is hard to know what else will be affected. It also makes testing almost impossible, because most behavior is tied directly to Android lifecycle and UI objects.

What we should do:

- Extract pure or mostly pure services first.
- Move embedded HTTP/control routing out of the Activity.
- Move edge detection UI/state coordination into a small controller class.
- Move network/IP/discovery helpers into a separate utility or service.
- Keep standalone server loops flat and explicit so network errors do not hide cleanup paths.
- Keep the Activity as the UI wiring layer, not the owner of all behavior.

Good first extraction target:

- the local HTTP/control server and `handlePostRequest` logic, because it is both long and complex and can become testable without the full Android UI.

Done already:

- Aircraft connection source-switching logic was named as `shouldSwitchToDroneVideoSource`, reducing condition complexity in `applyAircraftConnectionState`.
- Removed the constant mock-video gate so preview visibility follows `isMockVideoEnabled()` directly.
- POST payload parsing for stick, gimbal, waypoint, PID waypoint, trajectory, and native trajectory commands is now pure Kotlin and covered by JVM tests before the Activity calls DJI/SDK side effects.
- POST command routing now lives behind `LyrebirdHttpCommandHandler`, leaving `SimpleHttpServer` focused on socket IO and response writing.
- Options-menu dispatch now groups duplicate stream/detection settings entries into one action path.
- Device IP lookup now scans IPv4 interface candidates separately from the Activity method, preserving Wi-Fi preference while keeping the method flat.
- Edge-detection startup now has a small coordinator method with dedicated helper stages so UI messaging and source wiring remain explicit without deep branching.
- SDK key listener wiring now keeps flight-state transitions and telemetry cache updates in separate setup stages, making the listener lifecycle easier to audit.
- Home-point latching logic now uses one decision path, keeping the same distance threshold and flight-state guard with clearer state transitions.
- Phone preview startup and inference gating now keep image close paths explicit while avoiding multi-exit control flow in the Activity hot path.
- Sibling edge-label discovery now keeps folder validation, direct name probes, and SAF child scans in one decision flow.

### 2. WebRTC Flow Complexity

Primary files:

- `../lyrebird-app/src/main/java/com/lyrebird/rc/webrtc/WebRTCStreamer.kt`
- `../lyrebird-app/src/main/java/com/lyrebird/rc/webrtc/WhipPublisher.kt`
- `../lyrebird-app/src/main/java/com/lyrebird/rc/webrtc/SharedDJIFrameSource.kt`
- `../lyrebird-app/src/main/java/com/lyrebird/rc/webrtc/SharedPhoneCameraFrameSource.kt`

Main symptoms:

- return-count findings
- long methods
- nested frame-handling logic
- too many throws in publishing flow
- wildcard imports
- long lines

Why this matters:

Video streaming is one of the most important runtime paths in the app. Complexity here can cause subtle bugs: dropped frames, stuck listeners, incorrect source switching, poor recovery after network errors, or hard-to-debug WHIP publishing failures. Smaller components make it easier to test decisions such as frame-rate adaptation, SDP munging, and publishing retries.

What we should do:

- Keep pure SDP and media-option logic tested with JVM tests.
- Extract frame-rate adaptation decisions from `WebRTCStreamer` into a small policy class.
- Split WHIP publishing into request building, response parsing, and peer-connection lifecycle pieces.
- Reduce early returns in frame-source classes by extracting small guard helpers.

Done already:

- `SdpUtils` was refactored and covered by `SdpUtilsTest`.
- Adaptive frame-rate decisions were extracted into `AdaptiveFrameRatePolicy` and covered by `AdaptiveFrameRatePolicyTest`.
- Mock telemetry origin validation was extracted into `MockTelemetryOrigin` and covered by `MockTelemetryOriginTest`.
- Metadata capture now delegates to separate mock and cached metadata builders.
- WHIP startup now delegates publisher reuse, callbacks, and source-loss checks to named helpers.
- Local IP lookup now delegates address scanning to a helper and catches `SocketException` specifically.
- WebRTC stream metric labels now build from focused helper methods instead of one long interpolated string.
- Frame metadata detection parsing now uses helper methods for target arrays, source compatibility, and confidence thresholds.
- Shared phone camera frames now delegate NV21 conversion and even-size calculations to `PhoneImageConverter`.
- DJI V5 frame delivery now flows through a private delivery request/helper, keeping the capturer listener small.
- WHIP publisher imports are explicit, and the simple frame-rate/logging line findings are cleared.
- Mock MP4 startup and frame emission now share the same explicit failure-reporting style as the other capturers.
- Shared DJI camera selection now uses a single fallback expression, reducing return-count noise around camera availability changes.
- WHIP first-frame availability is now handled by a small tested gate, preserving the DJI recovery retry while keeping mock/phone sources as single-attempt checks.
- WHIP publishing now delegates peer-connection observer setup, SDP offer creation, remote-answer application, and connection-loss waiting to focused helpers, removing the `publish` long-method finding.

### 3. Edge Detection Pipeline Complexity

Primary files:

- `../lyrebird-app/src/main/java/com/lyrebird/rc/edge/EdgeDetectionController.kt`
- `../lyrebird-app/src/main/java/com/lyrebird/rc/edge/YoloTfliteDetector.kt`

Main symptoms:

- return-count findings
- loop jump complexity
- long lines
- image conversion and inference responsibilities mixed in one area

Why this matters:

Edge detection touches camera frames, threading, model inference, UI overlays, and metrics. Bugs here can hurt app performance or block video processing. The code also needs to be understandable because model formats and thresholds may change as the detection model improves.

What we should do:

- Separate frame gating from inference execution.
- Extract image conversion helpers from detection orchestration.
- Add tests for coordinate conversion and post-processing where possible.
- Keep Android/image APIs at the boundary and pure math in testable functions.

Done already:

- Letterbox coordinate mapping was extracted into `LetterboxTransform` and covered by `LetterboxTransformTest`.
- YUV conversion helpers were moved out of `YoloTfliteDetector`, reducing the detector's function count and removing its current Detekt findings.
- `EdgeDetectionConfig` now groups model, label, source, and confidence settings for controller construction.
- Frame admission for NV21 and YUV inference now flows through the same helper, removing duplicate throttling and busy checks.

### 4. Drone Control And Formation Logic

Primary files:

- `../lyrebird-app/src/main/java/com/lyrebird/rc/controller/DroneController.kt`
- `../lyrebird-app/src/main/java/com/lyrebird/rc/controller/FormationController.kt`
- `../lyrebird-app/src/main/java/com/lyrebird/rc/controller/PID.kt`

Main symptoms:

- very large controller object
- return-count findings
- wildcard imports
- unused private formation/collision helpers
- long lines
- constants that can be `const val`

Why this matters:

This is flight-control-adjacent code. Even if the app is not directly flying autonomously in every path, this logic is safety-sensitive and should be easier to reason about than normal UI code. Large controller objects also make it hard to isolate calculations from SDK side effects.

What we should do:

- Identify pure calculations and move them into tested Kotlin classes.
- Remove unused private formation/collision code if it is truly dead, or wire it intentionally if it is planned behavior.
- Keep DJI SDK calls in adapter-like boundaries.
- Add tests around PID, target-position math, and any collision-risk calculation before changing behavior.

Done already:

- Removed whitespace-only placeholder files from `aircraft/formation`; they were unreferenced and only produced `EmptyKtFile` findings.
- `DroneControlProfile` no longer has a long constructor; flight-control constants are grouped by role without changing call sites.
- `DroneController` no longer has wildcard-import, empty-callback-block, swallowed abort-cleanup exception, or simple formatting/naming findings. The remaining `run` loop findings should be treated as behavior-sensitive flight-control work and covered before extracting.
- Control-loop continuation now has pure unit coverage before further flight-control refactoring. The next controller step should apply the same pattern to the waypoint and trajectory `run` bodies before changing their flow.
- Trajectory control now has pure unit coverage for segment progress, lookahead interpolation, slowdown, yaw speed scaling, and final reach detection.
- Waypoint PID control now has pure unit coverage for speed limiting, body-frame velocity conversion, arrival checks, and hold-cooldown behavior. The remaining DroneController findings are class-level size/function-count signals, so the next step should be splitting responsibilities rather than trimming individual loops.

Done already:

- PID output-limit and anti-windup behavior is covered by `PIDTest`.
- `PID` now uses the package matching its source path, and the old package import was removed from `DroneController`.

### 5. Silent Or Generic Error Handling

Primary areas:

- `FlightDeckActivity.kt`
- `TelemetryServer.kt`
- `WhipPublisher.kt`
- `SharedDJIFrameSource.kt`
- `LyrebirdFlightLogger.kt`
- controller classes

Main symptoms:

- empty catch blocks
- generic caught exceptions
- generic thrown exceptions
- swallowed exceptions

Why this matters:

Silent failures make field debugging painful. In drone/video workflows, a failure often happens on-device, under network pressure, or while interacting with DJI SDK state. If we swallow exceptions without logging enough context, we lose the only clue for diagnosing production issues.

What we should do:

- Replace empty catches with explicit logging or a deliberate ignored-result helper.
- Catch narrower exception types when possible.
- Convert repeated error patterns into small helpers.
- Make user-visible failures clear where the operator can act on them.

### 6. Android Lint Findings

Primary reports:

- `../lyrebird-app/build/reports/lint-results-debug.html`
- `../android-sdk-v5-uxsdk/build/reports/lint-results-debug.html`

Why this matters:

Android Lint catches platform-specific issues that Detekt does not: deprecated APIs, resource problems, permissions, lifecycle concerns, manifest issues, and performance pitfalls. Some warnings are inherited from DJI/vendor code, but Lyrebird-owned lint findings should be reviewed because they may become runtime or compatibility problems.

What we should do:

- Keep `:uxsdk:lintDebug` report-only for vendor awareness.
- Use `:app:lintDebug` as the actionable report.
- Fix Lyrebird-owned lint issues when they touch runtime correctness, permissions, lifecycle, or compatibility.
- Avoid spending time on cosmetic vendor lint unless we maintain that patch.

## Suggested Order Of Work

### Phase 1: Keep The Loop Green

Before and after each refactor:

```sh
./gradlew :app:compileDebugKotlin
./gradlew :app:testDebugUnitTest
./gradlew detektLyrebird
```

If changing Android resources or UI behavior:

```sh
./gradlew :app:lintDebug
```

### Phase 2: Fix Small Pure Utilities First

These are low-risk and build testing habits:

- SDP utilities
- frame-rate adaptation policy
- telemetry formatting
- coordinate conversion
- PID/math helpers

### Phase 3: Extract Medium Components

Good next targets:

- WHIP request/response handling from `WhipPublisher`
- frame listener selection from `SharedDJIFrameSource`
- edge detection metrics/frame gating from `EdgeDetectionController`

### Phase 4: Split The Large Activity

Once enough behavior has tests, extract from `FlightDeckActivity`:

- HTTP/control API routing
- edge detection UI coordination
- video-source state coordination
- network discovery/IP helpers
- telemetry display/cache updates

This should be done in multiple small PRs. A giant Activity split without tests would be hard to review and easy to break.

## Definition Of Better

Code quality is improving when:

- `:app:compileDebugKotlin` stays green.
- `:app:testDebugUnitTest` stays green and grows around refactored logic.
- Lyrebird Detekt findings decrease for the file being touched.
- New code does not add untested pure logic inside Android Activities.
- DJI/vendor findings remain separated from Lyrebird-owned findings.
- Refactors reduce responsibilities, not just line counts.

The goal is not to make every report zero immediately. The goal is to make the most important code easier to test, easier to review, and less risky to change.
