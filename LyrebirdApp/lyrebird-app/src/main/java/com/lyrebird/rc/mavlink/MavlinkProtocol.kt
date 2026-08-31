package com.lyrebird.rc.mavlink

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Message ids for the telemetry set Lyrebird emits. */
internal object MavlinkMsgId {

    /** Inbound stick input, the standard equivalent of Lyrebird's /send/stick. */
    const val MANUAL_CONTROL = 69

    /** Inbound parameter write. */
    const val PARAM_SET = 23

    /**
     * The extended parameter protocol, which carries a value as bytes rather than as a float.
     *
     * Lyrebird needs it for the settings that are genuinely strings — the drone's name, the
     * video source, the MediaMTX address. Squeezing those through PARAM_SET would mean inventing
     * a private numbering ("video source 0 means drone"), which reads fine today and is
     * unmaintainable by anyone who was not in the room.
     */
    const val PARAM_EXT_REQUEST_LIST = 321
    const val PARAM_EXT_VALUE = 322
    const val PARAM_EXT_SET = 323
    const val PARAM_EXT_ACK = 324

    /** Gimbal attitude, from the gimbal v2 microservice we already advertise. */
    const val GIMBAL_DEVICE_ATTITUDE_STATUS = 285

    /** The laser rangefinder's range, as a standard ranging sensor. */
    const val DISTANCE_SENSOR = 132

    /** Lyrebird's own residue; see GroundStation/mavlink/lyrebird.xml. */
    const val LYREBIRD_STATUS = 42100

    /** Identity and service ports. Static, so streamed slowly and separately from state. */
    const val LYREBIRD_CONFIG = 42101

    /** On-device detection: state, then one message per target per cycle. */
    const val AUTOSENSING_STATUS = 42102
    const val AUTOSENSING_TARGET = 42103
    const val HEARTBEAT = 0
    const val SYS_STATUS = 1
    const val SET_MODE = 11
    const val GPS_RAW_INT = 24
    const val ATTITUDE = 30
    const val GLOBAL_POSITION_INT = 33
    const val VFR_HUD = 74
    // MAVLink 2 message id for EXTENDED_SYS_STATE. The message only exists in the MAVLink 2
    // dialect and lives at 245 there; 125 is POWER_STATUS in both dialects. QGroundControl 5.1.3
    // (which is MAVLink 2 only) listens for EXTENDED_SYS_STATE at 245, so sending 125 made its
    // flying-state logic never fire and Land/RTL never appeared after takeoff. The crc_extra
    // (130) is a property of the message definition and is unchanged.
    const val EXTENDED_SYS_STATE = 245
    const val BATTERY_STATUS = 147
    const val AUTOPILOT_VERSION = 148
    const val HOME_POSITION = 242
    const val STATUSTEXT = 253

    // Parameter and mission protocols. QGroundControl will not finish its initial-connect state
    // machine without answers to these, and until it does, its camera manager discards every
    // message it receives — so the video stream is never discovered. Found by running QGC.
    const val PARAM_REQUEST_LIST = 21
    const val PARAM_VALUE = 22
    const val MISSION_REQUEST_LIST = 43
    const val MISSION_COUNT = 44
    const val MISSION_CURRENT = 42
    const val MISSION_CLEAR_ALL = 45
    const val MISSION_ITEM_REACHED = 46
    const val MISSION_ACK = 47
    const val MISSION_REQUEST_INT = 51
    const val MISSION_ITEM_INT = 73

    // Camera component (see MavlinkCameraComponent).
    const val CAMERA_INFORMATION = 259
    const val VIDEO_STREAM_INFORMATION = 269
    const val CAMERA_SETTINGS = 260
    const val STORAGE_INFORMATION = 261
    const val VIDEO_STREAM_STATUS = 270
    const val CAMERA_CAPTURE_STATUS = 262
    const val CAMERA_IMAGE_CAPTURED = 263

    // Standard modes protocol. Without these a ground station can only show the raw custom_mode
    // integer, because MAV_AUTOPILOT_INVALID gives it no enum to look the number up in.
    const val AVAILABLE_MODES = 435
    const val CURRENT_MODE = 436

    // Command protocol. COMMAND_LONG and COMMAND_INT are received only; the endpoint answers
    // requests for messages and refuses everything else.
    const val COMMAND_INT = 75
    const val COMMAND_LONG = 76
    const val COMMAND_ACK = 77

