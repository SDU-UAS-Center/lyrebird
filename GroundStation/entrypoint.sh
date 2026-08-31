#!/bin/bash
source /opt/ros/humble/setup.bash
source /ros2_ws/install/setup.bash

echo "========================================================"
echo "   Lyrebird Ground Station (ROS 2 Humble)"
echo "========================================================"
echo "The container is starting."
echo ""
echo "For video diagnostics, run the GroundStation/video_test/compose.yaml stack."
echo "It starts MediaMTX plus the Lyrebird video dashboard at http://localhost:8090."
echo ""
echo "Starting ROS node with auto-discovery..."
echo "========================================================"

# Auto-discover Lyrebird drones and launch one lyrebird_controller node per drone
exec ros2 launch lyrebird_bringup auto_discovery_native.launch.py

