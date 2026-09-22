package com.lyrebird.rc.telemetry

/**
 * The telemetry stream's process-owned provider.
 *
 * The TCP server no longer depends on a screen being able to answer: the SDK runtime registers
 * this at attach and [com.lyrebird.rc.server.ProcessNetworkRuntime] falls back to it when the
 * activity is gone. The JSON comes from the same coordinator the screens write their settings
 * into, so a client sees continuity across UI recreation instead of a frame of `{}`.
 */
internal interface TelemetryFeed {
    /** Aircraft identity for discovery when no screen is attached to provide one. */
    val droneSerial: String

    fun telemetryJson(): String

    fun gapTelemetryJson(): String
}
