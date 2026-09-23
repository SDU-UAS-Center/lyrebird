# AGENTS.md — Lyrebird

Lyrebird is a source-available Android ground-station app (Kotlin + DJI Mobile SDK V5) that runs on the RC's Android system — built into controllers like the RC Pro/RC Plus, or on a phone connected to a non-smart controller like the RC-N3 — turning it into a networked drone server, speaking MAVLink 2 and HTTP side by side — both on by default — plus TCP telemetry, WHIP/WHEP video publishing, and auto-discovery. HTTP is kept alongside MAVLink for compatibility with ground stations built against this project's predecessor, WildBridge, and as the API for what MAVLink doesn't cover (media download, AI detections, live settings). It is paired with a Python GroundStation library, ROS 2 packages (Lyrical container default, Humble supported), and a Docker MediaMTX/video-test stack. The code is licensed under BUSL-1.1 with a planned MIT conversion; see `LICENSE`. This file tells coding agents how the repo is laid out and how to work in it safely.

## Project Overview

- **Android app** (Kotlin, DJI MSDK V5.18.0): `LyrebirdApp/android-sdk-v5-as/` — build root for `:app`, `:lyrebird-core`, and `:uxsdk`. The supported variant is `currentV5`; the declared `v4` flavor stays disabled until its adapter is implemented. `:uxsdk` is stock DJI UXSDK with a few intentional modifications.
- **SDK-free Android core**: `LyrebirdApp/lyrebird-core/` — MAVLink wire/protocol logic and aircraft contracts shared by the app. It must not depend on either DJI SDK or on `:app`.
- **Python GroundStation**: `GroundStation/Python/lyrebird_groundstation/` — `dji_client.py` (`DJIInterface`), `dji_helpers.py`, `mavlink_helpers.py`; `lyrebird_dji_helpers.py` is a compatibility shim.
- **ROS 2**: `GroundStation/ROS/` — `lyrebird_msgs`, `lyrebird_controller`, `lyrebird_videofeed`, and `lyrebird_bringup`. `GroundStation/Dockerfile` defaults to `ros:lyrical-ros-base`; `ROS_DISTRO=humble` selects the alternative. Fleet discovery and settings live in `lyrebird_controller/fleet_auto_discovery.py` and `config/fleet_settings.yaml`.
- **Fleet and aircraft profiles**: process-owned fleet discovery/status and settings keyed by aircraft serial. Keep identity and profile selection independent of the attached screen.
- **Video test stack**: `GroundStation/video_test/compose.yaml` — MediaMTX + a browser dashboard in `GroundStation/video_test/webapp/`.
- **Safety model**: two-computer authority. A Safety Computer can seize command authority via the `X-Safety-Token` header; takeover is persistent and only the Safety Computer returns control. Safety-adjacent logic lives in `GroundStation/Python/djiInterfaceSafety.py`, `GroundStation/Python/lyrebird_groundstation/safety.py`, and on-device.
- **Key ports on each drone**: MAVLink 2 `14550` (UDP), HTTP commands `8080`, TCP telemetry `8081`, UDP discovery `30000` (+ mDNS, subnet scan), WHIP/WHEP video via MediaMTX.
- **Supported public video path**: WHIP publish → MediaMTX → WHEP playback. The older direct RC-hosted WebSocket-signaling video viewer/server path has been removed from the public app and tooling; do not reintroduce it.

## Coding Agent Workflow

1. Preserve unrelated worktree changes. Read `git status` and the relevant diff before editing a modified file; never reset or overwrite user work.
2. Run checks from the repository root for Python (see Quality Gates), and from `LyrebirdApp/android-sdk-v5-as/` for Gradle work. The persistent terminal keeps its working directory — be explicit about which root a command belongs to.
3. Start from the failing behavior, owning abstraction, nearby test, or exact log entry. Avoid broad refactors until a focused check proves the controlling path.
4. GroundStation Python: keep pure helpers extracted before touching ROS/MAVLink/socket-bound behavior. When changing anything on the MAVLink wire, check it against the HTTP surface with the dashboard's MAVLink tab — every defect in that work so far has been a frame that decoded cleanly and meant the wrong thing, which no compiler or linter sees. Do not hide missing dependencies behind conditional imports or `try/except` import fallbacks (except documented cross-container cases).
5. Make the smallest coherent change, run the narrowest relevant test immediately, then widen validation in proportion to risk.
6. Keep code, comments, and documentation in English.

