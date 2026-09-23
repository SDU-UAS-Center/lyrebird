package com.lyrebird.rc.webrtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fan-out contract, where the numbers are references and not "how many devices are watching".
 *
 * These are the arithmetic that cannot be seen on a screen: one consumer too few behind on retains
 * and the buffer is freed under a viewer, one too many and it is never freed at all.
 */
class FrameRecipientsTest {
    private class Frame {
        var references = 1
    }

    private class Consumer(
        private val name: String,
    ) {
        val received = mutableListOf<Frame>()

        fun onFrame(frame: Frame) {
            received += frame
        }

        override fun toString(): String = name
    }

    private fun deliver(
        recipients: FrameRecipients<Consumer>,
        frame: Frame,
    ): List<Consumer> {
        val handed = mutableListOf<Consumer>()
        recipients.deliver(
            frame,
            retain = { it.references++ },
            hand = { consumer, f ->
                handed += consumer
                consumer.onFrame(f)
            },
        )
        return handed
    }

    @Test
    fun `nobody watching means the frame is never handed out`() {
        val recipients = FrameRecipients.of(emptyList<Consumer>())
        val frame = Frame()

        assertTrue(recipients.isEmpty)
        assertEquals(0, recipients.count)
        assertEquals(emptyList<Consumer>(), deliver(recipients, frame))
        assertEquals("and no reference is invented either", 1, frame.references)
    }

    @Test
    fun `one consumer takes the frame without a copy`() {
        val only = Consumer("only")
        val recipients = FrameRecipients.of(listOf(only))
        val frame = Frame()

        assertEquals(listOf(only), deliver(recipients, frame))

        assertEquals(1, frame.references)
        assertEquals(listOf(frame), only.received)
    }

    @Test
    fun `several consumers each get their own reference`() {
        val consumers = listOf(Consumer("a"), Consumer("b"), Consumer("c"))
        val recipients = FrameRecipients.of(consumers)
        val frame = Frame()

        assertEquals(consumers, deliver(recipients, frame))

        assertEquals(
            "the source's own reference plus one per consumer after the first",
            3,
            frame.references,
        )
        assertTrue(consumers.all { it.received == listOf(frame) })
    }

    @Test
    fun `a lone consumer added later is still a lone consumer`() {
        val consumers = java.util.concurrent.ConcurrentHashMap<String, Consumer>()
        consumers["a"] = Consumer("a")
        val recipients = FrameRecipients.of(consumers.values)
        assertEquals(1, recipients.count)

        consumers["b"] = Consumer("b")
        val two = FrameRecipients.of(consumers.values)
        assertEquals(2, two.count)
    }

    @Test
    fun `metadata goes to everybody once, counted or not`() {
        val consumers = listOf(Consumer("a"), Consumer("b"))
        val recipients = FrameRecipients.of(consumers)
        val seen = mutableListOf<Consumer>()

        recipients.forEach { seen += it }

        assertEquals(consumers, seen)
    }

    @Test
    fun `the capture runs while either kind of consumer wants frames`() {
        assertFalse("neither viewers nor a detector", FrameSourcePolicy.wantsCapture(consumers = 0, edgeDetectionActive = false))
        assertTrue("a viewer", FrameSourcePolicy.wantsCapture(consumers = 1, edgeDetectionActive = false))
        assertTrue("the detector alone", FrameSourcePolicy.wantsCapture(consumers = 0, edgeDetectionActive = true))
        assertTrue("both", FrameSourcePolicy.wantsCapture(consumers = 3, edgeDetectionActive = true))
    }
}
