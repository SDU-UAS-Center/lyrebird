package com.lyrebird.rc.webrtc

import android.util.Log
import org.webrtc.EncodedImage
import org.webrtc.VideoCodecInfo
import org.webrtc.VideoCodecStatus
import org.webrtc.VideoEncoder
import org.webrtc.VideoEncoderFactory
import org.webrtc.VideoFrame

/**
 * Wraps an encoder factory so the stream emits keyframes on a fixed interval.
 *
 * WebRTC assumes a closed world: one publisher, subscribers that negotiated the session and can
 * ask for a keyframe with a PLI when they need one. libwebrtc leans on that and sets the H.264
 * key-frame interval to **20 seconds** (`HardwareVideoEncoderFactory.getKeyFrameIntervalSec`),
 * because in a normal call nobody joins mid-stream without being able to request an IDR.
 *
 * Lyrebird is not that world. The WHIP publish is re-published by MediaMTX over RTSP, and an
 * RTSP client — QGroundControl, ffmpeg, a browser via HLS — attaches to a stream already in
 * flight, with no way to ask this encoder for anything. It simply waits for the next IDR.
 * Measured against a live Mini 3, `ffprobe` took 4.1–5.3 seconds to produce a first frame, which
 * is exactly the delay a ground station shows when the operator switches to the video view.
 *
 * Forcing a periodic keyframe costs bandwidth — an IDR is several times the size of a delta
 * frame — so the interval is a trade, not a free win. [DEFAULT_KEYFRAME_INTERVAL_MS] is chosen to
 * make a view switch feel responsive while keeping the overhead well under the 2 Mbit/s cap the
 * fleet profiles already impose.
 */
class PeriodicKeyframeEncoderFactory(
    private val delegate: VideoEncoderFactory,
    private val keyframeIntervalMs: Long = DEFAULT_KEYFRAME_INTERVAL_MS,
    /**
     * Consulted each time the interval has elapsed, not just once — lets a caller turn forcing
     * off and back on live (e.g. [MediaMtxConsumerWatcher] reacting to a viewer joining/leaving)
     * without recreating the encoder. Defaults to always-on, i.e. today's unconditional behavior,
     * so any other caller of this class is unaffected.
     */
    private val shouldForce: () -> Boolean = { true }
) : VideoEncoderFactory {

    override fun createEncoder(info: VideoCodecInfo?): VideoEncoder? {
        val encoder = delegate.createEncoder(info) ?: return null
        if (keyframeIntervalMs <= 0) return encoder
        Log.i(TAG, "Forcing a keyframe every ${keyframeIntervalMs}ms on ${info?.name}")
        return PeriodicKeyframeEncoder(encoder, keyframeIntervalMs, shouldForce)
    }

    override fun getSupportedCodecs(): Array<VideoCodecInfo> = delegate.supportedCodecs

    override fun getImplementations(): Array<VideoCodecInfo> = delegate.implementations

    override fun getEncoderSelector(): VideoEncoderFactory.VideoEncoderSelector? =
        delegate.encoderSelector

    companion object {
        private const val TAG = "PeriodicKeyframe"

        /**
         * Two seconds. Short enough that a viewer attaching to the RTSP republish sees a picture
         * almost immediately, long enough that the extra IDRs stay a small fraction of the stream.
         *
         * Frame-drop investigation: this interval is the leading suspect for the occasional
         * frame-rate drops WHIP shows (an IDR is several times a delta frame's size, sent against
         * the same tight bitrate cap). [PREF_KEYFRAME_INTERVAL_MS] lets a field test vary it
         * without a rebuild to test that hypothesis directly.
         */
        const val DEFAULT_KEYFRAME_INTERVAL_MS = 2_000L

        /** SharedPreferences ("LyrebirdPrefs") override for [DEFAULT_KEYFRAME_INTERVAL_MS], as an
         * Int of milliseconds. Unset or invalid falls back to the default -- see [WebRTCPeerFactory]. */
        const val PREF_KEYFRAME_INTERVAL_MS = "lb_whip_keyframe_interval_ms"
    }
}

