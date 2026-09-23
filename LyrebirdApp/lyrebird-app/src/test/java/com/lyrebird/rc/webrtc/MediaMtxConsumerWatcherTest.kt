package com.lyrebird.rc.webrtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaMtxConsumerWatcherTest {
    @Test
    fun hostAndPathParsesTheDocumentedWhipUrlShape() {
        val result = mediaMtxHostAndPathFromWhipUrl("http://192.168.1.50:8889/mini1/whip")

        assertEquals("192.168.1.50" to "mini1", result)
    }

    @Test
    fun hostAndPathReturnsNullForAnUnexpectedShape() {
        assertNull(mediaMtxHostAndPathFromWhipUrl("not a url"))
        assertNull(mediaMtxHostAndPathFromWhipUrl("http://192.168.1.50:8889/"))
    }

    @Test
    fun forcesKeyframeWhenPathIsMissingFromTheResponse() {
        val json = """{"itemCount":0,"pageCount":0,"items":[]}"""

        assertTrue(shouldForceKeyframeFor(json, "mini1"))
    }

    @Test
    fun forcesKeyframeOnMalformedJson() {
        assertTrue(shouldForceKeyframeFor("not json at all", "mini1"))
    }

    @Test
    fun doesNotForceKeyframeWhenEveryReaderIsWebRtc() {
        val json = """
            {"items":[{"name":"mini1","readers":[
                {"type":"webRTCSession","id":"a"},
                {"type":"webRTCSession","id":"b"}
            ]}]}
        """.trimIndent()

        assertEquals(false, shouldForceKeyframeFor(json, "mini1"))
    }

    @Test
    fun forcesKeyframeWhenAnyReaderIsNotWebRtc() {
        // Mirrors what a real MediaMTX instance reports for an RTSP puller, confirmed against a
        // running dev stack: readers: [{"type": "rtspSession", ...}].
        val json = """
            {"items":[{"name":"mini1","readers":[
                {"type":"webRTCSession","id":"a"},
                {"type":"rtspSession","id":"b"}
            ]}]}
        """.trimIndent()

        assertTrue(shouldForceKeyframeFor(json, "mini1"))
    }

    @Test
    fun doesNotForceKeyframeWhenPathHasNoReadersYet() {
        val json = """{"items":[{"name":"mini1","readers":[]}]}"""

        assertEquals(false, shouldForceKeyframeFor(json, "mini1"))
    }

    @Test
    fun ignoresOtherDronesPathsWhenLookingUpThisOne() {
        val json = """
            {"items":[
                {"name":"drone2","readers":[{"type":"rtspSession","id":"a"}]},
                {"name":"mini1","readers":[{"type":"webRTCSession","id":"b"}]}
            ]}
        """.trimIndent()

        assertEquals(false, shouldForceKeyframeFor(json, "mini1"))
    }

    @Test
    fun readsInboundFrameErrorsForThisPath() {
        val json = """
            {"items":[
                {"name":"other","inboundFramesInError":99},
                {"name":"mini1","inboundFramesInError":12}
            ]}
        """.trimIndent()

        assertEquals(12, inboundFrameErrorsFor(json, "mini1"))
    }

    @Test
    fun treatsOnlyAnIncreasingInboundErrorCountAsPacketLossRecovery() {
        assertEquals(false, hasNewInboundFrameErrors(null, 1))
        assertEquals(false, hasNewInboundFrameErrors(12, 12))
        assertEquals(false, hasNewInboundFrameErrors(12, 0))
        assertTrue(hasNewInboundFrameErrors(12, 13))
    }
}
