package com.lyrebird.rc.telemetry

/**
 * A pure Kotlin representation of mock telemetry values.
 * Free from any Android/DJI SDK dependencies, ensuring host JVM unit-testability.
 */
data class MockTelemetrySnapshot(
    val velocity: String,
    val heading: Double,
    val attitude: String,
    val location: String,
    val altitudeAGL: Double,
    val gimbalAttitude: String,
    val batteryPercent: Int,
    val satelliteCount: Int,
    val flightMode: String,
    val isFlying: Boolean,
    val locationLatitude: Double,
    val locationLongitude: Double
)
