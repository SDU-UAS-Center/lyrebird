package com.lyrebird.rc

enum class StreamingMode(
    val menuLabel: String,
    val prefValue: String,
    /**
     * The name this mode goes by outside the app: what `GET /config`, the mDNS advert, the
     * telemetry JSON and `LYREBIRD_CONFIG` report.
     *
     * Not always [prefValue]: a stored preference is a historical token (WHIP publishing kept the
     * "webrtc" it was introduced under, and existing installs hold it), while a ground station is
     * told the name of the path the aircraft actually publishes. Those two answers used to come
     * from different places on different surfaces - MAVLink said "webrtc" while HTTP said "whip"
     * for the same aircraft - and a ground station reading both cannot tell whether it is looking
     * at one video path or two.
     */
    val wireName: String,
) {
    WEBRTC("WebRTC (WHIP)", "webrtc", "whip"),
    RTMP("RTMP Push", "rtmp", "rtmp"),
    RTSP("RTSP Server Pull", "rtsp", "rtsp"),
    AGORA("Agora.io WebRTC", "agora", "agora"),
    GB28181("GB28181 Surveillance", "gb28181", "gb28181"),
    ;

    companion object {
        fun fromPref(value: String?): StreamingMode = entries.firstOrNull { it.prefValue == value } ?: WEBRTC
    }
}
