package com.lyrebird.rc.controller

import com.lyrebird.rc.mavlink.CommandProgress
import com.lyrebird.rc.mavlink.CommandResult
import com.lyrebird.rc.mavlink.MavlinkCommandOutcome
import com.lyrebird.rc.mavlink.MotionCommandPort
import com.lyrebird.rc.mavlink.PendingCommand
import com.lyrebird.rc.mavlink.PendingKind
import com.lyrebird.rc.mavlink.ReachLatch
import com.lyrebird.rc.mavlink.WaypointArrival
import com.lyrebird.rc.mavlink.WaypointRefusal
import com.lyrebird.rc.mavlink.WaypointRejection
import com.lyrebird.rc.platform.ManualStick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shared motion policy, without an SDK: gate ordering, the reposition sentinels, refusal
 * parity and accepted-versus-completed accounting. These are the rules that used to be reachable
 * only through the V5 controller statics, which is why they had no tests at this level.
 */
class MavlinkMotionPolicyTest {
    private val host = FakeMotionHost()
    private val commands = FakeCommands()
    private val policy = MavlinkMotionPolicy(host, commands)
    private val sink = policy.sink

    @Test
    fun `the flight gate short-circuits before any command runs`() {
        host.gate = CommandResult(MavlinkCommandOutcome.DENIED, "MAVLink flight not allowed")

        val result = sink.takeoff(10f)

        assertEquals(MavlinkCommandOutcome.DENIED, result.outcome)
        assertTrue(commands.calls.isEmpty())
    }

    @Test
    fun `takeoff climbs to the requested altitude and without one does not climb`() {
        sink.takeoff(12.5f)
        sink.takeoff(null)

        assertEquals(listOf("takeoff", "takeoff"), commands.calls)
        assertEquals(listOf("climb:12.5"), host.calls)
    }

    @Test
    fun `the manual-override latch refuses autonomous commands with DENIED`() {
        commands.rejectAutonomous = true

        assertEquals(MavlinkCommandOutcome.DENIED, sink.land().outcome)
        assertEquals(MavlinkCommandOutcome.DENIED, sink.arm().outcome)
        assertTrue(commands.calls.isEmpty())
    }

    @Test
    fun `immediate flight actions fail honestly while the aircraft link is down`() {
        commands.canDispatch = false

        val takeoff = sink.takeoff(10f)
        val land = sink.land()
        val rth = sink.returnToHome()

        assertEquals(MavlinkCommandOutcome.FAILED, takeoff.outcome)
        assertEquals("Aircraft is not connected: takeoff not sent", takeoff.detail)
        assertEquals("Aircraft is not connected: landing not sent", land.detail)
        assertEquals("Aircraft is not connected: return to home not sent", rth.detail)
        assertTrue(commands.calls.isEmpty())
        assertTrue(host.calls.isEmpty())
    }

    @Test
    fun `the pilot override is answered before the link is consulted`() {
        commands.rejectAutonomous = true
        commands.canDispatch = false

        assertEquals(MavlinkCommandOutcome.DENIED, sink.takeoff(10f).outcome)
        assertTrue(commands.calls.isEmpty())
    }

    @Test
    fun `a reposition with only an altitude changes altitude at the held height`() {
        host.altitude = 88.0

        val result = sink.reposition(Double.NaN, Double.NaN, Double.NaN, Double.NaN, -1.0)

        assertEquals(MavlinkCommandOutcome.ACCEPTED, result.outcome)
        assertEquals(PendingKind.ALTITUDE, result.pending?.kind)
        assertEquals(listOf("gotoAltitude:88.0"), commands.calls)
    }

    @Test
    fun `a reposition with only a yaw asks for that heading`() {
        val result = sink.reposition(Double.NaN, Double.NaN, Double.NaN, 42.0, -1.0)

        assertEquals(PendingKind.YAW, result.pending?.kind)
        assertEquals(listOf("gotoYaw:42.0"), commands.calls)
    }

    @Test
    fun `a reposition with a position flies nose-forward for an unset yaw, holding the current heading`() {
        host.heading = 123.0

        val result = sink.reposition(55.1, 12.2, Double.NaN, Double.NaN, -1.0)

        assertEquals(PendingKind.WAYPOINT, result.pending?.kind)
        // The unset altitude is the held altitude (88 is the fake default), the unset yaw is the
        // current heading, and the -1 speed is the profile's default cruise speed, not a retreat.
        assertEquals(
            listOf("waypoint:lat=55.1,lon=12.2,alt=88.0,yaw=123.0,speed=5.0,nose=true"),
            commands.calls,
        )
    }

    @Test
    fun `a reposition with a position and a yaw holds that heading`() {
        sink.reposition(55.1, 12.2, 30.0, 180.0, 3.5)

        assertEquals(
            listOf("waypoint:lat=55.1,lon=12.2,alt=30.0,yaw=180.0,speed=3.5,nose=false"),
            commands.calls,
        )
    }

    @Test
    fun `a refused waypoint is DENIED rather than reported pending`() {
        commands.refusal = WaypointRefusal(seq = 7, reason = WaypointRejection.OBSTACLE_BLOCKED)

        val result = sink.reposition(55.1, 12.2, 30.0, 180.0, 3.5)

        assertEquals(MavlinkCommandOutcome.DENIED, result.outcome)
        assertTrue(result.detail!!.contains("OBSTACLE_BLOCKED"))
    }