    /** MAVLink FTP v1 file transfer, see MavlinkFtp. */
    const val FILE_TRANSFER_PROTOCOL = 110
}

/**
 * The enum values used by the telemetry set, taken from the upstream definitions.
 *
 * The heartbeat reports [Mav.AUTOPILOT_PX4]. Lyrebird is not a flight stack, but QGroundControl
 * only enables its Fly View action buttons (Takeoff / Land / RTL) for firmware plugins that
 * declare guided-mode capabilities — and its Generic plugin, selected for
 * [Mav.AUTOPILOT_INVALID] / [Mav.AUTOPILOT_GENERIC], declares none, so the buttons stayed grey.
 * The claim was the documented fallback all along; the cost is that QGC renders `custom_mode`
 * through PX4's mode enum, which is why [MavlinkFlightMode] now carries PX4's packed mode
 * numbers and maps every Lyrebird mode to the PX4 mode whose meaning matches. The reverse
 * direction — QGC's PX4 plugin asking for Land/RTL via SET_MODE — is mapped back the same way.
 */
internal object Mav {
    const val TYPE_QUADROTOR = 2

    const val AUTOPILOT_GENERIC = 0
    const val AUTOPILOT_INVALID = 8
    const val AUTOPILOT_PX4 = 12

    const val STATE_UNINIT = 0
    const val STATE_STANDBY = 3
    const val STATE_ACTIVE = 4

    // EXTENDED_SYS_STATE (message id 245 in the MAVLink 2 dialect). landed_state is QGC's source
    // of the flying state, so it must be reported or the Fly View never offers Land/RTL — those
    // buttons require the vehicle to be flying, and without this message it never is.
    const val VTOL_STATE_MC = 3
    const val LANDED_STATE_ON_GROUND = 1
    const val LANDED_STATE_IN_AIR = 2
    const val LANDED_STATE_TAKEOFF = 3
    const val LANDED_STATE_LANDING = 4

    const val MODE_FLAG_CUSTOM_MODE_ENABLED = 1
    const val MODE_FLAG_STABILIZE_ENABLED = 16
    const val MODE_FLAG_GUIDED_ENABLED = 8
    const val MODE_FLAG_MANUAL_INPUT_ENABLED = 64
    const val MODE_FLAG_SAFETY_ARMED = 128

    // PX4 packed custom_mode values — (main_mode << 16) | (sub_mode << 24). Reported because the
    // heartbeat claims MAV_AUTOPILOT_PX4; the numbers are PX4's, but the meanings they carry are
    // Lyrebird's own modes (see MavlinkFlightMode). Values are PX4's, mirrored from QGC's
    // px4_custom_mode.h.
    const val PX4_MODE_MANUAL = 1 shl 16
    const val PX4_MODE_ALTCTL = 2 shl 16
    const val PX4_MODE_POSCTL = 3 shl 16
    const val PX4_MODE_OFFBOARD = 6 shl 16
    const val PX4_MODE_ORBIT = (3 shl 16) or (1 shl 24)
    const val PX4_MODE_MISSION = (4 shl 16) or (4 shl 24)
    const val PX4_MODE_TAKEOFF = (4 shl 16) or (2 shl 24)
    const val PX4_MODE_LAND = (4 shl 16) or (6 shl 24)
    const val PX4_MODE_RTL = (4 shl 16) or (5 shl 24)
    const val PX4_MODE_FOLLOW_TARGET = (4 shl 16) or (8 shl 24)

    const val COMP_ID_AUTOPILOT1 = 1

    /**
     * Camera component id. QGroundControl only looks for cameras in the range
     * MAV_COMP_ID_CAMERA..MAV_COMP_ID_CAMERA6 (100..105), so the video stream is not discoverable
     * from any other component id.
     */
    const val COMP_ID_CAMERA = 100

    const val TYPE_CAMERA = 30

    const val RESULT_ACCEPTED = 0

    /** Accepted and still running; a further ack follows when it completes. */
    const val RESULT_IN_PROGRESS = 5

