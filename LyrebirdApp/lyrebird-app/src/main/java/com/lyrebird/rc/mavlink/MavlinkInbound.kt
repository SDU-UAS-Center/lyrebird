package com.lyrebird.rc.mavlink

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A ground station announcing how many mission items it is about to upload. */
internal data class MavlinkMissionCount(
    val count: Int,
    val missionType: Int,
    val senderSystem: Int,
    val senderComponent: Int
)

/** One uploaded mission item. */
internal data class MavlinkMissionItem(
    val item: MissionItem,
    val missionType: Int,
    val senderSystem: Int,
    val senderComponent: Int
)

/** Stick input from a ground station, each axis normalised to -1..1. */
internal data class MavlinkManualControl(
    val roll: Float,
    val pitch: Float,
    val throttle: Float,
    val yaw: Float
)

/** A ground station writing one string-valued parameter. */
internal data class MavlinkParamExtSet(
    val name: String,
    val value: String,
    val senderSystem: Int,
    val senderComponent: Int
)

/** A ground station writing one parameter. */
internal data class MavlinkParamSet(
    val name: String,
    val value: Float,
    val senderSystem: Int,
    val senderComponent: Int
)

/** A ground station asking for the whole parameter list, or for a plan. */
internal data class MavlinkListRequest(
    val messageId: Int,
    val senderSystem: Int,
    val senderComponent: Int,
    /** Plan type for MISSION_REQUEST_LIST: mission, fence or rally. Zero for parameters. */
    val missionType: Int
)

/**
 * A SET_MODE request. QGC's PX4 firmware plugin asks for Land / RTL by sending this rather than
 * DO_SET_MODE, so it is parsed and handled like a command.
 */
internal data class MavlinkSetMode(
    val targetSystem: Int,
    val baseMode: Int,
    val customMode: Int,
    val senderSystem: Int,
    val senderComponent: Int
)

/**
 * A decoded inbound command, reduced to the parts Lyrebird acts on.
 *
 * Only what the endpoint answers is modelled. Everything else is identified far enough to be
 * discarded — there is no reason to interpret a ground station's own telemetry.
 */
internal data class MavlinkCommand(
    val command: Int,
    val targetSystem: Int,
    val targetComponent: Int,
    val senderSystem: Int,
    val senderComponent: Int,
    val param1: Float,
    val param2: Float,
    val param3: Float,
    val param4: Float,
    /** Latitude for position commands, degrees. */
    val param5: Float,
    /** Longitude for position commands, degrees. */
    val param6: Float,
    /** Altitude, metres. */
    val param7: Float,
    /**
     * Latitude and longitude in full precision, in degrees.
     *
     * [param5] and [param6] are floats because that is what COMMAND_LONG carries, and a float
     * holds about seven significant digits — at 46 degrees that is a rounding error of roughly
     * 0.6 m, which is the whole reason MAVLink defines COMMAND_INT with the position as int32
     * degE7. Position commands read these instead, so a COMMAND_INT keeps the precision it was
     * sent with rather than losing it on arrival.
     */
    val latitudeDeg: Double,
    val longitudeDeg: Double
)

/**
 * Minimal MAVLink 2 frame reader.
 *
 * Deliberately narrow: it validates the checksum and extracts COMMAND_LONG / COMMAND_INT /
 * SET_MODE, and returns null for everything else. The endpoint that uses it refuses every command
 * outside a short allowlist, and flight motion only ever reaches the gated motion sink — see
 * [MavlinkTelemetryEndpoint].
 *
 * Signed frames are parsed and their signature ignored rather than rejected, so that a ground
 * station which has signing enabled is not silently unable to ask for the camera information.
 * Signing is not yet a trust boundary here; when it becomes one (replacing `X-Safety-Token`) this
 * is the place that has to start verifying rather than skipping.
 */
internal object MavlinkInbound {

    /** Byte offsets shared by COMMAND_LONG and COMMAND_INT payloads. */
    private const val COMMAND_ID_OFFSET = 28
    private const val TARGET_SYSTEM_OFFSET = 30
    private const val TARGET_COMPONENT_OFFSET = 31

