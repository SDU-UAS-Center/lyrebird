package com.lyrebird.rc.controller

import com.lyrebird.rc.mavlink.CommandResult
import com.lyrebird.rc.mavlink.GimbalRotation
import com.lyrebird.rc.mavlink.GimbalRotationMode
import com.lyrebird.rc.mavlink.LrfReading
import com.lyrebird.rc.mavlink.MavlinkCommandOutcome
import com.lyrebird.rc.mavlink.PayloadCommandPort
import com.lyrebird.rc.telemetry.GeoPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shared payload policy without an SDK: validation, unit scaling into MAVLink's integer ack
 * fields, the refusal prose both surfaces render, and the threading rules — rules that used to be
 * reachable only through the V5 command sink, which is why none of them had tests.
 */
class MavlinkPayloadPolicyTest {
    private val host = FakePayloadHost()
    private val payload = FakePayloadPort()
    private val policy = MavlinkPayloadPolicy(host, payload)
    private val sink = policy.sink

    @Test
    fun `an explicit aim ends ROI tracking before it rotates the gimbal`() {
        val result =
            sink.setGimbal(
                GimbalRotation(
                    mode = GimbalRotationMode.ABSOLUTE,
                    pitchDeg = -15.0,
                    rollDeg = 0.0,
                    yawDeg = 30.0,
                    pitchIgnored = false,
                    rollIgnored = true,
                    yawIgnored = false,
                ),
            )

        assertEquals(MavlinkCommandOutcome.ACCEPTED, result.outcome)
        assertEquals("roiStop", payload.calls.first())
        assertEquals(
            "rotate:mode=ABSOLUTE,pitch=-15.0,yaw=30.0,pitchIgnored=false,yawIgnored=false",
            payload.calls[1],
        )
    }

    @Test
    fun `a relative nudge ignores the axis that did not move`() {
        sink.setGimbalRelative(pitchDeg = 0.0, yawDeg = -5.0)

        assertEquals(
            "rotate:mode=RELATIVE,pitch=0.0,yaw=-5.0,pitchIgnored=true,yawIgnored=false",
            payload.calls[1],
        )
    }

    @Test
    fun `ROI needs a real position and defaults an unset altitude to zero`() {
        assertEquals(
            MavlinkCommandOutcome.DENIED,
            sink.setRegionOfInterest(Double.NaN, 12.0, 30.0).outcome,
        )
        assertTrue(payload.calls.isEmpty())

        val accepted = sink.setRegionOfInterest(55.0, 12.0, Double.NaN)

        assertEquals(MavlinkCommandOutcome.ACCEPTED, accepted.outcome)
        assertEquals(listOf("roi:55.0,12.0,0.0"), payload.calls)
    }

    @Test
    fun `clearing the ROI stops tracking`() {
        sink.clearRegionOfInterest()

        assertEquals(listOf("roiStop"), payload.calls)
    }

    @Test
    fun `a missing rangefinder reading is refused`() {
        val result = sink.measureLrf()

        assertEquals(MavlinkCommandOutcome.FAILED, result.outcome)
        assertEquals("No rangefinder reading", result.detail)
        assertEquals(0, host.publishCount)
    }

    @Test
    fun `an unlocked laser is refused with its state`() {
        payload.lrfReading = LrfReading(stateName = "TOO_FAR", distanceM = null, target = null)

        val result = sink.measureLrf()

        assertEquals(MavlinkCommandOutcome.FAILED, result.outcome)
        assertEquals("Laser state TOO_FAR", result.detail)
        assertEquals(0, host.publishCount)
    }

    @Test
    fun `a locked reading is published and acknowledged in centimetres`() {
        val target = GeoPosition(55.5, 12.5, 30.0)
        payload.lrfReading = LrfReading(stateName = "NORMAL", distanceM = 12.5, target = target)

        val result = sink.measureLrf()

        assertEquals(MavlinkCommandOutcome.ACCEPTED, result.outcome)
        assertEquals(1_250, result.resultValue)
        assertEquals(12.5, host.publishedDistance!!, 0.0)
        assertEquals(target, host.publishedTarget)
    }

    @Test
    fun `a missing thermal reading is refused and a value is acknowledged in hundredths`() {
        assertEquals(MavlinkCommandOutcome.FAILED, sink.captureTemperature().outcome)
        assertEquals("No thermal reading", sink.captureTemperature().detail)

        payload.thermalTemp = 21.5

        assertEquals(2_150, sink.captureTemperature().resultValue)
    }

