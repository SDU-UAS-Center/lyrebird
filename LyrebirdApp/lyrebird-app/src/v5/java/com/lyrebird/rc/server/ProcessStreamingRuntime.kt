package com.lyrebird.rc.server

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.lyrebird.rc.StreamingMode
import com.lyrebird.rc.models.LiveStreamVM
import com.lyrebird.rc.settings.LyrebirdSettings
import com.lyrebird.rc.webrtc.StreamingTargetPolicy
import com.lyrebird.rc.webrtc.V5NativeStreamingCoordinator
import com.lyrebird.rc.webrtc.V5SettingsNativeStreamingHost
import com.lyrebird.rc.webrtc.V5WebRtcStreamerFactory
import com.lyrebird.rc.webrtc.WebRTCMediaOptions
import com.lyrebird.rc.webrtc.WebRTCStreamMetrics
import com.lyrebird.rc.webrtc.WebRTCStreamer
import dji.sdk.keyvalue.value.common.ComponentIndexType
import java.lang.ref.WeakReference

internal interface StreamingRuntimeCallbacks {
    val runtimeStreamingDroneName: String

    fun runtimeStreamingMode(): StreamingMode

    fun runtimeWebRtcOptions(): WebRTCMediaOptions

    fun runtimeBuildWhipUrl(clientIp: String): String

    fun runtimeConfiguredMediaMtxServer(): String

    fun runtimeClearConfiguredMediaMtxServer()

    fun runtimeOnStreamingMetrics(metrics: WebRTCStreamMetrics)

    fun runtimeOnStreamingState(state: String)

    /** A message worth surfacing to the operator (the native streamer's own status toasts). */
    fun runtimeOnStreamingMessage(message: String)

    /** The native streamer changed its configuration; the UI should rebuild what it shows. */
    fun runtimeOnStreamingConfigChanged()

    fun runtimeRebuildTelemetryCache()

    fun runtimeDefaultStreamingClientIp(): String
}

internal object ProcessStreamingRuntimeRegistry {
    private val runtime = ProcessStreamingRuntime()

    fun attach(
        context: Context,
        callbacks: StreamingRuntimeCallbacks,
    ) = runtime.attach(context, callbacks)

    fun prepare() = runtime.prepare()

    fun detach(callbacks: StreamingRuntimeCallbacks) = runtime.detach(callbacks)

    fun startForClient(clientIp: String) = runtime.startForClient(clientIp)

    fun restartActiveStreaming() = runtime.restartActiveStreaming()

    fun changeMediaOptions(options: WebRTCMediaOptions) = runtime.changeMediaOptions(options)

    fun streamer(): WebRTCStreamer? = runtime.streamer()

    fun currentWhipUrl(): String? = runtime.currentWhipUrl()

    fun currentClientIp(): String? = runtime.currentClientIp()

    fun hasTarget(): Boolean = runtime.hasTarget()

    fun isNativeStreaming(): Boolean = runtime.isNativeStreaming()
}

