package com.lyrebird.rc.telemetry

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The neutral serialisers must reproduce the wire objects the SDK's `toString()` produced.
 *
 * The interesting cases are the ones where a naive implementation would change bytes: an integral
 * double, a value that was never reported, and a non-finite number.
 */
class TelemetryWireTest {
    @Test
    fun `an integral double is written without a trailing point`() {
        // org.json strips these, and the SDK went through org.json, so `30` is what the wire has.
        assertEquals("30", telemetryNumber(30.0))
        assertEquals("0", telemetryNumber(0.0))
        assertEquals("-45", telemetryNumber(-45.0))
    }

    @Test
    fun `a fractional double keeps its fraction`() {
        assertEquals("55.47", telemetryNumber(55.47))
        assertEquals("-11.3", telemetryNumber(-11.3))
    }

    @Test
    fun `a non-finite value is null rather than a lost frame`() {
        assertEquals("null", telemetryNumber(Double.NaN))
        assertEquals("null", telemetryNumber(Double.POSITIVE_INFINITY))
        assertEquals("null", telemetryNumber(Double.NEGATIVE_INFINITY))
    }

    @Test
    fun `a position serialises to the keys consumers already parse`() {
        val json =
            JSONObject(
                GeoPosition(latitudeDeg = 55.47, longitudeDeg = 10.32, altitudeAslM = 30.0, altitudeAglM = 12.0)
                    .toWireJson(),
            )
        assertEquals(55.47, json.getDouble("latitude"), 1e-9)
        assertEquals(10.32, json.getDouble("longitude"), 1e-9)
        assertEquals(30.0, json.getDouble("altitude"), 1e-9)
        // AGL travels in its own top-level field, not inside the position object.
        assertEquals(3, json.length())
    }

    @Test
    fun `an unreported reading is null, not zero`() {
        val nothing: GeoPosition? = null
        assertEquals("null", nothing.toWireJson())
        val noPoint: GeoPoint? = null
        assertEquals("null", noPoint.toWireJson())
        val noAttitude: AttitudeDeg? = null
        assertEquals("null", noAttitude.toWireJson())
        val noVelocity: VelocityNedMps? = null
        assertEquals("null", noVelocity.toWireJson())
    }

    @Test
    fun `attitude uses the SDK key names`() {
        val json = JSONObject(AttitudeDeg(rollDeg = 1.5, pitchDeg = -12.0, yawDeg = 180.0).toWireJson())
        assertEquals(1.5, json.getDouble("roll"), 1e-9)
        assertEquals(-12.0, json.getDouble("pitch"), 1e-9)
        assertEquals(180.0, json.getDouble("yaw"), 1e-9)
    }

    @Test
    fun `velocity keeps the SDK's xyz names for north east down`() {
        val json = JSONObject(VelocityNedMps(northMps = 1.0, eastMps = -0.5, downMps = 0.25).toWireJson())
        assertEquals(1.0, json.getDouble("x"), 1e-9)
        assertEquals(-0.5, json.getDouble("y"), 1e-9)
        assertEquals(0.25, json.getDouble("z"), 1e-9)
    }

    @Test
    fun `a point omits altitude, which it does not carry`() {
        val json = JSONObject(GeoPoint(latitudeDeg = 46.518, longitudeDeg = 6.566).toWireJson())
        assertEquals(2, json.length())
        assertTrue(json.has("latitude"))
        assertTrue(json.has("longitude"))
    }
}
