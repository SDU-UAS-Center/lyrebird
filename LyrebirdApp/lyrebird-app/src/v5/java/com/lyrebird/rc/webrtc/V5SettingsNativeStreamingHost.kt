package com.lyrebird.rc.webrtc

import android.content.SharedPreferences
import com.lyrebird.rc.settings.LyrebirdSettings
import com.lyrebird.rc.util.NetworkUtils

/**
 * [V5NativeStreamingHost] owned by the streaming runtime, backed by preferences.
 *
 * The native streamer must keep flying after the screen does: its URL, ports and credentials are
 * read from the same preference store the settings screens write, not from the activity that
 * happened to be open when streaming started. Only the three UI effects go through callbacks,
 * and they resolve weakly — a closed screen receives no toasts or footer updates, while the
 * stream keeps its configuration instead of degrading to empty credentials.
 */
internal class V5SettingsNativeStreamingHost(
    private val preferences: SharedPreferences,
    private val onStatus: (String) -> Unit,
    private val onMessage: (String) -> Unit,
    private val onConfigChanged: () -> Unit,
) : V5NativeStreamingHost {
    private val settings = LyrebirdSettings(preferences)

    override fun rtmpUrl(clientIp: String): String {
        val stored = preferences.getString(LyrebirdSettings.PREF_RTMP_URL, "")?.trim().orEmpty()
        return stored.ifEmpty { "rtmp://$clientIp:1935/${droneName()}" }
    }

    override fun setRtmpUrl(url: String) = settings.setRtmpUrl(url)

    override fun rtspPort() = settings.getRtspPort()

    override fun setRtspPort(port: Int) = settings.setRtspPort(port)

    override fun resolveRtspPort(): Int {
        val configured = settings.getRtspPort()
        if (!NetworkUtils.isPortInUse(configured)) return configured
        // The configured port is taken by something else; try the fallbacks the app has always
        // used, and report the configured one when nothing is free so the failure is honest.
        return FALLBACK_RTSP_PORTS.firstOrNull { !NetworkUtils.isPortInUse(it) } ?: configured
    }

    override fun rtspUsername() = settings.getRtspUsername()

    override fun rtspPassword() = settings.getRtspPassword()

    override fun agoraChannel() = settings.getAgoraChannel()

    override fun agoraToken() = settings.getAgoraToken()

    override fun agoraUid() = settings.getAgoraUid()

    override fun gbServerIp() = settings.getGbServerIp()

    override fun gbServerPort() = settings.getGbServerPort()

    override fun gbServerId() = settings.getGbServerId()

    override fun gbAgentId() = settings.getGbAgentId()

    override fun gbChannel() = settings.getGbChannel()

    override fun gbLocalPort() = settings.getGbLocalPort()

    override fun gbPassword() = settings.getGbPassword()

    override fun onStatus(status: String) = onStatus.invoke(status)

    override fun onMessage(message: String) = onMessage.invoke(message)

    override fun onConfigChanged() = onConfigChanged.invoke()

    /** The name the aircraft publishes under, from the same preference the settings screen writes. */
    private fun droneName(): String =
        preferences
            .getString(LyrebirdSettings.PREF_DRONE_NAME, LyrebirdSettings.DEFAULT_DRONE_NAME)
            ?.trim()
            .orEmpty()
            .ifEmpty { LyrebirdSettings.DEFAULT_DRONE_NAME }

    private companion object {
        val FALLBACK_RTSP_PORTS = intArrayOf(18554, 28554, 38554)
    }
}