    /**
     * MAV_RESULT_CANCELLED: the command was superseded before it finished.
     *
     * Distinct from FAILED on purpose. A ground station that re-issues a goto every second
     * supersedes its own previous command constantly, and reporting each as a failure turns an
     * ordinary flight into a stream of errors. Nothing failed; a newer instruction arrived.
     */
    const val RESULT_CANCELLED = 6
    const val RESULT_DENIED = 2
    const val RESULT_UNSUPPORTED = 3

    const val CMD_SET_MESSAGE_INTERVAL = 511
    const val CMD_REQUEST_MESSAGE = 512
    const val CMD_REQUEST_CAMERA_INFORMATION = 521
    const val CMD_REQUEST_VIDEO_STREAM_INFORMATION = 2504
    const val CMD_REQUEST_VIDEO_STREAM_STATUS = 2505
    const val CMD_REQUEST_CAMERA_SETTINGS = 522
    const val CMD_REQUEST_STORAGE_INFORMATION = 525
    const val CMD_REQUEST_CAMERA_CAPTURE_STATUS = 527
    const val CMD_DO_SET_STANDARD_MODE = 262
    const val CMD_MISSION_START = 300
    const val CMD_DO_SET_MISSION_CURRENT = 224
    const val CMD_NAV_WAYPOINT = 16
    const val CMD_DO_CHANGE_SPEED = 178

    // Payload and camera commands the endpoint executes. None of these can move the aircraft;
    // see MavlinkCommandSink for why that boundary is drawn where it is.
    const val CMD_SET_CAMERA_ZOOM = 531
    const val CMD_IMAGE_START_CAPTURE = 2000
    const val CMD_VIDEO_START_CAPTURE = 2500
    const val CMD_VIDEO_STOP_CAPTURE = 2501
    const val CMD_DO_GIMBAL_MANAGER_PITCHYAW = 1000

    /**
     * The legacy gimbal-aim command. param1 is pitch, param2 is roll (unsupported, ignored),
     * param3 is yaw, all in degrees, for MAV_MOUNT_MODE_MAVLINK_TARGETING.
     *
     * QGroundControl's Survey planner writes one of these as the plan's "Initial Camera Settings"
     * gimbal item rather than [CMD_DO_GIMBAL_MANAGER_PITCHYAW] — same reason as
     * [CMD_SET_CAMERA_MODE] below, this is a different QGC panel targeting an older command.
     */
    const val CMD_DO_MOUNT_CONTROL = 205

    /**
     * Distance-triggered photo capture, param1 the trigger distance in metres. QGroundControl's
     * Survey planner writes one of these as the plan's "Initial Camera Settings" item whenever
     * Survey mode is ticked.
     *
     * Accepted so the upload does not fail, but not yet executed — Lyrebird has no
     * distance-triggered capture loop, only [CMD_IMAGE_START_CAPTURE]'s one-shot trigger. See
     * [CMD_SET_CAMERA_MODE] for why refusing an item QGC always writes fails the whole plan
     * rather than just losing that one setting.
     */
    const val CMD_DO_SET_CAM_TRIGG_DIST = 206

    /**
     * Switch the camera between stills and video. param2 is MAV_CAMERA_MODE.
     *
     * Worth supporting mainly because QGroundControl puts one at the head of every plan whose
     * Mission Settings name a camera action, as item #0 — so refusing it does not lose a camera
     * mode, it fails the entire mission transfer before a single waypoint is read.
     */
    const val CMD_SET_CAMERA_MODE = 530

    // Flight-motion commands. None of these executes until lb_mav_0_allow_flight is true and the
    // authority / manual-override gates pass — see MavlinkMotionSink.
    const val CMD_NAV_RETURN_TO_LAUNCH = 20
    const val CMD_NAV_LAND = 21
    const val CMD_NAV_TAKEOFF = 22
    const val CMD_CONDITION_YAW = 115
    const val CMD_DO_SET_MODE = 176
    const val CMD_DO_REPOSITION = 192
    const val CMD_COMPONENT_ARM_DISARM = 400

    /** Climb or descend to an altitude, holding position. param1 = rate, param7 = altitude. */
    const val CMD_CONDITION_CHANGE_ALT = 113

    /** Standard payload release. Lyrebird's drop port is a gripper in everything but name. */
    const val CMD_DO_GRIPPER = 211
    const val GRIPPER_ACTION_RELEASE = 0