    private const val HEADER_BYTES = 10
    private const val CHECKSUM_BYTES = 2
    private const val INCOMPAT_SIGNED = 0x01
    private const val SIGNATURE_BYTES = 13
    private const val MAX_PAYLOAD = 255

    /** FILE_TRANSFER_PROTOCOL leads with target_network/system/component before the FTP payload. */
    private const val FTP_TARGET_BYTES = 3
    private const val MISSION_TYPE_OFFSET = 2
    private const val MISSION_COUNT_TYPE_OFFSET = 4
    private const val MISSION_ITEM_TYPE_OFFSET = 36
    private const val COORD_SCALE = 1e7

    /** MANUAL_CONTROL axes are -1000..1000 for full deflection. */
    private const val MANUAL_CONTROL_SCALE = 1000f

    /** param_id starts after param_value(4) + target_system(1) + target_component(1). */
    private const val PARAM_SET_ID_OFFSET = 6
    private const val PARAM_ID_LENGTH = 16

    /** param_id follows target_system(1) + target_component(1) in PARAM_EXT_SET. */
    private const val PARAM_EXT_ID_OFFSET = 2
    private const val PARAM_EXT_VALUE_OFFSET = PARAM_EXT_ID_OFFSET + PARAM_ID_LENGTH
    private const val PARAM_EXT_VALUE_LENGTH = 128

    /**
     * Parse a PARAM_REQUEST_LIST or MISSION_REQUEST_LIST, or null for anything else.
     *
     * These are answered because QGroundControl's initial-connect state machine blocks on them,
     * and its camera manager ignores every message until that state machine completes — so
     * without them the video stream is never discovered. Neither request changes vehicle state.
     */
    fun parseListRequest(data: ByteArray, length: Int): MavlinkListRequest? {
        val frame = validate(data, length) ?: return null
        if (frame.messageId != MavlinkMsgId.PARAM_REQUEST_LIST &&
            frame.messageId != MavlinkMsgId.MISSION_REQUEST_LIST
        ) {
            return null
        }
        // target_system(u8), target_component(u8), then for missions an extension mission_type.
        val missionType = if (frame.payloadLength > MISSION_TYPE_OFFSET) {
            data[HEADER_BYTES + MISSION_TYPE_OFFSET].toInt() and 0xFF
        } else {
            0
        }
        return MavlinkListRequest(
            messageId = frame.messageId,
            senderSystem = data[5].toInt() and 0xFF,
            senderComponent = data[6].toInt() and 0xFF,
            missionType = missionType
        )
    }

    /**
     * Parse a MISSION_COUNT — the opening move of an upload.
     *
     * count(u16), target_system(u8), target_component(u8), [ext] mission_type(u8)
     */
    fun parseMissionCount(data: ByteArray, length: Int): MavlinkMissionCount? {
        val frame = validate(data, length) ?: return null
        if (frame.messageId != MavlinkMsgId.MISSION_COUNT) return null
        val payload = paddedPayload(data, frame.payloadLength)
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        return MavlinkMissionCount(
            count = buffer.getShort(0).toInt() and 0xFFFF,
            // mission_type is an extension, so a sender that omits it means "mission".
            missionType = if (frame.payloadLength > MISSION_COUNT_TYPE_OFFSET) {
                payload[MISSION_COUNT_TYPE_OFFSET].toInt() and 0xFF
            } else {
                MavlinkMissionStore.MISSION_TYPE_MISSION
            },
            senderSystem = data[5].toInt() and 0xFF,
            senderComponent = data[6].toInt() and 0xFF
        )
    }

