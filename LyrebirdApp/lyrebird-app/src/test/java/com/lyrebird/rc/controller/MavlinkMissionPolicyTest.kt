package com.lyrebird.rc.controller

import com.lyrebird.rc.mavlink.CommandProgress
import com.lyrebird.rc.mavlink.CommandResult
import com.lyrebird.rc.mavlink.GimbalRotation
import com.lyrebird.rc.mavlink.Mav
import com.lyrebird.rc.mavlink.MavlinkCommandOutcome
import com.lyrebird.rc.mavlink.MavlinkCommandSink
import com.lyrebird.rc.mavlink.MavlinkMotionSink
import com.lyrebird.rc.mavlink.MissionExecutor
import com.lyrebird.rc.mavlink.MissionItem
import com.lyrebird.rc.mavlink.MissionProgressListener
import com.lyrebird.rc.mavlink.MotionCommandPort
import com.lyrebird.rc.mavlink.PendingCommand
import com.lyrebird.rc.mavlink.PendingKind
import com.lyrebird.rc.mavlink.ReachLatch
import com.lyrebird.rc.mavlink.WaypointArrival
import com.lyrebird.rc.mavlink.WaypointRefusal
import com.lyrebird.rc.mavlink.WaypointRejection
import com.lyrebird.rc.platform.ManualStick
import com.lyrebird.rc.telemetry.AircraftReadings
import com.lyrebird.rc.telemetry.AircraftState
import com.lyrebird.rc.telemetry.AircraftTelemetryListener
import com.lyrebird.rc.telemetry.AircraftTelemetrySource
import com.lyrebird.rc.telemetry.GeoPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shared mission policy on a fake clock: the onboard sequencer's ordering, arrival mapping,
 * refusals, overrides, timeouts and cancellation, plus the native handoff — all without an SDK,
 * a real thread, or a second of waiting. These are exactly the rules that were unreachable while
 * the sequencer lived beside the V5 controller statics.
 */
class MavlinkMissionPolicyTest {
    private val host = FakeMissionHost()
    private val commands = FakeCommands()
    private val motion = FakeMotionSink()
    private val payload = FakeCommandSink()
    private val telemetry = FakeTelemetry()
    private val scheduler = FakeScheduler()
    private val policy = MavlinkMissionPolicy(host, commands, motion, payload, telemetry, scheduler)
    private val sink = policy.sink
    private val progress = RecordingListener()

    init {
        sink.setProgressListener(progress)
    }

    @Test
    fun `the flight gate short-circuits before any command runs`() {
        host.gate = CommandResult(MavlinkCommandOutcome.DENIED, "MAVLink flight not allowed")

        val result = sink.startMission(listOf(waypoint(0)), 0, MissionExecutor.ONBOARD)

        assertEquals(MavlinkCommandOutcome.DENIED, result.outcome)
        assertTrue(commands.legs.isEmpty())
        assertTrue(progress.events.isEmpty())
    }

    @Test
    fun `the manual-override latch refuses the mission with DENIED`() {
        commands.rejectAutonomous = true

        val result = sink.startMission(listOf(waypoint(0)), 0, MissionExecutor.ONBOARD)

        assertEquals(MavlinkCommandOutcome.DENIED, result.outcome)
        assertTrue(commands.legs.isEmpty())
    }

    @Test
    fun `a plan with no waypoints is denied`() {
        val result = sink.startMission(listOf(command(0, Mav.CMD_IMAGE_START_CAPTURE)), 0, MissionExecutor.ONBOARD)

        assertEquals(MavlinkCommandOutcome.DENIED, result.outcome)
        assertEquals("No waypoints in plan", result.detail)
        assertFalse(sink.isRunning)
    }

