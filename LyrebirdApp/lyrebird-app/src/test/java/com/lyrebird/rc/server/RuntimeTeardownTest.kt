package com.lyrebird.rc.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shutdown order, which is the part of "an explicit shutdown releases the lease last" that a
 * unit test can hold still: the runtimes that serve *through* a session must go down before the
 * session's lease is given back, and a failure in the middle must not leave the lease stranded.
 */
class RuntimeTeardownTest {
    private class Recorder {
        val events = mutableListOf<String>()

        fun step(label: String) =
            RuntimeTeardown.Step(label) {
                events += label
            }

        fun failingStep(label: String) =
            RuntimeTeardown.Step(label) {
                events += label
                error("$label exploded")
            }
    }

    @Test
    fun `steps run in the pinned order and the lease goes last`() {
        val recorder = Recorder()
        var released = 0
        val teardown =
            RuntimeTeardown(
                steps = listOf(recorder.step("streaming"), recorder.step("mavlink"), recorder.step("detections")),
                releaseLease = { released++ },
            )

        val ran = teardown.run()

        assertEquals(listOf("streaming", "mavlink", "detections"), recorder.events)
        assertEquals(listOf("streaming", "mavlink", "detections", RuntimeTeardown.LEASE_STEP), ran)
        assertEquals("the lease is released exactly once", 1, released)
    }

    @Test
    fun `a failing step neither skips the rest nor strands the lease`() {
        val recorder = Recorder()
        var released = false
        val teardown =
            RuntimeTeardown(
                steps =
                    listOf(
                        recorder.step("streaming"),
                        recorder.failingStep("mavlink"),
                        recorder.step("fleet mesh"),
                    ),
                releaseLease = { released = true },
            )

        val ran = teardown.run()

        assertEquals(
            "a failed teardown step must not keep the later ones from running",
            listOf("streaming", "mavlink", "fleet mesh"),
            recorder.events,
        )
        assertTrue(ran.last() == RuntimeTeardown.LEASE_STEP)
        assertTrue("both APKs must not be left believing the other owns the session", released)
    }

    @Test
    fun `a lease release that fails does not escape the teardown`() {
        val recorder = Recorder()
        val teardown =
            RuntimeTeardown(
                steps = listOf(recorder.step("streaming")),
                releaseLease = { error("lease port already gone") },
            )

        val ran = teardown.run()

        assertEquals(listOf("streaming"), recorder.events)
        assertEquals(listOf("streaming", RuntimeTeardown.LEASE_STEP), ran)
    }

    @Test
    fun `a teardown with no steps still gives the lease back`() {
        var released = 0

        RuntimeTeardown(steps = emptyList(), releaseLease = { released++ }).run()

        assertEquals(1, released)
    }
}
