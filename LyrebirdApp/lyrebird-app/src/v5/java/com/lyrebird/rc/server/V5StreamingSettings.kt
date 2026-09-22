package com.lyrebird.rc.server

import android.content.SharedPreferences
import com.lyrebird.rc.StreamingMode
import com.lyrebird.rc.settings.LyrebirdSettings
import com.lyrebird.rc.telemetry.ProcessTelemetryRuntimeRegistry
import com.lyrebird.rc.util.NetworkUtils
import com.lyrebird.rc.webrtc.WebRTCMediaOptions
import com.lyrebird.rc.webrtc.WhipEndpoint

/**
 * What the video publisher needs to know, answered from preferences and the aircraft — no screen.
 *
 * The streamer used to be composed from the Flight Deck's own callbacks: the drone name, the
 * WebRTC options, the MediaMTX address and the WHIP URL all came from the attached activity, so
 * `prepare()` returned early whenever no screen was attached and the publisher could not exist at
 * all. A ground station connecting to an RC whose screen was closed was therefore answered with
 * silence — while the same device's telemetry, MAVLink and HTTP surfaces happily served.
 *
 * Everything here is what that screen was reading anyway: the settings file, the aircraft serial,
 * and the phone's own address. The name comes from the one derivation rule
 * ([LyrebirdSettings.droneName]) so the path this publishes under is the path discovery
 * advertises and the dashboard builds its WHEP URL from.
 *
 * The preference store is injected rather than read from a context here, the same shape as
 * [com.lyrebird.rc.webrtc.V5SettingsNativeStreamingHost]: it is what makes this JVM-testable, and
 * the runtime is the only thing that knows which file the app writes.
 */
internal class V5StreamingSettings(
    private val preferences: SharedPreferences,
    private val serialProvider: () -> String = { ProcessTelemetryRuntimeRegistry.droneSerial },
) {
    private val settings = LyrebirdSettings(preferences)

    /** The name this aircraft publishes under, from the one rule that answers that question. */
    val droneName: String get() = settings.droneName(serialProvider())

    val mode: StreamingMode get() = settings.getStreamingMode()

    val options: WebRTCMediaOptions get() = settings.buildWebRTCOptions()

    /** The operator's MediaMTX server, or empty when the ground station's own address is used. */
    fun configuredServer(): String = settings.getMediamtxServer()

    /** Forgets a MediaMTX server the publisher found to be wrong, so the next start re-resolves. */
    fun clearConfiguredServer() {
        preferences.edit().remove(LyrebirdSettings.PREF_MEDIAMTX_SERVER).apply()
    }

    /** Where this device is: what a publisher with no remembering client falls back to. */
    fun defaultClientIp(): String = NetworkUtils.getDeviceIpAddress() ?: "127.0.0.1"

    /** The WHIP endpoint a ground station at [clientIp] publishes to, or consumes from. */
    fun whipUrl(clientIp: String): String =
        WhipEndpoint.url(
            clientIp = clientIp,
            droneName = droneName,
            configuredServer = settings.getMediamtxServer(),
        )
}
