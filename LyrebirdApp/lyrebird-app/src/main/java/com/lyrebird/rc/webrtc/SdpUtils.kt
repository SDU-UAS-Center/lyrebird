package com.lyrebird.rc.webrtc

/**
 * SDP manipulation utilities for forcing H264 codec and tuning keyframe interval.
 *
 * The DefaultVideoEncoderFactory always registers a software VP8 encoder even when
 * the Intel VP8 hardware encoder is disabled. During SDP negotiation the remote peer
 * (e.g. MediaMTX) may prefer VP8, causing the stream to be encoded as VP8 instead of
 * H264. These helpers strip non-H264 video codecs from the SDP so only H264 can be
 * negotiated.
 */
object SdpUtils {
    private val RTPMAP_REGEX = Regex("""^a=rtpmap:(\d+)\s+(\S+)/""")
    private val FMTP_APT_REGEX = Regex("""^a=fmtp:(\d+)\s+.*\bapt=(\d+)""")
    private val PAYLOAD_ATTRIBUTE_REGEX = Regex("""^a=(?:rtpmap|fmtp|rtcp-fb):(\d+)\b""")
    private val H264_RTPMAP_REGEX = Regex("""^a=rtpmap:(\d+)\s+H264/""")
    private val FMTP_REGEX = Regex("""^a=fmtp:(\d+)\s+""")

    /**
     * Remove all video codecs except H264 (and their associated RTX/RED/ULPFEC)
     * from the SDP, forcing the peer connection to negotiate H264.
     *
     * If no H264 codec is found the original SDP is returned unchanged.
     */
    fun forceH264Only(sdp: String): String {
        val lineEnding = if ("\r\n" in sdp) "\r\n" else "\n"
        val lines = sdp.split(lineEnding)
        val videoSection = findVideoSection(lines) ?: return sdp
        val codecInfo = collectCodecInfo(lines, videoSection)
        val h264PayloadTypes = codecInfo.codecForPayloadType
            .filter { it.value.equals("H264", ignoreCase = true) }
            .keys

        return if (h264PayloadTypes.isEmpty()) {
            sdp
        } else {
            val allowedPayloadTypes = allowedVideoPayloadTypes(codecInfo, h264PayloadTypes)
            rebuildVideoSection(lines, lineEnding, videoSection, allowedPayloadTypes)
        }
    }

    /**
     * Inject `x-google-max-keyframe-interval` into every H264 fmtp line.
     * This tells the libwebrtc encoder to insert a keyframe at most every
     * [intervalMs] milliseconds, improving recovery from packet loss.
     *
     * @param intervalMs max interval between keyframes in milliseconds (e.g. 2000)
     */
    fun setKeyframeInterval(sdp: String, intervalMs: Int): String {
        val lineEnding = if ("\r\n" in sdp) "\r\n" else "\n"
        val lines = sdp.split(lineEnding)
        val h264PayloadTypes = lines.mapNotNull { line ->
            H264_RTPMAP_REGEX.find(line)?.groupValues?.get(1)?.toInt()
        }.toSet()

        return if (intervalMs <= 0 || h264PayloadTypes.isEmpty()) {
            sdp
        } else {
            lines.joinToString(lineEnding) { line ->
                addKeyframeInterval(line, intervalMs, h264PayloadTypes)
            }
        }
    }

    /**
     * Apply both H264 enforcement and keyframe interval to an SDP string.
     */
    fun mungeForH264(sdp: String, keyframeIntervalMs: Int = 2000): String =
        setKeyframeInterval(forceH264Only(sdp), keyframeIntervalMs)

    private data class VideoSection(
        val lineIndex: Int,
        val endIndex: Int,
        val payloadTypes: List<Int>
    )

    private data class CodecInfo(
        val codecForPayloadType: Map<Int, String>,
        val rtxAssociatedPayloadType: Map<Int, Int>
    )

