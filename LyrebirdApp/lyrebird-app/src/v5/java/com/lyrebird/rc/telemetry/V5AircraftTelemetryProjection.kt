package com.lyrebird.rc.telemetry

import com.lyrebird.rc.controller.DroneController
import com.lyrebird.rc.edge.ProcessDetectionRuntimeRegistry

/**
 * Applies the aircraft half of the telemetry frame — readings, mission reach latches, the home
 * latch and the sensor/override flags — to the process-owned coordinator.
 *
 * This used to run inside the FlightDeck screen's rebuild, so the TCP stream stopped seeing
 * updates whenever no screen was alive, and the home latch was forgotten on every recreation.
 * The projection subscribes at attach and keeps applying regardless of UI; the screen keeps
 * writing its settings/streaming fields into the same coordinator.
 */
internal class V5AircraftTelemetryProjection(
    private val telemetry: V5AircraftTelemetrySource,
    private val coordinator: TelemetryCoordinator,
) : AircraftTelemetryListener {
    /** The laser target most recently reported, or null while the laser has no fix. */
    @Volatile var lrfTarget: GeoPoint3D? = null

    /** The home latch is per session: once the aircraft has stood within half a metre of its
     *  reported home point, that point is this flight's home. */
    private var homePointSetLatch = false

    override fun onReadingsChanged() = apply()

    fun apply() {
        val readings = telemetry.readState().readings
        readings.applyTo(coordinator)
        val location = readings.location
        val homeLocation = readings.home
        // Zero until home is a real place. DJI reports (0, 0) before it has a home point, and
        // that is a real spot in the Atlantic: measuring to it produced a confident 2,559 km
        // from a stationary aircraft, which is worse than reporting nothing because it looks
        // like an answer.
        coordinator.distanceToHome =
            if (hasRealHomeCoordinates(homeLocation.latitudeDeg, homeLocation.longitudeDeg)) {
                DroneController.calculateDistance(
                    location.latitudeDeg,
                    location.longitudeDeg,
                    homeLocation.latitudeDeg,
                    homeLocation.longitudeDeg,
                )
            } else {
                0.0
            }
        coordinator.waypointReached = DroneController.isWaypointReached()
        coordinator.intermediaryWaypointReached = DroneController.isIntermediaryWaypointReached()
        coordinator.yawReached = DroneController.isYawReached()
        coordinator.altitudeReached = DroneController.isAltitudeReached()
        coordinator.homeSet = isHomeSet(readings)
        coordinator.waypointSeq = DroneController.getWaypointSeq()
        coordinator.yawSeq = DroneController.getYawSeq()
        coordinator.altitudeSeq = DroneController.getAltitudeSeq()
        // A laser fix is a place on the globe, not a place relative to take-off, so it maps to the
        // three-field point rather than to the aircraft's own position type.
        coordinator.lrfTarget = lrfTarget
        coordinator.isManualOverrideActive = DroneController.isManualOverrideActive
        coordinator.isAutoSensingActive = ProcessDetectionRuntimeRegistry.isAutoSensingActive()
        // The frame is cached; whoever changed a field must refresh it.
        coordinator.rebuildTelemetryCache()
    }

    private fun isHomeSet(readings: AircraftReadings): Boolean {
        if (homePointSetLatch) return true
        if (readings.flying) return false
        val home = readings.home
        if (!hasRealHomeCoordinates(home.latitudeDeg, home.longitudeDeg)) return false
        val current = readings.location
        val distance =
            DroneController.calculateDistance(
                current.latitudeDeg,
                current.longitudeDeg,
                home.latitudeDeg,
                home.longitudeDeg,
            )
        if (distance < 0.5) {
            homePointSetLatch = true
        }
        return homePointSetLatch
    }

    private fun hasRealHomeCoordinates(
        latitude: Double,
        longitude: Double,
    ): Boolean =
        (latitude != 0.0 || longitude != 0.0) &&
            latitude in -90.0..90.0 &&
            longitude in -180.0..180.0
}
