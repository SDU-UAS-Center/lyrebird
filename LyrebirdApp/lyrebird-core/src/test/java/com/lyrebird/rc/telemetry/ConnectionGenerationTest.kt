package com.lyrebird.rc.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionGenerationTest {
    @Test
    fun generationOnlyAdvancesWhenAConnectionStarts() {
        val generations = ConnectionGeneration()

        assertEquals(ConnectionGeneration.State(false, 0), generations.current())
        assertEquals(ConnectionGeneration.State(true, 1), generations.update(true))
        assertEquals(ConnectionGeneration.State(true, 1), generations.update(true))
        assertEquals(ConnectionGeneration.State(false, 1), generations.update(false))
        assertEquals(ConnectionGeneration.State(true, 2), generations.update(true))
    }

    @Test
    fun disconnectedStateIsNotAValidConnectedGeneration() {
        val generations = ConnectionGeneration()
        val disconnected = generations.update(false)

        assertFalse(disconnected.connected)
        assertTrue(disconnected.generation == 0L)
    }
}
