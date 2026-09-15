package com.lyrebird.rc.settings

import android.content.SharedPreferences
import com.lyrebird.rc.StreamingMode
import com.lyrebird.rc.mavlink.MavlinkEndpointConfig
import com.lyrebird.rc.mavlink.MavlinkSystemId
import com.lyrebird.rc.webrtc.WebRTCMediaOptions
import com.lyrebird.rc.webrtc.WebRTCPeerFactory

internal enum class StreamResolutionPreset(
    val prefValue: String,
    val menuLabel: String,
    val width: Int,
    val height: Int,
    val bitrate: Int,
) {
    AUTO("auto", "Auto / native", 0, 0, 6_000_000),
    FULL_HD("1080p", "1080p", 1920, 1080, 8_000_000),
    HD("720p", "720p", 1280, 720, 2_000_000),
    SD("480p", "480p", 640, 480, 1_500_000),
    ;

    companion object {
        fun fromPref(value: String?): StreamResolutionPreset = entries.firstOrNull { it.prefValue == value } ?: AUTO
    }
}

internal enum class DetectionSource(
    val prefValue: String,
    val menuLabel: String,
) {
    NONE("none", "None"),
    DJI_ONBOARD("dji_onboard", "DJI onboard"),
    YOLO_ON_PHONE("yolo_on_phone", "YOLO on phone"),
    ;

    companion object {
        fun fromPref(value: String?): DetectionSource = entries.firstOrNull { it.prefValue == value } ?: NONE
    }
}

