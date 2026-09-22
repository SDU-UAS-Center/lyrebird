package com.lyrebird.rc.server

import com.lyrebird.rc.mavlink.MavlinkCommandOutcome
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The MAVLink parameter table moved out of the activity so the command path survives the screen.
 *
 * Two things must not drift while it lives somewhere new: the parameter names are the wire
 * contract a ground station writes against, and a refused write must say so rather than being
 * accepted and dropped. The branches that reach the aircraft need the SDK and are exercised on
 * the bench, not here.
 */
class ProcessMavlinkParametersTest {
    @Test
    fun `the writable parameter names keep their wire spelling`() {
        assertEquals("LB_RTH_ALT", ProcessMavlinkParameters.PARAM_RTH_ALTITUDE)
        assertEquals("LB_MAX_HEIGHT", ProcessMavlinkParameters.PARAM_MAX_HEIGHT)
        assertEquals("LB_MAX_DIST", ProcessMavlinkParameters.PARAM_MAX_DISTANCE)
        assertEquals("LB_DIST_LIMIT_EN", ProcessMavlinkParameters.PARAM_DISTANCE_LIMIT)
        assertEquals("LB_RTC_FPS", ProcessMavlinkParameters.PARAM_WEBRTC_FPS)
        assertEquals("LB_DETECT_EN", ProcessMavlinkParameters.PARAM_DETECTIONS)
        assertEquals("LB_EDGE_CONF", ProcessMavlinkParameters.PARAM_EDGE_CONFIDENCE)
        assertEquals("LB_SURFACE_H264", ProcessMavlinkParameters.PARAM_SURFACE_H264_ENCODER)
        assertEquals("LB_MAV_SYSID", ProcessMavlinkParameters.PARAM_MAVLINK_SYSTEM_ID)
    }

    @Test
    fun `a parameter outside the allowlist is refused, not dropped`() {
        val result = ProcessMavlinkParameters.apply("LB_DIST_KP", 1.0f)

        assertEquals(MavlinkCommandOutcome.DENIED, result.outcome)
        assertEquals("LB_DIST_KP is read-only", result.detail)
    }

    @Test
    fun `an unusable RTH altitude is refused before it touches the aircraft`() {
        val result = ProcessMavlinkParameters.apply(ProcessMavlinkParameters.PARAM_RTH_ALTITUDE, -5f)

        assertEquals(MavlinkCommandOutcome.DENIED, result.outcome)
        assertEquals("RTH altitude must be positive", result.detail)
    }
}
