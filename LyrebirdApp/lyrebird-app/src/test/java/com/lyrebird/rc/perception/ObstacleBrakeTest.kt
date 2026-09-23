package com.lyrebird.rc.perception

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The obstacle guard decides whether to stop an aircraft in flight, so its silences are tested as
 * hard as its firings. A guard that brakes when it should not is not a cautious guard: it is one
 * that gets switched off before the flight where it mattered.
 */
class ObstacleBrakeTest {

    private val sectorCount = 72
    private val intervalDeg = 360.0 / sectorCount

    @Test
    fun `required clearance grows faster than speed`() {
        val slow = ObstacleBrake.requiredClearanceM(2.0)
        val fast = ObstacleBrake.requiredClearanceM(10.0)
        assertTrue("braking distance is quadratic, so this must more than double", fast > slow * 2)
        // 3 m margin + 0.5 s at 10 m/s + 100/(2*2.5).
        assertEquals(28.0, fast, 0.01)
    }

    @Test
    fun `a stationary aircraft still keeps its standoff`() {
        assertEquals(ObstacleBrake.DEFAULT_MARGIN_M, ObstacleBrake.requiredClearanceM(0.0), 0.001)
    }

    @Test
    fun `above a certain speed the aircraft cannot stop inside its own sensor range`() {
        assertTrue(ObstacleBrake.canStopWithinSensorRange(speedMps = 3.0, sensorRangeM = 30.0))
        assertFalse(ObstacleBrake.canStopWithinSensorRange(speedMps = 12.0, sensorRangeM = 30.0))
    }

    @Test
    fun `nothing is stopped while the aircraft is not under autonomous control`() {
        val decision = ObstacleBrake.evaluate(
            reading = readingWithObstacle(bearingDeg = 0.0, rangeM = 1.0),
            velocityNorthMps = 8.0,
            velocityEastMps = 0.0,
            velocityDownMps = 0.0,
            headingDeg = 0.0,
            autonomousMotionActive = false
        )
        assertFalse("a human on the sticks is never countermanded", decision.shouldBrake)
    }

    @Test
    fun `an obstacle dead ahead on the flight path stops the aircraft`() {
        val decision = ObstacleBrake.evaluate(
            reading = readingWithObstacle(bearingDeg = 0.0, rangeM = 6.0),
            velocityNorthMps = 6.0,
            velocityEastMps = 0.0,
            velocityDownMps = 0.0,
            headingDeg = 0.0,
            autonomousMotionActive = true
        )
        assertTrue(decision.shouldBrake)
        assertEquals(BrakeReason.HORIZONTAL, decision.reason)
        assertEquals(6.0, decision.clearanceM, 0.001)
    }

    @Test
    fun `the same obstacle at the same range is ignored at a crawl`() {
        // 6 m of clearance is plenty at 1 m/s and not enough at 6 m/s. The threshold, not the
        // obstacle, is what changed.
        val decision = ObstacleBrake.evaluate(
            reading = readingWithObstacle(bearingDeg = 0.0, rangeM = 6.0),
            velocityNorthMps = 1.0,
            velocityEastMps = 0.0,
            velocityDownMps = 0.0,
            headingDeg = 0.0,
            autonomousMotionActive = true
        )
        assertFalse(decision.shouldBrake)
    }

    @Test
    fun `an obstacle behind the aircraft is ignored`() {
        val decision = ObstacleBrake.evaluate(
            reading = readingWithObstacle(bearingDeg = 180.0, rangeM = 2.0),
            velocityNorthMps = 6.0,
            velocityEastMps = 0.0,
            velocityDownMps = 0.0,
            headingDeg = 0.0,
            autonomousMotionActive = true
        )
        assertFalse("flying away from something is not a hazard", decision.shouldBrake)
    }

    @Test
    fun `an obstacle to the side is caught when the aircraft strafes into it`() {
        // Nose north, flying due east: the hold-heading waypoint controller does exactly this, and
        // a guard that only watched the nose would miss the entire obstacle.
        val decision = ObstacleBrake.evaluate(
            reading = readingWithObstacle(bearingDeg = 90.0, rangeM = 5.0),
            velocityNorthMps = 0.0,
            velocityEastMps = 6.0,
            velocityDownMps = 0.0,
            headingDeg = 0.0,
            autonomousMotionActive = true
        )
        assertTrue(decision.shouldBrake)
        assertEquals(90.0, decision.bearingFromNoseDeg, intervalDeg)
    }

    @Test
    fun `the travel arc follows the heading, not the compass`() {
        // Nose east, flying east. The obstacle is off the nose in body frame, which is sector 0,
        // even though its compass bearing is 90.
        val decision = ObstacleBrake.evaluate(
            reading = readingWithObstacle(bearingDeg = 0.0, rangeM = 5.0),
            velocityNorthMps = 0.0,
            velocityEastMps = 6.0,
            velocityDownMps = 0.0,
            headingDeg = 90.0,
            autonomousMotionActive = true
        )
        assertTrue(decision.shouldBrake)
    }

    @Test
    fun `a hovering aircraft only brakes at the standoff distance`() {
        val justOutside = ObstacleBrake.evaluate(
            reading = readingWithObstacle(bearingDeg = 0.0, rangeM = 4.0),
            velocityNorthMps = 0.0, velocityEastMps = 0.0, velocityDownMps = 0.0,
            headingDeg = 0.0, autonomousMotionActive = true
        )
        assertFalse(justOutside.shouldBrake)

        val insideStandoff = ObstacleBrake.evaluate(
            reading = readingWithObstacle(bearingDeg = 200.0, rangeM = 1.5),
            velocityNorthMps = 0.0, velocityEastMps = 0.0, velocityDownMps = 0.0,
            headingDeg = 0.0, autonomousMotionActive = true
        )
        assertTrue("a hover watches the whole ring", insideStandoff.shouldBrake)
    }

