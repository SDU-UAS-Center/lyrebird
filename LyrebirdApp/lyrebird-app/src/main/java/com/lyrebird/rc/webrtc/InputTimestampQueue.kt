package com.lyrebird.rc.webrtc

import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.atomic.AtomicLong

/**
 * Capture timestamps of the frames handed to a Java WebRTC [org.webrtc.VideoEncoder]'s `encode()`,
 * kept so an encoded output can be stamped with a timestamp libwebrtc will accept.
 *
 * `VideoEncoderWrapper::OnEncodedFrame` (sdk/android/src/jni/video_encoder_wrapper.cc) keeps its
 * own FIFO of the timestamps recorded in `Encode()` and **silently drops** every delivered frame
 * whose `captureTimeNs` is not an exact match for an entry still in it ("Java encoder produced an
 * unexpected frame with timestamp"). The stock `HardwareVideoEncoder` satisfies that by queueing
 * one `EncodedImage.Builder` per input frame and building each output from the head of that queue.
 * An encoder whose real pixels arrive from somewhere else -- the experimental DJI surface encoder's
 * MediaCodec surface, written directly by DJI -- has no such pairing and has to reproduce it here:
 * MediaCodec's presentation timestamps belong to DJI's frames and can never match the timestamps of
 * the synthetic frames WebRTC drives `encode()` with.
 *
 * Delivery takes the newest pending timestamp rather than the oldest. Stamping with the oldest
 * would let the stream's timeline fall behind wall time whenever the codec produces frames slower
 * than the driver supplies timestamps; taking the newest keeps every delivered frame at "now" and
 * simply skips the entries in between, which costs nothing because libwebrtc discards every queued
 * timestamp older than the one it is matching.
 */
internal class InputTimestampQueue(
    private val capacity: Int,
) {
    private val timestamps = LinkedBlockingDeque<Long>(capacity)
    private val evicted = AtomicLong(0)
    private val starved = AtomicLong(0)

    /** Timestamps discarded because the queue was full. */
    val evictions: Long
        get() = evicted.get()

    /** Delivery attempts that had a frame waiting but no timestamp to stamp it with. */
    val starvations: Long
        get() = starved.get()

    /** Records the capture timestamp of one frame handed to `encode()`. */
    fun record(timestampNs: Long) {
        if (timestamps.offerLast(timestampNs)) return
        // Full: the backlog is older than anything still worth sending, so evict the oldest entry
        // and keep the window near the present.
        timestamps.pollFirst()
        evicted.incrementAndGet()
        timestamps.offerLast(timestampNs)
    }

    /** The stamp for the next delivered frame, or null when no input frame is pending. */
    fun nextForDelivery(): Long? =
        timestamps.pollLast()
            ?: run {
                starved.incrementAndGet()
                null
            }

    fun clear() {
        timestamps.clear()
    }
}
