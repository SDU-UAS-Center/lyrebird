package com.lyrebird.rc.perception

import kotlin.math.abs
import kotlin.math.hypot

/** Why the guard did or did not stop the aircraft, in terms an operator could be shown. */
internal enum class BrakeReason {
    /** Nothing in the way, or nothing the guard is entitled to act on. */
    NONE,

    /** Something is inside the stopping distance along the current track. */
    HORIZONTAL,

    /** Something is above and the aircraft is climbing toward it. */
    UPWARD,

    /** Something is below and the aircraft is descending toward it. */
    DOWNWARD
}

/**
 * The guard's verdict for one sweep.
 *
 * [clearanceM] is what was actually measured and [requiredM] is what the current speed needs, so a
 * log line or a status readout can say "3 m at 6 m/s, needed 13" rather than just "braked".
 */
internal data class BrakeDecision(
    val shouldBrake: Boolean,
    val reason: BrakeReason,
    val clearanceM: Double,
    val requiredM: Double,
    /** Bearing of the hazard from the nose, degrees. NaN for the vertical cases. */
    val bearingFromNoseDeg: Double
) {
    companion object {
        val CLEAR = BrakeDecision(false, BrakeReason.NONE, Double.NaN, 0.0, Double.NaN)
    }
}

/**
 * Decides whether an approaching obstacle should stop Lyrebird's autonomous motion.
 *
 * ## What this is, and what it is not
 *
 * This is a backstop for the one case DJI's own obstacle avoidance does not cover. When Lyrebird
 * flies its own PID sequencer through virtual stick, the aircraft is taking stick input from an
 * app, and the vendor's braking behaviour around that is neither documented nor guaranteed. The
 * guard closes that gap by stopping Lyrebird's own motion, which it unambiguously controls.
 *
 * It is deliberately not an avoidance system. It never steers, never picks a way around anything,
 * and never overrides a human on the sticks. It has exactly one action: stop commanding movement
 * and let the aircraft hold position. That is the only action that is safe to take from an app on
 * a phone, on data whose blind arcs it cannot see into.
 *
 * ## Why the threshold moves with speed
 *
 * A fixed radius is wrong at both ends: 5 m is paranoid at walking pace and far too late at 10
 * m/s. The required clearance is the distance the aircraft covers before it can stop — the
 * latency between the sensor and a stick command, plus the braking distance at the current speed —
 * with a fixed margin on top. At high speed this can exceed the sensor's own range, which is not a
 * flaw in the arithmetic but a real statement about the aircraft: past a certain speed it cannot
 * see far enough ahead to stop, and [canStopWithinSensorRange] says so.
 */
internal object ObstacleBrake {

    /** Sensor sweep to stick command: listener hop, decision, control loop tick. */
    const val REACTION_TIME_S = 0.5

    /** Conservative multirotor braking deceleration. Below what the airframe can do. */
    const val BRAKING_DECELERATION_MPS2 = 2.5

    /** Standoff kept after the aircraft has stopped. */
    const val DEFAULT_MARGIN_M = 3.0

    /** Half-angle of the arc searched around the direction of travel. */
    const val TRAVEL_ARC_HALF_ANGLE_DEG = 35.0

    /**
     * Half-angle used when the aircraft is too slow to have a course.
     *
     * A hovering aircraft has no track to search along, but it may be about to be commanded
     * somewhere, so the guard keeps watching the whole ring at the margin distance only.
     */
    const val HOVER_ARC_HALF_ANGLE_DEG = 180.0

    /** Vertical speed above which a climb or descent is deliberate rather than drift. */
    const val MIN_VERTICAL_SPEED_MPS = 0.3

    /**
     * Clearance the aircraft needs at [speedMps] before it can stop, in metres.
     *
     * Reaction distance plus braking distance plus the standoff. The braking term is the standard
     * v²/2a; it dominates above a few metres per second, which is why the result is not linear.
     */
    fun requiredClearanceM(speedMps: Double, marginM: Double = DEFAULT_MARGIN_M): Double {
        val speed = speedMps.coerceAtLeast(0.0)
        val reactionM = speed * REACTION_TIME_S
        val brakingM = speed * speed / (2.0 * BRAKING_DECELERATION_MPS2)
        return marginM + reactionM + brakingM
    }

