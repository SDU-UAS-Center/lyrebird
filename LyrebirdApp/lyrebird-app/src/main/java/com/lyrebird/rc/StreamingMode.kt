package com.lyrebird.rc

enum class StreamingMode(
    val menuLabel: String,
    val prefValue: String,
) {
    WEBRTC("WebRTC (WHIP)", "webrtc"),
    RTMP("RTMP Push", "rtmp"),
    RTSP("RTSP Server Pull", "rtsp"),
    AGORA("Agora.io WebRTC", "agora"),
    GB28181("GB28181 Surveillance", "gb28181"),
    ;

    companion object {
        fun fromPref(value: String?): StreamingMode = entries.firstOrNull { it.prefValue == value } ?: WEBRTC
    }
}