    /**
     * Fly a circle around a point. param1 radius (signed for direction), param2 tangential
     * velocity, param3 yaw behaviour, param4 the arc to fly in radians, param5/6/7 the centre.
     */
    const val CMD_DO_ORBIT = 34

    /**
     * MAV_ORBIT_YAW_BEHAVIOUR. Only the first two are distinguishable on this airframe: the
     * aircraft either keeps its nose on the centre or holds the heading it started with.
     * Uncontrolled and RC-controlled both leave the heading alone, which is the second of those,
     * and a tangential nose is not offered because nothing here asks for it.
     */
    const val ORBIT_YAW_FACE_CENTRE = 0
    const val ORBIT_YAW_HOLD_INITIAL = 1

    /**
     * Point the camera at a fixed position and keep it there — MAVLink's region of interest.
     *
     * Deliberately a payload command rather than a motion one. Some autopilots also yaw the
     * airframe to satisfy an ROI; Lyrebird turns only the gimbal, which is the honest reading
     * for an aircraft whose camera has its own two axes, and it means an ROI can be set on a
     * build with flight motion disabled entirely.
     *
     * [CMD_DO_SET_ROI] is the superseded form, still what some ground stations send, and it
     * carries the same position in param5/6/7 behind a mode selector in param1.
     */
    const val CMD_DO_SET_ROI_LOCATION = 195
    const val CMD_DO_SET_ROI_NONE = 197
    const val CMD_DO_SET_ROI = 201

    /** MAV_ROI_LOCATION, the only MAV_ROI mode that names a place. */
    const val ROI_MODE_LOCATION = 3
    const val ROI_MODE_NONE = 0

    /**
     * The two Lyrebird-specific commands.
     *
     * Everything that has a standard MAV_CMD uses it; these carry the residue that genuinely has
     * no portable equivalent, kept to two commands with a selector rather than sprawling across
     * the user range. A ground station that does not know Lyrebird simply never sends them.
     *
     * USER_1 is payload aiming and authority (relative gimbal, releasing the manual-override
     * latch, releasing safety back to the Pilot). USER_2 is payload sensing (laser rangefinder,
     * spot temperature, thermal shutter).
     */
    /** MAV_DISTANCE_SENSOR_LASER. */
    const val DISTANCE_SENSOR_TYPE_LASER = 0

    /** MAV_SENSOR_ROTATION_PITCH_270: pointing down, where a gimballed rangefinder looks. */
    const val SENSOR_ROTATION_PITCH_270 = 25

    const val CMD_USER_1 = 31010
    const val CMD_USER_2 = 31011

    /** USER_1 selectors, in param1. */
    const val USER1_GIMBAL_RELATIVE = 1f
    const val USER1_RELEASE_MANUAL_OVERRIDE = 2f
    const val USER1_AUTOSENSING_START = 3f
    const val USER1_AUTOSENSING_STOP = 4f
    const val USER1_RELEASE_SAFETY = 3f

    /** USER_2 selectors, in param1. */
    const val USER2_LRF_MEASURE = 1f
    const val USER2_CAPTURE_TEMPERATURE = 2f
    const val USER2_CAPTURE_THERMAL_IMAGE = 3f

    const val RESULT_FAILED = 4

    // Claimed only because the endpoint actually implements them — see MavlinkCommandSink.
    // Advertising a capability commits us to the protocol behind it, which is how the camera
    // panel got into a broken state the first time.
    const val CAMERA_CAP_CAPTURE_VIDEO = 1L
    const val CAMERA_CAP_CAPTURE_IMAGE = 2L
    const val CAMERA_CAP_HAS_VIDEO_STREAM = 256L

    /** CAMERA_CAPTURE_STATUS video_status / image_status: 0 idle, 1 capture in progress. */
    const val CAPTURE_STATUS_IDLE = 0
    const val CAPTURE_STATUS_RUNNING = 1

    /**
     * RTSP, not WHEP. VIDEO_STREAM_TYPE_WHEP exists in the definitions, but QGroundControl's
     * VideoManager only auto-selects a source for RTSP, RTP/UDP, TCP-MPEG and MPEG-TS — a WHEP
     * stream would be advertised and then ignored. MediaMTX already republishes the WHIP ingest
     * on RTSP, so RTSP is both honest and the one that works.
     */
    const val VIDEO_STREAM_TYPE_RTSP = 0
    const val VIDEO_STREAM_ENCODING_H264 = 1
    const val VIDEO_STREAM_STATUS_RUNNING = 1

