package com.lyrebird.rc.controller

import android.util.Log
import com.lyrebird.rc.DroneControlProfiles
import com.lyrebird.rc.StreamingMode
import com.lyrebird.rc.mavlink.CommandResult
import com.lyrebird.rc.mavlink.GimbalRotation
import com.lyrebird.rc.mavlink.GimbalRotationMode
import com.lyrebird.rc.mavlink.MavlinkCommandOutcome
import com.lyrebird.rc.mavlink.MavlinkCommandSink
import com.lyrebird.rc.models.MediaVM
import com.lyrebird.rc.models.PayloadWidgetVM
import com.lyrebird.rc.settings.LyrebirdSettings
import dji.sdk.keyvalue.key.DJIKey
import dji.sdk.keyvalue.value.camera.LaserMeasureState
import dji.sdk.keyvalue.value.common.EmptyMsg
import dji.sdk.keyvalue.value.gimbal.GimbalAngleRotation
import dji.sdk.keyvalue.value.gimbal.GimbalAngleRotationMode
import dji.v5.et.action
import dji.v5.et.set
import java.util.concurrent.ExecutorService

internal interface V5MavlinkCommandHost {
    val mainHandler: android.os.Handler
    val droneName: String
    val mediaVM: MediaVM
    val payloadWidgetVM: PayloadWidgetVM
    var gimbalKey: DJIKey.ActionKey<GimbalAngleRotation, EmptyMsg>
    val zoomKey: DJIKey<Double>
    val startRecording: DJIKey.ActionKey<EmptyMsg, EmptyMsg>
    val stopRecording: DJIKey.ActionKey<EmptyMsg, EmptyMsg>
    var lrfDistanceMeters: Double?
    var lrfTargetLocation: dji.sdk.keyvalue.value.common.LocationCoordinate3D?

    fun isMavlinkOriginTrusted(): Boolean

    fun reportCaptureStarted()

    fun reportImageCaptured(
        success: Boolean,
        fileName: String,
    )

    val captureExecutor: ExecutorService
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

