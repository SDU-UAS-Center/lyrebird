package com.lyrebird.rc.webrtc

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import dji.sdk.keyvalue.value.common.ComponentIndexType
import org.webrtc.CapturerObserver
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.RTCStatsReport
import org.webrtc.RtpReceiver
import org.webrtc.RtpSender
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoSink
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.io.IOException
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Publishes a WebRTC video stream to a mediamtx server via WHIP
 * (WebRTC HTTP Ingest Protocol).
 *
 * Flow:
 *  1. Create PeerConnection with a sendonly video track
 *  2. Create SDP offer and gather all ICE candidates
 *  3. POST the offer to the WHIP endpoint
 *  4. Set the SDP answer from the response
 *  5. Video flows through mediamtx to all WHEP consumers
 *
 * Reconnects automatically if the connection drops.
 */
@Suppress("TooManyFunctions")
class WhipPublisher(
    context: Context,
    private val cameraIndex: ComponentIndexType,
    private val videoCapturer: VideoCapturer,
    private val options: WebRTCMediaOptions = WebRTCMediaOptions(),
    private val whipUrl: String,
    private var localPreviewSink: VideoSink? = null
) {
    companion object {
        private const val TAG = "WhipPublisher"
        private const val ICE_GATHER_TIMEOUT_S = 10L
        private const val RECONNECT_BASE_DELAY_MS = 2000L
        private const val RECONNECT_MAX_DELAY_MS = 30000L
        private const val FIRST_FRAME_TIMEOUT_MS = 8_000L
        private const val FIRST_FRAME_RECOVERY_TIMEOUT_MS = 4_000L
        //: How long stop() waits for the publish loop to finish its own teardown before returning.
        private const val STOP_AWAIT_GRACE_S = 5L
        //: How long teardown() waits for DJI's live-view thread to finish an in-flight frame
        //: before disposing the WebRTC source it delivers into.
        private const val FRAME_DRAIN_TIMEOUT_MS = 200L
        //: getStats() polling cadence, matching SharedDJIFrameSource's own ~1s metrics window so
        //: network-side and processing-side diagnostics land on comparable timescales.
        private const val STATS_POLL_INTERVAL_MS = 1000L
    }

    private val appContext = context.applicationContext

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var peerConnection: PeerConnection? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var whipResourceUrl: String? = null  // Location header for DELETE on teardown

    // Phase 3 of the frame-drop investigation: skips the periodic forced keyframe while nothing
    // but WebRTC/WHEP viewers are attached to this drone's MediaMTX path. Owned per-publish
    // (fresh instance each publish() call) and published into WebRTCPeerFactory's shared slot,
    // which is what the encoder actually reads -- see WebRTCPeerFactory.activeConsumerWatcher.
    private var consumerWatcher: MediaMtxConsumerWatcher? = null

    private val isRunning = AtomicBoolean(false)
    private val isPublishing = AtomicBoolean(false)
    private val isTearingDown = AtomicBoolean(false)
    @Volatile private var currentFps: Int = options.fps

    // Send-side network diagnostics (Phase 1: WHIP/WHEP frame-drop investigation). Updated on
    // WebRTC's own stats callback thread, read from WebRTCStreamer's metrics thread -- volatile
    // read/write of an immutable snapshot is enough, no lock needed.
    @Volatile private var latestNetworkStats: WhipNetworkStats? = null
    private var lastStatsBytesSent: Long = 0L
    private var lastStatsTimestampUs: Double = 0.0

    fun latestNetworkStats(): WhipNetworkStats? = latestNetworkStats

    var listener: WhipListener? = null

    interface WhipListener {
        fun onPublishing()
        fun onDisconnected()
        fun onError(error: String)
    }

    /**
     * Send-side network stats read from [PeerConnection.getStats], separate from
     * [WebRTCStreamMetrics]'s on-device processing-time numbers -- see [pollNetworkStatsIfDue].
     */
    data class WhipNetworkStats(
        val qualityLimitationReason: String?,
        val framesEncodedNotSent: Long?,
        val sendBitrateBps: Long?
    )

    fun start() {
        if (isRunning.getAndSet(true)) return
        executor.execute { publishLoop() }
    }

    fun isRunning(): Boolean = isRunning.get()

    fun isPublishing(): Boolean = isPublishing.get()

    fun setLocalPreviewSink(sink: VideoSink?) {
        localPreviewSink?.let { oldSink -> runCatching { videoTrack?.removeSink(oldSink) } }
        localPreviewSink = sink
        sink?.let { newSink -> runCatching { videoTrack?.addSink(newSink) } }
    }

    fun stop() {
        val wasRunning = isRunning.getAndSet(false)
        listener = null
        mainHandler.removeCallbacksAndMessages(null)
        executor.shutdownNow()
        if (wasRunning) {
            // The publish loop owns teardown, on the executor thread. Disposing the native
            // WebRTC objects from here while publish() is mid-flight (addTrack, WHIP POST, ICE
            // gather) is a use-after-free that has crashed the process -- SIGILL inside
            // PeerConnection_nativeAddTrack, observed in the field. The loop always tears down
            // before it exits, and its blocking calls are bounded, so wait for it instead.
            runCatching { executor.awaitTermination(STOP_AWAIT_GRACE_S, TimeUnit.SECONDS) }
            Log.i(TAG, "WhipPublisher stopped")
        }
    }

    /** Change resolution without reconnection. */
    fun changeResolution(width: Int, height: Int) {
        when (videoCapturer) {
            is DJIV5VideoCapturer -> videoCapturer.changeResolution(width, height)
            is SharedVideoCapturerHandle -> videoCapturer.changeResolution(width, height)
            is MockMp4VideoCapturer -> videoCapturer.changeResolution(width, height)
            is SharedPhoneVideoCapturerHandle -> videoCapturer.changeCaptureFormat(width, height, currentFps)
        }
    }

    fun changeFrameRate(fps: Int) {
        val boundedFps = fps.coerceIn(1, 60)
        currentFps = boundedFps
        when (videoCapturer) {
            is DJIV5VideoCapturer -> videoCapturer.changeCaptureFormat(
                options.videoResolutionWidth,
                options.videoResolutionHeight,
                boundedFps
            )
            is SharedVideoCapturerHandle -> videoCapturer.changeFrameRate(boundedFps)
            is MockMp4VideoCapturer -> videoCapturer.changeFrameRate(boundedFps)
            is SharedPhoneVideoCapturerHandle -> videoCapturer.changeCaptureFormat(
                options.videoResolutionWidth,
                options.videoResolutionHeight,
                boundedFps
            )
        }
        peerConnection?.senders?.firstOrNull()?.let { configureVideoSenderForStability(it) }
        Log.d(TAG, "WHIP frame rate changed to $boundedFps fps")
    }

    // ── internal ────────────────────────────────────────────────────

    private fun publishLoop() {
        var consecutiveFailures = 0

        while (isRunning.get()) {
            val failure = runCatching {
                publish()
            }.exceptionOrNull()

            if (failure == null) {
                consecutiveFailures = 0
            } else if (isRunning.get()) {
                consecutiveFailures++
                Log.e(TAG, "Publish failed (attempt $consecutiveFailures): ${failure.message}")
                mainHandler.post { listener?.onError(failure.message ?: "Unknown error") }
                if (failure is InterruptedException) {
                    Thread.currentThread().interrupt()
                    isRunning.set(false)
                }
            }

            teardown()
            isPublishing.set(false)
            mainHandler.post { listener?.onDisconnected() }

            if (isRunning.get()) {
                val delay = (RECONNECT_BASE_DELAY_MS * (1L shl minOf(consecutiveFailures - 1, 4)))
                    .coerceAtMost(RECONNECT_MAX_DELAY_MS)
                Log.i(TAG, "Reconnecting in ${delay}ms...")
                if (!sleepBeforeReconnect(delay)) {
                    isRunning.set(false)
                }
            }
        }
    }

    /**
     * Single publish attempt. Blocks until the connection closes or
     * [isRunning] becomes false.
     */
    private fun publish() {
        Log.i(TAG, "Publishing to $whipUrl")

        startConsumerWatcher()

        val factory = WebRTCPeerFactory.getFactory(appContext, cameraIndex, options)

        // 1. Create video source & track
        videoSource = factory.createVideoSource(false)
        surfaceTextureHelper = SurfaceTextureHelper.create(
            "LyrebirdWhipCapture",
            WebRTCPeerFactory.getEglBase().eglBaseContext
        )
        videoTrack = factory.createVideoTrack(options.videoTrackId, videoSource).apply {
            setEnabled(true)
            localPreviewSink?.let { addSink(it) }
        }
        val useSurfaceEncoder = appContext
            .getSharedPreferences("LyrebirdPrefs", Context.MODE_PRIVATE)
            .getBoolean(WebRTCPeerFactory.PREF_USE_DJI_SURFACE_H264_ENCODER, false)
        // The experimental DJI surface encoder cannot be initialized until the WebRTC peer
        // creates its encoder, so waiting here for the normal NV21 listener would deadlock its
        // startup whenever that listener is quiet. Its own MediaCodec output is the first-frame
        // signal for this path; the default encoder keeps the existing gate.
        val firstFrameGate = if (useSurfaceEncoder) null else createFirstFrameGate(videoCapturer)
        if (useSurfaceEncoder) {
            Log.w(TAG, "Surface encoder enabled; skipping pre-offer DJI frame gate")
        }
        val startingFrameCount = firstFrameGate?.totalOutputFrames() ?: 0L
        videoCapturer.initialize(surfaceTextureHelper, appContext, videoSource!!.capturerObserver)
        videoCapturer.startCapture(
            options.videoResolutionWidth,
            options.videoResolutionHeight,
            currentFps
        )
        firstFrameGate?.awaitFirstFrame(startingFrameCount, FIRST_FRAME_TIMEOUT_MS, FIRST_FRAME_RECOVERY_TIMEOUT_MS)

        // 2. Create PeerConnection
        val rtcConfig = whipRtcConfiguration()

        // Disable CPU overuse detection so WebRTC doesn't auto-downscale resolution
        disableCpuOveruseDetection(rtcConfig)

        val iceGatherLatch = CountDownLatch(1)
        val connected = AtomicBoolean(false)

        peerConnection = factory.createPeerConnection(
            rtcConfig,
            WhipConnectionObserver(
                iceGatherLatch = iceGatherLatch,
                connected = connected,
                onPublishing = {
                    isPublishing.set(true)
                    mainHandler.post { listener?.onPublishing() }
                }
            )
        )

        // Add video track (sendonly — mediamtx doesn't send back video)
        peerConnection!!.addTrack(videoTrack, listOf(options.mediaStreamId))

        // Configure sender for stable resolution.
        peerConnection!!.senders.firstOrNull()?.let { sender ->
            configureVideoSenderForStability(sender)
        }

        // 3. Create offer
        createAndSetLocalOffer(peerConnection!!)

        // 4. Wait for ICE gathering to finish (full SDP needed for WHIP)
        if (!iceGatherLatch.await(ICE_GATHER_TIMEOUT_S, TimeUnit.SECONDS)) {
            Log.w(TAG, "ICE gathering timeout — proceeding with partial candidates")
        }

        // Use the local description which now contains all gathered ICE candidates
        val offerSdp = checkNotNull(peerConnection!!.localDescription?.description) {
            "No local description after ICE gathering"
        }

        // 5. POST offer to WHIP endpoint
        val answerSdp = postWhipOffer(offerSdp)

        // 6. Set remote description (answer from mediamtx)
        setRemoteWhipAnswer(peerConnection!!, answerSdp)

        Log.i(TAG, "WHIP publish started — waiting for connection")

        // 7. Wait until connection drops or we're stopped
        waitForWhipConnectionLoss(
            isRunning, connected, { peerConnection }, STATS_POLL_INTERVAL_MS, ::pollNetworkStatsIfDue
        )
    }

    /**
     * Phase 3 of the frame-drop investigation: only skip the periodic forced keyframe when we
     * can actually confirm nothing but WebRTC viewers is attached. An unparseable [whipUrl]
     * (custom deployment, unexpected shape) leaves [WebRTCPeerFactory.activeConsumerWatcher]
     * untouched, i.e. null, which the encoder's fallback (`?: true`) already treats as
     * "keep forcing" -- the same fail-safe default as every other failure mode here.
     */
    private fun startConsumerWatcher() {
        val hostAndPath = mediaMtxHostAndPathFromWhipUrl(whipUrl)
        if (hostAndPath == null) {
            Log.w(TAG, "Could not parse ground-station host/path from $whipUrl for keyframe watcher")
            return
        }
        val (host, pathName) = hostAndPath
        val watcher = MediaMtxConsumerWatcher()
        consumerWatcher = watcher
        WebRTCPeerFactory.activeConsumerWatcher = watcher
        watcher.start(host, pathName)
    }

    /**
     * Send-side stats (Phase 1 of the frame-drop investigation): distinguishes a
     * network-congestion-driven drop from the on-device processing-time saturation
     * AdaptiveFrameRatePolicy already tracks, by asking WebRTC's own RTP sender directly rather
     * than inferring it from capture-thread timing. Safe to call at any connection state --
     * getStats() answers with zeroed/absent fields before a real outbound-rtp stream exists.
     */
    private fun pollNetworkStatsIfDue() {
        val pc = peerConnection ?: return
        pc.getStats { report -> onStatsReport(report) }
    }

    private fun onStatsReport(report: RTCStatsReport) {
        val outboundVideoRtp = report.statsMap.values.firstOrNull { stats ->
            stats.type == "outbound-rtp" && stats.members["kind"] == "video"
        } ?: return

        val members = outboundVideoRtp.members
        val qualityLimitationReason = (members["qualityLimitationReason"] as? String)
        val framesEncoded = (members["framesEncoded"] as? Number)?.toLong()
        val framesSent = (members["framesSent"] as? Number)?.toLong()
        val framesEncodedNotSent = if (framesEncoded != null && framesSent != null) {
            (framesEncoded - framesSent).coerceAtLeast(0L)
        } else {
            null
        }

        val bytesSent = (members["bytesSent"] as? Number)?.toLong()
        val timestampUs = outboundVideoRtp.timestampUs
        val sendBitrateBps = if (bytesSent != null) {
            sendBitrateBpsFromSample(bytesSent, timestampUs, lastStatsBytesSent, lastStatsTimestampUs).also {
                lastStatsBytesSent = bytesSent
                lastStatsTimestampUs = timestampUs
            }
        } else {
            null
        }

        latestNetworkStats = WhipNetworkStats(
            qualityLimitationReason = qualityLimitationReason,
            framesEncodedNotSent = framesEncodedNotSent,
            sendBitrateBps = sendBitrateBps
        )
    }

    /**
     * HTTP POST of SDP offer to the WHIP endpoint.
     * Returns the SDP answer body.
     */
    private fun postWhipOffer(offerSdp: String): String {
        val url = URL(whipUrl)
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/sdp")
            conn.doOutput = true
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000

            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(offerSdp) }

            val status = conn.responseCode
            if (status != 201) {
                val body = runCatching { conn.errorStream?.bufferedReader()?.readText() }.getOrNull() ?: ""
                throw IOException("WHIP POST failed: $status $body")
            }
            whipResourceUrl = absoluteWhipResourceUrl(url, conn.getHeaderField("Location"))
            return conn.inputStream.bufferedReader().readText()
        } finally {
            conn.disconnect()
        }
    }

    private fun deleteWhipResource() {
        val resourceUrl = whipResourceUrl ?: return
        whipResourceUrl = null
        runCatching {
            val conn = URL(resourceUrl).openConnection() as HttpURLConnection
            conn.requestMethod = "DELETE"
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            val status = conn.responseCode
            conn.disconnect()
            Log.d(TAG, "WHIP resource DELETE: $status")
        }.onFailure { error -> Log.d(TAG, "WHIP resource DELETE failed: ${error.message}") }
    }

    private fun teardown() {
        if (!isTearingDown.compareAndSet(false, true)) return
        try {
            deleteWhipResource()
            localPreviewSink?.let { sink -> runCatching { videoTrack?.removeSink(sink) } }
            runCatching { videoCapturer.stopCapture() }
            // stopCapture removed our observer, but a frame already dispatched on DJI's live-view
            // thread can still be delivering into the VideoSource. Disposing the source first
            // aborts inside WebRTC native -- pthread_mutex_lock on a destroyed mutex, seen in the
            // field -- so wait for the frame thread to quiesce before disposing anything it feeds.
            drainInFlightFrames()
            runCatching { videoTrack?.dispose() }
            videoTrack = null
            runCatching { videoSource?.dispose() }
            videoSource = null
            runCatching { surfaceTextureHelper?.dispose() }
            surfaceTextureHelper = null
            runCatching { peerConnection?.dispose() }
                .onFailure { Log.d(TAG, "PeerConnection dispose ignored: ${it.message}") }
            peerConnection = null
            // A reconnect starts a fresh RTP stream; a bitrate computed across the gap against
            // the old session's byte count would be meaningless (or negative).
            latestNetworkStats = null
            lastStatsBytesSent = 0L
            lastStatsTimestampUs = 0.0
            consumerWatcher?.stop()
            // Only clear the shared slot if it's still ours -- defensive against a reconnect
            // race, even though publish()/teardown() only ever run sequentially on this
            // instance's own single-thread executor today.
            if (WebRTCPeerFactory.activeConsumerWatcher === consumerWatcher) {
                WebRTCPeerFactory.activeConsumerWatcher = null
            }
            consumerWatcher = null
        } finally {
            isTearingDown.set(false)
        }
    }

    private fun drainInFlightFrames() {
        val drained = when (videoCapturer) {
            is SharedVideoCapturerHandle -> videoCapturer.awaitInFlightFramesIdle(FRAME_DRAIN_TIMEOUT_MS)
            is DJIV5VideoCapturer -> videoCapturer.awaitInFlightFramesIdle(FRAME_DRAIN_TIMEOUT_MS)
            else -> true
        }
        if (!drained) Log.w(TAG, "Timed out draining in-flight video frames before dispose")
    }

    /**
     * Disable WebRTC's internal CPU overuse detector which auto-downscales
     * resolution when it thinks the device is under load.
     */
    private fun disableCpuOveruseDetection(rtcConfig: PeerConnection.RTCConfiguration) {
        runCatching {
            val field = rtcConfig.javaClass.getField("enableCpuOveruseDetection")
            field.isAccessible = true
            field.setBoolean(rtcConfig, false)
            Log.d(TAG, "Disabled RTC CPU overuse detection")
        }.onFailure {
            Log.d(TAG, "RTC CPU overuse flag unavailable on this WebRTC build")
        }
    }

    /**
    * Configure the RTP sender to maintain framerate under load:
     * - Set max bitrate and framerate
    * - Set DegradationPreference to MAINTAIN_FRAMERATE (scale before dropping FPS)
     */
    private fun configureVideoSenderForStability(sender: RtpSender) {
        runCatching {
            val params = sender.parameters ?: return
            val encodings = params.encodings ?: emptyList()
            val bitrateCap = options.senderBitrateBps()
            val senderFps = if (isSurfaceEncoderEnabled()) {
                DjiSurfaceVideoCapturer.DRIVER_FPS
            } else {
                currentFps
            }

            encodings.forEach { encoding ->
                runCatching { encoding.maxBitrateBps = bitrateCap }
                runCatching { encoding.maxFramerate = senderFps }
            }

            // Force adaptation strategy toward FPS reduction before resolution reduction
            runCatching {
                val preferenceClass = Class.forName("org.webrtc.RtpParameters\$DegradationPreference")
                @Suppress("UNCHECKED_CAST")
                val enumClass = preferenceClass as Class<out Enum<*>>
                val maintainFramerate = java.lang.Enum.valueOf(enumClass, "MAINTAIN_FRAMERATE")
                val field = params.javaClass.getField("degradationPreference")
                field.isAccessible = true
                field.set(params, maintainFramerate)
            }

            sender.parameters = params
            Log.d(
                TAG,
                "Sender params tuned: maxBitrate=${bitrateCap}bps, " +
                    "maxFps=$senderFps, prefer=MAINTAIN_FRAMERATE"
            )
        }.onFailure { e ->
            Log.w(TAG, "Unable to fully apply sender tuning: ${e.message}")
        }
    }

    private fun isSurfaceEncoderEnabled(): Boolean = appContext
        .getSharedPreferences("LyrebirdPrefs", Context.MODE_PRIVATE)
        .getBoolean(WebRTCPeerFactory.PREF_USE_DJI_SURFACE_H264_ENCODER, false)
}

