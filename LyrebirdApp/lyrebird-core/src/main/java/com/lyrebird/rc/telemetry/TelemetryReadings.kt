package com.lyrebird.rc.telemetry

/*
 * The aircraft-state contract, in plain units and with no DJI types.
 *
 * Telemetry used to travel as `Any?` fields on `TelemetryCoordinator` holding SDK objects, with
 * their `toString()` interpolated straight into JSON. That removed the imports but not the
 * dependency: the wire format was whatever DJI's `toString()` happened to print, so a field could
 * change shape without a single line of Lyrebird code changing, and nothing could be validated,
 * compared, or tested without an SDK on the classpath.
 *
 * These types are the replacement. Three rules, each of which the old representation could not
 * express:
 *
 *  - **Units are in the name.** `altitudeAslM`, `pitchDeg`, `northMps`. The SDK uses bare
 *    `altitude`/`pitch`/`x`, and the units are documented separately from the value, which is how
 *    a metres-versus-feet or degrees-versus-radians mistake survives review.
 *  - **Absent is not zero.** A reading that was never reported is `null`; a reading the aircraft
 *    explicitly reported as zero is a value. DJI's sentinels make those two look alike — an unset
 *    position is `(0, 0)`, a real place in the Atlantic — so the distinction has to be in the type.
 *  - **Every reading carries when it was taken.** Consumers can tell a stale value from a current
 *    one, which a bare number cannot say.
 */

/**
 * One observation of a value, with when it was seen.
 *
 * [reported] is false when the aircraft told us it does not have this value — a camera with no
 * thermal lens, a laser that did not lock — as opposed to the field simply having no reading yet,
 * which is a null [Reading]. Keeping the two apart is what lets a consumer refuse an operation
 * that needs the value instead of acting on a zero that was never measured.
 */
data class Reading<T>(
    val value: T,
    val observedAtMillis: Long,
    val reported: Boolean = true,
)

/**
 * A position on the WGS-84 ellipsoid.
 *
 * Carries only the height above mean sea level: the wire keeps height above the take-off point in
 * its own top-level `altitude` field, so having it here too meant two fields holding the same
 * number, with the frame reading one of them. One source, and it is the one the frame uses.
 */
data class GeoPosition(
    val latitudeDeg: Double,
    val longitudeDeg: Double,
    /** Height above mean sea level, metres. */
    val altitudeAslM: Double,
) {
    /** True when the coordinates are inside the globe's range rather than an unset sentinel. */
    val isPlausible: Boolean
        get() =
            latitudeDeg in -90.0..90.0 &&
                longitudeDeg in -180.0..180.0 &&
                !(latitudeDeg == 0.0 && longitudeDeg == 0.0)
}

/** Attitude in degrees: roll and pitch are absolute, yaw is a heading. */
data class AttitudeDeg(
    val rollDeg: Double,
    val pitchDeg: Double,
    val yawDeg: Double,
)

/** Velocity in the aircraft's local NED frame, metres per second. Down is positive downwards. */
data class VelocityNedMps(
    val northMps: Double,
    val eastMps: Double,
    val downMps: Double,
)

/**
 * Gimbal angles in degrees, in both reference frames the app needs.
 *
 * The absolute angles are the gimbal in the world frame; the joint angles are its own, which is
 * what a relative aim is expressed in. Whether the absolute yaw is referenced to north or to the
 * aircraft is a question this project has twice had to settle by flying rather than by reading, so
 * both are published rather than one being derived from the other.
 */
data class GimbalAnglesDeg(
    val rollDeg: Double,
    val pitchDeg: Double,
    val yawDeg: Double,
    val jointRollDeg: Double,
    val jointPitchDeg: Double,
    val jointYawDeg: Double,
)

/** A horizontal position, for a home point or a tracked target. */
data class GeoPoint(
    val latitudeDeg: Double,
    val longitudeDeg: Double,
) {
    val isPlausible: Boolean
        get() =
            latitudeDeg in -90.0..90.0 &&
                longitudeDeg in -180.0..180.0 &&
                !(latitudeDeg == 0.0 && longitudeDeg == 0.0)
}

/**
 * A point in space, for a target the aircraft measured rather than the place it is.
 *
 * Separate from [GeoPosition] because it carries no height above the take-off point: a laser fix on
 * a tree is a place on the globe, not a place relative to where the aircraft took off. Forcing it
 * into [GeoPosition] would mean inventing an AGL of zero, which is a claim about the terrain rather
 * than an absence of information.
 */
data class GeoPoint3D(
    val latitudeDeg: Double,
    val longitudeDeg: Double,
    /** Height in the same reference the aircraft reports its own altitude in. */
    val altitudeM: Double,
) {
    val isPlausible: Boolean
        get() =
            latitudeDeg in -90.0..90.0 &&
                longitudeDeg in -180.0..180.0 &&
                !(latitudeDeg == 0.0 && longitudeDeg == 0.0 && altitudeM == 0.0)
}

/** Pack and cell state, as the aircraft reports it. Percentages are 0..100, or -1 when unset. */
data class BatteryState(
    val percentRemaining: Int,
    val seriousLowPercent: Int,
    val lowPercent: Int,
    val remainingFlightTimeS: Int,
    val timeNeededToGoHomeS: Int,
    val timeNeededToLandS: Int,
)

/** Zoom state: focal lengths in millimetres, -1 when the aircraft does not report one. */
data class CameraZoomState(
    val zoomFocalLengthMm: Int,
    val opticalFocalLengthMm: Int,
    val hybridFocalLengthMm: Int,
    val zoomRatio: Double,
)

/** What the aircraft's flight controller is doing, in Lyrebird's own vocabulary. */
enum class AircraftFlightMode {
    /** The aircraft has not reported a mode, or is powered down. */
    UNKNOWN,
    MANUAL,
    ATTITUDE,
    GPS_ATTITUDE,
    SPORT,
    TRIPOD,
    AUTO_TAKEOFF,
    AUTO_LANDING,
    GO_HOME,
    WAYPOINT,
    VIRTUAL_STICK,
    OTHER,
}
