package com.lyrebird.rc.telemetry

import android.content.Context

internal object ProcessTelemetryRuntimeRegistry {
    private val aircraftTelemetry = V5AircraftTelemetrySource()
    private var deviceStatusSource: DeviceStatusSource? = null

    @Synchronized
    fun attach(context: Context) {
        if (deviceStatusSource == null) {
            deviceStatusSource = DeviceStatusSource(context.applicationContext)
        }
    }

    fun aircraftTelemetry(): V5AircraftTelemetrySource = aircraftTelemetry

    fun deviceStatusSource(): DeviceStatusSource = deviceStatusSource ?: error("Process telemetry runtime is not attached")

    fun detachUiObservers() {
        aircraftTelemetry.detachObservers()
    }
}
