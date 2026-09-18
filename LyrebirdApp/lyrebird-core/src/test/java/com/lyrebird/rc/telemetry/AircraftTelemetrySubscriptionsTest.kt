package com.lyrebird.rc.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Subscription lifetime, which is what lets the runtime's telemetry keep updating while screens
 * come and go: each subscriber owns a handle, and closing one screen's handle must not disturb
 * the other subscriber or the fan-out itself.
 */
class AircraftTelemetrySubscriptionsTest {
    private class Recorder : AircraftTelemetryListener {
        val events = mutableListOf<String>()

        override fun onReadingsChanged() {
            events += "readings"
        }

        override fun onFlyingChanged(flying: Boolean) {
            events += "flying=$flying"
        }
    }

    @Test
    fun `each subscriber receives every event until its own handle closes`() {
        val subscriptions = AircraftTelemetrySubscriptions()
        val first = Recorder()
        val second = Recorder()
        val firstHandle = subscriptions.add(first)
        subscriptions.add(second)

        subscriptions.dispatch { it.onFlyingChanged(true) }
        assertEquals(listOf("flying=true"), first.events)
        assertEquals(listOf("flying=true"), second.events)

        firstHandle.close()
        subscriptions.dispatch { it.onFlyingChanged(false) }

        // The first screen detached; the second — and any later dispatches — carry on.
        assertEquals(listOf("flying=true"), first.events)
        assertEquals(listOf("flying=true", "flying=false"), second.events)
    }

    @Test
    fun `closing a handle twice removes nothing else`() {
        val subscriptions = AircraftTelemetrySubscriptions()
        val first = Recorder()
        val second = Recorder()
        val firstHandle = subscriptions.add(first)
        subscriptions.add(second)

        firstHandle.close()
        firstHandle.close()

        assertEquals(1, subscriptions.size)
        subscriptions.dispatch { it.onReadingsChanged() }
        assertTrue(first.events.isEmpty())
        assertEquals(listOf("readings"), second.events)
    }

    @Test
    fun `a listener overriding nothing is still a valid subscriber`() {
        val subscriptions = AircraftTelemetrySubscriptions()
        subscriptions.add(object : AircraftTelemetryListener {})

        // The default methods exist so a consumer only has to implement what it cares about;
        // dispatching must not require it to react.
        subscriptions.dispatch {
            it.onReadingsChanged()
            it.onAltitudeChanged(1.0)
            it.onGimbalPitchChanged(-1.0)
            it.onFlyingChanged(true)
            it.onFlightModeChanged(AircraftFlightMode.GO_HOME)
            it.onSatelliteCountChanged(12)
        }
    }

    @Test
    fun `clear detaches every subscriber`() {
        val subscriptions = AircraftTelemetrySubscriptions()
        val first = Recorder()
        subscriptions.add(first)
        subscriptions.add(Recorder())

        subscriptions.clear()

        assertEquals(0, subscriptions.size)
        subscriptions.dispatch { it.onReadingsChanged() }
        assertTrue(first.events.isEmpty())
    }
}
