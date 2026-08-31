package com.lyrebird.rc.mavlink

/**
 * One consistent read of the aircraft state, in plain units, with no DJI SDK types.
 *
 * The mavlink package stays free of SDK imports for the same reason `TelemetryCoordinator` does:
 * the mapping from DJI values to wire values is the part worth testing, and it should not need a
 * drone (or the SDK on the classpath) to exercise. The host activity builds this from the same
 * accessors that feed the JSON telemetry cache, so the two surfaces cannot report different
 * numbers for the same instant.
 *
 * Unknown values use the conventions the MAVLink field documentation specifies rather than a
 * plausible-looking zero — see the `INVALID_*` constants.
 */
/**
 * One detected object, in the shape the detector reports it.
 *
 * Kept as the SDK's own left/top/right/bottom rather than converted to a centre and extent: the
 * conversion would be lossless but pointless, since nothing downstream wants it that way.
 */
internal data class DetectedTargetSnapshot(
    val type: String,
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double,
    val confidence: Double?
)

internal data class MavlinkSnapshot(
    val droneName: String = "",

    // Position. Altitudes in metres: [altitudeAslM] above mean sea level, [altitudeAglM]
    // relative to the take-off point.
    val latitudeDeg: Double = 0.0,
    val longitudeDeg: Double = 0.0,
    val altitudeAslM: Double = 0.0,
    val altitudeAglM: Double = 0.0,

    // Velocity in the NED frame, metres per second. Down is positive.
    val velocityNorthMps: Double = 0.0,
    val velocityEastMps: Double = 0.0,
    val velocityDownMps: Double = 0.0,

    // Attitude and heading in degrees. [headingDeg] is true north, as DJI reports it.
    val rollDeg: Double = 0.0,
    val pitchDeg: Double = 0.0,
    val yawDeg: Double = 0.0,
    val headingDeg: Double = 0.0,

    val satelliteCount: Int = INVALID_SATELLITES,

    /** Battery charge 0..100, or [INVALID_BATTERY] when the SDK has not reported one yet. */
    val batteryPercent: Int = INVALID_BATTERY,
    /** Seconds of flight remaining, or 0 meaning "no estimate provided" per BATTERY_STATUS. */
    val remainingFlightTimeS: Int = 0,

    val homeLatitudeDeg: Double = 0.0,
    val homeLongitudeDeg: Double = 0.0,
    val homeAltitudeAslM: Double = 0.0,
    val homeSet: Boolean = false,

    /** DJI flight mode name, used only to derive the reported mode. */
    val flightMode: String = "UNKNOWN",

    /**
     * Motors running. DJI exposes no arm/disarm concept, so this is the only honest source for
     * the heartbeat's armed flag — deriving it from the flight mode instead reports armed on the
     * ground, which is what the ground station must never be told.
     */
    val motorsRunning: Boolean = false,

    val manualOverrideActive: Boolean = false,

    /**
     * An ARM command was accepted. DJI has no arming state, so without this the heartbeat never
     * sets SAFETY_ARMED and a ground station's arm wait times out while the aircraft is already
     * taking off. It is cleared by DISARM; real motor activity reports armed regardless.
     */
    val armedCommanded: Boolean = false,

    /**
     * The onboard mission sequencer is flying a plan. It moves the aircraft through virtual
     * stick, which DJI reports as VIRTUAL_STICK — read as OFFBOARD — so the reported mode has to
     * be told about the sequencer directly or a ground station that started a mission never sees
     * the vehicle enter mission mode.
     */
    val missionActive: Boolean = false,

    /**
     * Camera is recording. Reported as CAMERA_CAPTURE_STATUS so a ground station's record button
     * reflects what the aircraft is actually doing, including when recording was started from the
     * Lyrebird UI or the RC rather than over MAVLink.
     */
    val isRecording: Boolean = false,

    // -- Payload and gimbal ------------------------------------------------------------------

    /** Gimbal attitude in the world frame, degrees. */
    val gimbalRollDeg: Double = 0.0,
    val gimbalPitchDeg: Double = 0.0,
    val gimbalYawDeg: Double = 0.0,

    /**
     * Gimbal attitude relative to the aircraft body, degrees. DJI calls these the joint angles.
     *
     * Reported rather than left to be derived. Composing the world attitude with the aircraft's
     * recovers the right sign and slope, but sits about 1.5 degrees off what DJI reports --
     * measured over a hand-tilted sweep -- because the joint angles carry a mounting offset the
     * world attitude does not describe.
     *
     * On the wire, pitch and roll travel as centidegrees in LYREBIRD_STATUS, and yaw travels
     * as `delta_yaw` in GIMBAL_DEVICE_ATTITUDE_STATUS -- which MAVLink specifies in radians, so
     * the builder converts.
     */
    val gimbalJointPitchDeg: Double = 0.0,
    val gimbalJointRollDeg: Double = 0.0,
    val gimbalJointYawDeg: Double = 0.0,

    /** Focal lengths in millimetres, or 0 when the payload does not report them. */
    val zoomFocalLengthMm: Int = 0,
    val opticalFocalLengthMm: Int = 0,
    val hybridFocalLengthMm: Int = 0,

    /** Last rangefinder reading in metres, or null when the laser has not locked. */
    val lrfDistanceM: Double? = null,
    val lrfTargetLatitudeDeg: Double? = null,
    val lrfTargetLongitudeDeg: Double? = null,
    val lrfTargetAltitudeM: Double? = null,

    // -- Authority and readiness -------------------------------------------------------------

    /** Pre-flight checks pass and a takeoff would be accepted. */
    val readyToTakeoff: Boolean = false,

    /** Why a takeoff would be refused, or "NONE". */
    val takeoffBlockReason: String = "",

    // -- DJI's smart-return budgets ----------------------------------------------------------

    val timeNeededToGoHomeS: Int = 0,
    val timeNeededToLandS: Int = 0,
    val totalFlightTimeS: Int = 0,
    val maxRadiusCanFlyAndGoHomeM: Double = 0.0,
    val batteryNeededToGoHomePercent: Int = 0,
    val batteryNeededToLandPercent: Int = 0,

    // -- Reach latches -----------------------------------------------------------------------

    /**
     * Where the aircraft has got to, reported as state alongside the command acknowledgements.
     *
     * The ack says "the command you sent has finished"; these say "this is where things stand".
     * A ground station that missed an ack — started late, dropped a packet — still learns the
     * truth from the next status message, which an ack alone can never provide.
     */
    val waypointReached: Boolean = false,
    val waypointSeq: Long = 0,
    val yawReached: Boolean = false,
    val yawSeq: Long = 0,
    val altitudeReached: Boolean = false,
    val altitudeSeq: Long = 0,

    // -- Identity and services ---------------------------------------------------------------

    /** How to reach this aircraft's other surfaces, and what it is. Static for a session. */
    val ipAddress: String = "",
    val httpPort: Int = 0,
    val telemetryPort: Int = 0,
    val videoMode: String = "",
    val hasThermal: Boolean = false,

    // -- On-device detection -----------------------------------------------------------------

    val autoSensingActive: Boolean = false,
    val detectionSource: String = "",
    val detectionConfidenceThreshold: Float = 0f,

    /** The current detection cycle's targets, in the order the detector reported them. */
    val detectedTargets: List<DetectedTargetSnapshot> = emptyList()
) {
    /**
     * Whether the home coordinates are a real place rather than the SDK's uninitialised value.
     *
     * Deliberately separate from [homeSet], which is a latch meaning "the aircraft recorded its
     * home point on this flight". DJI reports usable home coordinates well before that latch
     * closes, and the HTTP surface has always published them; gating the MAVLink message on the
     * latch instead meant a ground station saw no home at all, and computed distances from
     * nothing. The check that actually matters is whether the numbers are a place: (0, 0) is the
     * SDK's unset value, and out-of-range values are the uninitialised garbage that once
     * overflowed the degE7 encoding.
     */
    val homeCoordinatesValid: Boolean
        get() = (homeLatitudeDeg != 0.0 || homeLongitudeDeg != 0.0) &&
            homeLatitudeDeg in -90.0..90.0 &&
            homeLongitudeDeg in -180.0..180.0

    companion object {
        const val INVALID_BATTERY = -1
        const val INVALID_SATELLITES = -1

        /** GPS_RAW_INT / GLOBAL_POSITION_INT "unknown" for uint16 fields. */
        const val UINT16_UNKNOWN = 0xFFFF

        /** BATTERY_STATUS temperature "unknown" (INT16_MAX). */
        const val INT16_UNKNOWN = 32767
    }
}
