package com.lyrebird.rc.telemetry

import android.content.Context
import android.os.Handler
import android.os.Looper
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.v5.et.create
import dji.v5.et.get
import dji.v5.et.listen
import dji.v5.manager.KeyManager

internal object ProcessTelemetryRuntimeRegistry {
    private val aircraftTelemetry = V5AircraftTelemetrySource()
    private var deviceStatusSource: DeviceStatusSource? = null
    private var connectionListenerStarted = false
    private var flightStateSubscription: AutoCloseable? = null

    @Synchronized
    fun attach(context: Context) {
        val appContext = context.applicationContext
        if (deviceStatusSource == null) {
            deviceStatusSource = DeviceStatusSource(appContext)
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

    fun deviceStatusSource(): DeviceStatusSource = deviceStatusSource ?: error("Process telemetry runtime is not attached")
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
