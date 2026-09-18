package com.lyrebird.rc.controller

import android.util.Log
import com.lyrebird.rc.DroneControlProfiles
import com.lyrebird.rc.mavlink.CommandProgress
import com.lyrebird.rc.mavlink.CommandResult
import com.lyrebird.rc.mavlink.MavlinkCommandOutcome
import com.lyrebird.rc.mavlink.MavlinkMotionSink
import com.lyrebird.rc.mavlink.PendingCommand
import com.lyrebird.rc.mavlink.PendingKind
import com.lyrebird.rc.telemetry.V5AircraftTelemetrySource
import kotlin.math.abs

internal interface V5MavlinkMotionHost {
    val aircraftTelemetry: V5AircraftTelemetrySource
    var armedCommanded: Boolean

    fun isMavlinkOriginTrusted(): Boolean

    fun climbAfterTakeoff(altitudeMeters: Double)

    fun mavlinkFlightGate(): CommandResult?

    fun supersedeMission(reason: String)
}

internal class V5MavlinkMotionSink(
    private val host: V5MavlinkMotionHost,
) {
    companion object {
        private const val REPOSITION_COORD_EPSILON = 1e-7
        private const val TAG = "LyrebirdDefaultLayout"
        private const val MIN_ORBIT_RADIUS_M = 5.0
    }

    val sink: MavlinkMotionSink
        get() = mavlinkMotionSink

    /**
     * Flight-motion commands over MAVLink, behind the safety gate.
     *
     * Three layers, checked in order:
     *   1. lb_mav_0_allow_flight — enabled by default; an explicit settings choice can block it.
     *   2. command authority — MAVLink speaks as the Pilot, so it is refused once the Safety
     *      Computer has seized control over HTTP.
     *   3. the RC manual-override latch — closed-loop commands (reposition, yaw) are refused while
     *      the physical RC pilot has taken over.
     */
    internal val mavlinkMotionSink =
        object : MavlinkMotionSink {
            override fun takeoff(altitudeM: Float?): CommandResult {
                host.mavlinkFlightGate()?.let { return it }
                if (DroneController.shouldRejectAutonomousCommand("takeoff")) {
                    return CommandResult(MavlinkCommandOutcome.DENIED)
                }
                DroneController.startTakeOff()
                if (altitudeM != null) host.climbAfterTakeoff(altitudeM.toDouble())
                return CommandResult(MavlinkCommandOutcome.ACCEPTED)
            }

            override fun land(): CommandResult {
                host.mavlinkFlightGate()?.let { return it }
                if (DroneController.shouldRejectAutonomousCommand("land")) {
                    return CommandResult(MavlinkCommandOutcome.DENIED)
                }
                host.supersedeMission("land")
                DroneController.startLanding()
                return CommandResult(MavlinkCommandOutcome.ACCEPTED)
            }

            override fun returnToHome(): CommandResult {
                host.mavlinkFlightGate()?.let { return it }
                if (DroneController.shouldRejectAutonomousCommand("return to home")) {
                    return CommandResult(MavlinkCommandOutcome.DENIED)
                }
                host.supersedeMission("return to home")
                DroneController.startReturnToHome()
                return CommandResult(MavlinkCommandOutcome.ACCEPTED)
            }

            override fun reposition(
                latitudeDeg: Double,
                longitudeDeg: Double,
                altitudeMeters: Double,
                yawDeg: Double,
                groundSpeedMps: Double,
            ): CommandResult {
                host.mavlinkFlightGate()?.let { return it }
                if (DroneController.shouldRejectAutonomousCommand("reposition")) {
                    return CommandResult(MavlinkCommandOutcome.DENIED)
                }
                host.supersedeMission("reposition")

                // DO_REPOSITION carries three "leave this one alone" sentinels, and QGroundControl
                // sends all three. None of them are values to fly to, and passing them through as if
                // they were is what made a goto fly backwards and an altitude change do nothing.

                // param1 is "ground speed, less than 0 (-1) for default". As a speed it is harmless;
                // as the *ceiling* the waypoint loop clamps to, -1 m/s is a command to retreat.
                val speed =
                    groundSpeedMps
                        .takeIf { it.isFinite() && it > 0.0 }
                        ?: DroneControlProfiles.activeProfile().defaultCruiseSpeedMps

                // param5/param6 NaN mean "hold the current position and change only the altitude",
                // which is how QGC expresses Change Altitude. A COMMAND_INT carries them as scaled
                // int32, where NaN converts to 0 -- a real coordinate in the Gulf of Guinea rather
                // than a marker -- so the zero case has to be caught alongside the non-finite one.
                val holdingPosition =
                    !latitudeDeg.isFinite() ||
                        !longitudeDeg.isFinite() ||
                        (
                            abs(latitudeDeg) < REPOSITION_COORD_EPSILON &&
                                abs(longitudeDeg) < REPOSITION_COORD_EPSILON
                        )
                if (holdingPosition) {
                    // Three of QGroundControl's guided actions are this one command, told apart only
                    // by which parameters are real: Go to location carries a position, Change
                    // altitude carries only an altitude, and Set heading carries only a yaw. So a
                    // command with no position is not automatically an altitude change, and reading
                    // it as one silently discarded the heading the operator had just dialled in.
                    //
                    // Neither is routed to a waypoint at the current position, which would be the
                    // obvious way to express "stay here": a zero-length leg has no bearing, so the
                    // nose-forward controller reads atan2(0, 0) and turns the aircraft north first.
                    return if (!yawDeg.isNaN()) {
                        val seq = DroneController.gotoYaw(yawDeg)
                        CommandResult(
                            MavlinkCommandOutcome.ACCEPTED,
                            pending = PendingCommand(PendingKind.YAW, seq),
                        )
                    } else {
                        val seq =
                            DroneController.gotoAltitude(
                                // A Change altitude always names one. Defended anyway, because an
                                // altitude of NaN reaches the vertical controller as a setpoint and
                                // every comparison against it is false, so the aircraft would hold
                                // whatever throttle it had rather than refuse.
                                altitudeMeters.takeIf { it.isFinite() } ?: host.aircraftTelemetry.getLocation3D().altitude,
                            )
                        CommandResult(
                            MavlinkCommandOutcome.ACCEPTED,
                            pending = PendingCommand(PendingKind.ALTITUDE, seq),
                        )
                    }
                }

                // param4 NaN means "use the vehicle's heading mode", exactly as it does in a mission
                // item. Honouring it here too is what lets a single reposition express nose-forward,
                // which is otherwise only reachable by uploading a one-item plan. The arrival heading
                // is then the heading the aircraft already holds: "do not change yaw" cannot mean
                // "finish by rotating to north", which is what a hardcoded zero asked for.
                // param7 NaN means "keep the altitude you are at", the same "leave this alone" that
                // the other three parameters express. Flown as a setpoint it is not refused: every
                // comparison against NaN is false, so the aircraft never reaches the altitude and
                // never reports arriving.
                val altitude = altitudeMeters.takeIf { it.isFinite() } ?: host.aircraftTelemetry.getLocation3D().altitude
                val seq =
                    if (yawDeg.isNaN()) {
                        DroneController.flyToWaypointNoseForward(
                            latitudeDeg,
                            longitudeDeg,
                            altitude,
                            host.aircraftTelemetry.getHeading(),
                            speed,
                        )
                    } else {
                        DroneController.flyToWaypointHoldHeading(
                            latitudeDeg,
                            longitudeDeg,
                            altitude,
                            yawDeg,
                            speed,
                        )
                    }
                // A refused leg is a refusal, not a pending flight: the controller published the
                // seq exactly so a caller correlating on it can tell the two apart. Answering
                // ACCEPTED here is what made MAVLink and HTTP disagree about the same command —
                // HTTP inspects this same refusal, the reposition path did not — and left a ground
                // station awaiting a leg the aircraft never started flying.
                val refusal = DroneController.lastWaypointRefusal()
                if (refusal?.seq == seq && refusal.reason != DroneController.WaypointRejection.NONE) {
                    return CommandResult(
                        MavlinkCommandOutcome.DENIED,
                        "Waypoint refused: ${refusal.reason}",
                    )
                }
                return CommandResult(
                    MavlinkCommandOutcome.ACCEPTED,
                    pending = PendingCommand(PendingKind.WAYPOINT, seq),
                )
            }

            override fun setYaw(yawDeg: Double): CommandResult {
                host.mavlinkFlightGate()?.let { return it }
                if (DroneController.shouldRejectAutonomousCommand("yaw")) {
                    return CommandResult(MavlinkCommandOutcome.DENIED)
                }
                host.supersedeMission("yaw")
                val seq = DroneController.gotoYaw(yawDeg)
                return CommandResult(
                    MavlinkCommandOutcome.ACCEPTED,
                    pending = PendingCommand(PendingKind.YAW, seq),
                )
            }

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
            ): CommandResult {
                host.mavlinkFlightGate()?.let { return it }
                if (DroneController.shouldRejectAutonomousCommand("orbit")) {
                    return CommandResult(MavlinkCommandOutcome.DENIED)
                }
                if (!latitudeDeg.isFinite() || !longitudeDeg.isFinite()) {
                    return CommandResult(MavlinkCommandOutcome.DENIED, "Orbit needs a real centre")
                }
                // A radius of zero is a rotation in place dressed as an orbit, and the radial term
                // would divide the aircraft's own position noise by nothing to correct it.
                if (radiusMeters < MIN_ORBIT_RADIUS_M) {
                    return CommandResult(
                        MavlinkCommandOutcome.DENIED,
                        "Orbit radius must be at least $MIN_ORBIT_RADIUS_M m",
                    )
                }
                host.supersedeMission("orbit")
                DroneController.orbit(
                    centreLatitude = latitudeDeg,
                    centreLongitude = longitudeDeg,
                    // As everywhere else on this surface, an unset altitude is the one being held.
                    targetAltitude =
                        altitudeMeters.takeIf { it.isFinite() }
                            ?: host.aircraftTelemetry.getLocation3D().altitude,
                    radiusMeters = radiusMeters,
                    tangentialSpeedMps = tangentialSpeedMps,
                    clockwise = clockwise,
                    arcDegrees = arcDegrees,
                    faceCentre = faceCentre,
                )
                // Acknowledged immediately rather than held pending until the lap completes: a full
                // orbit takes minutes, and QGroundControl's orbit tool re-issues DO_ORBIT as its
                // parameters change — a pending ack makes it refuse with "Waiting on previous
                // response to same command" for the whole lap. The orbit still finishes on its own;
                // completion is reported on the telemetry stream, not by blocking the next command.
                return CommandResult(MavlinkCommandOutcome.ACCEPTED)
            }

            override fun abortToPositionHold(): CommandResult {
                host.mavlinkFlightGate()?.let { return it }
                if (DroneController.shouldRejectAutonomousCommand("abort")) {
                    return CommandResult(MavlinkCommandOutcome.DENIED)
                }
                host.supersedeMission("abort")
                // The union of the three HTTP aborts: stop the PID loops, neutralise the sticks and
                // leave virtual stick, and end any DJI wayline. Each is safe when nothing is running.
                DroneController.abortAllMissions()
                DroneController.setStick(0f, 0f, 0f, 0f)
                DroneController.disableVirtualStick()
                runCatching { DroneController.endMission() }
                return CommandResult(MavlinkCommandOutcome.ACCEPTED)
            }

            override fun enableOffboard(): CommandResult {
                host.mavlinkFlightGate()?.let { return it }
                if (DroneController.shouldRejectAutonomousCommand("enableVirtualStick")) {
                    return CommandResult(MavlinkCommandOutcome.DENIED)
                }
                DroneController.enableVirtualStick()
                return CommandResult(MavlinkCommandOutcome.ACCEPTED)
            }

            override fun manualControl(
                roll: Float,
                pitch: Float,
                throttle: Float,
                yaw: Float,
            ): CommandResult {
                host.mavlinkFlightGate()?.let { return it }
                // Refused while the pilot has the sticks, exactly as /send/stick is. The latch
                // already drops virtual stick, so these would most likely be ignored anyway — but
                // "most likely ignored" is not the guarantee to rely on when the pilot has taken
                // over, and the two surfaces disagreeing about it is its own bug.
                if (DroneController.shouldRejectAutonomousCommand("stick")) {
                    return CommandResult(MavlinkCommandOutcome.DENIED)
                }
                // DJI's sticks: left is yaw/throttle, right is roll/pitch. MAVLink's axes are named
                // for what they do, so the mapping is by meaning rather than by position.
                DroneController.setStick(
                    leftX = yaw,
                    leftY = throttle,
                    rightX = roll,
                    rightY = pitch,
                )
                return CommandResult(MavlinkCommandOutcome.ACCEPTED)
            }

            override fun setAltitude(altitudeMeters: Double): CommandResult {
                host.mavlinkFlightGate()?.let { return it }
                if (DroneController.shouldRejectAutonomousCommand("altitude")) {
                    return CommandResult(MavlinkCommandOutcome.DENIED)
                }
                host.supersedeMission("altitude change")
                val seq = DroneController.gotoAltitude(altitudeMeters)
                return CommandResult(
                    MavlinkCommandOutcome.ACCEPTED,
                    pending = PendingCommand(PendingKind.ALTITUDE, seq),
                )
            }

            override fun releaseManualOverride(): CommandResult {
                // Deliberately not behind the flight gate: this grants authority rather than using
                // it, and the commands it re-enables are each gated in their own right.
                DroneController.deactivateManualOverride()
                return CommandResult(MavlinkCommandOutcome.ACCEPTED)
            }

            override fun releaseSafetyControl(): CommandResult {
                // Deliberately not behind the flight gate: this is the operation that returns
                // authority to the Pilot, so it must be reachable precisely while the Safety
                // Computer holds control (the gate would refuse everything once SAFETY seized it).
                // Only a frame signed with the configured key may release — an unsigned frame is
                // the Pilot, and the Pilot cannot release safety. HTTP's /releaseSafetyControl has
                // the same rule, enforced with its X-Safety-Token header instead.
                val source =
                    if (host.isMavlinkOriginTrusted()) {
                        ControlAuthority.Source.SAFETY
                    } else {
                        ControlAuthority.Source.PILOT
                    }
                return if (ControlAuthority.releaseSafetyControl(source)) {
                    CommandResult(MavlinkCommandOutcome.ACCEPTED)
                } else {
                    CommandResult(
                        MavlinkCommandOutcome.DENIED,
                        "Only the Safety Computer can release safety control",
                    )
                }
            }

            /**
             * Whether the movement with this seq has arrived.
             *
             * The seq comparison is what makes the answer trustworthy: the latch is a single shared
             * flag, so without it a leftover `true` from the previous movement reads as this one
             * arriving instantly. A manual override is reported as a failure rather than as a wait,
             * because the command is not going to complete once the pilot has the sticks.
             */
            override fun pollCompletion(pending: PendingCommand): CommandProgress {
                if (DroneController.isManualOverrideActive) return CommandProgress.ABANDONED
                // A refused waypoint is a refusal the moment it is detected, not a command that
                // runs until somebody gives up waiting: the leg was never issued to the airframe,
                // so no reach latch will ever close for it.
                if (pending.kind == PendingKind.WAYPOINT) {
                    val refusal = DroneController.lastWaypointRefusal()
                    if (refusal?.seq == pending.seq &&
                        refusal.reason != DroneController.WaypointRejection.NONE
                    ) {
                        return CommandProgress.ABANDONED
                    }
                }
                val (currentSeq, reached) =
                    when (pending.kind) {
                        PendingKind.WAYPOINT ->
                            DroneController.getWaypointSeq() to DroneController.isWaypointReached()
                        PendingKind.YAW ->
                            DroneController.getYawSeq() to DroneController.isYawReached()
                        PendingKind.ALTITUDE ->
                            DroneController.getAltitudeSeq() to DroneController.isAltitudeReached()
                        PendingKind.ORBIT ->
                            DroneController.getOrbitSeq() to DroneController.isOrbitComplete()
                    }
                return when {
                    // A newer command took over. Ordinary, not a failure: this is what re-issuing a
                    // goto looks like from the perspective of the one it replaced.
                    currentSeq > pending.seq -> CommandProgress.SUPERSEDED
                    currentSeq == pending.seq && reached -> CommandProgress.ARRIVED
                    else -> CommandProgress.RUNNING
                }
            }

            override fun arm(): CommandResult {
                // DJI has no arming: motors spin up when the takeoff command actually runs. QGC's
                // takeoff sequence arms right after NAV_TAKEOFF is accepted, so this is a gated no-op
                // that keeps the sequence moving rather than an honest refusal that aborts it. The
                // heartbeat reports armed from [host.armedCommanded] so QGC's arm wait sees a result.
                host.mavlinkFlightGate()?.let { return it }
                if (DroneController.shouldRejectAutonomousCommand("arm")) {
                    return CommandResult(MavlinkCommandOutcome.DENIED)
                }
                host.armedCommanded = true
                Log.i(TAG, "Vehicle armed (commanded; DJI has no arming state)")
                return CommandResult(MavlinkCommandOutcome.ACCEPTED)
            }

            override fun disarm(): CommandResult {
                host.mavlinkFlightGate()?.let { return it }
                if (DroneController.shouldRejectAutonomousCommand("disarm")) {
                    return CommandResult(MavlinkCommandOutcome.DENIED)
                }
                host.armedCommanded = false
                Log.i(TAG, "Vehicle disarmed (commanded)")
                return CommandResult(MavlinkCommandOutcome.ACCEPTED)
            }
        }
}
