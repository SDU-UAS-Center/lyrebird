# ROS 2 Lyrical (the current LTS) is the default base. The legacy Humble stack stays
# supported from this same Dockerfile:  docker build --build-arg ROS_DISTRO=humble ...
#
# The -ros-base variant is named explicitly rather than the bare distro tag: those two
# tags are the same image today (same manifest digests), but naming the variant means
# a future tag move cannot silently pull something heavier than this stack needs.
ARG ROS_DISTRO=lyrical
FROM ros:${ROS_DISTRO}-ros-base

# Re-declared so the build steps below see it, and promoted to the container
# environment for the entrypoint.
ARG ROS_DISTRO
ENV ROS_DISTRO=${ROS_DISTRO}

# Set environment variables
ENV DEBIAN_FRONTEND=noninteractive
ENV PYTHONUNBUFFERED=1
# uv is the Python installer here; these keep its output small and offline-reproducible.
ENV UV_LINK_MODE=copy
ENV UV_PYTHON_DOWNLOADS=never

# Create the dds logs directory with proper permissions
RUN mkdir -p /.dds/log && chmod 777 /.dds/log

# Create workspace directories
RUN mkdir -p /ros2_ws/src
WORKDIR /ros2_ws

# Install system dependencies (python3-pip stays: colcon installs the ament_python
# packages below through it; uv installs everything on the Python side)
RUN --mount=type=cache,target=/var/cache/apt,sharing=locked \
    --mount=type=cache,target=/var/lib/apt/lists,sharing=locked \
    apt-get update && apt-get install -y --no-install-recommends \
    ros-${ROS_DISTRO}-rmw-cyclonedds-cpp \
    ros-${ROS_DISTRO}-cv-bridge \
    ros-${ROS_DISTRO}-vision-opencv \
    ros-${ROS_DISTRO}-image-transport \
    python3-pip \
    libgl1 \
    libglib2.0-0 \
    iputils-ping \
    net-tools \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /opt/lyrebird

# uv.lock is the dependency source for the Python side, and the client is installed
# as a real distribution so the ROS nodes and any script in the image import it from
# site-packages without PYTHONPATH hacks. The client keeps its repository-relative
# path: `uv` resolves it as the workspace member the lock was written for.
COPY pyproject.toml uv.lock ./
COPY GroundStation/Python/ GroundStation/Python/

# ROS packages
COPY GroundStation/ROS /ros2_ws/src/

# ROS Python modules are apt-managed, so the exact locked groups are installed with
# `uv pip` into system Python rather than synced (a sync would remove them). uv is
# mounted for the build only. The mavlink group joins the ros group because the
# fleet auto-discovery manager's transport router imports pymavlink in start().
RUN --mount=from=ghcr.io/astral-sh/uv:0.12.17,source=/uv,target=/bin/uv \
    --mount=type=cache,target=/root/.cache/uv \
    uv export --quiet --locked --no-emit-project --no-emit-workspace \
        --only-group ros --only-group mavlink --output-file /tmp/locked.txt \
    && uv pip install --system --break-system-packages --no-cache --requirements /tmp/locked.txt \
    && uv pip install --system --break-system-packages --no-cache ./GroundStation/Python \
    && rm -f /tmp/locked.txt

# Build the ROS workspace (from /ros2_ws: the install space the entrypoint sources)
RUN /bin/bash -c "source /opt/ros/${ROS_DISTRO}/setup.bash && cd /ros2_ws && colcon build --symlink-install"

# Source the workspace in bashrc
RUN echo "source /opt/ros/${ROS_DISTRO}/setup.bash" >> ~/.bashrc
RUN echo "source /ros2_ws/install/setup.bash" >> ~/.bashrc

# Set working directory
WORKDIR /app

# Copy entrypoint
COPY GroundStation/entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

# Default command
CMD ["/entrypoint.sh"]
