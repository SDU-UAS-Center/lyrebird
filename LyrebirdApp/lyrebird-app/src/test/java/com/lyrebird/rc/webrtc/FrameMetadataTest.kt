package com.lyrebird.rc.webrtc

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameMetadataTest {
    @Test
    fun fromJsonReadsNestedDetectionMetadata() {
        val json = JSONObject()
            .put("frameNumber", 42L)
            .put("timestampNs", 123_456L)
            .put("captureTimeMs", 999L)
            .put("droneName", "scout")
            .put("frameWidth", 1280)
            .put("frameHeight", 720)
            .put("latitude", 55.1)
            .put("longitude", 10.2)
            .put("altitudeASL", 31.5)
            .put("altitudeAGL", 12.5)
            .put("aircraftPitch", 1.0)
            .put("aircraftRoll", 2.0)
            .put("aircraftYaw", 3.0)
            .put("gimbalPitch", -10.0)
            .put("gimbalRoll", 0.5)
            .put("gimbalYaw", 4.0)
            .put("velocityX", 1.1)
            .put("velocityY", 2.2)
            .put("velocityZ", -0.3)
            .put("satelliteCount", 12)
            .put("batteryPercent", 87)
            .put("isFlying", true)
            .put("flightMode", "GPS")
            .put("isManualOverrideActive", true)
            .put(
                "detections",
                JSONObject()
                    .put("source", "phone-yolo")
                    .put("active", true)
                    .put("model", "lyrebird-v1")
                    .put("confidenceThreshold", 0.65)
                    .put("targets", JSONArray())
            )

        val metadata = FrameMetadata.fromJson(json.toString())

        assertEquals(42L, metadata.frameNumber)
        assertEquals("scout", metadata.droneName)
        assertEquals(1280, metadata.frameWidth)
        assertEquals(720, metadata.frameHeight)
        assertTrue(metadata.isFlying)
        assertTrue(metadata.isManualOverrideActive)
        assertEquals("phone-yolo", metadata.detectionSource)
        assertTrue(metadata.detectionActive)
        assertEquals("lyrebird-v1", metadata.detectionModel)
        assertEquals(0.65f, metadata.detectionConfidenceThreshold ?: 0f, 0.001f)
        assertEquals(0, metadata.detectedTargets.size)
    }

    @Test
    fun toJsonRoundTripPreservesCoreTelemetryAndDetectionState() {
        val original = FrameMetadata(
            frameNumber = 7L,
            timestampNs = 456L,
            captureTimeMs = 789L,
            droneName = "m350",
            frameWidth = 1920,
            frameHeight = 1080,
            latitude = 1.2,
            longitude = 3.4,
            altitudeASL = 5.6,
            altitudeAGL = 7.8,
            aircraftPitch = 1.0,
            aircraftRoll = 2.0,
            aircraftYaw = 3.0,
            gimbalPitch = 4.0,
            gimbalRoll = 5.0,
            gimbalYaw = 6.0,
            velocityX = 0.1,
            velocityY = 0.2,
            velocityZ = 0.3,
            satelliteCount = 10,
            batteryPercent = 55,
            isFlying = false,
            flightMode = "SPORT",
            isManualOverrideActive = false,
            detectionSource = "none",
            detectionActive = false,
            detectionModel = null,
            detectionConfidenceThreshold = null
        )

        val parsed = FrameMetadata.fromJson(original.toJsonString())

        assertEquals(original.frameNumber, parsed.frameNumber)
        assertEquals(original.droneName, parsed.droneName)
        assertEquals(original.frameWidth, parsed.frameWidth)
        assertEquals(original.frameHeight, parsed.frameHeight)
        assertEquals(original.batteryPercent, parsed.batteryPercent)
        assertFalse(parsed.detectionActive)
        assertEquals("none", parsed.detectionSource)
        assertNull(parsed.detectionModel)
        assertNull(parsed.detectionConfidenceThreshold)
    }
}