package com.lyrebird.rc.controller

import com.lyrebird.rc.mavlink.MavlinkCommandOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MavlinkFlightPolicyTest {
    @Test
    fun disabledFlightIsDeniedBeforeAuthorityIsConsulted() {
        var calls = 0
        val policy = MavlinkFlightPolicy {
            calls++
            true
        }

        val result = policy.check(flightAllowed = false, trustedOrigin = false)

        assertEquals(MavlinkCommandOutcome.DENIED, result?.outcome)
        assertEquals(0, calls)
    }

    @Test
    fun pilotIsDeniedWhenAuthorityRejectsIt() {
        val policy = MavlinkFlightPolicy { source -> source == ControlAuthority.Source.SAFETY }

        val result = policy.check(flightAllowed = true, trustedOrigin = false)

        assertEquals(MavlinkCommandOutcome.DENIED, result?.outcome)
    }

    @Test
    fun safetyOriginCanPassWhenAuthorityAllowsIt() {
        val policy = MavlinkFlightPolicy { source -> source == ControlAuthority.Source.SAFETY }

        assertNull(policy.check(flightAllowed = true, trustedOrigin = true))
    }
}