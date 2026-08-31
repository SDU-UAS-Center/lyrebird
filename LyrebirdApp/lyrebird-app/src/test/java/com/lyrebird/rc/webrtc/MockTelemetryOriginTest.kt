package com.lyrebird.rc.webrtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MockTelemetryOriginTest {
    @Test
    fun rejectsMissingLatitudeOrLongitude() {
        assertNull(MockTelemetryOrigin.fromNullable(null, 12.0))
        assertNull(MockTelemetryOrigin.fromNullable(55.0, null))
    }

    @Test
    fun rejectsZeroZeroCoordinates() {
        assertNull(MockTelemetryOrigin.fromNullable(0.0, 0.0))
    }

    @Test
    fun acceptsCoordinatesWhenEitherValueIsNonZero() {
        val origin = MockTelemetryOrigin.fromNullable(55.6761, 0.0)

        assertEquals(55.6761, origin!!.latitude, 0.0001)
        assertEquals(0.0, origin.longitude, 0.0001)
    }
}