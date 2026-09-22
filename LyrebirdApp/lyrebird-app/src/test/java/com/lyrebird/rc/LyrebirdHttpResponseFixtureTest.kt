package com.lyrebird.rc

import android.os.Handler
import com.lyrebird.rc.controller.ControlAuthority
import com.lyrebird.rc.mavlink.CommandResult
import com.lyrebird.rc.mavlink.GimbalRotation
import com.lyrebird.rc.mavlink.MavlinkCommandOutcome
import com.lyrebird.rc.mavlink.MavlinkCommandSink
import com.lyrebird.rc.mavlink.PendingCommand
import com.lyrebird.rc.mavlink.PendingKind
import com.lyrebird.rc.telemetry.GeoPoint3D
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.OutputStream

/**
 * The HTTP command surface's response text, pinned before the bridge moves the code.
 *
 * The dashboard and the Python ground station match on these strings ("REJECTED:",
 * "WAYPOINT_REFUSED", "seq="), so the flights, waypoints and refusals must keep their text while
 * the command policy underneath changes. This drives the real [LyrebirdHttpCommandHandler] with
 * fake flight ports; it deliberately does not start a socket server. The authority-rejection
 * branch is exercised through the only release path a unit test can reach without latching the
 * process-wide Safety takeover.
 */
class LyrebirdHttpResponseFixtureTest {
    private val flight = FakeFlight()
    private val aircraftSettings = FakeAircraftSettings()
    private val handler = LyrebirdHttpCommandHandler(FakeHost(flight, aircraftSettings), FakeSink())

    private fun post(uri: String, body: String = "") =
        handler.handlePostRequest(uri, body, ControlAuthority.Source.PILOT)

    @Test
    fun `movement routes keep their acknowledgement text`() {
        assertEquals("Takeoff command sent.", post("/send/takeoff"))
        assertEquals("Landing command sent.", post("/send/land"))
        assertEquals("Return to home command sent.", post("/send/RTH"))
        assertEquals(listOf("takeoff", "land", "rth"), flight.calls)
    }

    @Test
    fun `an accepted waypoint reports its seq and parameters`() {
        flight.waypointResult =
            CommandResult(
                outcome = MavlinkCommandOutcome.ACCEPTED,
                pending = PendingCommand(PendingKind.WAYPOINT, 7),
            )
        assertEquals(
            "WAYPOINT_ACCEPTED seq=7 Latitude=55.1, Longitude=12.2, Altitude=30.0, " +
                "Yaw=180.0, MaxSpeed=3.5",
            post("/send/gotoWaypointHoldHeading", "55.1,12.2,30.0,180.0,3.5"),
        )
    }

    @Test
    fun `a refused waypoint reports the refusal instead of accepted`() {
        flight.waypointResult =
            CommandResult(
                outcome = MavlinkCommandOutcome.DENIED,
                detail = "Below minimum altitude",
                pending = PendingCommand(PendingKind.WAYPOINT, 7),
            )
        assertEquals(
            "WAYPOINT_REFUSED seq=7 reason=Below minimum altitude Latitude=55.1, Longitude=12.2",
            post("/send/gotoWaypointHoldHeading", "55.1,12.2,30.0,180.0,3.5"),
        )
    }

    @Test
    fun `only the Safety Computer can release safety control`() {
        assertEquals(
            "REJECTED: only the Safety Computer can release safety control.",
            post("/releaseSafetyControl"),
        )
    }

    @Test
    fun `accepted aircraft settings keep their acknowledgement text`() {
        assertEquals("RC control mode set to usa", post("/send/setRcControlMode", "USA"))
        assertEquals("RC pairing requested", post("/send/rcPairing/start"))
        assertEquals("RC pairing stopped", post("/send/rcPairing/stop"))
        assertEquals("Distance limit enabled", post("/send/setDistanceLimitEnabled", "true"))
        assertEquals(listOf("setRcControlMode(USA)", "requestRcPairing", "stopRcPairing", "setDistanceLimitEnabled(true)"), aircraftSettings.calls)
    }

    @Test
    fun `a refused setting reports the aircraft's reason, not a malformed request`() {
        aircraftSettings.result =
            CommandResult(
                MavlinkCommandOutcome.DENIED,
                detail = "Aircraft in standby: RC control mode not changed (wanted usa)",
            )
        assertEquals(
            "Aircraft in standby: RC control mode not changed (wanted usa)",
            post("/send/setRcControlMode", "usa"),
        )
    }

