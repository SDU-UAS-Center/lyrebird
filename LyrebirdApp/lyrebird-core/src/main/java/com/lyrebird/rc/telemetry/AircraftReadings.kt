package com.lyrebird.rc.telemetry

interface AircraftTelemetrySource {
    fun read(): AircraftReadings
}

data class AircraftReadings(
    val location: GeoPosition = GeoPosition(0.0, 0.0, 0.0),
    val heightAboveTakeoffM: Double = 0.0,
    val velocity: VelocityNedMps = VelocityNedMps(0.0, 0.0, 0.0),
    val attitude: AttitudeDeg = AttitudeDeg(0.0, 0.0, 0.0),
    val headingDeg: Double = 0.0,
    val home: GeoPoint = GeoPoint(0.0, 0.0),
    val gimbal: AttitudeDeg = AttitudeDeg(0.0, 0.0, 0.0),
    val gimbalJoint: AttitudeDeg = AttitudeDeg(0.0, 0.0, 0.0),
    val zoom: CameraZoomState = CameraZoomState(-1, -1, -1, 1.0),
    val battery: BatteryState = BatteryState(-1, 0, 0, 0, 0, 0),
    val satelliteCount: Int = -1,
    val flightMode: String = "UNKNOWN",
    val flying: Boolean = false,
    val recording: Boolean = false,
    val readyToTakeoff: Boolean = false,
    val takeoffBlockReason: String = "UNKNOWN",
    val remainingCharge: Int = 0,
    val maxReturnRadiusM: Double = 0.0,
    val batteryNeededToGoHomePercent: Int = 0,
    val batteryNeededToLandPercent: Int = 0,
)