    /**
     * Whether the aircraft could stop inside what its sensors can see at this speed.
     *
     * False means the guard is flying blind: it will still brake on whatever it detects, but it
     * cannot promise the detection comes early enough. Worth surfacing, never worth hiding.
     */
    fun canStopWithinSensorRange(
        speedMps: Double,
        sensorRangeM: Double,
        marginM: Double = DEFAULT_MARGIN_M
    ): Boolean = requiredClearanceM(speedMps, marginM) <= sensorRangeM

    /**
     * Evaluate one sweep.
     *
     * [autonomousMotionActive] gates everything. The guard acts only on motion Lyrebird itself is
     * commanding: a human on the sticks is flying with the vendor's own avoidance active and must
     * never have an app countermand them, and a DJI-native wayline is being flown by the flight
     * controller, which has its own braking and which cancelling a stick loop would not stop.
     */
    @Suppress("LongParameterList", "ReturnCount")
    fun evaluate(
        reading: ObstacleReading,
        velocityNorthMps: Double,
        velocityEastMps: Double,
        velocityDownMps: Double,
        headingDeg: Double,
        autonomousMotionActive: Boolean,
        marginM: Double = DEFAULT_MARGIN_M
    ): BrakeDecision {
        if (!autonomousMotionActive) return BrakeDecision.CLEAR

        verticalDecision(reading, velocityDownMps, marginM)?.let { return it }

        if (!reading.hasHorizontalData) return BrakeDecision.CLEAR
        val groundSpeed = hypot(velocityNorthMps, velocityEastMps)
        val travelBearing = travelBearingFromNoseDeg(velocityNorthMps, velocityEastMps, headingDeg)

        // Hovering: no track to search along, so watch the whole ring but only at the standoff.
        // This is what catches an aircraft that has been commanded to a point it cannot safely
        // start toward, rather than one already moving at something.
        val (searchBearing, halfAngle, required) = if (travelBearing == null) {
            Triple(0.0, HOVER_ARC_HALF_ANGLE_DEG, marginM)
        } else {
            Triple(travelBearing, TRAVEL_ARC_HALF_ANGLE_DEG, requiredClearanceM(groundSpeed, marginM))
        }

        val clearance = reading.minimumInArc(searchBearing, halfAngle)
        // NaN is "no information", never "clear" and never "obstacle". Braking on an absent
        // reading would stop the aircraft every time a sector dropped out.
        if (!clearance.isFinite() || clearance > required) return BrakeDecision.CLEAR

        return BrakeDecision(
            shouldBrake = true,
            reason = BrakeReason.HORIZONTAL,
            clearanceM = clearance,
            requiredM = required,
            bearingFromNoseDeg = searchBearing
        )
    }

    /**
     * Up and down are checked against vertical speed alone.
     *
     * The downward sensor is looking at the ground for most of a flight, so it can only be a
     * hazard while descending onto it, and a landing is a descent onto it on purpose — which is
     * why the guard never runs during one. Upward is the case that actually bites: a climb under
     * a branch or a roof edge that nothing else in the stack is watching for.
     */
    private fun verticalDecision(
        reading: ObstacleReading,
        velocityDownMps: Double,
        marginM: Double
    ): BrakeDecision? {
        val climbRate = -velocityDownMps
        if (climbRate > MIN_VERTICAL_SPEED_MPS && reading.upwardM.isFinite()) {
            val required = requiredClearanceM(abs(climbRate), marginM)
            if (reading.upwardM <= required) {
                return BrakeDecision(
                    shouldBrake = true,
                    reason = BrakeReason.UPWARD,
                    clearanceM = reading.upwardM,
                    requiredM = required,
                    bearingFromNoseDeg = Double.NaN
                )
            }
        }
        if (velocityDownMps > MIN_VERTICAL_SPEED_MPS && reading.downwardM.isFinite()) {
            val required = requiredClearanceM(velocityDownMps, marginM)
            if (reading.downwardM <= required) {
                return BrakeDecision(
                    shouldBrake = true,
                    reason = BrakeReason.DOWNWARD,
                    clearanceM = reading.downwardM,
                    requiredM = required,
                    bearingFromNoseDeg = Double.NaN
                )
            }
        }
        return null
    }
}
