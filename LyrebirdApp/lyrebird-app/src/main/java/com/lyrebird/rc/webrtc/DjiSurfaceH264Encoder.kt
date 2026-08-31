package com.lyrebird.rc.webrtc

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Surface
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.v5.manager.datacenter.MediaDataCenter
import dji.v5.manager.interfaces.ICameraStreamManager
import org.webrtc.EncodedImage
import org.webrtc.VideoCodecInfo
import org.webrtc.VideoCodecStatus
import org.webrtc.VideoEncoder
import org.webrtc.VideoEncoderFactory
import org.webrtc.VideoFrame
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * PROTOTYPE / SPIKE -- Phase 4 of the WHIP frame-drop investigation. Not wired into the default
 * publish path ([WebRTCPeerFactory] does not use this); exists so the two open questions in the
 * plan can be answered against a real aircraft before any production integration is designed:
 *
 * 1. Does WebRTC's RTP sender pipeline tolerate a [VideoCapturer] that never calls
 *    `CapturerObserver.onFrameCaptured`? This design never does -- see [DjiSurfaceVideoCapturer].
 * 2. Does [ICameraStreamManager.putCameraStreamSurface] coexist with the existing
 *    `addFrameListener` NV21 path (used by [SharedDJIFrameSource] for edge detection) on the same
 *    camera index? Untested here.
 *
 * Zero-copy path: DJI's own `ICameraStreamManager.putCameraStreamSurface` docs state the target
 * surface "supports SurfaceView, TextureView and MediaCodec Surface, does not support
 * GLSurfaceView surface and any surface bound to OpenGL" -- which is why this targets a
 * [MediaCodec] encoder's input surface (explicitly sanctioned) rather than WebRTC's own
 * [org.webrtc.SurfaceTextureHelper] (a GL-bound surface, explicitly not sanctioned, per that
 * same doc). DJI's own FPV widget (`dji.v5.ux.core.widget.fpv.FPVWidget`) uses the same
 * `putCameraStreamSurface` API, just aimed at a display surface instead of an encoder.
 *
 * This bypasses libwebrtc's normal VideoFrame-in/EncodedImage-out encoder contract: real H.264
 * encoding happens entirely inside the [MediaCodec] instance via the surface DJI writes into.
 * [encode] only exists to satisfy the [VideoEncoder] interface and to translate a WebRTC keyframe
 * request into the codec's own `PARAMETER_KEY_REQUEST_SYNC_FRAME` -- the same request DJI's own
 * FPS/keyframe machinery is asking for, just answered by the real encoder instead of
 * [PeriodicKeyframeEncoder]'s frame-type trick.
 */
