package com.lyrebird.rc.mavlink

import com.lyrebird.rc.telemetry.GeoPosition

/** One fresh rangefinder reading, in the payload policy's vocabulary. */
data class LrfReading(
    /** The SDK's own state name, kept verbatim for the refusal message. NORMAL means locked. */
    val stateName: String,
    /** Distance in metres, or null when the laser reported none. */
    val distanceM: Double?,
    /** The measured point on the ground, or null when the laser did not resolve one. */
    val target: GeoPosition?,
)

/**
 * The payload and camera hardware the shared MAVLink command policy drives.
 *
 * Like [MotionCommandPort], this is one SDK generation's answer to a neutral question: the policy
 * above it owns validation, prose, unit scaling and threading, and this port does the aiming, the
 * shutter, the settings write. Nothing here can move the aircraft, and the port mirrors that — it
 * has no flight method to call.
 */
interface PayloadCommandPort {
    /** Aim the gimbal. No effect on ROI tracking, which is the policy's decision to make. */
    fun rotateGimbal(rotation: GimbalRotation)

    /** One fresh rangefinder reading, or null when none arrived. */
    fun takeLrfReading(): LrfReading?

    /** The thermal spot temperature in degrees Celsius, or null when there is no reading. */
    fun readThermalMaxTempC(): Double?

    /** Trip the thermal shutter; the descriptor names the stored files, or null when none. */
    fun captureThermalImage(): String?

    /** Trip the shutter; the stored file's name, or null when the shutter produced nothing. */
    fun capturePhoto(): String?

    fun setZoomRatio(ratio: Double)

    fun startRecording(): CommandResult

    fun stopRecording(): CommandResult

    /** The label of the aircraft's payload drop port, or null when it has none. */
    fun payloadDropPort(): String?

    /** The aircraft's display name, for the "no drop port" refusal prose. */
    val aircraftDisplayName: String

    /** Pulse the drop port's unlock-then-release; true when the payload accepted it. */
    fun dropPayload(): Boolean

    fun startAutoSensing()

    fun stopAutoSensing()

    fun setAutoSensingSwitch(checked: Boolean)

    fun startRoiTracking(
        latitudeDeg: Double,
        longitudeDeg: Double,
        altitudeM: Double,
    )

    fun stopRoiTracking()

    fun applyMavlinkParameter(
        name: String,
        value: Float,
    ): CommandResult

    /** Write one named text setting; false when the aircraft rejected the value. */
    fun writeTextSetting(
        name: String,
        value: String,
    ): Boolean

    /** The current value of one named text setting. */
    fun readTextSetting(name: String): String
}
