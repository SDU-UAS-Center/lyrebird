package com.lyrebird.rc.webrtc

import android.util.Log
import dji.sdk.keyvalue.key.ProductKey
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.sdk.keyvalue.value.product.ProductType
import dji.v5.et.create
import dji.v5.et.get
import dji.v5.manager.datacenter.MediaDataCenter
import dji.v5.manager.interfaces.ICameraStreamManager
import org.webrtc.CapturerObserver
import org.webrtc.NV21Buffer
import org.webrtc.VideoFrame
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Shared video frame source that registers a single DJI CameraFrameListener,
 * scales once, and broadcasts the resulting VideoFrame to all registered
 * WebRTC CapturerObservers.  This eliminates duplicate NV21→scale→encode work
 * when multiple viewers are connected.
 */
@Suppress("TooManyFunctions")
class SharedDJIFrameSource(
    private val preferredCameraIndex: ComponentIndexType,
    private val droneName: String
) {
    companion object {
        private const val TAG = "SharedDJIFrameSource"

        // Order in which we fall back when the preferred camera index is not
        // exposed by the connected aircraft (e.g. M350 may not publish
        // LEFT_OR_MAIN; Mini 4 Pro typically does).
        private val CAMERA_PREFERENCE = listOf(
            ComponentIndexType.LEFT_OR_MAIN,
            ComponentIndexType.FPV,
            ComponentIndexType.RIGHT,
            ComponentIndexType.UP
        )

        // Payload/accessory ports (PORT_1..PORT_8) are never video cameras — they carry
        // non-imaging payloads such as a hook on a multi-port aircraft (e.g. M400). The SDK
        // still lists them in the available-camera set, so we exclude them from auto-selection;
        // otherwise the last-resort fallback could bind the video stream to a payload port and
        // the bridge shows no camera. Drop one here if it ever genuinely carries a camera.
        private val NON_CAMERA_INDICES = setOf(
            ComponentIndexType.PORT_4
        )
    }

    /** Currently used camera index. Resolved dynamically from the available
     *  camera list reported by the SDK. Starts at the caller's preferred
     *  index but is updated as soon as the SDK publishes the real list. */
    @Volatile
    private var activeCameraIndex: ComponentIndexType = preferredCameraIndex

    private val observers = ConcurrentHashMap<String, CapturerObserver>()
    private val metadataListeners = ConcurrentHashMap<String, DJIV5VideoCapturer.FrameMetadataListener>()
    private val isCapturing = AtomicBoolean(false)
    private val edgeDetectionActive = AtomicBoolean(false)

    interface EdgeDetectionFrameListener {
        fun onNv21Frame(frame: Nv21Frame)
    }

    data class Nv21Frame(
        val data: ByteArray,
        val offset: Int,
        val length: Int,
        val width: Int,
        val height: Int,
        val timestampNs: Long
    )

    @Volatile var targetWidth: Int = DJIV5VideoCapturer.FULL_HD_WIDTH
    @Volatile var targetHeight: Int = DJIV5VideoCapturer.FULL_HD_HEIGHT
    @Volatile private var scaleToTarget: Boolean = true
    @Volatile private var targetFps: Int = 30
    @Volatile private var frameIntervalNs: Long = 1_000_000_000L / 30L
    @Volatile var metricsListener: ((WebRTCStreamMetrics) -> Unit)? = null
    @Volatile private var edgeDetectionFrameListener: EdgeDetectionFrameListener? = null
    private val lastSentTimestampNs = AtomicLong(0L)
    private val frameCounter = AtomicLong(0)
    private val incomingFrameCounter = AtomicLong(0)
    //: Frames currently being handled on DJI's live-view thread; teardown waits on this before
    //: disposing anything the observers feed into (see awaitInFlightFramesIdle).
    private val inFlightFrames = AtomicInteger(0)
    private val frameIdleLock = Object()
    private val droppedFrameCounter = AtomicLong(0)
    private val processingErrorCounter = AtomicLong(0)
    private val recoveryCounter = AtomicLong(0)
    private val frameWaitLock = Object()
    private val sentFramesInWindow = AtomicLong(0)
    private val inputFramesInWindow = AtomicLong(0)
    private val droppedFramesInWindow = AtomicLong(0)
    private val processingTimeNsInWindow = AtomicLong(0)
    private var lastSourceWidth = 0
    private var lastSourceHeight = 0
    private var lastOutputWidth = 0
    private var lastOutputHeight = 0
    private var lastMetricsTimestampNs = System.nanoTime()
    @Volatile private var lastError: String? = null

    private val cameraStreamManager: ICameraStreamManager by lazy {
        MediaDataCenter.getInstance().cameraStreamManager
    }

    private val availableCameraListener = object : ICameraStreamManager.AvailableCameraUpdatedListener {
        override fun onAvailableCameraUpdated(availableCameraList: MutableList<ComponentIndexType>) {
            onAvailableCamerasChanged(availableCameraList)
        }

        override fun onCameraStreamEnableUpdate(cameraStreamEnableMap: MutableMap<ComponentIndexType, Boolean>) {
            // Not used; frame availability is detected by the frame callback itself.
        }
    }

    init {
        // Begin observing available cameras as early as possible so we can
        // attach the frame listener to a camera index that actually exists
        // on the connected aircraft.
        runCatching {
            cameraStreamManager.addAvailableCameraUpdatedListener(availableCameraListener)
        }.onFailure { error ->
            Log.w(TAG, "Could not register available-camera listener: ${error.message}")
        }
    }

    /**
     * Pick the best camera to stream from given the list reported by the SDK.
     * Preference order:
     *  1. The caller's preferred index (if present in the list).
     *  2. Any entry from CAMERA_PREFERENCE that is present, in order.
     *  3. The first entry in the list as a last resort.
     */
    // Only the M400 exposes payload ports (a hook etc.) that pollute the camera list; other
    // aircraft are left untouched. Read live so it tracks the currently connected drone.
    private fun isMatrice400(): Boolean =
        ProductKey.KeyProductType.create().get(ProductType.UNKNOWN) == ProductType.DJI_MATRICE_400

    private fun pickCameraIndex(available: List<ComponentIndexType>): ComponentIndexType? {
        // On the M400, drop payload/accessory ports (e.g. a hook on PORT_4) so they can never be
        // selected. Other aircraft keep the full list unchanged.
        val cameras = if (isMatrice400()) available.filterNot { it in NON_CAMERA_INDICES } else available
        return preferredCameraIndex.takeIf { cameras.contains(it) }
            ?: CAMERA_PREFERENCE.firstOrNull { cameras.contains(it) }
            ?: cameras.firstOrNull()
    }

    @Synchronized
    private fun onAvailableCamerasChanged(available: List<ComponentIndexType>) {
        val resolved = pickCameraIndex(available) ?: run {
            Log.w(TAG, "Available camera list is empty — keeping current index $activeCameraIndex")
            return
        }
        if (resolved == activeCameraIndex) {
            Log.d(TAG, "Available cameras updated ($available); active index unchanged: $activeCameraIndex")
            return
        }
        val previous = activeCameraIndex
        activeCameraIndex = resolved
        Log.i(
            TAG,
            "Active camera index changed: $previous -> $resolved " +
                "(available: $available, preferred: $preferredCameraIndex)"
        )

        // If we're already streaming, re-attach the frame listener to the new index.
        if (isCapturing.get()) {
            runCatching {
                cameraStreamManager.removeFrameListener(frameListener)
                cameraStreamManager.addFrameListener(
                    activeCameraIndex,
                    ICameraStreamManager.FrameFormat.NV21,
                    frameListener
                )
                Log.i(TAG, "Re-attached frame listener on $activeCameraIndex")
            }.onFailure { error ->
                Log.e(TAG, "Failed to re-attach frame listener on $activeCameraIndex: ${error.message}", error)
            }
        }
    }

    private val frameProcessor = FrameProcessor()

    private val frameListener = object : ICameraStreamManager.CameraFrameListener {
        override fun onFrame(
            frameData: ByteArray,
            offset: Int,
            length: Int,
            width: Int,
            height: Int,
            format: ICameraStreamManager.FrameFormat
        ) {
            // Count the callback before anything else. Teardown removes its observer and then
            // waits for this counter to drain, so a frame already dispatched on DJI's live-view
            // thread can never outlive the WebRTC VideoSource it delivers into -- disposing that
            // first aborts inside the native lib (pthread_mutex_lock on a destroyed mutex).
            inFlightFrames.incrementAndGet()
            try {
                if (isCapturing.get()) {
                    val frame = Nv21Frame(frameData, offset, length, width, height, System.nanoTime())
                    edgeDetectionFrameListener?.onNv21Frame(frame)

                    val recipients = DjiFrameRecipients.capture(observers, metadataListeners)
                    if (recipients.hasObservers) {
                        frameProcessor.process(frame, recipients)
                    }
                }
            } finally {
                if (inFlightFrames.decrementAndGet() == 0) {
                    synchronized(frameIdleLock) { frameIdleLock.notifyAll() }
                }
            }
        }
    }

    private inner class FrameProcessor {
        fun process(frame: Nv21Frame, recipients: DjiFrameRecipients) {
            runCatching {
                incomingFrameCounter.incrementAndGet()
                inputFramesInWindow.incrementAndGet()

                if (shouldDropForTargetFrameRate(frame.timestampNs)) {
                    recordDroppedFrame(frame.timestampNs)
                } else {
                    deliverFrame(frame, recipients)
                }
            }.onFailure { error ->
                processingErrorCounter.incrementAndGet()
                lastError = error.message
                Log.e(TAG, "Error processing frame: ${error.message}", error)
            }
        }

        private fun deliverFrame(frame: Nv21Frame, recipients: DjiFrameRecipients) {
            updateSourceSize(frame.width, frame.height)
            val frameNumber = recordAcceptedFrame(frame.timestampNs)
            val (outputWidth, outputHeight) = chooseOutputSize(frame.width, frame.height)
            lastOutputWidth = outputWidth
            lastOutputHeight = outputHeight

            deliverMetadata(frameNumber, frame.timestampNs, outputWidth, outputHeight, recipients)
            deliverVideoFrame(frame, outputWidth, outputHeight, recipients)
            processingTimeNsInWindow.addAndGet(System.nanoTime() - frame.timestampNs)
            maybeEmitMetrics(frame.timestampNs)
        }

        private fun shouldDropForTargetFrameRate(timestampNs: Long): Boolean {
            val previousSent = lastSentTimestampNs.get()
            return previousSent != 0L && (timestampNs - previousSent) < frameIntervalNs
        }

        private fun recordDroppedFrame(timestampNs: Long) {
            droppedFrameCounter.incrementAndGet()
            droppedFramesInWindow.incrementAndGet()
            maybeEmitMetrics(timestampNs)
        }

        private fun updateSourceSize(width: Int, height: Int) {
            if (width != lastSourceWidth || height != lastSourceHeight) {
                lastSourceWidth = width
                lastSourceHeight = height
                Log.d(
                    TAG,
                    "Source: ${width}x${height}, Target: " +
                        "${targetWidth}x${targetHeight}, Scale: $scaleToTarget"
                )
            }
        }

        private fun recordAcceptedFrame(timestampNs: Long): Long {
            lastSentTimestampNs.set(timestampNs)
            val frameNumber = frameCounter.incrementAndGet()
            synchronized(frameWaitLock) {
                frameWaitLock.notifyAll()
            }
            sentFramesInWindow.incrementAndGet()
            return frameNumber
        }

        private fun deliverMetadata(
            frameNumber: Long,
            timestampNs: Long,
            outputWidth: Int,
            outputHeight: Int,
            recipients: DjiFrameRecipients
        ) {
            if (!recipients.hasMetadataListeners) return
            val metadata = TelemetryProvider.captureMetadata(
                frameNumber = frameNumber,
                timestampNs = timestampNs,
                frameWidth = outputWidth,
                frameHeight = outputHeight,
                droneName = droneName
            )
            recipients.singleMetadataListener?.onFrameMetadata(metadata)
                ?: recipients.metadataListeners.forEach { it.onFrameMetadata(metadata) }
        }

        private fun deliverVideoFrame(
            frame: Nv21Frame,
            outputWidth: Int,
            outputHeight: Int,
            recipients: DjiFrameRecipients
        ) {
            val buffer = NV21Buffer(frame.data, frame.width, frame.height, null)
            val needsScale = scaleToTarget && (frame.width != outputWidth || frame.height != outputHeight)
            val outputBuffer = if (needsScale) {
                val scaled = buffer.cropAndScale(0, 0, frame.width, frame.height, outputWidth, outputHeight)
                buffer.release()
                scaled
            } else {
                buffer
            }

            val videoFrame = VideoFrame(outputBuffer, 0, frame.timestampNs)
            try {
                recipients.deliver(videoFrame)
            } finally {
                videoFrame.release()
            }
        }
    }

    fun setEdgeDetectionFrameListener(listener: EdgeDetectionFrameListener?) {
        edgeDetectionFrameListener = listener
        val shouldRun = listener != null
        edgeDetectionActive.set(shouldRun)
        if (shouldRun) {
            ensureFrameListenerAttached("edge detection enabled")
        } else if (observers.isEmpty() && isCapturing.compareAndSet(true, false)) {
            Log.d(TAG, "No observers or edge detector left – stopping shared capture")
            cameraStreamManager.removeFrameListener(frameListener)
        }
    }

    @Synchronized
    private fun ensureFrameListenerAttached(reason: String) {
        if (isCapturing.compareAndSet(false, true)) {
            lastSentTimestampNs.set(0L)
            Log.d(TAG, "Starting shared capture for $reason on camera $activeCameraIndex")
            runCatching { cameraStreamManager.enableStream(activeCameraIndex, true) }
                .onFailure { Log.w(TAG, "Could not enable stream on $activeCameraIndex: ${it.message}") }
            cameraStreamManager.addFrameListener(
                activeCameraIndex,
                ICameraStreamManager.FrameFormat.NV21,
                frameListener
            )
        }
    }

    private fun chooseOutputSize(sourceWidth: Int, sourceHeight: Int): Pair<Int, Int> {
        if (!scaleToTarget) return sourceWidth to sourceHeight
        val boundedWidth = targetWidth.coerceAtMost(sourceWidth).coerceAtLeast(2)
        val boundedHeight = targetHeight.coerceAtMost(sourceHeight).coerceAtLeast(2)
        val evenWidth = boundedWidth - (boundedWidth % 2)
        val evenHeight = boundedHeight - (boundedHeight % 2)
        return evenWidth.coerceAtLeast(2) to evenHeight.coerceAtLeast(2)
    }

    fun totalOutputFrames(): Long = frameCounter.get()

    fun observerCount(): Int = observers.size

    fun waitForOutputFrameAfter(frameCount: Long, timeoutMs: Long): Boolean {
        val deadlineMs = System.currentTimeMillis() + timeoutMs
        synchronized(frameWaitLock) {
            while (frameCounter.get() <= frameCount) {
                val remainingMs = deadlineMs - System.currentTimeMillis()
                if (remainingMs <= 0L) return false
                runCatching { frameWaitLock.wait(remainingMs) }
            }
        }
        return true
    }

    /**
     * Block until every frame currently being handled on DJI's live-view thread has finished, or
     * the timeout elapses.
     *
     * Call after removing your observer and before disposing anything the observers hold native
     * state in (e.g. a WebRTC [VideoSource]): a frame already dispatched on that thread can
     * otherwise still land in a disposed source, which aborts inside WebRTC native
     * (pthread_mutex_lock on a destroyed mutex).
     */
    fun awaitInFlightFramesIdle(timeoutMs: Long): Boolean {
        val deadlineMs = System.currentTimeMillis() + timeoutMs
        synchronized(frameIdleLock) {
            while (inFlightFrames.get() > 0) {
                val remainingMs = deadlineMs - System.currentTimeMillis()
                if (remainingMs <= 0L) return false
                runCatching { frameIdleLock.wait(remainingMs) }
            }
        }
        return true
    }

    private fun maybeEmitMetrics(nowNs: Long) {
        val elapsedNs = nowNs - lastMetricsTimestampNs
        if (elapsedNs < 1_000_000_000L) return

        val elapsedSeconds = elapsedNs / 1_000_000_000.0
        val inputFps = inputFramesInWindow.getAndSet(0) / elapsedSeconds
        val sentFrames = sentFramesInWindow.getAndSet(0)
        val outputFps = sentFrames / elapsedSeconds
        val droppedFps = droppedFramesInWindow.getAndSet(0) / elapsedSeconds
        val processingNs = processingTimeNsInWindow.getAndSet(0)
        val averageProcessingMs = if (sentFrames > 0) processingNs / sentFrames / 1_000_000.0 else 0.0
        lastMetricsTimestampNs = nowNs

        metricsListener?.invoke(
            WebRTCStreamMetrics(
                sourceWidth = lastSourceWidth,
                sourceHeight = lastSourceHeight,
                outputWidth = lastOutputWidth,
                outputHeight = lastOutputHeight,
                requestedWidth = targetWidth,
                requestedHeight = targetHeight,
                targetFps = targetFps,
                inputFps = inputFps,
                outputFps = outputFps,
                droppedFps = droppedFps,
                averageFrameProcessingMs = averageProcessingMs,
                totalFrames = frameCounter.get(),
                totalDroppedFrames = droppedFrameCounter.get(),
                processingErrors = processingErrorCounter.get(),
                observerCount = observers.size,
                activeCamera = activeCameraIndex.name,
                status = if (isCapturing.get()) "running" else "idle",
                recoveryCount = recoveryCounter.get().toInt(),
                lastError = lastError
            )
        )
    }

    // ---- Client management ----

    fun registerObserver(clientId: String, observer: CapturerObserver) {
        observers[clientId] = observer
        Log.d(TAG, "Observer registered: $clientId (total: ${observers.size})")
    }

    fun setMetadataListener(clientId: String, listener: DJIV5VideoCapturer.FrameMetadataListener?) {
        if (listener != null) {
            metadataListeners[clientId] = listener
        } else {
            metadataListeners.remove(clientId)
        }
    }

    /**
     * Start capturing if not already started. If already capturing, the
     * new client immediately begins receiving frames.
     */
    fun startClient(clientId: String, width: Int, height: Int, fps: Int) {
        // Use the first client's requested settings
        if (isCapturing.compareAndSet(false, true)) {
            applyResolutionRequest(width, height)
            targetFps = fps.coerceAtLeast(1)
            frameIntervalNs = 1_000_000_000L / targetFps.toLong()
            lastSentTimestampNs.set(0L)
            Log.d(
                TAG,
                "Starting shared capture: ${targetWidth}x${targetHeight}@$targetFps fps " +
                    "on camera $activeCameraIndex (preferred: $preferredCameraIndex)"
            )
            runCatching { cameraStreamManager.enableStream(activeCameraIndex, true) }
                .onFailure { Log.w(TAG, "Could not enable stream on $activeCameraIndex: ${it.message}") }
            cameraStreamManager.addFrameListener(
                activeCameraIndex,
                ICameraStreamManager.FrameFormat.NV21,
                frameListener
            )
        }
        observers[clientId]?.onCapturerStarted(true)
    }

    fun stopClient(clientId: String) {
        observers[clientId]?.onCapturerStopped()
        observers.remove(clientId)
        metadataListeners.remove(clientId)
        Log.d(TAG, "Client removed: $clientId (remaining: ${observers.size})")
        if (observers.isEmpty() && !edgeDetectionActive.get() && isCapturing.compareAndSet(true, false)) {
            Log.d(TAG, "No observers left – stopping shared capture")
            cameraStreamManager.removeFrameListener(frameListener)
        }
    }

    fun changeResolution(width: Int, height: Int) {
        val previousWidth = targetWidth
        val previousHeight = targetHeight
        val previousScale = scaleToTarget
        applyResolutionRequest(width, height)
        Log.d(
            TAG,
            "Changing target resolution: " +
                "${previousWidth}x${previousHeight} (scale=$previousScale) -> " +
                "${targetWidth}x${targetHeight} (scale=$scaleToTarget)"
        )
    }

    private fun applyResolutionRequest(width: Int, height: Int) {
        if (width <= 0 || height <= 0) {
            targetWidth = 0
            targetHeight = 0
            scaleToTarget = false
            return
        }
        targetWidth = width
        targetHeight = height
        scaleToTarget = true
    }

    fun changeFrameRate(fps: Int) {
        val boundedFps = fps.coerceIn(1, 60)
        Log.d(TAG, "Changing target FPS: $targetFps -> $boundedFps")
        targetFps = boundedFps
        frameIntervalNs = 1_000_000_000L / boundedFps.toLong()
        lastSentTimestampNs.set(0L)
    }

    /** Enable the selected DJI camera without registering the NV21 observer path. */
    fun prepareSurfaceCapture() {
        runCatching { cameraStreamManager.enableStream(activeCameraIndex, true) }
            .onFailure {
                Log.w(TAG, "Could not enable surface stream on $activeCameraIndex: ${it.message}")
            }
        Log.d(TAG, "Prepared surface capture on $activeCameraIndex")
    }

    @Synchronized
    fun recoverCapture(reason: String) {
        if (!isCapturing.get()) return
        recoveryCounter.incrementAndGet()
        lastSentTimestampNs.set(0L)
        runCatching { cameraStreamManager.removeFrameListener(frameListener) }
        runCatching { cameraStreamManager.enableStream(activeCameraIndex, true) }
            .onFailure {
                Log.w(TAG, "Could not enable stream during listener reset on $activeCameraIndex: ${it.message}")
            }
        cameraStreamManager.addFrameListener(
            activeCameraIndex,
            ICameraStreamManager.FrameFormat.NV21,
            frameListener
        )
        Log.w(TAG, "Reset DJI frame listener on $activeCameraIndex: $reason")
    }

    fun dispose() {
        if (isCapturing.compareAndSet(true, false)) {
            cameraStreamManager.removeFrameListener(frameListener)
        }
        runCatching {
            cameraStreamManager.removeAvailableCameraUpdatedListener(availableCameraListener)
        }.onFailure { error -> Log.w(TAG, "Could not remove available-camera listener: ${error.message}") }
        observers.clear()
        metadataListeners.clear()
        edgeDetectionFrameListener = null
        edgeDetectionActive.set(false)
        metricsListener = null
        Log.d(TAG, "SharedDJIFrameSource disposed")
    }
}

