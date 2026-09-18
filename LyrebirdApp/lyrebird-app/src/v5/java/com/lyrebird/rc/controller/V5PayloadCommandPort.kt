package com.lyrebird.rc.controller

import com.lyrebird.rc.DroneControlProfiles
import com.lyrebird.rc.StreamingMode
import com.lyrebird.rc.mavlink.CommandResult
import com.lyrebird.rc.mavlink.GimbalRotation
import com.lyrebird.rc.mavlink.GimbalRotationMode
import com.lyrebird.rc.mavlink.LrfReading
import com.lyrebird.rc.mavlink.PayloadCommandPort
import com.lyrebird.rc.models.MediaVM
import com.lyrebird.rc.models.PayloadWidgetVM
import com.lyrebird.rc.settings.LyrebirdSettings
import com.lyrebird.rc.telemetry.GeoPosition
import dji.sdk.keyvalue.key.DJIKey
import dji.sdk.keyvalue.value.common.EmptyMsg
import dji.sdk.keyvalue.value.gimbal.GimbalAngleRotation
import dji.sdk.keyvalue.value.gimbal.GimbalAngleRotationMode
import dji.v5.et.action
import dji.v5.et.set

/**
 * What [V5PayloadCommandPort] needs from the app: the DJI keys, the view models and the settings
 * actions the activity owns. Everything here is a resource; every decision about what to do with
 * it lives in [MavlinkPayloadPolicy].
 */
internal interface V5PayloadCommandHost {
    val droneName: String
    val mediaVM: MediaVM
    val payloadWidgetVM: PayloadWidgetVM
    var gimbalKey: DJIKey.ActionKey<GimbalAngleRotation, EmptyMsg>
    val zoomKey: DJIKey<Double>
    val startRecording: DJIKey.ActionKey<EmptyMsg, EmptyMsg>
    val stopRecording: DJIKey.ActionKey<EmptyMsg, EmptyMsg>
    val settings: LyrebirdSettings

    fun applyMavlinkParameter(
        name: String,
        value: Float,
    ): CommandResult

    fun awaitAction(key: DJIKey.ActionKey<EmptyMsg, EmptyMsg>): CommandResult

    fun readThermalMaxTempNow(): Double?

    fun setAutoSensingSwitchChecked(checked: Boolean)

    fun setDetectionSource(value: String): Boolean

    fun setDroneName(value: String): Boolean

    fun setMediamtxServer(value: String): Boolean

    fun setStreamingMode(mode: StreamingMode)

    fun setVideoSource(value: String): Boolean

    fun setWebRtcResolution(value: String): Boolean

    fun startAutoSensing()

    fun stopAutoSensing()

    fun startRoiTracking(
        latitudeDeg: Double,
        longitudeDeg: Double,
        altitudeM: Double,
    )

    fun stopRoiTracking()
}

/**
 * [PayloadCommandPort] over the V5 payload and camera.
 *
 * Every method is a direct delegation or a unit conversion: the DJI rotation shape, the SDK's
 * unset-location sentinel and the profile's drop port live here, and nothing above this file
 * imports the SDK for them. The one piece of routing (`writeTextSetting`) keys off the parameter
 * names [MavlinkPayloadPolicy] owns, so the wire API has a single definition.
 */
