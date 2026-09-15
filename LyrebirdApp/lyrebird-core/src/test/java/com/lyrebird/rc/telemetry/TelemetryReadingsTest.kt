package com.lyrebird.rc.telemetry

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the neutral telemetry types promise.
 *
 * The interesting assertions are the negative ones: the whole reason these types exist is to tell
 * "the aircraft reported zero" apart from "nothing was reported", because DJI's unset sentinels
 * look exactly like real values.
 */
class TelemetryReadingsTest {
    private fun position(
        lat: Double,
        lon: Double,
    ) = GeoPosition(latitudeDeg = lat, longitudeDeg = lon, altitudeAslM = 0.0, altitudeAglM = 0.0)

    @Test
    fun `an unset position is not plausible`() {
        // (0, 0) is where DJI reports "no fix", and it is also a real coordinate in the Atlantic.
        // Treating it as a position produced a confident 2,559 km from a stationary aircraft.
        assertFalse(position(0.0, 0.0).isPlausible)
    }

    @Test
    fun `a real position is plausible, including one at zero latitude`() {
        assertTrue(position(55.47, 10.32).isPlausible)
        // On the equator, or the Greenwich meridian: one coordinate legitimately zero.
        assertTrue(position(0.0, 10.32).isPlausible)
        assertTrue(position(55.47, 0.0).isPlausible)
    }

    @Test
    fun `coordinates outside the globe are not plausible`() {
        assertFalse(position(91.0, 10.0).isPlausible)
        assertFalse(position(10.0, 181.0).isPlausible)
        assertFalse(position(Double.NaN, 10.0).isPlausible)
    }

    @Test
    fun `a point with no fix is not plausible`() {
        assertFalse(GeoPoint(0.0, 0.0).isPlausible)
        assertTrue(GeoPoint(55.47, 10.32).isPlausible)
    }

    @Test
    fun `a reading records when it was taken and whether it was reported`() {
        val current = Reading(value = 42, observedAtMillis = 1_700_000_000_000L)
        assertTrue(current.reported)
        assertTrue(current.observedAtMillis > 0)

        // An explicit refusal: the sensor exists but had nothing to say (a laser that did not lock).
        val refused = Reading(value = 0, observedAtMillis = 1_700_000_000_000L, reported = false)
        assertFalse(refused.reported)
        // The value is still carried, so a consumer can log what came back; it just must not act
        // on it as though it were a measurement.
        assertTrue(refused.value == 0)
    }
}