internal class LyrebirdSettings(
    private val sharedPreferences: SharedPreferences,
) {
    companion object {
        internal const val PREF_DRONE_NAME = "drone_name"

        internal const val PREF_DRONE_NAME_USER_SET = "drone_name_user_set"

        internal const val PREF_MAVLINK_FLIGHT_DEFAULT_MIGRATED = "lb_mav_0_allow_flight_default_v2"

        internal const val PREF_MEDIAMTX_SERVER = "mediamtx_server"

        internal const val PREF_WEBRTC_FPS = "webrtc_fps"

        internal const val PREF_WEBRTC_RESOLUTION = "webrtc_resolution"

        internal const val PREF_MAP_EXPANDED = "map_expanded"

        internal const val PREF_DETECTIONS_ENABLED = "detections_enabled"

        internal const val PREF_DETECTION_SOURCE = "detection_source"

        internal const val PREF_EDGE_DETECTION_ENABLED = "edge_detection_enabled"

        internal const val PREF_EDGE_MODEL_URI = "edge_model_uri"

        internal const val PREF_EDGE_MODEL_NAME = "edge_model_name"

        internal const val PREF_EDGE_LABELS_URI = "edge_labels_uri"

        internal const val PREF_EDGE_LABELS_NAME = "edge_labels_name"

        internal const val PREF_EDGE_CONFIDENCE_THRESHOLD = "edge_confidence_threshold"

        internal const val PREF_STREAMING_MODE = "streaming_mode"

/**
         * Fallback fleet identity for a device with no aircraft bound.
         *
         * The aircraft serial is the right identity for a flying device and the one the settings
         * profiles already key on, but it reads as "UNKNOWN" before an aircraft connects. Without
         * a per-install fallback every unbound RC on the network would claim the same identity and
         * collapse into one roster entry.
         */
        internal const val PREF_FLEET_INSTALL_ID = "lb_fleet_install_id"

/** Enough UUID to make an accidental collision across a field team implausible. */
        internal const val FLEET_INSTALL_ID_LENGTH = 8

/** Preferences that travel with the aircraft, swapped by [DroneSettingsProfiles] on serial change. */
        internal val PER_DRONE_PROFILE_KEYS =
            setOf(
                PREF_DRONE_NAME,
                PREF_DRONE_NAME_USER_SET,
                PREF_MEDIAMTX_SERVER,
                PREF_WEBRTC_FPS,
                PREF_WEBRTC_RESOLUTION,
                PREF_DETECTIONS_ENABLED,
                PREF_DETECTION_SOURCE,
                PREF_STREAMING_MODE,
                // lb_mav_0_sysid: 0 (the default) derives the id from the serial, so it needs no
                // stored value; an operator-pinned manual id is per-drone and travels with it.
                MavlinkEndpointConfig.PREF_SYSTEM_ID,
            )

        internal const val PREF_RTMP_URL = "rtmp_url"

        internal const val PREF_RTSP_PORT = "rtsp_port"

        internal const val PREF_RTSP_USER = "rtsp_user"

        internal const val PREF_RTSP_PWD = "rtsp_pwd"

        internal const val PREF_AGORA_CHANNEL = "agora_channel"

        internal const val PREF_AGORA_TOKEN = "agora_token"

        internal const val PREF_AGORA_UID = "agora_uid"

        internal const val PREF_GB_SERVER_IP = "gb_server_ip"

        internal const val PREF_GB_SERVER_PORT = "gb_server_port"

        internal const val PREF_GB_SERVER_ID = "gb_server_id"

        internal const val PREF_GB_AGENT_ID = "gb_agent_id"

        internal const val PREF_GB_CHANNEL = "gb_channel"

        internal const val PREF_GB_LOCAL_PORT = "gb_local_port"

        internal const val PREF_GB_PASSWORD = "gb_password"

        internal const val DEFAULT_WEBRTC_FPS = 10

        internal const val DEFAULT_EDGE_CONFIDENCE_THRESHOLD = 0.25f

        internal val EDGE_CONFIDENCE_OPTIONS =
            floatArrayOf(
                0.10f,
                0.15f,
                0.20f,
                0.25f,
                0.30f,
                0.40f,
                0.50f,
                0.60f,
                0.70f,
            )

        internal const val DEFAULT_DRONE_NAME = "lb_unknown"

        internal val WEBRTC_FPS_OPTIONS = intArrayOf(5, 10, 15, 20, 25, 30)
    }

    fun setDroneName(value: String): Boolean {
        val trimmed = value.trim()
        if (trimmed.isEmpty() || trimmed.length > 32) return false
        sharedPreferences
            .edit()
            .putString(PREF_DRONE_NAME, trimmed)
            .putBoolean(PREF_DRONE_NAME_USER_SET, true)
            .apply()
        return true
    }

    fun setMavlinkSystemId(value: Int): Boolean {
        if (value != MavlinkSystemId.AUTO && !MavlinkSystemId.isManual(value)) return false
        sharedPreferences.edit().putInt(MavlinkEndpointConfig.PREF_SYSTEM_ID, value).apply()
        return true
    }

    fun setWebRtcFps(value: Int): Boolean {
        if (!WEBRTC_FPS_OPTIONS.contains(value)) return false
        sharedPreferences.edit().putInt(PREF_WEBRTC_FPS, value).apply()
        return true
    }

    fun setWebRtcResolution(value: String): Boolean {
        val preset = StreamResolutionPreset.entries.firstOrNull { it.prefValue.equals(value, ignoreCase = true) } ?: return false
        sharedPreferences.edit().putString(PREF_WEBRTC_RESOLUTION, preset.prefValue).apply()
        return true
    }

    fun setEdgeConfidence(value: Float): Boolean {
        if (EDGE_CONFIDENCE_OPTIONS.none { kotlin.math.abs(it - value) < 0.001f }) return false
        sharedPreferences.edit().putFloat(PREF_EDGE_CONFIDENCE_THRESHOLD, value).apply()
        return true
    }

    fun setMediamtxServer(value: String): Boolean {
        val trimmed = value.trim()
        if (trimmed.length > 200) return false
        sharedPreferences.edit().putString(PREF_MEDIAMTX_SERVER, trimmed).apply()
        return true
    }

    internal fun buildWebRTCOptions(): WebRTCMediaOptions {
        val preset = getWebRTCResolutionPreset()
        return if (preset == StreamResolutionPreset.AUTO) {
            WebRTCMediaOptions.native().copy(fps = getWebRTCFps())
        } else {
            WebRTCMediaOptions(
                videoResolutionWidth = preset.width,
                videoResolutionHeight = preset.height,
                fps = getWebRTCFps(),
                videoBitrate = preset.bitrate,
                videoCodec = "H264",
            )
        }
    }

    internal fun getWebRTCFps(): Int {
        val storedFps = sharedPreferences.getInt(PREF_WEBRTC_FPS, DEFAULT_WEBRTC_FPS)
        return if (WEBRTC_FPS_OPTIONS.contains(storedFps)) storedFps else DEFAULT_WEBRTC_FPS
    }

    internal fun getWebRTCResolutionPreset(): StreamResolutionPreset =
        StreamResolutionPreset.fromPref(
            sharedPreferences.getString(PREF_WEBRTC_RESOLUTION, StreamResolutionPreset.AUTO.prefValue),
        )

    internal fun isDjiSurfaceH264EncoderEnabled(): Boolean =
        sharedPreferences.getBoolean(
            WebRTCPeerFactory.PREF_USE_DJI_SURFACE_H264_ENCODER,
            false,
        )

    internal fun getStreamingMode(): StreamingMode =
        StreamingMode.fromPref(sharedPreferences.getString(PREF_STREAMING_MODE, StreamingMode.WEBRTC.prefValue))

    internal fun setRtmpUrl(url: String) {
        sharedPreferences.edit().putString(PREF_RTMP_URL, url.trim()).apply()
    }

    internal fun getRtspPort(): Int = sharedPreferences.getInt(PREF_RTSP_PORT, 8554)

    internal fun setRtspPort(port: Int) = sharedPreferences.edit().putInt(PREF_RTSP_PORT, port).apply()

    internal fun getRtspUsername(): String = sharedPreferences.getString(PREF_RTSP_USER, "admin") ?: "admin"

    internal fun setRtspUsername(user: String) = sharedPreferences.edit().putString(PREF_RTSP_USER, user.trim()).apply()

    internal fun getRtspPassword(): String = sharedPreferences.getString(PREF_RTSP_PWD, "lyrebird") ?: "lyrebird"

    internal fun setRtspPassword(pwd: String) = sharedPreferences.edit().putString(PREF_RTSP_PWD, pwd).apply()

    internal fun getAgoraChannel(): String = sharedPreferences.getString(PREF_AGORA_CHANNEL, "") ?: ""

    internal fun setAgoraChannel(ch: String) = sharedPreferences.edit().putString(PREF_AGORA_CHANNEL, ch.trim()).apply()

    internal fun getAgoraToken(): String = sharedPreferences.getString(PREF_AGORA_TOKEN, "") ?: ""

    internal fun setAgoraToken(tok: String) = sharedPreferences.edit().putString(PREF_AGORA_TOKEN, tok.trim()).apply()

    internal fun getAgoraUid(): String = sharedPreferences.getString(PREF_AGORA_UID, "") ?: ""

    internal fun setAgoraUid(uid: String) = sharedPreferences.edit().putString(PREF_AGORA_UID, uid.trim()).apply()

    internal fun getGbServerIp(): String = sharedPreferences.getString(PREF_GB_SERVER_IP, "") ?: ""

    internal fun setGbServerIp(ip: String) = sharedPreferences.edit().putString(PREF_GB_SERVER_IP, ip.trim()).apply()

    internal fun getGbServerPort(): Int = sharedPreferences.getInt(PREF_GB_SERVER_PORT, 5060)

    internal fun setGbServerPort(port: Int) = sharedPreferences.edit().putInt(PREF_GB_SERVER_PORT, port).apply()

    internal fun getGbServerId(): String = sharedPreferences.getString(PREF_GB_SERVER_ID, "") ?: ""

    internal fun setGbServerId(id: String) = sharedPreferences.edit().putString(PREF_GB_SERVER_ID, id.trim()).apply()

    internal fun getGbAgentId(): String = sharedPreferences.getString(PREF_GB_AGENT_ID, "") ?: ""

    internal fun setGbAgentId(id: String) = sharedPreferences.edit().putString(PREF_GB_AGENT_ID, id.trim()).apply()

    internal fun getGbChannel(): String = sharedPreferences.getString(PREF_GB_CHANNEL, "") ?: ""

    internal fun setGbChannel(ch: String) = sharedPreferences.edit().putString(PREF_GB_CHANNEL, ch.trim()).apply()

    internal fun getGbLocalPort(): Int = sharedPreferences.getInt(PREF_GB_LOCAL_PORT, 5061)

    internal fun setGbLocalPort(port: Int) = sharedPreferences.edit().putInt(PREF_GB_LOCAL_PORT, port).apply()

    internal fun getGbPassword(): String = sharedPreferences.getString(PREF_GB_PASSWORD, "") ?: ""

    internal fun setGbPassword(pwd: String) = sharedPreferences.edit().putString(PREF_GB_PASSWORD, pwd).apply()

    internal fun getEdgeConfidenceThreshold(): Float =
        sharedPreferences
            .getFloat(PREF_EDGE_CONFIDENCE_THRESHOLD, DEFAULT_EDGE_CONFIDENCE_THRESHOLD)
            .coerceIn(0.01f, 0.99f)

    internal fun getMediamtxServer(): String = sharedPreferences.getString(PREF_MEDIAMTX_SERVER, "")?.trim().orEmpty()

    internal fun isDetectionsEnabled(): Boolean =
        sharedPreferences.getBoolean(
            PREF_DETECTIONS_ENABLED,
            sharedPreferences.getString(
                PREF_DETECTION_SOURCE,
                null,
            ) != null &&
                getDetectionSource() != DetectionSource.NONE,
        )

    internal fun activeDetectionSource(): DetectionSource = if (isDetectionsEnabled()) getDetectionSource() else DetectionSource.NONE

    internal fun getDetectionSource(): DetectionSource {
        val stored = sharedPreferences.getString(PREF_DETECTION_SOURCE, null)
        if (stored == null && sharedPreferences.getBoolean(PREF_EDGE_DETECTION_ENABLED, false)) {
            // Legacy migration: edge detection used to be a standalone toggle.
            return DetectionSource.YOLO_ON_PHONE
        }
        // "none" (or an unset pref) stays NONE — detections are off by default.
        return DetectionSource.fromPref(stored)
    }

    internal fun isEdgeDetectionEnabled(): Boolean = isDetectionsEnabled() && getDetectionSource() == DetectionSource.YOLO_ON_PHONE
}
