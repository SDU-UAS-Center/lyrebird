# Lyrebird ROS bridge monitor container.
# Build context is the repository root (./) so it can COPY the shared
# GroundStation packages; the root .dockerignore keeps the context small.
FROM ros:humble-ros-base

ENV PYTHONUNBUFFERED=1

RUN apt-get update \
    && apt-get install -y --no-install-recommends python3-pip python3-colcon-common-extensions \
    && rm -rf /var/lib/apt/lists/*

# Ubuntu 22.04 ships pip 22, which cannot build PEP 621 projects and installs
# an empty "UNKNOWN" distribution; upgrade before installing anything local.
RUN python3 -m pip install --no-cache-dir --upgrade pip

COPY GroundStation/video_test/ros_monitor/requirements.txt /opt/lyrebird/requirements.txt
RUN pip3 install --no-cache-dir -r /opt/lyrebird/requirements.txt

# Shared GroundStation client used by the ROS controller and the monitor.
# Installed as a distribution, so both import it from site-packages.
COPY GroundStation/Python /opt/lyrebird/groundstation
RUN pip3 install --no-cache-dir /opt/lyrebird/groundstation

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

# The auto-discovery launch file that starts one namespaced lyrebird_controller node per
# discovered drone, unmodified apart from the added periodic re-scan.
COPY GroundStation/ROS/lyrebird_bringup/launch/auto_discovery_native.launch.py /opt/lyrebird/launch/auto_discovery_native.launch.py

COPY GroundStation/video_test/ros_monitor/ros_monitor.py /opt/lyrebird/ros_monitor/ros_monitor.py
COPY GroundStation/video_test/ros_monitor/entrypoint.sh /opt/lyrebird/entrypoint.sh

RUN chmod +x /opt/lyrebird/entrypoint.sh

ENTRYPOINT ["/opt/lyrebird/entrypoint.sh"]
