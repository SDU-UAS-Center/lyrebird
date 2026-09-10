package com.lyrebird.rc.mavlink

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * LYREBIRD_STATUS wire layout.
 *
 * The flags byte and the waypoint/yaw/altitude seq fields are how the ground station learns that
 * a movement arrived even when it missed the completion ack — the fields behind this week's
 * altitude-completion and manual-override field bugs. These tests pin the packed offsets (mavgen
 * sorts fields by size on the wire, so the seq u32s come before the time u16s) so a change to the
 * snapshot or to lyrebird.xml cannot silently zero or shuffle them.
 */
class MavlinkLyrebirdStatusTest {

    private fun status(snapshot: MavlinkSnapshot): ByteArray =
        MavlinkMessages.lyrebirdStatus(snapshot, timeBootMs = 1234)

    private fun u32At(bytes: ByteArray, offset: Int): Long =
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(offset).toLong() and 0xFFFFFFFFL

    @Test
    fun flagsAndSeqsPackAtTheWireOffsets() {
        val bytes = status(
            MavlinkSnapshot(
                manualOverrideActive = true,
                readyToTakeoff = true,
                waypointReached = true,
                waypointSeq = 7,
                yawReached = true,
                yawSeq = 8,
                altitudeReached = true,
                altitudeSeq = 9,
                takeoffBlockReason = "NONE"
            )
        )
        assertEquals(75, bytes.size)
        // After time_boot_ms, the two lrf int32s, the lrf float and max_radius (20 bytes) the
        // three seq u32s are packed, before the time u16s.
        assertEquals(7L, u32At(bytes, 20)) // waypoint_seq
        assertEquals(8L, u32At(bytes, 24)) // yaw_seq
        assertEquals(9L, u32At(bytes, 28)) // altitude_seq
        // flags is the last byte before the 24-char reason.
        val flags = bytes[50].toInt() and 0xFF
        assertEquals(1 or 2 or 16 or 32 or 64, flags)
        val reason = bytes.copyOfRange(51, 75).toString(Charsets.US_ASCII).trimEnd('\u0000')
        assertEquals("NONE", reason)
    }

    @Test
    fun cleanSnapshotSendsNoReachedFlags() {
        val bytes = status(MavlinkSnapshot(takeoffBlockReason = "NONE"))
        assertEquals(0, bytes[50].toInt() and 0xFF)
        assertEquals(0L, u32At(bytes, 20))
        assertEquals(0L, u32At(bytes, 24))
        assertEquals(0L, u32At(bytes, 28))
    }
}
