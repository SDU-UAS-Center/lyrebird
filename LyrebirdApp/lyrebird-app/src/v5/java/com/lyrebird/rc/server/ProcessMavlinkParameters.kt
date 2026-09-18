package com.lyrebird.rc.server

import android.util.Log
import com.lyrebird.rc.controller.DroneController
import com.lyrebird.rc.mavlink.CommandResult
import com.lyrebird.rc.mavlink.MavlinkCommandOutcome
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The MAVLink parameter writes shared by the HTTP and MAVLink command paths.
 *
 * An allowlist, not a passthrough (moved from the activity so the table keeps working with no
 * screen attached). Most of the published list is read-only by nature — PID gains belong to the
 * control profile, and the PX4 compatibility parameters are constants that exist only to satisfy
 * QGroundControl's setup checks. Writing those would either do nothing or quietly change flight
 * behaviour from a settings dialog, so anything not named here is refused rather than accepted
 * and dropped.
 *
 * The writes that land on settings a visible control reflects are routed through
 * [ProcessCommandSurface]; with no screen attached those degrade exactly like the HTTP surface
 * does rather than pretending to have taken.
 */
internal object ProcessMavlinkParameters {
    private const val TAG = "LyrebirdMavParams"

    /** How long to wait for the aircraft to confirm a write before reporting failure. */
    private const val ACTION_TIMEOUT_MS = 2_000L

    const val PARAM_RTH_ALTITUDE = "LB_RTH_ALT"
    const val PARAM_MAX_HEIGHT = "LB_MAX_HEIGHT"
    const val PARAM_MAX_DISTANCE = "LB_MAX_DIST"
    const val PARAM_DISTANCE_LIMIT = "LB_DIST_LIMIT_EN"
    const val PARAM_WEBRTC_FPS = "LB_RTC_FPS"
    const val PARAM_DETECTIONS = "LB_DETECT_EN"
    const val PARAM_EDGE_CONFIDENCE = "LB_EDGE_CONF"
    const val PARAM_SURFACE_H264_ENCODER = "LB_SURFACE_H264"
    const val PARAM_MAVLINK_SYSTEM_ID = "LB_MAV_SYSID"

    fun apply(
        name: String,
        value: Float,
    ): CommandResult =
        when (name) {
            PARAM_MAX_HEIGHT ->
                awaitParameterWrite { done ->
                    DroneController.setMaxFlightHeight(value.toInt())
                    done(true)
                }

            PARAM_MAX_DISTANCE ->
                awaitParameterWrite { done ->
                    DroneController.setMaxFlightDistance(value.toInt())
                    done(true)
                }

            PARAM_DISTANCE_LIMIT ->
                awaitParameterWrite { done ->
                    DroneController.setDistanceLimitEnabled(value >= 0.5f)
                    done(true)
                }

            PARAM_WEBRTC_FPS ->
                if (ProcessCommandSurface.setWebRtcFps(value.toInt())) {
                    CommandResult(MavlinkCommandOutcome.ACCEPTED)
                } else {
                    CommandResult(MavlinkCommandOutcome.DENIED, "Unsupported frame rate")
                }

            PARAM_DETECTIONS -> {
                ProcessCommandSurface.setDetectionsEnabled(value >= 0.5f)
                CommandResult(MavlinkCommandOutcome.ACCEPTED)
            }

            PARAM_SURFACE_H264_ENCODER -> {
                ProcessCommandSurface.setDjiSurfaceH264Encoder(value >= 0.5f)
                CommandResult(MavlinkCommandOutcome.ACCEPTED)
            }

            PARAM_MAVLINK_SYSTEM_ID -> {
                val systemId = value.toInt()
                if (value != systemId.toFloat() || !ProcessCommandSurface.setMavlinkSystemId(systemId)) {
                    CommandResult(
                        MavlinkCommandOutcome.DENIED,
                        "Use 0 for automatic or 1-99 for a manual vehicle ID",
                    )
                } else {
                    CommandResult(MavlinkCommandOutcome.ACCEPTED)
                }
            }

            PARAM_EDGE_CONFIDENCE ->
                if (ProcessCommandSurface.setEdgeConfidence(value)) {
                    CommandResult(MavlinkCommandOutcome.ACCEPTED)
                } else {
                    CommandResult(MavlinkCommandOutcome.DENIED, "Threshold out of range")
                }

            PARAM_RTH_ALTITUDE -> {
                val altitude = value.toInt()
                if (altitude <= 0) {
                    CommandResult(MavlinkCommandOutcome.DENIED, "RTH altitude must be positive")
                } else {
                    // Waited on rather than fired and forgotten, because the PARAM_VALUE sent back
                    // immediately afterwards is meant to report what the parameter now holds. Without
                    // the wait it reports the value from before the write, and a ground station
                    // correctly concludes the write did not take.
                    awaitParameterWrite { done -> DroneController.setRTHAltitude(altitude, done) }
                }
            }

            else -> {
                Log.d(TAG, "Refusing write to read-only parameter $name")
                CommandResult(MavlinkCommandOutcome.DENIED, "$name is read-only")
            }
        }

    /**
     * Run an asynchronous parameter write and wait, briefly, for the aircraft to confirm it.
     *
     * Bounded so a key the aircraft never answers cannot wedge the endpoint's receive thread —
     * a timeout is reported as a failure, which is what it is.
     */
    private fun awaitParameterWrite(write: ((Boolean) -> Unit) -> Unit): CommandResult {
        val latch = CountDownLatch(1)
        val succeeded =
            java.util.concurrent.atomic
                .AtomicBoolean(false)
        write { ok ->
            succeeded.set(ok)
            latch.countDown()
        }
        val answered = latch.await(ACTION_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        return when {
            !answered -> CommandResult(MavlinkCommandOutcome.FAILED, "Aircraft did not answer")
            succeeded.get() -> CommandResult(MavlinkCommandOutcome.ACCEPTED)
            else -> CommandResult(MavlinkCommandOutcome.FAILED, "Aircraft refused the write")
        }
    }
}
