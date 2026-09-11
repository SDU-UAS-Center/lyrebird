package com.lyrebird.rc.mavlink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Frame addressing: which system a message is for, and whether its sender flies.
 *
 * Both questions are answered by reading one byte at an offset that mavgen chose, and a wrong
 * offset fails silently rather than loudly — it compares the wrong byte and either drops commands
 * meant for this aircraft or accepts ones meant for another. The offsets under test were taken
 * from the generated dialect, and these frames are built the same way the wire builds them.
 */
class MavlinkAddressingTest {

    private val framer = MavlinkFramer(systemId = 200, componentId = Mav.COMP_ID_AUTOPILOT1)

    @Test
    fun `a command names the system it is addressed to`() {
        val frame = framer.frame(
            MavlinkMsgId.COMMAND_LONG,
            commandLongPayload(targetSystem = 42)
        )
        assertEquals(42, MavlinkInbound.targetSystemOf(frame, frame.size))
    }

    @Test
    fun `a command addressed to the broadcast system reads as zero`() {
        val frame = framer.frame(
            MavlinkMsgId.COMMAND_LONG,
            commandLongPayload(targetSystem = 0)
        )
        assertEquals(0, MavlinkInbound.targetSystemOf(frame, frame.size))
    }

    @Test
    fun `a set-mode request names its target`() {
        val payload = PayloadWriter().u32(0L).u8(77).u8(1).build()
        val frame = framer.frame(MavlinkMsgId.SET_MODE, payload)
        assertEquals(77, MavlinkInbound.targetSystemOf(frame, frame.size))
    }

    @Test
    fun `a mission item names its target`() {
        val item = MissionItem(
            seq = 1,
            command = Mav.CMD_NAV_WAYPOINT,
            param1 = 0f,
            param2 = 0f,
            param3 = 0f,
            param4 = Float.NaN,
            latitudeDeg = 55.47,
            longitudeDeg = 10.32,
            altitudeM = 30.0,
            autocontinue = true
        )
        val payload = MavlinkMessages.missionItemInt(
            item, targetSystem = 88, targetComponent = 1, isCurrent = false
        )
        val frame = framer.frame(MavlinkMsgId.MISSION_ITEM_INT, payload)
        assertEquals(88, MavlinkInbound.targetSystemOf(frame, frame.size))
    }

    @Test
    fun `a heartbeat has no addressee`() {
        val frame = framer.frame(MavlinkMsgId.HEARTBEAT, gcsHeartbeatPayload())
        assertNull(
            "an unaddressed message must pass the gate rather than be dropped",
            MavlinkInbound.targetSystemOf(frame, frame.size)
        )
    }

    @Test
    fun `a truncated target_system reads as broadcast rather than as garbage`() {
        // MAVLink 2 drops trailing zero bytes, so a request-list addressed to the broadcast system
        // arrives with an empty payload. Reading past it must yield zero, not a stale byte.
        val frame = framer.frame(MavlinkMsgId.PARAM_REQUEST_LIST, ByteArray(0))
        assertEquals(0, MavlinkInbound.targetSystemOf(frame, frame.size))
    }

    @Test
    fun `a ground station heartbeat declares no autopilot`() {
        val frame = framer.frame(MavlinkMsgId.HEARTBEAT, gcsHeartbeatPayload())
        assertEquals(
            MavlinkInbound.AUTOPILOT_INVALID,
            MavlinkInbound.heartbeatAutopilot(frame, frame.size)
        )
    }

    @Test
    fun `another Lyrebird aircraft's heartbeat declares a real autopilot`() {
        // Lyrebird reports itself as PX4, which is exactly how a second aircraft is recognised as
        // a vehicle rather than as a ground station worth streaming telemetry at.
        val snapshot = MavlinkSnapshot(droneName = "bravo")
        val frame = framer.frame(MavlinkMsgId.HEARTBEAT, MavlinkMessages.heartbeat(snapshot))
        val autopilot = MavlinkInbound.heartbeatAutopilot(frame, frame.size)
        assertEquals(Mav.AUTOPILOT_PX4, autopilot)
        assert(autopilot != MavlinkInbound.AUTOPILOT_INVALID)
    }

    @Test
    fun `a command is not mistaken for a heartbeat`() {
        val frame = framer.frame(MavlinkMsgId.COMMAND_LONG, commandLongPayload(targetSystem = 1))
        assertNull(MavlinkInbound.heartbeatAutopilot(frame, frame.size))
    }

    @Test
    fun `a corrupt frame yields no addressing information`() {
        val frame = framer.frame(MavlinkMsgId.COMMAND_LONG, commandLongPayload(targetSystem = 1))
        frame[12] = (frame[12] + 1).toByte()
        assertNull(MavlinkInbound.targetSystemOf(frame, frame.size))
        assertNull(MavlinkInbound.heartbeatAutopilot(frame, frame.size))
    }

    /** param1..7(f32), command(u16), target_system(u8), target_component(u8), confirmation(u8). */
    private fun commandLongPayload(targetSystem: Int): ByteArray =
        PayloadWriter()
            .f32(0f).f32(0f).f32(0f).f32(0f).f32(0f).f32(0f).f32(0f)
            .u16(Mav.CMD_NAV_TAKEOFF)
            .u8(targetSystem)
            .u8(Mav.COMP_ID_AUTOPILOT1)
            .u8(0)
            .build()

    /** custom_mode(u32), type(u8), autopilot(u8), base_mode(u8), system_status(u8), version(u8). */
    private fun gcsHeartbeatPayload(): ByteArray =
        PayloadWriter()
            .u32(0L)
            .u8(MAV_TYPE_GCS)
            .u8(MavlinkInbound.AUTOPILOT_INVALID)
            .u8(0)
            .u8(0)
            .u8(MAVLINK_VERSION)
            .build()

    private companion object {
        const val MAV_TYPE_GCS = 6
        const val MAVLINK_VERSION = 3
    }
}