private class ProcessStreamingRuntime {
    companion object {
        private const val TAG = "LyrebirdStreamingRuntime"
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var callbacksRef: WeakReference<StreamingRuntimeCallbacks> = WeakReference(null)
    private var context: Context? = null
    private var streamerInstance: WebRTCStreamer? = null
    private var nativeStreamingHost: V5SettingsNativeStreamingHost? = null
    private var nativeStreamingVM: LiveStreamVM? = null
    private var nativeStreamingCoordinator: V5NativeStreamingCoordinator? = null

    @Volatile private var lastClientIp: String? = null

    @Volatile private var lastWhipUrl: String? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var wifiManager: WifiManager? = null

    @Synchronized
    fun attach(
        context: Context,
        callbacks: StreamingRuntimeCallbacks,
    ) {
        callbacksRef = WeakReference(callbacks)
        val appContext = context.applicationContext
        this.context = appContext
        // Owned here, not by the screen that attached: the native streamer must keep its URL,
        // ports and credentials after the activity is gone, and the UI effects resolve weakly.
        nativeStreamingHost =
            V5SettingsNativeStreamingHost(
                preferences = appContext.getSharedPreferences(LyrebirdSettings.PREFS_FILE, Context.MODE_PRIVATE),
                onStatus = { status -> callbacksRef.get()?.runtimeOnStreamingState(status) },
                onMessage = { message -> callbacksRef.get()?.runtimeOnStreamingMessage(message) },
                onConfigChanged = { callbacksRef.get()?.runtimeOnStreamingConfigChanged() },
            )
    }

    @Synchronized
    fun prepare() {
        if (streamerInstance != null) return
        val callbacks = callbacksRef.get() ?: return
        val appContext = context ?: return
        wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        streamerInstance =
            V5WebRtcStreamerFactory(
                context = appContext,
                cameraIndex = ComponentIndexType.LEFT_OR_MAIN,
                droneName = callbacks.runtimeStreamingDroneName,
                options = callbacks.runtimeWebRtcOptions(),
                configuredServer = {
                    callbacksRef.get()?.runtimeConfiguredMediaMtxServer().orEmpty()
                },
                clearConfiguredServer = {
                    callbacksRef.get()?.runtimeClearConfiguredMediaMtxServer()
                },
                onMetrics = { metrics ->
                    callbacksRef.get()?.runtimeOnStreamingMetrics(metrics)
                },
                onState = { state ->
                    callbacksRef.get()?.runtimeOnStreamingState(state)
                },
            ).create()
        if (nativeStreamingCoordinator == null) {
            val host = nativeStreamingHost ?: return
            nativeStreamingVM = LiveStreamVM()
            nativeStreamingCoordinator =
                V5NativeStreamingCoordinator(
                    liveStreamVM = nativeStreamingVM ?: return,
                    host = host,
                )
        }
        Log.i(TAG, "Process-scoped WebRTC streamer ready")
    }

    fun detach(callbacks: StreamingRuntimeCallbacks) {
        if (callbacksRef.get() === callbacks) callbacksRef.clear()
    }

    fun startForClient(clientIp: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { startForClient(clientIp) }
            return
        }
        val callbacks = callbacksRef.get() ?: return
        val streamer = streamerInstance
        val publisherHealthy = streamer?.isRunning() == true && streamer.isPublishing()
        val decision =
            StreamingTargetPolicy.decide(
                previousClientIp = lastClientIp,
                previousWhipUrl = lastWhipUrl,
                publisherHealthy = publisherHealthy,
                clientIp = clientIp,
            )
        if (!decision.shouldStart) {
            if (lastClientIp != clientIp) {
                Log.w(TAG, "Ignoring telemetry client $clientIp while healthy publisher targets $lastClientIp")
            }
            return
        }
        if (lastClientIp == clientIp && lastWhipUrl != null) {
            Log.w(TAG, "Restarting stale WHIP publisher for $clientIp")
        }
        lastClientIp = decision.targetIp
        callbacks.runtimeRebuildTelemetryCache()
        acquireLowLatencyWifiLock()

        val mode = callbacks.runtimeStreamingMode()
        Log.i(TAG, "Starting active streaming in mode: ${mode.menuLabel}")
        streamer?.stop()
        if (mode != StreamingMode.WEBRTC) {
            nativeStreamingCoordinator?.start(mode, decision.targetIp)
            return
        }

        nativeStreamingCoordinator?.stop()
        val whipUrl = callbacks.runtimeBuildWhipUrl(decision.targetIp)
        lastWhipUrl = whipUrl
        if (streamer == null) {
            Log.w(TAG, "Cannot start WHIP - WebRTCStreamer not initialized yet")
            callbacks.runtimeOnStreamingState("error: streamer not initialized")
            return
        }
        runCatching {
            streamer.startWhip(whipUrl, whipUrlProvider = { callbacksRef.get()?.runtimeBuildWhipUrl(decision.targetIp) ?: whipUrl })
            Log.i(TAG, "WHIP publishing started: $whipUrl")
            callbacks.runtimeOnStreamingState("running")
        }.onFailure { error ->
            Log.e(TAG, "Failed to start WHIP publishing: ${error.message}", error)
            callbacks.runtimeOnStreamingState("error: ${error.message ?: "start failed"}")
        }
    }

    fun restartActiveStreaming() {
        val callbacks = callbacksRef.get() ?: return
        val target =
            lastClientIp
                ?: lastWhipUrl?.let {
                    runCatching {
                        android.net.Uri
                            .parse(it)
                            .host
                    }.getOrNull()
                }
                ?: callbacks.runtimeDefaultStreamingClientIp()
        startForClient(target)
    }

    fun changeMediaOptions(options: WebRTCMediaOptions) {
        streamerInstance?.changeMediaOptions(options)
    }

    fun streamer(): WebRTCStreamer? = streamerInstance

    fun currentWhipUrl(): String? = lastWhipUrl

    fun currentClientIp(): String? = lastClientIp

    fun hasTarget(): Boolean = lastClientIp != null || lastWhipUrl != null

    fun isNativeStreaming(): Boolean = nativeStreamingVM?.isStreaming() == true

    private fun acquireLowLatencyWifiLock() {
        if (wifiLock?.isHeld == true) return
        runCatching {
            wifiLock =
                wifiManager?.createWifiLock(
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY,
                    "LyrebirdStreamingWifiLock",
                )
            wifiLock?.acquire()
            Log.i(TAG, "Low-latency Wi-Fi lock acquired for streaming")
        }.onFailure { error ->
            Log.w(TAG, "Could not acquire low-latency Wi-Fi lock: ${error.message}")
        }
    }
}
