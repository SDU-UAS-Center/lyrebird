package com.lyrebird.rc.telemetry

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The telemetry frame's shape, pinned.
 *
 * `TelemetryCoordinator` used to hold SDK objects as `Any?` and interpolate their `toString()`, so
 * the wire format was decided by DJI and could change without a line of Lyrebird code changing —
 * and nothing here could tell. Every consumer (the Python ground station, the video-test dashboard,
 * the ROS packages) parses these as nested objects, so this test is the thing that notices if the
 * shape moves.
 */
class TelemetryWireFixtureTest {

    private fun coordinator() = TelemetryCoordinator().apply {
        droneName = "lb_test"
        speed = VelocityNedMps(northMps = 1.0, eastMps = -0.5, downMps = 0.25)
        attitude = AttitudeDeg(rollDeg = 1.5, pitchDeg = -12.0, yawDeg = 180.0)
        location = GeoPosition(
            latitudeDeg = 55.47,
            longitudeDeg = 10.32,
            altitudeAslM = 30.0,
            altitudeAglM = 12.0,
        )
        // The wire takes AGL from this field, not from the position object's altitudeAglM — the
        // two are set together in FlightDeckActivity. See the note in the commit: geo position's
        // AGL is currently duplicated and should be reconciled to one source.
        altitudeAGL = 12.0
        gimbalAttitude = AttitudeDeg(rollDeg = 0.0, pitchDeg = -45.0, yawDeg = 90.0)
        gimbalJointAttitude = AttitudeDeg(rollDeg = 0.0, pitchDeg = -30.0, yawDeg = 10.0)
        homeLocation = GeoPoint(latitudeDeg = 55.46, longitudeDeg = 10.31)
        batteryLevel = 91
        heading = 180.0
    }

    @Test
    fun `the frame is valid JSON with the objects consumers parse`() {
        val json = JSONObject(coordinator().buildTelemetryJson())

        val location = json.getJSONObject("location")
        assertEquals(55.47, location.getDouble("latitude"), 1e-9)
        assertEquals(10.32, location.getDouble("longitude"), 1e-9)
        assertEquals(30.0, location.getDouble("altitude"), 1e-9)

        val attitude = json.getJSONObject("attitude")
        assertEquals(1.5, attitude.getDouble("roll"), 1e-9)
        assertEquals(-12.0, attitude.getDouble("pitch"), 1e-9)
        assertEquals(180.0, attitude.getDouble("yaw"), 1e-9)

        // The SDK's own names for north/east/down, which the dashboard reads as speed.x/y/z.
        val speed = json.getJSONObject("speed")
        assertEquals(1.0, speed.getDouble("x"), 1e-9)
        assertEquals(-0.5, speed.getDouble("y"), 1e-9)
        assertEquals(0.25, speed.getDouble("z"), 1e-9)

        assertEquals(-45.0, json.getJSONObject("gimbalAttitude").getDouble("pitch"), 1e-9)
        assertEquals(-30.0, json.getJSONObject("gimbalJointAttitude").getDouble("pitch"), 1e-9)

        val home = json.getJSONObject("homeLocation")
        assertEquals(55.46, home.getDouble("latitude"), 1e-9)
        assertFalse("homeLocation carries no altitude", home.has("altitude"))

        assertEquals("lb_test", json.getString("droneName"))
        assertEquals(91, json.getInt("batteryLevel"))
        // AGL travels as its own top-level field, not inside the position object.
        assertEquals(12.0, json.getDouble("altitude"), 1e-9)
    }

    @Test
    fun `an unreported reading serialises as null rather than a invented zero`() {
        val json = JSONObject(TelemetryCoordinator().buildTelemetryJson())
        assertTrue(json.isNull("location"))
        assertTrue(json.isNull("attitude"))
        assertTrue(json.isNull("speed"))
        assertTrue(json.isNull("homeLocation"))
        assertTrue(json.isNull("lrfTarget"))
        assertTrue(json.isNull("gimbalAttitude"))
    }

    @Test
    fun `a laser target carries the three fields it has`() {
        val json = JSONObject(
            coordinator()
                .apply {
                    lrfTarget = GeoPoint3D(latitudeDeg = 46.518, longitudeDeg = 6.566, altitudeM = 12.5)
                }
                .buildTelemetryJson()
        )
        val target = json.getJSONObject("lrfTarget")
        assertEquals(46.518, target.getDouble("latitude"), 1e-9)
        assertEquals(6.566, target.getDouble("longitude"), 1e-9)
        assertEquals(12.5, target.getDouble("altitude"), 1e-9)
        assertEquals(3, target.length())
    }

    @Test
    fun `the trimmed gap frame still carries what MAVLink cannot`() {
        // Gap mode deliberately omits the MAVLink-covered fields, so the shape pin above does not
        // apply to it — but it must stay valid JSON and keep its marker. phoneLocation is the
        // *phone's* own GPS, which is why it is here at all and not in the MAVLink-covered set.
        val json = JSONObject(coordinator().buildGapTelemetryJson())
        assertEquals("gap", json.getString("telemetryMode"))
        val phone = json.getJSONObject("phoneLocation")
        assertTrue(phone.has("latitude"))
        assertTrue(phone.has("longitude"))
        assertTrue(phone.has("wifiRssi"))
        // The aircraft's own position is MAVLink's job, so it is not repeated here.
        assertFalse(json.has("location"))
    }
}
