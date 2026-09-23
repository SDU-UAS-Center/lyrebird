package com.lyrebird.rc.webrtc

/**
 * Control surface [WhipPublisher] uses when its [org.webrtc.VideoCapturer] does not own its
 * frames directly but draws them from a shared on-device source (the flavor's capturer classes
 * implement this). It keeps WhipPublisher free of any SDK type while resolution, frame-rate and
 * in-flight-drain handling stay with whoever owns the frames.
 */
interface SharedFrameSourceControl {
    /** Change the target resolution; takes effect on subsequent frames. */
    fun changeResolution(
        width: Int,
        height: Int,
    )

    /**
     * Change the frame rate. The implementation owns any resolution behaviour here: the V5
     * capturers keep their current target resolution, where the capturer's own
     * changeCaptureFormat would also reset it.
     */
    fun changeFrameRate(fps: Int)

    /**
     * Block until frames already being handled downstream have been delivered (or the timeout
     * elapses). Call before disposing the observer that receives them.
     */
    fun awaitInFlightFramesIdle(timeoutMs: Long): Boolean
}

/**
 * Frame-availability probing for capturers that share one on-device frame source: the WHIP
 * first-frame gate waits for the source to produce output before offering the connection, and
 * asks it to recover after a stall.
 */
interface FrameAvailabilityWaiter {
    fun totalOutputFrames(): Long

    fun waitForOutputFrameAfter(
        frameCount: Long,
        timeoutMs: Long,
    ): Boolean

    /** Ask the shared source to recover capture after a stall. */
    fun recoverCapture(reason: String)
}
