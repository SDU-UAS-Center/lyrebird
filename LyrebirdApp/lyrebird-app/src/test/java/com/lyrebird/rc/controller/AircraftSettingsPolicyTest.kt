package com.lyrebird.rc.controller

import com.lyrebird.rc.mavlink.MavlinkCommandOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The validation and prose behind the aircraft-settings writes.
 *
 * The HTTP route this came from answered every failed write with "Invalid control mode
 * (jp|usa|ch|custom)", so a ground station was told its request was malformed when the real
 * reason was an aircraft in standby (or, before this batch, no screen attached to reach it).
 * These cases pin the distinction.
 */
class AircraftSettingsPolicyTest {
    @Test
    fun `control mode values the wire accepts are normalized`() {
        assertEquals("usa", AircraftSettingsPolicy.normalizeControlMode("usa"))
        assertEquals("usa", AircraftSettingsPolicy.normalizeControlMode(" USA "))
        assertEquals("jp", AircraftSettingsPolicy.normalizeControlMode("JP"))
        assertEquals("custom", AircraftSettingsPolicy.normalizeControlMode("Custom"))
    }

    @Test
    fun `a value that names no DJI control mode is the one client error`() {
        assertNull(AircraftSettingsPolicy.normalizeControlMode("canada"))
        assertNull(AircraftSettingsPolicy.normalizeControlMode(""))
        assertNull(AircraftSettingsPolicy.normalizeControlMode("usa,ch"))

        val rejected = AircraftSettingsPolicy.rejectedControlMode()
        assertEquals(MavlinkCommandOutcome.DENIED, rejected.outcome)
        assertEquals("Invalid control mode (jp|usa|ch|custom)", rejected.detail)
    }

    @Test
    fun `a standby refusal says the aircraft is asleep rather than blaming the request`() {
        val refused = AircraftSettingsPolicy.controlModeRefused("usa", inStandby = true)

        assertEquals(MavlinkCommandOutcome.DENIED, refused.outcome)
        assertEquals("Aircraft in standby: RC control mode not changed (wanted usa)", refused.detail)
    }

    @Test
    fun `a refusal with the aircraft awake reports the write did not take`() {
        val refused = AircraftSettingsPolicy.controlModeRefused("jp", inStandby = false)

        assertEquals(MavlinkCommandOutcome.DENIED, refused.outcome)
        assertEquals("RC control mode not applied (jp)", refused.detail)
    }
}
