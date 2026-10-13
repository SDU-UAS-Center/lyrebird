package com.lyrebird.rc.mavlink

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * PROTOCOL_VERSION: the answer that keeps QGroundControl 5.0 on MAVLink 2.
 *
 * Unanswered, QGC 5.0.8 fell back to MAVLink 1 framing about a second into the connect, and the
 * endpoint (which reads only MAVLink 2) stopped hearing it: parameters stalled and commands went
 * unacknowledged. The expected values are from the generated `mavlink_msg_protocol_version.h`.
 */
class MavlinkProtocolVersionTest {
    @Test
    fun `the message id and checksum seed match the generated dialect`() {
        assertEquals(300, MavlinkMsgId.PROTOCOL_VERSION)
        assertEquals(217, MavlinkCrc.CRC_EXTRA[MavlinkMsgId.PROTOCOL_VERSION])
    }

    @Test
    fun `the payload advertises MAVLink 2 only`() {
        val payload = MavlinkMessages.protocolVersion()
        assertEquals(22, payload.size)
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(200, buffer.getShort(0).toInt()) // version
        assertEquals(200, buffer.getShort(2).toInt()) // min_version: MAVLink 1 is not read
        assertEquals(200, buffer.getShort(4).toInt()) // max_version
        assertEquals(0, payload.copyOfRange(6, 22).count { it != 0.toByte() })
    }
}
