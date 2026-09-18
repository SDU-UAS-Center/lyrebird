# V4 Feasibility — B0 Bench Record (2026-09-18)

Scope: resolve and inspect the documented V4.18 dependencies in an isolated bench, per
[DUAL_SDK_FLAVORS_PLAN.md](DUAL_SDK_FLAVORS_PLAN.md) batch B0. This is a resolution and build
inspection, not a registration, video or flight qualification. No V4 flavor is enabled in the
app (`currentV4`/`demoBiomassV4` stay disabled) and no V4 code was added to the repository.

## Verified

### Artifacts and toolchain

- `com.dji:dji-sdk:4.18` (aar, 59,037,696 bytes, published 2024-10-17) and
  `com.dji:dji-sdk-provided:4.18` (jar, 12,424 classes) both resolve from Maven Central.
- An isolated bench project (`/tmp/v4apk`; AGP 9.3.2, compileSdk 36, minSdk 24) with
  `implementation 'com.dji:dji-sdk:4.18'` + `compileOnly 'com.dji:dji-sdk-provided:4.18'`
  assembles successfully (`assembleDebug`; 198 MB debug APK with all three ABI trees
  unstripped, no `abiFilters`).

### The Java API is shell-loaded, not packaged

- The runtime AAR's `classes.jar` (38 KB) contains exactly one class,
  `com/cySdkyc/clx/Helper.class`, plus `dji/thirdparty/okhttp3/.../publicsuffixes.gz`.
- `Helper` is an application-shell entry point: native methods `attach`,
  `installApplicationEx`, `makeInMemoryDexElements`; static `cl` classloader, `new_so_dir`,
  `JNIPPATH`. The 12,424 API classes exist only in `dji-sdk-provided` (compile-only) and are
  supplied at runtime as in-memory dex elements.
- The current entry point is `com.cySdkyc.clx.Helper`; the older documentation's
  `com.secneo.sdk.Helper` does not exist in 4.18. Any V4 bootstrap must call
  `Helper.install(application)` from `Application.attachBaseContext()` before any SDK class is
  touched.
- Bench APK dex proof: `com/cySdkyc/clx/Helper` is present; `dji.sdk.sdkmanager.DJISDKManager`
  is absent. The only `dji/sdk` classes packaged are the transitive AAR `R` classes.
- Consequence for the plan: `dji-sdk-provided` must never be packaged (`implementation`) and
  must not leak into shared source. V4 adapter source compiles against classes that exist only
  at runtime through the shell.

### Undocumented resource dependencies

- The AAR's own layouts reference appcompat's `srcCompat` and ConstraintLayout attributes, but
  its POM declares neither. Resource linking fails without them
  (`processDebugResources`: `layout_constraintTop_toTopOf ... not found`). The official sample
  adds `androidx.appcompat` and `androidx.constraintlayout` itself; the Lyrebird app already
  carries both.

### Native libraries and packaging

- 78 `.so` entries across `arm64-v8a`, `armeabi-v7a`, `x86` (no `x86_64`); the AAR also ships
  `libs/*.jar` (netty 3.5.2, dji-rxjava 1.1.3, sqlcipher, retrofit 2.2.0, okio, ...).
- 18 of 31 `arm64-v8a` libraries include at least one LOAD segment aligned below 16 KB (4 KB).
  The baseline phone (below) uses 4 KB pages, so this is not a blocker for it; a 16 KB-page
  device/RC would need a DJI-published rebuild or an explicit compatibility decision.
- Merged manifest components in the bench APK: service `dji.sdk.sdkmanager.DJIGlobalService`
  and exported receiver `dji.logic.receiver.DJIPilotStartupReceiver` (`dji.go3.STARTUP`,
  `dji.go4.STARTUP`). The V4 bootstrap/session gate must account for these SDK-owned,
  app-uncontrolled components. AAR permissions include `SYSTEM_ALERT_WINDOW`, `GET_TASKS`,
  `WRITE_EXTERNAL_STORAGE`, Wi-Fi/location/WAKE_LOCK.

### Target device baseline (phone)

- HONOR BKQ-N49, Android 16 (SDK 36), arm64-v8a only, 4 KB pages, security patch 2026-08-01.
- The currentV5 debug APK (`com.lyrebird.rc`) installs, launches and runs on it with no
  startup crash (verified 2026-09-18).

## Open / not yet verified

- Registration/key: V4 requires `com.dji.sdk.API_KEY` meta-data for the V4 application id and a
  distinct key. No V4 key is provisioned; a missing key blocks registration, not neutral
  interface work.
- Video: `VideoFeeder`/`DJICodecManager` decoded-frame behavior (format, stride, lifetime) is
  unmeasured; the shell must load in a real V4 APK at runtime first.
- Flight: `FlightControlData` units/modes and the 5–25 Hz send cadence are unverified on
  hardware.
- Runtime load and release packaging: the bench assembled but was never installed or launched;
  minify/shrink rules and `jniLibs` packaging behavior still need a device check.
- Co-installation with the V5 APK (network ports, session lease, distinct app id) is untested.

## Reproduction

```bash
mkdir -p /tmp/v4probe && cd /tmp/v4probe
curl -O https://repo1.maven.org/maven2/com/dji/dji-sdk/4.18/dji-sdk-4.18.aar
curl -O https://repo1.maven.org/maven2/com/dji/dji-sdk-provided/4.18/dji-sdk-provided-4.18.jar
# /tmp/v4apk: single-module AGP project with the two dependencies, one class referencing
# com.cySdkyc.clx.Helper and dji.sdk.sdkmanager.DJISDKManager; assembleDebug; then inspect
# classes*.dex strings, jni ELF alignment (readelf -lW) and the merged manifest.
```
