package com.lyrebird.rc.controller

import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Where the gimbal must point to keep one ground position in frame.
 *
 * Split out from the loop that drives it so the geometry can be tested without an aircraft. That
 * matters more here than the size of the file suggests: every frame error in this project so far
 * has been a plausible-looking angle in the wrong reference, and an angle is exactly the kind of
 * value that looks reasonable while being wrong by a sign or by the aircraft's heading.
 */
internal object RoiControl {

    /** A gimbal aim expressed in the joint frame — angles relative to the aircraft body. */
    data class Aim(val pitchDeg: Double, val yawDeg: Double)

    /**
     * The joint angles that point the camera at a fixed position on the ground.
     *
     * Deliberately returned in the **joint** frame rather than the world frame, because that is
     * the one DJI reports without ambiguity: `KeyGimbalJointAttitude` is defined relative to the
     * aircraft body, whereas whether an absolute gimbal command is referenced to north or to the
     * aircraft heading is exactly the sort of question this project has twice had to settle by
     * flying rather than by reading. Comparing a desired joint angle against a measured joint
     * angle removes the question: both are in the same frame by construction, and the difference
     * is a rotation the gimbal can be asked to make.
     *
     * @param headingDeg the aircraft's compass heading, degrees.
     * @param aircraftPitchDeg the aircraft's own pitch. Small in a hover and not small while
     *   translating, and the gimbal hangs off the airframe either way.
     * @param altitudeAboveRoiM how far the aircraft is above the point. Negative looks upward,
     *   which is a legal thing to ask of a gimbal that can do it and clamps at its limit if not.
     */
    fun aimAt(
        bearingToRoiDeg: Double,
        groundDistanceM: Double,
        altitudeAboveRoiM: Double,
        headingDeg: Double,
        aircraftPitchDeg: Double
    ): Aim {
        // Depression below the horizon, from the right triangle the aircraft and the point make.
        // atan2 rather than atan so that standing directly over the point gives -90 rather than a
        // division by zero, and so the sign survives an aircraft below its target.
        val depressionDeg = Math.toDegrees(atan2(altitudeAboveRoiM, groundDistanceM))
        return Aim(
            // DJI's gimbal pitch is zero at the horizon and negative looking down.
            pitchDeg = -depressionDeg - aircraftPitchDeg,
            yawDeg = normalizeAngle(bearingToRoiDeg - headingDeg)
        )
    }

    /** Horizontal distance and height difference, in the flat-earth approximation that is right at these ranges. */
    fun groundDistanceM(northM: Double, eastM: Double): Double = hypot(northM, eastM)

    /**
     * The rotation to ask of the gimbal this tick: the error, limited.
     *
     * Rate-limited because a relative rotation is executed as fast as the gimbal can move it, and
     * a large step is a slew that whips the picture. Dead-banded because the measured attitude is
     * noisy and a gimbal asked to correct a tenth of a degree ten times a second hunts audibly.
     */
    fun step(errorDeg: Double, deadbandDeg: Double, maxStepDeg: Double): Double =
        if (kotlin.math.abs(errorDeg) < deadbandDeg) 0.0
        else errorDeg.coerceIn(-maxStepDeg, maxStepDeg)

    /** Wrap to -180..180 so an error never takes the long way round. */
    fun normalizeAngle(angle: Double): Double {
        var result = angle % 360.0
        if (result > 180.0) result -= 360.0
        if (result < -180.0) result += 360.0
        return result
    }
}
