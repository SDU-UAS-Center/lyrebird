#!/bin/bash

# Get the directory where the script is located
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"

# ROS distro for the image. Lyrical (the current LTS) matches the Dockerfile default;
# override for the alternative Humble build:  ROS_DISTRO=humble ./run_docker.sh
ROS_DISTRO="${ROS_DISTRO:-lyrical}"

# Build the Docker image. The build context is the repository root, because the image
# installs the Python side from pyproject.toml + uv.lock, which live there.
REPO_ROOT="$( cd "$SCRIPT_DIR/.." &> /dev/null && pwd )"
echo "Building Lyrebird Ground Station Docker image (ROS 2 ${ROS_DISTRO})..."
docker build --build-arg "ROS_DISTRO=${ROS_DISTRO}" -t lyrebird-gs -f "$SCRIPT_DIR/Dockerfile" "$REPO_ROOT"

# Allow X11 connections (be careful with security on public networks)
xhost +local:docker

# Run the container
# --net=host: Required for UDP broadcast discovery and WebRTC
# -v /tmp/.X11-unix:/tmp/.X11-unix: Required for GUI display
# -e DISPLAY=$DISPLAY: Required for GUI display
echo "Running Lyrebird Ground Station..."
docker run -it --rm \
    --net=host \
    -v /tmp/.X11-unix:/tmp/.X11-unix \
    -e DISPLAY=$DISPLAY \
    lyrebird-gs
