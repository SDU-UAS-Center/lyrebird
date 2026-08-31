package com.lyrebird.rc.webrtc

import android.content.Context
import android.util.Log
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.v5.manager.datacenter.MediaDataCenter
import dji.v5.manager.interfaces.ICameraStreamManager
import org.webrtc.CapturerObserver
import org.webrtc.NV21Buffer
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoFrame
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * VideoCapturer implementation for DJI SDK V5 that captures frames
 * from the drone's camera using CameraStreamManager.
 *
 * This adapter captures YUV frames from the DJI camera and converts them
 * to WebRTC VideoFrames for transmission via WebRTC.
 *
 * Also captures synchronized telemetry metadata for each frame.
 */
class DJIV5VideoCapturer(
    private val cameraIndex: ComponentIndexType = ComponentIndexType.LEFT_OR_MAIN,
    @Volatile var targetWidth: Int = FULL_HD_WIDTH,
    @Volatile var targetHeight: Int = FULL_HD_HEIGHT,
    @Volatile private var scaleToTarget: Boolean = true,
    private val droneName: String = "drone_1"
) : VideoCapturer {

    companion object {
        private const val TAG = "DJIV5VideoCapturer"

        // Resolution presets
        const val FULL_HD_WIDTH = 1920
        const val FULL_HD_HEIGHT = 1080
        const val HD_WIDTH = 1280
        const val HD_HEIGHT = 720
    }

    private var capturerObserver: CapturerObserver? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private val isCapturing = AtomicBoolean(false)
    @Volatile private var targetFps = 30
    @Volatile private var frameIntervalNs = 1_000_000_000L / 30L
    private var lastSourceWidth = 0
    private var lastSourceHeight = 0
    private val lastSentTimestampNs = AtomicLong(0L)

    // Frame counter for metadata synchronization
    private val frameCounter = AtomicLong(0)
    //: Frames currently being handled on DJI's live-view thread; teardown waits on this before
    //: disposing the observer it feeds (see awaitInFlightFramesIdle).
    private val inFlightFrames = AtomicInteger(0)
    private val frameIdleLock = Object()

    // Listener for frame metadata (called for each frame with synchronized telemetry)
    var metadataListener: FrameMetadataListener? = null

    interface FrameMetadataListener {
        fun onFrameMetadata(metadata: FrameMetadata)
    }

    private val cameraStreamManager: ICameraStreamManager by lazy {
        MediaDataCenter.getInstance().cameraStreamManager
    }

    private val frameListener = object : ICameraStreamManager.CameraFrameListener {
        override fun onFrame(
            frameData: ByteArray,
            offset: Int,
            length: Int,
            width: Int,
            height: Int,
            format: ICameraStreamManager.FrameFormat
        ) {
            // Count before anything else so teardown can wait for in-flight delivery before
            // disposing the observer (see awaitInFlightFramesIdle).
            inFlightFrames.incrementAndGet()
            try {
                if (!isCapturing.get() || capturerObserver == null) return

                runCatching {
                    processFrame(frameData, width, height)
                }.onFailure { error ->
                    Log.e(TAG, "Error processing frame: ${error.message}", error)
                }
            } finally {
                if (inFlightFrames.decrementAndGet() == 0) {
                    synchronized(frameIdleLock) { frameIdleLock.notifyAll() }
                }
            }
        }
    }

    private fun processFrame(frameData: ByteArray, width: Int, height: Int) {
        val timestampNs = System.nanoTime()
        val previousSent = lastSentTimestampNs.get()
        if (previousSent != 0L && timestampNs - previousSent < frameIntervalNs) return
        lastSentTimestampNs.set(timestampNs)

        if (width != lastSourceWidth || height != lastSourceHeight) {
            lastSourceWidth = width
            lastSourceHeight = height
            Log.d(
                TAG,
                "Source: ${width}x${height}, Target: " +
                    "${targetWidth}x${targetHeight}, Scale: $scaleToTarget"
            )
        }

        val frameNumber = frameCounter.incrementAndGet()
        val (outputWidth, outputHeight) = chooseOutputSize(width, height)
        notifyMetadataListener(frameNumber, timestampNs, outputWidth, outputHeight)
        DjiVideoFrameDelivery.deliver(
            request = DjiFrameDeliveryRequest(
                frameData = frameData,
                sourceWidth = width,
                sourceHeight = height,
                outputWidth = outputWidth,
                outputHeight = outputHeight,
                timestampNs = timestampNs
            ),
            observer = capturerObserver,
            scaleToTarget = scaleToTarget
        )
    }

    private fun notifyMetadataListener(
        frameNumber: Long,
        timestampNs: Long,
        outputWidth: Int,
        outputHeight: Int
    ) {
        metadataListener?.let { listener ->
            val metadata = TelemetryProvider.captureMetadata(
                frameNumber = frameNumber,
                timestampNs = timestampNs,
                frameWidth = outputWidth,
                frameHeight = outputHeight,
                droneName = droneName
            )
            Log.v(
                TAG,
                "Captured metadata for frame $frameNumber: " +
                    "lat=${metadata.latitude}, lon=${metadata.longitude}, " +
                    "battery=${metadata.batteryPercent}%"
            )
            listener.onFrameMetadata(metadata)
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

    override fun initialize(
        surfaceTextureHelper: SurfaceTextureHelper?,
        applicationContext: Context,
        capturerObserver: CapturerObserver
    ) {
        Log.d(TAG, "Initializing DJIV5VideoCapturer for camera: $cameraIndex, target: ${targetWidth}x${targetHeight}")
        this.surfaceTextureHelper = surfaceTextureHelper
        this.capturerObserver = capturerObserver
    }

    override fun startCapture(width: Int, height: Int, framerate: Int) {
        changeResolution(width, height)
        targetFps = framerate.coerceAtLeast(1)
        frameIntervalNs = 1_000_000_000L / targetFps.toLong()
        lastSentTimestampNs.set(0L)
        Log.d(TAG, "Starting capture: ${targetWidth}x${targetHeight}@${targetFps}fps (scale=$scaleToTarget)")

        if (isCapturing.compareAndSet(false, true)) {
            // Register frame listener with NV21 format (compatible with WebRTC's NV21Buffer)
            cameraStreamManager.addFrameListener(
                cameraIndex,
                ICameraStreamManager.FrameFormat.NV21,
                frameListener
            )

            capturerObserver?.onCapturerStarted(true)
            Log.d(TAG, "Capture started successfully")
        }
    }

    override fun stopCapture() {
        Log.d(TAG, "Stopping capture")

        if (isCapturing.compareAndSet(true, false)) {
            cameraStreamManager.removeFrameListener(frameListener)
            capturerObserver?.onCapturerStopped()
            Log.d(TAG, "Capture stopped")
        }
    }

    /**
     * Change the target resolution on-the-fly. Takes effect on the next frame.
     */
    fun changeResolution(width: Int, height: Int) {
        val previousWidth = targetWidth
        val previousHeight = targetHeight
        val previousScale = scaleToTarget
        if (width <= 0 || height <= 0) {
            targetWidth = 0
            targetHeight = 0
            scaleToTarget = false
        } else {
            targetWidth = width
            targetHeight = height
            scaleToTarget = true
        }
        Log.d(
            TAG,
            "Changing target resolution: " +
                "${previousWidth}x${previousHeight} (scale=$previousScale) -> " +
                "${targetWidth}x${targetHeight} (scale=$scaleToTarget)"
        )
    }

    override fun changeCaptureFormat(width: Int, height: Int, framerate: Int) {
        Log.d(TAG, "Change capture format requested: ${width}x${height}@${framerate}fps")
        changeResolution(width, height)
        targetFps = framerate.coerceAtLeast(1)
        frameIntervalNs = 1_000_000_000L / targetFps.toLong()
    }

    override fun dispose() {
        Log.d(TAG, "Disposing DJIV5VideoCapturer")
        stopCapture()
        capturerObserver = null
        surfaceTextureHelper = null
    }

    /**
     * Block until every frame currently being handled on DJI's live-view thread has finished, or
     * the timeout elapses. Call before disposing the observer, which is otherwise a use-after-free
     * on the frame thread.
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

    override fun isScreencast(): Boolean = false
}

private data class DjiFrameDeliveryRequest(
    val frameData: ByteArray,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val outputWidth: Int,
    val outputHeight: Int,
    val timestampNs: Long
)

private object DjiVideoFrameDelivery {
    fun deliver(
        request: DjiFrameDeliveryRequest,
        observer: CapturerObserver?,
        scaleToTarget: Boolean
    ) {
        val buffer = NV21Buffer(request.frameData, request.sourceWidth, request.sourceHeight, null)
        val outputBuffer = if (request.needsScale(scaleToTarget)) {
            val scaled = buffer.cropAndScale(
                0,
                0,
                request.sourceWidth,
                request.sourceHeight,
                request.outputWidth,
                request.outputHeight
            )
            buffer.release()
            scaled
        } else {
            buffer
        }

        val videoFrame = VideoFrame(outputBuffer, 0, request.timestampNs)
        observer?.onFrameCaptured(videoFrame)
        videoFrame.release()
    }

    private fun DjiFrameDeliveryRequest.needsScale(scaleToTarget: Boolean): Boolean {
        return scaleToTarget && (sourceWidth != outputWidth || sourceHeight != outputHeight)
    }
}