internal class DjiSurfaceH264Encoder(
    private val cameraIndex: ComponentIndexType,
    private val width: Int,
    private val height: Int,
    private val bitrateBps: Int,
    private val fps: Int
) : VideoEncoder {

    private companion object {
        const val TAG = "DjiSurfaceH264Encoder"
        const val MIME_TYPE = "video/avc"
        const val I_FRAME_INTERVAL_S = 2
        const val DRAIN_TIMEOUT_US = 10_000L
        const val DIAGNOSTIC_LOG_INTERVAL = 100L
        const val PENDING_OUTPUT_CAPACITY = 4
    }

    private val cameraStreamManager: ICameraStreamManager by lazy {
        MediaDataCenter.getInstance().cameraStreamManager
    }

    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var callback: VideoEncoder.Callback? = null
    private var drainThread: Thread? = null
    private val isRunning = AtomicBoolean(false)
    private val encodeCalls = AtomicLong(0)
    private val outputBuffers = AtomicLong(0)
    private val codecConfigBuffers = AtomicLong(0)
    private val encodedFrames = AtomicLong(0)
    private val encodedBytes = AtomicLong(0)
    private val callbackFrames = AtomicLong(0)
    private val droppedFrames = AtomicLong(0)
    private val pendingOutputs = ArrayBlockingQueue<PendingEncodedFrame>(PENDING_OUTPUT_CAPACITY)
    @Volatile private var waitingForKeyFrame = false
    @Volatile private var codecConfig: ByteBuffer? = null

    private data class PendingEncodedFrame(
        val buffer: ByteBuffer,
        val size: Int,
        val isKeyFrame: Boolean,
        val presentationTimeUs: Long
    )

    override fun initEncode(settings: VideoEncoder.Settings?, callback: VideoEncoder.Callback?): VideoCodecStatus {
        this.callback = callback
        return runCatching {
            val format = MediaFormat.createVideoFormat(MIME_TYPE, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_S)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    setInteger(MediaFormat.KEY_MAX_FPS_TO_ENCODER, fps)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setInteger(MediaFormat.KEY_ALLOW_FRAME_DROP, 1)
                }
            }
            val mediaCodec = MediaCodec.createEncoderByType(MIME_TYPE)
            mediaCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = mediaCodec.createInputSurface()
            mediaCodec.start()

            // The zero-copy handoff: DJI's decoder writes camera frames directly onto the
            // encoder's own input surface. Unverified against real hardware -- see class doc.
            cameraStreamManager.putCameraStreamSurface(
                cameraIndex, surface, width, height, ICameraStreamManager.ScaleType.CENTER_INSIDE
            )
            Log.i(TAG, "Camera surface registered on $cameraIndex")

            codec = mediaCodec
            inputSurface = surface
            startDrainThread(mediaCodec)
            Log.i(TAG, "Started: ${width}x${height}@${fps}fps ${bitrateBps}bps on $cameraIndex")
            VideoCodecStatus.OK
        }.getOrElse { error ->
            Log.e(TAG, "initEncode failed: ${error.message}", error)
            VideoCodecStatus.FALLBACK_SOFTWARE
        }
    }

    private fun startDrainThread(mediaCodec: MediaCodec) {
        isRunning.set(true)
        drainThread = Thread({ drainLoop(mediaCodec) }, "DjiSurfaceH264Drain").apply {
            isDaemon = true
            start()
        }
    }

    private fun drainLoop(mediaCodec: MediaCodec) {
        val bufferInfo = MediaCodec.BufferInfo()
        while (isRunning.get()) {
            val outputIndex = runCatching { mediaCodec.dequeueOutputBuffer(bufferInfo, DRAIN_TIMEOUT_US) }
                .getOrNull() ?: break
            when (outputIndex) {
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    Log.i(TAG, "Output format changed: ${mediaCodec.outputFormat}")
                    continue
                }
                MediaCodec.INFO_TRY_AGAIN_LATER,
                MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> continue
            }
            if (outputIndex < 0) continue

            outputBuffers.incrementAndGet()
            runCatching {
                val outputBuffer = mediaCodec.getOutputBuffer(outputIndex) ?: return@runCatching
                deliverEncodedFrame(outputBuffer, bufferInfo)
            }.onFailure { Log.e(TAG, "Error draining encoder output: ${it.message}", it) }
            runCatching { mediaCodec.releaseOutputBuffer(outputIndex, false) }
        }
    }

    private fun deliverEncodedFrame(outputBuffer: ByteBuffer, bufferInfo: MediaCodec.BufferInfo) {
        if (bufferInfo.size <= 0) return
        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
            val count = codecConfigBuffers.incrementAndGet()
            outputBuffer.position(bufferInfo.offset)
            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
            codecConfig = ByteBuffer.allocateDirect(bufferInfo.size).apply {
                put(outputBuffer)
                rewind()
            }
            Log.i(TAG, "Codec config buffer received: size=${bufferInfo.size} count=$count")
            return
        }
        val isKeyFrame = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
        if (waitingForKeyFrame && !isKeyFrame) {
            val dropped = droppedFrames.incrementAndGet()
            if (dropped == 1L || dropped % DIAGNOSTIC_LOG_INTERVAL == 0L) {
                Log.w(TAG, "Dropping dependent surface frames until next keyframe: count=$dropped")
            }
            return
        }

        val config = codecConfig
        val outputSize = bufferInfo.size + if (isKeyFrame) config?.capacity() ?: 0 else 0
        outputBuffer.position(bufferInfo.offset)
        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
        val copy = ByteBuffer.allocateDirect(outputSize).apply {
            if (isKeyFrame && config != null) {
                put(config.duplicate().apply { rewind() })
            }
            put(outputBuffer)
            rewind()
        }
        val frameCount = encodedFrames.incrementAndGet()
        encodedBytes.addAndGet(outputSize.toLong())
        val pending = PendingEncodedFrame(copy, outputSize, isKeyFrame, bufferInfo.presentationTimeUs)
        if (!pendingOutputs.offer(pending)) {
            pendingOutputs.clear()
            waitingForKeyFrame = true
            requestSyncFrame()
            if (!isKeyFrame) {
                val dropped = droppedFrames.incrementAndGet()
                Log.w(TAG, "Surface output queue overflow; waiting for keyframe: dropped=$dropped")
                return
            }
            waitingForKeyFrame = false
            pendingOutputs.offer(pending)
            Log.w(TAG, "Surface output queue overflow; restarted with keyframe")
        } else if (isKeyFrame) {
            waitingForKeyFrame = false
        }
        Log.d(
            TAG,
            "Queued encoded output: frame=$frameCount size=$outputSize keyframe=$isKeyFrame " +
                "configPrepended=${isKeyFrame && config != null}"
        )
    }

    /**
     * Real encoding already happened via the surface DJI wrote into (or hasn't, if the two open
     * questions above resolve unfavourably) -- this only forwards a keyframe request WebRTC/PLI
     * makes to the actual encoder, via the standard MediaCodec sync-frame parameter.
     */
    private fun requestSyncFrame() {
        runCatching {
            codec?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
        }.onFailure { Log.w(TAG, "Could not request sync frame: ${it.message}") }
    }

    /**
     * Real encoding already happened via the surface DJI wrote into (or hasn't, if the two open
     * questions above resolve unfavourably) -- this only forwards a keyframe request WebRTC/PLI
     * makes to the actual encoder, via the standard MediaCodec sync-frame parameter.
     */
    override fun encode(frame: VideoFrame?, info: VideoEncoder.EncodeInfo?): VideoCodecStatus {
        val callCount = encodeCalls.incrementAndGet()
        if (callCount == 1L) {
            Log.i(TAG, "First WebRTC encode call received; surface output is asynchronous")
        }
        val wantsKeyframe = info?.frameTypes?.any { it == EncodedImage.FrameType.VideoFrameKey } == true
        if (wantsKeyframe) {
            requestSyncFrame()
        }
        pendingOutputs.poll()?.let { pending ->
            val encodedImage = EncodedImage.builder()
                .setBuffer(pending.buffer) { }
                .setEncodedWidth(width)
                .setEncodedHeight(height)
                .setCaptureTimeNs(frame?.timestampNs ?: pending.presentationTimeUs * 1000)
                .setFrameType(
                    if (pending.isKeyFrame) {
                        EncodedImage.FrameType.VideoFrameKey
                    } else {
                        EncodedImage.FrameType.VideoFrameDelta
                    }
                )
                .setRotation(0)
                .setQp(null)
                .createEncodedImage()
            callback?.onEncodedFrame(encodedImage, VideoEncoder.CodecSpecificInfo())
            encodedImage.release()
            val callbackCount = callbackFrames.incrementAndGet()
            if (callbackCount == 1L || callbackCount % DIAGNOSTIC_LOG_INTERVAL == 0L) {
                Log.i(
                    TAG,
                    "Delivered encoded output: encodeCall=$callCount callback=$callbackCount " +
                        "size=${pending.size} keyframe=${pending.isKeyFrame} " +
                        "codecPtsUs=${pending.presentationTimeUs} inputTsNs=${frame?.timestampNs}"
                )
            }
        }
        return VideoCodecStatus.OK
    }

    override fun release(): VideoCodecStatus {
        isRunning.set(false)
        runCatching { drainThread?.join(500) }
        drainThread = null
        inputSurface?.let { surface ->
            runCatching { cameraStreamManager.removeCameraStreamSurface(surface) }
                .onFailure { Log.w(TAG, "removeCameraStreamSurface failed: ${it.message}") }
        }
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
        inputSurface = null
        callback = null
        pendingOutputs.clear()
        waitingForKeyFrame = false
        codecConfig = null
        Log.i(
            TAG,
            "Released: encodeCalls=${encodeCalls.get()} outputBuffers=${outputBuffers.get()} " +
                "codecConfig=${codecConfigBuffers.get()} encodedFrames=${encodedFrames.get()} " +
            "encodedBytes=${encodedBytes.get()} callbackFrames=${callbackFrames.get()} " +
                "droppedFrames=${droppedFrames.get()}"
        )
        return VideoCodecStatus.OK
    }

    override fun setRateAllocation(allocation: VideoEncoder.BitrateAllocation?, framerate: Int): VideoCodecStatus {
        val bitrate = allocation?.sum ?: return VideoCodecStatus.OK
        return applyBitrate(bitrate)
    }

    override fun setRates(parameters: VideoEncoder.RateControlParameters?): VideoCodecStatus {
        val bitrate = parameters?.bitrate?.sum ?: return VideoCodecStatus.OK
        return applyBitrate(bitrate)
    }

    private fun applyBitrate(bitrateBps: Int): VideoCodecStatus = runCatching {
        codec?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bitrateBps) })
        VideoCodecStatus.OK
    }.getOrElse { VideoCodecStatus.ERROR }

    override fun getScalingSettings(): VideoEncoder.ScalingSettings = VideoEncoder.ScalingSettings.OFF

    override fun getResolutionBitrateLimits(): Array<VideoEncoder.ResolutionBitrateLimits> = emptyArray()

    override fun getImplementationName(): String = "DjiSurfaceH264Encoder"

    override fun getEncoderInfo(): VideoEncoder.EncoderInfo = VideoEncoder.EncoderInfo(1, false)

    override fun isHardwareEncoder(): Boolean = true

    /** Deliberately not delegated to the real MediaCodec — this class already owns and drains
     * it directly; returning a native pointer here would let libwebrtc bypass that entirely. */
    override fun createNativeVideoEncoder(): Long = 0
}

