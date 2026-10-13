package com.lyrebird.rc.mavlink

/**
 * The video stream a ground station should play.
 *
 * Lyrebird publishes by WHIP to MediaMTX, which republishes the same ingest on RTSP. That RTSP
 * URL is what gets advertised: QGroundControl's video sources are RTSP, RTP/UDP, TCP-MPEG and
 * MPEG-TS, so WHEP — although MAVLink now has a stream type for it — would be advertised and then
 * ignored. Announcing the republish costs nothing and is the difference between video appearing on
 * its own and an operator typing a URL into settings.
 */
data class MavlinkVideoStream(
    val uri: String,
    val name: String,
    val framerate: Float = 0f,
    val widthPx: Int = 0,
    val heightPx: Int = 0,
) {
    companion object {
        /** The port MediaMTX serves RTSP on in the GroundStation compose stack. */
        const val DEFAULT_RTSP_PORT = 8554

        /**
         * Derive the RTSP republish URL from the WHIP URL the app already publishes to, so the
         * ground-station address never has to be configured twice.
         *
         * `http://gs:8889/RedScout/whip` becomes `rtsp://gs:8554/RedScout`. Returns null when the
         * WHIP URL is missing or malformed, in which case nothing is advertised — better than
         * pointing a ground station at a stream that is not there.
         *
         * The path comes from the WHIP URL too, not from [droneName]: MediaMTX names the stream
         * after the path it was published to, and the ground station that chose that URL may
         * know the aircraft by another name than the one stored on it. Advertising [droneName]
         * pointed QGroundControl at `lb_<serial>` while the video sat under the dashboard's name.
         * [droneName] is only the fallback for a WHIP URL with no path.
         */
        fun fromWhipUrl(
            whipUrl: String?,
            droneName: String,
            rtspPort: Int = DEFAULT_RTSP_PORT,
        ): MavlinkVideoStream? {
            val uri = whipUrl?.let { runCatching { java.net.URI(it) }.getOrNull() } ?: return null
            val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
            val path =
                uri.path
                    .orEmpty()
                    .split('/')
                    .filter { it.isNotEmpty() }
                    .let { segments -> if (segments.lastOrNull() == "whip") segments.dropLast(1) else segments }
                    .joinToString("/")
                    .ifBlank { droneName.ifBlank { "drone" } }
            return MavlinkVideoStream(
                uri = "rtsp://$host:$rtspPort/$path",
                name = path,
            )
        }
    }
}
