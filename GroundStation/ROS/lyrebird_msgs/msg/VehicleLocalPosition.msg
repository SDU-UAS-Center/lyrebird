# Local kinematic state. Named after PX4's real VehicleLocalPosition.msg but
# holds only the subset DJI telemetry provides; `attitude` stays a free-form
# string (DJI's own {pitch, roll, yaw} JSON) rather than a fabricated
# quaternion.

uint64 timestamp

float32 vx
float32 vy
float32 vz
float32 speed     # [m/s] velocity magnitude
float32 heading   # [deg] compass heading
float32 alt       # [m] barometric altitude relative to takeoff
string attitude   # DJI {pitch, roll, yaw} JSON
