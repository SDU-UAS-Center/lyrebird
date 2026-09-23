package com.lyrebird.rc.server

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.lyrebird.rc.edge.ProcessDetectionRuntimeRegistry
import com.lyrebird.rc.fleet.FleetMeshSessionRegistry
import com.lyrebird.rc.settings.LyrebirdSettings
import com.lyrebird.rc.telemetry.ProcessTelemetryRuntimeRegistry

/**
 * Brings up the parts of the process runtime that must not wait for a screen.
 *
 * Two things start this, and the split between them is the point: the network session (HTTP
 * commands, telemetry, discovery, the device lease) comes up with the process, because it is
 * sockets and preferences; everything that talks to the SDK comes up when the SDK says it is
 * registered ([onSdkRegistered]), because a DJI key cannot be created before then. Neither needs
 * a screen. An RC whose screen is closed, or whose activity is being restarted by the system,
 * keeps answering its ground station and keeps serving video — which is the whole point of
 * running the server on the aircraft. What genuinely needs a screen degrades honestly until one
 * attaches (see ProcessCommandSurface, and the *Ui interfaces next to it).
 *
 * Idempotent: Application.onCreate runs once per process, but a second call must not rebind.
 */
internal object ProcessAppRuntime {
    private const val TAG = "LyrebirdAppRuntime"

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    @Volatile private var networkStarted = false

    @Volatile private var sdkRuntimeStarted = false

    fun startNetworkSession(context: Context) {
        if (networkStarted) return
        networkStarted = true
        val appContext = context.applicationContext
        // The detection runtime reads its selection from settings, so attaching it here (rather
        // than only when a screen opens) is what lets a ground station start or stop detections on
        // an RC whose screen is closed.
        ProcessDetectionRuntimeRegistry.attachProcess(appContext)
        // The fleet mesh is a process service, not a screen service: it announces this device to
        // the rest of the aircraft and listens for them, which is exactly what has to keep
        // working when the screen is closed or mid-recreation (see ProcessFleetRuntime).
        FleetMeshSessionRegistry.attach(
            appContext,
            appContext.getSharedPreferences(LyrebirdSettings.PREFS_FILE, Context.MODE_PRIVATE),
            ProcessFleetRuntime,
        )
        ProcessNetworkRuntimeRegistry.attachSession(appContext, ProcessCommandSurface, ProcessPayloadCommands.sink)
        ProcessNetworkRuntimeRegistry.attachTelemetryFeed(ProcessTelemetryRuntimeRegistry)
        // A ground station that connects to the telemetry port is asking for the aircraft, and
        // the answer includes its video: start publishing for whoever connected, in the process,
        // whether or not anyone is looking at the RC's screen (see ProcessStreamingRuntime).
        ProcessNetworkRuntimeRegistry.telemetryClientListener = { clientIp ->
            ProcessStreamingRuntimeRegistry.startForClient(clientIp)
        }
        // The obstacle guard is armed with the process, not with a screen: the aircraft it is
        // there to stop can be flown by a ground station while the RC's screen is closed, and a
        // brake that only exists while an activity is visible is not a brake. Its own preference
        // decides whether it arms at all (see ProcessObstacleGuard).
        ProcessObstacleGuard.attach(appContext)
        val status = ProcessNetworkRuntimeRegistry.start()
        Log.i(TAG, "Process network session: ${status.summary()}")
        startMavlinkEndpointIfServing()
    }

