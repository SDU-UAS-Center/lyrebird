package com.lyrebird.rc.server

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.lyrebird.rc.StreamingMode
import com.lyrebird.rc.models.LiveStreamVM
import com.lyrebird.rc.settings.LyrebirdSettings
import com.lyrebird.rc.webrtc.PublisherState
import com.lyrebird.rc.webrtc.StreamingTargetPolicy
import com.lyrebird.rc.webrtc.V5NativeStreamingCoordinator
import com.lyrebird.rc.webrtc.V5SettingsNativeStreamingHost
import com.lyrebird.rc.webrtc.V5WebRtcStreamerFactory
import com.lyrebird.rc.webrtc.WebRTCMediaOptions
import com.lyrebird.rc.webrtc.WebRTCStreamMetrics
import com.lyrebird.rc.webrtc.WebRTCStreamer
import dji.sdk.keyvalue.value.common.ComponentIndexType
import java.lang.ref.WeakReference

/**
 * What a screen does about the video: show it.
 *
 * The configuration this runtime needs — the drone name, the WebRTC options, the MediaMTX
 * address, the WHIP URL — is not here, because it is not a screen's to give: it comes from
 * preferences and the aircraft (see [V5StreamingSettings]). That split is what lets the publisher
 * exist, and restart, with no screen attached at all.
 */
internal interface StreamingRuntimeUi {
    fun streamingUiOnMetrics(metrics: WebRTCStreamMetrics)

    fun streamingUiOnState(state: String)

    /** A message worth surfacing to the operator (the native streamer's own status toasts). */
    fun streamingUiOnMessage(message: String)

    /** The native streamer changed its configuration; the UI should rebuild what it shows. */
    fun streamingUiOnConfigChanged()

    fun streamingUiRebuildTelemetryCache()
}

internal object ProcessStreamingRuntimeRegistry {
    private val runtime = ProcessStreamingRuntime()

    /** Process-scoped: no screen and no callbacks, so the publisher can be composed at boot. */
    fun attach(context: Context) = runtime.attach(context)

    /** Attaches the screen that draws streaming state; identity-guarded like the other UIs. */
    fun attachUi(ui: StreamingRuntimeUi) = runtime.attachUi(ui)

    fun detachUi(ui: StreamingRuntimeUi) = runtime.detachUi(ui)

    fun prepare() = runtime.prepare()

    fun startForClient(clientIp: String) = runtime.startForClient(clientIp)

    fun restartActiveStreaming() = runtime.restartActiveStreaming()

    fun changeMediaOptions(options: WebRTCMediaOptions) = runtime.changeMediaOptions(options)

    fun streamer(): WebRTCStreamer? = runtime.streamer()

    fun currentWhipUrl(): String? = runtime.currentWhipUrl()

    fun currentClientIp(): String? = runtime.currentClientIp()

    fun hasTarget(): Boolean = runtime.hasTarget()

    fun isNativeStreaming(): Boolean = runtime.isNativeStreaming()

    fun stop() = runtime.stop()
}

