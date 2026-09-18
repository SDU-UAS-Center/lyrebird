package com.lyrebird.rc.edge

import com.lyrebird.rc.mavlink.DetectedTargetSnapshot
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The detected-targets wire shape, pinned. The TCP telemetry cache and the WebRTC frame metadata
 * both carry it and the ground station and video webapp parse it, so this replaced the UXSDK
 * serializer's output and must match it: index, type, optional confidence, rect — nothing else.
 */
class DetectionWireTest {
    @Test
    fun `an empty target list is an empty array`() {
        assertEquals("[]", DetectionWire.targetsJson(emptyList()))
    }

    @Test
    fun `the shape is the one the old serializer produced`() {
        val json =
            DetectionWire.targetsJson(
                listOf(
                    target(
                        type = "PERSON",
                        left = 0.1,
                        top = 0.2,
                        right = 0.3,
                        bottom = 0.4,
                        confidence = 0.5,
                    ),
                ),
            )

        assertEquals("""[{"index":0,"type":"PERSON","confidence":0.5,"rect":[0.1,0.2,0.3,0.4]}]""", json)
    }

    @Test
    fun `indices are list positions`() {
        val json = DetectionWire.targetsJson(listOf(target(type = "A"), target(type = "B")))

        assertEquals(
            """[{"index":0,"type":"A","rect":[0.0,0.0,1.0,1.0]},{"index":1,"type":"B","rect":[0.0,0.0,1.0,1.0]}]""",
            json,
        )
    }

    @Test
    fun `an absent confidence omits the key rather than writing null`() {
        val json = DetectionWire.targetsJson(listOf(target(type = "EDGE_CAR")))

        assertEquals("""[{"index":0,"type":"EDGE_CAR","rect":[0.0,0.0,1.0,1.0]}]""", json)
    }

    @Test
    fun `a label cannot break the JSON`() {
        val json = DetectionWire.targetsJson(listOf(target(type = "A\"B\\C\n</script>")))

        assertEquals(
            """[{"index":0,"type":"A\"B\\C\n<\/script>","rect":[0.0,0.0,1.0,1.0]}]""",
            json,
        )
    }

    @Test
    fun `control characters take their escaped forms`() {
        assertEquals(
            """[{"index":0,"type":"tab\there","rect":[0.0,0.0,1.0,1.0]}]""",
            DetectionWire.targetsJson(listOf(target(type = "tab\there"))),
        )

        // A raw control character becomes a unicode escape, as org.json wrote it.
        assertEquals(
            "[{\"index\":0,\"type\":\"\\u0001\",\"rect\":[0.0,0.0,1.0,1.0]}]",
            DetectionWire.targetsJson(listOf(target(type = "\u0001"))),
        )
    }

    @Test
    fun `a non-finite coordinate becomes zero rather than an unparsable number`() {
        // The old serializer threw on NaN, which would have taken the telemetry update with it;
        // zero is wrong by at most one bounding box and never produces invalid JSON.
        val json = DetectionWire.targetsJson(listOf(target(left = Double.NaN, top = Double.POSITIVE_INFINITY)))

        assertEquals("""[{"index":0,"type":"EDGE_CAR","rect":[0,0,1.0,1.0]}]""", json)
    }

    private fun target(
        type: String = "EDGE_CAR",
        left: Double = 0.0,
        top: Double = 0.0,
        right: Double = 1.0,
        bottom: Double = 1.0,
        confidence: Double? = null,
    ) = DetectedTargetSnapshot(type, left, top, right, bottom, confidence)
}
