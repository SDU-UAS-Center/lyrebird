# Virtual-stick manual control input, PX4-ManualControlSetpoint-style.
# Replaces command/stick's Float64MultiArray [leftX, leftY, rightX, rightY].

uint64 timestamp

float32 roll       # rightX, [-1, 1]
float32 pitch      # rightY, [-1, 1]
float32 throttle   # leftY,  [-1, 1]
float32 yaw        # leftX,  [-1, 1]
