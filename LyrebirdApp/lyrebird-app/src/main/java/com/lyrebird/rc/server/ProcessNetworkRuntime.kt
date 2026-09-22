package com.lyrebird.rc.server

import android.content.Context
import android.util.Log
import com.lyrebird.rc.HTTP_PORT
import com.lyrebird.rc.LyrebirdCommandHost
import com.lyrebird.rc.SimpleHttpServer
import com.lyrebird.rc.TELEMETRY_PORT
import com.lyrebird.rc.mavlink.MavlinkCommandSink
import com.lyrebird.rc.telemetry.TelemetryFeed
import java.lang.ref.WeakReference

internal interface NetworkRuntimeCallbacks {
    val runtimeDroneSerial: String

    fun telemetryJson(): String

    fun gapTelemetryJson(): String

    fun onTelemetryClient(clientIp: String)
}

internal object ProcessNetworkRuntimeRegistry {
    private val runtime = ProcessNetworkRuntime()

    /**
     * Command-log hook handed to the HTTP server this runtime builds. The flavor composition
     * installs it before attach/start (the V5 flight logger lives outside the shared source set);
     * null keeps command logging off.
     */
    @Volatile
    var commandLogger: ((uri: String, postData: String) -> Unit)? = null

    /**
     * Told when a ground station connects to the telemetry port, in the process.
     *
     * The screen's callback of the same name ([NetworkRuntimeCallbacks.onTelemetryClient]) is the
     * UI's copy and is absent whenever no screen is attached — which is exactly the state a ground
     * station is most likely connecting in. The flavor composition installs this so a connection
     * can start the video on its own, with nobody looking at the RC.
     */
    @Volatile
    var telemetryClientListener: ((clientIp: String) -> Unit)? = null

    /**
     * Brings the session up with the process, before a screen exists: the aircraft serves its
     * ground station from launch, and keeps serving while its screen is closed or restarting.
     */
    fun attachSession(
        context: Context,
        host: LyrebirdCommandHost,
        sink: MavlinkCommandSink,
    ) = runtime.attachSession(context, host, sink)

    /** Attaches the screen that shows session state; identity-guarded against stale detaches. */
    fun attachCallbacks(callbacks: NetworkRuntimeCallbacks) = runtime.attachCallbacks(callbacks)

    /** Drops only this screen's callbacks; the session and command path are process-owned. */
    fun detachCallbacks(callbacks: NetworkRuntimeCallbacks) = runtime.detachCallbacks(callbacks)

    /** Installs the process-owned telemetry feed served when no screen is attached. */
    fun attachTelemetryFeed(feed: TelemetryFeed) = runtime.attachTelemetryFeed(feed)

    fun start() = runtime.start()

    fun stop() = runtime.stop()

    fun status() = runtime.status()

    fun hasTelemetryClients() = runtime.hasTelemetryClients()

    /**
     * The most recent ground station on the telemetry socket, which a runtime that started later
     * still has to publish for - its own client callback can only reach a screen that attached.
     */
    fun lastTelemetryClientIp() = runtime.lastTelemetryClientIp()
}

private class ProcessNetworkRuntime {
    private companion object {
        const val TAG = "LyrebirdNetworkRuntime"
    }

    private val hostBridge = RuntimeCommandHost()
    private val sinkBridge = RuntimeMavlinkCommandSink()
    private var callbacksRef: WeakReference<NetworkRuntimeCallbacks> = WeakReference(null)
    private var telemetryFeed: TelemetryFeed? = null
    private var discovery: LyrebirdDiscoveryManager? = null
    private var session: LyrebirdSession? = null

    /**
     * Where the last telemetry client came from. Kept here rather than derived from
     * [LyrebirdSession.hasTelemetryClients] because the address itself is what the streamer needs:
     * it publishes to the ground station that connected.
     */
    @Volatile private var recentClientIp: String? = null

