package com.lyrebird.rc.fleet

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot

/**
 * Local-tangent-plane geometry for the fleet mesh.
 *
 * Every distance the mesh reports is between two aircraft that share a Wi-Fi access point, so the
 * pair is at most a few kilometres apart and an equirectangular projection around the observer is
 * accurate to well under a metre — far finer than the GPS fixes being compared. Haversine would
 * be no more truthful here and costs a transcendental per peer per beacon, on a device that is
 * simultaneously encoding video.
 *
 * East/north/up metres, the frame the advisory arithmetic wants, with up positive. DJI reports
 * velocity as north, east and down, so callers negate the down component on the way in.
 */
internal object FleetGeo {

    /** IUGG mean Earth radius. */
    const val EARTH_RADIUS_M = 6_371_008.8

    /** Below this the relative-velocity vector is noise and closest approach is undefined. */
    private const val MIN_RELATIVE_SPEED_MPS = 0.2

    /**
     * Whether a latitude/longitude pair is a real place.
     *
     * The DJI SDK reports an unset location as exactly (0, 0), which is a valid coordinate in the
     * Gulf of Guinea. Treating it as one would put every not-yet-fixed aircraft at the same point
     * and produce a collision warning between two drones sitting on the ground, so null island is
     * rejected here as it is elsewhere in the app.
     */
    fun isRealPosition(latitudeDeg: Double, longitudeDeg: Double): Boolean =
        latitudeDeg.isFinite() && longitudeDeg.isFinite() &&
            (latitudeDeg != 0.0 || longitudeDeg != 0.0) &&
            latitudeDeg in -90.0..90.0 && longitudeDeg in -180.0..180.0

    /** East and north metres of the target relative to the origin. */
    fun eastNorthOffsetM(
        originLatitudeDeg: Double,
        originLongitudeDeg: Double,
        targetLatitudeDeg: Double,
        targetLongitudeDeg: Double
    ): Pair<Double, Double> {
        val originLatRad = Math.toRadians(originLatitudeDeg)
        val east = Math.toRadians(normalizeLongitudeDelta(targetLongitudeDeg - originLongitudeDeg)) *
            EARTH_RADIUS_M * cos(originLatRad)
        val north = Math.toRadians(targetLatitudeDeg - originLatitudeDeg) * EARTH_RADIUS_M
        return east to north
    }

    /** Great-circle-equivalent ground distance in metres between two nearby points. */
    fun horizontalDistanceM(
        originLatitudeDeg: Double,
        originLongitudeDeg: Double,
        targetLatitudeDeg: Double,
        targetLongitudeDeg: Double
    ): Double {
        val (east, north) = eastNorthOffsetM(
            originLatitudeDeg, originLongitudeDeg, targetLatitudeDeg, targetLongitudeDeg
        )
        return hypot(east, north)
    }

    /** Compass bearing in degrees from the origin to the target, 0 at true north. */
    fun bearingDeg(
        originLatitudeDeg: Double,
        originLongitudeDeg: Double,
        targetLatitudeDeg: Double,
        targetLongitudeDeg: Double
    ): Double {
        val (east, north) = eastNorthOffsetM(
            originLatitudeDeg, originLongitudeDeg, targetLatitudeDeg, targetLongitudeDeg
        )
        if (east == 0.0 && north == 0.0) return 0.0
        return normalizeBearingDeg(Math.toDegrees(atan2(east, north)))
    }

    fun normalizeBearingDeg(bearingDeg: Double): Double {
        val wrapped = bearingDeg % 360.0
        return if (wrapped < 0) wrapped + 360.0 else wrapped
    }

    /**
     * Seconds until the two tracks are closest, or null when they are not converging.
     *
     * Standard closest-point-of-approach: with the relative position vector r and the relative
     * velocity vector v, the separation is minimised at t = -(r·v)/(v·v). A non-positive result
     * means the pair is already past its closest point and drawing apart, which is not a hazard
     * and is reported as null rather than as a time in the past.
     */
    fun timeToClosestApproachS(
        relativeEastM: Double,
        relativeNorthM: Double,
        relativeUpM: Double,
        relativeEastMps: Double,
        relativeNorthMps: Double,
        relativeUpMps: Double
    ): Double? {
        val speedSquared = relativeEastMps * relativeEastMps +
            relativeNorthMps * relativeNorthMps +
            relativeUpMps * relativeUpMps
        if (speedSquared < MIN_RELATIVE_SPEED_MPS * MIN_RELATIVE_SPEED_MPS) return null
        val dot = relativeEastM * relativeEastMps +
            relativeNorthM * relativeNorthMps +
            relativeUpM * relativeUpMps
        val timeS = -dot / speedSquared
        return if (timeS > 0.0) timeS else null
    }

    /** Rate of closure in metres per second: positive while the gap is shrinking. */
    fun closingSpeedMps(
        relativeEastM: Double,
        relativeNorthM: Double,
        relativeUpM: Double,
        relativeEastMps: Double,
        relativeNorthMps: Double,
        relativeUpMps: Double
    ): Double {
        val range = kotlin.math.sqrt(
            relativeEastM * relativeEastM +
                relativeNorthM * relativeNorthM +
                relativeUpM * relativeUpM
        )
        if (range <= 0.0) return 0.0
        val dot = relativeEastM * relativeEastMps +
            relativeNorthM * relativeNorthMps +
            relativeUpM * relativeUpMps
        return -dot / range
    }

    /** Longitude differences wrap at the antimeridian; without this a pair astride it reads as half a world apart. */
    private fun normalizeLongitudeDelta(deltaDeg: Double): Double = when {
        deltaDeg > 180.0 -> deltaDeg - 360.0
        deltaDeg < -180.0 -> deltaDeg + 360.0
        else -> deltaDeg
    }

    /** Compact metre/kilometre rendering for the one-line peer rows on the Flight Deck. */
    fun formatDistance(metres: Double): String = when {
        !metres.isFinite() -> "--"
        metres < 1_000.0 -> "${metres.toInt()}m"
        metres < 10_000.0 -> String.format("%.1fkm", metres / 1_000.0)
        else -> "${(metres / 1_000.0).toInt()}km"
    }

    /** Signed relative altitude, always carrying its sign so "level" is unambiguous. */
    fun formatRelativeAltitude(metres: Double): String {
        if (!metres.isFinite()) return "--"
        val rounded = Math.round(metres).toInt()
        return when {
            rounded > 0 -> "+${rounded}m"
            rounded < 0 -> "${rounded}m"
            else -> "0m"
        }
    }

    /** Eight-point compass label, the most a row this narrow can carry. */
    fun compassPoint(bearingDeg: Double): String {
        val points = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        val index = Math.round(normalizeBearingDeg(bearingDeg) / 45.0).toInt() % points.size
        return points[index]
    }

    fun absDifference(first: Double, second: Double): Double = abs(first - second)
}
