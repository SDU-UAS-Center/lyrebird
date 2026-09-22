package com.lyrebird.rc.server

import android.content.SharedPreferences
import com.lyrebird.rc.perception.ObstacleGuard
import com.lyrebird.rc.perception.ObstacleSensorPort
import java.lang.ref.WeakReference

data class ObstacleRuntimeMotion(
    val velocityNorthMps: Double,
    val velocityEastMps: Double,
    val velocityDownMps: Double,
    val headingDeg: Double,
)

data class ObstacleRuntimeBrake(
    val reason: String,
    val clearanceM: Double,
    val requiredM: Double,
    /** Where the obstacle was seen, relative to the nose. Logged; nothing steers on it. */
    val bearingFromNoseDeg: Double,
)

internal interface ObstacleRuntimeCallbacks {
    /**
     * The aircraft's current motion, or null when the runtime cannot say — a detached UI must not
     * be answered with zeroes, because zeroes read as "hovering" to the guard.
     */
    fun runtimeObstacleMotion(): ObstacleRuntimeMotion?

    /** Whether the app is commanding autonomous motion right now. */
    fun runtimeAutonomousMotionActive(): Boolean

    /** Whether the physical RC pilot has the sticks. */
    fun runtimeManualOverrideActive(): Boolean

    /** Cancel the app's active control loop, the same stop [ObstacleGuard] applies on a brake. */
    fun runtimeStopAutonomousMotion()

    /**
     * The guard stopped the aircraft. Reported to the process, which owns the flight log; the
     * screen is told separately, by [ObstacleGuardUi].
     */
    fun runtimeOnObstacleBrake(event: ObstacleRuntimeBrake)
}

/**
 * What a screen does about a brake: refresh what it shows about the aircraft.
 *
 * Deliberately not part of [ObstacleRuntimeCallbacks]: that interface is the process's — the
 * motion, the authority questions and the flight log — and it has to keep answering when no
 * screen exists. A screen only draws, and the detach here is identity-guarded like the bridges.
 */
internal interface ObstacleGuardUi {
    fun obstacleGuardOnBrake(event: ObstacleRuntimeBrake)
}

internal object ProcessObstacleRuntimeRegistry {
    private val runtime = ProcessObstacleRuntime()

    fun attach(
        preferences: SharedPreferences,
        callbacks: ObstacleRuntimeCallbacks,
        sensor: ObstacleSensorPort,
    ) = runtime.attach(preferences, callbacks, sensor)

    fun start() = runtime.start()

    fun stop() = runtime.stop()

    fun detach(callbacks: ObstacleRuntimeCallbacks) = runtime.detach(callbacks)

    /** Attaches the screen that draws brake state; the provider answers stay process-owned. */
    fun attachUi(ui: ObstacleGuardUi) = runtime.attachUi(ui)

    fun detachUi(ui: ObstacleGuardUi) = runtime.detachUi(ui)

    fun isEnabled() = runtime.isEnabled()

    fun isRunning() = runtime.isRunning()

    fun isLatched() = runtime.isLatched()
}

private class ProcessObstacleRuntime {
    private var preferences: SharedPreferences? = null
    private var callbacksRef: WeakReference<ObstacleRuntimeCallbacks> = WeakReference(null)
    private var uiRef: WeakReference<ObstacleGuardUi> = WeakReference(null)

    @Synchronized
    fun attach(
        preferences: SharedPreferences,
        callbacks: ObstacleRuntimeCallbacks,
        sensor: ObstacleSensorPort,
    ) {
        this.preferences = preferences
        callbacksRef = WeakReference(callbacks)
        ObstacleGuard.sensorPort = sensor
        ObstacleGuard.motionProvider = {
            callbacksRef.get()?.runtimeObstacleMotion()?.let {
                ObstacleGuard.Motion(
                    velocityNorthMps = it.velocityNorthMps,
                    velocityEastMps = it.velocityEastMps,
                    velocityDownMps = it.velocityDownMps,
                    headingDeg = it.headingDeg,
                )
            }
        }
        ObstacleGuard.autonomousMotionProvider = { callbacksRef.get()?.runtimeAutonomousMotionActive() }
        ObstacleGuard.manualOverrideProvider = { callbacksRef.get()?.runtimeManualOverrideActive() }
        ObstacleGuard.stopMotion = { callbacksRef.get()?.runtimeStopAutonomousMotion() }
        ObstacleGuard.onBrake = { event ->
            val brake =
                ObstacleRuntimeBrake(
                    reason = event.reason.name,
                    clearanceM = event.clearanceM,
                    requiredM = event.requiredM,
                    bearingFromNoseDeg = event.bearingFromNoseDeg,
                )
            callbacksRef.get()?.runtimeOnObstacleBrake(brake)
            uiRef.get()?.obstacleGuardOnBrake(brake)
        }
    }

    @Synchronized
    fun attachUi(ui: ObstacleGuardUi) {
        uiRef = WeakReference(ui)
    }

    @Synchronized
    fun detachUi(ui: ObstacleGuardUi) {
        if (uiRef.get() === ui) uiRef = WeakReference(null)
    }

    fun start() {
        val prefs = preferences ?: return
        ObstacleGuard.start(prefs)
    }

    fun stop() {
        ObstacleGuard.stop()
    }

    @Synchronized
    fun detach(callbacks: ObstacleRuntimeCallbacks) {
        if (callbacksRef.get() === callbacks) callbacksRef.clear()
    }

    fun isEnabled(): Boolean = preferences?.let(ObstacleGuard::isEnabled) == true

    fun isRunning(): Boolean = ObstacleGuard.isRunning

    fun isLatched(): Boolean = ObstacleGuard.isLatched
}

internal object ProcessCaptureExecutorRegistry {
    private val executor =
        java.util.concurrent.Executors.newSingleThreadExecutor { task ->
            Thread(task, "lyrebird-capture").apply { isDaemon = true }
        }

    fun executor() = executor
}
