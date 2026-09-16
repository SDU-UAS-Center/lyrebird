package com.lyrebird.rc.server

import android.content.SharedPreferences
import com.lyrebird.rc.perception.ObstacleGuard
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
)

internal interface ObstacleRuntimeCallbacks {
    fun runtimeObstacleMotion(): ObstacleRuntimeMotion

    fun runtimeOnObstacleBrake(event: ObstacleRuntimeBrake)
}

internal object ProcessObstacleRuntimeRegistry {
    private val runtime = ProcessObstacleRuntime()

    fun attach(
        preferences: SharedPreferences,
        callbacks: ObstacleRuntimeCallbacks,
    ) = runtime.attach(preferences, callbacks)

    fun start() = runtime.start()

    fun stop() = runtime.stop()

    fun detach(callbacks: ObstacleRuntimeCallbacks) = runtime.detach(callbacks)

    fun isEnabled() = runtime.isEnabled()

    fun isRunning() = runtime.isRunning()

    fun isLatched() = runtime.isLatched()
}

private class ProcessObstacleRuntime {
    private var preferences: SharedPreferences? = null
    private var callbacksRef: WeakReference<ObstacleRuntimeCallbacks> = WeakReference(null)

    @Synchronized
    fun attach(
        preferences: SharedPreferences,
        callbacks: ObstacleRuntimeCallbacks,
    ) {
        this.preferences = preferences
        callbacksRef = WeakReference(callbacks)
        ObstacleGuard.motionProvider = {
            callbacksRef.get()?.runtimeObstacleMotion()?.let {
                ObstacleGuard.Motion(
                    velocityNorthMps = it.velocityNorthMps,
                    velocityEastMps = it.velocityEastMps,
                    velocityDownMps = it.velocityDownMps,
                    headingDeg = it.headingDeg,
                )
            } ?: ObstacleGuard.Motion(0.0, 0.0, 0.0, 0.0)
        }
        ObstacleGuard.onBrake = { event ->
            callbacksRef.get()?.runtimeOnObstacleBrake(
                ObstacleRuntimeBrake(
                    reason = event.reason.name,
                    clearanceM = event.clearanceM,
                    requiredM = event.requiredM,
                ),
            )
        }
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
