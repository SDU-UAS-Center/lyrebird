# Ground-station command to the drone. Mirrors PX4's real VehicleCommand.msg /
# MAVLink COMMAND_LONG shape: a command ID plus seven generic parameters.
# Where a command has a direct MAV_CMD equivalent, `command` reuses its exact
# numeric ID (verified against MAVLink's common.xml and PX4-Autopilot's
# msg/versioned/VehicleCommand.msg). DJI-only actions with no MAV_CMD
# equivalent use VEHICLE_CMD_LYREBIRD_* ids, placed inside MAVLink's own
# documented user-command range (31000-31999) so they can never collide with
# a real MAV_CMD.

uint64 timestamp        # [us] time since node start

uint32 command
uint8 target_system
uint8 target_component
uint8 source_system
uint8 source_component
bool from_external

float32 param1
float32 param2
float32 param3
float32 param4
float64 param5           # latitude, where applicable
float64 param6           # longitude, where applicable
float32 param7           # altitude, where applicable

# --- Real MAV_CMD ids (from MAVLink common.xml / PX4 VehicleCommand.msg) ---
uint32 VEHICLE_CMD_NAV_TAKEOFF = 22                    # param4=yaw, param5=lat, param6=lon, param7=alt
uint32 VEHICLE_CMD_NAV_LAND = 21                       # param4=yaw, param5=lat, param6=lon, param7=alt
uint32 VEHICLE_CMD_NAV_RETURN_TO_LAUNCH = 20
uint32 VEHICLE_CMD_CONDITION_YAW = 115                 # param1=angle[deg], param2=speed[deg/s], param3=direction, param4=relative
uint32 VEHICLE_CMD_DO_CHANGE_ALTITUDE = 186            # param1=altitude[m], param2=frame
uint32 VEHICLE_CMD_DO_GIMBAL_MANAGER_PITCHYAW = 1000   # param1=pitch[deg], param2=yaw[deg], NaN = unset
uint32 VEHICLE_CMD_SET_CAMERA_ZOOM = 531               # param1=zoom type, param2=zoom value
uint32 VEHICLE_CMD_VIDEO_START_CAPTURE = 2500
uint32 VEHICLE_CMD_VIDEO_STOP_CAPTURE = 2501
uint32 VEHICLE_CMD_IMAGE_START_CAPTURE = 2000

# --- Lyrebird-only ids (DJI concepts with no MAV_CMD equivalent), placed in
# MAVLink's documented user-command range (31000-31999) ---
uint32 VEHICLE_CMD_LYREBIRD_ABORT_MISSION = 31015
uint32 VEHICLE_CMD_LYREBIRD_ABORT_ALL = 31016
uint32 VEHICLE_CMD_LYREBIRD_ENABLE_VIRTUAL_STICK = 31017
uint32 VEHICLE_CMD_LYREBIRD_ABORT_DJI_NATIVE_MISSION = 31018
uint32 VEHICLE_CMD_LYREBIRD_DEACTIVATE_MANUAL_OVERRIDE = 31019
uint32 VEHICLE_CMD_LYREBIRD_GIMBAL_REL_PITCH = 31020    # param1=degrees relative to current pitch
uint32 VEHICLE_CMD_LYREBIRD_GIMBAL_REL_YAW = 31021      # param1=degrees relative to current yaw
uint32 VEHICLE_CMD_LYREBIRD_SET_RTH_ALTITUDE = 31022    # param1=altitude[m]
uint32 VEHICLE_CMD_LYREBIRD_CAPTURE_TEMPERATURE = 31023
uint32 VEHICLE_CMD_LYREBIRD_LIST_MEDIA = 31024
uint32 VEHICLE_CMD_LYREBIRD_LRF_MEASURE = 31025
uint32 VEHICLE_CMD_LYREBIRD_DROP = 31026
