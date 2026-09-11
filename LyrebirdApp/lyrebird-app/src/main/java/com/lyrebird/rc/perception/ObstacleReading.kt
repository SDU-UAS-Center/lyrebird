package com.lyrebird.rc.perception

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * One sweep of the aircraft's obstacle sensors, in metres, with no DJI types.
 *
 * The SDK reports horizontal range as a list of equal angular sectors around the aircraft in
 * millimetres, plus a single value up and a single value down. This mirrors that shape in the
 * units the rest of the app thinks in, so the decision logic can be exercised without a drone.
 *
 * ## What the numbers mean, and do not
 *
 * The horizontal list is body-referenced: sector zero is centred on the nose and the index
 * advances clockwise, so a sector's bearing is `index * angleIntervalDeg` relative to the nose,
 * not to north. Every airframe has blind arcs where it simply cannot see, and a sector inside one
 * reports the same "nothing there" as a sector with genuinely clear air. Nothing here can tell
 * those apart, which is the single most important limitation of anything built on this data.
 *
 * A reading of zero is treated as absent rather than as an obstacle in contact with the aircraft.
 * An obstacle at literally zero range is not a physical measurement, it is a sensor with nothing
 * to say, and reading it literally would brake the aircraft every time a sector dropped out.
 */
internal data class ObstacleReading(
    /** Range per sector, metres, in body frame from the nose clockwise. NaN where unknown. */
    val horizontalM: List<Double>,
    /** Degrees covered by each horizontal sector. */
    val angleIntervalDeg: Double,
    /** Range above the aircraft in metres, or NaN when unknown. */
    val upwardM: Double,
    /** Range below the aircraft in metres, or NaN when unknown. */
    val downwardM: Double,
    /** When this sweep was produced, on the device's monotonic clock. */
    val timestampMs: Long
) {

    val hasHorizontalData: Boolean get() = horizontalM.any { it.isFinite() }

    /**
     * The closest range within [halfAngleDeg] either side of [bearingDeg], measured from the nose.
     *
     * Returns NaN when every sector in the arc is unknown, which a caller must treat as "no
     * information" and never as "clear".
     */
    fun minimumInArc(bearingDeg: Double, halfAngleDeg: Double): Double {
        if (horizontalM.isEmpty() || angleIntervalDeg <= 0.0) return Double.NaN
        var closest = Double.NaN
        horizontalM.forEachIndexed { index, rangeM ->
            if (!rangeM.isFinite()) return@forEachIndexed
            val sectorBearing = index * angleIntervalDeg
            if (angularSeparationDeg(sectorBearing, bearingDeg) > halfAngleDeg) return@forEachIndexed
            if (closest.isNaN() || rangeM < closest) closest = rangeM
        }
        return closest
    }

    /** The closest range anywhere in the horizontal ring, or NaN when nothing is known. */
    fun minimumHorizontal(): Double =
        horizontalM.filter { it.isFinite() }.minOrNull() ?: Double.NaN

    companion object {

        /** Below this a reading is a sensor dropout rather than a measurement. */
        private const val MIN_VALID_M = 0.05

        val EMPTY = ObstacleReading(emptyList(), 0.0, Double.NaN, Double.NaN, 0L)

        /** Convert the SDK's millimetre readings, mapping absent values to NaN. */
        fun fromMillimetres(
            horizontalMm: List<Int>,
            angleIntervalDeg: Double,
            upwardMm: Int,
            downwardMm: Int,
            timestampMs: Long
        ): ObstacleReading = ObstacleReading(
            horizontalM = horizontalMm.map { toMetres(it) },
            // The SDK occasionally reports no interval; deriving it from the list length is the
            // same arithmetic the interval describes, and a ring always covers a full turn.
            angleIntervalDeg = if (angleIntervalDeg > 0.0) {
                angleIntervalDeg
            } else if (horizontalMm.isNotEmpty()) {
                FULL_TURN_DEG / horizontalMm.size
            } else {
                0.0
            },
            upwardM = toMetres(upwardMm),
            downwardM = toMetres(downwardMm),
            timestampMs = timestampMs
        )

        private fun toMetres(millimetres: Int): Double {
            val metres = millimetres / MILLIMETRES_PER_METRE
            return if (metres < MIN_VALID_M) Double.NaN else metres
        }

        private const val MILLIMETRES_PER_METRE = 1_000.0
        const val FULL_TURN_DEG = 360.0
    }
}

/** Smallest angle between two bearings, 0..180. */
internal fun angularSeparationDeg(first: Double, second: Double): Double {
    val delta = abs(normalizeDeg(first) - normalizeDeg(second))
    return if (delta > 180.0) 360.0 - delta else delta
}

internal fun normalizeDeg(value: Double): Double {
    val wrapped = value % 360.0
    return if (wrapped < 0) wrapped + 360.0 else wrapped
}

/**
 * Which way the aircraft is actually moving, relative to its own nose.
 *
 * This is the whole reason the guard is not simply "is anything close". A multirotor under
 * Lyrebird's hold-heading waypoint controller strafes sideways and backwards with the nose fixed,
 * so the obstacle that matters is the one in the direction of travel, which may be anywhere around
 * the aircraft. Braking for a tree the aircraft is flying away from is the fastest way to get the
 * feature switched off.
 *
 * Returns null below [MIN_GROUND_SPEED_MPS], where the course over ground is noise.
 */
internal fun travelBearingFromNoseDeg(
    velocityNorthMps: Double,
    velocityEastMps: Double,
    headingDeg: Double
): Double? {
    val groundSpeed = hypot(velocityNorthMps, velocityEastMps)
    if (groundSpeed < MIN_GROUND_SPEED_MPS) return null
    val courseDeg = Math.toDegrees(atan2(velocityEastMps, velocityNorthMps))
    return normalizeDeg(courseDeg - headingDeg)
}

/** The sector index a bearing falls in, for logging a brake against something an operator can picture. */
internal fun sectorIndexFor(bearingDeg: Double, angleIntervalDeg: Double, sectorCount: Int): Int {
    if (angleIntervalDeg <= 0.0 || sectorCount <= 0) return 0
    return ((normalizeDeg(bearingDeg) / angleIntervalDeg).roundToInt()) % sectorCount
}

/** Under this the aircraft is hovering and has no meaningful direction of travel. */
internal const val MIN_GROUND_SPEED_MPS = 0.4
