package com.lyrebird.rc.controller

import com.lyrebird.rc.settings.FlightSettingsActions

internal object V5FlightSettingsActions : FlightSettingsActions {
    override fun getRcControlMode() = DroneController.getRcControlMode()

    override fun setRcControlMode(mode: String) = DroneController.setRcControlMode(mode)

    override fun getRcPairingStatus() = DroneController.getRcPairingStatus()

    override fun requestRcPairing() = DroneController.requestRcPairing()

    override fun stopRcPairing() = DroneController.stopRcPairing()

    override fun getHdFrequencyBand() = DroneController.getHdFrequencyBand()

    override fun getRTHAltitude() = DroneController.getRTHAltitude()

    override fun setRTHAltitude(altitude: Int) = DroneController.setRTHAltitude(altitude)

    override fun getMaxFlightHeight() = DroneController.getMaxFlightHeight()

    override fun setMaxFlightHeight(height: Int) = DroneController.setMaxFlightHeight(height)

    override fun getMaxFlightDistance() = DroneController.getMaxFlightDistance()

    override fun setMaxFlightDistance(distance: Int) = DroneController.setMaxFlightDistance(distance)

    override fun getDistanceLimitEnabled() = DroneController.getDistanceLimitEnabled()

    override fun setDistanceLimitEnabled(enabled: Boolean) = DroneController.setDistanceLimitEnabled(enabled)
}