/**
 * Delegating encoder that promotes a frame to a keyframe once per interval.
 *
 * Everything else passes straight through. The only decision made here is replacing the frame
 * type WebRTC asked for with [EncodedImage.FrameType.VideoFrameKey]; the encoder already knows
 * how to honour that, so no codec configuration is touched and the wrapper stays valid across
 * libwebrtc versions.
 */
private class PeriodicKeyframeEncoder(
    private val delegate: VideoEncoder,
    private val keyframeIntervalMs: Long,
    private val shouldForce: () -> Boolean = { true }
) : VideoEncoder {

    private var lastKeyframeAtNanos = 0L

    override fun encode(frame: VideoFrame?, info: VideoEncoder.EncodeInfo?): VideoCodecStatus {
        val forced = maybeForceKeyframe(info)
        return delegate.encode(frame, forced)
    }

    private fun maybeForceKeyframe(info: VideoEncoder.EncodeInfo?): VideoEncoder.EncodeInfo? {
        if (info == null) return null

        // WebRTC already wants a keyframe here (stream start, or a PLI arrived). Let it through
        // and treat it as satisfying the interval, so we do not immediately force another.
        if (info.frameTypes.any { it == EncodedImage.FrameType.VideoFrameKey }) {
            lastKeyframeAtNanos = System.nanoTime()
            return info
        }

        val elapsedMs = (System.nanoTime() - lastKeyframeAtNanos) / NANOS_PER_MILLI
        if (elapsedMs < keyframeIntervalMs) return info
        // Deliberately does NOT reset lastKeyframeAtNanos when skipped: the interval stays
        // "already elapsed" so a consumer that needs it (e.g. an RTSP puller joining right
        // after this check) gets its keyframe on the very next frame, not after a fresh wait.
        if (!shouldForce()) return info

        lastKeyframeAtNanos = System.nanoTime()
        // One line per actual occurrence (not the configured-interval line logged once at
        // construction) -- lets a field test correlate AdaptiveFrameRatePolicy's saturation/drop
        // timestamps against exactly when a forced IDR went out, rather than just the interval.
        Log.d(TAG, "Forcing keyframe at ${System.currentTimeMillis()} (interval ${keyframeIntervalMs}ms)")
        val keyTypes = Array(info.frameTypes.size) { EncodedImage.FrameType.VideoFrameKey }
        return VideoEncoder.EncodeInfo(keyTypes)
    }

    override fun initEncode(
        settings: VideoEncoder.Settings?,
        callback: VideoEncoder.Callback?
    ): VideoCodecStatus {
        // Reset so the first frame after (re)configuration is a keyframe.
        lastKeyframeAtNanos = 0L
        return delegate.initEncode(settings, callback)
    }

    override fun release(): VideoCodecStatus = delegate.release()

    override fun setRateAllocation(
        allocation: VideoEncoder.BitrateAllocation?,
        framerate: Int
    ): VideoCodecStatus = delegate.setRateAllocation(allocation, framerate)

    override fun setRates(parameters: VideoEncoder.RateControlParameters?): VideoCodecStatus =
        delegate.setRates(parameters)

    override fun getScalingSettings(): VideoEncoder.ScalingSettings = delegate.scalingSettings

    override fun getResolutionBitrateLimits(): Array<VideoEncoder.ResolutionBitrateLimits> =
        delegate.resolutionBitrateLimits

    override fun getImplementationName(): String = delegate.implementationName

    override fun getEncoderInfo(): VideoEncoder.EncoderInfo = delegate.encoderInfo

    override fun isHardwareEncoder(): Boolean = delegate.isHardwareEncoder

    /**
     * Deliberately not delegated. Returning the delegate's native encoder would hand libwebrtc a
     * pointer that bypasses this wrapper entirely, and the forced keyframes would silently stop
     * happening. Returning 0 keeps the Java path, which is what [HardwareVideoEncoder] uses
     * anyway.
     */
    override fun createNativeVideoEncoder(): Long = 0

    private companion object {
        const val TAG = "PeriodicKeyframe"
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
