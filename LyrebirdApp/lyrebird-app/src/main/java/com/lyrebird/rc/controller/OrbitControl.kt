package com.lyrebird.rc.controller

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The velocity that carries an aircraft around a circle, and the bookkeeping that says when it has
 * gone far enough.
 *
 * Pure geometry, separate from the loop that flies it, so the circle can be tested on the ground.
 * A circle is not obviously harder than a straight line, but it has two failure modes a waypoint
 * does not: the direction of travel is a choice that reverses on a sign, and the angle travelled
 * accumulates, so a wrap at north either counts a lap or loses one.
 */
internal object OrbitControl {

    /** A commanded velocity as a speed and the compass direction it points. */
    data class Velocity(val speedMps: Double, val directionDeg: Double)

    /**
     * The velocity to fly this tick.
     *
     * Two components: around the circle at the requested speed, and in or out to correct the
     * radius. The correction is proportional and capped, so joining an orbit from outside it is a
     * curve rather than a dash at the centre.
     *
     * @param northM,[eastM] the aircraft's offset from the centre, in metres.
     * @param radiusM the requested radius, always positive here; direction is [clockwise].
     * @param tangentialMps how fast to travel around. Positive.
     */
    fun velocity(
        northM: Double,
        eastM: Double,
        radiusM: Double,
        tangentialMps: Double,
        clockwise: Boolean,
        radialGain: Double,
        maxRadialMps: Double
    ): Velocity {
        val currentRadius = hypot(northM, eastM)
        // Standing exactly on the centre leaves the outward direction undefined, so any direction
        // is as good as another and north is the one that does not produce a NaN.
        val outwardDeg = if (currentRadius < 1e-6) 0.0 else Math.toDegrees(atan2(eastM, northM))

        // Positive means "too far out", so the correction points inward.
        val radialError = currentRadius - radiusM
        val radialMps = (-radialGain * radialError).coerceIn(-maxRadialMps, maxRadialMps)

        // Travelling clockwise seen from above means the bearing from the centre increases, and
        // the velocity is a quarter turn ahead of the outward direction.
        val tangentDeg = if (clockwise) outwardDeg + 90.0 else outwardDeg - 90.0

        // Compose the two in the north/east plane and read the result back as speed and bearing.
        val north = radialMps * cos(Math.toRadians(outwardDeg)) +
            tangentialMps * cos(Math.toRadians(tangentDeg))
        val east = radialMps * sin(Math.toRadians(outwardDeg)) +
            tangentialMps * sin(Math.toRadians(tangentDeg))
        return Velocity(
            speedMps = hypot(north, east),
            directionDeg = normalizeCompass(Math.toDegrees(atan2(east, north)))
        )
    }

    /**
     * How far around the circle the aircraft has moved since the last tick, in degrees, signed by
     * the direction of travel.
     *
     * Accumulated rather than measured against the start, because the aircraft passes the start
     * angle once per lap and an absolute comparison cannot tell a completed lap from a fresh one.
     * The step is wrapped so crossing north counts as a small move rather than as 359 degrees
     * backwards, which is the arithmetic that turns "orbit twice" into "stop immediately".
     */
    fun angleStepDeg(previousDeg: Double, currentDeg: Double): Double {
        var step = currentDeg - previousDeg
        if (step > 180.0) step -= 360.0
        if (step < -180.0) step += 360.0
        return step
    }

    /** The bearing from the centre out to the aircraft, 0..360. */
    fun bearingFromCentreDeg(northM: Double, eastM: Double): Double =
        if (hypot(northM, eastM) < 1e-6) 0.0
        else normalizeCompass(Math.toDegrees(atan2(eastM, northM)))

    /** Has the aircraft travelled the arc it was asked for? A request of zero means "forever". */
    fun isComplete(travelledDeg: Double, requestedDeg: Double): Boolean =
        requestedDeg > 0.0 && abs(travelledDeg) >= requestedDeg

    private fun normalizeCompass(deg: Double): Double = (deg + 360.0) % 360.0
}