/**
 * Send-side bitrate from two [PeerConnection.getStats] outbound-rtp samples. Null on the first
 * sample of a session (`previousTimestampUs == 0.0`, nothing to diff against yet) or if the
 * stats clock hasn't advanced (a duplicate/out-of-order callback) -- never a negative or
 * divide-by-zero result.
 */
internal fun sendBitrateBpsFromSample(
    bytesSent: Long,
    timestampUs: Double,
    previousBytesSent: Long,
    previousTimestampUs: Double
): Long? {
    if (previousTimestampUs == 0.0 || timestampUs <= previousTimestampUs) return null
    val elapsedSeconds = (timestampUs - previousTimestampUs) / 1_000_000.0
    val deltaBytes = (bytesSent - previousBytesSent).coerceAtLeast(0L)
    return (deltaBytes * 8 / elapsedSeconds).toLong()
}

internal fun absoluteWhipResourceUrl(url: URL, location: String?): String? {
    return when {
        location == null -> null
        location.startsWith("http") -> location
        else -> "${url.protocol}://${url.host}${url.portSegment()}$location"
    }
}

private fun URL.portSegment(): String = if (port >= 0) ":$port" else ""

private fun whipRtcConfiguration(): PeerConnection.RTCConfiguration {
    return PeerConnection.RTCConfiguration(
        listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302")
                .createIceServer()
        )
    ).apply {
        sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
    }
}

