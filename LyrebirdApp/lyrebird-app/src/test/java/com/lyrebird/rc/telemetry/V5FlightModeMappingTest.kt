package com.lyrebird.rc.telemetry

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The SDK-to-neutral flight-mode map.
 *
 * Tested through its name overload: DJI's enum cannot be instantiated on a desktop JVM (the
 * application shell ships it with bytecode the verifier rejects), and the adapter's enum overload
 * is a one-line delegation to this table. Two consumers branch on the result — the idle detector
 * treats [AircraftFlightMode.UNKNOWN] as "aircraft asleep", and RC-triggered return-to-home is
 * detected on [AircraftFlightMode.GO_HOME] — so a name-matching shortcut that silently collapsed
 * `ATTI`, `GPS_SPORT`, `GPS_TRIPOD` and `AUTO_TAKE_OFF` into OTHER would change behaviour without
 * a compile error.
 */
class V5FlightModeMappingTest {
    private fun neutral(name: String): AircraftFlightMode =
        V5AircraftTelemetrySource.neutralFlightMode(name)

    @Test
    fun `the modes the app branches on keep their identity`() {
        assertEquals(AircraftFlightMode.UNKNOWN, neutral("UNKNOWN"))
        assertEquals(AircraftFlightMode.GO_HOME, neutral("GO_HOME"))
        assertEquals(AircraftFlightMode.WAYPOINT, neutral("WAYPOINT"))
        assertEquals(AircraftFlightMode.VIRTUAL_STICK, neutral("VIRTUAL_STICK"))
        assertEquals(AircraftFlightMode.MANUAL, neutral("MANUAL"))
        assertEquals(AircraftFlightMode.AUTO_LANDING, neutral("AUTO_LANDING"))
    }

    @Test
    fun `sdk names that differ from the neutral vocabulary are mapped explicitly`() {
        assertEquals(AircraftFlightMode.ATTITUDE, neutral("ATTI"))
        assertEquals(AircraftFlightMode.SPORT, neutral("GPS_SPORT"))
        assertEquals(AircraftFlightMode.TRIPOD, neutral("GPS_TRIPOD"))
        assertEquals(AircraftFlightMode.AUTO_TAKEOFF, neutral("AUTO_TAKE_OFF"))
    }

    @Test
    fun `a mode with no neutral equivalent stays OTHER rather than UNKNOWN`() {
        // UNKNOWN means "aircraft asleep" to the idle detector, so an unmapped flying mode must
        // never land there — TapFly is a real flight, not an unset value.
        assertEquals(AircraftFlightMode.OTHER, neutral("TAP_FLY"))
        assertEquals(AircraftFlightMode.OTHER, neutral("A_MODE_FROM_A_FUTURE_SDK"))
    }
}
