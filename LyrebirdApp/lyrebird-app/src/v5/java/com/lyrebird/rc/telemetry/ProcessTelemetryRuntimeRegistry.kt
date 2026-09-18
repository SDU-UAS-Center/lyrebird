package com.lyrebird.rc.telemetry

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.lyrebird.rc.controller.ControlAuthority
import com.lyrebird.rc.controller.SafetyLatchStore
import com.lyrebird.rc.server.ProcessNetworkRuntimeRegistry
import com.lyrebird.rc.settings.LyrebirdSettings
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.KeyTools
import dji.v5.et.create
import dji.v5.et.get
import dji.v5.et.listen
import dji.v5.manager.KeyManager

internal object ProcessTelemetryRuntimeRegistry : TelemetryFeed {
    private val aircraftTelemetry = V5AircraftTelemetrySource()
    private val telemetryCoordinator = TelemetryCoordinator()
    private val projection = V5AircraftTelemetryProjection(aircraftTelemetry, telemetryCoordinator)
    private var deviceStatusSource: DeviceStatusSource? = null
    private var connectionListenerStarted = false
    private var serialListenerStarted = false
    private var flightStateSubscription: AutoCloseable? = null
    private var projectionSubscription: AutoCloseable? = null

    @Volatile private var aircraftSerial: String = "UNKNOWN"

    @Synchronized
    fun attach(context: Context) {
        val appContext = context.applicationContext
        if (deviceStatusSource == null) {
            deviceStatusSource = DeviceStatusSource(appContext)
        }
        if (projectionSubscription == null) {
            // The runtime's own projection into the telemetry frame. Subscribed for the life of
            // the process: the TCP stream keeps moving with no screen attached, instead of
            // freezing on the last frame a screen built.
            projectionSubscription = aircraftTelemetry.subscribe(projection)
            ProcessNetworkRuntimeRegistry.attachTelemetryFeed(this)
        }
        if (!serialListenerStarted) {
            serialListenerStarted = true
            attachSerialLatch(appContext)
        }
        if (flightStateSubscription == null) {
            // The runtime's own flight-state consumer, subscribed for the life of the process:
            // takeoff/landing effects and the controller's airborne latch stay in step with no
            // screen attached. Screens subscribe separately and close only their own handles.
            flightStateSubscription =
                aircraftTelemetry.subscribe(
                    FlightStateConsumer(
                        effects = V5FlightStateEffects(appContext),
                        scheduler = MainHandlerFlightStateScheduler(),
                    ),
                )
        }
        if (!connectionListenerStarted) {
            val connectionKey = FlightControllerKey.KeyConnection.create()
            KeyManager.getInstance().listen(connectionKey, this) { _, connected ->
                aircraftTelemetry.setConnectionState(connected == true)
            }
            aircraftTelemetry.setConnectionState(connectionKey.get(false))
            connectionListenerStarted = true
        }
    }

    fun aircraftTelemetry(): V5AircraftTelemetrySource = aircraftTelemetry

    fun telemetryCoordinator(): TelemetryCoordinator = telemetryCoordinator

    fun projection(): V5AircraftTelemetryProjection = projection

    fun deviceStatusSource(): DeviceStatusSource = deviceStatusSource ?: error("Process telemetry runtime is not attached")

    /**
     * The latch is per airframe, and the serial only exists after the SDK answers: attach the
     * persistence first with a provider that reads whatever serial is current, then restore the
     * latch for it. Running here — not in the screen — is what keeps Safety authority in force
     * across a UI teardown, and the read is deliberately repeated per restore (see
     * [ControlAuthority.restoreLatch]).
     */
    private fun attachSerialLatch(appContext: Context) {
        ControlAuthority.attachPersistence(
            SafetyLatchStore(appContext.getSharedPreferences(LyrebirdSettings.PREFS_FILE, Context.MODE_PRIVATE)),
            aircraftSerial = { aircraftSerial },
        )
        val serialKey = KeyTools.createKey(FlightControllerKey.KeySerialNumber)
        KeyManager.getInstance().listen(serialKey, this) { _, value -> setAircraftSerial(value) }
        setAircraftSerial(serialKey.get(""))
    }

    private fun setAircraftSerial(value: String?) {
        aircraftSerial = value?.trim()?.takeIf { it.isNotEmpty() } ?: "UNKNOWN"
        runCatching { ControlAuthority.restoreLatch() }
    }

    override val droneSerial: String get() = aircraftSerial

    override fun telemetryJson(): String = telemetryCoordinator.getTelemetryJson()

    override fun gapTelemetryJson(): String = telemetryCoordinator.getGapTelemetryJson()
}

/** Schedules the landing grace on the main looper, where the old screen-side timer lived. */
private class MainHandlerFlightStateScheduler : FlightStateScheduler {
    private val handler = Handler(Looper.getMainLooper())

    override fun postDelayed(
        delayMs: Long,
        action: () -> Unit,
    ): ScheduledAction {
        val runnable = Runnable { action() }
        handler.postDelayed(runnable, delayMs)
        return ScheduledAction { handler.removeCallbacks(runnable) }
    }
}