private fun whipOfferConstraints(): MediaConstraints {
    return MediaConstraints().apply {
        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
    }
}

private fun createAndSetLocalOffer(peerConnection: PeerConnection) {
    val offerLatch = CountDownLatch(1)
    var localSdp: SessionDescription? = null

    peerConnection.createOffer(
        WhipOfferObserver(peerConnection, offerLatch) { sdp -> localSdp = sdp },
        whipOfferConstraints()
    )

    offerLatch.await(5, TimeUnit.SECONDS)
    check(localSdp != null) { "Failed to create SDP offer" }
}

private fun setRemoteWhipAnswer(peerConnection: PeerConnection, answerSdp: String) {
    val answerLatch = CountDownLatch(1)
    val answer = SessionDescription(SessionDescription.Type.ANSWER, answerSdp)
    peerConnection.setRemoteDescription(WhipRemoteDescriptionObserver(answerLatch), answer)
    answerLatch.await(5, TimeUnit.SECONDS)
}

private fun waitForWhipConnectionLoss(
    isRunning: AtomicBoolean,
    connected: AtomicBoolean,
    peerConnection: () -> PeerConnection?,
    tickIntervalMs: Long,
    onTick: () -> Unit
) {
    val loopDelayMs = 500L
    var elapsedSinceTickMs = 0L
    while (
        isRunning.get() &&
        (connected.get() || peerConnection()?.iceConnectionState() == PeerConnection.IceConnectionState.CHECKING)
    ) {
        Thread.sleep(loopDelayMs)
        elapsedSinceTickMs += loopDelayMs
        if (elapsedSinceTickMs >= tickIntervalMs) {
            elapsedSinceTickMs = 0L
            onTick()
        }
    }

    if (isRunning.get()) {
        Log.w("WhipPublisher", "WHIP connection lost — will reconnect")
    }
}

