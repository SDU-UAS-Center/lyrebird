package com.lyrebird.rc.fleet

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * How urgently the pilot should look at a particular peer.
 *
 * The scale stops at [WARNING] on purpose. Lyrebird never commands an avoidance manoeuvre and
 * never refuses a pilot's input on the strength of a beacon that arrived unauthenticated over
 * Wi-Fi. Everything this file produces is something a human reads.
 */
internal enum class AdvisoryLevel {
    /** Nothing worth drawing attention to. */
    NONE,

    /** Another aircraft is airborne nearby. Situational awareness, not a hazard. */
    ADVISORY,

    /** Converging, or close. Worth watching. */
    CAUTION,

    /** Converging fast, or very close. Worth acting on. */
    WARNING;

    fun atLeast(other: AdvisoryLevel): Boolean = ordinal >= other.ordinal
}

/**
 * The geometry between the aircraft this device flies and one peer.
 *
 * Separation is carried both as it is now and as it will be at closest approach, because those
 * answer different questions: the first is what the pilot sees out of the window, the second is
 * whether the current tracks end badly. A pair 400 metres apart and closing at 25 m/s deserves
 * more attention than a pair 80 metres apart holding station, and only the projection says so.
 */
internal data class TrafficSolution(
    val horizontalDistanceM: Double,
    /** Peer altitude minus own altitude, signed: positive means the peer is above. */
    val verticalSeparationM: Double,
    val slantRangeM: Double,
    val bearingDeg: Double,
    /** Positive while the gap is shrinking. */
    val closingSpeedMps: Double,
    /** Seconds to closest approach, or null when the tracks are diverging. */
    val timeToClosestApproachS: Double?,
    /** Horizontal separation at closest approach, or null when the tracks are diverging. */
    val closestApproachHorizontalM: Double?,
    /** Vertical separation at closest approach, or null when the tracks are diverging. */
    val closestApproachVerticalM: Double?,
    val level: AdvisoryLevel
)

/**
 * Closest-point-of-approach advisory between the local aircraft and one peer.
 *
 * Today every Lyrebird aircraft is blind to every other one unless a ground station is up and
 * someone is watching its map. This restores the most basic thing a pilot of a manned aircraft
 * has: knowing who else is in the air, how far away, how high, and whether the gap is closing.
 *
 * The thresholds below are separation minima, not predictions of a collision. They are chosen so
 * that a warning means "these two are on tracks that bring them inside the distance at which a
 * GPS fix and a one-second beacon can still tell them apart", which is a much weaker claim than
 * "these two will hit". Both aircraft carry a GPS error of a few metres, the beacon is half a
 * second old by the time it is read, and neither track accounts for what either pilot does next.
 */
internal object TrafficAdvisory {

    /** Inside this slant range an airborne peer is a warning whatever the tracks are doing. */
    const val WARNING_RANGE_M = 35.0

    /** Inside this slant range an airborne peer is a caution whatever the tracks are doing. */
    const val CAUTION_RANGE_M = 90.0

    /** An airborne peer closer than this is worth showing, even with no convergence at all. */
    const val ADVISORY_RANGE_M = 400.0

    const val WARNING_CPA_HORIZONTAL_M = 30.0
    const val WARNING_CPA_VERTICAL_M = 15.0
    const val WARNING_CPA_SECONDS = 25.0

    const val CAUTION_CPA_HORIZONTAL_M = 70.0
    const val CAUTION_CPA_VERTICAL_M = 30.0
    const val CAUTION_CPA_SECONDS = 60.0

