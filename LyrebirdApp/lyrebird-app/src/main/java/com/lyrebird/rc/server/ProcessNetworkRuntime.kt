package com.lyrebird.rc.server

import android.content.Context
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

    fun status() = runtime.status()

    fun hasTelemetryClients() = runtime.hasTelemetryClients()
}

private class ProcessNetworkRuntime {
    private val hostBridge = RuntimeCommandHost()
    private val sinkBridge = RuntimeMavlinkCommandSink()
    private var callbacksRef: WeakReference<NetworkRuntimeCallbacks> = WeakReference(null)
    private var telemetryFeed: TelemetryFeed? = null
    private var discovery: LyrebirdDiscoveryManager? = null
    private var session: LyrebirdSession? = null

    @Synchronized
    fun attachSession(
        context: Context,
        host: LyrebirdCommandHost,
        sink: MavlinkCommandSink,
    ) {
        hostBridge.attach(host)
        sinkBridge.attach(sink)
        if (discovery == null) {
            discovery = LyrebirdDiscoveryManager(context.applicationContext) { serialOrName() }
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

    private fun serialOrName(): String =
        callbacksRef.get()?.runtimeDroneSerial
            ?: telemetryFeed?.droneSerial
            ?: hostBridge.droneName

    @Synchronized
    fun start(): LyrebirdSessionStatus {
        val existing = session
        if (existing != null && existing.status.isServing) return existing.status
        val telemetry =
            TelemetryServer(TELEMETRY_PORT, { callbacksRef.get()?.telemetryJson() ?: telemetryFeed?.telemetryJson() ?: "{}" }, {
                callbacksRef.get()?.gapTelemetryJson()
                    ?: telemetryFeed?.gapTelemetryJson()
                    ?: "{}"
            }).apply {
                onFirstClientConnected = { clientIp -> callbacksRef.get()?.onTelemetryClient(clientIp) }
            }
        session =
            LyrebirdSession(
                lease = DeviceSessionLeaseRegistry.current(),
                http =
                    SimpleHttpServer(HTTP_PORT, hostBridge, sinkBridge).apply {
                        commandLogger = ProcessNetworkRuntimeRegistry.commandLogger
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

    fun hasTelemetryClients() = session?.hasTelemetryClients() == true
}