@Suppress("TooManyFunctions")
private class WhipConnectionObserver(
    private val iceGatherLatch: CountDownLatch,
    private val connected: AtomicBoolean,
    private val onPublishing: () -> Unit
) : PeerConnection.Observer {
    override fun onSignalingChange(s: PeerConnection.SignalingState) = Unit

    override fun onIceConnectionChange(s: PeerConnection.IceConnectionState) {
        Log.d("WhipPublisher", "ICE connection: $s")
        when (s) {
            PeerConnection.IceConnectionState.CONNECTED -> {
                connected.set(true)
                onPublishing()
            }
            PeerConnection.IceConnectionState.FAILED,
            PeerConnection.IceConnectionState.DISCONNECTED,
            PeerConnection.IceConnectionState.CLOSED -> {
                connected.set(false)
            }
            else -> Unit
        }
    }

    override fun onIceConnectionReceivingChange(b: Boolean) = Unit

    override fun onIceGatheringChange(s: PeerConnection.IceGatheringState) {
        if (s == PeerConnection.IceGatheringState.COMPLETE) {
            iceGatherLatch.countDown()
        }
    }

    override fun onIceCandidate(c: IceCandidate) = Unit
    override fun onIceCandidatesRemoved(c: Array<out IceCandidate>) = Unit
    override fun onAddStream(s: MediaStream) = Unit
    override fun onRemoveStream(s: MediaStream) = Unit
    override fun onDataChannel(dc: DataChannel) = Unit
    override fun onRenegotiationNeeded() = Unit
    override fun onAddTrack(r: RtpReceiver, ss: Array<out MediaStream>) = Unit
}

