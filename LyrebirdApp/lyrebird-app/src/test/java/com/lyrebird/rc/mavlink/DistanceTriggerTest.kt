package com.lyrebird.rc.mavlink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cadence of distance-triggered photography.
 *
 * Worth pinning down because the failure is quiet: an interval that fires early or late still
 * flies the plan perfectly, it just photographs the wrong places — and the photographs are
 * usually looked at long after the flight.
 */
class DistanceTriggerTest {

    @Test
    fun `no capture is due before the interval has been covered`() {
        val trigger = DistanceTrigger(100.0)
        assertFalse(trigger.addTravelled(40.0))
        assertFalse(trigger.addTravelled(40.0))
        assertEquals(20.0, trigger.remainingM, 1e-9)
    }

    @Test
    fun `a capture is due once the interval is covered exactly`() {
        val trigger = DistanceTrigger(100.0)
        assertFalse(trigger.addTravelled(60.0))
        assertTrue(trigger.addTravelled(40.0))
        assertEquals(100.0, trigger.remainingM, 1e-9)
    }

    @Test
    fun `the overshoot carries into the next interval`() {
        // Sampled at 5 Hz, a 100 m interval is rarely met on the exact step. The extra 5 m has
        // already been flown, so counting it again would make every later photo late.
        val trigger = DistanceTrigger(100.0)
        assertTrue(trigger.addTravelled(105.0))
        assertEquals(95.0, trigger.remainingM, 1e-9)
        assertFalse(trigger.addTravelled(90.0))
        assertTrue(trigger.addTravelled(5.0))
    }

    @Test
    fun `a large jump releases one capture, not a burst`() {
        // A resumed or corrected fix can report kilometres at once. One completed interval is
        // all that can honestly be claimed, so the shutter fires once.
        val trigger = DistanceTrigger(100.0)
        assertTrue(trigger.addTravelled(5000.0))
        assertFalse(trigger.addTravelled(0.0))
    }

    @Test
    fun `a stationary or nonsense reading never fires the shutter`() {
        val trigger = DistanceTrigger(100.0)
        assertFalse(trigger.addTravelled(0.0))
        assertFalse(trigger.addTravelled(-25.0))
        assertFalse(trigger.addTravelled(Double.NaN))
        assertFalse(trigger.addTravelled(Double.POSITIVE_INFINITY))
        assertEquals(100.0, trigger.remainingM, 1e-9)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a non-positive interval is refused rather than silently never firing`() {
        DistanceTrigger(0.0)
    }
}
