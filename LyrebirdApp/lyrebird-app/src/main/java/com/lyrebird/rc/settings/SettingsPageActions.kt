package com.lyrebird.rc.settings

import com.lyrebird.rc.StreamingMode

internal const val REQUEST_EDGE_MODEL_FILE = 3
internal const val REQUEST_EDGE_LABELS_FILE = 4

internal enum class AircraftStorage { SDCARD, INTERNAL }

internal data class DroneStorageStatus(
    val label: String,
    val summary: String,
) {
    val menuLabel: String get() = "$label ($summary)"
    val dialogText: String get() = "$label: $summary"
}

internal object SettingsDisplay {
    fun capacity(megabytes: Int): String =
        if (megabytes >= 1024) java.lang.String.format(java.util.Locale.US, "%.1f GB", megabytes / 1024.0) else "$megabytes MB"

    fun duration(seconds: Int): String {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
    }

    fun limit(value: Int): String = if (value >= 0) "$value m" else "Unavailable"

    fun fleet(peerCount: Int?): String =
        when (peerCount) {
            null -> "Off"
            0 -> "No peers"
            1 -> "1 aircraft"
            else -> "$peerCount aircraft"
        }
}

internal interface FlightSettingsActions {
    fun getRcControlMode(): String

    fun setRcControlMode(mode: String): Boolean

    fun getRcPairingStatus(): String

    fun requestRcPairing()

    fun stopRcPairing()

    fun getHdFrequencyBand(): String

    fun getRTHAltitude(): Int

    fun setRTHAltitude(altitude: Int)

    fun getMaxFlightHeight(): Int

    fun setMaxFlightHeight(height: Int)

    fun getMaxFlightDistance(): Int

    fun setMaxFlightDistance(distance: Int)

    fun getDistanceLimitEnabled(): Boolean

    fun setDistanceLimitEnabled(enabled: Boolean)
}

internal interface SettingsPageActions {
    val flight: FlightSettingsActions
    val aircraftConnected: Boolean
    val droneName: String
    val fleetPeerCount: Int?

    fun showFleetDialog(): Boolean

    fun settingsSnapshot(): SettingsSnapshot

    fun currentMavlinkSystemId(): Int

    fun prefIntOrDefault(
        key: String,
        fallback: Int,
    ): Int

    fun setAutomaticDroneName()

    fun setDroneName(name: String): Boolean

    fun setMavlinkSystemId(value: Int): Boolean

    fun isMavlinkFlightAllowed(): Boolean

    fun mavlinkFlightAllowedMenuLabel(): String

    fun setMavlinkFlightAllowed(allowed: Boolean)

    fun isDetectionActiveForUi(): Boolean

    fun setDetectionSource(source: DetectionSource)

    fun setDetectionsEnabled(enabled: Boolean)

    fun applyEdgeConfidenceSelection(threshold: Float)

    fun invalidateOptionsMenu()

    fun showEdgeFilePicker(
        requestCode: Int,
        title: String,
    )

    fun getDroneStorageStatus(
        location: AircraftStorage,
        label: String,
    ): DroneStorageStatus

    fun formatDroneStorage(
        location: AircraftStorage,
        label: String,
    )

    fun getRtmpUrl(clientIp: String): String

    fun setStreamingMode(mode: StreamingMode)

    fun shouldRestartActiveStreaming(): Boolean

    fun restartActiveStreaming()

    fun changeVideoOptions()

    fun toggleDjiSurfaceH264Encoder()

    fun obstacleGuardSummary(): String

    fun toggleObstacleGuard()
}
