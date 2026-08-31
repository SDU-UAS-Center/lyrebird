package com.lyrebird.rc.telemetry

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryCoordinatorTest {

    @Test
    fun realTelemetryJsonBuildsCorrectlyWithDecoupledProperties() {
        val coordinator = TelemetryCoordinator()
        coordinator.isMockEnabled = false
        coordinator.droneName = "scout_02"
        coordinator.speed = """{"x":1.1,"y":2.2,"z":3.3}"""
        coordinator.heading = 125.4
        coordinator.attitude = """{"pitch":4.0,"roll":2.0,"yaw":125.4}"""
        coordinator.location = """{"latitude":55.123,"longitude":12.456,"altitude":45.2}"""
        coordinator.altitudeASL = 45.2
        coordinator.altitudeAGL = 20.5
        coordinator.gimbalAttitude = """{"pitch":-30.0,"roll":0.0,"yaw":125.4}"""
        coordinator.gimbalJointAttitude = """{"pitch":-30.0,"roll":0.0,"yaw":125.4}"""
        coordinator.batteryLevel = 82
        coordinator.satelliteCount = 18
        coordinator.homeLocation = """{"latitude":55.122,"longitude":12.455}"""
        coordinator.distanceToHome = 12.3
        coordinator.waypointReached = true
        coordinator.intermediaryWaypointReached = false
        coordinator.yawReached = true
        coordinator.altitudeReached = true
        coordinator.isRecording = false
        coordinator.homeSet = true
        coordinator.flightMode = "GPS_NORMAL"
        coordinator.isManualOverrideActive = false
        coordinator.isAutoSensingActive = false

        coordinator.phoneLatitude = 55.121
        coordinator.phoneLongitude = 12.454
        coordinator.phoneHeading = 124.0
        coordinator.phonePressure = 1013.2f
        coordinator.phoneBattery = 95
        coordinator.wifiRssi = -45

        coordinator.webRtcMetricsJson = """{"fps":10,"rtt":15}"""
        coordinator.isDetectionsEnabled = true
        coordinator.detectionSource = "yolo_on_phone"
        coordinator.selectedDetectionSource = "yolo_on_phone"
        coordinator.detectionMenuLabel = "YOLO on phone"
        coordinator.edgeDetectionActive = true
        coordinator.edgeModelName = "test_model.tflite"
        coordinator.edgeLabelsName = "test_labels.txt"
        coordinator.edgeConfidenceThreshold = 0.35f
        coordinator.detectedTargetsJson = """[{"id":1,"label":"person","confidence":0.85}]"""
        coordinator.detectedTargetsSize = 1

        coordinator.rebuildTelemetryCache()
        val jsonString = coordinator.getTelemetryJson()
        val json = JSONObject(jsonString)

        assertEquals("scout_02", json.getString("droneName"))
        
        val speedObj = json.getJSONObject("speed")
        assertEquals(1.1, speedObj.getDouble("x"), 0.001)
        assertEquals(2.2, speedObj.getDouble("y"), 0.001)
        
        assertEquals(125.4, json.getDouble("heading"), 0.001)
        
        val attitudeObj = json.getJSONObject("attitude")
        assertEquals(4.0, attitudeObj.getDouble("pitch"), 0.001)
        
        val locationObj = json.getJSONObject("location")
        assertEquals(55.123, locationObj.getDouble("latitude"), 0.001)

        // Barometric altitude relative to takeoff (KeyAltitude)
        assertEquals(20.5, json.getDouble("altitude"), 0.001)
        
        assertEquals(82, json.getInt("batteryLevel"))
        assertEquals(18, json.getInt("satelliteCount"))
        assertTrue(json.getBoolean("homeSet"))
        assertEquals("GPS_NORMAL", json.getString("flightMode"))
        assertFalse(json.getBoolean("isManualOverrideActive"))

        val phone = json.getJSONObject("phoneLocation")
        assertEquals(55.121, phone.getDouble("latitude"), 0.001)
        assertEquals(95, phone.getInt("battery"))
        assertEquals(-45, phone.getInt("wifiRssi"))

        val detections = json.getJSONObject("detections")
        assertEquals("yolo_on_phone", detections.getString("source"))
        assertEquals("yolo_on_phone", detections.getString("selectedSource"))
        assertEquals("YOLO on phone", detections.getString("label"))
        assertTrue(detections.getBoolean("enabled"))
        assertTrue(detections.getBoolean("active"))
        assertEquals(1, detections.getInt("count"))
        assertEquals("test_model.tflite", detections.getString("model"))
        assertEquals("test_labels.txt", detections.getString("labels"))
        assertEquals(0.35, detections.getDouble("confidenceThreshold"), 0.001)
        
        val targets = detections.getJSONArray("targets")
        assertEquals(1, targets.length())
        assertEquals("person", targets.getJSONObject(0).getString("label"))
    }

    @Test
    fun mockTelemetryJsonBuildsWithoutDJISDKDependencies() {
        val coordinator = TelemetryCoordinator()
        coordinator.isMockEnabled = true
        coordinator.mockSnapshot = MockTelemetrySnapshot(
            velocity = """{"x":1.0,"y":2.0,"z":3.0}""",
            heading = 90.0,
            attitude = """{"pitch":0.0,"roll":0.0,"yaw":90.0}""",
            location = """{"latitude":55.0,"longitude":12.0,"altitude":20.0}""",
            altitudeAGL = 20.0,
            gimbalAttitude = """{"pitch":-15.0,"roll":0.0,"yaw":90.0}""",
            batteryPercent = 90,
            satelliteCount = 15,
            flightMode = "GPS_NORMAL",
            isFlying = true,
            locationLatitude = 55.0,
            locationLongitude = 12.0
        )
        coordinator.droneName = "mock_drone"
        coordinator.phoneLatitude = 1.0
        coordinator.phoneLongitude = 2.0
        coordinator.rebuildTelemetryCache()

        val jsonString = coordinator.getTelemetryJson()
        val json = JSONObject(jsonString)

        assertEquals("mock_drone", json.getString("droneName"))

        assertEquals(20.0, json.getDouble("altitude"), 0.001)
        
        val speedObj = json.getJSONObject("speed")
        assertEquals(1.0, speedObj.getDouble("x"), 0.001)
        
        val phone = json.getJSONObject("phoneLocation")
        assertEquals(1.0, phone.getDouble("latitude"), 0.001)
        assertEquals(2.0, phone.getDouble("longitude"), 0.001)

        // Verify some mock telemetry fallback values are generated correctly
        val detections = json.getJSONObject("detections")
        assertEquals("none", detections.getString("source"))
        assertFalse(detections.getBoolean("enabled"))
    }
}
