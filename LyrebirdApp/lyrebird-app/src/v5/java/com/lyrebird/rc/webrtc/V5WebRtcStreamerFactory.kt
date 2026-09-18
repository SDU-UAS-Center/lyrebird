package com.lyrebird.rc.webrtc

import android.content.Context
import android.util.Log
import dji.sdk.keyvalue.value.common.ComponentIndexType

internal class V5WebRtcStreamerFactory(
    context: Context,
    private val cameraIndex: ComponentIndexType,
    private val droneName: String,
    private val options: WebRTCMediaOptions,
    private val configuredServer: () -> String,
    private val clearConfiguredServer: () -> Unit,
    private val onMetrics: (WebRTCStreamMetrics) -> Unit,
    private val onState: (String) -> Unit,
) {
    companion object {
        private const val TAG = "LyrebirdWebRTC"
        private const val FAILURE_THRESHOLD = 3
    }

    private val appContext = context.applicationContext
    private var consecutiveFailures = 0

    fun create(): WebRTCStreamer =
        WebRTCStreamer(
            context = appContext,
            cameraIndex = cameraIndex,
            droneName = droneName,
            options = options,
        ).apply {
            listener =
                object : WebRTCStreamer.WebRTCStreamerListener {
                    override fun onServerStarted(
                        ip: String,
                        port: Int,
                    ) {
                        consecutiveFailures = 0
                        Log.i(TAG, "WHIP publishing from $ip")
                        onState("running")
                    }

                    override fun onServerStopped() {
                        Log.i(TAG, "WebRTC streamer stopped")
                        onState("stopped")
                    }

                    override fun onServerError(error: String) {
                        Log.e(TAG, "WebRTC error: $error")
                        consecutiveFailures++
                        if (consecutiveFailures >= FAILURE_THRESHOLD && configuredServer().isNotEmpty()) {
                            consecutiveFailures = 0
                            Log.w(TAG, "WHIP failed $FAILURE_THRESHOLD times against configured server; clearing override")
                            clearConfiguredServer()
                        }
                        onState("error: $error")
                    }

                    override fun onMetrics(metrics: WebRTCStreamMetrics) {
                        onMetrics(metrics)
                    }
                }
        }
}
