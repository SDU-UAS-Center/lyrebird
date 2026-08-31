#!/usr/bin/env bash
# Starts the DjiNode controller(s) (optional) and the topic monitor that
# reports to the Lyrebird webapp. Runs inside the ros-monitor container.
set -eo pipefail
# ROS setup.bash reads unset variables (AMENT_TRACE_SETUP_FILES), so source it
# with nounset disabled, then re-enable it.
set +u
# shellcheck disable=SC1091
source /opt/ros/humble/setup.bash
# shellcheck disable=SC1091
source /opt/ros_ws/install/setup.bash
set -u

CONTROLLER_PID=""

cleanup() {
  if [[ -n "$CONTROLLER_PID" ]]; then
    kill "$CONTROLLER_PID" 2>/dev/null || true
  fi
}
trap cleanup EXIT

# Auto-discover Lyrebird drones and launch one namespaced lyrebird_controller
# node per drone (publishes telemetry under /<drone>/fmu/out/... and listens
# for commands under /<drone>/fmu/in/...), re-scanning periodically for newly
# joined drones, unless disabled. This is the same auto_discovery_native.launch.py
# the rest of the ROS 2 stack uses (see GroundStation/ROS/lyrebird_bringup).
if [[ "${ROS_RUN_CONTROLLER:-1}" == "1" ]]; then
  echo "[ros-monitor] discovering drones and starting namespaced lyrebird_controller nodes"
  ros2 launch /opt/lyrebird/launch/auto_discovery_native.launch.py &
  CONTROLLER_PID=$!
fi

echo "[ros-monitor] starting ros_monitor"
exec python3 /opt/lyrebird/ros_monitor/ros_monitor.py