    /**
     * MAV_CAMERA_MODE. Reported in CAMERA_SETTINGS, and accepted from a plan's
     * MAV_CMD_SET_CAMERA_MODE.
     *
     * Survey mode is stills flown on a grid, which is a property of the flight rather than of the
     * camera, so DJI has nothing separate to put it in and it is treated as stills.
     */
    const val CAMERA_MODE_IMAGE = 0
    const val CAMERA_MODE_VIDEO = 1
    const val CAMERA_MODE_IMAGE_SURVEY = 2

    /** STORAGE_STATUS_NOT_SUPPORTED: the DJI card is not exposed over MAVLink yet. */
    const val STORAGE_STATUS_NOT_SUPPORTED = 3

    const val STANDARD_MODE_NON_STANDARD = 0
    const val STANDARD_MODE_POSITION_HOLD = 1
    const val STANDARD_MODE_ORBIT = 2
    const val STANDARD_MODE_ALTITUDE_HOLD = 4
    const val STANDARD_MODE_SAFE_RECOVERY = 5
    const val STANDARD_MODE_MISSION = 6
    const val STANDARD_MODE_LAND = 7
    const val STANDARD_MODE_TAKEOFF = 8

    /** MAV_MODE_PROPERTY_NOT_USER_SELECTABLE: advertised for display, but not settable yet. */
    const val MODE_PROPERTY_NOT_USER_SELECTABLE = 2L

    const val SEVERITY_INFO = 6
    const val SEVERITY_WARNING = 4

    // Sensor bits used in SYS_STATUS. Only sensors Lyrebird can actually speak to are reported;
    // advertising a sensor that is not backed by a real reading is the same mistake as claiming
    // the wrong autopilot.
    const val SENSOR_3D_GYRO = 1
    const val SENSOR_3D_ACCEL = 2
    const val SENSOR_3D_MAG = 4
    const val SENSOR_ABSOLUTE_PRESSURE = 8
    const val SENSOR_GPS = 32
    const val SENSOR_ATTITUDE_STABILIZATION = 2048
    const val SENSOR_YAW_POSITION = 4096
    const val SENSOR_Z_ALTITUDE_CONTROL = 8192
    const val SENSOR_XY_POSITION_CONTROL = 16384
    const val SENSOR_BATTERY = 33554432

    const val CAP_MISSION_INT = 4L
    const val CAP_COMMAND_INT = 8L
    const val CAP_MAVLINK2 = 8192L

    /** MAV_PROTOCOL_CAPABILITY_FTP: this component serves MAVLink FTP v1 (see MavlinkFtp). */
    const val CAP_FTP = 32L

    /** MAVLink wire-format version reported in HEARTBEAT. Always 3 for MAVLink 2. */
    const val MAVLINK_VERSION = 3

    /** MAV_PARAM_TYPE_REAL32. Every Lyrebird parameter is a float for now. */
    const val PARAM_TYPE_REAL32 = 9

    /** MAV_PARAM_EXT_TYPE_CUSTOM: the value is an opaque byte string. */
    const val PARAM_EXT_TYPE_CUSTOM = 11

    /** PARAM_ACK values. */
    const val PARAM_ACK_ACCEPTED = 0
    const val PARAM_ACK_VALUE_UNSUPPORTED = 1
    const val PARAM_ACK_FAILED = 2
}

/**
 * Little-endian payload writer.
 *
 * Fields must be appended in MAVLink wire order — base fields sorted by descending type size
 * (stable, so declaration order breaks ties), then extension fields in declaration order. The
 * builders in [MavlinkMessages] follow the order generated from `common.xml`; changing the order
 * silently corrupts every field after the change, so the orders are commented per message there.
 */
internal class PayloadWriter(capacity: Int = MAX_PAYLOAD) {
    private val buffer: ByteBuffer =
        ByteBuffer.allocate(capacity).order(ByteOrder.LITTLE_ENDIAN)

