package com.lyrebird.rc.controller

import com.lyrebird.rc.mavlink.MavlinkCommandOutcome
import com.lyrebird.rc.server.ProcessAircraftSettings
import com.lyrebird.rc.settings.FlightSettingsActions

internal object V5FlightSettingsActions : FlightSettingsActions {
    // The RC values come from the process-scoped aircraft-settings surface rather than from here,
    // so the settings page, HTTP and MAVLink all read and write the RC through one owner with one
    // set of refusal rules (see ProcessAircraftSettings).
    override fun getRcControlMode() = ProcessAircraftSettings.rcControlMode()

    override fun setRcControlMode(mode: String) = ProcessAircraftSettings.setRcControlMode(mode).outcome == MavlinkCommandOutcome.ACCEPTED

    override fun getRcPairingStatus() = ProcessAircraftSettings.rcPairingStatus()

    override fun requestRcPairing() {
        ProcessAircraftSettings.requestRcPairing()
    }

    override fun stopRcPairing() {
        ProcessAircraftSettings.stopRcPairing()
    }

    override fun getHdFrequencyBand() = ProcessAircraftSettings.hdFrequencyBand()

    override fun getRTHAltitude() = DroneController.getRTHAltitude()

    override fun setRTHAltitude(altitude: Int) = DroneController.setRTHAltitude(altitude)

    override fun getMaxFlightHeight() = DroneController.getMaxFlightHeight()

    override fun setMaxFlightHeight(height: Int) = DroneController.setMaxFlightHeight(height)

    override fun getMaxFlightDistance() = DroneController.getMaxFlightDistance()

    override fun setMaxFlightDistance(distance: Int) = DroneController.setMaxFlightDistance(distance)

    override fun getDistanceLimitEnabled() = DroneController.getDistanceLimitEnabled()

    override fun setDistanceLimitEnabled(enabled: Boolean) = DroneController.setDistanceLimitEnabled(enabled)
}