    @Test
    fun `an onboard plan flies every leg, reports progress and finishes`() {
        val items =
            listOf(
                // A settling waypoint: dwell and acceptance radius, nose forward (NaN yaw).
                waypoint(0, holdSeconds = 2.5f, acceptanceRadius = 8f),
                // A plan-asked pass-through: no dwell, so it must fly through rather than stop.
                waypoint(1, yaw = 90f),
                // The last leg is never a pass-through, whatever the plan says.
                waypoint(2, passRadius = 1f),
            )

        val result = sink.startMission(items, 0, MissionExecutor.ONBOARD)

        assertEquals(MavlinkCommandOutcome.ACCEPTED, result.outcome)
        assertEquals(
            listOf(
                "lat=55.0,lon=12.0,alt=30.0,yaw=0.0,speed=5.0,nose=true,pass=false,radius=8.0,hold=2.5",
                "lat=55.0,lon=12.0,alt=30.0,yaw=90.0,speed=5.0,nose=false,pass=true,radius=null,hold=0.0",
                "lat=55.0,lon=12.0,alt=30.0,yaw=0.0,speed=5.0,nose=true,pass=false,radius=null,hold=0.0",
            ),
            commands.legs,
        )
        assertEquals(
            listOf(
                "started:0", "reached:0",
                "started:1", "reached:1",
                "started:2", "reached:2",
                "finished:true",
            ),
            progress.events,
        )
        assertFalse(sink.isRunning)
    }

    @Test
    fun `a speed change sets the speed of every leg`() {
        val items =
            listOf(
                waypoint(0),
                command(1, Mav.CMD_DO_CHANGE_SPEED, p2 = 3f),
                waypoint(2),
            )

        sink.startMission(items, 0, MissionExecutor.ONBOARD)

        // The initial speed prescans the plan and the item then applies to the legs after it, so
        // the plan's own speed is what both legs fly — not the profile default.
        assertTrue(commands.legs[0].contains("speed=3.0"))
        assertTrue(commands.legs[1].contains("speed=3.0"))
        assertEquals(
            listOf(
                "started:0", "reached:0",
                "started:1", "reached:1",
                "started:2", "reached:2",
                "finished:true",
            ),
            progress.events,
        )
    }

    @Test
    fun `payload and ROI items run through the command sink where they sit`() {
        val items =
            listOf(
                command(0, Mav.CMD_IMAGE_START_CAPTURE),
                command(1, Mav.CMD_DO_SET_ROI_LOCATION, lat = 55.5, lon = 12.5, alt = 10.0),
                waypoint(2),
                command(3, Mav.CMD_DO_SET_ROI_NONE),
                command(4, Mav.CMD_VIDEO_START_CAPTURE),
            )

        sink.startMission(items, 0, MissionExecutor.ONBOARD)

        assertEquals(
            listOf("captureImage", "roi:55.5,12.5,10.0", "clearRoi", "videoStart"),
            payload.calls,
        )
    }

    @Test
    fun `a gimbal item aims the set axis and ignores the unset one`() {
        val items =
            listOf(
                command(0, Mav.CMD_DO_GIMBAL_MANAGER_PITCHYAW, p1 = -30f, p2 = Float.NaN),
                waypoint(1),
            )

        sink.startMission(items, 0, MissionExecutor.ONBOARD)

        assertEquals(
            listOf("gimbal:pitch=-30.0,yaw=NaN,pitchIgnored=false,yawIgnored=true"),
            payload.calls,
        )
    }

    @Test
    fun `a refused leg stops the plan before the next one`() {
        commands.refuseLegOrdinal = 2

        sink.startMission(listOf(waypoint(0), waypoint(1), waypoint(2)), 0, MissionExecutor.ONBOARD)

        assertEquals(listOf("started:0", "reached:0", "started:1", "finished:false"), progress.events)
        assertEquals(2, commands.legs.size)
        assertFalse(sink.isRunning)
    }

    @Test
    fun `a manual override during a leg ends the plan`() {
        commands.overrideAfterLegOrdinal = 2

        sink.startMission(listOf(waypoint(0), waypoint(1), waypoint(2)), 0, MissionExecutor.ONBOARD)

        assertEquals(listOf("started:0", "reached:0", "started:1", "finished:false"), progress.events)
        assertEquals(2, commands.legs.size)
    }

    @Test
    fun `a leg that never reports reached times out`() {
        commands.autoReach = false

        sink.startMission(listOf(waypoint(0)), 0, MissionExecutor.ONBOARD)

        assertEquals(listOf("started:0", "finished:false"), progress.events)
        // One poll per MISSION_POLL_MS until MISSION_LEG_TIMEOUT_MS is spent.
        assertEquals(1_500, scheduler.sleeps.size)
    }

