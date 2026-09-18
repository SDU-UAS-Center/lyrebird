package com.lyrebird.rc.controller

import com.lyrebird.rc.telemetry.AircraftReadings
import com.lyrebird.rc.telemetry.AircraftState
import com.lyrebird.rc.telemetry.AircraftTelemetryListener
import com.lyrebird.rc.telemetry.AircraftTelemetrySource
import com.lyrebird.rc.telemetry.AttitudeDeg
import com.lyrebird.rc.telemetry.GeoPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ROI session without the SDK: when yaw is freed and restored, how often the gimbal is
 * aimed, and what a tick refuses to act on. The geometry itself is [RoiControlTest]'s; this is
 * the cadence and lifecycle around it.
 */
class RoiTrackerTest {
    private val gimbal = FakeGimbal()
    private val telemetry = FakeTelemetry()
    private val ticker = FakeTicker()
    private val tracker = RoiTracker(gimbal, { telemetry }, ticker)

    @Test
    fun `start frees yaw and the first tick aims at the target`() {
        tracker.start(latitudeDeg = 55.001, longitudeDeg = 12.0, altitudeM = 0.0)
        assertEquals(listOf(0L), ticker.delays)
        assertEquals(listOf("free"), gimbal.calls)

        ticker.pump()

        // Aim is ~42 degrees below the horizon; the per-tick step caps at 15, and the yaw error is
        // inside the dead band, so only pitch moves.
        assertEquals(listOf("free", "rotate:-15.0:0.0"), gimbal.calls)
    }

    @Test
    fun `a joint already on the aim is left alone`() {
        telemetry.readings = telemetry.readings.copy(gimbalJoint = AttitudeDeg(0.0, -42.0, 0.0))

        tracker.start(latitudeDeg = 55.001, longitudeDeg = 12.0, altitudeM = 0.0)
        ticker.pump()

        assertEquals(listOf("free"), gimbal.calls)
    }

    @Test
    fun `the tick re-arms on the tracking interval`() {
        tracker.start(latitudeDeg = 55.001, longitudeDeg = 12.0, altitudeM = 0.0)
        ticker.pump()
        ticker.pump()

        assertEquals(listOf(0L, 200L, 200L), ticker.delays)
    }

    @Test
    fun `an unset aircraft position is never aimed from`() {
        telemetry.readings = telemetry.readings.copy(location = GeoPosition(0.0, 0.0, 0.0))

        tracker.start(latitudeDeg = 55.001, longitudeDeg = 12.0, altitudeM = 0.0)
        ticker.pump()

        assertEquals(listOf("free"), gimbal.calls)
    }

    @Test
    fun `a restart while tracking only moves the target`() {
        tracker.start(latitudeDeg = 55.001, longitudeDeg = 12.0, altitudeM = 0.0)
        ticker.pump()

        // A second ROI east of the aircraft: a new target, not a new session — the saved mode
        // must not be overwritten with FREE.
        tracker.start(latitudeDeg = 55.0, longitudeDeg = 12.001, altitudeM = 0.0)
        ticker.pump()

        assertEquals(listOf("free", "rotate:-15.0:0.0", "rotate:-15.0:15.0"), gimbal.calls)
    }

    @Test
    fun `stop restores the mode and cancels the pending tick`() {
        tracker.start(latitudeDeg = 55.001, longitudeDeg = 12.0, altitudeM = 0.0)
        ticker.pump()

        tracker.stop()

        assertTrue(ticker.cancelled)
        assertEquals(listOf("free", "rotate:-15.0:0.0", "restore"), gimbal.calls)

        ticker.pump()

        assertEquals(listOf("free", "rotate:-15.0:0.0", "restore"), gimbal.calls)
    }

    @Test
    fun `a stop before any start does not touch the gimbal`() {
        tracker.stop()

        assertTrue(gimbal.calls.isEmpty())
    }

    private class FakeGimbal : RoiGimbalPort {
        val calls = mutableListOf<String>()

        override fun freeYawForTracking() {
            calls += "free"
        }

        override fun restoreYawMode() {
            calls += "restore"
        }

        override fun rotateJoint(
            relativePitchDeg: Double,
            relativeYawDeg: Double,
        ) {
            calls += "rotate:$relativePitchDeg:$relativeYawDeg"
        }
    }

    private class FakeTicker : RoiTicker {
        val delays = mutableListOf<Long>()
        var cancelled = false
        private var pending: (() -> Unit)? = null

        override fun post(
            delayMs: Long,
            action: () -> Unit,
        ) {
            delays += delayMs
            pending = action
        }

        fun pump() {
            val action = pending ?: return
            pending = null
            action()
        }

        override fun cancel() {
            cancelled = true
            pending = null
        }
    }

    private class FakeTelemetry : AircraftTelemetrySource {
        var readings =
            AircraftReadings(
                location = GeoPosition(55.0, 12.0, 100.0),
                headingDeg = 0.0,
                attitude = AttitudeDeg(0.0, 0.0, 0.0),
                gimbalJoint = AttitudeDeg(0.0, 0.0, 0.0),
            )

        override fun read(): AircraftReadings = readings

        override fun readState(): AircraftState =
            AircraftState(
                readings = readings,
                connected = true,
                connectionGeneration = 1,
                observedAtMillis = 0,
            )

        override fun subscribe(listener: AircraftTelemetryListener): AutoCloseable = AutoCloseable { }
    }
}
