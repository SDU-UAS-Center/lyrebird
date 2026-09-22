package com.lyrebird.rc.server

import android.content.Context
import android.util.Log
import com.lyrebird.rc.fleet.FleetBeacon
import com.lyrebird.rc.fleet.FleetRuntimeCallbacks
import com.lyrebird.rc.mavlink.MavlinkEndpointConfig
import com.lyrebird.rc.mavlink.MavlinkSystemId
import com.lyrebird.rc.settings.DroneSettingsProfiles
import com.lyrebird.rc.settings.LyrebirdSettings
import com.lyrebird.rc.telemetry.ProcessTelemetryRuntimeRegistry
import com.lyrebird.rc.telemetry.toFleetBeacon
import com.lyrebird.rc.util.AppContextHolder

/**
 * The fleet mesh's view of this device, owned by the process instead of a screen.
 *
 * The mesh used to ask the open Flight Deck for its identity and its readings, which is the same
 * shape of mistake the other runtimes had already made once: a screen is not always there, and the
 * two ways it is not both showed on the roster. When Flight Deck was closed the device simply went
 * silent and every peer marked it lost; when the screen's serial field reset (activity recreation,
 * the video-setting restart) the device id flapped between aircraft serial and installation id for
 * a beacon or two, and peers kept the previous id as a second, stale row that collided with the new
 * one on MAVLink id and name.
 *
 * Everything read here lives in the process registries and preferences, so the mesh keeps talking
 * — and keeps saying the same thing about who it is — while no screen exists.
 */
internal object ProcessFleetRuntime : FleetRuntimeCallbacks {
    private const val TAG = "LyrebirdFleetRuntime"

    private val preferences
        get() = AppContextHolder.context?.getSharedPreferences(LyrebirdSettings.PREFS_FILE, Context.MODE_PRIVATE)

    /** The aircraft serial where one is known; a generated install id until it is. */
    override val fleetDeviceIdForRuntime: String
        get() {
            val serial = ProcessTelemetryRuntimeRegistry.droneSerial.trim()
            if (DroneSettingsProfiles.isUsableSerial(serial)) return serial
            val prefs = preferences ?: return INSTALL_ID_UNRESOLVED
            prefs.getString(LyrebirdSettings.PREF_FLEET_INSTALL_ID, null)?.takeIf { it.isNotBlank() }?.let {
                return it
            }
            val generated =
                "rc-" +
                    java.util.UUID
                        .randomUUID()
                        .toString()
                        .take(LyrebirdSettings.FLEET_INSTALL_ID_LENGTH)
            prefs.edit().putString(LyrebirdSettings.PREF_FLEET_INSTALL_ID, generated).apply()
            Log.i(TAG, "Generated a fleet install id for a device with no aircraft bound: $generated")
            return generated
        }

    override val fleetDroneNameForRuntime: String
        get() =
            preferences
                ?.getString(LyrebirdSettings.PREF_DRONE_NAME, "")
                ?.trim()
                .takeUnless { it.isNullOrEmpty() }
                ?: LyrebirdSettings.DEFAULT_DRONE_NAME

    override fun buildFleetBeaconForRuntime(): FleetBeacon? {
        val prefs = preferences ?: return null
        return ProcessTelemetryRuntimeRegistry.aircraftTelemetry().readState().readings.toFleetBeacon(
            deviceId = fleetDeviceIdForRuntime,
            droneName = fleetDroneNameForRuntime,
            systemId =
                MavlinkSystemId.resolve(
                    prefs.getInt(MavlinkEndpointConfig.PREF_SYSTEM_ID, MavlinkEndpointConfig.DEFAULT_SYSTEM_ID),
                    ProcessTelemetryRuntimeRegistry.droneSerial.trim().ifEmpty { "UNKNOWN" },
                ),
            homeSet = ProcessTelemetryRuntimeRegistry.telemetryCoordinator().homeSet,
            videoPath = fleetDroneNameForRuntime.trim().ifEmpty { LyrebirdSettings.DEFAULT_DRONE_NAME },
            videoServer = LyrebirdSettings(prefs).getMediamtxServer(),
        )
    }

    /** Marker for "we could not even read preferences": better than a crash on the beacon thread. */
    private const val INSTALL_ID_UNRESOLVED = "rc-unresolved"
}
