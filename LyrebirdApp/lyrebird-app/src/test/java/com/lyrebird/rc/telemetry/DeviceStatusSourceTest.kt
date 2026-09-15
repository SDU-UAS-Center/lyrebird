package com.lyrebird.rc.telemetry

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceStatusSourceTest {
    @Test
    fun unavailableDeviceValuesKeepTheirWireDefaults() {
        val coordinator = TelemetryCoordinator()
        DeviceStatusSnapshot().applyTo(coordinator)
        val phone = JSONObject(coordinator.buildTelemetryJson()).getJSONObject("phoneLocation")
        assertEquals(6, phone.length())
        assertEquals(0.0, phone.getDouble("latitude"), 0.0)
        assertEquals(0.0, phone.getDouble("longitude"), 0.0)
        assertEquals(0.0, phone.getDouble("heading"), 0.0)
        assertEquals(0.0, phone.getDouble("pressure"), 0.0)
        assertEquals(-1, phone.getInt("battery"))
        assertEquals(-100, phone.getInt("wifiRssi"))
    }

    @Test
    fun deviceSnapshotFeedsFullAndGapTelemetryIdentically() {
        val coordinator = TelemetryCoordinator()
        DeviceStatusSnapshot(55.2, 10.1, 270.0, 1013.25f, 84, -56).applyTo(coordinator)
        coordinator.rebuildTelemetryCache()
        val full = JSONObject(coordinator.getTelemetryJson()).getJSONObject("phoneLocation")
        val gap = JSONObject(coordinator.getGapTelemetryJson()).getJSONObject("phoneLocation")
        assertEquals(full.toString(), gap.toString())
        assertEquals(55.2, full.getDouble("latitude"), 0.0)
        assertEquals(10.1, full.getDouble("longitude"), 0.0)
        assertEquals(1013.25, full.getDouble("pressure"), 0.0)
        assertEquals(84, full.getInt("battery"))
        assertEquals(-56, full.getInt("wifiRssi"))
    }

    @Test
    fun compassHeadingPreservesSensorAzimuthConvention() {
        assertEquals(0.0, DeviceStatusSnapshot.headingDegrees(0.0), 0.0001)
        assertEquals(90.0, DeviceStatusSnapshot.headingDegrees(Math.PI / 2), 0.0001)
        assertEquals(180.0, DeviceStatusSnapshot.headingDegrees(-Math.PI), 0.0001)
        assertEquals(270.0, DeviceStatusSnapshot.headingDegrees(-Math.PI / 2), 0.0001)
    }
}