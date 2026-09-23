package com.lyrebird.rc.mavlink

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Capture operation identity: what an endpoint accepts as its own shutter, and what it refuses.
 * The case that matters is the replacement endpoint — a result from before a restart must land on
 * nothing, not announce itself as the new session's photo.
 */
class CaptureTrackerTest {
    @Test
    fun `a started capture is in flight until its result arrives`() {
        val tracker = CaptureTracker()

        assertFalse(tracker.isCapturing)

        val id = tracker.started()

        assertTrue(tracker.isCapturing)
        assertTrue(tracker.finished(id))
        assertFalse(tracker.isCapturing)
    }

    @Test
    fun `an id this tracker never issued is refused`() {
        val tracker = CaptureTracker()
        tracker.started()

        assertFalse(tracker.finished(427L))
        assertTrue("the real capture is still in flight", tracker.isCapturing)
    }

    @Test
    fun `a replacement endpoint does not accept the previous one's ids`() {
        val old = CaptureTracker()
        val replacement = CaptureTracker()

        val oldId = old.started()

        assertFalse(replacement.finished(oldId))
        assertTrue(replacement.started() != oldId)
    }

    @Test
    fun `overlapping captures each deliver exactly once`() {
        val tracker = CaptureTracker()
        val first = tracker.started()
        val second = tracker.started()

        assertTrue(tracker.finished(second))
        assertTrue("the first shutter is still in flight", tracker.isCapturing)
        assertTrue(tracker.finished(first))
        assertFalse("a repeated result must not be delivered twice", tracker.finished(first))
        assertFalse(tracker.isCapturing)
    }
}
