package com.lyrebird.rc.controller

import android.os.Handler
import android.os.Looper
import com.lyrebird.rc.telemetry.ProcessTelemetryRuntimeRegistry

/**
 * The process's ROI tracker, wired to the V5 gimbal and telemetry.
 *
 * The scheduling, geometry and session rules live in [RoiTracker]; this exists so there is one
 * ROI instance and so the V5 pieces have somewhere to be composed — the gimbal port and the
 * telemetry provider, which is re-read per tick because the source is replaced on reconnect.
 */
internal object ProcessRoiRuntimeRegistry {
    private val tracker =
        RoiTracker(
            gimbal = V5RoiGimbalPort(),
            telemetry = { ProcessTelemetryRuntimeRegistry.aircraftTelemetry() },
            ticker = HandlerRoiTicker(Handler(Looper.getMainLooper())),
        )

    fun start(
        latitudeDeg: Double,
        longitudeDeg: Double,
        altitudeM: Double,
    ) = tracker.start(latitudeDeg, longitudeDeg, altitudeM)

    fun stop() = tracker.stop()
}
