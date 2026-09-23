package com.lyrebird.rc.controller

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The control loops' measured timestep: the first tick, the value between ticks, and the clamps
 * that keep a stalled main Looper from spiking a PID or the acceleration limiter.
 */
class LoopTimingTest {
    @Test
    fun `the first tick uses the nominal interval`() {
        val timing = LoopTiming(nominalIntervalMs = 100L, maxDtSec = 0.5)

        assertEquals(0.1, timing.nextDtSec(5_000L), 0.0)
    }

    @Test
    fun `a tick measures from the previous one`() {
        val timing = LoopTiming(nominalIntervalMs = 100L, maxDtSec = 0.5)

        timing.nextDtSec(1_000L)

        // A late tick is reported as the real 160 ms, not as the nominal 100 ms.
        assertEquals(0.16, timing.nextDtSec(1_160L), 0.0)
    }

    @Test
    fun `a stalled loop is clamped to its own ceiling`() {
        val waypoint = LoopTiming(nominalIntervalMs = 100L, maxDtSec = 0.5)
        waypoint.nextDtSec(10_000L)
        assertEquals(0.5, waypoint.nextDtSec(60_000L), 0.0)

        val trajectory = LoopTiming(nominalIntervalMs = 100L, maxDtSec = 2.0)
        trajectory.nextDtSec(10_000L)
        assertEquals(2.0, trajectory.nextDtSec(60_000L), 0.0)
    }

    @Test
    fun `a re-tick in the same millisecond is clamped to the floor`() {
        val timing = LoopTiming(nominalIntervalMs = 100L, maxDtSec = 0.5)
        timing.nextDtSec(10_000L)

        assertEquals(0.02, timing.nextDtSec(10_000L), 0.0)
    }

    @Test
    fun `elapsed since the last tick is null before the first one`() {
        val timing = LoopTiming(nominalIntervalMs = 100L, maxDtSec = 0.5)

        assertNull(timing.elapsedSinceLastTickMs(1_000L))

        timing.nextDtSec(1_000L)

        // This is the trajectory loop's resend cadence check: 40 ms since the control tick is a
        // re-send, a full interval is the next control tick.
        assertEquals(40L, timing.elapsedSinceLastTickMs(1_040L))
        assertEquals(100L, timing.elapsedSinceLastTickMs(1_100L))
    }
}
