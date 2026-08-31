# Acknowledgement of a vehicle_command. Mirrors PX4's real VehicleCommandAck.msg
# (command id + result), plus a Lyrebird-only `seq` field carrying the
# app-assigned sequence number the old command_ack topics (waypoint_seq,
# yaw_seq, altitude_seq) reported (PX4 has no equivalent since its acks don't
# track a waypoint/yaw/altitude sequence id the way Lyrebird's HTTP command
# protocol does).

uint64 timestamp

uint32 command
uint8 target_system

uint8 result
uint8 VEHICLE_CMD_RESULT_ACCEPTED = 0
uint8 VEHICLE_CMD_RESULT_TEMPORARILY_REJECTED = 1
uint8 VEHICLE_CMD_RESULT_DENIED = 2
uint8 VEHICLE_CMD_RESULT_UNSUPPORTED = 3
uint8 VEHICLE_CMD_RESULT_FAILED = 4

int32 seq   # Lyrebird addition: app-assigned seq for the acked command, -1 if unknown/rejected