/**
 * PROTOTYPE / SPIKE -- see [DjiSurfaceH264Encoder]. Builds one for a fixed camera/resolution/
 * bitrate/fps combination; not wired into [WebRTCPeerFactory]'s default encoder factory.
 */
internal class DjiSurfaceH264EncoderFactory(
    private val cameraIndex: ComponentIndexType,
    private val width: Int,
    private val height: Int,
    private val bitrateBps: Int,
    private val fps: Int
) : VideoEncoderFactory {
    override fun createEncoder(info: VideoCodecInfo?): VideoEncoder =
        DjiSurfaceH264Encoder(cameraIndex, width, height, bitrateBps, fps)

    override fun getSupportedCodecs(): Array<VideoCodecInfo> =
        arrayOf(
            VideoCodecInfo("H264", h264Params("640c1f")),
            VideoCodecInfo("H264", h264Params("42e01f"))
        )

    override fun getImplementations(): Array<VideoCodecInfo> = supportedCodecs

    private fun h264Params(profileLevelId: String): Map<String, String> = mapOf(
        "level-asymmetry-allowed" to "1",
        "packetization-mode" to "1",
        "profile-level-id" to profileLevelId
    )

    override fun getEncoderSelector(): VideoEncoderFactory.VideoEncoderSelector? = null
}
