package com.lyrebird.rc.telemetry

/** A neutral aircraft snapshot plus the lifecycle metadata needed to reject stale consumers. */
data class AircraftState(
    val readings: AircraftReadings,
    val connected: Boolean,
    val connectionGeneration: Long,
    val observedAtMillis: Long,
)
