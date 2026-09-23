# Battery / endurance status. `remaining` and `time_remaining_s` reuse PX4's
# real BatteryStatus.msg field names; the rest are Lyrebird additions for
# endurance figures the DJI telemetry stream reports that PX4 does not (PX4
# clients compute these client-side instead of publishing them).

uint64 timestamp

float32 remaining              # [0,1] battery fraction remaining
float32 time_remaining_s       # [s] predicted time remaining

float32 distance_to_home_m
float32 time_needed_to_go_home_s
float32 time_needed_to_land_s
float32 time_to_landing_spot_s
float32 max_radius_can_fly_and_go_home_m
float32 battery_needed_to_go_home    # [0,100] percent
float32 battery_needed_to_land       # [0,100] percent