    @Test
    fun `a refused limit write reports the aircraft's reason instead of the success line`() {
        aircraftSettings.result =
            CommandResult(
                MavlinkCommandOutcome.DENIED,
                detail = "REJECTED: aircraft in standby, RTH altitude not changed",
            )
        assertEquals(
            "REJECTED: aircraft in standby, RTH altitude not changed",
            post("/send/setRTHAltitude", "30"),
        )
    }

    @Test
    fun `a refused pairing request reports why instead of claiming it was requested`() {
        aircraftSettings.result =
            CommandResult(MavlinkCommandOutcome.FAILED, detail = "runtime detached")
        assertEquals("runtime detached", post("/send/rcPairing/start"))
    }

    @Test
    fun `an unknown route is not found by name`() {
        assertEquals("Not Found: /send/unknown", post("/send/unknown"))
    }
}

private class FakeFlight : LyrebirdFlightPort {
    val calls = mutableListOf<String>()
    var waypointResult: CommandResult = CommandResult(MavlinkCommandOutcome.ACCEPTED)

    private fun record(name: String): CommandResult {
        calls += name
        return CommandResult(MavlinkCommandOutcome.ACCEPTED)
    }

    override fun takeoff(): CommandResult = record("takeoff")

    override fun land(): CommandResult = record("land")

    override fun returnToHome(): CommandResult = record("rth")

    override fun stick(command: StickCommand): CommandResult = record("stick")

    override fun gotoYaw(yawDeg: Double): CommandResult = record("gotoYaw")

    override fun gotoAltitude(altitudeM: Double): CommandResult = record("gotoAltitude")

    override fun abortMission(): CommandResult = record("abortMission")

    override fun abortAll(): CommandResult = record("abortAll")

    override fun enableVirtualStick(): CommandResult = record("enableVirtualStick")

    override fun waypoint(
        latitudeDeg: Double,
        longitudeDeg: Double,
        altitudeM: Double,
        yawDeg: Double,
        maxSpeedMps: Double,
        noseForward: Boolean,
    ): CommandResult {
        calls += "waypoint"
        return waypointResult
    }

    override fun nativeTrajectory(
        waypoints: List<Triple<Double, Double, Double>>,
        speedMps: Double,
    ): CommandResult = error("not used by this fixture")

    override fun abortNativeMission(): CommandResult = error("not used by this fixture")

    override fun deactivateManualOverride(): CommandResult = error("not used by this fixture")

    override fun isManualOverrideActive(): Boolean = false
}

/**
 * The aircraft's own settings, faked so the route text can be pinned without DJI keys.
 *
 * The result is settable because the routes' honesty is the point: a refusal must render the
 * aircraft's reason, not the success line the route used to print unconditionally.
 */
private class FakeAircraftSettings : LyrebirdAircraftSettingsPort {
    val calls = mutableListOf<String>()
    var result: CommandResult = CommandResult(MavlinkCommandOutcome.ACCEPTED)

    private fun record(name: String): CommandResult {
        calls += name
        return result
    }

    override fun setRthAltitude(altitudeM: Int): CommandResult = record("setRthAltitude($altitudeM)")

    override fun setMaxFlightHeight(heightM: Int): CommandResult = record("setMaxFlightHeight($heightM)")

    override fun setMaxFlightDistance(distanceM: Int): CommandResult = record("setMaxFlightDistance($distanceM)")

    override fun setDistanceLimitEnabled(enabled: Boolean): CommandResult = record("setDistanceLimitEnabled($enabled)")

    override fun setRcControlMode(mode: String): CommandResult = record("setRcControlMode($mode)")

    override fun requestRcPairing(): CommandResult = record("requestRcPairing")

    override fun stopRcPairing(): CommandResult = record("stopRcPairing")

    override fun rcControlMode(): String = "usa"

    override fun rcPairingStatus(): String = "paired"

    override fun hdFrequencyBand(): String = "2.4g"
}

private class FakeMedia : LyrebirdMediaPort {
    override fun capturePhotoFileName(): String? = error("not used by this fixture")

    override fun captureThermalJson(): String? = error("not used by this fixture")

    override fun listMediaJson(): String = error("not used by this fixture")

    override fun sendMediaFile(
        fileName: String,
        outputStream: OutputStream,
    ) = error("not used by this fixture")

