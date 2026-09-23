package com.lyrebird.rc.perception

import kotlin.math.abs

/**
 * Which directions after a brake stay closed, and for how long.
 *
 * A brake stops the aircraft; it does not tell anyone flying it later where the obstacle was.
 * Without that memory the cycle is: autonomous leg is stopped 5 m from a tree, the latch expires,
 * the same waypoint is re-issued from the ground station's retry loop, and the aircraft flies
 * straight back into the thing it just braked for. This class is the answer to "the aircraft did
 * stop — now what is it allowed to start toward".
 *
 * The shape is a bearing arc centred on where the obstacle was measured, held for a lockout that
 * outlives the guard's brake latch. The arc, not a single bearing, is deliberate: the vehicle
 * baselines the leg from its current position, and a retry issued from a drifting hover can name a
 * waypoint several degrees off the original track even when the operator meant the same leg.
 *
 * Decide-and-expiry is pure here so it can be tested without a drone, exactly like
 * [ObstacleBrake]. Consumers (the guard records, the controller refuses) stay thin.
 */
internal data class BlockedArc(
    /** Bearing from the nose of the measured obstacle, degrees body frame. */
    val bearingFromNoseDeg: Double,
    /** Half-width of the blocked arc, degrees. */
    val halfAngleDeg: Double,
    /** Wall-clock ms, from [android.os.SystemClock.elapsedRealtime], when the arc opens. */
    val expiresAtElapsedMs: Long,
) {
    /** True when [atElapsedMs] is before the lockout ended; a stale arc blocks nothing. */
    fun isActive(atElapsedMs: Long): Boolean = atElapsedMs < expiresAtElapsedMs

    /**
     * Whether a leg bearing falls inside the blocked arc.
     *
     * [legBearingDeg] is body frame from the nose, the same frame every other bearing here uses —
     * compass bearings must be reduced to body frame by the caller before asking.
     */
    fun contains(
        legBearingDeg: Double,
        atElapsedMs: Long,
    ): Boolean =
        isActive(atElapsedMs) &&
            abs(angularSeparationDeg(legBearingDeg, bearingFromNoseDeg)) <= halfAngleDeg

    companion object {
        /**
         * How wide the arc is around the measured obstacle bearing.
         *
         * The obstacle was measured in one sensor sector, which the ring places at
         * [ObstacleReading.angleIntervalDeg] steps — about 5 degrees on the common ring. The arc
         * adds a half-sector either side for the measurement being a point sample of something
         * wider than itself, and a fixed few degrees for the aircraft having drifted or turned a
         * little between the brake and the next command.
         */
        const val HALF_ANGLE_DEG = 15.0

        /**
         * How long after a brake a leg toward the obstacle stays refused.
         *
         * Deliberately much longer than [ObstacleGuard]'s brake latch. The latch exists to stop
         * the guard re-firing on its own sweep while the aircraft hovers in front of what it just
         * braked for; five seconds of that is enough. This lockout exists so a retry loop on the
         * ground station does not fly the leg again the moment the latch clears, and a retry
         * loop's resend cadence is measured in seconds — the lockout has to be the thing a human
         * outruns, not the retry timer.
         */
        const val LOCKOUT_MS = 30_000L

        /**
         * The arc a brake puts in place, centred on where the obstacle was measured rather than
         * on the arc centre [BrakeDecision] reports for horizontal hazards.
         *
         * Null for the vertical reasons (upward/downward): a climb blocked overhead says nothing
         * about any horizontal leg, and there is no yaw-bearing geometry to refuse reliably.
         */
        fun fromBrake(
            decision: BrakeDecision,
            nowElapsedMs: Long,
        ): BlockedArc? {
            if (!decision.shouldBrake || decision.reason != BrakeReason.HORIZONTAL) return null
            if (!decision.bearingFromNoseDeg.isFinite()) return null
            return BlockedArc(
                bearingFromNoseDeg = decision.bearingFromNoseDeg,
                halfAngleDeg = HALF_ANGLE_DEG,
                expiresAtElapsedMs = nowElapsedMs + LOCKOUT_MS,
            )
        }
    }
}
