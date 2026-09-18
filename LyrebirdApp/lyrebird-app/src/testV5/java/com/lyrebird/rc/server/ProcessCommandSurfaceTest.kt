package com.lyrebird.rc.server

import com.lyrebird.rc.LrfMeasurement
import com.lyrebird.rc.LyrebirdFlightPort
import com.lyrebird.rc.StickCommand
import com.lyrebird.rc.StreamingMode
import com.lyrebird.rc.controller.ControlAuthority
import com.lyrebird.rc.mavlink.CommandResult
import com.lyrebird.rc.mavlink.MavlinkCommandOutcome
import com.lyrebird.rc.telemetry.GeoPoint3D
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The process-scoped command surface: with no screen attached it answers from shared state and
 * fails the screen-bound commands honestly; with one attached it forwards to it, and a stale
 * screen's detach can never unplug the newer one.
 */
class ProcessCommandSurfaceTest {
    private class FakeFlightPort(
        private val name: String,
    ) : LyrebirdFlightPort {
        private fun ok() = CommandResult(MavlinkCommandOutcome.ACCEPTED, "fake:$name")

        override fun takeoff() = ok()

        override fun land() = ok()

        override fun returnToHome() = ok()

        override fun stick(command: StickCommand) = ok()

        override fun gotoYaw(yawDeg: Double) = ok()

        override fun gotoAltitude(altitudeM: Double) = ok()

        override fun abortMission() = ok()

        override fun abortAll() = ok()

        override fun enableVirtualStick() = ok()

        override fun waypoint(
            latitudeDeg: Double,
            longitudeDeg: Double,
            altitudeM: Double,
            yawDeg: Double,
            maxSpeedMps: Double,
            noseForward: Boolean,
        ) = ok()

        override fun nativeTrajectory(
            waypoints: List<Triple<Double, Double, Double>>,
            speedMps: Double,
        ) = ok()

        override fun abortNativeMission() = ok()

        override fun setRthAltitude(altitudeM: Int) = ok()

        override fun setMaxFlightHeight(heightM: Int) = ok()

        override fun setMaxFlightDistance(distanceM: Int) = ok()

        override fun setDistanceLimitEnabled(enabled: Boolean) = ok()

        override fun setRcControlMode(mode: String) = ok()

        override fun requestRcPairing() = ok()

        override fun stopRcPairing() = ok()

        override fun deactivateManualOverride() = ok()

        override fun isManualOverrideActive(): Boolean = false
    }

    private class FakeUi(
        private val name: String,
    ) : CommandSurfaceUi {
        var stateChanges = 0
            private set
        var manualOverrideRefreshes = 0
            private set
        var lastAutoSensingSwitch: Boolean? = null
            private set
        var lastDetectionsEnabled: Boolean? = null
            private set

        override val commandSurfaceFlight: LyrebirdFlightPort = FakeFlightPort(name)

        override fun commandSurfaceReadThermalMaxTempNow(): Double? = 42.0

        override fun commandSurfaceHasThermalCamera(): Boolean = true

        override fun commandSurfaceReadLrfMeasurement(): LrfMeasurement = LrfMeasurement(1.5, "NORMAL", null)

        override fun commandSurfaceSetLrfTarget(target: GeoPoint3D?) = Unit

        override fun commandSurfaceUpdateManualOverrideUi() {
            manualOverrideRefreshes++
        }

        override fun commandSurfaceSetAutoSensingSwitch(checked: Boolean) {
            lastAutoSensingSwitch = checked
        }

        override fun commandSurfaceSetMavlinkSystemId(value: Int): Boolean = true

        override fun commandSurfaceSetDetectionsEnabled(enabled: Boolean) {
            lastDetectionsEnabled = enabled
        }

        override fun commandSurfaceSetDetectionSource(value: String): Boolean = true

        override fun commandSurfaceSetStreamingMode(mode: StreamingMode) = Unit

        override fun commandSurfaceSetDjiSurfaceH264Encoder(enabled: Boolean) = Unit

        override fun commandSurfaceOnStateChanged() {
            stateChanges++
        }
    }