    /**
     * Parse a MISSION_ITEM_INT.
     *
     * param1..4(f), x(i32), y(i32), z(f), seq(u16), command(u16), target_system(u8),
     * target_component(u8), frame(u8), current(u8), autocontinue(u8)
     */
    fun parseMissionItem(data: ByteArray, length: Int): MavlinkMissionItem? {
        val frame = validate(data, length) ?: return null
        if (frame.messageId != MavlinkMsgId.MISSION_ITEM_INT) return null
        val payload = paddedPayload(data, frame.payloadLength)
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val item = MissionItem(
            seq = buffer.getShort(28).toInt() and 0xFFFF,
            command = buffer.getShort(30).toInt() and 0xFFFF,
            param1 = buffer.getFloat(0),
            param2 = buffer.getFloat(4),
            param3 = buffer.getFloat(8),
            // Left exactly as sent, NaN included: NaN is the heading mode, not a missing value.
            param4 = buffer.getFloat(12),
            latitudeDeg = buffer.getInt(16) / COORD_SCALE,
            longitudeDeg = buffer.getInt(20) / COORD_SCALE,
            altitudeM = buffer.getFloat(24).toDouble(),
            autocontinue = payload[35].toInt() != 0
        )
        return MavlinkMissionItem(
            item = item,
            missionType = if (frame.payloadLength > MISSION_ITEM_TYPE_OFFSET) {
                payload[MISSION_ITEM_TYPE_OFFSET].toInt() and 0xFF
            } else {
                MavlinkMissionStore.MISSION_TYPE_MISSION
            },
            senderSystem = data[5].toInt() and 0xFF,
            senderComponent = data[6].toInt() and 0xFF
        )
    }

    /**
     * Stick input, if this frame is one.
     *
     * MANUAL_CONTROL carries each axis as an int16 in -1000..1000 (1000 meaning full deflection),
     * so it is scaled to the -1..1 the controller expects. INT16_MAX means "this axis is not
     * being controlled", which is normalised to neutral rather than to full deflection — reading
     * it literally would command a hard input from a station that meant to command nothing.
     */
    fun parseManualControl(data: ByteArray, length: Int): MavlinkManualControl? {
        val frame = validate(data, length) ?: return null
        if (frame.messageId != MavlinkMsgId.MANUAL_CONTROL) return null
        val payload = paddedPayload(data, frame.payloadLength)
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        fun axis(offset: Int): Float {
            val raw = buffer.getShort(offset).toInt()
            if (raw == Short.MAX_VALUE.toInt()) return 0f
            return (raw / MANUAL_CONTROL_SCALE).coerceIn(-1f, 1f)
        }
        // Wire order: x(i16) y(i16) z(i16) r(i16) buttons(u16) target(u8)
        return MavlinkManualControl(
            pitch = axis(0),
            roll = axis(2),
            throttle = axis(4),
            yaw = axis(6)
        )
    }

    /**
     * A parameter write, if this frame is one.
     *
     * param_id is a 16-byte field that is NUL-terminated only when the name is shorter, so it is
     * trimmed at the first NUL rather than assumed to be one.
     */
    fun parseParamSet(data: ByteArray, length: Int): MavlinkParamSet? {
        val frame = validate(data, length) ?: return null
        if (frame.messageId != MavlinkMsgId.PARAM_SET) return null
        val payload = paddedPayload(data, frame.payloadLength)
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        // Wire order: param_value(f32) target_system(u8) target_component(u8)
        //             param_id(char[16]) param_type(u8)
        val nameBytes = payload.copyOfRange(PARAM_SET_ID_OFFSET, PARAM_SET_ID_OFFSET + PARAM_ID_LENGTH)
        val name = String(nameBytes, Charsets.US_ASCII).substringBefore('\u0000').trim()
        if (name.isEmpty()) return null
        return MavlinkParamSet(
            name = name,
            value = buffer.getFloat(0),
            senderSystem = data[5].toInt() and 0xFF,
            senderComponent = data[6].toInt() and 0xFF
        )
    }