    @Test
    fun `a climb into something overhead is stopped`() {
        val decision = ObstacleBrake.evaluate(
            reading = clearRing().copy(upwardM = 3.0),
            velocityNorthMps = 0.0,
            velocityEastMps = 0.0,
            velocityDownMps = -3.0,
            headingDeg = 0.0,
            autonomousMotionActive = true
        )
        assertTrue(decision.shouldBrake)
        assertEquals(BrakeReason.UPWARD, decision.reason)
    }

    @Test
    fun `something overhead is ignored while descending away from it`() {
        val decision = ObstacleBrake.evaluate(
            reading = clearRing().copy(upwardM = 1.0),
            velocityNorthMps = 0.0,
            velocityEastMps = 0.0,
            velocityDownMps = 2.0,
            headingDeg = 0.0,
            autonomousMotionActive = true
        )
        assertFalse(decision.reason == BrakeReason.UPWARD)
    }

    @Test
    fun `the ground is ignored while holding altitude`() {
        // The downward sensor sees the ground for the whole flight. Reading that as an obstacle
        // would brake the aircraft permanently.
        val decision = ObstacleBrake.evaluate(
            reading = clearRing().copy(downwardM = 2.0),
            velocityNorthMps = 0.0,
            velocityEastMps = 0.0,
            velocityDownMps = 0.0,
            headingDeg = 0.0,
            autonomousMotionActive = true
        )
        assertFalse(decision.shouldBrake)
    }

    @Test
    fun `a sensor with nothing to say never brakes`() {
        // Every sector unknown. This is what a blind arc and a dropped frame both look like, and
        // treating either as an obstacle would stop the aircraft for no reason.
        val blind = ObstacleReading(
            horizontalM = List(sectorCount) { Double.NaN },
            angleIntervalDeg = intervalDeg,
            upwardM = Double.NaN,
            downwardM = Double.NaN,
            timestampMs = 0L
        )
        val decision = ObstacleBrake.evaluate(
            reading = blind,
            velocityNorthMps = 8.0, velocityEastMps = 0.0, velocityDownMps = 0.0,
            headingDeg = 0.0, autonomousMotionActive = true
        )
        assertFalse(decision.shouldBrake)
    }

    @Test
    fun `a zero reading is a dropout rather than an obstacle in contact`() {
        val reading = ObstacleReading.fromMillimetres(
            horizontalMm = List(sectorCount) { 0 },
            angleIntervalDeg = intervalDeg,
            upwardMm = 0,
            downwardMm = 0,
            timestampMs = 0L
        )
        assertFalse(reading.hasHorizontalData)
        assertFalse(reading.upwardM.isFinite())
    }

    @Test
    fun `an empty sweep never brakes`() {
        val decision = ObstacleBrake.evaluate(
            reading = ObstacleReading.EMPTY,
            velocityNorthMps = 8.0, velocityEastMps = 0.0, velocityDownMps = 0.0,
            headingDeg = 0.0, autonomousMotionActive = true
        )
        assertFalse(decision.shouldBrake)
    }

    @Test
    fun `millimetres become metres and a missing interval is derived from the ring`() {
        val reading = ObstacleReading.fromMillimetres(
            horizontalMm = List(sectorCount) { 12_500 },
            angleIntervalDeg = 0.0,
            upwardMm = 4_000,
            downwardMm = 9_000,
            timestampMs = 7L
        )
        assertEquals(12.5, reading.minimumHorizontal(), 0.001)
        assertEquals(intervalDeg, reading.angleIntervalDeg, 0.001)
        assertEquals(4.0, reading.upwardM, 0.001)
        assertEquals(9.0, reading.downwardM, 0.001)
    }

    @Test
    fun `a hovering aircraft has no direction of travel`() {
        assertNull(travelBearingFromNoseDeg(0.05, -0.05, headingDeg = 10.0))
    }

    @Test
    fun `travel bearing is measured from the nose`() {
        // Flying due north with the nose east means travelling 270 degrees off the nose.
        val bearing = travelBearingFromNoseDeg(
            velocityNorthMps = 5.0, velocityEastMps = 0.0, headingDeg = 90.0
        )
        assertEquals(270.0, bearing!!, 0.01)
    }

    @Test
    fun `angular separation wraps the short way round`() {
        assertEquals(20.0, angularSeparationDeg(350.0, 10.0), 0.001)
        assertEquals(180.0, angularSeparationDeg(0.0, 180.0), 0.001)
    }

    /** A full ring of clear air, with one sector holding an obstacle at the given bearing. */
    private fun readingWithObstacle(bearingDeg: Double, rangeM: Double): ObstacleReading {
        val index = sectorIndexFor(bearingDeg, intervalDeg, sectorCount)
        val ring = MutableList(sectorCount) { FAR_M }
        ring[index] = rangeM
        return ObstacleReading(ring, intervalDeg, Double.NaN, Double.NaN, 0L)
    }

    private fun clearRing(): ObstacleReading =
        ObstacleReading(List(sectorCount) { FAR_M }, intervalDeg, Double.NaN, Double.NaN, 0L)

    private companion object {
        /** Beyond any threshold the guard computes, so it never triggers on its own. */
        const val FAR_M = 400.0
    }
}
