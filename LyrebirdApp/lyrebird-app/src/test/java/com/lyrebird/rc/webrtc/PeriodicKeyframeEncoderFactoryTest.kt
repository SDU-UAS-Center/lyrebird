package com.lyrebird.rc.webrtc

import org.junit.Assert.assertEquals
import org.junit.Test
import org.webrtc.EncodedImage
import org.webrtc.VideoCodecInfo
import org.webrtc.VideoCodecStatus
import org.webrtc.VideoEncoder
import org.webrtc.VideoEncoderFactory

/**
 * Exercises [PeriodicKeyframeEncoder] (a file-private class) through the public
 * [PeriodicKeyframeEncoderFactory], the way production code does — there is no other seam to
 * reach it from a test in a different file.
 *
 * The interval is deliberately tiny (milliseconds, not the real 2s default) so the test can
 * cross it with a short real sleep instead of stubbing out the class's `System.nanoTime()` calls,
 * which it has no injectable seam for.
 */
class PeriodicKeyframeEncoderFactoryTest {
    @Test
    fun forcesAKeyframeOnceTheIntervalHasElapsed() {
        val delegate = RecordingEncoder()
        val encoder = PeriodicKeyframeEncoderFactory(FixedEncoderFactory(delegate), keyframeIntervalMs = 20L)
            .createEncoder(null)!!
        encoder.initEncode(null, null)

        // initEncode resets the timer to zero, so the very first frame is always a forced
        // keyframe by design ("first frame after (re)configuration is a keyframe") -- not what
        // this test is checking, just an unavoidable first call to get the interval started.
        encoder.encode(null, deltaFrameInfo())
        assertEquals(listOf(EncodedImage.FrameType.VideoFrameKey), delegate.lastFrameTypes())

        encoder.encode(null, deltaFrameInfo())
        assertEquals(listOf(EncodedImage.FrameType.VideoFrameDelta), delegate.lastFrameTypes())

        Thread.sleep(30)
        encoder.encode(null, deltaFrameInfo())
        assertEquals(listOf(EncodedImage.FrameType.VideoFrameKey), delegate.lastFrameTypes())
    }

    @Test
    fun doesNotForceAKeyframeBeforeTheIntervalElapses() {
        val delegate = RecordingEncoder()
        val encoder = PeriodicKeyframeEncoderFactory(FixedEncoderFactory(delegate), keyframeIntervalMs = 5_000L)
            .createEncoder(null)!!
        encoder.initEncode(null, null)

        encoder.encode(null, deltaFrameInfo())
        encoder.encode(null, deltaFrameInfo())

        assertEquals(listOf(EncodedImage.FrameType.VideoFrameDelta), delegate.lastFrameTypes())
    }

    @Test
    fun aKeyframeWebRtcAlreadyRequestedResetsTheIntervalWithoutForcingAnother() {
        val delegate = RecordingEncoder()
        val encoder = PeriodicKeyframeEncoderFactory(FixedEncoderFactory(delegate), keyframeIntervalMs = 20L)
            .createEncoder(null)!!
        encoder.initEncode(null, null)

        // WebRTC itself wants a keyframe (e.g. a PLI arrived) -- passed straight through.
        encoder.encode(null, keyFrameInfo())
        assertEquals(listOf(EncodedImage.FrameType.VideoFrameKey), delegate.lastFrameTypes())

        // Immediately after, a delta frame should stay a delta -- the interval was satisfied by
        // WebRTC's own keyframe, so this must not force a second one back-to-back.
        encoder.encode(null, deltaFrameInfo())
        assertEquals(listOf(EncodedImage.FrameType.VideoFrameDelta), delegate.lastFrameTypes())
    }

    private fun deltaFrameInfo() = VideoEncoder.EncodeInfo(arrayOf(EncodedImage.FrameType.VideoFrameDelta))
    private fun keyFrameInfo() = VideoEncoder.EncodeInfo(arrayOf(EncodedImage.FrameType.VideoFrameKey))

    /** Records only what [PeriodicKeyframeEncoder] actually decides to forward. */
    private class RecordingEncoder : VideoEncoder {
        private var lastInfo: VideoEncoder.EncodeInfo? = null

        fun lastFrameTypes(): List<EncodedImage.FrameType> = lastInfo?.frameTypes?.toList().orEmpty()

        override fun initEncode(
            settings: VideoEncoder.Settings?,
            callback: VideoEncoder.Callback?
        ): VideoCodecStatus = VideoCodecStatus.OK

        override fun release(): VideoCodecStatus = VideoCodecStatus.OK

        override fun encode(frame: org.webrtc.VideoFrame?, info: VideoEncoder.EncodeInfo?): VideoCodecStatus {
            lastInfo = info
            return VideoCodecStatus.OK
        }

        override fun setRateAllocation(
            allocation: VideoEncoder.BitrateAllocation?,
            framerate: Int
        ): VideoCodecStatus = VideoCodecStatus.OK

        override fun setRates(parameters: VideoEncoder.RateControlParameters?): VideoCodecStatus =
            VideoCodecStatus.OK

        override fun getScalingSettings(): VideoEncoder.ScalingSettings = VideoEncoder.ScalingSettings.OFF

        override fun getResolutionBitrateLimits(): Array<VideoEncoder.ResolutionBitrateLimits> = emptyArray()

        override fun getImplementationName(): String = "RecordingEncoder"

        override fun getEncoderInfo(): VideoEncoder.EncoderInfo = VideoEncoder.EncoderInfo(1, false)

        override fun isHardwareEncoder(): Boolean = false

        override fun createNativeVideoEncoder(): Long = 0
    }

    /** Hands back a single fixed [VideoEncoder] instance, so the test can control it directly. */
    private class FixedEncoderFactory(private val encoder: VideoEncoder) : VideoEncoderFactory {
        override fun createEncoder(info: VideoCodecInfo?): VideoEncoder = encoder
        override fun getSupportedCodecs(): Array<VideoCodecInfo> = emptyArray()
        override fun getImplementations(): Array<VideoCodecInfo> = emptyArray()
        override fun getEncoderSelector(): VideoEncoderFactory.VideoEncoderSelector? = null
    }
}
