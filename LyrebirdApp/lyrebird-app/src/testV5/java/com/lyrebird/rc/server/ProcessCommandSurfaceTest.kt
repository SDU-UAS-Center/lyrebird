package com.lyrebird.rc.server

import com.lyrebird.rc.controller.ControlAuthority
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The process-scoped command surface: with no screen attached it answers from shared state and
 * delegates only what a screen can do; with one attached it forwards that, and a stale screen's
 * detach can never unplug the newer one.
 *
 * The flight port is deliberately not exercised here. Its commands are [DroneController] calls,
 * and touching a DJI-key class in a host JVM test fails at class load — so what is pinned is that
 * the surface hands out the process port rather than anything a screen supplies; the mapping from
 * those commands to the aircraft is the same code it always was, and is verified on the bench.
 */
class ProcessCommandSurfaceTest {
    private class FakeUi : CommandSurfaceUi {
        var stateChanges = 0
            private set
        var manualOverrideRefreshes = 0
            private set
        var lastAutoSensingSwitch: Boolean? = null
            private set

        override fun commandSurfaceUpdateManualOverrideUi() {
            manualOverrideRefreshes++
        }

        override fun commandSurfaceSetAutoSensingSwitch(checked: Boolean) {
            lastAutoSensingSwitch = checked
        }

        override fun commandSurfaceOnStateChanged() {
            stateChanges++
        }
    }

    @Test
    fun `flight motion is process-owned with a screen attached or not`() {
        val ui = FakeUi()
        ProcessCommandSurface.attachUi(ui)

        assertSame(ProcessHttpFlightPort, ProcessCommandSurface.flight)

        ProcessCommandSurface.detachUi(ui)

        assertSame(ProcessHttpFlightPort, ProcessCommandSurface.flight)
    }

    /**
     * The settings commands no longer take this path at all: they persist, apply the matching
     * runtime change and ask the screen to redraw. What is left here is the delegation that is
     * genuinely screen work, and a stale screen's detach must not unplug the current one.
     */
    @Test
    fun `the newest screen is served and a stale detach cannot unplug it`() {
        val old = FakeUi()
        val new = FakeUi()
        ProcessCommandSurface.attachUi(old)
        ProcessCommandSurface.attachUi(new)

        ProcessCommandSurface.updateManualOverrideUI()
        assertEquals("the newest screen is the one served", 1, new.manualOverrideRefreshes)
        assertEquals("the previous screen is not reached any more", 0, old.manualOverrideRefreshes)

        ProcessCommandSurface.detachUi(old)
        ProcessCommandSurface.updateManualOverrideUI()
        assertEquals("the current screen must survive the previous one's teardown", 2, new.manualOverrideRefreshes)

        ProcessCommandSurface.detachUi(new)
        ProcessCommandSurface.updateManualOverrideUI()
        assertEquals("a detached screen is not reached", 2, new.manualOverrideRefreshes)
    }

    @Test
    fun `delegated members forward while attached and stop when detached`() {
        val ui = FakeUi()
        ProcessCommandSurface.attachUi(ui)

        ProcessCommandSurface.updateManualOverrideUI()
        ProcessCommandSurface.setAutoSensingSwitchChecked(true)
        assertEquals(true, ui.lastAutoSensingSwitch)
        assertEquals(1, ui.manualOverrideRefreshes)

        ProcessCommandSurface.detachUi(ui)

        ProcessCommandSurface.updateManualOverrideUI()
        ProcessCommandSurface.setAutoSensingSwitchChecked(false)
        assertEquals("detached forwards must not reach the gone screen", true, ui.lastAutoSensingSwitch)
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

    /**
     * The aircraft's own settings are answered by the process, not by whichever screen happens to
     * be attached. That is the whole point of the split: the RC and the firmware limits used to
     * travel through the screen's flight port, so a ground station configuring an RC with its
     * screen closed was answered by a detached port — and the rcControlMode route turned that
     * into "Invalid control mode (jp|usa|ch|custom)".
     *
     * The type system now guarantees the UI cannot be the owner (the port has no settings
     * members), so this pins the wiring itself: the surface must hand out the process object even
     * with a screen attached.
     */
    @Test
    fun `aircraft settings are process-owned with a screen attached or not`() {
        val ui = FakeUi()
        ProcessCommandSurface.attachUi(ui)

        assertSame(ProcessAircraftSettings, ProcessCommandSurface.aircraftSettings)

        ProcessCommandSurface.detachUi(ui)

        assertSame(ProcessAircraftSettings, ProcessCommandSurface.aircraftSettings)
    }
}
