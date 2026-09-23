package com.lyrebird.rc.controller

import android.os.Handler
import android.util.Log
import com.lyrebird.rc.telemetry.AircraftTelemetrySource
import com.lyrebird.rc.telemetry.GeoPosition

/** The gimbal actions ROI tracking needs; an adapter owns the SDK's mode values and rotation shape. */
internal interface RoiGimbalPort {
    /** Free the gimbal yaw for tracking, remembering the mode to restore. */
    fun freeYawForTracking()

    /** Restore the mode saved by [freeYawForTracking]; a no-op when nothing was saved. */
    fun restoreYawMode()

    /** Aim the joint by a relative nudge in degrees. */
    fun rotateJoint(
        relativePitchDeg: Double,
        relativeYawDeg: Double,
    )
}

/** Schedules the tracking tick; the app posts to its main looper, tests tick by hand. */
internal interface RoiTicker {
    fun post(
        delayMs: Long,
        action: () -> Unit,
    )

    fun cancel()
}

/** [RoiTicker] on a main-looper handler. */
internal class HandlerRoiTicker(
    private val handler: Handler,
) : RoiTicker {
    @Volatile
    private var pending: Runnable? = null

    override fun post(
        delayMs: Long,
        action: () -> Unit,
    ) {
        cancel()
        val runnable =
            Runnable {
                pending = null
                action()
            }
        pending = runnable
        handler.postDelayed(runnable, delayMs)
    }

    override fun cancel() {
        pending?.let { handler.removeCallbacks(it) }
        pending = null
    }
}

/**
 * Keeps the gimbal pointed at a fixed ground position while the aircraft moves.
 *
 * The geometry lives in [RoiControl]; this owns the session: free the gimbal yaw on start so the
 * airframe heading no longer drags the camera off target, aim every [TRACK_INTERVAL_MS], and
 * restore the mode the operator had on stop. Deliberately SDK-free behind its two ports, so the
 * cadence, the dead band, the mode restore and the stop-without-start cases are all testable.
 */
internal class RoiTracker(
    private val gimbal: RoiGimbalPort,
    private val telemetry: () -> AircraftTelemetrySource,
    private val ticker: RoiTicker,
) {
    companion object {
        private const val TAG = "LyrebirdRoi"
        private const val TRACK_INTERVAL_MS = 200L
        private const val DEADBAND_DEG = 0.5
        private const val MAX_STEP_DEG = 15.0
    }

    @Volatile
    private var target: GeoPosition? = null

    @Volatile
    private var running = false

    fun start(
        latitudeDeg: Double,
        longitudeDeg: Double,
        altitudeM: Double,
    ) {
        target = GeoPosition(latitudeDeg, longitudeDeg, altitudeM)
        // A start while tracking is a target move, not a new session: re-freeing the yaw would
        // overwrite the saved mode with FREE and lose what the operator had.
        if (running) return
        running = true
        gimbal.freeYawForTracking()
        ticker.post(0L) { tick() }
        Log.i(TAG, "ROI tracking $latitudeDeg, $longitudeDeg at ${altitudeM}m")
    }

    fun stop() {
        if (!running) return
        target = null
        running = false
        ticker.cancel()
        gimbal.restoreYawMode()
        Log.i(TAG, "ROI tracking cleared")
    }

    private fun tick() {
        val aim = target ?: return
        trackOnce(aim)
        ticker.post(TRACK_INTERVAL_MS) { tick() }
    }

    private fun trackOnce(target: GeoPosition) {
        val readings = telemetry().read()
        val position = readings.location
        // (0, 0) is the SDK's unset position — a real place in the Gulf of Guinea — and aiming
        // from it would swing the gimbal to a bearing the aircraft never had.
        if (position.latitudeDeg == 0.0 && position.longitudeDeg == 0.0) return

        val aim =
            RoiControl.aimAt(
                bearingToRoiDeg =
                    RoiControl.bearingDeg(
                        position.latitudeDeg,
                        position.longitudeDeg,
                        target.latitudeDeg,
                        target.longitudeDeg,
                    ),
                groundDistanceM = position.distanceTo(target),
                altitudeAboveRoiM = position.altitudeAslM - target.altitudeAslM,
                headingDeg = readings.headingDeg,
                aircraftPitchDeg = readings.attitude.pitchDeg,
            )
        val joint = readings.gimbalJoint
        val pitchStep = RoiControl.step(aim.pitchDeg - joint.pitchDeg, DEADBAND_DEG, MAX_STEP_DEG)
        val yawStep =
            RoiControl.step(
                RoiControl.normalizeAngle(aim.yawDeg - joint.yawDeg),
                DEADBAND_DEG,
                MAX_STEP_DEG,
            )
        if (pitchStep == 0.0 && yawStep == 0.0) return

        gimbal.rotateJoint(pitchStep, yawStep)
    }
}
