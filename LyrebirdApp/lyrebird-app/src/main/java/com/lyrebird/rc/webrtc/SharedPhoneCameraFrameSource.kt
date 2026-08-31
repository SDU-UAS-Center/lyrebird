package com.lyrebird.rc.webrtc

import android.media.Image
import android.util.Log
import org.webrtc.CapturerObserver
import org.webrtc.NV21Buffer
import org.webrtc.VideoFrame
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

object SharedPhoneCameraFrameSource {
    private const val TAG = "SharedPhoneCameraFrameSource"

    private data class ClientState(
        val observer: CapturerObserver,
        @Volatile var running: Boolean = false,
        @Volatile var width: Int = 1280,
        @Volatile var height: Int = 720,
        @Volatile var fps: Int = 10,
        @Volatile var lastFrameNs: Long = 0L
    )

    private val clients = ConcurrentHashMap<String, ClientState>()
    private val frameCounter = AtomicLong(0L)
    private val frameWaitLock = Object()

    fun registerObserver(clientId: String, observer: CapturerObserver) {
        clients[clientId] = ClientState(observer = observer)
    }

    fun startClient(clientId: String, width: Int, height: Int, fps: Int) {
        val client = clients[clientId] ?: return
        client.width = width.takeIf { it > 0 } ?: client.width
        client.height = height.takeIf { it > 0 } ?: client.height
        client.fps = fps.coerceIn(1, 60)
        client.running = true
        client.observer.onCapturerStarted(true)
        Log.i(TAG, "Phone shared capture started for $clientId: ${client.width}x${client.height}@${client.fps}")
    }

    fun stopClient(clientId: String) {
        val client = clients[clientId] ?: return
        if (client.running) {
            client.running = false
            client.observer.onCapturerStopped()
        }
    }

    fun unregisterClient(clientId: String) {
        stopClient(clientId)
        clients.remove(clientId)
    }

    fun changeFormat(width: Int, height: Int, fps: Int) {
        clients.values.forEach { client ->
            if (width > 0) client.width = width
            if (height > 0) client.height = height
            client.fps = fps.coerceIn(1, 60)
        }
    }

    fun hasRunningClients(): Boolean = clients.values.any { it.running }

    fun observerCount(): Int = clients.values.count { it.running }

    fun totalOutputFrames(): Long = frameCounter.get()

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

    fun offerImage(image: Image, timestampNs: Long): Boolean {
        val eligibleClients = clients.values.filter { client ->
            val frameIntervalNs = 1_000_000_000L / client.fps.coerceAtLeast(1)
            client.running && timestampNs - client.lastFrameNs >= frameIntervalNs
        }
        if (eligibleClients.isEmpty()) return false

        return runCatching {
            val nv21 = PhoneImageConverter.toNv21(image)
            val sourceWidth = image.width
            val sourceHeight = image.height
            val frameNumber = frameCounter.incrementAndGet()
            synchronized(frameWaitLock) {
                frameWaitLock.notifyAll()
            }

            eligibleClients.forEach { client ->
                client.lastFrameNs = timestampNs
                val sourceBuffer = NV21Buffer(nv21, sourceWidth, sourceHeight, null)
                val outputWidth = PhoneImageConverter.even(
                    client.width.coerceAtMost(sourceWidth).coerceAtLeast(2)
                )
                val outputHeight = PhoneImageConverter.even(
                    client.height.coerceAtMost(sourceHeight).coerceAtLeast(2)
                )
                val outputBuffer = if (outputWidth != sourceWidth || outputHeight != sourceHeight) {
                    val scaled = sourceBuffer.cropAndScale(
                        0,
                        0,
                        sourceWidth,
                        sourceHeight,
                        outputWidth,
                        outputHeight
                    )
                    sourceBuffer.release()
                    scaled
                } else {
                    sourceBuffer
                }
                val videoFrame = VideoFrame(outputBuffer, 0, timestampNs)
                try {
                    client.observer.onFrameCaptured(videoFrame)
                } finally {
                    videoFrame.release()
                }
            }
            Log.v(
                TAG,
                "Shared phone frame delivered: #$frameNumber " +
                    "${sourceWidth}x${sourceHeight} clients=${eligibleClients.size}"
            )
            true
        }.onFailure {
            Log.e(TAG, "Failed to deliver phone frame to WebRTC: ${it.message}", it)
        }.getOrDefault(false)
    }
}

private object PhoneImageConverter {
    private data class PlaneCopyTarget(
        val output: ByteArray,
        val offset: Int,
        val pixelStride: Int
    )

    fun toNv21(image: Image): ByteArray {
        val width = image.width
        val height = image.height
        val output = ByteArray(width * height * 3 / 2)
        copyPlane(image.planes[0], width, height, PlaneCopyTarget(output, 0, 1))
        val chromaOffset = width * height
        copyPlane(image.planes[2], width / 2, height / 2, PlaneCopyTarget(output, chromaOffset, 2))
        copyPlane(image.planes[1], width / 2, height / 2, PlaneCopyTarget(output, chromaOffset + 1, 2))
        return output
    }

    private fun copyPlane(plane: Image.Plane, width: Int, height: Int, target: PlaneCopyTarget) {
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        var outputIndex = target.offset
        for (row in 0 until height) {
            val rowStart = row * rowStride
            for (column in 0 until width) {
                target.output[outputIndex] = buffer.get(rowStart + column * pixelStride)
                outputIndex += target.pixelStride
            }
        }
    }

    fun even(value: Int): Int = (value - value % 2).coerceAtLeast(2)
}

class SharedPhoneVideoCapturerHandle(
    private val clientId: String,
    private val source: SharedPhoneCameraFrameSource = SharedPhoneCameraFrameSource
) : org.webrtc.VideoCapturer {

    override fun initialize(
        surfaceTextureHelper: org.webrtc.SurfaceTextureHelper?,
        applicationContext: android.content.Context,
        capturerObserver: CapturerObserver
    ) {
        source.registerObserver(clientId, capturerObserver)
    }

    override fun startCapture(width: Int, height: Int, framerate: Int) {
        source.startClient(clientId, width, height, framerate)
    }

    override fun stopCapture() {
        source.stopClient(clientId)
    }

    override fun changeCaptureFormat(width: Int, height: Int, framerate: Int) {
        source.changeFormat(width, height, framerate)
    }

    fun totalOutputFrames(): Long = source.totalOutputFrames()

    fun waitForOutputFrameAfter(frameCount: Long, timeoutMs: Long): Boolean {
        return source.waitForOutputFrameAfter(frameCount, timeoutMs)
    }

    override fun dispose() {
        source.unregisterClient(clientId)
    }

    override fun isScreencast(): Boolean = false
}