    /**
     * A string parameter write, if this frame is one.
     *
     * PARAM_EXT_SET carries the value as 128 bytes rather than as a float, which is the whole
     * reason it exists here: the settings it serves are names and addresses, not numbers. Both
     * fields are NUL-terminated only when shorter than their field, so both are trimmed at the
     * first NUL rather than assumed to be terminated.
     */
    fun parseParamExtSet(data: ByteArray, length: Int): MavlinkParamExtSet? {
        val frame = validate(data, length) ?: return null
        if (frame.messageId != MavlinkMsgId.PARAM_EXT_SET) return null
        val payload = paddedPayload(data, frame.payloadLength)
        // Wire order: target_system(u8) target_component(u8) param_id(char[16])
        //             param_value(char[128]) param_type(u8)
        val name = readChars(payload, PARAM_EXT_ID_OFFSET, PARAM_ID_LENGTH)
        if (name.isEmpty()) return null
        return MavlinkParamExtSet(
            name = name,
            value = readChars(payload, PARAM_EXT_VALUE_OFFSET, PARAM_EXT_VALUE_LENGTH),
            senderSystem = data[5].toInt() and 0xFF,
            senderComponent = data[6].toInt() and 0xFF
        )
    }

    /** True when the frame asks for the whole extended parameter list. */
    fun isParamExtRequestList(data: ByteArray, length: Int): Boolean {
        val frame = validate(data, length) ?: return false
        return frame.messageId == MavlinkMsgId.PARAM_EXT_REQUEST_LIST
    }

    private fun readChars(payload: ByteArray, offset: Int, length: Int): String =
        String(payload.copyOfRange(offset, offset + length), Charsets.US_ASCII)
            .substringBefore('\u0000')
            .trim()

    /** True when the frame is a MISSION_CLEAR_ALL for the mission plan. */
    fun isMissionClearAll(data: ByteArray, length: Int): Boolean {
        val frame = validate(data, length) ?: return false
        return frame.messageId == MavlinkMsgId.MISSION_CLEAR_ALL
    }

    /** True when the frame is the ground station acknowledging a download. */
    fun isMissionAck(data: ByteArray, length: Int): Boolean {
        val frame = validate(data, length) ?: return false
        return frame.messageId == MavlinkMsgId.MISSION_ACK
    }

    /** MAVLink 2 truncates trailing zeros, so pad before reading fixed offsets. */
    private fun paddedPayload(data: ByteArray, payloadLength: Int): ByteArray {
        val payload = ByteArray(MAX_PAYLOAD)
        System.arraycopy(data, HEADER_BYTES, payload, 0, payloadLength)
        return payload
    }

    /** Frame-level facts shared by every parse path. */
    private data class Frame(val messageId: Int, val payloadLength: Int)

    private fun validate(data: ByteArray, length: Int): Frame? {
        if (length < HEADER_BYTES + CHECKSUM_BYTES) return null
        if (data[0] != MavlinkFramer.MAGIC_V2) return null

        val payloadLength = data[1].toInt() and 0xFF
        val incompatFlags = data[2].toInt() and 0xFF
        val signatureLength = if (incompatFlags and INCOMPAT_SIGNED != 0) SIGNATURE_BYTES else 0
        if (length < HEADER_BYTES + payloadLength + CHECKSUM_BYTES + signatureLength) return null

        val messageId = (data[7].toInt() and 0xFF) or
            ((data[8].toInt() and 0xFF) shl 8) or
            ((data[9].toInt() and 0xFF) shl 16)
        val crcExtra = MavlinkCrc.CRC_EXTRA[messageId] ?: return null
        val expected = MavlinkCrc.checksum(data, 1, HEADER_BYTES - 1 + payloadLength, crcExtra)
        val actual = (data[HEADER_BYTES + payloadLength].toInt() and 0xFF) or
            ((data[HEADER_BYTES + payloadLength + 1].toInt() and 0xFF) shl 8)
        if (expected != actual) return null

        return Frame(messageId, payloadLength)
    }

    /**
     * Parse a SET_MODE frame. Wire order is by descending type size: custom_mode(u32) first, then
     * target_system(u8) and base_mode(u8) in declaration order.
     */
    fun parseSetMode(data: ByteArray, length: Int): MavlinkSetMode? {
        val frame = validate(data, length) ?: return null
        if (frame.messageId != MavlinkMsgId.SET_MODE) return null
        val payload = ByteArray(MAX_PAYLOAD)
        System.arraycopy(data, HEADER_BYTES, payload, 0, frame.payloadLength)
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        return MavlinkSetMode(
            targetSystem = payload[4].toInt() and 0xFF,
            baseMode = payload[5].toInt() and 0xFF,
            customMode = buffer.getInt(0),
            senderSystem = data[5].toInt() and 0xFF,
            senderComponent = data[6].toInt() and 0xFF
        )
    }

