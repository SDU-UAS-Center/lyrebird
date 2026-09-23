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

# Auto-discover Lyrebird drones and give each its own namespaced lyrebird_controller_<name>
# DjiNode (publishes telemetry under /<drone>/fmu/out/... and listens for commands under
# /<drone>/fmu/in/...), re-scanning periodically for newly joined drones, unless disabled. This
# is the same fleet_auto_discovery.launch.py the rest of the ROS 2 stack uses (see
# GroundStation/ROS/lyrebird_bringup).
if [[ "${ROS_RUN_CONTROLLER:-1}" == "1" ]]; then
  echo "[ros-monitor] discovering drones and starting namespaced lyrebird_controller nodes"
  launch_args=()
  # ROS_DISCOVERY_PERIOD overrides fleet_settings.yaml's discovery_period_sec -- kept as an env
  # var here (rather than editing the YAML) so this test container can rescan faster than a
  # real fleet deployment would want to.
  if [[ -n "${ROS_DISCOVERY_PERIOD:-}" ]]; then
    launch_args+=("discovery_period_sec:=${ROS_DISCOVERY_PERIOD}")
  fi
  ros2 launch /opt/lyrebird/launch/fleet_auto_discovery.launch.py "${launch_args[@]}" &
  CONTROLLER_PID=$!
fi

echo "[ros-monitor] starting ros_monitor"
exec python3 /opt/lyrebird/ros_monitor/ros_monitor.py
