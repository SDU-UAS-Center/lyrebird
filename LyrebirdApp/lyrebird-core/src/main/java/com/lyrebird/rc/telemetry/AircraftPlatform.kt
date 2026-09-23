package com.lyrebird.rc.telemetry

/**
 * One SDK generation's aircraft boundary, as the application sees it.
 *
 * The facade groups the ports the shared application consumes. It starts with telemetry because
 * that is the first vertical slice the bridge moves; flight, camera, gimbal and media ports are
 * added as their consumer batches arrive, not ahead of them — an unused interface is not an
 * implemented one. One implementation exists per SDK flavor (`V5AircraftPlatform`, and later a
 * V4 adapter); shared code never branches on which one it holds.
 */
interface AircraftPlatform {
    val telemetry: AircraftTelemetrySource
}