    @Test
    fun `flight fails honestly while no screen is attached`() {
        val result = ProcessCommandSurface.flight.takeoff()

        assertEquals(MavlinkCommandOutcome.FAILED, result.outcome)
        assertEquals("runtime detached", result.detail)
        assertFalse(ProcessCommandSurface.flight.isManualOverrideActive())
        assertNull(ProcessCommandSurface.readThermalMaxTempNow())
        assertFalse(ProcessCommandSurface.hasThermalCamera())
        assertEquals(LrfMeasurement(null, null, null), ProcessCommandSurface.readLrfMeasurement())
        assertFalse(ProcessCommandSurface.setMavlinkSystemId(5))
        assertFalse(ProcessCommandSurface.setDetectionSource("yolo_on_phone"))
    }

    @Test
    fun `the newest screen is served and a stale detach cannot unplug it`() {
        val old = FakeUi("old")
        val new = FakeUi("new")
        ProcessCommandSurface.attachUi(old)
        ProcessCommandSurface.attachUi(new)

        assertEquals("fake:new", ProcessCommandSurface.flight.takeoff().detail)
        assertEquals(42.0, ProcessCommandSurface.readThermalMaxTempNow())

        ProcessCommandSurface.detachUi(old)
        assertEquals("the current screen must survive the previous one's teardown", "fake:new", ProcessCommandSurface.flight.takeoff().detail)

        ProcessCommandSurface.detachUi(new)
        assertEquals(MavlinkCommandOutcome.FAILED, ProcessCommandSurface.flight.takeoff().outcome)
    }

    @Test
    fun `delegated members forward while attached and stop when detached`() {
        val ui = FakeUi("ui")
        ProcessCommandSurface.attachUi(ui)

        ProcessCommandSurface.setDetectionsEnabled(false)
        ProcessCommandSurface.updateManualOverrideUI()
        ProcessCommandSurface.setAutoSensingSwitchChecked(true)
        assertTrue(ProcessCommandSurface.setMavlinkSystemId(7))
        assertTrue(ProcessCommandSurface.setDetectionSource("dji_onboard"))
        assertEquals(false, ui.lastDetectionsEnabled)
        assertEquals(true, ui.lastAutoSensingSwitch)
        assertEquals(1, ui.manualOverrideRefreshes)

        ProcessCommandSurface.detachUi(ui)

        ProcessCommandSurface.setDetectionsEnabled(true)
        ProcessCommandSurface.updateManualOverrideUI()
        assertFalse(ProcessCommandSurface.setMavlinkSystemId(7))
        assertFalse(ProcessCommandSurface.setDetectionSource("dji_onboard"))
        assertEquals("detached forwards must not reach the gone screen", false, ui.lastDetectionsEnabled)
        assertEquals(1, ui.manualOverrideRefreshes)
    }

    @Test
    fun `the safety token verifies exactly and everything else is the Pilot`() {
        // Authorization is process state: it must classify the same with no screen attached.
        assertEquals(ControlAuthority.Source.SAFETY, ProcessCommandSurface.classifyCommandSource("98"))
        assertEquals(ControlAuthority.Source.PILOT, ProcessCommandSurface.classifyCommandSource(null))
        assertEquals(ControlAuthority.Source.PILOT, ProcessCommandSurface.classifyCommandSource("98 "))
        assertEquals(ControlAuthority.Source.PILOT, ProcessCommandSurface.classifyCommandSource("99"))
    }

    @Test
    fun `only the drone video source is accepted`() {
        assertTrue(ProcessCommandSurface.setVideoSource("drone"))
        assertTrue(ProcessCommandSurface.setVideoSource("DRONE"))
        assertFalse(ProcessCommandSurface.setVideoSource("phone"))
        assertFalse(ProcessCommandSurface.setVideoSource(""))
    }
}
