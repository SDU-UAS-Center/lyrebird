package com.lyrebird.rc.controller

import org.junit.Assert.assertEquals
import org.junit.Test

class RoiControlTest {

    /** Level with the target and due north of it: look level, and turn to the target's bearing. */
    @Test
    fun looksAlongTheHorizonWhenLevelWithTheTarget() {
        val aim = RoiControl.aimAt(
            bearingToRoiDeg = 90.0, groundDistanceM = 100.0, altitudeAboveRoiM = 0.0,
            headingDeg = 90.0, aircraftPitchDeg = 0.0
        )
        assertEquals(0.0, aim.pitchDeg, 0.001)
        // Already pointing at it, so the gimbal stays centred rather than turning.
        assertEquals(0.0, aim.yawDeg, 0.001)
    }

    /** Directly overhead is the case that divides by zero if the geometry is done with atan. */
    @Test
    fun looksStraightDownFromDirectlyOverhead() {
        val aim = RoiControl.aimAt(
            bearingToRoiDeg = 0.0, groundDistanceM = 0.0, altitudeAboveRoiM = 50.0,
            headingDeg = 0.0, aircraftPitchDeg = 0.0
        )
        assertEquals(-90.0, aim.pitchDeg, 0.001)
    }

    /** Equal height and distance is the 45 degree case, and it must be down rather than up. */
    @Test
    fun depressionIsNegativeLookingDown() {
        val aim = RoiControl.aimAt(
            bearingToRoiDeg = 0.0, groundDistanceM = 100.0, altitudeAboveRoiM = 100.0,
            headingDeg = 0.0, aircraftPitchDeg = 0.0
        )
        assertEquals(-45.0, aim.pitchDeg, 0.001)
    }

    /** A target above the aircraft — on a hillside, or a nest in a tree from below. */
    @Test
    fun elevationIsPositiveLookingUp() {
        val aim = RoiControl.aimAt(
            bearingToRoiDeg = 0.0, groundDistanceM = 100.0, altitudeAboveRoiM = -100.0,
            headingDeg = 0.0, aircraftPitchDeg = 0.0
        )
        assertEquals(45.0, aim.pitchDeg, 0.001)
    }

    /**
     * The yaw is in the joint frame, so it is the bearing *relative to the nose*. Getting this
     * wrong points the camera at the target's bearing as if the aircraft were facing north.
     */
    @Test
    fun yawIsRelativeToTheNoseAndTakesTheShortWayRound() {
        val behind = RoiControl.aimAt(
            bearingToRoiDeg = 0.0, groundDistanceM = 100.0, altitudeAboveRoiM = 0.0,
            headingDeg = 90.0, aircraftPitchDeg = 0.0
        )
        assertEquals(-90.0, behind.yawDeg, 0.001)

        // 350 degrees from a nose on 10 would be 340 the long way; it must be -20.
        val wrapped = RoiControl.aimAt(
            bearingToRoiDeg = 350.0, groundDistanceM = 100.0, altitudeAboveRoiM = 0.0,
            headingDeg = 10.0, aircraftPitchDeg = 0.0
        )
        assertEquals(-20.0, wrapped.yawDeg, 0.001)
    }

    /** The gimbal hangs off the airframe, so the aircraft's own pitch is part of the answer. */
    @Test
    fun aircraftPitchIsSubtractedFromTheJointAngle() {
        val aim = RoiControl.aimAt(
            bearingToRoiDeg = 0.0, groundDistanceM = 100.0, altitudeAboveRoiM = 100.0,
            headingDeg = 0.0, aircraftPitchDeg = -10.0
        )
        assertEquals(-35.0, aim.pitchDeg, 0.001)
    }

    @Test
    fun stepIsDeadBandedAndRateLimited() {
        assertEquals(0.0, RoiControl.step(0.2, deadbandDeg = 0.5, maxStepDeg = 10.0), 0.001)
        assertEquals(3.0, RoiControl.step(3.0, deadbandDeg = 0.5, maxStepDeg = 10.0), 0.001)
        assertEquals(10.0, RoiControl.step(90.0, deadbandDeg = 0.5, maxStepDeg = 10.0), 0.001)
        assertEquals(-10.0, RoiControl.step(-90.0, deadbandDeg = 0.5, maxStepDeg = 10.0), 0.001)
    }
}
