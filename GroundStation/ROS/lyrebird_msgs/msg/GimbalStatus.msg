# Gimbal telemetry. Lyrebird-original -- PX4 has no camera/gimbal uORB
# telemetry topic of its own to mirror.

uint64 timestamp

string attitude          # DJI {pitch, roll, yaw} JSON
string joint_attitude    # DJI joint-angle JSON
float64 yaw
float64 pitch
