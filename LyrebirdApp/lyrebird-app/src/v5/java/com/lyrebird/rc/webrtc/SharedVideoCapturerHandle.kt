package com.lyrebird.rc.webrtc

import android.content.Context
import org.webrtc.CapturerObserver
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer

/**
 * Lightweight [VideoCapturer] proxy that delegates to a [SharedDJIFrameSource]
 * so the expensive DJI frame listener and NV21 scaling run only once.
 */
@Suppress("TooManyFunctions")
class SharedVideoCapturerHandle(
    private val clientId: String,
    private val source: SharedDJIFrameSource,
) : VideoCapturer,
    SharedFrameSourceControl,
    FrameAvailabilityWaiter {
    var metadataListener: DJIV5VideoCapturer.FrameMetadataListener? = null
        set(value) {
            field = value
            source.setMetadataListener(clientId, value)
        }

    override fun initialize(
        surfaceTextureHelper: SurfaceTextureHelper?,
        applicationContext: Context,
        capturerObserver: CapturerObserver,
    ) {
        source.registerObserver(clientId, capturerObserver)
    }

    override fun startCapture(
        width: Int,
        height: Int,
        framerate: Int,
    ) {
        source.startClient(clientId, width, height, framerate)
    }

    override fun stopCapture() {
        source.stopClient(clientId)
    }

    override fun changeResolution(
        width: Int,
        height: Int,
    ) {
        source.changeResolution(width, height)
    }

    override fun changeFrameRate(fps: Int) {
        source.changeFrameRate(fps)
    }

    override fun totalOutputFrames(): Long = source.totalOutputFrames()

    override fun waitForOutputFrameAfter(
        frameCount: Long,
        timeoutMs: Long,
    ): Boolean = source.waitForOutputFrameAfter(frameCount, timeoutMs)

    override fun recoverCapture(reason: String) {
        source.recoverCapture(reason)
    }

    override fun awaitInFlightFramesIdle(timeoutMs: Long): Boolean = source.awaitInFlightFramesIdle(timeoutMs)

    override fun changeCaptureFormat(
        width: Int,
        height: Int,
        framerate: Int,
    ) {
        source.changeResolution(width, height)
        source.changeFrameRate(framerate)
    }

    override fun dispose() {
        source.stopClient(clientId)
    }

    override fun isScreencast(): Boolean = false
}
