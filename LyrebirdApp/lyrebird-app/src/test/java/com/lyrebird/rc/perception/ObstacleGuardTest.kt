package com.lyrebird.rc.perception

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The guard's one action and its many silences, on a fake clock: a guard that brakes when it
 * should not gets switched off before the flight where it mattered, so the refusals are tested
 * as hard as the brake.
 *
 * [ObstacleGuard] is a process singleton, so each test arms every provider it depends on and
 * jumps the clock to a fresh epoch — an old latch or blocked arc can never leak into a later
 * test.
 */
class ObstacleGuardTest {
    private val stopped = mutableListOf<String>()
    private val brakes = mutableListOf<String>()
    private var clock = 0L

    private fun armGuard() {
        clock = ++epoch * 1_000_000L
        stopped.clear()
        brakes.clear()
        ObstacleGuard.nowMs = { clock }
        ObstacleGuard.motionProvider = { ObstacleGuard.Motion(6.0, 0.0, 0.0, 0.0) }
        ObstacleGuard.autonomousMotionProvider = { true }
        ObstacleGuard.manualOverrideProvider = { false }
        ObstacleGuard.stopMotion = { stopped += "stop" }
        ObstacleGuard.onBrake = { event -> brakes += event.reason.name }
    }

    @Test
    fun `an unnamed flight is never stopped`() {
        armGuard()
        ObstacleGuard.autonomousMotionProvider = null
        ObstacleGuard.manualOverrideProvider = null

        ObstacleGuard.onReading(readingWithObstacle())

        assertTrue(stopped.isEmpty())
        assertTrue(brakes.isEmpty())
        assertFalse(ObstacleGuard.isLatched)
    }

    @Test
    fun `the pilot on the sticks is never countermanded`() {
        armGuard()
        ObstacleGuard.manualOverrideProvider = { true }

        ObstacleGuard.onReading(readingWithObstacle())

        assertTrue("a human on the sticks must not be overridden by a phone", stopped.isEmpty())
        assertTrue(brakes.isEmpty())
    }

    @Test
    fun `a missing motion report is not a stationary aircraft`() {
        armGuard()
        ObstacleGuard.motionProvider = { null }

        ObstacleGuard.onReading(readingWithObstacle())

        assertTrue(stopped.isEmpty())
        assertTrue(brakes.isEmpty())
    }

    @Test
    fun `a stopper-less guard does not latch or report`() {
        armGuard()
        ObstacleGuard.stopMotion = null

        ObstacleGuard.onReading(readingWithObstacle())

        assertTrue(brakes.isEmpty())
        assertFalse(ObstacleGuard.isLatched)
    }

    @Test
    fun `a close obstacle stops the loop, latches and reports`() {
        armGuard()

        ObstacleGuard.onReading(readingWithObstacle())

        assertEquals(listOf("stop"), stopped)
        assertEquals(listOf("HORIZONTAL"), brakes)
        assertTrue(ObstacleGuard.isLatched)
        assertNotNull(ObstacleGuard.lastBrake)
        assertNotNull("a horizontal brake records the bearing it measured", ObstacleGuard.blockedArc)
    }

    @Test
    fun `the latch suppresses sweeps until it expires`() {
        armGuard()
        ObstacleGuard.onReading(readingWithObstacle())
        stopped.clear()
        brakes.clear()

        ObstacleGuard.onReading(readingWithObstacle())
        assertTrue("a latched guard must not re-fire on every sweep", stopped.isEmpty())

        clock += ObstacleGuard.BRAKE_LATCH_MS + 1

        ObstacleGuard.onReading(readingWithObstacle())
        assertEquals(listOf("stop"), stopped)
        assertEquals(listOf("HORIZONTAL"), brakes)
    }

    /** A full ring of clear air with one sector holding an obstacle dead ahead at [rangeM]. */
    private fun readingWithObstacle(rangeM: Double = 6.0): ObstacleReading {
        val sectorCount = 72
        val intervalDeg = 360.0 / sectorCount
        val ring = MutableList(sectorCount) { 400.0 }
        ring[sectorIndexFor(0.0, intervalDeg, sectorCount)] = rangeM
        return ObstacleReading(ring, intervalDeg, Double.NaN, Double.NaN, 0L)
    }

    private companion object {
        var epoch = 0L
    }
}