    @Test
    fun `poll reports superseded, arrived and running from the reach latch`() {
        val pending = PendingCommand(PendingKind.ALTITUDE, seq = 4)

        commands.latch = ReachLatch(seq = 5, reached = false)
        assertEquals(CommandProgress.SUPERSEDED, sink.pollCompletion(pending))

        commands.latch = ReachLatch(seq = 4, reached = true)
        assertEquals(CommandProgress.ARRIVED, sink.pollCompletion(pending))

        commands.latch = ReachLatch(seq = 4, reached = false)
        assertEquals(CommandProgress.RUNNING, sink.pollCompletion(pending))
    }

    @Test
    fun `a refused waypoint and a manual override both abandon the poll`() {
        val pending = PendingCommand(PendingKind.WAYPOINT, seq = 9)

        commands.refusal = WaypointRefusal(seq = 9, reason = WaypointRejection.OBSTACLE_BLOCKED)
        assertEquals(CommandProgress.ABANDONED, sink.pollCompletion(pending))

        commands.refusal = WaypointRefusal(seq = 8, reason = WaypointRejection.OBSTACLE_BLOCKED)
        assertEquals(CommandProgress.RUNNING, sink.pollCompletion(pending))

        commands.override = true
        assertEquals(CommandProgress.ABANDONED, sink.pollCompletion(pending))
    }

    @Test
    fun `abort neutralizes the sticks and ends the mission`() {
        assertEquals(MavlinkCommandOutcome.ACCEPTED, sink.abortToPositionHold().outcome)

        assertEquals(listOf("abortAll", "stick:0.0,0.0,0.0,0.0", "disableVs", "endMission"), commands.calls)
    }

    @Test
    fun `arm and disarm set the commanded latch the heartbeat reports`() {
        sink.arm()
        assertTrue(host.armedCommanded)

        sink.disarm()
        assertTrue(!host.armedCommanded)
    }

    @Test
    fun `orbit refuses a centre-less command and a radius that is a rotation in place`() {
        assertEquals(
            MavlinkCommandOutcome.DENIED,
            sink.orbit(latitudeDeg = Double.NaN, longitudeDeg = 12.0, altitudeMeters = 30.0, radiusMeters = 20.0, tangentialSpeedMps = 5.0, clockwise = true, arcDegrees = 360.0, faceCentre = true).outcome,
        )
        assertEquals(
            MavlinkCommandOutcome.DENIED,
            sink.orbit(latitudeDeg = 55.0, longitudeDeg = 12.0, altitudeMeters = 30.0, radiusMeters = 1.0, tangentialSpeedMps = 5.0, clockwise = true, arcDegrees = 360.0, faceCentre = true).outcome,
        )
        assertTrue(commands.calls.isEmpty())
    }

    @Test
    fun `orbit with an unset altitude orbits at the held height`() {
        sink.orbit(latitudeDeg = 55.0, longitudeDeg = 12.0, altitudeMeters = Double.NaN, radiusMeters = 20.0, tangentialSpeedMps = 5.0, clockwise = true, arcDegrees = 360.0, faceCentre = true)

        assertEquals(listOf("orbit:alt=88.0"), commands.calls)
    }

    private class FakeMotionHost(
        var gate: CommandResult? = null,
    ) : MavlinkMotionHost {
        val calls = mutableListOf<String>()
        var altitude = 88.0
        var heading = 10.0
        override var armedCommanded = false

        override fun isMavlinkOriginTrusted() = false

        override fun climbAfterTakeoff(altitudeMeters: Double) {
            calls += "climb:$altitudeMeters"
        }

        override fun mavlinkFlightGate(): CommandResult? = gate

        override fun supersedeMission(reason: String) {
            calls += "supersede:$reason"
        }

        override fun currentAltitudeM() = altitude

        override fun currentHeadingDeg() = heading

        override fun defaultCruiseSpeedMps() = 5.0
    }

    private class FakeCommands : MotionCommandPort {
        val calls = mutableListOf<String>()
        var rejectAutonomous = false
        var override = false
        var canDispatch = true
        var refusal: WaypointRefusal? = null
        var latch = ReachLatch(seq = 0, reached = false)
        var nextSeq = 7L

        override fun shouldRejectAutonomousCommand(commandName: String) = rejectAutonomous

        override fun canDispatchFlightCommands() = canDispatch

        override fun takeoff() {
            calls += "takeoff"
        }

        override fun land() {
            calls += "land"
        }

        override fun returnToHome() {
            calls += "rth"
        }

        override fun gotoYaw(yawDeg: Double): Long {
            calls += "gotoYaw:$yawDeg"
            return nextSeq
        }

        override fun gotoAltitude(altitudeMeters: Double): Long {
            calls += "gotoAltitude:$altitudeMeters"
            return nextSeq
        }

        override fun waypoint(
            latitudeDeg: Double,
            longitudeDeg: Double,
            altitudeMeters: Double,
            yawDeg: Double,
            speedMps: Double,
            noseForward: Boolean,
            arrival: WaypointArrival,
        ): Long {
            calls += "waypoint:lat=$latitudeDeg,lon=$longitudeDeg,alt=$altitudeMeters,yaw=$yawDeg,speed=$speedMps,nose=$noseForward"
            return nextSeq
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
        ) {
            calls += "orbit:alt=$targetAltitude"
        }

        override fun abortAllMissions() {
            calls += "abortAll"
        }

        override fun endMission() {
            calls += "endMission"
        }

        override fun sendManualStick(stick: ManualStick) {
            calls += "stick:${stick.leftX},${stick.leftY},${stick.rightX},${stick.rightY}"
        }

        override fun enableVirtualStick() {
            calls += "enableVs"
        }

        override fun disableVirtualStick() {
            calls += "disableVs"
        }

        override fun deactivateManualOverride() {
            calls += "releaseOverride"
            override = false
        }

        override val isManualOverrideActive get() = override

        override fun lastWaypointRefusal() = refusal

        override fun reachLatch(kind: PendingKind) = latch
    }
}