private class ProcessStreamingRuntime {
    companion object {
        private const val TAG = "LyrebirdStreamingRuntime"
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var uiRef: WeakReference<StreamingRuntimeUi> = WeakReference(null)
    private var context: Context? = null
    private var settings: V5StreamingSettings? = null
    private var streamerInstance: WebRTCStreamer? = null
    private var nativeStreamingHost: V5SettingsNativeStreamingHost? = null
    private var nativeStreamingVM: LiveStreamVM? = null
    private var nativeStreamingCoordinator: V5NativeStreamingCoordinator? = null

    @Volatile private var lastClientIp: String? = null

    /**
     * A ground station that asked for a stream before this runtime could serve one. The network
     * session comes up with the process while the streamer still waits for a screen, so a client
     * that connects first would otherwise be answered with silence until it happened to
     * reconnect; whoever asked is remembered here and published to once the streamer exists.
     */
    @Volatile private var pendingClientIp: String? = null

    @Volatile private var lastWhipUrl: String? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var wifiManager: WifiManager? = null

    /**
     * Compose this runtime's configuration. No screen is involved and none is required.
     *
     * The native streamer host is built here too: it reads its URL, ports and credentials from
     * preferences (see [V5SettingsNativeStreamingHost]) and reports through the weak UI reference,
     * which is what lets it keep streaming after the activity that once attached it is gone.
     */
    @Synchronized
    fun attach(context: Context) {
        val appContext = context.applicationContext
        this.context = appContext
        settings =
            V5StreamingSettings(
                appContext.getSharedPreferences(LyrebirdSettings.PREFS_FILE, Context.MODE_PRIVATE),
            )
        // Owned here, not by the screen that attached: the native streamer must keep its URL,
        // ports and credentials after the activity is gone, and the UI effects resolve weakly.
        nativeStreamingHost =
            V5SettingsNativeStreamingHost(
                preferences = appContext.getSharedPreferences(LyrebirdSettings.PREFS_FILE, Context.MODE_PRIVATE),
                onStatus = { status -> uiRef.get()?.streamingUiOnState(status) },
                onMessage = { message -> uiRef.get()?.streamingUiOnMessage(message) },
                onConfigChanged = { uiRef.get()?.streamingUiOnConfigChanged() },
            )
        // Someone may already be waiting: the network session accepts a telemetry client long
        // before the SDK registers, and such a request was only remembered. Nothing else asks
        // again, so take it up here.
        if (pendingClientIp != null || ProcessNetworkRuntimeRegistry.hasTelemetryClients()) {
            prepare()
        }
    }

    @Synchronized
    fun attachUi(ui: StreamingRuntimeUi) {
        uiRef = WeakReference(ui)
    }

    @Synchronized
    fun detachUi(ui: StreamingRuntimeUi) {
        if (uiRef.get() === ui) uiRef = WeakReference(null)
    }

    @Synchronized
    fun prepare() {
        if (streamerInstance != null) return
        val settings = settings ?: return
        val appContext = context ?: return
        wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        streamerInstance =
            V5WebRtcStreamerFactory(
                context = appContext,
                cameraIndex = ComponentIndexType.LEFT_OR_MAIN,
                droneName = settings.droneName,
                options = settings.options,
                configuredServer = { settings.configuredServer() },
                clearConfiguredServer = { settings.clearConfiguredServer() },
                onMetrics = { metrics ->
                    uiRef.get()?.streamingUiOnMetrics(metrics)
                },
                onState = { state ->
                    uiRef.get()?.streamingUiOnState(state)
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
        // A ground station may have asked for a stream before the streamer existed (the network
        // session starts before the SDK is registered, and either can precede a screen). Take that
        // request up now - the address startForClient remembered, or the one the network runtime is
        // serving at this moment.
        val pending = pendingClientIp ?: ProcessNetworkRuntimeRegistry.lastTelemetryClientIp()
        pendingClientIp = null
        if (pending != null && lastClientIp == null && ProcessNetworkRuntimeRegistry.hasTelemetryClients()) {
            Log.i(TAG, "Publishing for the ground station that connected before the streamer was ready: $pending")
            startForClient(pending)
        }
    }

    fun startForClient(clientIp: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { startForClient(clientIp) }
            return
        }
        val settings =
            settings ?: run {
                // The SDK is not registered yet, so nothing can be published at all. Remember the
                // request; attach() composes the runtime and picks it up.
                pendingClientIp = clientIp
                return
            }
        if (streamerInstance == null) {
            // Build the publisher on demand: nothing needs a warm streamer until a ground station
            // asks for one, and building it here is what makes "a client connects" enough.
            prepare()
            if (streamerInstance == null) {
                pendingClientIp = clientIp
                return
            }
        }
        val streamer = streamerInstance
        val publisherState =
            when {
                streamer == null || !streamer.isRunning() -> PublisherState.NONE
                streamer.isPublishing() -> PublisherState.PUBLISHING
                else -> PublisherState.STARTING
            }
        val decision =
            StreamingTargetPolicy.decide(
                previousClientIp = lastClientIp,
                previousWhipUrl = lastWhipUrl,
                publisherState = publisherState,
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
        uiRef.get()?.streamingUiRebuildTelemetryCache()
        acquireLowLatencyWifiLock()

        val mode = settings.mode
        Log.i(TAG, "Starting active streaming in mode: ${mode.menuLabel}")
        streamer?.stop()
        if (mode != StreamingMode.WEBRTC) {
            nativeStreamingCoordinator?.start(mode, decision.targetIp)
            return
        }

        nativeStreamingCoordinator?.stop()
        val whipUrl = settings.whipUrl(decision.targetIp)
        lastWhipUrl = whipUrl
        if (streamer == null) {
            Log.w(TAG, "Cannot start WHIP - WebRTCStreamer not initialized yet")
            uiRef.get()?.streamingUiOnState("error: streamer not initialized")
            return
        }
        runCatching {
            streamer.startWhip(whipUrl, whipUrlProvider = { settings.whipUrl(decision.targetIp) })
            Log.i(TAG, "WHIP publishing started: $whipUrl")
            uiRef.get()?.streamingUiOnState("running")
        }.onFailure { error ->
            Log.e(TAG, "Failed to start WHIP publishing: ${error.message}", error)
            uiRef.get()?.streamingUiOnState("error: ${error.message ?: "start failed"}")
        }
    }

    fun restartActiveStreaming() {
        val settings = settings ?: return
        val target =
            StreamingTargetPolicy.restartTargetOrNull(
                previousClientIp = lastClientIp,
                previousWhipHost =
                    lastWhipUrl?.let { url ->
                        runCatching {
                            android.net.Uri
                                .parse(url)
                                .host
                        }.getOrNull()
                    },
                configuredServer = settings.configuredServer(),
                deviceIp = settings.defaultClientIp(),
            )
        if (target == null) {
            Log.i(TAG, "Not starting a stream: no ground station has connected and no MediaMTX server is configured")
            return
        }
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

    /**
     * Stops every stream this runtime owns and lets go of the Wi-Fi lock.
     *
     * Called from the explicit shutdown path before the network session releases the device
     * lease: a publisher or a native DJI stream still running against a session this app has
     * given back would keep the camera and the radio busy for the APK that now owns the device.
     * Restartable — the next [prepare] rebuilds the streamer.
     */
    @Synchronized
    fun stop() {
        nativeStreamingCoordinator?.stop()
        streamerInstance?.stop()
        streamerInstance = null
        wifiLock?.let { lock -> runCatching { if (lock.isHeld) lock.release() } }
        wifiLock = null
        lastClientIp = null
        lastWhipUrl = null
        pendingClientIp = null
        Log.i(TAG, "Process-scoped streaming stopped")
    }

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