internal class V5PayloadCommandPort(
    private val host: V5PayloadCommandHost,
) : PayloadCommandPort {
    companion object {
        private const val VIDEO_SOURCE_LABEL = "drone"
    }

    override fun rotateGimbal(rotation: GimbalRotation) {
        host.gimbalKey.action(
            GimbalAngleRotation(
                if (rotation.mode == GimbalRotationMode.ABSOLUTE) {
                    GimbalAngleRotationMode.ABSOLUTE_ANGLE
                } else {
                    GimbalAngleRotationMode.RELATIVE_ANGLE
                },
                rotation.pitchDeg,
                rotation.rollDeg,
                rotation.yawDeg,
                rotation.pitchIgnored,
                rotation.rollIgnored,
                rotation.yawIgnored,
                0.1,
                false,
                0,
            ),
        )
    }

    override fun takeLrfReading(): LrfReading? {
        val info = Payload.takeFreshLrfReading() ?: return null
        return LrfReading(
            stateName = info.laserMeasureState.name,
            distanceM = info.distance,
            // The SDK's unset location is (0, 0, 0) — a real place in the Gulf of Guinea, not a fix.
            target =
                info.location3D
                    ?.takeIf { it.latitude != 0.0 || it.longitude != 0.0 || it.altitude != 0.0 }
                    ?.let { GeoPosition(it.latitude, it.longitude, it.altitude) },
        )
    }

    override fun readThermalMaxTempC(): Double? = host.readThermalMaxTempNow()

    override fun captureThermalImage(): String? = Payload.captureThermal(host.mediaVM)

    override fun capturePhoto(): String? = Payload.capturePhoto(host.mediaVM)?.fileName

    override fun setZoomRatio(ratio: Double) {
        host.zoomKey.set(ratio)
    }

    override fun startRecording(): CommandResult = host.awaitAction(host.startRecording)

    override fun stopRecording(): CommandResult = host.awaitAction(host.stopRecording)

    override fun payloadDropPort(): String? = DroneControlProfiles.activeProfile().payloadIndexType?.name

    override val aircraftDisplayName: String get() = DroneControlProfiles.activeProfile().displayName

    override fun dropPayload(): Boolean {
        val profile = DroneControlProfiles.activeProfile()
        val indexType = profile.payloadIndexType ?: return false
        return Payload.dropPayload(
            host.payloadWidgetVM,
            indexType,
            profile.dropArmSwitchIndex,
            profile.dropReleaseButtonIndex,
        )
    }

    override fun startAutoSensing() = host.startAutoSensing()

    override fun stopAutoSensing() = host.stopAutoSensing()

    override fun setAutoSensingSwitch(checked: Boolean) = host.setAutoSensingSwitchChecked(checked)

    override fun startRoiTracking(
        latitudeDeg: Double,
        longitudeDeg: Double,
        altitudeM: Double,
    ) = host.startRoiTracking(latitudeDeg, longitudeDeg, altitudeM)

    override fun stopRoiTracking() = host.stopRoiTracking()

    override fun applyMavlinkParameter(
        name: String,
        value: Float,
    ): CommandResult = host.applyMavlinkParameter(name, value)

    override fun writeTextSetting(
        name: String,
        value: String,
    ): Boolean =
        when (name) {
            MavlinkPayloadPolicy.PARAM_DRONE_NAME -> host.setDroneName(value)
            MavlinkPayloadPolicy.PARAM_VIDEO_SOURCE -> host.setVideoSource(value)
            MavlinkPayloadPolicy.PARAM_MEDIAMTX -> host.setMediamtxServer(value)
            MavlinkPayloadPolicy.PARAM_DETECTION_SOURCE -> host.setDetectionSource(value)
            MavlinkPayloadPolicy.PARAM_RC_CONTROL_MODE -> DroneController.setRcControlMode(value)
            MavlinkPayloadPolicy.PARAM_RTC_RESOLUTION -> host.setWebRtcResolution(value)
            MavlinkPayloadPolicy.PARAM_STREAMING_MODE -> {
                val mode = StreamingMode.entries.firstOrNull { it.prefValue == value }
                if (mode == null) {
                    false
                } else {
                    host.setStreamingMode(mode)
                    true
                }
            }

            else -> false
        }

    override fun readTextSetting(name: String): String =
        when (name) {
            MavlinkPayloadPolicy.PARAM_DRONE_NAME -> host.droneName
            MavlinkPayloadPolicy.PARAM_VIDEO_SOURCE -> VIDEO_SOURCE_LABEL
            MavlinkPayloadPolicy.PARAM_MEDIAMTX -> host.settings.getMediamtxServer()
            MavlinkPayloadPolicy.PARAM_DETECTION_SOURCE -> host.settings.getDetectionSource().prefValue
            MavlinkPayloadPolicy.PARAM_RC_CONTROL_MODE -> DroneController.getRcControlMode()
            MavlinkPayloadPolicy.PARAM_RTC_RESOLUTION -> host.settings.getWebRTCResolutionPreset().prefValue
            MavlinkPayloadPolicy.PARAM_STREAMING_MODE -> host.settings.getStreamingMode().prefValue
            else -> ""
        }
}