    @Test
    fun `stopping the mission cancels the running plan and aborts the controller`() {
        scheduler.inline = false
        commands.autoReach = false
        scheduler.onSleep = {
            if (scheduler.sleepCount == 2) sink.stopMission()
        }

        sink.startMission(listOf(waypoint(0)), 0, MissionExecutor.ONBOARD)
        assertTrue(sink.isRunning)

        scheduler.pump()

        assertEquals(listOf("started:0", "finished:false"), progress.events)
        assertTrue(scheduler.interrupted)
        assertTrue(host.calls.contains("post"))
        assertTrue(commands.calls.contains("abortAll"))
        assertFalse(sink.isRunning)
    }

    @Test
    fun `a second start while running is accepted without restarting the plan`() {
        scheduler.inline = false
        val items = listOf(waypoint(0))

        sink.startMission(items, 0, MissionExecutor.ONBOARD)
        val callsAfterFirst = commands.calls.toList()
        val second = sink.startMission(items, 0, MissionExecutor.ONBOARD)

        assertEquals(MavlinkCommandOutcome.ACCEPTED, second.outcome)
        // QGC's SET_MODE(MISSION) then MISSION_START pair must not stop and restart the plan: the
        // second start adds nothing, not even the cleanup abort the first one posted.
        assertEquals(callsAfterFirst, commands.calls)
        scheduler.pump()
        assertEquals(1, commands.legs.size)
        assertEquals(listOf("started:0", "reached:0", "finished:true"), progress.events)
    }

    @Test
    fun `a takeoff item waits for the aircraft to leave the ground`() {
        host.takeoffClimbing = true
        telemetry.flying = false
        // The first airborne poll fails, so the climb is seen to finish on the next one.
        scheduler.onSleep = {
            telemetry.flying = true
            host.takeoffClimbing = false
        }

        sink.startMission(
            listOf(command(0, Mav.CMD_NAV_TAKEOFF, alt = 12.5), waypoint(1)),
            0,
            MissionExecutor.ONBOARD,
        )

        assertTrue(motion.calls.contains("takeoff:12.5"))
        assertEquals(1, commands.legs.size)
        assertEquals(
            listOf("started:0", "reached:0", "started:1", "reached:1", "finished:true"),
            progress.events,
        )
    }

    @Test
    fun `a takeoff that never gets airborne ends the plan`() {
        host.takeoffClimbing = true
        telemetry.flying = false

        sink.startMission(listOf(command(0, Mav.CMD_NAV_TAKEOFF, alt = 12.5), waypoint(1)), 0, MissionExecutor.ONBOARD)

        assertEquals(listOf("started:0", "finished:false"), progress.events)
        assertTrue(commands.legs.isEmpty())
        assertEquals(60, scheduler.sleeps.size)
    }

    @Test
    fun `a land item ends the plan after the leg before it`() {
        sink.startMission(listOf(waypoint(0), command(1, Mav.CMD_NAV_LAND)), 0, MissionExecutor.ONBOARD)

        assertEquals(listOf("started:0", "reached:0", "started:1", "finished:false"), progress.events)
        assertTrue(motion.calls.contains("land"))
        assertEquals(1, commands.legs.size)
    }

    @Test
    fun `distance-triggered capture trips when the interval is covered`() {
        commands.autoReach = false
        telemetry.location = GeoPosition(55.0, 12.0, 30.0)
        scheduler.onSleep = {
            if (scheduler.sleepCount == 3) sink.stopMission()
            // About 111 m north per fix: comfortably over the 100 m interval set below.
            telemetry.location = GeoPosition(55.0 + 0.001 * scheduler.sleepCount, 12.0, 30.0)
        }

        sink.startMission(
            listOf(command(0, Mav.CMD_DO_SET_CAM_TRIGG_DIST, p1 = 100f), waypoint(1)),
            0,
            MissionExecutor.ONBOARD,
        )

        assertEquals(2, payload.calls.count { it == "captureImage" })
        assertEquals(listOf("started:0", "reached:0", "started:1", "finished:false"), progress.events)
    }

    @Test
    fun `native execution hands the plan to the adapter and relays progress`() {
        val items = listOf(waypoint(0), waypoint(1))

        val result = sink.startMission(items, 0, MissionExecutor.DJI_NATIVE)

        assertEquals(MavlinkCommandOutcome.ACCEPTED, result.outcome)
        assertEquals(items, host.nativeItems)
        assertEquals(listOf("started:0"), progress.events)
        assertTrue(sink.isRunning)

        host.nativeOnProgress?.invoke(1)
        host.nativeOnFinished?.invoke(true)

        assertEquals(listOf("started:0", "started:1", "finished:true"), progress.events)
        assertFalse(sink.isRunning)
    }

