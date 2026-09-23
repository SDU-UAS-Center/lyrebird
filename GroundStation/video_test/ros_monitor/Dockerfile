# Lyrebird ROS bridge monitor container.
# Build context is the repository root (./) so it can COPY the shared
# GroundStation packages; the root .dockerignore keeps the context small.
FROM ros:humble-ros-base

ENV PYTHONUNBUFFERED=1
# uv installs the Python side; these keep its output small and reproducible.
ENV UV_LINK_MODE=copy
ENV UV_PYTHON_DOWNLOADS=never

# python3-pip stays: colcon installs the ament_python packages below through it.
RUN --mount=type=cache,target=/var/cache/apt,sharing=locked \
    --mount=type=cache,target=/var/lib/apt/lists,sharing=locked \
    apt-get update \
    && apt-get install -y --no-install-recommends python3-pip python3-colcon-common-extensions \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /opt/lyrebird

# pyproject.toml + uv.lock are the dependency source: the ros-monitor group is numpy
# for the controller node plus pymavlink, because this container's DjiNodes register
# with the shared MAVLink router. `uv pip` rather than a sync, since ROS's own Python
# modules are apt-managed and a sync would remove them. The client keeps its
# repository-relative path so uv resolves the workspace member the lock was written for.
COPY pyproject.toml uv.lock ./
COPY GroundStation/Python/ GroundStation/Python/
RUN --mount=from=ghcr.io/astral-sh/uv:0.12.17,source=/uv,target=/bin/uv \
    --mount=type=cache,target=/root/.cache/uv \
    uv export --quiet --locked --no-emit-project --no-emit-workspace \
        --only-group ros-monitor --output-file /tmp/locked.txt \
    && uv pip install --system --break-system-packages --no-cache --requirements /tmp/locked.txt \
    && uv pip install --system --break-system-packages --no-cache ./GroundStation/Python \
    && rm -f /tmp/locked.txt

# lyrebird_msgs (the PX4-DDS-style message definitions) and the ROS
# lyrebird_controller node (publishes telemetry + fmu/out/settings), built as
# real colcon packages so `ros2 launch`/`ros2 run` can resolve the
# `lyrebird_controller` executable the same way the rest of the ROS 2 stack
# does (see GroundStation/ROS/lyrebird_bringup's launch files), and so
# ros_monitor.py can import both lyrebird_msgs.msg and
# lyrebird_controller.topics (the shared topic/QoS registry).
COPY GroundStation/ROS/lyrebird_msgs /opt/ros_ws/src/lyrebird_msgs
COPY GroundStation/ROS/lyrebird_controller /opt/ros_ws/src/lyrebird_controller
RUN . /opt/ros/humble/setup.sh \
    && cd /opt/ros_ws \
    && colcon build --packages-select lyrebird_msgs lyrebird_controller --symlink-install

# The fleet auto-discovery launch file: one FleetAutoDiscoveryManager node that re-scans
# periodically and gives each discovered drone its own namespaced lyrebird_controller_<name>
# DjiNode (see lyrebird_controller/fleet_auto_discovery.py).
COPY GroundStation/ROS/lyrebird_bringup/launch/fleet_auto_discovery.launch.py /opt/lyrebird/launch/fleet_auto_discovery.launch.py

COPY GroundStation/video_test/ros_monitor/ros_monitor.py /opt/lyrebird/ros_monitor/ros_monitor.py
COPY GroundStation/video_test/ros_monitor/entrypoint.sh /opt/lyrebird/entrypoint.sh

RUN chmod +x /opt/lyrebird/entrypoint.sh

ENTRYPOINT ["/opt/lyrebird/entrypoint.sh"]
