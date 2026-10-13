package com.lyrebird.rc.mavlink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gimbal v2 manager QGroundControl needs before it shows gimbal controls.
 *
 * The wire payloads are compared with pymavlink's encoding of the same values (MAVLink 2 trims
 * trailing zero bytes, so the reference is compared against the trimmed payload).
 */
class GimbalManagerTest {
    @Test
    fun `the information payload matches pymavlink`() {
        // pymavlink 2.4.49: gimbal_manager_information(1234, 32|128|256|512, 1, 0, 0,
        // radians(-90), radians(30), radians(-180), radians(180)), payload bytes.
        assertEquals(
            "d2040000a00300000000000000000000db0fc9bf920a063fdb0f49c0db0f494001",
            hex(trimmed(MavlinkMessages.gimbalManagerInformation(1234))),
        )
    }

    @Test
    fun `the status payload matches pymavlink and names who is in control`() {
        // pymavlink 2.4.49: gimbal_manager_status(1234, 4|8, 1, 255, 190, 0, 0), payload bytes.
        val control = GimbalControl(primarySystem = 255, primaryComponent = 190)
        assertEquals("d20400000c00000001ffbe", hex(trimmed(MavlinkMessages.gimbalManagerStatus(control, 1234))))
    }

    @Test
    fun `the checksum seeds match the generated dialect`() {
        assertEquals(70, MavlinkCrc.CRC_EXTRA[MavlinkMsgId.GIMBAL_MANAGER_INFORMATION])
        assertEquals(48, MavlinkCrc.CRC_EXTRA[MavlinkMsgId.GIMBAL_MANAGER_STATUS])
    }

    @Test
    fun `the attitude names the same device the manager does`() {
        // QGC pairs attitude, information and status by device id; 0 never paired.
        val payload = MavlinkMessages.gimbalDeviceAttitudeStatus(MavlinkSnapshot(), 0)
        assertEquals(GimbalManager.DEVICE_ID, payload.last().toInt())
    }

    @Test
    fun `angles become an absolute rotation`() {
        val rotation = GimbalManager.rotationFor(-30f, 10f, Float.NaN, Float.NaN)!!
        assertEquals(GimbalRotationMode.ABSOLUTE, rotation.mode)
        assertEquals(-30.0, rotation.pitchDeg, 1e-6)
        assertEquals(10.0, rotation.yawDeg, 1e-6)
        assertFalse(rotation.pitchIgnored)
        assertFalse(rotation.yawIgnored)
        assertTrue(rotation.rollIgnored)
    }

    @Test
    fun `an unset angle leaves its axis alone instead of sending NaN`() {
        val rotation = GimbalManager.rotationFor(-45f, Float.NaN, Float.NaN, Float.NaN)!!
        assertEquals(-45.0, rotation.pitchDeg, 1e-6)
        assertTrue(rotation.yawIgnored)
        assertFalse(rotation.yawDeg.isNaN())
    }

    @Test
    fun `a rate becomes one relative step of half a second`() {
        // QGC's pitch buttons: angles NaN, 20 deg/s, repeated every 500 ms while held.
        val rotation = GimbalManager.rotationFor(Float.NaN, Float.NaN, -20f, 0f)!!
        assertEquals(GimbalRotationMode.RELATIVE, rotation.mode)
        assertEquals(-10.0, rotation.pitchDeg, 1e-6)
        assertTrue(rotation.yawIgnored)
    }

    @Test
    fun `a zero rate with no angle moves nothing`() {
        assertNull(GimbalManager.rotationFor(Float.NaN, Float.NaN, 0f, 0f))
        assertNull(GimbalManager.rotationFor(Float.NaN, Float.NaN, Float.NaN, Float.NaN))
    }

    @Test
    fun `qgc acquiring control puts it in primary control`() {
        // QGC: configure(own sysid, own compid, -1, -1).
        val control = GimbalControl().configure(255f, 190f, -1f, -1f, senderSystem = 255, senderComponent = 190)
        assertEquals(GimbalControl(primarySystem = 255, primaryComponent = 190), control)
    }

    @Test
    fun `releasing frees control only for the holder`() {
        val held = GimbalControl(primarySystem = 255, primaryComponent = 190)
        assertEquals(held, held.configure(-3f, -3f, -1f, -1f, senderSystem = 254, senderComponent = 190))
        assertEquals(GimbalControl(), held.configure(-3f, -3f, -1f, -1f, senderSystem = 255, senderComponent = 190))
    }

    @Test
    fun `minus two puts the sender in control`() {
        val control = GimbalControl().configure(-2f, -2f, -1f, -1f, senderSystem = 7, senderComponent = 1)
        assertEquals(7, control.primarySystem)
        assertEquals(1, control.primaryComponent)
    }

    private fun trimmed(payload: ByteArray): ByteArray {
        var end = payload.size
        while (end > 1 && payload[end - 1] == 0.toByte()) end--
        return payload.copyOf(end)
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
