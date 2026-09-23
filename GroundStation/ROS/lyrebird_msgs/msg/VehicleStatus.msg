# Vehicle mode/readiness. `flight_mode` is DJI's own mode name string, not
# PX4's numeric nav_state enum -- deliberately not claiming PX4-enum
# compatibility here since the mode sets are not equivalent.

uint64 timestamp

string flight_mode
bool manual_override_active
bool ready_to_takeoff
string takeoff_block_reason
