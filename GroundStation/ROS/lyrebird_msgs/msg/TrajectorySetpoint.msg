# Guided waypoint setpoint. Replaces the old goto_waypoint /
# goto_waypoint_nose_forward / goto_waypoint_hold_heading topics with one
# topic and an explicit yaw_mode, instead of three near-duplicate topics kept
# in sync by a compatibility alias.

uint64 timestamp

float64 latitude
float64 longitude
float32 altitude
float32 yaw      # [deg] meaning depends on yaw_mode
float32 speed    # [m/s]

uint8 yaw_mode
uint8 YAW_MODE_NOSE_FORWARD = 0   # turn to face the leg, rotate to `yaw` (final heading) on arrival
uint8 YAW_MODE_HOLD_HEADING = 1   # crab sideways, holding `yaw` for the whole flight