    override fun sendErrorResponse(
        message: String,
        outputStream: OutputStream,
    ) = error("not used by this fixture")
}

private class FakeDetection : LyrebirdDetectionPort {
    override val isAutoSensingActive: Boolean = false

    override fun currentTargets() = emptyList<com.lyrebird.rc.mavlink.DetectedTargetSnapshot>()
}

private class FakeHost(
    private val flightPort: LyrebirdFlightPort,
    private val settingsPort: LyrebirdAircraftSettingsPort = FakeAircraftSettings(),
) : LyrebirdCommandHost {
    override val mainHandler: Handler get() = error("not used by this fixture")

    override val droneName: String = "lb_fixture"

    override val streamingModeName: String = StreamingMode.WEBRTC.wireName

    override val media: LyrebirdMediaPort = FakeMedia()

    override val detection: LyrebirdDetectionPort = FakeDetection()

    override val flight: LyrebirdFlightPort = flightPort

    override val aircraftSettings: LyrebirdAircraftSettingsPort = settingsPort

    override fun readSettingsJson(): String = error("not used by this fixture")

    override fun setDroneName(name: String): Boolean = error("not used by this fixture")

    override fun setMavlinkSystemId(value: Int): Boolean = error("not used by this fixture")

    override fun setVideoSource(value: String): Boolean = error("not used by this fixture")

    override fun setWebRtcResolution(value: String): Boolean = error("not used by this fixture")

    override fun setWebRtcFps(value: Int): Boolean = error("not used by this fixture")

    override fun setDetectionsEnabled(enabled: Boolean) = error("not used by this fixture")

    override fun setDetectionSource(value: String): Boolean = error("not used by this fixture")

    override fun setEdgeConfidence(threshold: Float): Boolean = error("not used by this fixture")

    override fun setMediamtxServer(value: String): Boolean = error("not used by this fixture")

    override fun setDjiSurfaceH264Encoder(enabled: Boolean) = error("not used by this fixture")

    override fun restartActiveStreaming() = error("not used by this fixture")

    override fun setStreamingMode(mode: StreamingMode) = error("not used by this fixture")

    override fun startAutoSensing() = error("not used by this fixture")

    override fun stopAutoSensing() = error("not used by this fixture")

    override fun updateManualOverrideUI() = error("not used by this fixture")

    override fun classifyCommandSource(presentedToken: String?): ControlAuthority.Source =
        error("not used by this fixture")

    override fun readThermalMaxTempNow(): Double? = error("not used by this fixture")

    override fun readLrfMeasurement(): LrfMeasurement = error("not used by this fixture")

    override fun setLrfTarget(target: GeoPoint3D?) = error("not used by this fixture")

    override fun hasThermalCamera(): Boolean = error("not used by this fixture")

    override fun setAutoSensingSwitchChecked(checked: Boolean) = error("not used by this fixture")
}

private class FakeSink : MavlinkCommandSink {
    override fun setGimbal(rotation: GimbalRotation): CommandResult = error("not used by this fixture")

    override fun setCameraZoom(zoomRatio: Float): CommandResult = error("not used by this fixture")

    override fun startVideoRecording(): CommandResult = error("not used by this fixture")

    override fun stopVideoRecording(): CommandResult = error("not used by this fixture")

    override fun captureImage(): CommandResult = error("not used by this fixture")

    override fun setGimbalRelative(
        pitchDeg: Double,
        yawDeg: Double,
    ): CommandResult = error("not used by this fixture")

    override fun measureLrf(): CommandResult = error("not used by this fixture")

    override fun captureTemperature(): CommandResult = error("not used by this fixture")

    override fun captureThermalImage(): CommandResult = error("not used by this fixture")

    override fun dropPayload(): CommandResult = error("not used by this fixture")

    override fun setAutoSensing(enabled: Boolean): CommandResult = error("not used by this fixture")

    override fun setParameter(
        name: String,
        value: Float,
    ): CommandResult = error("not used by this fixture")

    override fun setTextParameter(
        name: String,
        value: String,
    ): CommandResult = error("not used by this fixture")

    override fun textParameters(): List<Pair<String, String>> = error("not used by this fixture")

    override fun setRegionOfInterest(
        latitudeDeg: Double,
        longitudeDeg: Double,
        altitudeM: Double,
    ): CommandResult = error("not used by this fixture")

    override fun clearRegionOfInterest(): CommandResult = error("not used by this fixture")
}
