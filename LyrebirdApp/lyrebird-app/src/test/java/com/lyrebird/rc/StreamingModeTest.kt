package com.lyrebird.rc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The outbound name of a video mode, and why it is not the stored preference.
 *
 * A bench check found the same aircraft reporting "webrtc" over MAVLink and "whip" over HTTP: the
 * stored token leaked onto one surface only. These pin the two apart, and pin the MAVLink field's
 * width so a longer name cannot be truncated into a mode that does not exist.
 */
class StreamingModeTest {
    @Test
    fun `the published path keeps its own name whatever the stored token is`() {
        assertEquals("webrtc", StreamingMode.WEBRTC.prefValue)
        assertEquals("whip", StreamingMode.WEBRTC.wireName)
    }

    @Test
    fun `every outbound name fits the MAVLink config field`() {
        StreamingMode.entries.forEach { mode ->
            assertTrue(
                "${mode.name} reports \"${mode.wireName}\", which LYREBIRD_CONFIG's char[12] " +
                    "video_mode field would truncate",
                mode.wireName.length <= 12,
            )
        }
    }

    @Test
    fun `stored preferences still select the mode they always did`() {
        StreamingMode.entries.forEach { mode ->
            assertEquals(mode, StreamingMode.fromPref(mode.prefValue))
        }
        assertEquals(StreamingMode.WEBRTC, StreamingMode.fromPref("not-a-mode"))
    }
}
