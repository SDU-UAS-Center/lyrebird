package com.lyrebird.rc.telemetry

/**
 * A controllable [AircraftPlatform] for tests that need injected aircraft state without an SDK.
 *
 * The real sources only exist in the V5 flavor and need the DJI runtime to initialize, so this
 * double is what lets a test drive the shared consumers — subscription lifetime, projections and
 * serializers — with the same neutral types the adapters produce.
 */
class FakeAircraftPlatform : AircraftPlatform {
    override val telemetry = FakeAircraftTelemetrySource()
}

/** In-memory [AircraftTelemetrySource]: publish a reading, and every subscriber sees it. */
class FakeAircraftTelemetrySource : AircraftTelemetrySource {
    private val subscriptions = AircraftTelemetrySubscriptions()

    @Volatile private var readings = AircraftReadings()

    @Volatile private var state =
        AircraftState(
            readings = readings,
            connected = false,
            connectionGeneration = 0L,
            observedAtMillis = 0L,
        )

    override fun read(): AircraftReadings = readings

    override fun readState(): AircraftState = state

    override fun subscribe(listener: AircraftTelemetryListener): AutoCloseable = subscriptions.add(listener)

    /**
     * Publish a full snapshot. A false-to-true connection transition bumps the generation, the
     * same rule the V5 source's [ConnectionGeneration] applies — late callbacks from the previous
     * connection are what the generation is for.
     */
    fun publish(
        readings: AircraftReadings,
        connected: Boolean = true,
        observedAtMillis: Long = System.currentTimeMillis(),
    ) {
        val generation =
            if (connected && !state.connected) {
                state.connectionGeneration + 1
            } else {
                state.connectionGeneration
            }
        this.readings = readings
        state =
            AircraftState(
                readings = readings,
                connected = connected,
                connectionGeneration = generation,
                observedAtMillis = observedAtMillis,
            )
        // Mirrors the real source: a location update reports readings and the altitude the
        // altitude view consumes, so a consumer can tell which subscription carried it.
        subscriptions.dispatch { it.onReadingsChanged() }
        subscriptions.dispatch { it.onAltitudeChanged(readings.location.altitudeAslM) }
    }

    fun publishFlying(flying: Boolean) = subscriptions.dispatch { it.onFlyingChanged(flying) }

    fun publishFlightMode(mode: AircraftFlightMode) = subscriptions.dispatch { it.onFlightModeChanged(mode) }

    fun publishSatelliteCount(count: Int) = subscriptions.dispatch { it.onSatelliteCountChanged(count) }
}