    @Synchronized
    fun attachSession(
        context: Context,
        host: LyrebirdCommandHost,
        sink: MavlinkCommandSink,
    ) {
        hostBridge.attach(host)
        sinkBridge.attach(sink)
        if (discovery == null) {
            // The discovery reply and the mDNS service name carry the DRONE NAME, not the
            // aircraft serial: every consumer keys the aircraft by the name the app itself
            // publishes under (the WHIP/RTMP path, telemetry `droneName`, the ROS namespace),
            // and the ground-station dashboard builds its WHEP URL from this reply. Answering
            // with the serial - which is what this did between the session refactor and now -
            // leaves the browser subscribing to a MediaMTX path nothing publishes to. The
            // serial is still advertised, as the mDNS TXT record below.
            discovery =
                LyrebirdDiscoveryManager(
                    context.applicationContext,
                    droneNameProvider = { hostBridge.droneName },
                    videoModeProvider = { hostBridge.streamingModeName },
                )
        }
    }

    @Synchronized
    fun attachCallbacks(callbacks: NetworkRuntimeCallbacks) {
        callbacksRef = WeakReference(callbacks)
    }

    @Synchronized
    fun detachCallbacks(callbacks: NetworkRuntimeCallbacks) {
        if (callbacksRef.get() === callbacks) callbacksRef = WeakReference(null)
    }

    @Synchronized
    fun attachTelemetryFeed(feed: TelemetryFeed) {
        telemetryFeed = feed
    }

    @Synchronized
    fun start(): LyrebirdSessionStatus {
        val existing = session
        // Reuse the session that is already answering, and let it retry whatever did not bind:
        // rebuilding it here would try to bind the same ports from a second server object while
        // the first one still holds them, and drop the session that was serving all along. Only
        // a session that bound nothing is rebuilt — it holds no port and gave the lease back.
        if (existing != null && existing.status.isServing) return existing.start()
        val telemetry =
            TelemetryServer(TELEMETRY_PORT, { callbacksRef.get()?.telemetryJson() ?: telemetryFeed?.telemetryJson() ?: "{}" }, {
                callbacksRef.get()?.gapTelemetryJson()
                    ?: telemetryFeed?.gapTelemetryJson()
                    ?: "{}"
            }).apply {
                onFirstClientConnected = { clientIp ->
                    recentClientIp = clientIp
                    callbacksRef.get()?.onTelemetryClient(clientIp)
                    ProcessNetworkRuntimeRegistry.telemetryClientListener?.invoke(clientIp)
                }
            }
        session =
            LyrebirdSession(
                lease = DeviceSessionLeaseRegistry.current(),
                http =
                    SimpleHttpServer(HTTP_PORT, hostBridge, sinkBridge).apply {
                        commandLogger = ProcessNetworkRuntimeRegistry.commandLogger
                        // Read through the object's `session` at request time: this server is
                        // constructed as an argument of that same session, so the lambda must
                        // not capture a value that does not exist yet.
                        telemetryServing = { session?.status?.telemetryPort != null }
                    },
                telemetry = telemetry,
                advertiser =
                    DiscoveryAdvertiser(discovery ?: error("runtime not attached")) {
                        callbacksRef.get()?.runtimeDroneSerial
                            ?: telemetryFeed?.droneSerial
                            ?: hostBridge.droneName
                    },
                httpPort = HTTP_PORT,
                telemetryPort = TELEMETRY_PORT,
            )
        return session!!.start()
    }

    fun status() = session?.status ?: LyrebirdSessionStatus()

    /**
     * Stops serving and gives the device lease back.
     *
     * The lease goes last, inside [LyrebirdSession.stop]: the advertisement comes down first, so
     * a ground station is never pointed at a session that has already left, and only then does
     * the device become available to the other APK. Safe to call when nothing is running, and
     * [start] can bring the session back afterwards.
     */
    @Synchronized
    fun stop() {
        val wasServing = session != null
        session?.stop()
        session = null
        recentClientIp = null
        if (wasServing) Log.i(TAG, "Network session stopped")
    }

    fun hasTelemetryClients() = session?.hasTelemetryClients() == true

    fun lastTelemetryClientIp() = recentClientIp
}