    @Test
    fun `a refused native plan reports no mission running`() {
        host.nativeResult =
            CommandResult(
                MavlinkCommandOutcome.DENIED,
                "DJI native missions need at least two waypoints",
            )

        val result = sink.startMission(listOf(waypoint(0)), 0, MissionExecutor.DJI_NATIVE)

        assertEquals(MavlinkCommandOutcome.DENIED, result.outcome)
        assertTrue(progress.events.isEmpty())
        assertFalse(sink.isRunning)
    }

    private fun waypoint(
        seq: Int,
        yaw: Float = Float.NaN,
        holdSeconds: Float = 0f,
        acceptanceRadius: Float = 0f,
        passRadius: Float = 0f,
    ) = MissionItem(
        seq = seq,
        command = Mav.CMD_NAV_WAYPOINT,
        param1 = holdSeconds,
        param2 = acceptanceRadius,
        param3 = passRadius,
        param4 = yaw,
        latitudeDeg = 55.0,
        longitudeDeg = 12.0,
        altitudeM = 30.0,
        autocontinue = true,
    )

    private fun command(
        seq: Int,
        command: Int,
        p1: Float = 0f,
        p2: Float = 0f,
        p3: Float = 0f,
        p4: Float = 0f,
        lat: Double = 0.0,
        lon: Double = 0.0,
        alt: Double = 0.0,
    ) = MissionItem(
        seq = seq,
        command = command,
        param1 = p1,
        param2 = p2,
        param3 = p3,
        param4 = p4,
        latitudeDeg = lat,
        longitudeDeg = lon,
        altitudeM = alt,
        autocontinue = true,
    )

    private class RecordingListener : MissionProgressListener {
        val events = mutableListOf<String>()

        override fun onItemStarted(seq: Int) {
            events += "started:$seq"
        }

        override fun onItemReached(seq: Int) {
            events += "reached:$seq"
        }

        override fun onMissionFinished(completed: Boolean) {
            events += "finished:$completed"
        }
    }

    private class FakeMissionHost : MavlinkMissionHost {
        val calls = mutableListOf<String>()
        var gate: CommandResult? = null
        var takeoffClimbing = false
        var nativeResult: CommandResult = CommandResult(MavlinkCommandOutcome.ACCEPTED)
        var nativeItems: List<MissionItem>? = null
        var nativeOnProgress: ((Int) -> Unit)? = null
        var nativeOnFinished: ((Boolean) -> Unit)? = null

        override fun mavlinkFlightGate(): CommandResult? = gate

        override fun setCameraMode(mode: Int) {
            calls += "cameraMode:$mode"
        }

        override fun defaultCruiseSpeedMps() = 5.0

        override fun postToMain(block: () -> Unit) {
            calls += "post"
            block()
        }

        override fun isTakeoffStillClimbing() = takeoffClimbing

        override fun startNativeMission(
            items: List<MissionItem>,
            onProgress: (Int) -> Unit,
            onFinished: (Boolean) -> Unit,
        ): CommandResult {
            calls += "nativeStart"
            nativeItems = items
            nativeOnProgress = onProgress
            nativeOnFinished = onFinished
            return nativeResult
        }
    }

    private class FakeCommands : MotionCommandPort {
        val calls = mutableListOf<String>()
        val legs = mutableListOf<String>()
        var rejectAutonomous = false
        var override = false
        var refusal: WaypointRefusal? = null
        var autoReach = true

        /** The ordinal (1-based) of the leg whose issue publishes a refusal. */
        var refuseLegOrdinal: Int? = null

        /** The ordinal (1-based) of the leg after which the pilot takes over. */
        var overrideAfterLegOrdinal: Int? = null
        private var legCount = 0
        private var reachedSeq = -1L

        override fun shouldRejectAutonomousCommand(commandName: String) = rejectAutonomous

        override fun takeoff() {
            calls += "takeoff"
        }

        override fun land() {
            calls += "land"
        }

        override fun returnToHome() {
            calls += "rth"
        }

        override fun gotoYaw(yawDeg: Double): Long = 0L

        override fun gotoAltitude(altitudeMeters: Double): Long = 0L

