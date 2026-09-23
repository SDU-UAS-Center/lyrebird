package com.lyrebird.rc.settings

import org.json.JSONObject

internal data class SettingsSnapshot(
    val droneName: String,
    val aircraftSerialNumber: String,
    val mavlinkSystemId: Int,
    val streamingMode: String,
    val webrtcResolution: String,
    val webrtcFps: Int,
    val detectionSource: String,
    val detectionsEnabled: Boolean,
    val edgeConfidenceThreshold: Float,
    val mediamtxServer: String,
    val rthAltitude: Int,
    val rthAltitudeEffective: Int,
    val rthAltitudeStatus: String,
    val maxFlightHeight: Int,
    val maxFlightDistance: Int,
    val distanceLimitEnabled: Boolean,
    val rcControlMode: String,
    val rcPairingStatus: String,
    val hdFrequencyBand: String,
    val detectedAircraft: String,
    val controlProfile: String,
) {
    fun toJson(): String {
        val fields =
            linkedMapOf<String, Any>(
                "droneName" to droneName,
                "aircraftSerialNumber" to aircraftSerialNumber,
                "mavlinkSystemId" to mavlinkSystemId,
                "videoSource" to "drone",
                "streamingMode" to streamingMode,
                "webrtcResolution" to webrtcResolution,
                "webrtcFps" to webrtcFps,
                "detectionSource" to detectionSource,
                "detectionsEnabled" to detectionsEnabled,
                "edgeConfidenceThreshold" to edgeConfidenceThreshold,
                "mediamtxServer" to mediamtxServer,
                "rthAltitude" to rthAltitude,
                "rthAltitudeEffective" to rthAltitudeEffective,
                "rthAltitudeStatus" to rthAltitudeStatus,
                "maxFlightHeight" to maxFlightHeight,
                "maxFlightDistance" to maxFlightDistance,
                "distanceLimitEnabled" to distanceLimitEnabled,
                "rcControlMode" to rcControlMode,
                "rcPairingStatus" to rcPairingStatus,
                "hdFrequencyBand" to hdFrequencyBand,
                "detectedAircraft" to detectedAircraft,
                "controlProfile" to controlProfile,
            )
        val values =
            fields.entries.joinToString(",") { (key, value) ->
                "${JSONObject.quote(key)}:${if (value is String) JSONObject.quote(value) else value}"
            }
        val groups =
            GROUPS.entries.joinToString(",") { (key, value) ->
                "${JSONObject.quote(key)}:${JSONObject.quote(value)}"
            }
        return "{$values,\"groups\":{$groups}}"
    }

    companion object {
        private val GROUPS =
            linkedMapOf(
                "droneName" to "identity",
                "aircraftSerialNumber" to "identity",
                "mavlinkSystemId" to "identity",
                "detectedAircraft" to "identity",
                "controlProfile" to "identity",
                "videoSource" to "video",
                "streamingMode" to "video",
                "webrtcResolution" to "video",
                "webrtcFps" to "video",
                "mediamtxServer" to "video",
                "rthAltitude" to "flight",
                "rthAltitudeEffective" to "flight",
                "rthAltitudeStatus" to "flight",
                "maxFlightHeight" to "flight",
                "maxFlightDistance" to "flight",
                "distanceLimitEnabled" to "flight",
                "detectionsEnabled" to "detection",
                "detectionSource" to "detection",
                "edgeConfidenceThreshold" to "detection",
                "rcControlMode" to "rc",
            )
    }
}