    /**
     * Geometry and alert level for one peer, or null when there is nothing truthful to compute.
     *
     * Returns null rather than a zeroed solution when either aircraft has no GPS fix: a peer with
     * no position is a peer of unknown range, and showing it at zero metres would be the most
     * dangerous possible way to say "unknown".
     */
    @Suppress("LongParameterList")
    fun solve(
        ownLatitudeDeg: Double,
        ownLongitudeDeg: Double,
        ownAltitudeAslM: Double,
        ownVelocityNorthMps: Double,
        ownVelocityEastMps: Double,
        ownVelocityDownMps: Double,
        ownFlying: Boolean,
        peer: FleetBeacon
    ): TrafficSolution? {
        if (!FleetGeo.isRealPosition(ownLatitudeDeg, ownLongitudeDeg)) return null
        if (!peer.hasRealPosition()) return null

        val (relativeEastM, relativeNorthM) = FleetGeo.eastNorthOffsetM(
            ownLatitudeDeg, ownLongitudeDeg, peer.latitudeDeg, peer.longitudeDeg
        )
        val relativeUpM = peer.altitudeAslM - ownAltitudeAslM

        // Up-positive throughout: both sides report down-positive on the wire.
        val relativeEastMps = peer.velocityEastMps - ownVelocityEastMps
        val relativeNorthMps = peer.velocityNorthMps - ownVelocityNorthMps
        val relativeUpMps = -(peer.velocityDownMps - ownVelocityDownMps)

        val horizontalDistanceM = hypot(relativeEastM, relativeNorthM)
        val slantRangeM = sqrt(
            relativeEastM * relativeEastM + relativeNorthM * relativeNorthM + relativeUpM * relativeUpM
        )

        val timeToCpaS = FleetGeo.timeToClosestApproachS(
            relativeEastM, relativeNorthM, relativeUpM,
            relativeEastMps, relativeNorthMps, relativeUpMps
        )
        val cpaHorizontalM = timeToCpaS?.let {
            hypot(relativeEastM + relativeEastMps * it, relativeNorthM + relativeNorthMps * it)
        }
        val cpaVerticalM = timeToCpaS?.let { abs(relativeUpM + relativeUpMps * it) }

        return TrafficSolution(
            horizontalDistanceM = horizontalDistanceM,
            verticalSeparationM = relativeUpM,
            slantRangeM = slantRangeM,
            bearingDeg = FleetGeo.bearingDeg(
                ownLatitudeDeg, ownLongitudeDeg, peer.latitudeDeg, peer.longitudeDeg
            ),
            closingSpeedMps = FleetGeo.closingSpeedMps(
                relativeEastM, relativeNorthM, relativeUpM,
                relativeEastMps, relativeNorthMps, relativeUpMps
            ),
            timeToClosestApproachS = timeToCpaS,
            closestApproachHorizontalM = cpaHorizontalM,
            closestApproachVerticalM = cpaVerticalM,
            level = level(
                airborne = ownFlying || peer.flying,
                slantRangeM = slantRangeM,
                timeToCpaS = timeToCpaS,
                cpaHorizontalM = cpaHorizontalM,
                cpaVerticalM = cpaVerticalM
            )
        )
    }

    /**
     * Grade one solution.
     *
     * Nothing is raised above [AdvisoryLevel.NONE] unless at least one of the pair is airborne.
     * Two aircraft parked five metres apart on the same launch mat are the normal way a field day
     * starts, and an app that shouts about it gets ignored by the time it matters.
     */
    private fun level(
        airborne: Boolean,
        slantRangeM: Double,
        timeToCpaS: Double?,
        cpaHorizontalM: Double?,
        cpaVerticalM: Double?
    ): AdvisoryLevel {
        if (!airborne) return AdvisoryLevel.NONE

        val convergingWarning = timeToCpaS != null && cpaHorizontalM != null && cpaVerticalM != null &&
            timeToCpaS <= WARNING_CPA_SECONDS &&
            cpaHorizontalM <= WARNING_CPA_HORIZONTAL_M &&
            cpaVerticalM <= WARNING_CPA_VERTICAL_M
        if (slantRangeM <= WARNING_RANGE_M || convergingWarning) return AdvisoryLevel.WARNING

        val convergingCaution = timeToCpaS != null && cpaHorizontalM != null && cpaVerticalM != null &&
            timeToCpaS <= CAUTION_CPA_SECONDS &&
            cpaHorizontalM <= CAUTION_CPA_HORIZONTAL_M &&
            cpaVerticalM <= CAUTION_CPA_VERTICAL_M
        if (slantRangeM <= CAUTION_RANGE_M || convergingCaution) return AdvisoryLevel.CAUTION

        return if (slantRangeM <= ADVISORY_RANGE_M) AdvisoryLevel.ADVISORY else AdvisoryLevel.NONE
    }
}