        override fun waypoint(
            latitudeDeg: Double,
            longitudeDeg: Double,
            altitudeMeters: Double,
            yawDeg: Double,
            speedMps: Double,
            noseForward: Boolean,
            arrival: WaypointArrival,
        ): Long {
            legCount++
            if (refuseLegOrdinal == legCount) {
                refusal = WaypointRefusal(legCount.toLong(), WaypointRejection.OBSTACLE_BLOCKED)
            }
            if (overrideAfterLegOrdinal == legCount) override = true
            reachedSeq = if (autoReach) legCount.toLong() else -1L
            legs +=
                "lat=$latitudeDeg,lon=$longitudeDeg,alt=$altitudeMeters,yaw=$yawDeg," +
                "speed=$speedMps,nose=$noseForward,pass=${arrival.passThrough}," +
                "radius=${arrival.acceptanceRadiusM},hold=${arrival.holdSeconds}"
            return legCount.toLong()
        }

        @Suppress("LongParameterList")
        override fun orbit(
            centreLatitude: Double,
            centreLongitude: Double,
            targetAltitude: Double,
            radiusMeters: Double,
            tangentialSpeedMps: Double,
            clockwise: Boolean,
            arcDegrees: Double,
            faceCentre: Boolean,
        ) = Unit

        override fun abortAllMissions() {
            calls += "abortAll"
        }

        override fun endMission() {
            calls += "endMission"
        }

        override fun sendManualStick(stick: ManualStick) = Unit

        override fun enableVirtualStick() = Unit

        override fun disableVirtualStick() = Unit

        override fun deactivateManualOverride() = Unit

        override val isManualOverrideActive get() = override

        override fun lastWaypointRefusal() = refusal

        override fun reachLatch(kind: PendingKind) = ReachLatch(seq = legCount.toLong(), reached = reachedSeq == legCount.toLong())
    }

    private class FakeMotionSink : MavlinkMotionSink {
        val calls = mutableListOf<String>()

        override fun takeoff(altitudeM: Float?): CommandResult {
            calls += "takeoff:$altitudeM"
            return CommandResult(MavlinkCommandOutcome.ACCEPTED)
        }

        override fun land(): CommandResult {
            calls += "land"
            return CommandResult(MavlinkCommandOutcome.ACCEPTED)
        }

        override fun returnToHome(): CommandResult {
            calls += "rth"
            return CommandResult(MavlinkCommandOutcome.ACCEPTED)
        }

        override fun reposition(
            latitudeDeg: Double,
            longitudeDeg: Double,
            altitudeMeters: Double,
            yawDeg: Double,
            groundSpeedMps: Double,
        ): CommandResult = CommandResult(MavlinkCommandOutcome.ACCEPTED)

        override fun setYaw(yawDeg: Double): CommandResult = CommandResult(MavlinkCommandOutcome.ACCEPTED)

        @Suppress("LongParameterList")
        override fun orbit(
            latitudeDeg: Double,
            longitudeDeg: Double,
            altitudeMeters: Double,
            radiusMeters: Double,
            tangentialSpeedMps: Double,
            clockwise: Boolean,
            arcDegrees: Double,
            faceCentre: Boolean,
        ): CommandResult = CommandResult(MavlinkCommandOutcome.ACCEPTED)

        override fun abortToPositionHold(): CommandResult = CommandResult(MavlinkCommandOutcome.ACCEPTED)

        override fun enableOffboard(): CommandResult = CommandResult(MavlinkCommandOutcome.ACCEPTED)

        override fun manualControl(
            roll: Float,
            pitch: Float,
            throttle: Float,
            yaw: Float,
        ): CommandResult = CommandResult(MavlinkCommandOutcome.ACCEPTED)

        override fun setAltitude(altitudeMeters: Double): CommandResult = CommandResult(MavlinkCommandOutcome.ACCEPTED)

        override fun releaseManualOverride(): CommandResult = CommandResult(MavlinkCommandOutcome.ACCEPTED)

        override fun releaseSafetyControl(): CommandResult = CommandResult(MavlinkCommandOutcome.ACCEPTED)

        override fun pollCompletion(pending: PendingCommand): CommandProgress = CommandProgress.RUNNING

        override fun arm(): CommandResult = CommandResult(MavlinkCommandOutcome.ACCEPTED)

        override fun disarm(): CommandResult = CommandResult(MavlinkCommandOutcome.ACCEPTED)
    }

    private class FakeCommandSink : MavlinkCommandSink {
        val calls = mutableListOf<String>()

