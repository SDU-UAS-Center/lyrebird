package com.lyrebird.rc.fleet

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The map's follow rules: it tracks the fleet, it yields to a human, and it takes the map back
 * after they stop. Everything here is about time and distance thresholds, which is exactly what
 * cannot be checked by looking at the screen once.
 */
class MapFollowPolicyTest {
    private val policy = MapFollowPolicy(idleResumeMs = 5_000L, reframeIntervalMs = 1_500L, moveThresholdDeg = 3e-5)

    @Test
    fun `a moving aircraft is re-framed but not more often than the interval`() {
        policy.onFramed(nowMs = 10_000L, latitudeDeg = 55.0, longitudeDeg = 12.0, peerCount = 0)

        assertFalse("nothing moved", policy.shouldReframe(10_000L, 55.0, 12.0, 0))
        assertFalse("moved, but too soon to move the camera again", policy.shouldReframe(10_500L, 55.0001, 12.0, 0))
        assertTrue("moved and past the interval", policy.shouldReframe(12_000L, 55.0001, 12.0, 0))
    }

    @Test
    fun `a tiny drift does not move the map`() {
        policy.onFramed(0L, 55.0, 12.0, 0)

        assertFalse(
            "a metre of GPS noise is not a reason to re-centre",
            policy.shouldReframe(10_000L, 55.0 + 1e-6, 12.0, 0),
        )
    }

    @Test
    fun `a peer appearing is worth showing even without a fix`() {
        policy.onFramed(0L, null, null, peerCount = 0)

        assertTrue(policy.shouldReframe(100L, null, null, peerCount = 1))
    }

    @Test
    fun `the pilot has the map until they have been quiet for the idle window`() {
        policy.onUserMoved(nowMs = 1_000L)

        assertFalse("still panning", policy.shouldResume(3_000L))
        assertFalse("one frame short", policy.shouldResume(5_999L))
        assertTrue("quiet long enough", policy.shouldResume(6_000L))
        assertFalse("and while the pilot has it, the map does not move by itself", policy.shouldReframe(9_000L, 55.0, 12.0, 0))
    }

    @Test
    fun `a second pan restarts the clock`() {
        policy.onUserMoved(nowMs = 1_000L)
        policy.onUserMoved(nowMs = 4_000L)

        assertFalse(policy.shouldResume(7_000L))
        assertTrue(policy.shouldResume(9_000L))
    }

    @Test
    fun `expanding the map re-frames it at once`() {
        policy.onFramed(0L, 55.0, 12.0, 0)

        assertTrue("the space it has to fill just changed", policy.shouldReframe(100L, 55.0, 12.0, 0, force = true))
    }
}
