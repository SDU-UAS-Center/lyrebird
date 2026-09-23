package com.lyrebird.rc.server

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.lyrebird.rc.controller.DroneController
import com.lyrebird.rc.logger.LyrebirdFlightLogger
import com.lyrebird.rc.perception.ObstacleGuard
import com.lyrebird.rc.perception.V5ObstacleSensorPort
import com.lyrebird.rc.settings.LyrebirdSettings
import com.lyrebird.rc.telemetry.ProcessTelemetryRuntimeRegistry

/**
 * The obstacle guard's process side: the answers it needs, and nothing that needs a screen.
 *
 * The guard watches the aircraft's sensors and cancels Lyrebird's own control loop when there is
 * no stopping distance left. Every answer it asks for — the motion, whether this app is flying,
 * whether a pilot has the sticks, how to stop — is process state, and the sensor port is an SDK
 * listener that never needed an activity either. Wiring them from the screen meant the guard
 * existed only while the screen did: a waypoint mission started from a ground station with the
 * screen closed was flown with the brake silently absent, because the providers answered "cannot
 * say" and the guard reads that as a no.
 *
 * So the guard now arms with the process and stays armed, and the screen only draws what happened
 * (see [ObstacleGuardUi]).
 *
 * DJI types are touched inside method bodies only: this object holds no DJI state, so it can be
 * loaded in host JVM tests.
 */
internal object ProcessObstacleGuard : ObstacleRuntimeCallbacks {
    private const val TAG = "LyrebirdObstacleGuard"

    @Volatile private var preferences: SharedPreferences? = null

    @Volatile private var wired = false

    /**
     * Attach the guard runtime, once, and arm it if the operator has opted in.
     *
     * Idempotent, and safe with no aircraft in sight: [ObstacleGuard.start] itself does nothing
     * while the preference is off, so a device that never opted in does not even register the
     * sensor listener.
     */
    fun attach(context: Context) {
        val prefs =
            context.applicationContext.getSharedPreferences(
                LyrebirdSettings.PREFS_FILE,
                Context.MODE_PRIVATE,
            )
        preferences = prefs
        if (!wired) {
            wired = true
            // One sensor registration for the guard: the port owns the listener and removes it
            // again on stop, so a repeated attach must not leak a second one.
            ProcessObstacleRuntimeRegistry.attach(prefs, this, V5ObstacleSensorPort())
        }
        armIfEnabled()
    }

    /**
     * Arm the guard if the operator has opted in, refreshing the standoff.
     *
     * Called whenever the aircraft link comes up: the sensors are only reachable with the link,
     * and a guard that could not subscribe while the aircraft was asleep gets another chance here.
     * [ObstacleGuard.refresh] is the whole policy — start, stop, or re-read the margin.
     */
    fun armIfEnabled() {
        val prefs = preferences ?: return
        ObstacleGuard.refresh(prefs)
        Log.i(
            TAG,
            "Obstacle guard ${if (ObstacleGuard.isRunning) "armed" else "not armed"} " +
                "(enabled=${ObstacleGuard.isEnabled(prefs)})",
        )
    }

    /**
     * The aircraft's motion, or null when the link cannot say.
     *
     * Null is the answer that keeps the guard honest: zero velocity reads as a hovering aircraft
     * it may brake, and a link that is down is not a measurement of anything.
     */
    override fun runtimeObstacleMotion(): ObstacleRuntimeMotion? {
        val telemetry = ProcessTelemetryRuntimeRegistry.aircraftTelemetry()
        if (!telemetry.isConnected()) return null
        val speed = telemetry.getSpeed()
        return ObstacleRuntimeMotion(
            velocityNorthMps = speed.x,
            velocityEastMps = speed.y,
            velocityDownMps = speed.z,
            headingDeg = telemetry.getHeading(),
        )
    }

    override fun runtimeAutonomousMotionActive(): Boolean = DroneController.isAutonomousFlightActive

    override fun runtimeManualOverrideActive(): Boolean = DroneController.isManualOverrideActive

    override fun runtimeStopAutonomousMotion() {
        DroneController.cancelActiveControlLoop()
    }

    /**
     * Record the brake in the flight log, which outlives any screen.
     *
     * The brake itself has already been applied by the time this runs (the guard stops first and
     * reports after), so this is a record, not a decision.
     */
    override fun runtimeOnObstacleBrake(event: ObstacleRuntimeBrake) {
        LyrebirdFlightLogger.logStatus(
            "OBSTACLE_STOP ${event.reason} " +
                "clearance=${"%.1f".format(event.clearanceM)}m " +
                "required=${"%.1f".format(event.requiredM)}m " +
                "bearing=${"%.0f".format(event.bearingFromNoseDeg)}deg",
        )
    }
}
