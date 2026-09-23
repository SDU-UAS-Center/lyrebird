# Continuous mission-progress status (distinct from the one-shot
# VehicleCommandAck): which reach-flags are currently set, and which command
# sequence each one refers to.

uint64 timestamp

bool waypoint_reached
bool intermediary_waypoint_reached
bool altitude_reached
bool yaw_reached

int32 waypoint_seq
int32 altitude_seq
int32 yaw_seq
