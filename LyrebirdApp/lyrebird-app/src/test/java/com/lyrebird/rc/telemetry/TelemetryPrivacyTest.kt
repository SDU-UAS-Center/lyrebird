package com.lyrebird.rc.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the telemetry stream is allowed to disclose about the streaming configuration.
 *
 * The TCP telemetry port has no client authentication, so anything serialised here reaches
 * whoever connects — a ground station, a dashboard, or a phone probing the network. The RTSP
 * password used to be published in this object, which handed it to all of them. These tests
 * exist so a future field addition cannot quietly put it back.
 */
class TelemetryPrivacyTest {

    private fun coordinator() = TelemetryCoordinator().apply {
        streamingMode = "rtsp"
        rtspPort = 8554
        rtspUser = "admin"
        streamRequiresAuth = true
        consumptionPath = "rtsp://10.0.0.5:8554/streaming/live/1"
    }

    @Test
    fun `the streaming object never carries a password field`() {
        val json = coordinator().streamingTelemetryJson()
        assertFalse("streaming telemetry must not publish a password", json.contains("rtspPwd"))
        assertFalse(json.contains("password", ignoreCase = true))
    }

    @Test
    fun `the streaming object still describes what is configured`() {
        val json = coordinator().streamingTelemetryJson()
        assertTrue(json.contains("\"mode\":\"rtsp\""))
        assertTrue(json.contains("\"rtspPort\":8554"))
        assertTrue(json.contains("\"rtspUser\":\"admin\""))
        assertTrue(json.contains("\"requiresAuth\":true"))
        assertTrue(json.contains("\"consumptionPath\":\"rtsp://10.0.0.5:8554/streaming/live/1\""))
    }

    @Test
    fun `the full telemetry object inherits the same guarantee`() {
        val json = coordinator().buildTelemetryJson()
        assertFalse(json.contains("rtspPwd"))
    }

    @Test
    fun `the trimmed gap object inherits the same guarantee`() {
        val json = coordinator().buildGapTelemetryJson()
        assertFalse(json.contains("rtspPwd"))
    }

    @Test
    fun `an unauthenticated stream reports that honestly`() {
        val coordinator = coordinator().apply { streamRequiresAuth = false }
        assertEquals(false, coordinator.streamingTelemetryJson().contains("\"requiresAuth\":true"))
        assertTrue(coordinator.streamingTelemetryJson().contains("\"requiresAuth\":false"))
    }
}
