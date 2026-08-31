package com.lyrebird.rc.webrtc

import android.content.Context
import android.util.Log
import dji.sdk.keyvalue.value.common.ComponentIndexType
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.PeerConnectionFactory
import org.webrtc.VideoEncoderFactory

object WebRTCPeerFactory {
    private const val TAG = "WebRTCPeerFactory"

    @Volatile private var factory: PeerConnectionFactory? = null
    private var eglBase: EglBase? = null
    private val factoryLock = Any()

    /**
     * The [MediaMtxConsumerWatcher] for whichever [WhipPublisher] is currently publishing, if
     * any. There is only ever one active WHIP publish per aircraft process, so a single shared
     * slot (set/cleared by WhipPublisher itself around its own publish/teardown) is enough —
     * simpler than threading a per-session reference through this singleton factory, which is
     * built once at first use, before any publish session (or its ground-station host) exists.
     * Read by the [PeriodicKeyframeEncoderFactory] this object constructs below; null means
     * "no active publish" and resolves to the same always-force default as before this existed.
     */
    @Volatile internal var activeConsumerWatcher: MediaMtxConsumerWatcher? = null

    fun getEglBase(): EglBase {
        synchronized(factoryLock) {
            if (eglBase == null) {
                eglBase = EglBase.create()
            }
            return eglBase!!
        }
    }

    fun getFactory(
        context: Context,
        cameraIndex: ComponentIndexType = ComponentIndexType.LEFT_OR_MAIN,
        options: WebRTCMediaOptions = WebRTCMediaOptions()
    ): PeerConnectionFactory {
        synchronized(factoryLock) {
            if (factory == null) {
                initializeFactory(context, cameraIndex, options)
            }
            return factory!!
        }
    }

    private fun initializeFactory(
        context: Context,
        cameraIndex: ComponentIndexType,
        options: WebRTCMediaOptions
    ) {
        val initOptions = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(true)
            .setFieldTrials("WebRTC-H264HighProfile/Enabled/")
            .createInitializationOptions()
        PeerConnectionFactory.initialize(initOptions)

        val rootEglBase = getEglBase()

        factory = PeerConnectionFactory.builder()
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(rootEglBase.eglBaseContext))
            .setVideoEncoderFactory(createVideoEncoderFactory(context, rootEglBase, cameraIndex, options))
            .setOptions(PeerConnectionFactory.Options())
            .createPeerConnectionFactory()

        Log.d(TAG, "PeerConnectionFactory initialized")
    }

    private fun createVideoEncoderFactory(
        context: Context,
        rootEglBase: EglBase,
        cameraIndex: ComponentIndexType,
        options: WebRTCMediaOptions
    ): VideoEncoderFactory {
        val useSurfaceEncoder = context
            .getSharedPreferences("LyrebirdPrefs", Context.MODE_PRIVATE)
            .getBoolean(PREF_USE_DJI_SURFACE_H264_ENCODER, false)
        if (useSurfaceEncoder) {
            val width = if (options.usesSourceResolution) 1920 else options.videoResolutionWidth
            val height = if (options.usesSourceResolution) 1080 else options.videoResolutionHeight
            Log.w(TAG, "Using experimental DJI surface H264 encoder: ${width}x${height}@${options.fps}")
            return DjiSurfaceH264EncoderFactory(
                cameraIndex = cameraIndex,
                width = width,
                height = height,
                bitrateBps = options.senderBitrateBps(),
                fps = options.fps
            )
        }

        // Wrapped so the stream emits periodic keyframes. libwebrtc sets a 20-second H.264
        // key-frame interval, which is fine for a negotiated WebRTC call but leaves anything
        // attaching to the MediaMTX RTSP republish waiting seconds for a first picture.
        return PeriodicKeyframeEncoderFactory(
            DefaultVideoEncoderFactory(rootEglBase.eglBaseContext, false, true),
            keyframeIntervalMs = resolveKeyframeIntervalMs(context),
            shouldForce = { activeConsumerWatcher?.shouldForceKeyframe ?: true }
        )
    }

    /**
     * Field-test override for the periodic keyframe interval (frame-drop investigation) — an
     * invalid or unset value falls back to today's [PeriodicKeyframeEncoderFactory.DEFAULT_KEYFRAME_INTERVAL_MS]
     * rather than disabling forced keyframes altogether, since a silently missing keyframe would
     * regress RTSP/HLS join time far more visibly than a slightly-too-long interval would.
     */
    private fun resolveKeyframeIntervalMs(context: Context): Long {
        val prefs = context.getSharedPreferences("LyrebirdPrefs", Context.MODE_PRIVATE)
        val overrideMs = prefs.getInt(PeriodicKeyframeEncoderFactory.PREF_KEYFRAME_INTERVAL_MS, -1)
        return if (overrideMs > 0) overrideMs.toLong() else PeriodicKeyframeEncoderFactory.DEFAULT_KEYFRAME_INTERVAL_MS
    }

    internal const val PREF_USE_DJI_SURFACE_H264_ENCODER = "lb_whip_surface_h264_encoder"
}
