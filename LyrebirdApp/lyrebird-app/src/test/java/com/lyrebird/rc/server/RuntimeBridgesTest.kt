package com.lyrebird.rc.server

import android.os.Handler
import com.lyrebird.rc.LrfMeasurement
import com.lyrebird.rc.LyrebirdCommandHost
import com.lyrebird.rc.LyrebirdDetectionPort
import com.lyrebird.rc.LyrebirdFlightPort
import com.lyrebird.rc.LyrebirdMediaPort
import com.lyrebird.rc.StickCommand
import com.lyrebird.rc.StreamingMode
import com.lyrebird.rc.controller.ControlAuthority
import com.lyrebird.rc.mavlink.CommandResult
import com.lyrebird.rc.mavlink.DetectedTargetSnapshot
import com.lyrebird.rc.mavlink.GimbalRotation
import com.lyrebird.rc.mavlink.MavlinkCommandOutcome
import com.lyrebird.rc.mavlink.MavlinkCommandSink
import com.lyrebird.rc.telemetry.GeoPoint3D
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.OutputStream

/**
 * The weak bridges between the process-scoped network runtime and whatever screen is attached.
 *
 * Two properties matter more than the rest: with nothing attached, the bridge must answer
 * honestly (a command that cannot reach the aircraft is FAILED, a token nobody can verify is the
 * Pilot's), and a stale screen's detach must never clear the bridge a newer screen installed.
 */
class RuntimeBridgesTest {
    private class FakeMedia : LyrebirdMediaPort {
        override fun capturePhotoFileName(): String? = "photo.jpg"

        override fun captureThermalJson(): String? = "{}"

        override fun listMediaJson(): String = "[]"

        override fun sendMediaFile(
            fileName: String,
            outputStream: OutputStream,
        ) = Unit

        override fun sendErrorResponse(
            message: String,
            outputStream: OutputStream,
        ) = Unit
    }

    private class FakeDetection : LyrebirdDetectionPort {
        override val isAutoSensingActive: Boolean get() = true

        override fun currentTargets(): List<DetectedTargetSnapshot> = emptyList()
    }

    private class FakeFlight : LyrebirdFlightPort {
        private fun ok() = CommandResult(MavlinkCommandOutcome.ACCEPTED)

        override fun takeoff() = ok()

        override fun land() = ok()

        override fun returnToHome() = ok()

        override fun stick(command: StickCommand) = ok()

        override fun gotoYaw(yawDeg: Double) = ok()

        override fun gotoAltitude(altitudeM: Double) = ok()

        override fun abortMission() = ok()

        override fun abortAll() = ok()

        override fun enableVirtualStick() = ok()

        override fun waypoint(
            latitudeDeg: Double,
            longitudeDeg: Double,
            altitudeM: Double,
            yawDeg: Double,
            maxSpeedMps: Double,
            noseForward: Boolean,
        ) = ok()

        override fun nativeTrajectory(
            waypoints: List<Triple<Double, Double, Double>>,
            speedMps: Double,
        ) = ok()

        override fun abortNativeMission() = ok()

        override fun setRthAltitude(altitudeM: Int) = ok()

        override fun setMaxFlightHeight(heightM: Int) = ok()

        override fun setMaxFlightDistance(distanceM: Int) = ok()

        override fun setDistanceLimitEnabled(enabled: Boolean) = ok()

        override fun setRcControlMode(mode: String) = ok()

        override fun requestRcPairing() = ok()

        override fun stopRcPairing() = ok()

        override fun deactivateManualOverride() = ok()

        override fun isManualOverrideActive(): Boolean = false
    }

    private class FakeHost(
        private val name: String,
    ) : LyrebirdCommandHost {
        override val mainHandler: Handler get() = error("the bridge tests never post")

        override val droneName: String get() = name

        override val media: LyrebirdMediaPort = FakeMedia()

        override val detection: LyrebirdDetectionPort = FakeDetection()

        override val flight: LyrebirdFlightPort = FakeFlight()

        override fun readSettingsJson(): String = "{\"drone\":\"$name\"}"

        override fun setDroneName(name: String): Boolean = true

        override fun setMavlinkSystemId(value: Int): Boolean = true

        override fun setVideoSource(value: String): Boolean = true

        override fun setWebRtcResolution(value: String): Boolean = true

        override fun setWebRtcFps(value: Int): Boolean = true

        override fun setDetectionsEnabled(enabled: Boolean) = Unit

        override fun setDetectionSource(value: String): Boolean = true

        override fun setEdgeConfidence(threshold: Float): Boolean = true

        override fun setMediamtxServer(value: String): Boolean = true

        override fun setDjiSurfaceH264Encoder(enabled: Boolean) = Unit

        override fun restartActiveStreaming() = Unit

        override fun setStreamingMode(mode: StreamingMode) = Unit

        override fun startAutoSensing() = Unit

        override fun stopAutoSensing() = Unit

        override fun updateManualOverrideUI() = Unit

        override fun classifyCommandSource(presentedToken: String?): ControlAuthority.Source =
            ControlAuthority.Source.SAFETY

        override fun readThermalMaxTempNow(): Double? = 41.0

        override fun readLrfMeasurement(): LrfMeasurement = LrfMeasurement(12.0, "NORMAL", null)

        override fun setLrfTarget(target: GeoPoint3D?) = Unit

        override fun hasThermalCamera(): Boolean = true

        override fun setAutoSensingSwitchChecked(checked: Boolean) = Unit
    }

