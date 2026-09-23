package com.lyrebird.rc.edge

import com.lyrebird.rc.mavlink.DetectedTargetSnapshot

/**
 * The detected-targets JSON the TCP telemetry cache and the WebRTC frame metadata carry.
 *
 * Built by hand rather than with org.json: the shape is a public interface the ground station and
 * the video webapp parse, so it is written once here, pinned by tests, and kept out of the SDK
 * modules. It is the shape the old UXSDK serializer produced:
 * `[{"index":0,"type":"PERSON","confidence":0.5,"rect":[l,t,r,b]}]` — `confidence` omitted when
 * absent, `index` the position in the list.
 */
internal object DetectionWire {
    /** All targets as one JSON array; the index is the list position, as the old serializer had it. */
    fun targetsJson(targets: List<DetectedTargetSnapshot>): String =
        targets
            .mapIndexed { index, target -> targetJson(index, target) }
            .joinToString(prefix = "[", postfix = "]", separator = ",")

    private fun targetJson(
        index: Int,
        target: DetectedTargetSnapshot,
    ): String =
        buildString {
            append("{\"index\":").append(index)
            append(",\"type\":").append(quote(target.type))
            // Omitted rather than null, exactly as the old serializer omitted it.
            target.confidence?.let { append(",\"confidence\":").append(number(it)) }
            append(",\"rect\":[")
                .append(number(target.left))
                .append(',')
                .append(number(target.top))
                .append(',')
                .append(number(target.right))
                .append(',')
                .append(number(target.bottom))
                .append("]}")
        }

    /** JSON has no NaN or infinity; a non-finite coordinate becomes zero rather than a crash. */
    private fun number(value: Double): String = if (value.isFinite()) value.toString() else "0"

    /**
     * One JSON string, escaped as Android's JSONStringer escapes: quote and backslash take a
     * backslash, control characters take their short forms, and a forward slash is escaped only
     * after `<` so that `</` cannot close an embedded script context.
     */
    private fun quote(value: String): String =
        buildString {
            append('"')
            value.forEachIndexed { position, character ->
                when (character) {
                    '"', '\\' -> append('\\').append(character)
                    '\b' -> append("\\b")
                    '\u000C' -> append("\\f")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    '/' -> {
                        if (position > 0 && value[position - 1] == '<') append('\\')
                        append('/')
                    }

                    else ->
                        if (character < ' ') {
                            append("\\u").append(character.code.toString(16).padStart(4, '0'))
                        } else {
                            append(character)
                        }
                }
            }
            append('"')
        }
}