    @Test
    fun `a thermal capture reports its descriptor or refuses`() {
        assertEquals(
            "Thermal capture produced no file",
            sink.captureThermalImage().detail,
        )

        payload.thermalDescriptor = "{\"thermal\":\"T.JPG\"}"

        val result = sink.captureThermalImage()

        assertEquals(MavlinkCommandOutcome.ACCEPTED, result.outcome)
        assertEquals("{\"thermal\":\"T.JPG\"}", result.detail)
    }

    @Test
    fun `a payload with no drop port is refused with the profile prose`() {
        val result = sink.dropPayload()

        assertEquals(MavlinkCommandOutcome.UNSUPPORTED, result.outcome)
        assertEquals("REJECTED: Matrice 400 has no payload drop port configured.", result.detail)
        assertTrue(payload.calls.none { it == "drop" })
    }

    @Test
    fun `a drop reports the port it used or a failure`() {
        payload.dropPort = "PORT_4"
        payload.dropAccepted = true
        val accepted = sink.dropPayload()
        assertEquals(MavlinkCommandOutcome.ACCEPTED, accepted.outcome)
        assertEquals("Payload dropped on PORT_4", accepted.detail)

        payload.dropAccepted = false
        val failed = sink.dropPayload()
        assertEquals(MavlinkCommandOutcome.FAILED, failed.outcome)
        assertEquals("Payload drop failed", failed.detail)
    }

    @Test
    fun `zoom refuses a non-positive ratio before the payload sees it`() {
        assertEquals(MavlinkCommandOutcome.FAILED, sink.setCameraZoom(0f).outcome)
        assertTrue(payload.calls.isEmpty())

        assertEquals(MavlinkCommandOutcome.ACCEPTED, sink.setCameraZoom(2.5f).outcome)
        assertEquals(listOf("zoom:2.5"), payload.calls)
    }

    @Test
    fun `recording delegates to the payload`() {
        assertEquals(MavlinkCommandOutcome.ACCEPTED, sink.startVideoRecording().outcome)
        assertEquals(MavlinkCommandOutcome.ACCEPTED, sink.stopVideoRecording().outcome)
        assertEquals(listOf("record:start", "record:stop"), payload.calls)
    }

    @Test
    fun `auto-sensing runs on the main thread and syncs the switch`() {
        sink.setAutoSensing(true)
        assertEquals(listOf("autoSensing:start", "autoSensingSwitch:true"), payload.calls)
        assertTrue(host.events.first() == "post")

        payload.calls.clear()
        sink.setAutoSensing(false)
        assertEquals(listOf("autoSensing:stop", "autoSensingSwitch:false"), payload.calls)
    }

    @Test
    fun `an unknown text parameter is refused without a write`() {
        val result = sink.setTextParameter("LB_NOPE", "x")

        assertEquals(MavlinkCommandOutcome.DENIED, result.outcome)
        assertEquals("LB_NOPE is not writable", result.detail)
        assertTrue(payload.calls.isEmpty())
    }

    @Test
    fun `a written text parameter echoes the value it now holds`() {
        payload.textValues[MavlinkPayloadPolicy.PARAM_RC_CONTROL_MODE] = "auto"

        val accepted = sink.setTextParameter(MavlinkPayloadPolicy.PARAM_RC_CONTROL_MODE, "auto")
        assertEquals(MavlinkCommandOutcome.ACCEPTED, accepted.outcome)
        assertEquals("auto", accepted.detail)
        assertEquals("write:LB_RC_MODE=auto", payload.calls.first())

        payload.writeApplied = false
        val denied = sink.setTextParameter(MavlinkPayloadPolicy.PARAM_RC_CONTROL_MODE, "bogus")
        assertEquals(MavlinkCommandOutcome.DENIED, denied.outcome)
        // The echo reports what the setting holds, not what was asked for.
        assertEquals("auto", denied.detail)
    }

    @Test
    fun `the text parameter list is the writable set in wire order`() {
        val names = sink.textParameters().map { it.first }

        assertEquals(
            listOf("LB_DRONE_NAME", "LB_VIDEO_SRC", "LB_MEDIAMTX", "LB_DETECT_SRC", "LB_RC_MODE", "LB_RTC_RES", "LB_STREAM_MODE"),
            names,
        )
    }

