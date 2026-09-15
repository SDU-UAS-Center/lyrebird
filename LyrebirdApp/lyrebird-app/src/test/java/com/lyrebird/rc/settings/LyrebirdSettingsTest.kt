package com.lyrebird.rc.settings

import com.lyrebird.rc.StreamingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class LyrebirdSettingsTest {
    @Test
    fun displayValuesKeepTheirOriginalLabels() {
        assertEquals("Unavailable", SettingsDisplay.limit(-1))
        assertEquals("0 m", SettingsDisplay.limit(0))
        assertEquals("120 m", SettingsDisplay.limit(120))
        assertEquals("1023 MB", SettingsDisplay.capacity(1023))
        assertEquals("1.0 GB", SettingsDisplay.capacity(1024))
        assertEquals("1.5 GB", SettingsDisplay.capacity(1536))
        assertEquals("0m", SettingsDisplay.duration(59))
        assertEquals("59m", SettingsDisplay.duration(3599))
        assertEquals("1h 1m", SettingsDisplay.duration(3660))
        assertEquals("Off", SettingsDisplay.fleet(null))
        assertEquals("No peers", SettingsDisplay.fleet(0))
        assertEquals("1 aircraft", SettingsDisplay.fleet(1))
        assertEquals("6 aircraft", SettingsDisplay.fleet(6))
        val storage = DroneStorageStatus("SD card", "1.5 GB free")
        assertEquals("SD card (1.5 GB free)", storage.menuLabel)
        assertEquals("SD card: 1.5 GB free", storage.dialogText)
    }

    @Test
    fun settingsWireBytesRemainStable() {
        val snapshot = SettingsSnapshot(
            "mini3", "SERIAL", 42, "webrtc", "auto", 10, "none", false, 0.25f, "",
            60, 60, "confirmed", 120, 500, false, "usa", "idle", "auto", "DJI_MINI_3", "Mini 3",
        )
        val expected = """{"droneName":"mini3","aircraftSerialNumber":"SERIAL","mavlinkSystemId":42,"videoSource":"drone","streamingMode":"webrtc","webrtcResolution":"auto","webrtcFps":10,"detectionSource":"none","detectionsEnabled":false,"edgeConfidenceThreshold":0.25,"mediamtxServer":"","rthAltitude":60,"rthAltitudeEffective":60,"rthAltitudeStatus":"confirmed","maxFlightHeight":120,"maxFlightDistance":500,"distanceLimitEnabled":false,"rcControlMode":"usa","rcPairingStatus":"idle","hdFrequencyBand":"auto","detectedAircraft":"DJI_MINI_3","controlProfile":"Mini 3","groups":{"droneName":"identity","aircraftSerialNumber":"identity","mavlinkSystemId":"identity","detectedAircraft":"identity","controlProfile":"identity","videoSource":"video","streamingMode":"video","webrtcResolution":"video","webrtcFps":"video","mediamtxServer":"video","rthAltitude":"flight","rthAltitudeEffective":"flight","rthAltitudeStatus":"flight","maxFlightHeight":"flight","maxFlightDistance":"flight","distanceLimitEnabled":"flight","detectionsEnabled":"detection","detectionSource":"detection","edgeConfidenceThreshold":"detection","rcControlMode":"rc"}}"""
        assertEquals(expected, snapshot.toJson())
    }

    @Test
    fun settingsWireShapeAndSecretExclusionArePreserved() {
        val snapshot = SettingsSnapshot(
            "mini3", "SERIAL", 42, "webrtc", "auto", 10, "none", false, 0.25f, "",
            60, 60, "confirmed", 120, 500, false, "usa", "idle", "auto", "DJI_MINI_3", "Mini 3",
        )
        val json = JSONObject(snapshot.toJson())
        assertEquals(23, json.length())
        assertEquals("drone", json.getString("videoSource"))
        assertEquals(42, json.getInt("mavlinkSystemId"))
        assertEquals("identity", json.getJSONObject("groups").getString("mavlinkSystemId"))
        assertEquals("flight", json.getJSONObject("groups").getString("rthAltitudeEffective"))
        assertEquals(20, json.getJSONObject("groups").length())
        assertFalse(snapshot.toJson().contains("password", ignoreCase = true))
        assertEquals("quote\"\\line\n", JSONObject(snapshot.copy(droneName = "quote\"\\line\n").toJson()).getString("droneName"))
    }

    private fun settings(values: Map<String, Any> = emptyMap()) = LyrebirdSettings(DroneSettingsProfilesTest.FakePrefs(values))

    @Test
    fun defaultsPreserveCurrentVideoConfiguration() {
        val settings = settings()
        assertEquals(10, settings.getWebRTCFps())
        assertEquals(StreamResolutionPreset.AUTO, settings.getWebRTCResolutionPreset())
        assertEquals(StreamingMode.WEBRTC, settings.getStreamingMode())
        assertEquals(8554, settings.getRtspPort())
        assertEquals("admin", settings.getRtspUsername())
        assertEquals("lyrebird", settings.getRtspPassword())
        assertEquals(DetectionSource.NONE, settings.getDetectionSource())
        assertFalse(settings.isDetectionsEnabled())
    }

    @Test
    fun rejectedWritesLeaveStoredValuesUntouched() {
        val settings = settings()
        assertFalse(settings.setDroneName("   "))
        assertFalse(settings.setDroneName("x".repeat(33)))
        assertTrue(settings.setDroneName(" mini3 "))
        assertFalse(settings.setMavlinkSystemId(-1))
        assertFalse(settings.setMavlinkSystemId(256))
        assertTrue(settings.setMavlinkSystemId(0))
        assertTrue(settings.setMavlinkSystemId(42))
        assertTrue(settings.setWebRtcFps(20))
        assertFalse(settings.setWebRtcFps(17))
        assertEquals(20, settings.getWebRTCFps())
        assertFalse(settings.setWebRtcResolution("4k"))
        assertTrue(settings.setWebRtcResolution("720P"))
        assertEquals(StreamResolutionPreset.HD, settings.getWebRTCResolutionPreset())
        assertFalse(settings.setEdgeConfidence(Float.NaN))
        assertFalse(settings.setEdgeConfidence(0.91f))
        assertTrue(settings.setEdgeConfidence(0.4f))
        assertEquals(0.4f, settings.getEdgeConfidenceThreshold(), 0f)
        assertTrue(settings.setMediamtxServer(" relay:8889 "))
        assertFalse(settings.setMediamtxServer("x".repeat(201)))
        assertEquals("relay:8889", settings.getMediamtxServer())
    }

    @Test
    fun legacyDetectionAndInvalidPreferenceDefaultsArePreserved() {
        val settings = settings(mapOf("edge_detection_enabled" to true, "webrtc_fps" to 17, "webrtc_resolution" to "invalid"))
        assertEquals(DetectionSource.YOLO_ON_PHONE, settings.getDetectionSource())
        assertEquals(10, settings.getWebRTCFps())
        assertEquals(StreamResolutionPreset.AUTO, settings.getWebRTCResolutionPreset())
    }
}