private class WhipOfferObserver(
    private val peerConnection: PeerConnection,
    private val offerLatch: CountDownLatch,
    private val onLocalSdp: (SessionDescription) -> Unit
) : SdpObserver {
    override fun onCreateSuccess(sdp: SessionDescription) {
        val mungedSdp = SessionDescription(
            sdp.type,
            SdpUtils.mungeForH264(sdp.description)
        )
        peerConnection.setLocalDescription(
            WhipSetLocalDescriptionObserver(offerLatch) { onLocalSdp(mungedSdp) },
            mungedSdp
        )
    }

    override fun onCreateFailure(err: String) {
        Log.e("WhipPublisher", "createOffer failed: $err")
        offerLatch.countDown()
    }

    override fun onSetSuccess() = Unit
    override fun onSetFailure(s: String?) = Unit
}

private class WhipSetLocalDescriptionObserver(
    private val offerLatch: CountDownLatch,
    private val onSet: () -> Unit
) : SdpObserver {
    override fun onSetSuccess() {
        onSet()
        offerLatch.countDown()
    }

    override fun onSetFailure(err: String) {
        Log.e("WhipPublisher", "setLocalDescription failed: $err")
        offerLatch.countDown()
    }

    override fun onCreateSuccess(s: SessionDescription?) = Unit
    override fun onCreateFailure(s: String?) = Unit
}

