package com.lyrebird.rc.mavlink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The RTSP URL advertised to QGroundControl must name the stream MediaMTX actually has, which is
 * the path the WHIP URL published to — not whatever name the aircraft stores for itself.
 */
class MavlinkVideoStreamTest {
    @Test
    fun `the stream is named after the WHIP path, not the stored drone name`() {
        val stream = MavlinkVideoStream.fromWhipUrl("http://10.0.0.2:8889/mini7/whip", "lb_7ml308n4")
        assertEquals("rtsp://10.0.0.2:8554/mini7", stream!!.uri)
        assertEquals("mini7", stream.name)
    }

    @Test
    fun `a nested path is kept whole`() {
        val stream = MavlinkVideoStream.fromWhipUrl("http://gs:8889/fleet/red/whip", "red")
        assertEquals("rtsp://gs:8554/fleet/red", stream!!.uri)
    }

    @Test
    fun `only a whole whip segment is dropped`() {
        val stream = MavlinkVideoStream.fromWhipUrl("http://gs:8889/bigwhip/whip", "x")
        assertEquals("rtsp://gs:8554/bigwhip", stream!!.uri)
    }

    @Test
    fun `a WHIP URL with no path falls back to the drone name`() {
        val stream = MavlinkVideoStream.fromWhipUrl("http://gs:8889/whip", "RedScout")
        assertEquals("rtsp://gs:8554/RedScout", stream!!.uri)
    }

    @Test
    fun `no WHIP URL advertises nothing`() {
        assertNull(MavlinkVideoStream.fromWhipUrl(null, "RedScout"))
        assertNull(MavlinkVideoStream.fromWhipUrl("not a url", "RedScout"))
    }
}
