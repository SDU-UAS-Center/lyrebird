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
internal data class MavlinkVideoStream(
    val uri: String,
    val name: String,
    val framerate: Float = 0f,
    val widthPx: Int = 0,
    val heightPx: Int = 0
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
         */
        fun fromWhipUrl(
            whipUrl: String?,
            droneName: String,
            rtspPort: Int = DEFAULT_RTSP_PORT
        ): MavlinkVideoStream? {
            val host = whipUrl?.let {
                runCatching { java.net.URI(it).host }.getOrNull()
            }?.takeIf { it.isNotBlank() } ?: return null
            val path = droneName.ifBlank { "drone" }
            return MavlinkVideoStream(
                uri = "rtsp://$host:$rtspPort/$path",
                name = path
            )
        }
    }
}
