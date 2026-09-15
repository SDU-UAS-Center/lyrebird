package com.lyrebird.rc

import com.lyrebird.rc.mavlink.DetectedTargetSnapshot
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