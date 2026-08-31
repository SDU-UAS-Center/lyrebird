package com.lyrebird.rc.mavlink

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * GIMBAL_DEVICE_ATTITUDE_STATUS encoding, checked against the wire contract rather than a golden
 * blob.
 *
 * The one field this pins is `delta_yaw`, which the MAVLink definition specifies in radians.
 * Sending degrees there is invisible while the aircraft is still — the gimbal's yaw relative to
 * the body sits near zero, and a zero is a zero in either unit — and appears the moment the
 * aircraft moves and the gimbal compensates: a receiver converts the field from radians, so 30
 * degrees sent as 30.0 comes back as 1719 degrees and the two wires disagree in the MAVLink tab.
 */
class MavlinkGimbalMessageTest {

    @Test
    fun deltaYawIsEncodedInRadians() {
        val payload = MavlinkMessages.gimbalDeviceAttitudeStatus(
            MavlinkSnapshot(gimbalJointYawDeg = 30.0),
            timeBootMs = 1234L
        )
        // Base fields end at byte 40: time_boot_ms(u32), q(float[4]), angular rates(3*f32),
        // failure_flags(u32), flags(u16), target_system(u8), target_component(u8); delta_yaw is
        // the first extension field.
        val deltaYaw = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN).getFloat(40)
        assertEquals(
            "delta_yaw is specified in radians, not degrees",
            Math.PI / 6.0,
            deltaYaw.toDouble(),
            1e-6
        )
    }
}
