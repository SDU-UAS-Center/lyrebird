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
    fun setRthAltitude(altitudeM: Int): CommandResult
    fun setMaxFlightHeight(heightM: Int): CommandResult
    fun setMaxFlightDistance(distanceM: Int): CommandResult
    fun setDistanceLimitEnabled(enabled: Boolean): CommandResult
    fun setRcControlMode(mode: String): CommandResult
    fun requestRcPairing(): CommandResult
    fun stopRcPairing(): CommandResult
    fun deactivateManualOverride(): CommandResult
    fun isManualOverrideActive(): Boolean
}