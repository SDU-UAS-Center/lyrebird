package com.lyrebird.rc.telemetry

import com.lyrebird.rc.mavlink.MavlinkSnapshot
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AircraftTelemetryProjectionTest {
    private val readings = AircraftReadings(
        location = GeoPosition(55.2, 10.1, 128.5),
        heightAboveTakeoffM = 38.5,
        velocity = VelocityNedMps(4.0, -3.0, -2.0),
        attitude = AttitudeDeg(1.0, 2.0, 3.0),
        gimbal = AttitudeDeg(4.0, -45.0, 6.0),
        gimbalJoint = AttitudeDeg(7.0, -48.0, 9.0),
        home = GeoPoint(55.19, 10.09),
        battery = BatteryState(84, 10, 20, 600, 80, 30),
        zoom = CameraZoomState(24, 25, 26, 2.0),
        remainingCharge = 83,
        maxReturnRadiusM = 234.75,
        batteryNeededToGoHomePercent = 14,
        batteryNeededToLandPercent = 3,
        flightMode = "GPS_NORMAL",
        satelliteCount = 21,
        flying = true,
        recording = true,
    )

    @Test
    fun telemetrySurfacesUseTheSameUnitsAndAxes() {
        val coordinator = TelemetryCoordinator()
        readings.applyTo(coordinator)
        val wire = JSONObject(coordinator.buildTelemetryJson())
        val mavlink = readings.toMavlinkSnapshot(MavlinkSnapshot())
        val fleet = readings.toFleetBeacon("serial", "mini3", 42, true, "mini3", "relay")
        assertEquals(mavlink.altitudeAslM, wire.getJSONObject("location").getDouble("altitude"), 0.0)
        assertEquals(mavlink.altitudeAglM, wire.getDouble("altitude"), 0.0)
        assertEquals(mavlink.altitudeAslM, fleet.altitudeAslM, 0.0)
        assertEquals(mavlink.altitudeAglM, fleet.altitudeAglM, 0.0)
        assertEquals(90.0, mavlink.homeAltitudeAslM, 0.0)
        assertEquals(4.0, wire.getJSONObject("speed").getDouble("x"), 0.0)
        assertEquals(-3.0, mavlink.velocityEastMps, 0.0)
        assertEquals(-2.0, fleet.velocityDownMps, 0.0)
        assertEquals(-45.0, mavlink.gimbalPitchDeg, 0.0)
        assertEquals(-48.0, wire.getJSONObject("gimbalJointAttitude").getDouble("pitch"), 0.0)
        assertEquals(84, fleet.batteryPercent)
        assertEquals(110, mavlink.totalFlightTimeS)
        assertEquals(110, wire.getInt("totalTime"))
        assertEquals(234, wire.getInt("maxRadiusCanFlyAndGoHome"))
        assertEquals(234.75, mavlink.maxRadiusCanFlyAndGoHomeM, 0.0)
        assertEquals(25, mavlink.opticalFocalLengthMm)
        assertEquals(26, wire.getInt("hybridFl"))
    }

    @Test
    fun aircraftProjectionDoesNotOverwriteSessionAuthorityAndProgress() {
        val session = MavlinkSnapshot(
            droneName = "mini3", manualOverrideActive = true, armedCommanded = true, missionActive = true,
            homeSet = true, waypointSeq = 123L, waypointReached = true, lrfDistanceM = 45.0, httpPort = 8080,
        )
        val result = readings.toMavlinkSnapshot(session)
        assertTrue(result.manualOverrideActive)
        assertTrue(result.armedCommanded)
        assertTrue(result.missionActive)
        assertTrue(result.homeSet)
        assertTrue(result.waypointReached)
        assertEquals(123L, result.waypointSeq)
        assertEquals(45.0, result.lrfDistanceM!!, 0.0)
        assertEquals(8080, result.httpPort)
        assertEquals("mini3", result.droneName)
    }

    @Test
    fun unavailableValuesPreserveLegacyFallbacks() {
        val empty = AircraftReadings()
        val coordinator = TelemetryCoordinator()
        empty.applyTo(coordinator)
        val result = empty.toMavlinkSnapshot(MavlinkSnapshot())
        assertEquals(-1, result.batteryPercent)
        assertEquals(-1, coordinator.satelliteCount)
        assertEquals(-1, coordinator.zoomFl)
        assertEquals(1.0, coordinator.zoomRatio, 0.0)
        assertFalse(result.motorsRunning)
        assertFalse(result.homeCoordinatesValid)
    }
}