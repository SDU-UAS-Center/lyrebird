package com.lyrebird.rc.telemetry

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The B1 gate, on the shape the runtime actually serves: injected neutral state — not an SDK
 * class — reaches the existing serializers with the wire shape the consumers parse, and every
 * subscriber sees it. The applier here is deliberately a lambda: the production one
 * (`V5AircraftTelemetryProjection`) needs the DJI runtime, and the point of this test is that the
 * *contract* carries the state, not that the adapter wraps it.
 */
class FakeAircraftPlatformTest {
    private val platform = FakeAircraftPlatform()
    private val coordinator = TelemetryCoordinator()

    private val applied =
        platform.telemetry.subscribe(
            object : AircraftTelemetryListener {
                override fun onReadingsChanged() {
                    platform.telemetry.read().applyTo(coordinator)
                    coordinator.rebuildTelemetryCache()
                }
            },
        )

    @Test
    fun `injected fake state reaches the cached telemetry frame`() {
        platform.telemetry.publish(
            AircraftReadings(
                location = GeoPosition(latitudeDeg = 55.47, longitudeDeg = 10.32, altitudeAslM = 30.0),
                heightAboveTakeoffM = 12.0,
                velocity = VelocityNedMps(northMps = 1.0, eastMps = -0.5, downMps = 0.25),
                battery = BatteryState(91, 10, 20, 600, 80, 30),
                flying = true,
                satelliteCount = 21,
            ),
        )

        val json = JSONObject(coordinator.getTelemetryJson())
        assertEquals(55.47, json.getJSONObject("location").getDouble("latitude"), 1e-9)
        assertEquals(12.0, json.getDouble("altitude"), 1e-9)
        assertEquals(1.0, json.getJSONObject("speed").getDouble("x"), 1e-9)
        assertEquals(91, json.getInt("batteryLevel"))
        assertEquals(21, json.getInt("satelliteCount"))
        // The unsampled fields keep their legacy defaults rather than inventing a measurement.
        assertEquals("UNKNOWN", json.getString("flightMode"))
    }

    @Test
    fun `closing one subscription stops only that subscriber`() {
        val altitudes = mutableListOf<Double>()
        val secondHandle =
            platform.telemetry.subscribe(
                object : AircraftTelemetryListener {
                    override fun onAltitudeChanged(altitudeAslM: Double) {
                        altitudes += altitudeAslM
                    }
                },
            )

        platform.telemetry.publish(AircraftReadings(location = GeoPosition(1.0, 2.0, 30.0)))
        applied.close()
        platform.telemetry.publish(AircraftReadings(location = GeoPosition(1.0, 2.0, 99.0)))

        // The closed subscriber no longer updates the frame ...
        assertEquals(
            30.0,
            JSONObject(coordinator.getTelemetryJson()).getJSONObject("location").getDouble("altitude"),
            1e-9,
        )
        // ... while the surviving one keeps receiving.
        assertEquals(listOf(30.0, 99.0), altitudes)
        secondHandle.close()
    }

    @Test
    fun `a reconnect bumps the generation`() {
        platform.telemetry.publish(AircraftReadings(), connected = true)
        val first = platform.telemetry.readState().connectionGeneration

        platform.telemetry.publish(AircraftReadings(), connected = false)
        assertEquals(first, platform.telemetry.readState().connectionGeneration)
        assertFalse(platform.telemetry.readState().connected)

        platform.telemetry.publish(AircraftReadings(), connected = true)
        assertEquals(first + 1, platform.telemetry.readState().connectionGeneration)
        assertTrue(platform.telemetry.readState().connected)
    }
}
