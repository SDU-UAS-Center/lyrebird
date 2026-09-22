package com.lyrebird.rc

import com.lyrebird.rc.mavlink.DetectedTargetSnapshot
import com.lyrebird.rc.mavlink.CommandResult
import com.lyrebird.rc.telemetry.GeoPoint3D
import java.io.OutputStream

data class LrfMeasurement(
    val distanceMeters: Double?,
    val state: String?,
    val target: GeoPoint3D?,
)

/** Media operations exposed to HTTP without leaking DJI view models or SDK value types. */
interface LyrebirdMediaPort {
    fun capturePhotoFileName(): String?
    fun captureThermalJson(): String?
    fun listMediaJson(): String
    fun sendMediaFile(fileName: String, outputStream: OutputStream)
    fun sendErrorResponse(message: String, outputStream: OutputStream)
}

/** Detection state exposed to HTTP as neutral snapshots rather than UXSDK target objects. */
interface LyrebirdDetectionPort {
    val isAutoSensingActive: Boolean
    fun currentTargets(): List<DetectedTargetSnapshot>
}

data class StickCommand(
    val leftX: Float,
    val leftY: Float,
    val rightX: Float,
    val rightY: Float,
)

interface LyrebirdFlightPort {
    fun takeoff(): CommandResult
    fun land(): CommandResult
    fun returnToHome(): CommandResult
    fun stick(command: StickCommand): CommandResult
    fun gotoYaw(yawDeg: Double): CommandResult
    fun gotoAltitude(altitudeM: Double): CommandResult
    fun abortMission(): CommandResult
    fun abortAll(): CommandResult
    fun enableVirtualStick(): CommandResult
    fun waypoint(
        latitudeDeg: Double,
        longitudeDeg: Double,
        altitudeM: Double,
        yawDeg: Double,
        maxSpeedMps: Double,
        noseForward: Boolean,
    ): CommandResult
    fun nativeTrajectory(waypoints: List<Triple<Double, Double, Double>>, speedMps: Double): CommandResult
    fun abortNativeMission(): CommandResult
    fun deactivateManualOverride(): CommandResult
    fun isManualOverrideActive(): Boolean
}

/**
 * The aircraft's own settings: firmware limits and the RC link's controls.
 *
 * Deliberately separate from [LyrebirdFlightPort]'s motion commands. Motion is what the aircraft
 * does, and its refusals are the aircraft's own (the safety latch, the RC pilot's override, a
 * refused waypoint); these are key reads and writes with explicit validation of their own.
 * Splitting them is what keeps the two sets of rules — what may fly, and what may be configured —
 * readable in one place each, and what lets the flight-limits and RC-pairing routes report the
 * aircraft's real answer instead of a screen's absence.
 *
 * Both ports are served by the process: a ground station can take off, fly a mission and
 * reconfigure the RC with no screen attached at all.
 *
 * A refused setting is a [CommandResult] with the aircraft's reason in `detail` — never a bare
 * boolean, and never a success message for a write the aircraft never got.
 */
interface LyrebirdAircraftSettingsPort {
    fun setRthAltitude(altitudeM: Int): CommandResult

    fun setMaxFlightHeight(heightM: Int): CommandResult

    fun setMaxFlightDistance(distanceM: Int): CommandResult

    fun setDistanceLimitEnabled(enabled: Boolean): CommandResult

    fun setRcControlMode(mode: String): CommandResult

    fun requestRcPairing(): CommandResult

    fun stopRcPairing(): CommandResult

    /** The RC's current control-mode value, as it crosses the wire (jp/usa/ch/custom). */
    fun rcControlMode(): String

    /** The RC link's pairing state, for the settings page and the settings snapshot. */
    fun rcPairingStatus(): String

    fun hdFrequencyBand(): String
}