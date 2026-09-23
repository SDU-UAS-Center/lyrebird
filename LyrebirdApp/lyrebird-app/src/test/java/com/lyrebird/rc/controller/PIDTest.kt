package com.lyrebird.rc.controller

import org.junit.Assert.assertEquals
import org.junit.Test

class PIDTest {
    @Test
    fun appliesIntegralWhenOutputIsInsideLimits() {
        val pid = PID(kp = 0.0, ki = 1.0, kd = 0.0, dt = 1.0, outputLimits = null to 10.0)

        assertEquals(2.0, pid.update(2.0), 0.0001)
    }

    @Test
    fun skipsIntegralWhenOutputWouldExceedUpperLimit() {
        val pid = PID(kp = 0.0, ki = 1.0, kd = 0.0, dt = 1.0, outputLimits = null to 1.0)

        assertEquals(0.0, pid.update(2.0), 0.0001)
    }

    @Test
    fun clampsProportionalOutputToConfiguredLimits() {
        val pid = PID(kp = 2.0, ki = 0.0, kd = 0.0, dt = 1.0, outputLimits = -3.0 to 3.0)

        assertEquals(3.0, pid.update(4.0), 0.0001)
        assertEquals(-3.0, pid.update(-4.0), 0.0001)
    }
}
