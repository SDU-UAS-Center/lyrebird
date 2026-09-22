package com.lyrebird.rc.server

import com.lyrebird.rc.perception.ObstacleGuard
import com.lyrebird.rc.perception.ObstacleReading
import com.lyrebird.rc.perception.ObstacleSensorPort
import com.lyrebird.rc.settings.DroneSettingsProfilesTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The obstacle guard's process wiring.
 *
 * What matters here is not the brake arithmetic ([ObstacleGuardTest] covers it) but that the
 * answers the guard asks for come from the process object and that a brake reaches both the
 * flight log's side and whichever screen is current — with the same identity-guarded detach the
 * other runtimes use, so a screen on its way out cannot take the report with it.
 *
 * [ObstacleGuard] is a process singleton, so every test arms what it needs and jumps its clock to
 * a new epoch: a latch left by the previous test must not silence this one, and the tear-down
 * clears the providers so a later suite never sees these fakes.
 */
class ProcessObstacleRuntimeTest {
    private class FakeCallbacks : ObstacleRuntimeCallbacks {
        // Heading zero with a northward velocity: the aircraft is flying towards the sector the
        // test sweep puts its obstacle in, which is what the brake arithmetic reads.
        var motion: ObstacleRuntimeMotion? = ObstacleRuntimeMotion(6.0, 0.0, 0.0, 0.0)
        var autonomousMotion = true
        var manualOverride = false
        val stops = mutableListOf<String>()
        val brakes = mutableListOf<ObstacleRuntimeBrake>()

        override fun runtimeObstacleMotion(): ObstacleRuntimeMotion? = motion

        override fun runtimeAutonomousMotionActive(): Boolean = autonomousMotion

        override fun runtimeManualOverrideActive(): Boolean = manualOverride

        override fun runtimeStopAutonomousMotion() {
            stops += "stop"
        }

        override fun runtimeOnObstacleBrake(event: ObstacleRuntimeBrake) {
            brakes += event
        }
    }

    private class FakeUi : ObstacleGuardUi {
        val brakes = mutableListOf<ObstacleRuntimeBrake>()

        override fun obstacleGuardOnBrake(event: ObstacleRuntimeBrake) {
            brakes += event
        }
    }

    private class FakeSensor : ObstacleSensorPort {
        var sweeps: ((ObstacleReading) -> Unit)? = null
        var starts = 0
            private set
        var stops = 0
            private set

        override fun start(onSweep: (ObstacleReading) -> Unit) {
            sweeps = onSweep
            starts++
        }

        override fun stop() {
            sweeps = null
            stops++
        }
    }

    private val callbacks = FakeCallbacks()
    private val sensor = FakeSensor()
    private val enabledPrefs = DroneSettingsProfilesTest.FakePrefs(mapOf(ObstacleGuard.PREF_ENABLED to true))

    private var clock = 0L

    private fun attach(enabled: Boolean = true) {
        clock = ++epoch * 1_000_000L
        ObstacleGuard.nowMs = { clock }
        val prefs =
            if (enabled) {
                enabledPrefs
            } else {
                DroneSettingsProfilesTest.FakePrefs(mapOf(ObstacleGuard.PREF_ENABLED to false))
            }
        ProcessObstacleRuntimeRegistry.attach(prefs, callbacks, sensor)
    }

    @After
    fun tearDown() {
        ProcessObstacleRuntimeRegistry.stop()
        ObstacleGuard.sensorPort = null
        ObstacleGuard.motionProvider = null
        ObstacleGuard.autonomousMotionProvider = null
        ObstacleGuard.manualOverrideProvider = null
        ObstacleGuard.stopMotion = null
        ObstacleGuard.onBrake = null
    }

    @Test
    fun `the guard asks the process object, not a screen`() {
        attach()
        callbacks.motion = ObstacleRuntimeMotion(6.0, 1.0, -2.0, 123.0)

        assertSame("the sensor the guard subscribes is the process's port", sensor, ObstacleGuard.sensorPort)
        val motion = ObstacleGuard.motionProvider!!.invoke()
        assertEquals(6.0, motion!!.velocityNorthMps, 0.0)
        assertEquals(1.0, motion.velocityEastMps, 0.0)
        assertEquals(-2.0, motion.velocityDownMps, 0.0)
        assertEquals(123.0, motion.headingDeg, 0.0)

        callbacks.autonomousMotion = false
        callbacks.manualOverride = true
        assertEquals(false, ObstacleGuard.autonomousMotionProvider!!.invoke())
        assertEquals(true, ObstacleGuard.manualOverrideProvider!!.invoke())

        ObstacleGuard.stopMotion!!.invoke()
        assertEquals(listOf("stop"), callbacks.stops)
    }

