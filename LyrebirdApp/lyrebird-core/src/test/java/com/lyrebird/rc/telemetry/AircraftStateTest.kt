package com.lyrebird.rc.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AircraftStateTest {
    @Test
    fun stateCarriesLifecycleMetadataAlongsideReadings() {
        val readings = AircraftReadings(flying = true, flightMode = "GPS_NORMAL")
        val state = AircraftState(readings, connected = true, connectionGeneration = 4L, observedAtMillis = 1234L)

        assertTrue(state.connected)
        assertEquals(4L, state.connectionGeneration)
        assertEquals(1234L, state.observedAtMillis)
        assertTrue(state.readings.flying)
        assertEquals("GPS_NORMAL", state.readings.flightMode)
    }

    @Test
    fun disconnectedStateCanCarryTheLastGenerationWithoutClaimingFreshData() {
        val state = AircraftState(AircraftReadings(), connected = false, connectionGeneration = 4L, observedAtMillis = 1234L)

        assertFalse(state.connected)
        assertEquals(4L, state.connectionGeneration)
        assertEquals(1234L, state.observedAtMillis)
    }
}