    fun setStreamingMode(mode: com.lyrebird.rc.StreamingMode)

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

internal class V5MavlinkCommandSink(
    private val host: V5MavlinkCommandHost,
) {
    companion object {
        private const val TAG = "LyrebirdDefaultLayout"
        private const val ACTION_TIMEOUT_MS = 2_000L
        private const val PARAM_RTH_ALTITUDE = "LB_RTH_ALT"
        private const val PARAM_MAX_HEIGHT = "LB_MAX_HEIGHT"
        private const val PARAM_MAX_DISTANCE = "LB_MAX_DIST"
        private const val PARAM_DISTANCE_LIMIT = "LB_DIST_LIMIT_EN"
        private const val PARAM_WEBRTC_FPS = "LB_RTC_FPS"
        private const val PARAM_DETECTIONS = "LB_DETECT_EN"
        private const val PARAM_EDGE_CONFIDENCE = "LB_EDGE_CONF"
        private const val PARAM_SURFACE_H264_ENCODER = "LB_SURFACE_H264"
        private const val PARAM_MAVLINK_SYSTEM_ID = "LB_MAV_SYSID"
        private const val PARAM_DRONE_NAME = "LB_DRONE_NAME"
        private const val PARAM_VIDEO_SOURCE = "LB_VIDEO_SRC"
        private const val PARAM_MEDIAMTX = "LB_MEDIAMTX"
        private const val PARAM_DETECTION_SOURCE = "LB_DETECT_SRC"
        private const val PARAM_RC_CONTROL_MODE = "LB_RC_MODE"
        private const val PARAM_RTC_RESOLUTION = "LB_RTC_RES"
        private const val PARAM_STREAMING_MODE = "LB_STREAM_MODE"
        private const val VIDEO_SOURCE_LABEL = "drone"
    }

    val commandSink: MavlinkCommandSink
        get() = mavlinkCommandSink

    fun nudgeGimbal(
        pitchDeg: Double,
        yawDeg: Double,
    ): CommandResult {
        host.gimbalKey.action(
            GimbalAngleRotation(
                GimbalAngleRotationMode.RELATIVE_ANGLE,
                pitchDeg,
                0.0,
                yawDeg,
                pitchDeg == 0.0,
                true,
                yawDeg == 0.0,
                0.1,
                false,
                0,
            ),
        )
        return CommandResult(MavlinkCommandOutcome.ACCEPTED)
    }

    /**
     * Payload and camera commands reachable over MAVLink.
     *
     * Deliberately excludes every command that could move the aircraft. The set here is the same
     * work the equivalent HTTP endpoints do, called through the same view models, so the two
     * surfaces cannot drift in behaviour — which is the failure that killed the previous
     * ground-station MAVLink proxy.
     *
     * Commands run on the main thread because the DJI view models expect it, and the endpoint
     * calls this from its receive thread.
     */
    internal val mavlinkCommandSink =
        object : MavlinkCommandSink {
            override fun setGimbal(rotation: GimbalRotation): CommandResult {
                // An explicit aim ends the tracking. Otherwise the two fight at 5 Hz and the operator
                // loses, which looks like a gimbal that ignores its commands rather than like a
                // region of interest that is still set.
                host.stopRoiTracking()
                return rotateGimbal(rotation)
            }

            /** The aim itself, with no effect on tracking — what the ROI loop drives. */
            private fun rotateGimbal(rotation: GimbalRotation): CommandResult {
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
                return CommandResult(MavlinkCommandOutcome.ACCEPTED)
            }

            override fun setGimbalRelative(
                pitchDeg: Double,
                yawDeg: Double,
            ): CommandResult {
                host.stopRoiTracking()
                return nudgeGimbal(pitchDeg, yawDeg)
            }

            /** A relative nudge that leaves tracking alone, so the ROI loop can use it. */
            fun nudgeGimbal(
                pitchDeg: Double,
                yawDeg: Double,
            ): CommandResult {
                // A zero delta on an axis means "leave it alone", which is what the ignore flags say
                // — sending zero as a relative angle would be the same thing, but saying it through
                // the flag is what keeps a two-axis nudge from fighting itself.
                return rotateGimbal(
                    GimbalRotation(
                        mode = GimbalRotationMode.RELATIVE,
                        pitchDeg = pitchDeg,
                        rollDeg = 0.0,
                        yawDeg = yawDeg,
                        pitchIgnored = pitchDeg == 0.0,
                        rollIgnored = true,
                        yawIgnored = yawDeg == 0.0,
                    ),
                )
            }

            override fun setRegionOfInterest(
                latitudeDeg: Double,
                longitudeDeg: Double,
                altitudeM: Double,
            ): CommandResult {
                if (!latitudeDeg.isFinite() || !longitudeDeg.isFinite()) {
                    return CommandResult(MavlinkCommandOutcome.DENIED, "ROI needs a real position")
                }
                host.startRoiTracking(latitudeDeg, longitudeDeg, altitudeM.takeIf { it.isFinite() } ?: 0.0)
                return CommandResult(MavlinkCommandOutcome.ACCEPTED)
            }

            override fun clearRegionOfInterest(): CommandResult {
                host.stopRoiTracking()
                return CommandResult(MavlinkCommandOutcome.ACCEPTED)
            }

            override fun measureLrf(): CommandResult {
                val info =
                    Payload.takeFreshLrfReading()
                        ?: return CommandResult(MavlinkCommandOutcome.FAILED, "No rangefinder reading")
                if (info.laserMeasureState != LaserMeasureState.NORMAL) {
                    // The laser did not lock — no distance, and no point to geo-reference.
                    return CommandResult(
                        MavlinkCommandOutcome.FAILED,
                        "Laser state ${info.laserMeasureState}",
                    )
                }
                host.lrfDistanceMeters = info.distance
                info.location3D
                    ?.takeIf { it.latitude != 0.0 || it.longitude != 0.0 || it.altitude != 0.0 }
                    // Surfaced on the telemetry stream as lrfTarget, exactly as the HTTP route does.
                    ?.let { host.lrfTargetLocation = it }
                // Centimetres: the distance is metres with a useful fraction.
                return CommandResult(
                    MavlinkCommandOutcome.ACCEPTED,
                    resultValue = ((info.distance ?: 0.0) * 100).toInt(),
                )
            }

            override fun captureTemperature(): CommandResult {
                val maxTemp =
                    host.readThermalMaxTempNow()
                        ?: return CommandResult(MavlinkCommandOutcome.FAILED, "No thermal reading")
                // Hundredths of a degree, so a fractional reading survives an integer field.
                return CommandResult(
                    MavlinkCommandOutcome.ACCEPTED,
                    resultValue = (maxTemp * 100).toInt(),
                )
            }

            override fun captureThermalImage(): CommandResult {
                // The descriptor names the per-lens files the shutter stored; downloads stay on the
                // media surface (by name), exactly as they do over HTTP. Only the shutter is commanded
                // here — there is no room for the descriptor in an ack.
                val descriptor =
                    Payload.captureThermal(host.mediaVM)
                        ?: return CommandResult(
                            MavlinkCommandOutcome.FAILED,
                            "Thermal capture produced no file",
                        )
                return CommandResult(MavlinkCommandOutcome.ACCEPTED, detail = descriptor)
            }

            override fun dropPayload(): CommandResult {
                val profile = DroneControlProfiles.activeProfile()
                val indexType =
                    profile.payloadIndexType
                        // The detail is the HTTP surface's prose, composed here where the profile
                        // is known — the route table on the other side of the port is SDK-free.
                        ?: return CommandResult(
                            MavlinkCommandOutcome.UNSUPPORTED,
                            "REJECTED: ${profile.displayName} has no payload drop port configured.",
                        )
                val dropped =
                    Payload.dropPayload(
                        host.payloadWidgetVM,
                        indexType,
                        profile.dropArmSwitchIndex,
                        profile.dropReleaseButtonIndex,
                    )
                return if (dropped) {
                    CommandResult(MavlinkCommandOutcome.ACCEPTED, "Payload dropped on $indexType")
                } else {
                    CommandResult(MavlinkCommandOutcome.FAILED, "Payload drop failed")
                }
            }

            override fun setAutoSensing(enabled: Boolean): CommandResult {
                // On the main thread, as the HTTP route does: this drives the detector and its UI
                // switch, and the MAVLink receive thread is not where either belongs.
                host.mainHandler.post {
                    if (enabled) host.startAutoSensing() else host.stopAutoSensing()
                    host.setAutoSensingSwitchChecked(enabled)
                }
                return CommandResult(MavlinkCommandOutcome.ACCEPTED)
            }

            override fun setParameter(
                name: String,
                value: Float,
            ): CommandResult = host.applyMavlinkParameter(name, value)

            override fun setTextParameter(
                name: String,
                value: String,
            ): CommandResult {
                val applied =
                    when (name) {
                        PARAM_DRONE_NAME -> host.setDroneName(value)
                        PARAM_VIDEO_SOURCE -> host.setVideoSource(value)
                        PARAM_MEDIAMTX -> host.setMediamtxServer(value)
                        PARAM_DETECTION_SOURCE -> host.setDetectionSource(value)
                        PARAM_RC_CONTROL_MODE -> DroneController.setRcControlMode(value)
                        PARAM_RTC_RESOLUTION -> host.setWebRtcResolution(value)
                        PARAM_STREAMING_MODE -> {
                            val mode = StreamingMode.entries.firstOrNull { it.prefValue == value }
                            if (mode == null) {
                                false
                            } else {
                                host.setStreamingMode(mode)
                                true
                            }
                        }

                        else -> {
                            Log.d(TAG, "Refusing write to unknown text parameter $name")
                            return CommandResult(MavlinkCommandOutcome.DENIED, "$name is not writable")
                        }
                    }
                // The detail carries the value the setting now holds, so the acknowledgement echoes
                // what took rather than what was asked for — which is how a caller detects a value
                // the aircraft rejected as out of range or unknown.
                val current = textParameters().firstOrNull { it.first == name }?.second.orEmpty()
                return if (applied) {
                    CommandResult(MavlinkCommandOutcome.ACCEPTED, current)
                } else {
                    CommandResult(MavlinkCommandOutcome.DENIED, current)
                }
            }

            override fun textParameters(): List<Pair<String, String>> =
                listOf(
                    PARAM_DRONE_NAME to host.droneName,
                    PARAM_VIDEO_SOURCE to VIDEO_SOURCE_LABEL,
                    PARAM_MEDIAMTX to host.settings.getMediamtxServer(),
                    PARAM_DETECTION_SOURCE to host.settings.getDetectionSource().prefValue,
                    PARAM_RC_CONTROL_MODE to DroneController.getRcControlMode(),
                    PARAM_RTC_RESOLUTION to host.settings.getWebRTCResolutionPreset().prefValue,
                    PARAM_STREAMING_MODE to host.settings.getStreamingMode().prefValue,
                )

            override fun setCameraZoom(zoomRatio: Float): CommandResult {
                if (zoomRatio <= 0f) return CommandResult(MavlinkCommandOutcome.FAILED)
                host.zoomKey.set(zoomRatio.toDouble())
                // set() is fire-and-forget; the ratio the aircraft settled on is reported in
                // telemetry, which is where a ground station should read it back from.
                return CommandResult(MavlinkCommandOutcome.ACCEPTED)
            }

            override fun startVideoRecording(): CommandResult = host.awaitAction(host.startRecording)

            override fun stopVideoRecording(): CommandResult = host.awaitAction(host.stopRecording)

            /**
             * Trip one shutter.
             *
             * Runs on a worker rather than inline: tripping a shutter and waiting for the file to
             * appear takes seconds, and this is called from the endpoint's single receive thread —
             * blocking it would stall every other inbound message, including the ground station's own
             * heartbeat handling.
             *
             * So the command is acknowledged as accepted and the real outcome follows as
             * CAMERA_IMAGE_CAPTURED, whose `capture_result` reports whether a photo actually
             * happened. That split is what the message exists for.
             *
             * Uses the generic photo path, not the thermal one: a Mini 3 has a single lens and no
             * thermal file to find, so labelling the result thermal/wide/zoom would be meaningless.
             *
             * The endpoint is optional rather than required: the distance-triggered capture loop
             * inside a flying mission trips this same shutter, and a mission must not stop
             * photographing because the MAVLink endpoint happens to be mid-restart. Without an
             * endpoint there is simply no CAMERA_IMAGE_CAPTURED to send, so the outcome is logged
             * instead of reported — the shutter still fires.
             */
            override fun captureImage(): CommandResult {
                host.reportCaptureStarted()
                host.captureExecutor.execute {
                    val file =
                        runCatching { Payload.capturePhoto(host.mediaVM) }
                            .onFailure { Log.e(TAG, "Capture failed: ${it.message}", it) }
                            .getOrNull()
                    if (file == null) {
                        Log.w(TAG, "Capture produced no file")
                    }
                    host.reportImageCaptured(file != null, file?.fileName.orEmpty())
                }
                return CommandResult(MavlinkCommandOutcome.ACCEPTED)
            }
        }
}