    @Test
    fun `a shutter capture is acknowledged immediately and reported from the worker`() {
        payload.photoFile = "DSC00001.JPG"

        val result = sink.captureImage()

        assertEquals(MavlinkCommandOutcome.ACCEPTED, result.outcome)
        assertEquals(listOf("captureStarted", "captured:42:true:DSC00001.JPG"), host.events)

        host.events.clear()
        payload.photoFile = null

        sink.captureImage()

        assertEquals(listOf("captureStarted", "captured:42:false:"), host.events)
    }

    @Test
    fun `setParameter reaches the payload`() {
        sink.setParameter("LB_RTH_ALT", 60f)

        assertEquals(listOf("param:LB_RTH_ALT=60.0"), payload.calls)
    }

    private class FakePayloadHost : MavlinkPayloadHost {
        val events = mutableListOf<String>()
        var publishedDistance: Double? = null
        var publishedTarget: GeoPosition? = null
        var publishCount = 0

        override fun postToMain(block: () -> Unit) {
            events += "post"
            block()
        }

        override fun runCapture(block: () -> Unit) {
            block()
        }

        override fun reportCaptureStarted(): Long {
            events += "captureStarted"
            return 42L
        }

        override fun reportImageCaptured(
            captureId: Long,
            success: Boolean,
            fileName: String,
        ) {
            events += "captured:$captureId:$success:$fileName"
        }

        override fun publishLrfReading(
            distanceM: Double?,
            target: GeoPosition?,
        ) {
            publishedDistance = distanceM
            publishedTarget = target
            publishCount++
        }
    }

    private class FakePayloadPort : PayloadCommandPort {
        val calls = mutableListOf<String>()
        val textValues = mutableMapOf<String, String>()
        var lrfReading: LrfReading? = null
        var thermalTemp: Double? = null
        var thermalDescriptor: String? = null
        var photoFile: String? = null
        var dropPort: String? = null
        var dropAccepted = true
        var writeApplied = true
        private var recordingResult = CommandResult(MavlinkCommandOutcome.ACCEPTED)

        override fun rotateGimbal(rotation: GimbalRotation) {
            calls +=
                "rotate:mode=${rotation.mode},pitch=${rotation.pitchDeg},yaw=${rotation.yawDeg}," +
                "pitchIgnored=${rotation.pitchIgnored},yawIgnored=${rotation.yawIgnored}"
        }

        override fun takeLrfReading(): LrfReading? = lrfReading

        override fun readThermalMaxTempC(): Double? = thermalTemp

        override fun captureThermalImage(): String? = thermalDescriptor

        override fun capturePhoto(): String? = photoFile

        override fun setZoomRatio(ratio: Double) {
            calls += "zoom:$ratio"
        }

        override fun startRecording(): CommandResult {
            calls += "record:start"
            return recordingResult
        }

        override fun stopRecording(): CommandResult {
            calls += "record:stop"
            return recordingResult
        }

        override fun payloadDropPort(): String? = dropPort

        override val aircraftDisplayName: String get() = "Matrice 400"

        override fun dropPayload(): Boolean {
            calls += "drop"
            return dropAccepted
        }

        override fun startAutoSensing() {
            calls += "autoSensing:start"
        }

        override fun stopAutoSensing() {
            calls += "autoSensing:stop"
        }

        override fun setAutoSensingSwitch(checked: Boolean) {
            calls += "autoSensingSwitch:$checked"
        }

        override fun startRoiTracking(
            latitudeDeg: Double,
            longitudeDeg: Double,
            altitudeM: Double,
        ) {
            calls += "roi:$latitudeDeg,$longitudeDeg,$altitudeM"
        }

        override fun stopRoiTracking() {
            calls += "roiStop"
        }

        override fun applyMavlinkParameter(
            name: String,
            value: Float,
        ): CommandResult {
            calls += "param:$name=$value"
            return CommandResult(MavlinkCommandOutcome.ACCEPTED)
        }

        override fun writeTextSetting(
            name: String,
            value: String,
        ): Boolean {
            calls += "write:$name=$value"
            return writeApplied
        }

        override fun readTextSetting(name: String): String = textValues[name].orEmpty()
    }
}