### Local History Archives

Preserve local archive branches, historical tags, and Git bundles. Push an explicit branch ref when publishing changes; do not upload local history with `git push --all`, `--mirror`, or `--tags` unless the user explicitly requests it.

### Generated and Runtime Files

- Never edit `build/`, `**/outputs/`, `dist/`, caches, `*.apk`, `*.ndjson`, `GroundStation/video_test/logs/`, or other generated/runtime artifacts by hand.
- `local.properties` (Android SDK path + DJI API key) is per-machine and never committed.
- Do not commit deployment secrets, `google-services.json`, keystores, recordings, or runtime state.

## Quick Start

### Python GroundStation

Python is managed by [uv](https://docs.astral.sh/uv/): `uv.lock` is committed, `.python-version` pins 3.14.7 and uv fetches that interpreter itself. ROS images use their distribution's Python; the shared client must keep importing on 3.10 for Humble — CI tests 3.14.7 and 3.10 from the one lock.

```bash
uv sync                                          # client + dev tools, from the lock
uv run --locked pytest GroundStation/tests -q
uv run --locked python -m compileall -q GroundStation/Python GroundStation/ROS
uv sync --group ros --group mavlink               # local ROS Python dependencies; ROS 2 must be installed separately
uv run --locked --group mavlink lyrebird-mavlink-listen   # only for mavlink_listen.py
```

### Android

```bash
cd LyrebirdApp/android-sdk-v5-as
cp local.properties.example local.properties   # set sdk.dir and AIRCRAFT_API_KEY
./gradlew :lyrebird-core:compileDebugKotlin :app:compileCurrentV5DebugKotlin
./gradlew :app:assembleCurrentV5Debug        # current/v5 variant
./auto_install_on_connect.sh currentV5 --build # build+install to a connected device
```

Shared code belongs in `lyrebird-core/src/main` or SDK-neutral app `src/main`. DJI-facing implementations, manifests, resources, and the Flight Deck belong in app `src/v5`. Keep the `v4` flavor disabled until its SDK dependencies and platform adapter are provisioned.

### ROS 2 stack

From the repository root:

```bash
./GroundStation/run_docker.sh                    # build and run ROS 2 Lyrical
ROS_DISTRO=humble ./GroundStation/run_docker.sh   # build and run the Humble alternative
```

The container starts `fleet_auto_discovery.launch.py`. Each aircraft receives a namespace and settings from `GroundStation/ROS/lyrebird_controller/config/fleet_settings.yaml`. The fleet shares one ground-station MAVLink listener; keep its port separate from other ground stations on the same host.

### Video test stack

```bash
docker compose -f GroundStation/video_test/compose.yaml up -d --build
# dashboard: http://localhost:8090   MediaMTX WHIP/WHEP: http://localhost:8889
# RTSP: rtsp://localhost:8554  MediaMTX API: http://localhost:9997  ICE UDP: :8189
docker compose -f GroundStation/video_test/compose.yaml down
```

Runtime diagnostics land in `GroundStation/video_test/logs/` (git-ignored).

### Documentation site

```bash
npm install
npm run dev      # local preview at http://localhost:4321/lyrebird/
npm run build    # production build into dist/ (validate doc edits with this)
```

- Starlight (Astro) site at the repo root. Content lives in `src/content/docs/` (`.md`/`.mdx`); the sidebar is configured in `astro.config.mjs`; `src/content.config.ts` wires the Starlight content collection.
- Images are **not duplicated**: pages reference the shared copies under `docs/images/`.
- `.github/workflows/docs.yml` builds and deploys to GitHub Pages on pushes to `main` (repo setting: Pages → Source = GitHub Actions). The deployed URL follows the fork that runs it, e.g. `https://<owner>.github.io/lyrebird/` — `astro.config.mjs` sets `site`/`base` for `SDU-UAS-Center.github.io`; adjust when deploying from another fork.
- New pages must be added to the `sidebar` in `astro.config.mjs`; keep page slugs kebab-case.

## Project Structure

| Path | Purpose |
|------|---------|
| `LyrebirdApp/android-sdk-v5-as/` | Android build root (`:app`, `:lyrebird-core`, `:uxsdk`); module source directories are mapped in `settings.gradle` |
| `LyrebirdApp/lyrebird-app/` | App source (`com.lyrebird.rc`): SDK-neutral `src/main`; V5 adapters, resources, navigation, and FlightDeckActivity in `src/v5` |
| `LyrebirdApp/lyrebird-core/` | SDK-free MAVLink protocol, wire messages, CRC, and shared aircraft contracts |
| `GroundStation/Python/lyrebird_groundstation/` | Shared Python helper package (dji_client, dji_helpers, mavlink_helpers, transport) |
| `GroundStation/mavlink/lyrebird.xml` | The Lyrebird MAVLink dialect. Source of truth for `LYREBIRD_STATUS`. Regenerate with mavgen and update `transport.py` (struct, size, CRC_EXTRA) together with `lyrebird-core`'s `MavlinkMessages.kt` wire layout and `MavlinkCrc.kt` CRC_EXTRA. mavgen sorts fields by type size on the wire for MAVLink 1/2 — XML declaration order is not the wire order |
| `GroundStation/Python/djiInterfaceSafety.py` | Safety-authority handling for the two-computer model |
| `GroundStation/ROS/lyrebird_controller/` | ROS package wrapping DJI control |
| `GroundStation/ROS/lyrebird_videofeed/` | ROS package for video feed |
| `GroundStation/ROS/lyrebird_bringup/` | Launch/config for the ROS stack |
| `GroundStation/ROS/lyrebird_msgs/` | ROS message definitions shared by the ROS packages |
| `GroundStation/ROS/lyrebird_controller/config/fleet_settings.yaml` | Fleet transport, discovery, MAVLink ports, and settings with per-aircraft overrides |
| `GroundStation/Dockerfile` | ROS 2 Lyrical image, or Humble via `ROS_DISTRO`; installs the locked `ros` and `mavlink` dependency groups |
| `GroundStation/tests/` | Pytest suite for the Python ground-station and video-test components |
| `GroundStation/video_test/` | MediaMTX config + webapp for the video dashboard |
| `GroundStation/qgc/` | QGroundControl MAVLink Actions config — Takeoff/Land/RTL Fly View buttons (`lyrebird-actions.json`) |
| `scripts/check_radon_complexity.py` | Complexity gate used by pre-commit/CI |
| `scripts/check_main_sdk_free.py` | Enforces the `src/main` SDK-neutral boundary (pre-commit/CI) |
| `scripts/check_process_ownership.py` | Enforces that the process runtimes stay screen-free (pre-commit/CI) |
| `GroundStation/video_test/compose.yaml` | MediaMTX + dashboard compose file |
| `src/content/docs/` | Starlight documentation content |
| `astro.config.mjs` | Starlight site config: sidebar, edit links, base path |
| `pyproject.toml` + `uv.lock` | Python toolchain: the uv workspace (`GroundStation/Python`) and the dependency groups CI, pre-commit and the container images install from |
| `.github/workflows/ci.yml` | GroundStation Python gates plus Lyrebird-owned Android Spotless/compile/unit tests on PRs and `main` |
| `.github/workflows/docs.yml` | Builds the Starlight site and deploys it to GitHub Pages on `main` |

## Configuration Notes

- `pyproject.toml` is the source of truth for Ruff (line length 100, Python 3.10 target for Humble compatibility), pytest paths (`GroundStation/Python` + `GroundStation/video_test/webapp`), mypy scope, bandit scope, and the uv workspace plus dependency groups. `uv.lock` is committed; `.python-version` pins 3.14.7 for local development. CI also tests 3.10, and ROS images use their distribution's Python.
- Android SDK/API key live in `local.properties` (never committed).
- MediaMTX behavior is defined in `GroundStation/video_test/mediamtx.yml`.

## Testing & Quality Gates

Local pre-commit hooks and `.github/workflows/ci.yml` run the same checks.
Treat a failing local hook as part of finishing the change — CI will fail the same way on
pull requests and pushes to `main`.

```bash
uv run --locked pre-commit install
uv run --locked pre-commit run --all-files
```

Hooks are configured in `.pre-commit-config.yaml`, with corresponding checks in CI. CI installs
Python tools from the dependency groups in `pyproject.toml` through `uv run --locked`. Local
Radon and pytest hooks also use uv; Ruff, Mypy, and Bandit use their pinned pre-commit environments:

- **Ruff lint + format** — scoped to `GroundStation/**.py` (`ruff check --fix` locally, `ruff check` / `ruff format --check` in CI)
- **Radon complexity** — `uv run --locked --only-group metrics python scripts/check_radon_complexity.py`, B-or-better blocks (blocks with cyclomatic complexity ≥ 11 fail)
- **Mypy** — gradual typing over `GroundStation/Python/lyrebird_groundstation` + `lyrebird_dji_helpers.py`
- **Bandit** — `bandit -r GroundStation -ll --skip B101`
- **GroundStation tests** — `uv run --locked --group test python -m pytest GroundStation/tests -q` (manual stage locally; CI runs it on 3.14.7 and on the ROS container's 3.10)
- **Android Spotless** — `./gradlew :app:spotlessKotlinCheck :lyrebird-core:spotlessKotlinCheck` on Lyrebird-owned app and core Kotlin
- **Android compile + unit tests** — `./gradlew :lyrebird-core:compileDebugKotlin :lyrebird-core:testDebugUnitTest :app:compileCurrentV5DebugKotlin :app:testCurrentV5DebugUnitTest` (manual stage locally; always run in CI, with `qualityLyrebird` Detekt/Lint reports)
- **Main SDK-free** — `python3 scripts/check_main_sdk_free.py`, `src/main` carries no DJI or flavor-only reference in its sources, shared manifest or shared resources
- **Process ownership** — `python3 scripts/check_process_ownership.py`, the process runtimes stay screen-free (see below)

Run the manual test hooks with:

```bash
uv run --locked pre-commit run groundstation-tests --hook-stage manual --all-files
uv run --locked pre-commit run android-tests --hook-stage manual --all-files
```

Vendor DJI/UXSDK quality stays out of the required gate (`qualityDji`); do not fail CI on inherited sample code.

## Code Conventions

- All code, comments, and docs in English; Python type hints expected.
- Imports at module scope, no conditional-import flags; add new packages to the relevant dependency group in the root `pyproject.toml` and re-run `uv lock` — CI and the container images install from that lock, not from a `requirements.txt`.
- Follow existing patterns when adding GroundStation helpers, ROS nodes, or app pages; register new Android pages in `data/AircraftFragmentPageInfoFactory.kt` and the nav graph.
- Keep generated/runtime artifacts out of Git.
- **Preserve the SDK boundary.** `:lyrebird-core` has no DJI or app dependency. App `src/main` can use shared contracts and neutral ports, but cannot reference DJI classes or flavor-only implementations. Put SDK-specific implementations in `src/v5` (and eventually `src/v4`); the shared manifest and resources follow the same boundary.
- **The ground-station runtimes are process-scoped, never screen-scoped.** HTTP, MAVLink, telemetry, discovery, streaming, the payload and flight command paths, the obstacle guard and the settings backup all run on the aircraft's process and must keep working with no Flight Deck attached: an RC whose screen is closed, or whose activity is being recreated, keeps serving. Concretely:
  - SDK-facing runtimes attach from `ProcessAppRuntime` (network session at process start; telemetry, streaming and backup on the SDK's `onRegisterSuccess` — a DJI key cannot be created before that).
  - What a screen contributes goes behind a `*Ui` contract (`CommandSurfaceUi`, `StreamingRuntimeUi`, `ObstacleGuardUi`), attached weakly and identity-guarded, implemented only by `FlightDeckActivity`; `src/v5/java/com/lyrebird/rc/server/` must not mention an activity at all (`scripts/check_process_ownership.py` enforces both).
  - Configuration a runtime needs comes from preferences, the aircraft, or the device — not from an attached screen (`V5StreamingSettings` is the model). If a value would have to come from a screen, the design is wrong, not the check.
  - Vendor widgets that command the aircraft are **operator controls**, in the same class as the RC's own buttons: the authority seam governs what a *remote* pilot may command, not what the person holding the controller may, so they stay. Lyrebird's job is then to stay in step — the flight-state machine and the controller's status follow the aircraft's own telemetry, so a take-off from one of them is still logged, still starts the detection runtime, and still makes a later mission wait out the climb (see `V5FlightStateEffects` and `DroneController.onTelemetryTakeoff`).
- Safety-critical changes (authority takeover, virtual-stick, control loops, RTH) deserve extra tests and explicit review; never weaken the takeover semantics.

## Commit Attribution

- Do **not** add a `Co-Authored-By:` line for AI to commit messages.
- If acknowledging AI assistance, append a short plain-text comment at the very end of the commit message, using the actual model name: `AI-assisted by <model name>.`
- Other human `Co-Authored-By:` trailers are fine and must be kept.
