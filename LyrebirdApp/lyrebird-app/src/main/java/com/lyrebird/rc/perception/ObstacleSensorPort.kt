package com.lyrebird.rc.perception

/**
 * The aircraft's obstacle sensors, as the guard sees them: a stream of converted sweeps.
 *
 * Implemented per SDK generation (V5: DJI's PerceptionManager). The port exists so [ObstacleGuard]
 * — which decides whether a sweep may stop the aircraft — carries no SDK dependency, and so a
 * sweep can be fed to it in a test without a perception stack.
 */
internal interface ObstacleSensorPort {
    /** Subscribe to sweeps. [onSweep] may fire on any thread, several times a second. */
    fun start(onSweep: (ObstacleReading) -> Unit)

    /** Unsubscribe. Safe to call when not subscribed. */
    fun stop()
}