    /**
     * Attach the runtimes that read the aircraft, once the SDK is registered.
     *
     * Called from the SDK's own registration callback. That timing is not a detail: a DJI key
     * cannot be created before registration, which is why this could not simply move next to
     * [startNetworkSession] — the earlier it runs, the more likely it fails, and a listener that
     * failed to register never comes back on its own.
     *
     * What it buys is the screenless case: telemetry (and with it the frame the TCP stream serves,
     * the aircraft serial, the per-aircraft safety latch and the flight-state effects), the video
     * publisher's configuration, and the connection hooks that re-read flight limits, warm the
     * thermal pipeline and re-arm the obstacle guard. All of it used to wait for an activity.
     *
     * Posted to the main looper because the SDK calls back on its own thread and these attach
     * listeners the rest of the app assumes are wired from one thread.
     */
    fun onSdkRegistered(context: Context) {
        if (sdkRuntimeStarted) return
        sdkRuntimeStarted = true
        val appContext = context.applicationContext
        mainHandler.post {
            ProcessTelemetryRuntimeRegistry.attach(appContext)
            ProcessStreamingRuntimeRegistry.attach(appContext)
            ProcessSettingsBackup.attach(appContext)
            Log.i(TAG, "SDK runtime attached (telemetry, streaming, settings backup) with no screen involved")
        }
    }

    /**
     * Attach and start the MAVLink endpoint, when this app owns a serving session.
     *
     * Process-scoped like the rest: a phone that boots into the background still binds its MAVLink
     * port, so a ground station can reach the aircraft without anyone opening the app first. The
     * callbacks are process-owned ([ProcessMavlinkCallbacks]) and the sinks and media source
     * already were, which is what made this possible at all.
     *
     * Gated on the session so a second Lyrebird APK that lost the device lease cannot bind the
     * port anyway — that would be the double-serving the lease exists to prevent. Idempotent, so a
     * screen that later gets the session serving may call it again.
     *
     * @return true when the endpoint was attached and asked to start.
     */
    fun startMavlinkEndpointIfServing(): Boolean {
        val status = ProcessNetworkRuntimeRegistry.status()
        if (!status.leaseHeld || !status.isServing) {
            Log.w(TAG, "MAVLink endpoint not started: this app does not own a serving session")
            return false
        }
        ProcessMavlinkRuntimeRegistry.attach(
            ProcessMavlinkCallbacks,
            ProcessPayloadCommands.mediaSource,
            ProcessPayloadCommands.sink,
            ProcessFlightCommands.motionSink,
            ProcessFlightCommands.missionSink,
        )
        ProcessMavlinkRuntimeRegistry.start()
        return true
    }

    /**
     * Recreate the endpoint so a changed vehicle id or endpoint configuration takes effect.
     *
     * Stopping first is what makes the restart a restart: the endpoint reads its configuration
     * once, on start.
     */
    fun restartMavlinkEndpointIfServing() {
        ProcessMavlinkRuntimeRegistry.stop()
        startMavlinkEndpointIfServing()
    }

    /**
     * Stops serving, in the order that keeps the device hand-over honest: everything that serves
     * through the session goes down first, the session — and with it the device lease — last.
     *
     * This is what the foreground notification's stop action runs. Giving the lease back while
     * the publisher, the MAVLink endpoint, the detection runtimes or the fleet mesh were still
     * running would hand the aircraft to the other APK with the camera, the radio and the ports
     * still busy; the order itself is enforced, and tested, by [RuntimeTeardown].
     */
    fun stopNetworkSession() {
        if (!networkStarted) return
        networkStarted = false
        val teardown =
            RuntimeTeardown(
                steps =
                    listOf(
                        RuntimeTeardown.Step("streaming") { ProcessStreamingRuntimeRegistry.stop() },
                        RuntimeTeardown.Step("mavlink endpoint") { ProcessMavlinkRuntimeRegistry.stop() },
                        RuntimeTeardown.Step("detections") { ProcessDetectionRuntimeRegistry.stopSelected() },
                        RuntimeTeardown.Step("obstacle guard") { ProcessObstacleRuntimeRegistry.stop() },
                        RuntimeTeardown.Step("fleet mesh") { FleetMeshSessionRegistry.stop() },
                    ),
                releaseLease = { ProcessNetworkRuntimeRegistry.stop() },
            )
        val ran = teardown.run()
        Log.i(TAG, "Process network session stopped (${ran.joinToString(" -> ")})")
    }
}