    @Test
    fun `a device that never opted in does not even subscribe`() {
        attach(enabled = false)

        ProcessObstacleRuntimeRegistry.start()

        assertFalse(ProcessObstacleRuntimeRegistry.isEnabled())
        assertFalse(ProcessObstacleRuntimeRegistry.isRunning())
        assertEquals("an opted-out device must not register the sensor listener", 0, sensor.starts)
    }

    @Test
    fun `an armed guard brakes through the process and tells the newest screen`() {
        attach()
        val old = FakeUi()
        val newest = FakeUi()
        ProcessObstacleRuntimeRegistry.attachUi(old)
        ProcessObstacleRuntimeRegistry.attachUi(newest)
        ProcessObstacleRuntimeRegistry.start()

        assertEquals(1, sensor.starts)
        assertTrue(ProcessObstacleRuntimeRegistry.isRunning())

        sensor.sweeps!!.invoke(readingWithObstacle())

        assertEquals("the stop is the process's to apply", listOf("stop"), callbacks.stops)
        assertEquals(listOf("HORIZONTAL"), callbacks.brakes.map { it.reason })
        assertEquals("the brake is reported to the current screen", 1, newest.brakes.size)
        assertEquals(
            "both consumers see the same brake",
            callbacks.brakes.single().bearingFromNoseDeg,
            newest.brakes.single().bearingFromNoseDeg,
            0.0,
        )
        assertTrue(ProcessObstacleRuntimeRegistry.isLatched())
    }

    @Test
    fun `a stale screen cannot take the brake report from the newest one`() {
        attach()
        val old = FakeUi()
        val newest = FakeUi()
        ProcessObstacleRuntimeRegistry.attachUi(old)
        ProcessObstacleRuntimeRegistry.attachUi(newest)
        ProcessObstacleRuntimeRegistry.start()

        ProcessObstacleRuntimeRegistry.detachUi(old)
        sensor.sweeps!!.invoke(readingWithObstacle())
        assertEquals(1, newest.brakes.size)
        assertEquals("a screen that detached must not be reached", 0, old.brakes.size)

        ProcessObstacleRuntimeRegistry.detachUi(newest)
        clock += ObstacleGuard.BRAKE_LATCH_MS + 1
        sensor.sweeps!!.invoke(readingWithObstacle())

        assertEquals("the process still applies and records the brake", listOf("stop", "stop"), callbacks.stops)
        assertEquals("what a detached screen missed, it does not get later", 1, newest.brakes.size)
    }

    @Test
    fun `a detached process callbacks cannot answer, so the guard stays silent`() {
        attach()
        ProcessObstacleRuntimeRegistry.start()

        ProcessObstacleRuntimeRegistry.detach(callbacks)

        assertNull("a detached runtime must not answer with zeroed motion", ObstacleGuard.motionProvider!!.invoke())
        sensor.sweeps!!.invoke(readingWithObstacle())
        assertTrue(callbacks.stops.isEmpty())
        assertTrue(callbacks.brakes.isEmpty())
    }

    @Test
    fun `stopping disarms the sensor and is safe to repeat`() {
        attach()
        ProcessObstacleRuntimeRegistry.start()

        ProcessObstacleRuntimeRegistry.stop()
        assertEquals(1, sensor.stops)
        assertFalse(ProcessObstacleRuntimeRegistry.isRunning())

        ProcessObstacleRuntimeRegistry.stop()
        assertEquals("a stopped guard must not stop the sensor twice", 1, sensor.stops)
    }

    /** A full ring of clear air with one sector holding an obstacle dead ahead. */
    private fun readingWithObstacle(rangeM: Double = 6.0): ObstacleReading {
        val sectorCount = 72
        val intervalDeg = 360.0 / sectorCount
        val ring = MutableList(sectorCount) { 400.0 }
        ring[0] = rangeM
        return ObstacleReading(ring, intervalDeg, Double.NaN, Double.NaN, 0L)
    }

    private companion object {
        var epoch = 0L
    }
}
