package com.lyrebird.rc.controller

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrbitControlTest {

    /** On the circle already, so nothing to correct: the velocity is purely around it. */
    @Test
    fun onRadiusTravelsTangentially() {
        // Due north of the centre at exactly the radius. Clockwise from above means heading east.
        val clockwise = OrbitControl.velocity(
            northM = 50.0, eastM = 0.0, radiusM = 50.0,
            tangentialMps = 5.0, clockwise = true, radialGain = 0.5, maxRadialMps = 3.0
        )
        assertEquals(5.0, clockwise.speedMps, 0.001)
        assertEquals(90.0, clockwise.directionDeg, 0.001)

        val anticlockwise = OrbitControl.velocity(
            northM = 50.0, eastM = 0.0, radiusM = 50.0,
            tangentialMps = 5.0, clockwise = false, radialGain = 0.5, maxRadialMps = 3.0
        )
        assertEquals(270.0, anticlockwise.directionDeg, 0.001)
    }

    /** The whole point of the radial term: an aircraft outside the circle is pulled in. */
    @Test
    fun outsideTheCircleIsPulledInward() {
        val velocity = OrbitControl.velocity(
            northM = 60.0, eastM = 0.0, radiusM = 50.0,
            tangentialMps = 0.0, clockwise = true, radialGain = 0.5, maxRadialMps = 6.0
        )
        // Ten metres out, gain 0.5, so 5 m/s inward — which from due north means flying south.
        assertEquals(5.0, velocity.speedMps, 0.001)
        assertEquals(180.0, velocity.directionDeg, 0.001)
    }

    @Test
    fun insideTheCircleIsPushedOutward() {
        val velocity = OrbitControl.velocity(
            northM = 0.0, eastM = 40.0, radiusM = 50.0,
            tangentialMps = 0.0, clockwise = true, radialGain = 0.5, maxRadialMps = 6.0
        )
        assertEquals(5.0, velocity.speedMps, 0.001)
        // Due east of the centre and too close, so the correction points further east.
        assertEquals(90.0, velocity.directionDeg, 0.001)
    }

    /** Joining from far away must be a curve, not a dive at the centre. */
    @Test
    fun radialCorrectionIsCapped() {
        val velocity = OrbitControl.velocity(
            northM = 500.0, eastM = 0.0, radiusM = 50.0,
            tangentialMps = 0.0, clockwise = true, radialGain = 0.5, maxRadialMps = 3.0
        )
        assertEquals(3.0, velocity.speedMps, 0.001)
    }

    /** Standing on the centre leaves the outward direction undefined; it must not be a NaN. */
    @Test
    fun centreDoesNotProduceNaN() {
        val velocity = OrbitControl.velocity(
            northM = 0.0, eastM = 0.0, radiusM = 50.0,
            tangentialMps = 4.0, clockwise = true, radialGain = 0.5, maxRadialMps = 3.0
        )
        assertFalse(velocity.speedMps.isNaN())
        assertFalse(velocity.directionDeg.isNaN())
    }

    /**
     * The wrap at north is what turns "orbit twice" into "stop immediately" if it is missed: the
     * step from 359 to 1 is two degrees onward, not 358 degrees backwards.
     */
    @Test
    fun angleStepWrapsAtNorth() {
        assertEquals(2.0, OrbitControl.angleStepDeg(359.0, 1.0), 0.001)
        assertEquals(-2.0, OrbitControl.angleStepDeg(1.0, 359.0), 0.001)
        assertEquals(10.0, OrbitControl.angleStepDeg(80.0, 90.0), 0.001)
    }

    /** A lap is only complete once the accumulated arc says so, in either direction. */
    @Test
    fun completionCountsAccumulatedArcAndZeroMeansForever() {
        assertFalse(OrbitControl.isComplete(traveledDegrees(359.0), 360.0))
        assertTrue(OrbitControl.isComplete(traveledDegrees(360.0), 360.0))
        // Anticlockwise accumulates negative arc, and is just as complete.
        assertTrue(OrbitControl.isComplete(traveledDegrees(-361.0), 360.0))
        // Zero requested means orbit until told otherwise, however far it has gone.
        assertFalse(OrbitControl.isComplete(traveledDegrees(100_000.0), 0.0))
    }

    private fun traveledDegrees(value: Double) = value

    @Test
    fun bearingFromCentreIsACompassBearing() {
        assertEquals(0.0, OrbitControl.bearingFromCentreDeg(10.0, 0.0), 0.001)
        assertEquals(90.0, OrbitControl.bearingFromCentreDeg(0.0, 10.0), 0.001)
        assertEquals(180.0, OrbitControl.bearingFromCentreDeg(-10.0, 0.0), 0.001)
        assertEquals(270.0, OrbitControl.bearingFromCentreDeg(0.0, -10.0), 0.001)
    }
}