    private class FakeSink : MavlinkCommandSink {
        private fun ok() = CommandResult(MavlinkCommandOutcome.ACCEPTED)

        override fun setGimbal(rotation: GimbalRotation) = ok()

        override fun setCameraZoom(zoomRatio: Float) = ok()

        override fun startVideoRecording() = ok()

        override fun stopVideoRecording() = ok()

        override fun captureImage() = ok()

        override fun setGimbalRelative(
            pitchDeg: Double,
            yawDeg: Double,
        ) = ok()

        override fun measureLrf() = ok()

        override fun captureTemperature() = ok()

        override fun captureThermalImage() = ok()

        override fun dropPayload() = ok()

        override fun setAutoSensing(enabled: Boolean) = ok()

        override fun setParameter(
            name: String,
            value: Float,
        ) = ok()

        override fun setTextParameter(
            name: String,
            value: String,
        ) = ok()

        override fun textParameters(): List<Pair<String, String>> = listOf("lb_drone_name" to "fake")

        override fun setRegionOfInterest(
            latitudeDeg: Double,
            longitudeDeg: Double,
            altitudeM: Double,
        ) = ok()

        override fun clearRegionOfInterest() = ok()
    }

    @Test
    fun `a detached command host answers honestly instead of implying success`() {
        val bridge = RuntimeCommandHost()

        assertEquals("lb_unavailable", bridge.droneName)
        assertEquals("{}", bridge.readSettingsJson())
        assertNull(bridge.media.capturePhotoFileName())
        assertEquals("{\"error\":\"runtime detached\"}", bridge.media.listMediaJson())
        assertEquals(MavlinkCommandOutcome.FAILED, bridge.flight.takeoff().outcome)
        assertEquals("runtime detached", bridge.flight.takeoff().detail)
        assertFalse(bridge.flight.isManualOverrideActive())
        assertNull(bridge.readThermalMaxTempNow())
        assertEquals(LrfMeasurement(null, null, null), bridge.readLrfMeasurement())
        assertFalse(bridge.hasThermalCamera())
        assertFalse("a settings write must not claim success with nobody attached", bridge.setDroneName("scout"))
    }

    @Test
    fun `a detached command is classified as the Pilot, never as Safety`() {
        val bridge = RuntimeCommandHost()

        // With nobody attached there is nothing that can verify a presented token: an
        // unverifiable caller stays on the lower authority rather than being promoted.
        assertEquals(ControlAuthority.Source.PILOT, bridge.classifyCommandSource(null))
        assertEquals(ControlAuthority.Source.PILOT, bridge.classifyCommandSource("mismatched-token"))
    }

    @Test
    fun `the newest attached screen is the one served`() {
        val bridge = RuntimeCommandHost()
        bridge.attach(FakeHost("old"))
        bridge.attach(FakeHost("new"))

        assertEquals("new", bridge.droneName)
        assertEquals(MavlinkCommandOutcome.ACCEPTED, bridge.flight.takeoff().outcome)
    }

    @Test
    fun `a stale screen detach cannot clear the newer one`() {
        val bridge = RuntimeCommandHost()
        val old = FakeHost("old")
        val new = FakeHost("new")
        bridge.attach(old)
        bridge.attach(new)

        bridge.detach(old)

        assertEquals("the current screen must survive the previous one's teardown", "new", bridge.droneName)

        bridge.detach(new)
        assertEquals("lb_unavailable", bridge.droneName)
    }

    @Test
    fun `detaching a host that was never attached changes nothing`() {
        val bridge = RuntimeCommandHost()
        bridge.attach(FakeHost("attached"))

        bridge.detach(FakeHost("stranger"))

        assertEquals("attached", bridge.droneName)
    }

    @Test
    fun `a detached mavlink sink fails commands honestly`() {
        val bridge = RuntimeMavlinkCommandSink()

        assertEquals(MavlinkCommandOutcome.FAILED, bridge.captureImage().outcome)
        assertEquals("runtime detached", bridge.captureImage().detail)
        assertEquals(MavlinkCommandOutcome.FAILED, bridge.dropPayload().outcome)
        assertTrue(bridge.textParameters().isEmpty())
    }

    @Test
    fun `the sink follows the newest attachment with the same identity guard`() {
        val bridge = RuntimeMavlinkCommandSink()
        val old = FakeSink()
        val new = FakeSink()
        bridge.attach(old)
        bridge.attach(new)

        bridge.detach(old)
        assertEquals(MavlinkCommandOutcome.ACCEPTED, bridge.captureImage().outcome)

        bridge.detach(new)
        assertEquals(MavlinkCommandOutcome.FAILED, bridge.captureImage().outcome)
    }
}
