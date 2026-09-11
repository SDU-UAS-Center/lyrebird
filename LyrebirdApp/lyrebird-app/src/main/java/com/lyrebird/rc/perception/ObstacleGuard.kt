package com.lyrebird.rc.perception

import android.content.SharedPreferences
import android.os.SystemClock
import android.util.Log
import com.lyrebird.rc.controller.DroneController
import dji.v5.manager.aircraft.perception.PerceptionManager
import dji.v5.manager.aircraft.perception.data.ObstacleData
import dji.v5.manager.aircraft.perception.listener.ObstacleDataListener

/**
 * Stops Lyrebird's autonomous motion when the aircraft's own sensors see something in the way.
 *
 * ## Why this exists
 *
 * DJI aircraft brake for obstacles on their own, and nothing here tries to replace that. The gap
 * is narrower and specific: when Lyrebird flies its PID sequencer through virtual stick, the
 * aircraft is being flown by stick input from an app, and what the vendor's avoidance does around
 * that input is neither documented nor guaranteed across the airframes this project supports. An
 * autonomous waypoint run is also the moment nobody has their hands on the controls.
 *
 * So the guard watches the same sensors the aircraft uses and, when the stopping distance is no
 * longer there, cancels the control loop Lyrebird is running. The aircraft holds position. That is
 * the entire behaviour.
 *
 * ## What it will not do
 *
 * It does not steer around anything, does not choose a new path, does not descend or climb away,
 * and does not touch the aircraft when a human is on the sticks. It has no authority it did not
 * already have: cancelling a loop this app started is the same thing the abort endpoint does.
 *
 * It also does not react to other drones. Peer positions arrive over an unauthenticated Wi-Fi
 * beacon that is one to two beacons stale and carries GPS error at both ends, which is fine for
 * telling a pilot where to look and nowhere near good enough to stop an aircraft with. That
 * remains deliberately out of scope; the fleet mesh stays advisory.
 *
 * ## What it cannot see
 *
 * Every airframe has blind arcs, and a sector inside one reports exactly what clear air reports.
 * Thin obstacles — wires, branches, guy lines, netting — are the classic case these sensors miss
 * entirely. This guard reduces the chance of flying into something detectable. It is not a reason
 * to fly somewhere you would not have flown without it.
 */
internal object ObstacleGuard {

    private const val TAG = "LyrebirdObstacle"

    /** Master switch. Default off: it changes flight behaviour, so it is opted into. */
    const val PREF_ENABLED = "lb_obstacle_guard_enabled"

    /** Standoff kept after stopping, in metres. */
    const val PREF_MARGIN_M = "lb_obstacle_guard_margin_m"

    /**
     * How long after a brake the guard stays latched.
     *
     * Once stopped, the aircraft is hovering in front of something it can still see, so an
     * unlatched guard would re-fire on every sweep and drown the log. The latch clears on its own
     * so a genuine second approach is still caught.
     */
    const val BRAKE_LATCH_MS = 5_000L

    /** What happened, for the status readout and the flight log. */
    data class BrakeEvent(
        val reason: BrakeReason,
        val clearanceM: Double,
        val requiredM: Double,
        val bearingFromNoseDeg: Double,
        val atElapsedMs: Long
    )

    /** Fired on the SDK's callback thread after the guard has stopped the aircraft. */
    var onBrake: ((BrakeEvent) -> Unit)? = null

    /** Supplies the aircraft's current velocity and heading. Set by the host before [start]. */
    var motionProvider: (() -> Motion)? = null

    /** Velocity in the NED frame and heading in degrees, as the rest of the app reports them. */
    data class Motion(
        val velocityNorthMps: Double,
        val velocityEastMps: Double,
        val velocityDownMps: Double,
        val headingDeg: Double
    )

    @Volatile
    var isRunning: Boolean = false
        private set

    /** The most recent sweep, for telemetry and diagnostics. */
    @Volatile
    var lastReading: ObstacleReading = ObstacleReading.EMPTY
        private set

    /** The most recent brake, or null if the guard has not fired this session. */
    @Volatile
    var lastBrake: BrakeEvent? = null
        private set

    @Volatile
    private var latchedUntilElapsedMs: Long = 0L

    @Volatile
    private var marginM: Double = ObstacleBrake.DEFAULT_MARGIN_M

    /** True while the aircraft is stopped by this guard rather than by anything else. */
    val isLatched: Boolean
        get() = SystemClock.elapsedRealtime() < latchedUntilElapsedMs