    private fun findVideoSection(lines: List<String>): VideoSection? {
        val videoLineIndex = lines.indexOfFirst { it.startsWith("m=video") }

        return videoLineIndex.takeIf { it >= 0 }?.let { lineIndex ->
            val nextMediaLineOffset = lines.drop(lineIndex + 1).indexOfFirst { it.startsWith("m=") }
            val endIndex = if (nextMediaLineOffset >= 0) {
                lineIndex + 1 + nextMediaLineOffset
            } else {
                lines.size
            }
            val payloadTypes = lines[lineIndex].split(" ").drop(3).mapNotNull { it.toIntOrNull() }

            VideoSection(lineIndex, endIndex, payloadTypes)
        }
    }

    private fun collectCodecInfo(lines: List<String>, section: VideoSection): CodecInfo {
        val codecForPayloadType = mutableMapOf<Int, String>()
        val rtxAssociatedPayloadType = mutableMapOf<Int, Int>()

        lines.asSequence()
            .drop(section.lineIndex + 1)
            .take(section.endIndex - section.lineIndex - 1)
            .forEach { line ->
                RTPMAP_REGEX.find(line)?.let { match ->
                    codecForPayloadType[match.groupValues[1].toInt()] = match.groupValues[2]
                }
                FMTP_APT_REGEX.find(line)?.let { match ->
                    rtxAssociatedPayloadType[match.groupValues[1].toInt()] = match.groupValues[2].toInt()
                }
            }

        return CodecInfo(codecForPayloadType, rtxAssociatedPayloadType)
    }

    private fun allowedVideoPayloadTypes(codecInfo: CodecInfo, h264PayloadTypes: Set<Int>): Set<Int> {
        val h264RtxPayloadTypes = codecInfo.rtxAssociatedPayloadType
            .filter { it.value in h264PayloadTypes }
            .keys
        val redPayloadTypes = codecInfo.codecForPayloadType
            .filter { it.value.equals("red", ignoreCase = true) }
            .keys
        val ulpfecPayloadTypes = codecInfo.codecForPayloadType
            .filter { it.value.equals("ulpfec", ignoreCase = true) }
            .keys

        return h264PayloadTypes + h264RtxPayloadTypes + redPayloadTypes + ulpfecPayloadTypes
    }

    private fun rebuildVideoSection(
        lines: List<String>,
        lineEnding: String,
        section: VideoSection,
        allowedPayloadTypes: Set<Int>
    ): String {
        return lines.mapIndexedNotNull { index, line ->
            when {
                index == section.lineIndex -> rewriteVideoLine(line, section.payloadTypes, allowedPayloadTypes)
                isDroppedVideoAttribute(index, line, section, allowedPayloadTypes) -> null
                else -> line
            }
        }.joinToString(lineEnding)
    }

    private fun rewriteVideoLine(line: String, payloadTypes: List<Int>, allowedPayloadTypes: Set<Int>): String {
        val parts = line.split(" ")
        val prefix = parts.take(3).joinToString(" ")
        val keptPayloadTypes = payloadTypes.filter { it in allowedPayloadTypes }
        return "$prefix ${keptPayloadTypes.joinToString(" ")}"
    }

    private fun isDroppedVideoAttribute(
        index: Int,
        line: String,
        section: VideoSection,
        allowedPayloadTypes: Set<Int>
    ): Boolean {
        val payloadType = PAYLOAD_ATTRIBUTE_REGEX.find(line)?.groupValues?.get(1)?.toInt()
        return index in (section.lineIndex + 1) until section.endIndex &&
            payloadType != null &&
            payloadType !in allowedPayloadTypes
    }

    private fun addKeyframeInterval(line: String, intervalMs: Int, h264PayloadTypes: Set<Int>): String {
        val payloadType = FMTP_REGEX.find(line)?.groupValues?.get(1)?.toInt()
        return if (payloadType in h264PayloadTypes && "x-google-max-keyframe-interval" !in line) {
            "$line;x-google-max-keyframe-interval=$intervalMs"
        } else {
            line
        }
    }
}
