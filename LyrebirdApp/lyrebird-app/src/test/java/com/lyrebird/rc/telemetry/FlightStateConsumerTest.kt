package com.lyrebird.rc.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Flight-state policy without an SDK: the consumer is shared, so takeoff/landing effects and the
 * RC return-to-home rule are exercised here rather than on the aircraft. The 10-second landing
 * grace is the delicate part — a brief mid-air telemetry glitch must not close the flight log of
 * an aircraft that is still flying.
 */
class FlightStateConsumerTest {
    private class FakeEffects(
        var returningHome: Boolean = false,
    ) : FlightStateEffects {
        val calls = mutableListOf<String>()

        override fun isReturningHome(): Boolean = returningHome

        override fun onAirborneChanged(flying: Boolean) {
            calls += "airborne=$flying"
        }

        override fun onTakeoff() {
            calls += "takeoff"
        }

        override fun onLanded() {
            calls += "landed"
        }

        override fun onRemoteReturnToHome() {
            calls += "rth"
        }
    }

    private class FakeScheduler : FlightStateScheduler {
        val pending = mutableListOf<() -> Unit>()

        override fun postDelayed(
            delayMs: Long,
            action: () -> Unit,
        ): ScheduledAction {
            assertEquals(10_000L, delayMs)
            pending += action
            return ScheduledAction { pending.remove(action) }
        }

        fun fire() {
            pending.toList().forEach { it() }
            pending.clear()
        }
    }

    private val effects = FakeEffects()
    private val scheduler = FakeScheduler()
    private val consumer = FlightStateConsumer(effects, scheduler)

    @Test
    fun `takeoff opens the session and the landing grace closes it`() {
        consumer.onFlyingChanged(true)
        assertTrue(effects.calls.contains("takeoff"))

        consumer.onFlyingChanged(false)
        assertEquals(listOf("airborne=true", "takeoff", "airborne=false"), effects.calls)

        scheduler.fire()
        assertEquals("landed", effects.calls.last())
    }

    @Test
    fun `a mid-air glitch during the grace period does not close the log`() {
        consumer.onFlyingChanged(true)
        consumer.onFlyingChanged(false)

        // The aircraft reappears before the grace expires: the pending landing is cancelled and a
        // later fire of the (removed) action must not end the session.
        consumer.onFlyingChanged(true)
        scheduler.fire()

        assertEquals(1, effects.calls.count { it == "takeoff" })
        assertTrue(effects.calls.none { it == "landed" })
    }

    @Test
    fun `repeated reports of the same state do not reopen the session`() {
        consumer.onFlyingChanged(true)
        consumer.onFlyingChanged(true)
        consumer.onFlyingChanged(true)

        assertEquals(1, effects.calls.count { it == "takeoff" })
    }

    @Test
    fun `an RC return-to-home is forwarded only when the controller is not already returning`() {
        consumer.onFlightModeChanged(AircraftFlightMode.GO_HOME)
        assertEquals(listOf("rth"), effects.calls)

        effects.returningHome = true
        consumer.onFlightModeChanged(AircraftFlightMode.GO_HOME)
        consumer.onFlightModeChanged(AircraftFlightMode.WAYPOINT)
        assertEquals(listOf("rth"), effects.calls)
    }
}