    fun u8(value: Int) = apply { buffer.put((value and 0xFF).toByte()) }
    fun i8(value: Int) = apply { buffer.put(value.toByte()) }
    fun u16(value: Int) = apply { buffer.putShort((value and 0xFFFF).toShort()) }
    fun i16(value: Int) = apply { buffer.putShort(value.toShort()) }
    fun u32(value: Long) = apply { buffer.putInt((value and 0xFFFFFFFFL).toInt()) }
    fun i32(value: Int) = apply { buffer.putInt(value) }
    fun u64(value: Long) = apply { buffer.putLong(value) }
    fun f32(value: Float) = apply { buffer.putFloat(value) }

    fun f32Array(values: FloatArray, length: Int) = apply {
        for (i in 0 until length) buffer.putFloat(values.getOrElse(i) { 0f })
    }

    fun u16Array(value: Int, length: Int) = apply {
        repeat(length) { buffer.putShort((value and 0xFFFF).toShort()) }
    }

    /** Fixed-width char field: UTF-8, truncated to [length], zero-padded. */
    fun chars(text: String, length: Int) = apply {
        val bytes = text.toByteArray(Charsets.UTF_8)
        for (i in 0 until length) buffer.put(if (i < bytes.size) bytes[i] else 0)
    }

    fun zeros(count: Int) = apply { repeat(count) { buffer.put(0) } }

    /** Raw bytes, zero-padded to [length] when shorter than it. */
    fun bytes(data: ByteArray, length: Int) = apply {
        for (i in 0 until length) buffer.put(if (i < data.size) data[i] else 0)
    }

    fun build(): ByteArray = ByteArray(buffer.position()).also {
        buffer.flip()
        buffer.get(it)
    }

    companion object {
        const val MAX_PAYLOAD = 255
    }
}

/**
 * MAVLink 2 frame serialiser.
 *
 * Frame layout: `0xFD | len | incompat | compat | seq | sysid | compid | msgid[3] | payload | crc[2]`.
 *
 * MAVLink 2 only — no version-1 fallback path exists here by design. The 24-bit message id is what
 * lets a Lyrebird dialect exist at all later, extension fields are how BATTERY_STATUS carries
 * `time_remaining`, and signing (not yet enabled) is what will eventually replace the
 * `X-Safety-Token` header. None of those exist in version 1.
 */
internal class MavlinkFramer(
    private val systemId: Int,
    private val componentId: Int = Mav.COMP_ID_AUTOPILOT1
) {
    private var sequence = 0

    fun frame(messageId: Int, payload: ByteArray): ByteArray {
        val crcExtra = MavlinkCrc.CRC_EXTRA[messageId]
            ?: error("No CRC_EXTRA registered for message id $messageId")

        // MAVLink 2 drops trailing zero bytes from the payload, but never all of them: a
        // zero-length payload is encoded as a single zero byte.
        var length = payload.size
        while (length > 1 && payload[length - 1] == ZERO_BYTE) length--
        if (payload.isEmpty()) length = 0

        val frame = ByteArray(HEADER_BYTES + length + CHECKSUM_BYTES)
        frame[0] = MAGIC_V2
        frame[1] = length.toByte()
        frame[2] = 0 // incompatibility flags: 0 = unsigned
        frame[3] = 0 // compatibility flags
        frame[4] = sequence.toByte()
        frame[5] = systemId.toByte()
        frame[6] = componentId.toByte()
        frame[7] = (messageId and 0xFF).toByte()
        frame[8] = ((messageId shr 8) and 0xFF).toByte()
        frame[9] = ((messageId shr 16) and 0xFF).toByte()
        System.arraycopy(payload, 0, frame, HEADER_BYTES, length)

        // Checksum covers LEN through the last payload byte, then CRC_EXTRA.
        val crc = MavlinkCrc.checksum(frame, 1, HEADER_BYTES - 1 + length, crcExtra)
        frame[HEADER_BYTES + length] = (crc and 0xFF).toByte()
        frame[HEADER_BYTES + length + 1] = ((crc shr 8) and 0xFF).toByte()

        sequence = (sequence + 1) and 0xFF
        return frame
    }

    companion object {
        /** Not `const`: `0xFD.toByte()` is not a compile-time constant in Kotlin. */
        val MAGIC_V2: Byte = 0xFD.toByte()
        private const val HEADER_BYTES = 10
        private const val CHECKSUM_BYTES = 2
        private const val ZERO_BYTE: Byte = 0
    }
}
