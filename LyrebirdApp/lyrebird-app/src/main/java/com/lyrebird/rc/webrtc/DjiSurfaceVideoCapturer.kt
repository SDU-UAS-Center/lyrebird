package com.lyrebird.rc.webrtc

import android.content.Context
import android.util.Log
import org.webrtc.CapturerObserver
import org.webrtc.JavaI420Buffer
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoFrame
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Drives the WebRTC video pipeline while [DjiSurfaceH264Encoder] receives the real DJI frames
 * directly through its MediaCodec input surface. The pixels in these frames are intentionally
 * unused by the surface encoder; their cadence keeps encode() aligned with asynchronous codec
 * output without registering a second DJI NV21 listener.
 */
internal class DjiSurfaceVideoCapturer : VideoCapturer {
    companion object {
        private const val TAG = "DjiSurfaceVideoCapturer"
        const val DRIVER_FPS = 30
        private const val DEFAULT_WIDTH = 1920
        private const val DEFAULT_HEIGHT = 1080
    }

    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "DjiSurfaceFrameDriver").apply { isDaemon = true }
    }
    private val isCapturing = AtomicBoolean(false)
    private val totalFrameCount = AtomicLong(0)
    private val windowFrameCount = AtomicLong(0)
    private var capturerObserver: CapturerObserver? = null
    @Volatile var metricsListener: ((WebRTCStreamMetrics) -> Unit)? = null
    private var frameBuffer: JavaI420Buffer? = null
    private var frameTask: ScheduledFuture<*>? = null
    private var lastMetricsAtNs = System.nanoTime()
    private var width = DEFAULT_WIDTH
    private var height = DEFAULT_HEIGHT

    override fun initialize(
        surfaceTextureHelper: SurfaceTextureHelper?,
        applicationContext: Context,
        capturerObserver: CapturerObserver
    ) {
        this.capturerObserver = capturerObserver
    }

    override fun startCapture(width: Int, height: Int, framerate: Int) {
        if (!isCapturing.compareAndSet(false, true)) return
        this.width = width.takeIf { it > 0 } ?: DEFAULT_WIDTH
        this.height = height.takeIf { it > 0 } ?: DEFAULT_HEIGHT
        frameBuffer = JavaI420Buffer.allocate(this.width, this.height)
        capturerObserver?.onCapturerStarted(true)
        val intervalMs = 1000L / DRIVER_FPS
        frameTask = executor.scheduleAtFixedRate(::emitFrame, 0L, intervalMs, TimeUnit.MILLISECONDS)
        Log.i(TAG, "Started synthetic driver: ${this.width}x${this.height}@${DRIVER_FPS}fps")
    }

    private fun emitFrame() {
        if (!isCapturing.get()) return
        val buffer = frameBuffer ?: return
        buffer.retain()
        val frame = VideoFrame(buffer, 0, System.nanoTime())
        try {
            capturerObserver?.onFrameCaptured(frame)
        } finally {
            frame.release()
        }
        totalFrameCount.incrementAndGet()
        windowFrameCount.incrementAndGet()
        emitMetricsIfDue()
    }

    private fun emitMetricsIfDue() {
        val nowNs = System.nanoTime()
        val elapsedNs = nowNs - lastMetricsAtNs
        if (elapsedNs < 1_000_000_000L) return
        val frames = windowFrameCount.getAndSet(0)
        val fps = frames * 1_000_000_000.0 / elapsedNs.toDouble()
        metricsListener?.invoke(
            WebRTCStreamMetrics(
                sourceWidth = width,
                sourceHeight = height,
                outputWidth = width,
                outputHeight = height,
                targetFps = DRIVER_FPS,
                inputFps = fps,
                outputFps = fps,
                totalFrames = totalFrameCount.get(),
                observerCount = 1,
                activeCamera = "surface",
                status = if (isCapturing.get()) "running" else "idle",
                configuredFps = DRIVER_FPS,
                scaleMode = "surface"
            )
        )
        lastMetricsAtNs = nowNs
    }

    override fun stopCapture() {
        if (!isCapturing.compareAndSet(true, false)) return
        frameTask?.cancel(false)
        frameTask = null
        capturerObserver?.onCapturerStopped()
        emitMetricsIfDue()
        Log.i(TAG, "Stopped synthetic driver")
    }

    override fun changeCaptureFormat(width: Int, height: Int, framerate: Int) {
        if (width > 0 && height > 0 && (width != this.width || height != this.height)) {
            frameBuffer?.release()
            this.width = width
            this.height = height
            frameBuffer = JavaI420Buffer.allocate(width, height)
        }
    }

    override fun dispose() {
        stopCapture()
        frameBuffer?.release()
        frameBuffer = null
        capturerObserver = null
        metricsListener = null
        executor.shutdownNow()
    }

    override fun isScreencast(): Boolean = false
}