private class WhipRemoteDescriptionObserver(private val answerLatch: CountDownLatch) : SdpObserver {
    override fun onSetSuccess() {
        answerLatch.countDown()
    }

    override fun onSetFailure(err: String) {
        Log.e("WhipPublisher", "setRemoteDescription failed: $err")
        answerLatch.countDown()
    }

    override fun onCreateSuccess(s: SessionDescription?) = Unit
    override fun onCreateFailure(s: String?) = Unit
}

private fun createFirstFrameGate(capturer: VideoCapturer): WhipFirstFrameGate? {
    return when (capturer) {
        is SharedVideoCapturerHandle -> WhipFirstFrameGate(
            waiter = object : WhipFirstFrameWaiter {
                override fun totalOutputFrames(): Long = capturer.totalOutputFrames()
                override fun waitForOutputFrameAfter(frameCount: Long, timeoutMs: Long): Boolean {
                    return capturer.waitForOutputFrameAfter(frameCount, timeoutMs)
                }
            },
            unavailableMessage = "No DJI video frames available for WHIP publishing",
            recoverBeforeRetry = { capturer.recoverCapture("no frames before WHIP offer") },
            recoveryLogMessage = "No DJI video frames before WHIP offer; recovering capture"
        )
        is MockMp4VideoCapturer -> WhipFirstFrameGate(
            waiter = object : WhipFirstFrameWaiter {
                override fun totalOutputFrames(): Long = capturer.totalOutputFrames()
                override fun waitForOutputFrameAfter(frameCount: Long, timeoutMs: Long): Boolean {
                    return capturer.waitForOutputFrameAfter(frameCount, timeoutMs)
                }
            },
            unavailableMessage = "No mock MP4 video frames available for WHIP publishing"
        )
        is SharedPhoneVideoCapturerHandle -> WhipFirstFrameGate(
            waiter = object : WhipFirstFrameWaiter {
                override fun totalOutputFrames(): Long = capturer.totalOutputFrames()
                override fun waitForOutputFrameAfter(frameCount: Long, timeoutMs: Long): Boolean {
                    return capturer.waitForOutputFrameAfter(frameCount, timeoutMs)
                }
            },
            unavailableMessage = "No shared phone camera frames available for WHIP publishing"
        )
        else -> null
    }
}

internal interface WhipFirstFrameWaiter {
    fun totalOutputFrames(): Long
    fun waitForOutputFrameAfter(frameCount: Long, timeoutMs: Long): Boolean
}

internal class WhipFirstFrameGate(
    private val waiter: WhipFirstFrameWaiter,
    private val unavailableMessage: String,
    private val recoverBeforeRetry: (() -> Unit)? = null,
    private val recoveryLogMessage: String? = null
) {
    fun totalOutputFrames(): Long = waiter.totalOutputFrames()

    fun awaitFirstFrame(startingFrameCount: Long, firstTimeoutMs: Long, recoveryTimeoutMs: Long) {
        if (waiter.waitForOutputFrameAfter(startingFrameCount, firstTimeoutMs)) return
        recoverBeforeRetry?.let { recover ->
            recoveryLogMessage?.let { Log.w("WhipPublisher", it) }
            recover()
            check(waiter.waitForOutputFrameAfter(startingFrameCount, recoveryTimeoutMs)) { unavailableMessage }
            return
        }
        error(unavailableMessage)
    }
}

private fun sleepBeforeReconnect(delayMs: Long): Boolean {
    return try {
        Thread.sleep(delayMs)
        true
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        false
    }
}