private data class DjiFrameRecipients(
    val singleObserver: CapturerObserver?,
    val observers: List<CapturerObserver>,
    val singleMetadataListener: DJIV5VideoCapturer.FrameMetadataListener?,
    val metadataListeners: List<DJIV5VideoCapturer.FrameMetadataListener>
) {
    val hasObservers: Boolean = singleObserver != null || observers.isNotEmpty()
    val hasMetadataListeners: Boolean = singleMetadataListener != null || metadataListeners.isNotEmpty()

    fun deliver(videoFrame: VideoFrame) {
        if (singleObserver != null) {
            singleObserver.onFrameCaptured(videoFrame)
        } else {
            repeat((observers.size - 1).coerceAtLeast(0)) { videoFrame.retain() }
            observers.forEach { it.onFrameCaptured(videoFrame) }
        }
    }

    companion object {
        fun capture(
            observers: ConcurrentHashMap<String, CapturerObserver>,
            metadataListeners: ConcurrentHashMap<String, DJIV5VideoCapturer.FrameMetadataListener>
        ): DjiFrameRecipients {
            val singleObserver = observers.singleValueOrNull()
            val observerSnapshot = if (singleObserver == null) observers.values.toList() else emptyList()
            val singleMetadataListener = metadataListeners.singleValueOrNull()
            val metadataSnapshot = if (singleMetadataListener == null) {
                metadataListeners.values.toList()
            } else {
                emptyList()
            }
            return DjiFrameRecipients(singleObserver, observerSnapshot, singleMetadataListener, metadataSnapshot)
        }

        private fun <T> ConcurrentHashMap<String, T>.singleValueOrNull(): T? {
            return if (size == 1) values.firstOrNull() else null
        }
    }
}
