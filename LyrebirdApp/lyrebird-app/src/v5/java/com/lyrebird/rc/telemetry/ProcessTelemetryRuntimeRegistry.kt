package com.lyrebird.rc.telemetry

import android.content.Context
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.v5.et.create
import dji.v5.et.get
import dji.v5.et.listen
import dji.v5.manager.KeyManager

internal object ProcessTelemetryRuntimeRegistry {
    private val aircraftTelemetry = V5AircraftTelemetrySource()
    private var deviceStatusSource: DeviceStatusSource? = null
    private var connectionListenerStarted = false

    @Synchronized
    fun attach(context: Context) {
        if (deviceStatusSource == null) {
            deviceStatusSource = DeviceStatusSource(context.applicationContext)
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

    fun detachUiObservers() {
        aircraftTelemetry.detachObservers()
    }
}
