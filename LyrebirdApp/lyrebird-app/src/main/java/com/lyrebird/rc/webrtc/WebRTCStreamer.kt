package com.lyrebird.rc.webrtc

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import dji.sdk.keyvalue.value.common.ComponentIndexType
import org.webrtc.VideoSink
import org.webrtc.VideoCapturer
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.SocketException

/**
 * WebRTCStreamer manages DJI video capture and WHIP publishing for the drone feed.
 */
@Suppress("TooManyFunctions")
class WebRTCStreamer(
    context: Context,
    private val cameraIndex: ComponentIndexType = ComponentIndexType.LEFT_OR_MAIN,
    private val droneName: String = "drone_1",
    private val options: WebRTCMediaOptions = WebRTCMediaOptions(),
    mockVideoEnabled: Boolean = false
) {

    companion object {
        private const val TAG = "WebRTCStreamer"
        private const val RECOVERY_COOLDOWN_MS = 15_000L
    }

    private val appContext = context.applicationContext

    enum class VideoSourceMode(val prefValue: String, val menuLabel: String) {
        DJI("drone", "Drone camera"),
        PHONE("phone", "Phone back camera"),
        MOCK("mock", "Mock MP4");

        companion object {
            fun fromPref(value: String?): VideoSourceMode {
                return entries.firstOrNull { it.prefValue == value } ?: DJI
            }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var sharedFrameSource: SharedDJIFrameSource? = null
    private var whipPublisher: WhipPublisher? = null
    private val frameRatePolicy = AdaptiveFrameRatePolicy(options.fps)
    @Volatile private var selectedOptions: WebRTCMediaOptions = options
    @Volatile private var currentSourceMode: VideoSourceMode = if (mockVideoEnabled) {
        VideoSourceMode.MOCK
    } else {
        VideoSourceMode.DJI
    }
    @Volatile private var currentOptions: WebRTCMediaOptions = optionsForSource(options, currentSourceMode)
    @Volatile private var currentWhipUrl: String? = null
    @Volatile private var localPreviewSink: VideoSink? = null
    private var badMetricsWindows = 0
    private var recoveryCount = 0
    private var lastRecoveryAtMs = 0L
    
    var listener: WebRTCStreamerListener? = null

    interface WebRTCStreamerListener {
        fun onServerStarted(ip: String, port: Int)
        fun onServerStopped()
        fun onServerError(error: String)
        fun onMetrics(metrics: WebRTCStreamMetrics) {}
    }

    /**
     * Stop WHIP publishing and release capture resources.
     */
    fun stop() {
        Log.d(TAG, "Stopping WebRTC streamer...")
        mainHandler.removeCallbacksAndMessages(null)
        val stoppedListener = listener
        currentWhipUrl = null
        badMetricsWindows = 0

        logWhipLifecycle(
            event = "whip_stop_requested",
            detail = "streamer stop invoked"
        )
        
        // Stop WHIP publisher if active
        whipPublisher?.stop()
        whipPublisher = null
        
        // Dispose shared frame source
        sharedFrameSource?.dispose()
        sharedFrameSource = null

        TelemetryProvider.stopListening()
        
        if (Looper.myLooper() == Looper.getMainLooper()) {
            stoppedListener?.onServerStopped()
        } else {
            mainHandler.post {
                stoppedListener?.onServerStopped()
            }
        }
        
        Log.d(TAG, "WebRTC streamer stopped")
    }

    fun setMockVideoEnabled(enabled: Boolean) {
        setVideoSourceMode(if (enabled) VideoSourceMode.MOCK else VideoSourceMode.DJI)
    }

    fun setVideoSourceMode(mode: VideoSourceMode) {
        if (currentSourceMode == mode) return
        val previousSource = currentSourceMode
        currentSourceMode = mode
        currentOptions = optionsForSource(selectedOptions, mode)
        badMetricsWindows = 0
        frameRatePolicy.reset()
        recoveryCount = 0

        logWhipLifecycle(
            event = "whip_source_switched",
            detail = "source ${previousSource.prefValue} -> ${mode.prefValue}"
        )

        if (mode != VideoSourceMode.DJI) {
            sharedFrameSource?.dispose()
            sharedFrameSource = null
        }

        val whipUrl = currentWhipUrl
        whipPublisher?.stop()
        whipPublisher = null
        if (whipUrl != null) {
            mainHandler.postDelayed({
                if (currentWhipUrl == whipUrl) startWhip(whipUrl)
            }, 500L)
        }
        Log.i(TAG, "Video source changed to ${mode.menuLabel}")
    }

    fun isMockVideoEnabled(): Boolean = currentSourceMode == VideoSourceMode.MOCK

    fun videoSourceMode(): VideoSourceMode = currentSourceMode

    fun setLocalPreviewSink(sink: VideoSink?) {
        localPreviewSink = sink
        whipPublisher?.setLocalPreviewSink(sink)
    }

    /**
    * Start publishing video via WHIP to a MediaMTX relay server. The phone
    * pushes its stream once and MediaMTX fans it out to WHEP consumers.
     *
     * @param whipUrl Full WHIP endpoint URL, e.g. "http://192.168.x.y:8889/drone_1/whip"
     */
    fun startWhip(whipUrl: String) {
        Log.d(TAG, "Starting WHIP publisher to $whipUrl")
        logWhipLifecycle(
            event = "whip_start_requested",
            whipUrl = whipUrl,
            detail = "fps=${frameRatePolicy.effectiveFps} target=${resolutionLabelForOptions(currentOptions)}"
        )

        if (keepHealthyPublisherOrStopStale(whipUrl)) return

        TelemetryProvider.startListening()
        currentWhipUrl = whipUrl

        val djiFrameSource = if (currentSourceMode == VideoSourceMode.DJI) getOrCreateSharedSource() else null
        if (isDjiSurfaceEncoderEnabled()) djiFrameSource?.prepareSurfaceCapture()
        val capturer = createVideoCapturer("whip")
        val startFrameCount = when (capturer) {
            is SharedVideoCapturerHandle -> capturer.totalOutputFrames()
            is SharedPhoneVideoCapturerHandle -> capturer.totalOutputFrames()
            else -> djiFrameSource?.totalOutputFrames() ?: 0L
        }

        whipPublisher = WhipPublisher(
            context = appContext,
            cameraIndex = cameraIndex,
            videoCapturer = capturer,
            options = currentOptions,
            whipUrl = whipUrl,
            localPreviewSink = localPreviewSink
        ).apply {
            this.listener = createWhipListener(whipUrl)
            start()
        }

        scheduleSourceLossCheck(whipUrl, djiFrameSource, startFrameCount)
    }

    private fun keepHealthyPublisherOrStopStale(whipUrl: String): Boolean {
        val existingPublisher = whipPublisher
        val keepPublisher = existingPublisher != null &&
            currentWhipUrl == whipUrl &&
            existingPublisher.isRunning() &&
            existingPublisher.isPublishing()

        if (keepPublisher) {
            Log.d(TAG, "WHIP publisher already healthy for $whipUrl")
            logWhipLifecycle(
                event = "whip_start_skipped",
                whipUrl = whipUrl,
                detail = "publisher already running"
            )
        } else if (existingPublisher != null) {
            Log.w(TAG, "Restarting stale WHIP publisher for $whipUrl")
            logWhipLifecycle(
                event = "whip_restart_requested",
                whipUrl = whipUrl,
                detail = "stale publisher detected"
            )
            existingPublisher.stop()
            whipPublisher = null
        }

        return keepPublisher
    }

    private fun createWhipListener(whipUrl: String): WhipPublisher.WhipListener {
        return object : WhipPublisher.WhipListener {
            override fun onPublishing() {
                val ip = getLocalIpAddress() ?: "Unknown"
                Log.i(TAG, "WHIP publishing from $ip to $whipUrl")
                logWhipLifecycle(
                    event = "whip_publishing",
                    whipUrl = whipUrl,
                    detail = "localIp=$ip"
                )
                mainHandler.post {
                    this@WebRTCStreamer.listener?.onServerStarted(ip, 0)
                }
            }

            override fun onDisconnected() {
                Log.w(TAG, "WHIP connection lost")
                logWhipLifecycle(
                    event = "whip_disconnected",
                    whipUrl = whipUrl,
                    detail = "publisher disconnected"
                )
            }

            override fun onError(error: String) {
                Log.e(TAG, "WHIP error: $error")
                logWhipLifecycle(
                    event = "whip_error",
                    whipUrl = whipUrl,
                    detail = error
                )
                mainHandler.post {
                    this@WebRTCStreamer.listener?.onServerError("WHIP: $error")
                }
            }
        }
    }

    private fun scheduleSourceLossCheck(
        whipUrl: String,
        djiFrameSource: SharedDJIFrameSource?,
        startFrameCount: Long
    ) {
        mainHandler.postDelayed({
            notifyIfDjiSourceLost(whipUrl, djiFrameSource, startFrameCount)
        }, 15_000L)
    }

    private fun notifyIfDjiSourceLost(
        whipUrl: String,
        djiFrameSource: SharedDJIFrameSource?,
        startFrameCount: Long
    ) {
        val noFramesSinceStart = currentSourceMode == VideoSourceMode.DJI &&
            djiFrameSource != null &&
            djiFrameSource.observerCount() > 0 &&
            djiFrameSource.totalOutputFrames() == startFrameCount
        if (currentWhipUrl == whipUrl && noFramesSinceStart) {
            val message = "Camera feed lost. The drone may have been idle too long or overheated; " +
                "power-cycle the drone and let it cool down before retrying."
            Log.w(TAG, message)
            logWhipLifecycle(
                event = "whip_source_lost",
                whipUrl = whipUrl,
                detail = "no new DJI frames after start"
            )
            mainHandler.post { listener?.onServerError(message) }
        }
    }

    fun isRunning(): Boolean = whipPublisher?.isRunning() == true

    /**
     * Change the streaming resolution for all active connections on-the-fly.
     */
    fun changeResolution(width: Int, height: Int) {
        Log.d(TAG, "Changing resolution to ${if (width > 0 && height > 0) "${width}x${height}" else "native"}")
        sharedFrameSource?.changeResolution(width, height)
        whipPublisher?.changeResolution(width, height)
    }

    /**
     * Store the new media defaults for future clients and apply resolution to
     * already-active WebRTC/WHIP capturers without reconnecting.
     */
    fun changeMediaOptions(options: WebRTCMediaOptions) {
        selectedOptions = options
        currentOptions = optionsForSource(options, currentSourceMode)
        frameRatePolicy.reset(currentOptions.fps)
        changeResolution(currentOptions.videoResolutionWidth, currentOptions.videoResolutionHeight)
        applyFrameRate(frameRatePolicy.effectiveFps, "media options updated")
    }

    fun changeFrameRate(fps: Int) {
        val boundedFps = fps.coerceIn(1, 60)
        frameRatePolicy.reset(boundedFps)
        applyFrameRate(boundedFps, "manual change")
    }

    fun setEdgeDetectionFrameListener(listener: SharedDJIFrameSource.EdgeDetectionFrameListener?) {
        if (currentSourceMode != VideoSourceMode.DJI) return
        if (listener == null) {
            sharedFrameSource?.setEdgeDetectionFrameListener(null)
        } else {
            getOrCreateSharedSource().setEdgeDetectionFrameListener(listener)
        }
    }

    private fun applyFrameRate(fps: Int, reason: String) {
        Log.d(TAG, "Changing FPS to $fps: $reason")
        sharedFrameSource?.changeFrameRate(fps)
        whipPublisher?.changeFrameRate(fps)
    }

    private fun getOrCreateSharedSource(): SharedDJIFrameSource {
        return sharedFrameSource ?: SharedDJIFrameSource(cameraIndex, droneName).also {
            it.metricsListener = ::handleFrameSourceMetrics
            sharedFrameSource = it
        }
    }

    private fun createVideoCapturer(clientId: String): VideoCapturer {
        return when (currentSourceMode) {
            VideoSourceMode.MOCK -> MockMp4VideoCapturer(droneName).apply {
                metricsListener = ::handleFrameSourceMetrics
            }
            VideoSourceMode.PHONE -> SharedPhoneVideoCapturerHandle(clientId)
            VideoSourceMode.DJI -> if (isDjiSurfaceEncoderEnabled()) {
                DjiSurfaceVideoCapturer().apply {
                    metricsListener = ::handleSurfaceMetrics
                }
            } else {
                SharedVideoCapturerHandle(clientId, getOrCreateSharedSource())
            }
        }
    }

    private fun isDjiSurfaceEncoderEnabled(): Boolean = appContext
        .getSharedPreferences("LyrebirdPrefs", Context.MODE_PRIVATE)
        .getBoolean(WebRTCPeerFactory.PREF_USE_DJI_SURFACE_H264_ENCODER, false)

    private fun handleFrameSourceMetrics(metrics: WebRTCStreamMetrics) {
        maybeAdaptFrameRate(metrics)
        val networkStats = whipPublisher?.latestNetworkStats()
        val enriched = metrics.copy(
            recoveryCount = recoveryCount,
            status = if (whipPublisher != null) metrics.status else "idle",
            configuredFps = frameRatePolicy.desiredFps,
            saturationState = frameRatePolicy.saturationState,
            scaleMode = if (currentOptions.usesSourceResolution) "native" else "fixed",
            qualityLimitationReason = networkStats?.qualityLimitationReason,
            framesEncodedNotSent = networkStats?.framesEncodedNotSent,
            sendBitrateBps = networkStats?.sendBitrateBps
        )
        // TEMP diagnostic (frame-drop investigation): telemetry has been reported stuck at
        // status=idle/totalFrames=0 despite confirmed-active WHIP streaming (MediaMTX byte
        // counters growing). This traces whether metrics reach this point at all, and whether
        // whipPublisher is unexpectedly null when they do -- remove once root-caused.
        Log.d(
            TAG,
            "handleFrameSourceMetrics: rawStatus=${metrics.status} totalFrames=${metrics.totalFrames} " +
                "whipPublisherNull=${whipPublisher == null} enrichedStatus=${enriched.status} " +
                "sourceMode=$currentSourceMode"
        )
        if (currentSourceMode == VideoSourceMode.DJI) maybeRecoverStreaming(enriched)
        mainHandler.post { listener?.onMetrics(enriched) }
    }

    private fun handleSurfaceMetrics(metrics: WebRTCStreamMetrics) {
        val networkStats = whipPublisher?.latestNetworkStats()
        val enriched = metrics.copy(
            recoveryCount = recoveryCount,
            qualityLimitationReason = networkStats?.qualityLimitationReason,
            framesEncodedNotSent = networkStats?.framesEncodedNotSent,
            sendBitrateBps = networkStats?.sendBitrateBps,
            status = if (whipPublisher?.isRunning() == true) "running" else metrics.status
        )
        mainHandler.post { listener?.onMetrics(enriched) }
    }

    private fun maybeAdaptFrameRate(metrics: WebRTCStreamMetrics) {
        val decision = frameRatePolicy.evaluate(metrics)
        val nextFps = decision.frameRateToApply ?: return
        decision.lifecycleEvent?.let { event ->
            logWhipLifecycle(event = event, detail = decision.lifecycleDetail)
        }
        applyFrameRate(nextFps, decision.reason ?: "adaptive frame-rate change")
    }

    private fun maybeRecoverStreaming(metrics: WebRTCStreamMetrics) {
        if (metrics.observerCount == 0) {
            badMetricsWindows = 0
            return
        }

        val stalled = metrics.inputFps > 1.0 && metrics.outputFps < 1.0
        val erroring = metrics.processingErrors > 0 && metrics.outputFps < metrics.targetFps * 0.25
        badMetricsWindows = if (stalled || erroring) badMetricsWindows + 1 else 0

        val now = System.currentTimeMillis()
        if (badMetricsWindows >= 3 && now - lastRecoveryAtMs > RECOVERY_COOLDOWN_MS) {
            lastRecoveryAtMs = now
            recoveryCount++
            badMetricsWindows = 0
            val reason = "low output fps ${metrics.outputFps} with input fps ${metrics.inputFps}"
            Log.w(TAG, "Recovering WebRTC pipeline: $reason")
            logWhipLifecycle(
                event = "whip_source_degraded",
                detail = buildString {
                    append("$reason proc=${metrics.averageFrameProcessingMs}ms")
                    append(" req=${metrics.requestedWidth}x${metrics.requestedHeight}")
                    append(" src=${metrics.sourceWidth}x${metrics.sourceHeight}")
                }
            )
            sharedFrameSource?.recoverCapture(reason)
            restartWhipPublisher(reason)
        }
    }

    private fun restartWhipPublisher(reason: String) {
        val whipUrl = currentWhipUrl ?: return
        val oldPublisher = whipPublisher ?: return
        Log.w(TAG, "Restarting WHIP publisher: $reason")
        logWhipLifecycle(
            event = "whip_restart_requested",
            whipUrl = whipUrl,
            detail = reason
        )
        oldPublisher.stop()
        whipPublisher = null
        mainHandler.postDelayed({
            if (currentWhipUrl == whipUrl) {
                startWhip(whipUrl)
            }
        }, 1000L)
    }

    /**
     * Get the local IP address of the device
     */
    fun getLocalIpAddress(): String? {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                findIpv4Address(interfaces.nextElement())?.let { return it }
            }
            null
        } catch (e: SocketException) {
            Log.e(TAG, "Error getting local IP: ${e.message}", e)
            null
        }
    }

    private fun findIpv4Address(networkInterface: NetworkInterface): String? {
        val addresses = networkInterface.inetAddresses
        while (addresses.hasMoreElements()) {
            val address = addresses.nextElement()
            if (!address.isLoopbackAddress && address is Inet4Address) {
                return address.hostAddress
            }
        }
        return null
    }

    private fun logWhipLifecycle(
        event: String,
        whipUrl: String? = currentWhipUrl,
        detail: String? = null
    ) {
        val sharedSource = sharedFrameSource
        val frameCount = sharedSource?.totalOutputFrames() ?: 0L
        val observers = sharedSource?.observerCount() ?: 0
        val source = currentSourceMode.prefValue
        val suffix = buildString {
            append("event=")
            append(event)
            append(" source=")
            append(source)
            append(" whipUrl=")
            append(whipUrl ?: "none")
            append(" frames=")
            append(frameCount)
            append(" observers=")
            append(observers)
            detail?.takeIf { it.isNotBlank() }?.let {
                append(" detail=")
                append(it)
            }
        }
        Log.i(TAG, "WHIP lifecycle $suffix")
    }

    private fun optionsForSource(
        baseOptions: WebRTCMediaOptions,
        sourceMode: VideoSourceMode = currentSourceMode
    ): WebRTCMediaOptions {
        if (sourceMode == VideoSourceMode.DJI) return baseOptions

        val fallbackOptions = if (baseOptions.usesSourceResolution) {
            if (sourceMode == VideoSourceMode.PHONE) WebRTCMediaOptions.hd() else WebRTCMediaOptions.fullHD()
        } else {
            baseOptions
        }
        return fallbackOptions.copy(
            fps = baseOptions.fps.coerceIn(1, 60),
            videoCodec = baseOptions.videoCodec
        )
    }

    private fun resolutionLabelForOptions(options: WebRTCMediaOptions): String {
        return if (options.usesSourceResolution) {
            "native"
        } else {
            "${options.videoResolutionWidth}x${options.videoResolutionHeight}"
        }
    }

}
