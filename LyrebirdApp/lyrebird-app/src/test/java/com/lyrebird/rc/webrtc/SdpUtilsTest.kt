package com.lyrebird.rc.webrtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SdpUtilsTest {
    @Test
    fun forceH264OnlyKeepsH264AndAssociatedPayloads() {
        val result = SdpUtils.forceH264Only(SAMPLE_SDP)

        assertTrue(result.contains("m=video 9 UDP/TLS/RTP/SAVPF 102 103 116 117"))
        assertTrue(result.contains("a=rtpmap:102 H264/90000"))
        assertTrue(result.contains("a=rtpmap:103 rtx/90000"))
        assertTrue(result.contains("a=rtpmap:116 red/90000"))
        assertTrue(result.contains("a=rtpmap:117 ulpfec/90000"))
        assertFalse(result.contains("VP8"))
        assertFalse(result.contains("a=rtcp-fb:96 nack"))
    }

    @Test
    fun forceH264OnlyLeavesSdpWithoutH264Unchanged() {
        val sdp = SAMPLE_SDP.replace("a=rtpmap:102 H264/90000", "a=rtpmap:102 AV1/90000")

        assertEquals(sdp, SdpUtils.forceH264Only(sdp))
    }

    @Test
    fun setKeyframeIntervalAddsIntervalToH264FmtpOnly() {
        val result = SdpUtils.setKeyframeInterval(SAMPLE_SDP, 2000)

        assertTrue(result.contains("a=fmtp:102 profile-level-id=42e01f;x-google-max-keyframe-interval=2000"))
        assertTrue(result.contains("a=fmtp:103 apt=102"))
        assertFalse(result.contains("a=fmtp:103 apt=102;x-google-max-keyframe-interval=2000"))
    }

    @Test
    fun setKeyframeIntervalDoesNotDuplicateExistingInterval() {
        val sdp = SAMPLE_SDP.replace(
            "a=fmtp:102 profile-level-id=42e01f",
            "a=fmtp:102 profile-level-id=42e01f;x-google-max-keyframe-interval=1000"
        )

        val result = SdpUtils.setKeyframeInterval(sdp, 2000)

        assertTrue(result.contains("x-google-max-keyframe-interval=1000"))
        assertFalse(result.contains("x-google-max-keyframe-interval=1000;x-google-max-keyframe-interval=2000"))
    }

    private companion object {
        private val SAMPLE_SDP = listOf(
            "v=0",
            "o=- 0 0 IN IP4 127.0.0.1",
            "s=-",
            "t=0 0",
            "m=audio 9 UDP/TLS/RTP/SAVPF 111",
            "a=rtpmap:111 opus/48000/2",
            "m=video 9 UDP/TLS/RTP/SAVPF 96 97 102 103 116 117",
            "a=rtpmap:96 VP8/90000",
            "a=rtcp-fb:96 nack",
            "a=rtpmap:97 rtx/90000",
            "a=fmtp:97 apt=96",
            "a=rtpmap:102 H264/90000",
            "a=rtcp-fb:102 nack pli",
            "a=fmtp:102 profile-level-id=42e01f",
            "a=rtpmap:103 rtx/90000",
            "a=fmtp:103 apt=102",
            "a=rtpmap:116 red/90000",
            "a=rtpmap:117 ulpfec/90000",
            "m=application 9 UDP/DTLS/SCTP webrtc-datachannel"
        ).joinToString("\n")
    }
}