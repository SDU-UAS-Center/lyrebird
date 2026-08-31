#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VARIANT="current"
BUILD=false
CHECK_ONLY=false
# Which device to install onto. Empty means "the only one attached", which is what a bare adb
# assumes and the reason this script used to fail outright with two RCs plugged in: adb refuses
# every command with "more than one device" rather than picking one. A fleet is the normal case
# here, so the choice has to be expressible.
SERIAL=""
ALL_DEVICES=false

while [[ $# -gt 0 ]]; do
  case "$1" in
    --build)
      BUILD=true
      ;;
    --check)
      CHECK_ONLY=true
      ;;
    --serial)
      SERIAL="${2:-}"
      if [[ -z "$SERIAL" ]]; then
        echo "--serial needs a device serial (see: adb devices)" >&2
        exit 1
      fi
      shift
      ;;
    --serial=*)
      SERIAL="${1#--serial=}"
      ;;
    --all)
      ALL_DEVICES=true
      ;;
    current)
      VARIANT="current"
      ;;
    demoBiomass|demo_biomass)
      VARIANT="demoBiomass"
      ;;
    *)
      echo "Usage: $0 [current|demoBiomass|demo_biomass] [--build] [--check]" >&2
      echo "          [--serial SERIAL | --all]" >&2
      exit 1
      ;;
  esac
  shift
done

if [[ "$ALL_DEVICES" == true && -n "$SERIAL" ]]; then
  echo "--all and --serial are mutually exclusive" >&2
  exit 1
fi

if [[ "$VARIANT" == "demoBiomass" ]]; then
  PACKAGE_NAME="com.lyrebird.rc.demo_biomass"
else
  PACKAGE_NAME="com.lyrebird.rc"
fi
TASK_NAME="assemble${VARIANT^}Debug"
APK_PATH="$ROOT_DIR/../lyrebird-app/build/outputs/apk/$VARIANT/debug/Lyrebird-debug.apk"
LAUNCH_ACTIVITY="com.lyrebird.rc.DJIAircraftMainActivity"

use_android_sdk() {
  export ANDROID_HOME="$1"
  export ANDROID_SDK_ROOT="$1"
  export PATH="$1/platform-tools:$PATH"
}

ensure_android_sdk_configured() {
  if [[ -n "${ANDROID_HOME:-}" && -d "$ANDROID_HOME" ]]; then
    use_android_sdk "$ANDROID_HOME"
    return
  fi
  if [[ -n "${ANDROID_SDK_ROOT:-}" && -d "$ANDROID_SDK_ROOT" ]]; then
    use_android_sdk "$ANDROID_SDK_ROOT"
    return
  fi
  if [[ -f "$ROOT_DIR/local.properties" ]] && grep -q '^sdk\.dir=' "$ROOT_DIR/local.properties"; then
    sdk_dir="$(grep -m1 '^sdk\.dir=' "$ROOT_DIR/local.properties" | cut -d= -f2- | tr -d '\r')"
    if [[ -d "$sdk_dir" ]]; then
      use_android_sdk "$sdk_dir"
      return
    fi

    echo "Android SDK location in $ROOT_DIR/local.properties does not exist: $sdk_dir" >&2
    echo "Set sdk.dir to the actual SDK path, or export ANDROID_HOME=/path/to/Android/Sdk." >&2
    exit 1
  fi

  echo "Android SDK location not configured." >&2
  echo "Create $ROOT_DIR/local.properties with a valid sdk.dir, for example:" >&2
  echo "  sdk.dir=$HOME/Android/Sdk" >&2
  echo "Or export ANDROID_HOME=/path/to/Android/Sdk before running this script." >&2
  echo "Template: $ROOT_DIR/local.properties.example" >&2
  exit 1
}

ensure_java_compiler_configured() {
  if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/javac" ]]; then
    return
  fi
  if command -v javac >/dev/null 2>&1; then
    return
  fi

  local_jdk="$HOME/.jdks/temurin-21"
  if [[ -x "$local_jdk/bin/javac" ]]; then
    export JAVA_HOME="$local_jdk"
    export PATH="$JAVA_HOME/bin:$PATH"
    return
  fi

  echo "Java compiler not found. Install a JDK or set JAVA_HOME to a JDK directory." >&2
  echo "Current Java runtime: $(command -v java || echo not found)" >&2
  exit 1
}

build_selected_variant() {
  ensure_android_sdk_configured
  ensure_java_compiler_configured
  "$ROOT_DIR/gradlew" -p "$ROOT_DIR" ":app:$TASK_NAME" --warning-mode summary
}

if [[ "$CHECK_ONLY" == true ]]; then
  echo "Variant: $VARIANT"
  echo "Package: $PACKAGE_NAME"
  echo "APK: $APK_PATH"
  if [[ -f "$APK_PATH" ]]; then
    echo "APK status: found"
  else
    echo "APK status: missing"
  fi
  exit 0
