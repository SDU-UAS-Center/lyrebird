package com.lyrebird.rc.mavlink

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * GIMBAL_DEVICE_ATTITUDE_STATUS encoding, checked against the wire contract rather than a golden
 * blob.
 *
 * Two things are pinned. `delta_yaw` is radians: degrees decode cleanly and mean the wrong thing,
 * invisibly while the aircraft is still. And the frame: the quaternion is earth-frame, so the flags
 * say YAW_IN_EARTH_FRAME and `delta_yaw` is the vehicle's yaw (world yaw minus joint yaw), from
 * which a receiver recovers the joint yaw as quaternion yaw minus `delta_yaw`.
 */
class MavlinkGimbalMessageTest {
    private fun buffer(snapshot: MavlinkSnapshot) =
        ByteBuffer
            .wrap(MavlinkMessages.gimbalDeviceAttitudeStatus(snapshot, timeBootMs = 1234L))
            .order(ByteOrder.LITTLE_ENDIAN)

    @Test
    fun deltaYawIsTheVehicleYawInRadians() {
        // Gimbal pointing at 120 degrees, turned 30 degrees right of the nose: the aircraft faces 90.
        // Base fields end at byte 40: time_boot_ms(u32), q(float[4]), angular rates(3*f32),
        // failure_flags(u32), flags(u16), target_system(u8), target_component(u8); delta_yaw is
        // the first extension field.
        val deltaYaw = buffer(MavlinkSnapshot(gimbalYawDeg = 120.0, gimbalJointYawDeg = 30.0)).getFloat(40)
        assertEquals("delta_yaw is the vehicle yaw, in radians", Math.PI / 2.0, deltaYaw.toDouble(), 1e-6)
    }

    @Test
    fun deltaYawWrapsAcrossNorth() {
        val deltaYaw = buffer(MavlinkSnapshot(gimbalYawDeg = -170.0, gimbalJointYawDeg = 20.0)).getFloat(40)
        assertEquals(Math.toRadians(170.0), deltaYaw.toDouble(), 1e-6)
    }

    @Test
    fun flagsSayEarthFrameAndNotYawLock() {
        // flags(u16) sits at byte 36. 16 is YAW_LOCK, which was sent here once believing it meant
        // "vehicle frame"; QGC then aimed in compass terms and every rotation was refused.
        val flags = buffer(MavlinkSnapshot()).getShort(36).toInt()
        assertEquals(4 or 8 or 64, flags)
    }
}
