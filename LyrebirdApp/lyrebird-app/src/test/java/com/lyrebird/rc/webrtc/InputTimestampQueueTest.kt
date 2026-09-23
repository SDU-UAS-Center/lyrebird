package com.lyrebird.rc.webrtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InputTimestampQueueTest {
    /**
     * The front-matching rule of `VideoEncoderWrapper::OnEncodedFrame`
     * (sdk/android/src/jni/video_encoder_wrapper.cc): every queued timestamp strictly older than
     * the delivered one is discarded first, and the delivered timestamp then has to be an exact
     * match for what is left at the front. Anything else is dropped before RTP framing, with the
     * frame never appearing in `framesEncoded`.
     */
    private class NativeWrapperQueue {
        private val pending = ArrayDeque<Long>()

        val dropped = mutableListOf<Long>()

        fun record(timestampNs: Long) {
            pending.addLast(timestampNs)
        }

        fun accepts(captureTimeNs: Long): Boolean {
            while (pending.isNotEmpty() && pending.first() < captureTimeNs) pending.removeFirst()
            if (pending.isEmpty() || pending.first() != captureTimeNs) {
                dropped += captureTimeNs
                return false
            }
            pending.removeFirst()
            return true
        }
    }

    @Test
    fun deliveriesStampedFromTheQueueSurviveWrapperMatching() {
        val queue = InputTimestampQueue(capacity = 16)
        val wrapper = NativeWrapperQueue()
        var driverClockNs = 1_000_000_000L

        repeat(90) { frameIndex ->
            driverClockNs += 33_333_333L
            wrapper.record(driverClockNs)
            queue.record(driverClockNs)

            // The codec only produces on every third capture in this run, which is exactly the
            // case that must not desynchronize the two FIFOs.
            if (frameIndex % 3 == 0) {
                val stamp = queue.nextForDelivery() ?: error("no stamp for frame $frameIndex")
                assertTrue("frame $frameIndex was dropped by the wrapper", wrapper.accepts(stamp))
            }
        }

        assertEquals(emptyList<Long>(), wrapper.dropped)
    }

    @Test
    fun deliveryPrefersTheNewestTimestampSoTimelineStaysAtNow() {
        val queue = InputTimestampQueue(capacity = 16)

        queue.record(100L)
        queue.record(200L)
        queue.record(300L)

        assertEquals(300L, queue.nextForDelivery())
        assertEquals(200L, queue.nextForDelivery())
        assertEquals(100L, queue.nextForDelivery())
    }

    @Test
    fun emptyQueueYieldsNoStampAndCountsAStarvation() {
        val queue = InputTimestampQueue(capacity = 4)

        assertNull(queue.nextForDelivery())
        assertEquals(1L, queue.starvations)

        queue.record(42L)

        assertEquals(42L, queue.nextForDelivery())
        assertEquals(1L, queue.starvations)
    }

    @Test
    fun overflowEvictsOldestTimestampsAndReportsThem() {
        val queue = InputTimestampQueue(capacity = 4)

        (1L..5L).forEach(queue::record)

        assertEquals(1L, queue.evictions)
        assertEquals(5L, queue.nextForDelivery())
        assertEquals(4L, queue.nextForDelivery())
        assertEquals(3L, queue.nextForDelivery())
        assertEquals(2L, queue.nextForDelivery())
        assertNull(queue.nextForDelivery())
    }

    @Test
    fun clearDropsPendingTimestamps() {
        val queue = InputTimestampQueue(capacity = 4)
        queue.record(1L)
        queue.record(2L)

        queue.clear()

        assertNull(queue.nextForDelivery())
    }
}
