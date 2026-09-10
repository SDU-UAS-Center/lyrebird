package com.lyrebird.rc.webrtc

/**
 * Configuration options for WebRTC media streaming.
 */
data class WebRTCMediaOptions(
    val mediaStreamId: String = "DJI_DRONE_STREAM",
    val videoTrackId: String = "DJI_VIDEO_TRACK",
    val videoResolutionWidth: Int = 1280,
    val videoResolutionHeight: Int = 720,
    val fps: Int = 10,
    val videoBitrate: Int = 2_000_000,  // 2 Mbps keeps six 720p/10 streams steadier on Wi-Fi
    val videoCodec: String = "H264"     // H264 High Profile for best quality
) {
    val usesSourceResolution: Boolean
        get() = videoResolutionWidth <= 0 || videoResolutionHeight <= 0

    /**
     * Cap sender bitrate conservatively for fleet Wi-Fi tests.
     * Native mode preserves source resolution, so it still needs a lower
     * ceiling than the old 6 Mbps default to avoid building long transmit queues.
     * Flight 1: 2.5 Mbps was binding for two of three drones (2.8 Mbps observed
     * ingress) and starved motion on the surface encoder; raised to 4 Mbps.
     * Flights 2-3 (VBR): fast motion still blurs at 4 Mbps on 1080p30, so the
     * native/1080p ceiling is raised to 6 Mbps for crisp motion.
     */
    fun senderBitrateBps(): Int = when {
        usesSourceResolution -> minOf(videoBitrate, 6_000_000)
        videoResolutionWidth >= 1920 || videoResolutionHeight >= 1080 -> minOf(videoBitrate, 6_000_000)
        videoResolutionWidth >= 1280 || videoResolutionHeight >= 720 -> minOf(videoBitrate, 2_000_000)
        else -> minOf(videoBitrate, 1_500_000)
    }

    companion object {
        /** Preserve the source frame size and skip app-side scaling. */
        fun native() = WebRTCMediaOptions(
            videoResolutionWidth = 0,
            videoResolutionHeight = 0,
            fps = 10,
            videoBitrate = 6_000_000,
            videoCodec = "H264"
        )

        /** 1920x1080 @ 8 Mbps — best quality for detection */
        fun fullHD() = WebRTCMediaOptions(
            videoResolutionWidth = 1920,
            videoResolutionHeight = 1080,
            fps = 30,
            videoBitrate = 8_000_000,
            videoCodec = "H264"
        )

        /** 1280x720 @ 2 Mbps — lighter on bandwidth for fleet testing */
        fun hd() = WebRTCMediaOptions(
            videoResolutionWidth = 1280,
            videoResolutionHeight = 720,
            fps = 10,
            videoBitrate = 2_000_000,
            videoCodec = "H264"
        )

        /** 640x480 @ 1.5 Mbps — low bandwidth fallback */
        fun sd() = WebRTCMediaOptions(
            videoResolutionWidth = 640,
            videoResolutionHeight = 480,
            fps = 10,
            videoBitrate = 1_500_000,
            videoCodec = "H264"
        )
    }
}
