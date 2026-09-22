package com.lyrebird.rc.webrtc

/**
 * When the shared camera capture is worth keeping attached.
 *
 * The capture has two kinds of consumer and they arrive and leave independently: WebRTC viewers
 * (a screen opened, a ground station connected, the last one disconnected) and the edge detector
 * (the operator switched AI detection on, and off again). The rule is that the capture runs while
 * either kind wants frames — a detector left running with no viewer must keep its frames, and a
 * viewer left open with detection off must keep its video.
 *
 * That rule used to be written out three times, in three transitions (a client arriving, the last
 * one leaving, detection being switched off), which is three chances to drop one of the two kinds
 * and stop the camera while somebody was still watching.
 */
internal object FrameSourcePolicy {
    fun wantsCapture(
        consumers: Int,
        edgeDetectionActive: Boolean,
    ): Boolean = consumers > 0 || edgeDetectionActive
}
