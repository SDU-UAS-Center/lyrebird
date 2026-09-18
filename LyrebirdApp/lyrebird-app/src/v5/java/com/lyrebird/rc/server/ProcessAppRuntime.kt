package com.lyrebird.rc.server

import android.content.Context
import android.util.Log
import com.lyrebird.rc.telemetry.ProcessTelemetryRuntimeRegistry

/**
 * Brings up the parts of the process runtime that must not wait for a screen.
 *
 * Called from the Application once the SDK lease is held: the network session (HTTP commands,
 * telemetry, discovery, the device lease) and the telemetry feed belong to the process. An RC
 * whose screen is closed, or whose activity is being restarted by the system, keeps answering
 * its ground station — which is the whole point of running the server on the aircraft. Commands
 * that genuinely need a screen degrade honestly until one attaches (see ProcessCommandSurface).
 *
 * Idempotent: Application.onCreate runs once per process, but a second call must not rebind.
 */
internal object ProcessAppRuntime {
    private const val TAG = "LyrebirdAppRuntime"

    @Volatile private var networkStarted = false

    fun startNetworkSession(context: Context) {
        if (networkStarted) return
        networkStarted = true
        val appContext = context.applicationContext
        ProcessNetworkRuntimeRegistry.attachSession(appContext, ProcessCommandSurface, ProcessPayloadCommands.sink)
        ProcessNetworkRuntimeRegistry.attachTelemetryFeed(ProcessTelemetryRuntimeRegistry)
        val status = ProcessNetworkRuntimeRegistry.start()
        Log.i(TAG, "Process network session: ${status.summary()}")
    }
}
