package com.lyrebird.rc.telemetry

import java.util.concurrent.CopyOnWriteArrayList

/**
 * A subscriber to aircraft readings, with the events this app actually consumes.
 *
 * One listener interface replaces the adapter's previous single-slot observer pair: the process
 * runtime and every attached screen subscribe independently and close only their own
 * subscription. Events are dispatched on the thread that produced them (an SDK callback thread
 * for key updates), so implementations that touch UI must post to their own handler — the same
 * rule the single-observer version had.
 */
interface AircraftTelemetryListener {
    /** A sampled value changed; call [AircraftTelemetrySource.readState] for the snapshot. */
    fun onReadingsChanged() {}

    fun onAltitudeChanged(altitudeAslM: Double) {}

    fun onGimbalPitchChanged(pitchDeg: Double) {}

    fun onFlyingChanged(flying: Boolean) {}

    fun onFlightModeChanged(mode: AircraftFlightMode) {}

    fun onSatelliteCountChanged(satelliteCount: Int) {}
}

/**
 * Fan-out over [AircraftTelemetryListener]s with idempotent disposal.
 *
 * Deliberately in core rather than in the adapter: subscription lifetime is what the bridge
 * tests need to exercise without an SDK — two screens attaching and detaching independently
 * while the runtime's own subscription stays put — and this fan-out holds no SDK types to do it.
 */
class AircraftTelemetrySubscriptions {
    private val listeners = CopyOnWriteArrayList<AircraftTelemetryListener>()

    val size: Int get() = listeners.size

    /** Registers [listener]; the returned handle removes only it, and may be closed more than once. */
    fun add(listener: AircraftTelemetryListener): AutoCloseable {
        listeners.add(listener)
        return AutoCloseable { listeners.remove(listener) }
    }

    fun dispatch(action: (AircraftTelemetryListener) -> Unit) {
        for (listener in listeners) action(listener)
    }

    fun clear() = listeners.clear()
}
