package com.lyrebird.rc.mavlink

/**
 * MAVLink checksum: CRC-16/MCRF4XX, the variant MAVLink calls "X.25".
 *
 * The checksum runs over every byte of the frame from LEN (index 1) to the end of the payload,
 * and then over one extra byte — CRC_EXTRA — derived from the message's field definition. That
 * extra byte is what makes a receiver reject a message whose sender was built against a different
 * version of the message definition, so the values must match the dialect exactly.
 *
 * The constants in [CRC_EXTRA] were generated from the upstream `common.xml`
 * (via minimal.xml → standard.xml → common.xml) rather than copied by hand; the generator was
 * validated against the canonical HEARTBEAT value of 50 before the rest were taken from it.
 */
internal object MavlinkCrc {

    /** CRC_EXTRA per message id, for the messages Lyrebird emits. */
    val CRC_EXTRA: Map<Int, Int> = mapOf(
        MavlinkMsgId.HEARTBEAT to 50,
        MavlinkMsgId.SYS_STATUS to 124,
        MavlinkMsgId.GPS_RAW_INT to 24,
        MavlinkMsgId.ATTITUDE to 39,
        MavlinkMsgId.GLOBAL_POSITION_INT to 104,
        MavlinkMsgId.VFR_HUD to 20,
        MavlinkMsgId.BATTERY_STATUS to 154,
        MavlinkMsgId.AUTOPILOT_VERSION to 178,
        MavlinkMsgId.HOME_POSITION to 104,
        MavlinkMsgId.STATUSTEXT to 83,
        MavlinkMsgId.PARAM_VALUE to 220,
        MavlinkMsgId.MISSION_COUNT to 221,
        MavlinkMsgId.MISSION_CURRENT to 28,
        MavlinkMsgId.MISSION_ITEM_REACHED to 11,
        MavlinkMsgId.MISSION_ACK to 153,
        MavlinkMsgId.MISSION_REQUEST_INT to 196,
        // Received as well as sent — the checksum of an inbound item is validated with this too.
        MavlinkMsgId.MISSION_ITEM_INT to 38,
        MavlinkMsgId.MISSION_CLEAR_ALL to 232,
        MavlinkMsgId.CAMERA_INFORMATION to 92,
        MavlinkMsgId.VIDEO_STREAM_INFORMATION to 109,
        MavlinkMsgId.CAMERA_SETTINGS to 146,
        MavlinkMsgId.STORAGE_INFORMATION to 179,
        MavlinkMsgId.VIDEO_STREAM_STATUS to 59,
        MavlinkMsgId.CAMERA_CAPTURE_STATUS to 12,
        MavlinkMsgId.CAMERA_IMAGE_CAPTURED to 133,
        MavlinkMsgId.AVAILABLE_MODES to 134,
        MavlinkMsgId.CURRENT_MODE to 193,
        MavlinkMsgId.COMMAND_ACK to 143,
        MavlinkMsgId.SET_MODE to 89,
        MavlinkMsgId.EXTENDED_SYS_STATE to 130,
        // Received, not sent — needed to validate the checksum of inbound commands.
        MavlinkMsgId.COMMAND_INT to 158,
        MavlinkMsgId.COMMAND_LONG to 152,
        MavlinkMsgId.MANUAL_CONTROL to 243,
        MavlinkMsgId.PARAM_SET to 168,
        MavlinkMsgId.PARAM_EXT_REQUEST_LIST to 88,
        // Received, not sent — an inbound FTP request is checksum-validated with this.
        MavlinkMsgId.FILE_TRANSFER_PROTOCOL to 84,
        MavlinkMsgId.PARAM_EXT_VALUE to 243,
        MavlinkMsgId.PARAM_EXT_SET to 78,
        MavlinkMsgId.PARAM_EXT_ACK to 132,
        MavlinkMsgId.GIMBAL_DEVICE_ATTITUDE_STATUS to 137,
        MavlinkMsgId.DISTANCE_SENSOR to 85,
        // Generated from lyrebird.xml with pymavlink's mavgen, not computed by hand.
        MavlinkMsgId.LYREBIRD_STATUS to 196,
        MavlinkMsgId.LYREBIRD_CONFIG to 201,
        MavlinkMsgId.AUTOSENSING_STATUS to 254,
        MavlinkMsgId.AUTOSENSING_TARGET to 83,
        MavlinkMsgId.PARAM_REQUEST_LIST to 159,
        MavlinkMsgId.MISSION_REQUEST_LIST to 132
    )

    /** Accumulate one byte into a running CRC. */
    private fun accumulate(crc: Int, byte: Int): Int {
        var tmp = byte xor (crc and 0xFF)
        tmp = (tmp xor (tmp shl 4)) and 0xFF
        return ((crc ushr 8) xor (tmp shl 8) xor (tmp shl 3) xor (tmp ushr 4)) and 0xFFFF
    }

    /**
     * Checksum of [length] bytes of [data] starting at [offset], finished with [crcExtra].
     */
    fun checksum(data: ByteArray, offset: Int, length: Int, crcExtra: Int): Int {
        var crc = 0xFFFF
        for (i in offset until offset + length) {
            crc = accumulate(crc, data[i].toInt() and 0xFF)
        }
        return accumulate(crc, crcExtra and 0xFF)
    }
}