    private val listener = ObstacleDataListener { data -> onObstacleData(data) }

    fun isEnabled(prefs: SharedPreferences): Boolean = prefs.getBoolean(PREF_ENABLED, false)

    /**
     * Subscribe to the aircraft's obstacle sensors.
     *
     * Safe to call repeatedly. Does nothing when the preference is off, so a device that has not
     * opted in never even registers the listener.
     */
    fun start(prefs: SharedPreferences) {
        if (isRunning) return
        if (!isEnabled(prefs)) {
            Log.i(TAG, "Obstacle guard is off")
            return
        }
        marginM = prefs.getFloat(PREF_MARGIN_M, ObstacleBrake.DEFAULT_MARGIN_M.toFloat()).toDouble()
        runCatching {
            PerceptionManager.getInstance().addObstacleDataListener(listener)
            isRunning = true
            Log.i(TAG, "Obstacle guard armed with a ${marginM}m standoff")
        }.onFailure { error ->
            Log.w(TAG, "Could not subscribe to obstacle data: ${error.message}")
        }
    }

    fun stop() {
        if (!isRunning) return
        runCatching { PerceptionManager.getInstance().removeObstacleDataListener(listener) }
        isRunning = false
        latchedUntilElapsedMs = 0L
        lastReading = ObstacleReading.EMPTY
        Log.i(TAG, "Obstacle guard disarmed")
    }

    /** Re-read the preferences, for when the operator changes them mid-session. */
    fun refresh(prefs: SharedPreferences) {
        val wanted = isEnabled(prefs)
        if (wanted && !isRunning) {
            start(prefs)
        } else if (!wanted && isRunning) {
            stop()
        } else if (wanted) {
            marginM = prefs.getFloat(PREF_MARGIN_M, ObstacleBrake.DEFAULT_MARGIN_M.toFloat()).toDouble()
        }
    }

    /**
     * One sweep from the SDK. Runs on DJI's callback thread.
     *
     * Kept short and allocation-light: this fires several times a second on a device that is also
     * encoding and publishing video.
     */
    private fun onObstacleData(data: ObstacleData?) {
        if (data == null) return
        val reading = runCatching {
            ObstacleReading.fromMillimetres(
                horizontalMm = data.horizontalObstacleDistance.orEmpty(),
                angleIntervalDeg = data.horizontalAngleInterval.toDouble(),
                upwardMm = data.upwardObstacleDistance,
                downwardMm = data.downwardObstacleDistance,
                timestampMs = SystemClock.elapsedRealtime()
            )
        }.getOrNull() ?: return
        lastReading = reading

        if (isLatched) return
        val motion = motionProvider?.invoke() ?: return

        // The gate that matters. Only motion this app is commanding is motion this app may stop:
        // a pilot on the sticks is flying with the vendor's own avoidance and must never be
        // countermanded by a phone, and a native wayline is the flight controller's to brake.
        val decision = ObstacleBrake.evaluate(
            reading = reading,
            velocityNorthMps = motion.velocityNorthMps,
            velocityEastMps = motion.velocityEastMps,
            velocityDownMps = motion.velocityDownMps,
            headingDeg = motion.headingDeg,
            autonomousMotionActive = DroneController.isAutonomousFlightActive &&
                !DroneController.isManualOverrideActive,
            marginM = marginM
        )
        if (!decision.shouldBrake) return

        applyBrake(decision)
    }

    private fun applyBrake(decision: BrakeDecision) {
        latchedUntilElapsedMs = SystemClock.elapsedRealtime() + BRAKE_LATCH_MS
        val event = BrakeEvent(
            reason = decision.reason,
            clearanceM = decision.clearanceM,
            requiredM = decision.requiredM,
            bearingFromNoseDeg = decision.bearingFromNoseDeg,
            atElapsedMs = SystemClock.elapsedRealtime()
        )
        lastBrake = event
        Log.w(
            TAG,
            "Stopping: ${decision.reason} clearance=${"%.1f".format(decision.clearanceM)}m " +
                "required=${"%.1f".format(decision.requiredM)}m"
        )
        // Cancels the PID loop and zeroes the sticks, so the aircraft holds position. Virtual
        // stick is deliberately left enabled: dropping it would hand control back mid-air, and the
        // next command should be able to fly without re-arming anything.
        runCatching { DroneController.cancelActiveControlLoop() }
            .onFailure { error -> Log.e(TAG, "Could not stop the control loop: ${error.message}") }
        onBrake?.invoke(event)
    }
}
