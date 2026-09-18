package com.lyrebird.rc.webrtc

import android.util.Log
import com.lyrebird.rc.StreamingMode
import com.lyrebird.rc.models.LiveStreamVM
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError

internal interface V5NativeStreamingHost {
    fun rtmpUrl(clientIp: String): String

    fun setRtmpUrl(url: String)

    fun rtspPort(): Int

    fun setRtspPort(port: Int)

    fun resolveRtspPort(): Int

    fun rtspUsername(): String

    fun rtspPassword(): String

    fun agoraChannel(): String

    fun agoraToken(): String

    fun agoraUid(): String

    fun gbServerIp(): String

    fun gbServerPort(): Int

    fun gbServerId(): String

    fun gbAgentId(): String

    fun gbChannel(): String

    fun gbLocalPort(): Int

    fun gbPassword(): String

    fun onStatus(status: String)

    fun onMessage(message: String)

    fun onConfigChanged()
}

internal class V5NativeStreamingCoordinator(
    private val liveStreamVM: LiveStreamVM,
    private val host: V5NativeStreamingHost,
) {
    companion object {
        private const val TAG = "LyrebirdNativeStreaming"
    }

    fun start(
        mode: StreamingMode,
        clientIp: String,
    ) {
        val startSelected = { startMode(mode, clientIp) }
        if (!liveStreamVM.isStreaming()) {
            startSelected()
            return
        }
        Log.i(TAG, "Stopping currently active native DJI livestream before restart")
        liveStreamVM.stopStream(
            completion("stopped", "Failed to stop native DJI livestream", after = startSelected),
        )
    }

    fun stop() {
        if (!liveStreamVM.isStreaming()) {
            host.onStatus("stopped")
            return
        }
        host.onStatus("stopping")
        liveStreamVM.stopStream(completion("stopped", "Failed to stop native DJI livestream", null))
    }

    private fun startMode(
        mode: StreamingMode,
        clientIp: String,
    ) {
        host.onStatus("starting")
        when (mode) {
            StreamingMode.RTMP -> startRtmp(host.rtmpUrl(clientIp))
            StreamingMode.RTSP -> startRtsp()
            StreamingMode.AGORA -> startAgora()
            StreamingMode.GB28181 -> startGb28181()
            StreamingMode.WEBRTC -> Unit
        }
    }

    private fun startRtmp(initialUrl: String) {
        fun fallback(url: String): String? {
            val match = Regex("^rtmp://([^/]+)/([^/]+)$").matchEntire(url.trim()) ?: return null
            return "rtmp://${match.groupValues[1]}/live/${match.groupValues[2]}"
        }

        fun attempt(
            url: String,
            fallbackAttempt: Boolean,
        ) {
            Log.i(TAG, "Starting native DJI RTMP streaming to: $url")
            liveStreamVM.setRTMPConfig(url)
            liveStreamVM.startStream(
                object : CommonCallbacks.CompletionCallback {
                    override fun onSuccess() {
                        if (url != initialUrl) host.setRtmpUrl(url)
                        host.onStatus("running")
                        host.onMessage("RTMP stream started")
                    }

                    override fun onFailure(error: IDJIError) {
                        val message = error.description()
                        if (!fallbackAttempt) {
                            val fallbackUrl = fallback(url)
                            if (fallbackUrl != null && fallbackUrl != url) {
                                host.onStatus("retrying with $fallbackUrl")
                                attempt(fallbackUrl, true)
                                return
                            }
                        }
                        Log.e(TAG, "Failed to start native DJI RTMP stream: $message")
                        host.onStatus("error: $message")
                        host.onMessage("RTMP failed: $message")
                    }
                },
            )
        }
        attempt(initialUrl, false)
    }

    private fun startRtsp() {
        val requestedPort = host.rtspPort()
        val port = host.resolveRtspPort()
        if (port != requestedPort) {
            host.setRtspPort(port)
            host.onConfigChanged()
            host.onMessage("RTSP port $requestedPort busy, switched to $port")
        }
        liveStreamVM.setRTSPConfig(host.rtspUsername(), host.rtspPassword(), port)
        liveStreamVM.startStream(completion("running", "Failed to start native DJI RTSP", "RTSP server started on port $port"))
    }

    private fun startAgora() {
        liveStreamVM.setAgoraConfig(host.agoraChannel(), host.agoraToken(), host.agoraUid())
        liveStreamVM.startStream(completion("running", "Failed to start Agora", "Agora stream started"))
    }

    private fun startGb28181() {
        liveStreamVM.setGB28181(
            host.gbServerIp(),
            host.gbServerPort(),
            host.gbServerId(),
            host.gbAgentId(),
            host.gbChannel(),
            host.gbLocalPort(),
            host.gbPassword(),
        )
        liveStreamVM.startStream(completion("running", "Failed to start GB28181", "GB28181 stream started"))
    }

    private fun completion(
        successStatus: String,
        failurePrefix: String,
        successMessage: String? = null,
        after: (() -> Unit)? = null,
    ) = object : CommonCallbacks.CompletionCallback {
        override fun onSuccess() {
            host.onStatus(successStatus)
            successMessage?.let(host::onMessage)
            after?.invoke()
        }

        override fun onFailure(error: IDJIError) {
            val message = error.description()
            Log.w(TAG, "$failurePrefix: $message")
            host.onStatus("error: $message")
            host.onMessage("$failurePrefix: $message")
            after?.invoke()
        }
    }
}
