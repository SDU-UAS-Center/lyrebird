package com.lyrebird.rc.controller

import android.util.Log
import com.lyrebird.rc.mavlink.CommandResult
import com.lyrebird.rc.mavlink.GimbalRotation
import com.lyrebird.rc.mavlink.GimbalRotationMode
import com.lyrebird.rc.mavlink.MavlinkCommandOutcome
import com.lyrebird.rc.mavlink.MavlinkCommandSink
import com.lyrebird.rc.mavlink.PayloadCommandPort
import com.lyrebird.rc.telemetry.GeoPosition

/**
 * What the shared payload policy needs from its host besides the hardware port.
 *
 * The host owns the application-side effects a command has: the worker the shutter runs on, the
 * capture reports the MAVLink endpoint sends, and where a rangefinder reading is published for the
 * telemetry stream. No SDK types — an adapter wires these to whatever it has.
 */
internal interface MavlinkPayloadHost {
    fun postToMain(block: () -> Unit)

    /** Run a shutter capture on a worker; blocking the caller is never acceptable. */
    fun runCapture(block: () -> Unit)

    fun reportCaptureStarted()

    fun reportImageCaptured(
        success: Boolean,
        fileName: String,
    )

    /**
     * Record a rangefinder reading for the telemetry stream. A null [target] leaves the last one
     * in place — a laser that resolved no fix is not a command to forget where it was aiming.
     */
    fun publishLrfReading(
        distanceM: Double?,
        target: GeoPosition?,
    )
}

/**
 * Payload and camera commands reachable over MAVLink.
 *
 * Deliberately excludes every command that could move the aircraft. The set here is the same work
 * the equivalent HTTP endpoints do, called through the same view models, so the two surfaces
 * cannot drift in behaviour — which is the failure that killed the previous ground-station MAVLink
 * proxy.
 *
 * This is the policy both backends share: the validation, the unit scaling into MAVLink's integer
 * fields, the refusal prose (which doubles as the HTTP surface's response text) and the threading
 * rules. It used to live beside the V5 SDK, where none of it was testable; the hardware calls now
 * go through [PayloadCommandPort].
 */