    /**
     * Parse a FILE_TRANSFER_PROTOCOL frame. Returns the requester's system/component ids (from the
     * frame header, so the reply can target them) plus the 251-byte FTP payload, or null when the
     * frame is not FTP or is malformed.
     *
     * The message payload is the three target bytes followed by the FTP payload; trailing zero
     * truncation is normal on MAVLink 2, so the missing bytes are zero-filled.
     */
    fun parseFtp(data: ByteArray, length: Int): FtpFrame? {
        val frame = validate(data, length) ?: return null
        if (frame.messageId != MavlinkMsgId.FILE_TRANSFER_PROTOCOL) return null
        val payload = ByteArray(MavlinkFtp.PAYLOAD_BYTES)
        val copied = (frame.payloadLength - FTP_TARGET_BYTES)
            .coerceIn(0, MavlinkFtp.PAYLOAD_BYTES)
        System.arraycopy(data, HEADER_BYTES + FTP_TARGET_BYTES, payload, 0, copied)
        return FtpFrame(
            requesterSystem = data[5].toInt() and 0xFF,
            requesterComponent = data[6].toInt() and 0xFF,
            payload = payload
        )
    }

    /** A FILE_TRANSFER_PROTOCOL frame plus who sent it. */
    data class FtpFrame(val requesterSystem: Int, val requesterComponent: Int, val payload: ByteArray)

    /**
     * Parse one datagram. Returns null when the frame is not a command, is malformed, or fails its
     * checksum.
     */
    fun parseCommand(data: ByteArray, length: Int): MavlinkCommand? {
        val frame = validate(data, length) ?: return null
        if (frame.messageId != MavlinkMsgId.COMMAND_LONG &&
            frame.messageId != MavlinkMsgId.COMMAND_INT
        ) {
            return null
        }
        val payloadLength = frame.payloadLength

        // MAVLink 2 senders truncate trailing zero bytes, so a short payload is normal and the
        // missing bytes are zeros. Copy into a full-width buffer rather than reading past the end.
        val payload = ByteArray(MAX_PAYLOAD)
        System.arraycopy(data, HEADER_BYTES, payload, 0, payloadLength)
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)

        // COMMAND_LONG holds param5-7 as floats. COMMAND_INT stores x/y as int32 lat/lon in 1e7
        // degrees and z as a float, so normalise x/y back to degrees — the one motion command that
        // uses a position (DO_REPOSITION) then reads param5/6/7 uniformly regardless of encoding.
        val isCommandInt = frame.messageId == MavlinkMsgId.COMMAND_INT
        return MavlinkCommand(
            command = buffer.getShort(COMMAND_ID_OFFSET).toInt() and 0xFFFF,
            targetSystem = payload[TARGET_SYSTEM_OFFSET].toInt() and 0xFF,
            targetComponent = payload[TARGET_COMPONENT_OFFSET].toInt() and 0xFF,
            senderSystem = data[5].toInt() and 0xFF,
            senderComponent = data[6].toInt() and 0xFF,
            param1 = buffer.getFloat(0),
            param2 = buffer.getFloat(4),
            param3 = buffer.getFloat(8),
            param4 = buffer.getFloat(12),
            param5 = if (isCommandInt) buffer.getInt(16) / 1e7f else buffer.getFloat(16),
            param6 = if (isCommandInt) buffer.getInt(20) / 1e7f else buffer.getFloat(20),
            param7 = buffer.getFloat(24),
            latitudeDeg = if (isCommandInt) {
                buffer.getInt(16) / COORD_SCALE
            } else {
                buffer.getFloat(16).toDouble()
            },
            longitudeDeg = if (isCommandInt) {
                buffer.getInt(20) / COORD_SCALE
            } else {
                buffer.getFloat(20).toDouble()
            }
        )
    }
}
