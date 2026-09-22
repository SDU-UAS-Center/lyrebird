package com.lyrebird.rc.webrtc

import android.content.Context
import android.util.Log
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

    /**
     * Builds the experimental surface-H264 encoder for an opaque camera handle, installed by the
     * flavor that owns that encoder. When it is absent - another flavor, or the host JVM tests -
     * the experimental setting falls back to the default encoder with a warning.
     */
    @Volatile
    internal var surfaceEncoderFactory:
        ((cameraHandle: Any?, width: Int, height: Int, bitrateBps: Int, fps: Int) -> VideoEncoderFactory)? = null

    /**
     * Driver cadence of the experimental surface-H264 path: the frames its synthetic driver
     * produces carry no pixels (the surface encoder feeds itself); they only pace encode()
     * calls, so this must match the surface capturer's own driver rate.
     */
    internal const val SURFACE_ENCODER_DRIVER_FPS = 30

    fun getEglBase(): EglBase {
        synchronized(factoryLock) {
            if (eglBase == null) {
                eglBase = EglBase.create()
            }
            return eglBase!!
        }
    }

    /**
     * @param cameraHandle opaque flavor camera handle (its camera index), consumed only by
     *   [surfaceEncoderFactory]; null when the caller has none.
     */
    fun getFactory(
        context: Context,
        cameraHandle: Any? = null,
        options: WebRTCMediaOptions = WebRTCMediaOptions(),
    ): PeerConnectionFactory {
        synchronized(factoryLock) {
            if (factory == null) {
                initializeFactory(context, cameraHandle, options)
            }
            return factory!!
        }
    }

    fun reset() {
        synchronized(factoryLock) {
            factory?.dispose()
            factory = null
            eglBase?.release()
            eglBase = null
            activeConsumerWatcher = null
        }
    }

    private fun initializeFactory(
        context: Context,
        cameraHandle: Any?,
        options: WebRTCMediaOptions,
    ) {
        val initOptions =
            PeerConnectionFactory.InitializationOptions
                .builder(context)
                .setEnableInternalTracer(true)
                .setFieldTrials("WebRTC-H264HighProfile/Enabled/")
                .createInitializationOptions()
        PeerConnectionFactory.initialize(initOptions)

        val rootEglBase = getEglBase()

        factory =
            PeerConnectionFactory
                .builder()
                .setVideoDecoderFactory(DefaultVideoDecoderFactory(rootEglBase.eglBaseContext))
                .setVideoEncoderFactory(createVideoEncoderFactory(context, rootEglBase, cameraHandle, options))
                .setOptions(PeerConnectionFactory.Options())
                .createPeerConnectionFactory()

        Log.d(TAG, "PeerConnectionFactory initialized")
    }

    private fun createVideoEncoderFactory(
        context: Context,
        rootEglBase: EglBase,
        cameraHandle: Any?,
        options: WebRTCMediaOptions,
    ): VideoEncoderFactory {
        val useSurfaceEncoder =
            context
                .getSharedPreferences("LyrebirdPrefs", Context.MODE_PRIVATE)
                .getBoolean(PREF_USE_DJI_SURFACE_H264_ENCODER, false)
        val surfaceEncoder = surfaceEncoderFactory
        if (useSurfaceEncoder && surfaceEncoder != null) {
            val width = if (options.usesSourceResolution) 1920 else options.videoResolutionWidth
            val height = if (options.usesSourceResolution) 1080 else options.videoResolutionHeight
            Log.w(TAG, "Using experimental DJI surface H264 encoder: ${width}x$height@${options.fps}")
            return surfaceEncoder(cameraHandle, width, height, options.senderBitrateBps(), options.fps)
        }
        if (useSurfaceEncoder) {
            Log.w(TAG, "Surface H264 encoder requested but no provider is installed; using the default encoder")
        }

        // Wrapped so the stream emits periodic keyframes. libwebrtc sets a 20-second H.264
        // key-frame interval, which is fine for a negotiated WebRTC call but leaves anything
        // attaching to the MediaMTX RTSP republish waiting seconds for a first picture.
        return PeriodicKeyframeEncoderFactory(
            DefaultVideoEncoderFactory(rootEglBase.eglBaseContext, false, true),
            keyframeIntervalMs = resolveKeyframeIntervalMs(context),
            shouldForce = { activeConsumerWatcher?.shouldForceKeyframe ?: true },
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