internal class MavlinkPayloadPolicy(
    private val host: MavlinkPayloadHost,
    private val payload: PayloadCommandPort,
) {
    companion object {
        private const val TAG = "LyrebirdDefaultLayout"
        private const val LRF_STATE_NORMAL = "NORMAL"

        // The string map both surfaces expose. The names are the wire API; the adapter routes
        // them to whichever settings store it has.
        internal const val PARAM_DRONE_NAME = "LB_DRONE_NAME"
        internal const val PARAM_VIDEO_SOURCE = "LB_VIDEO_SRC"
        internal const val PARAM_MEDIAMTX = "LB_MEDIAMTX"
        internal const val PARAM_DETECTION_SOURCE = "LB_DETECT_SRC"
        internal const val PARAM_RC_CONTROL_MODE = "LB_RC_MODE"
        internal const val PARAM_RTC_RESOLUTION = "LB_RTC_RES"
        internal const val PARAM_STREAMING_MODE = "LB_STREAM_MODE"

        private val TEXT_PARAMETERS =
            listOf(
                PARAM_DRONE_NAME,
                PARAM_VIDEO_SOURCE,
                PARAM_MEDIAMTX,
                PARAM_DETECTION_SOURCE,
                PARAM_RC_CONTROL_MODE,
                PARAM_RTC_RESOLUTION,
                PARAM_STREAMING_MODE,
            )
    }

    val sink: MavlinkCommandSink
        get() = mavlinkCommandSink

    internal val mavlinkCommandSink =
        object : MavlinkCommandSink {
            override fun setGimbal(rotation: GimbalRotation): CommandResult {
                // An explicit aim ends the tracking. Otherwise the two fight at 5 Hz and the operator
                // loses, which looks like a gimbal that ignores its commands rather than like a
                // region of interest that is still set.
                payload.stopRoiTracking()
                payload.rotateGimbal(rotation)
                return CommandResult(MavlinkCommandOutcome.ACCEPTED)
            }

            override fun setGimbalRelative(
                pitchDeg: Double,
                yawDeg: Double,
            ): CommandResult {
                payload.stopRoiTracking()
                // A zero delta on an axis means "leave it alone", which is what the ignore flags say
                // — sending zero as a relative angle would be the same thing, but saying it through
                // the flag is what keeps a two-axis nudge from fighting itself.
                payload.rotateGimbal(
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
                return CommandResult(MavlinkCommandOutcome.ACCEPTED)
            }

            override fun setRegionOfInterest(
                latitudeDeg: Double,
                longitudeDeg: Double,
                altitudeM: Double,
            ): CommandResult {
                if (!latitudeDeg.isFinite() || !longitudeDeg.isFinite()) {
                    return CommandResult(MavlinkCommandOutcome.DENIED, "ROI needs a real position")
                }
                payload.startRoiTracking(latitudeDeg, longitudeDeg, altitudeM.takeIf { it.isFinite() } ?: 0.0)
                return CommandResult(MavlinkCommandOutcome.ACCEPTED)
            }

            override fun clearRegionOfInterest(): CommandResult {
                payload.stopRoiTracking()
                return CommandResult(MavlinkCommandOutcome.ACCEPTED)
            }

            override fun measureLrf(): CommandResult {
                val reading =
                    payload.takeLrfReading()
                        ?: return CommandResult(MavlinkCommandOutcome.FAILED, "No rangefinder reading")
                if (reading.stateName != LRF_STATE_NORMAL) {
                    // The laser did not lock — no distance, and no point to geo-reference.
                    return CommandResult(
                        MavlinkCommandOutcome.FAILED,
                        "Laser state ${reading.stateName}",
                    )
                }
                host.publishLrfReading(reading.distanceM, reading.target)
                // Centimetres: the distance is metres with a useful fraction.
                return CommandResult(
                    MavlinkCommandOutcome.ACCEPTED,
                    resultValue = ((reading.distanceM ?: 0.0) * 100).toInt(),
                )
            }

            override fun captureTemperature(): CommandResult {
                val maxTemp =
                    payload.readThermalMaxTempC()
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
                    payload.captureThermalImage()
                        ?: return CommandResult(
                            MavlinkCommandOutcome.FAILED,
                            "Thermal capture produced no file",
                        )
                return CommandResult(MavlinkCommandOutcome.ACCEPTED, detail = descriptor)
            }

            override fun dropPayload(): CommandResult {
                // The detail is the HTTP surface's prose, composed here where the capability is
                // known — the route table on the other side of the port is SDK-free.
                val port =
                    payload.payloadDropPort()
                        ?: return CommandResult(
                            MavlinkCommandOutcome.UNSUPPORTED,
                            "REJECTED: ${payload.aircraftDisplayName} has no payload drop port configured.",
                        )
                return if (payload.dropPayload()) {
                    CommandResult(MavlinkCommandOutcome.ACCEPTED, "Payload dropped on $port")
                } else {
                    CommandResult(MavlinkCommandOutcome.FAILED, "Payload drop failed")
                }
            }

            override fun setAutoSensing(enabled: Boolean): CommandResult {
                // On the main thread, as the HTTP route does: this drives the detector and its UI
                // switch, and the MAVLink receive thread is not where either belongs.
                host.postToMain {
                    if (enabled) payload.startAutoSensing() else payload.stopAutoSensing()
                    payload.setAutoSensingSwitch(enabled)
                }
                return CommandResult(MavlinkCommandOutcome.ACCEPTED)
            }

            override fun setParameter(
                name: String,
                value: Float,
            ): CommandResult = payload.applyMavlinkParameter(name, value)

            override fun setTextParameter(
                name: String,
                value: String,
            ): CommandResult {
                val applied =
                    when (name) {
                        PARAM_DRONE_NAME,
                        PARAM_VIDEO_SOURCE,
                        PARAM_MEDIAMTX,
                        PARAM_DETECTION_SOURCE,
                        PARAM_RC_CONTROL_MODE,
                        PARAM_RTC_RESOLUTION,
                        PARAM_STREAMING_MODE,
                        -> payload.writeTextSetting(name, value)

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

            override fun textParameters(): List<Pair<String, String>> = TEXT_PARAMETERS.map { it to payload.readTextSetting(it) }

            override fun setCameraZoom(zoomRatio: Float): CommandResult {
                if (zoomRatio <= 0f) return CommandResult(MavlinkCommandOutcome.FAILED)
                payload.setZoomRatio(zoomRatio.toDouble())
                // The set is fire-and-forget; the ratio the aircraft settled on is reported in
                // telemetry, which is where a ground station should read it back from.
                return CommandResult(MavlinkCommandOutcome.ACCEPTED)
            }

            override fun startVideoRecording(): CommandResult = payload.startRecording()

            override fun stopVideoRecording(): CommandResult = payload.stopRecording()

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
                host.runCapture {
                    val file =
                        runCatching { payload.capturePhoto() }
                            .onFailure { Log.e(TAG, "Capture failed: ${it.message}", it) }
                            .getOrNull()
                    if (file == null) {
                        Log.w(TAG, "Capture produced no file")
                    }
                    host.reportImageCaptured(file != null, file.orEmpty())
                }
                return CommandResult(MavlinkCommandOutcome.ACCEPTED)
            }
        }
}
