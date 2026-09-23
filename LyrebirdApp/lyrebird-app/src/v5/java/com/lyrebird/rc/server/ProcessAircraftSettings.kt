package com.lyrebird.rc.server

import android.util.Log
import com.lyrebird.rc.LyrebirdAircraftSettingsPort
import com.lyrebird.rc.controller.AircraftSettingsPolicy
import com.lyrebird.rc.controller.DroneController
import com.lyrebird.rc.mavlink.CommandResult
import com.lyrebird.rc.mavlink.MavlinkCommandOutcome

/**
 * The aircraft's own settings, answered by the process.
 *
 * The HTTP route table used to reach these through the screen's flight port, which meant that
 * with no screen attached a perfectly valid `rcControlMode=usa` came back as "Invalid control
 * mode (jp|usa|ch|custom)" — the route was reporting the absence of a screen as a malformed
 * request, and the ros-monitor log that started this work says exactly that. The keys and the
 * limit writes never needed a screen; only the composition did.
 *
 * Every write reports the aircraft's real answer:
 * * the firmware limits go through the MAVLink parameter table, so HTTP and MAVLink share one
 *   implementation and one refusal path (standby, range, pending-write confirmation);
 * * the RC control mode is validated here, refused while the aircraft is asleep, and otherwise
 *   written through [DroneController];
 * * pairing start/stop are *requests*: there is no setting to confirm, only a state to read
 *   afterwards with [rcPairingStatus], so issuing the request is the honest answer. Note that
 *   pairing is the one operation that is meant to be usable while the aircraft is off.
 *
 * DJI types are touched inside method bodies only: an object whose static initialiser resolved
 * them would be unloadable in the host JVM tests that exercise the command surface's detach
 * paths.
 */
internal object ProcessAircraftSettings : LyrebirdAircraftSettingsPort {
    private const val TAG = "LyrebirdAircraftSettings"

    // ── Firmware limits: the same table the MAVLink parameter side writes through ──

    override fun setRthAltitude(altitudeM: Int): CommandResult =
        ProcessMavlinkParameters.apply(ProcessMavlinkParameters.PARAM_RTH_ALTITUDE, altitudeM.toFloat())

    override fun setMaxFlightHeight(heightM: Int): CommandResult =
        ProcessMavlinkParameters.apply(ProcessMavlinkParameters.PARAM_MAX_HEIGHT, heightM.toFloat())

    override fun setMaxFlightDistance(distanceM: Int): CommandResult =
        ProcessMavlinkParameters.apply(ProcessMavlinkParameters.PARAM_MAX_DISTANCE, distanceM.toFloat())

    override fun setDistanceLimitEnabled(enabled: Boolean): CommandResult =
        ProcessMavlinkParameters.apply(
            ProcessMavlinkParameters.PARAM_DISTANCE_LIMIT,
            if (enabled) 1f else 0f,
        )

    // ── RC control mode ───────────────────────────────────────────────────────

    override fun setRcControlMode(mode: String): CommandResult {
        val normalized =
            AircraftSettingsPolicy.normalizeControlMode(mode)
                ?: return AircraftSettingsPolicy.rejectedControlMode()
        if (!DroneController.isFlightControllerConnected()) {
            Log.i(TAG, "RC control mode not changed: flight controller not connected (aircraft in standby)")
            return AircraftSettingsPolicy.controlModeRefused(normalized, inStandby = true)
        }
        return if (DroneController.setRcControlMode(normalized)) {
            Log.i(TAG, "RC control mode set to $normalized")
            CommandResult(MavlinkCommandOutcome.ACCEPTED)
        } else {
            Log.w(TAG, "RC control mode $normalized was refused by the aircraft")
            AircraftSettingsPolicy.controlModeRefused(normalized, inStandby = false)
        }
    }

    override fun rcControlMode(): String = DroneController.getRcControlMode()

    // ── RC pairing ────────────────────────────────────────────────────────────

    override fun requestRcPairing(): CommandResult = requestPairing(starting = true)

    override fun stopRcPairing(): CommandResult = requestPairing(starting = false)

    /**
     * Pairing is requested, not set: the RC acts on it and the result is read back through
     * [rcPairingStatus]. Deliberately not gated on the aircraft answering — pairing an aircraft
     * that is off is the normal case — so the answer says the request was issued and nothing more.
     */
    private fun requestPairing(starting: Boolean): CommandResult {
        if (starting) {
            DroneController.requestRcPairing()
            Log.i(TAG, "RC pairing requested")
        } else {
            DroneController.stopRcPairing()
            Log.i(TAG, "RC pairing stopped")
        }
        return CommandResult(MavlinkCommandOutcome.ACCEPTED)
    }

    override fun rcPairingStatus(): String = DroneController.getRcPairingStatus()

    override fun hdFrequencyBand(): String = DroneController.getHdFrequencyBand()
}