        override fun setGimbal(rotation: GimbalRotation): CommandResult {
            calls +=
                "gimbal:pitch=${rotation.pitchDeg},yaw=${rotation.yawDeg}," +
                "pitchIgnored=${rotation.pitchIgnored},yawIgnored=${rotation.yawIgnored}"
            return CommandResult(MavlinkCommandOutcome.ACCEPTED)
        }

        override fun setCameraZoom(zoomRatio: Float): CommandResult = CommandResult(MavlinkCommandOutcome.ACCEPTED)

        override fun startVideoRecording(): CommandResult {
            calls += "videoStart"
            return CommandResult(MavlinkCommandOutcome.ACCEPTED)
        }

        override fun stopVideoRecording(): CommandResult {
            calls += "videoStop"
            return CommandResult(MavlinkCommandOutcome.ACCEPTED)
        }

        override fun captureImage(): CommandResult {
            calls += "captureImage"
            return CommandResult(MavlinkCommandOutcome.ACCEPTED)
        }

        override fun setGimbalRelative(
            pitchDeg: Double,
            yawDeg: Double,
        ): CommandResult = CommandResult(MavlinkCommandOutcome.ACCEPTED)

        override fun measureLrf(): CommandResult = CommandResult(MavlinkCommandOutcome.ACCEPTED)

        override fun captureTemperature(): CommandResult = CommandResult(MavlinkCommandOutcome.ACCEPTED)

        override fun captureThermalImage(): CommandResult = CommandResult(MavlinkCommandOutcome.ACCEPTED)

        override fun dropPayload(): CommandResult = CommandResult(MavlinkCommandOutcome.ACCEPTED)

        override fun setAutoSensing(enabled: Boolean): CommandResult = CommandResult(MavlinkCommandOutcome.ACCEPTED)

        override fun setParameter(
            name: String,
            value: Float,
        ): CommandResult = CommandResult(MavlinkCommandOutcome.ACCEPTED)

        override fun setTextParameter(
            name: String,
            value: String,
        ): CommandResult = CommandResult(MavlinkCommandOutcome.ACCEPTED)

        override fun textParameters(): List<Pair<String, String>> = emptyList()

        override fun setRegionOfInterest(
            latitudeDeg: Double,
            longitudeDeg: Double,
            altitudeM: Double,
        ): CommandResult {
            calls += "roi:$latitudeDeg,$longitudeDeg,$altitudeM"
            return CommandResult(MavlinkCommandOutcome.ACCEPTED)
        }

        override fun clearRegionOfInterest(): CommandResult {
            calls += "clearRoi"
            return CommandResult(MavlinkCommandOutcome.ACCEPTED)
        }
    }

    private class FakeTelemetry : AircraftTelemetrySource {
        var flying = true
        var location = GeoPosition(55.0, 12.0, 30.0)

        override fun read(): AircraftReadings = AircraftReadings(location = location, flying = flying)

        override fun readState(): AircraftState =
            AircraftState(
                readings = read(),
                connected = true,
                connectionGeneration = 1,
                observedAtMillis = 0,
            )

        override fun subscribe(listener: AircraftTelemetryListener): AutoCloseable = AutoCloseable { }
    }

    /**
     * The sequencer's thread and clock, under test control.
     *
     * [inline] runs a plan to completion in the caller; with it off, [pump] runs the stored block
     * on demand, which is how a test holds a plan running across two calls. [sleepMs] makes the
     * clock advance, so a timeout is reached by polling rather than by waiting, and [onSleep]
     * is the hook tests use to fire a refusal, a position move or a cancellation on a chosen poll.
     */
    private class FakeScheduler : MissionScheduler {
        var now = 0L
        var inline = true
        var interrupted = false
        var sleepCount = 0
        val sleeps = mutableListOf<Long>()
        var onSleep: (() -> Unit)? = null
        private var pending: (() -> Unit)? = null

        override fun nowMs() = now

        override fun runAsync(
            name: String,
            block: () -> Unit,
        ): MissionTask {
            if (inline) block() else pending = block
            return object : MissionTask {
                override fun interrupt() {
                    interrupted = true
                }
            }
        }

        fun pump() {
            val block = pending ?: return
            pending = null
            block()
        }

        override fun sleepMs(ms: Long) {
            sleeps += ms
            sleepCount++
            now += ms
            onSleep?.invoke()
        }
    }
}
