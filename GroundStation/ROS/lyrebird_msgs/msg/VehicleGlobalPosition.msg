# Fused global position. Subset of PX4's real VehicleGlobalPosition.msg --
# only the fields DJI telemetry actually populates.

uint64 timestamp

float64 lat    # [deg]
float64 lon    # [deg]
float32 alt    # [m] AMSL

int32 satellite_count   # Lyrebird addition: GPS satellites in view, no PX4 field here to reuse