fi

if [[ "$BUILD" == true ]]; then
  build_selected_variant
fi

if [[ ! -f "$APK_PATH" ]]; then
  echo "APK not found, building $VARIANT first: $APK_PATH" >&2
  build_selected_variant
fi

if [[ ! -f "$APK_PATH" ]]; then
  APK_PATH="$(find "$ROOT_DIR/../lyrebird-app/build/outputs/apk/$VARIANT" -type f -name "Lyrebird-debug.apk" -print -quit 2>/dev/null || true)"
fi

if [[ -z "$APK_PATH" || ! -f "$APK_PATH" ]]; then
  echo "APK not found after build for variant: $VARIANT" >&2
  echo "Expected under: $ROOT_DIR/../lyrebird-app/build/outputs/apk/$VARIANT" >&2
  exit 1
fi

ensure_android_sdk_configured

# Install onto one device and start the app there.
install_to() {
  local target="$1"
  local model product
  model="$(adb -s "$target" shell getprop ro.product.model 2>/dev/null | tr -d '\r' || true)"
  product="$(adb -s "$target" shell getprop ro.product.product.name 2>/dev/null | tr -d '\r' || true)"

  echo
  echo "Device: serial=$target model=${model:-unknown} product=${product:-unknown}"
  echo "Installing $VARIANT: $APK_PATH"
  local remote_apk="/data/local/tmp/lyrebird-${VARIANT}-debug.apk"
  # Stop the app and bypass adb's wireless streaming installer. Pushing the APK first lets the
  # device-side package manager read it locally instead of keeping the streamed install session
  # open over an unstable Wi-Fi link.
  adb -s "$target" shell am force-stop "$PACKAGE_NAME"
  adb -s "$target" push "$APK_PATH" "$remote_apk"
  if adb -s "$target" shell pm install -r -t "$remote_apk"; then
    adb -s "$target" shell rm -f "$remote_apk"
  else
    adb -s "$target" shell rm -f "$remote_apk"
    return 1
  fi

  echo "Launching $PACKAGE_NAME"
  adb -s "$target" shell am start -n "$PACKAGE_NAME/$LAUNCH_ACTIVITY" >/dev/null || \
    adb -s "$target" shell monkey -p "$PACKAGE_NAME" -c android.intent.category.LAUNCHER 1 >/dev/null
}

# Serials of everything currently attached and ready. Devices listed as "offline",
# "unauthorized" or "no permissions" are excluded rather than attempted, so a stale entry for an
# RC that has been unplugged does not fail the run.
attached_devices() {
  adb devices | awk 'NR > 1 && $2 == "device" { print $1 }'
}

mapfile -t devices < <(attached_devices)

# Only actually wait when nothing is ready yet. A bare `adb wait-for-device` refuses outright
# once two devices are attached -- "more than one device/emulator" -- so waiting unconditionally
# breaks the fleet case this script exists to serve.
if [[ ${#devices[@]} -eq 0 || -n "$SERIAL" ]]; then
  echo "Waiting for a DJI RC / Android device over ADB..."
  if [[ -n "$SERIAL" ]]; then
    adb -s "$SERIAL" wait-for-device
  else
    adb wait-for-device
  fi
  mapfile -t devices < <(attached_devices)
fi

if [[ ${#devices[@]} -eq 0 ]]; then
  echo "No ready device attached. Check 'adb devices' — an entry marked offline or" >&2
  echo "unauthorized needs the RC's screen unlocked and the debugging prompt accepted." >&2
  exit 1
fi

if [[ -n "$SERIAL" ]]; then
  # shellcheck disable=SC2076  # literal match is intended: serials are compared exactly
  if [[ ! " ${devices[*]} " =~ " ${SERIAL} " ]]; then
    echo "Device not attached: $SERIAL" >&2
    printf 'Attached: %s\n' "${devices[*]}" >&2
    exit 1
  fi
  targets=("$SERIAL")
elif [[ "$ALL_DEVICES" == true ]]; then
  targets=("${devices[@]}")
elif [[ ${#devices[@]} -gt 1 ]]; then
  # Refused rather than guessed: installing the wrong build on the aircraft someone is about to
  # fly is worse than stopping, and with two RCs on a bench there is no safe default.
  echo "More than one device attached; choose one with --serial, or --all for every device:" >&2
  for device in "${devices[@]}"; do
    model="$(adb -s "$device" shell getprop ro.product.model 2>/dev/null | tr -d '\r' || true)"
    printf '  %s  %s\n' "$device" "${model:-unknown}" >&2
  done
  exit 1
else
  targets=("${devices[@]}")
fi

for target in "${targets[@]}"; do
  install_to "$target"
done

echo
echo "Done. PID/control profile is selected inside the app from DJI ProductKey.KeyProductType."
