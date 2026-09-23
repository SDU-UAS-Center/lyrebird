package com.lyrebird.rc.edge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectionTelemetryStateTest {
    @Test
    fun disabledDetectionPublishesNoneAndNoActiveModel() {
        val state = DetectionTelemetryProjection.project("yolo_on_phone", false, false, true, "model.tflite", 0.4f)

        assertEquals("none", state.source)
        assertFalse(state.active)
        assertEquals(null, state.modelName)
        assertEquals(null, state.threshold)
    }

    @Test
    fun onboardDetectionDoesNotPublishLocalModelMetadata() {
        val state = DetectionTelemetryProjection.project("dji_onboard", true, true, false, "model.tflite", 0.4f)

        assertTrue(state.active)
        assertEquals("dji_onboard", state.source)
        assertEquals(null, state.modelName)
        assertEquals(null, state.threshold)
    }

    @Test
    fun localDetectionPublishesItsModelMetadata() {
        val state = DetectionTelemetryProjection.project("yolo_on_phone", true, false, true, "model.tflite", 0.4f)

        assertTrue(state.active)
        assertEquals("model.tflite", state.modelName)
        assertEquals(0.4f, state.threshold)
    }
}
