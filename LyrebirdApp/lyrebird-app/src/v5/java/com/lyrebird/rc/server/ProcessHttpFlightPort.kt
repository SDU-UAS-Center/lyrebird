package com.lyrebird.rc.server

import com.lyrebird.rc.LyrebirdFlightPort
import com.lyrebird.rc.StickCommand
import com.lyrebird.rc.controller.DroneController
import com.lyrebird.rc.mavlink.CommandResult
import com.lyrebird.rc.mavlink.MavlinkCommandOutcome
import com.lyrebird.rc.mavlink.PendingCommand
import com.lyrebird.rc.mavlink.PendingKind
import com.lyrebird.rc.mavlink.WaypointRejection

/**
 * The HTTP command surface's flight motion, process-scoped.
 *
 * This is the same set of commands the MAVLink path flies through, offered to the ground
 * station's HTTP client: takeoff, landing, RTH, sticks, waypoints, trajectories and the native
 * mission stop. It used to be an anonymous object inside the Flight Deck screen, which meant a
 * ground station could configure an RC whose screen was gone but could not fly it — every
 * /send/ flight route answered "runtime detached" the moment the screen went away, while the
 * MAVLink endpoint on the same device happily accepted the same commands.
 *
 * The commands themselves are unchanged, because they are all [DroneController] calls that were
 * never screen-bound: the refusals (`shouldRejectAutonomousCommand`, waypoint refusals, the
 * safety latch) are the aircraft's own answers, and they are evaluated here exactly as they were
 * in the screen. Nothing about who may fly changed with the move either — the HTTP handler still
 * classifies every /send/ request through [com.lyrebird.rc.controller.ControlAuthority] before it
 * reaches this port, and the RC pilot's override still refuses autonomous commands regardless of
 * where the request came from.
 *
 * DJI types are touched inside method bodies only: this object has no state, so it can be loaded
 * in host JVM tests.
 */
internal object ProcessHttpFlightPort : LyrebirdFlightPort {
    /**
     * Refuse an immediate flight action the aircraft cannot receive, and say so.
     *
     * DJI accepts the call with nothing connected and reports the failure later, after the
     * ground station already has its "command sent" answer. The flight-controller link is the
     * same signal the RC and flight-limit writes refuse on: false covers both "aircraft off" and
     * DJI's low-power standby, where the product link stays up but nothing can fly.
     */
    private fun linkDownRefusal(action: String): CommandResult? =
        if (DroneController.isFlightControllerConnected()) {
            null
        } else {
            CommandResult(
                MavlinkCommandOutcome.FAILED,
                detail = "Aircraft is not connected: $action not sent",
            )
        }

    override fun takeoff(): CommandResult {
        linkDownRefusal("takeoff")?.let { return it }
        DroneController.startTakeOff()
        return CommandResult(MavlinkCommandOutcome.ACCEPTED)
    }

    override fun land(): CommandResult {
        linkDownRefusal("landing")?.let { return it }
        DroneController.startLanding()
        return CommandResult(MavlinkCommandOutcome.ACCEPTED)
    }

    override fun returnToHome(): CommandResult {
        linkDownRefusal("return to home")?.let { return it }
        DroneController.startReturnToHome()
        return CommandResult(MavlinkCommandOutcome.ACCEPTED)
    }

    override fun stick(command: StickCommand): CommandResult {
        if (DroneController.shouldRejectAutonomousCommand("stick")) {
            return CommandResult(MavlinkCommandOutcome.DENIED)
        }
        DroneController.setStick(command.leftX, command.leftY, command.rightX, command.rightY)
        return CommandResult(MavlinkCommandOutcome.ACCEPTED)
    }

    override fun gotoYaw(yawDeg: Double): CommandResult {
        if (DroneController.shouldRejectAutonomousCommand("gotoYaw")) {
            return CommandResult(MavlinkCommandOutcome.DENIED)
        }
        val seq = DroneController.gotoYaw(yawDeg)
        return CommandResult(
            MavlinkCommandOutcome.ACCEPTED,
            pending = PendingCommand(PendingKind.YAW, seq),
        )
    }

    override fun gotoAltitude(altitudeM: Double): CommandResult {
        if (DroneController.shouldRejectAutonomousCommand("gotoAltitude")) {
            return CommandResult(MavlinkCommandOutcome.DENIED)
        }
        val seq = DroneController.gotoAltitude(altitudeM)
        return CommandResult(
            MavlinkCommandOutcome.ACCEPTED,
            pending = PendingCommand(PendingKind.ALTITUDE, seq),
        )
    }

    override fun abortMission(): CommandResult {
        DroneController.setStick(0.0f, 0.0f, 0.0f, 0.0f)
        DroneController.disableVirtualStick()
        return CommandResult(MavlinkCommandOutcome.ACCEPTED)
    }

    override fun abortAll(): CommandResult {
        DroneController.abortAllMissions()
        return CommandResult(MavlinkCommandOutcome.ACCEPTED)
    }

    override fun enableVirtualStick(): CommandResult {
        if (DroneController.shouldRejectAutonomousCommand("enableVirtualStick")) {
            return CommandResult(MavlinkCommandOutcome.DENIED)
        }
        DroneController.enableVirtualStick()
        return CommandResult(MavlinkCommandOutcome.ACCEPTED)
    }

    override fun waypoint(
        latitudeDeg: Double,
        longitudeDeg: Double,
        altitudeM: Double,
        yawDeg: Double,
        maxSpeedMps: Double,
        noseForward: Boolean,
    ): CommandResult {
        val commandName = if (noseForward) "gotoWaypointNoseForward" else "gotoWaypointHoldHeading"
        if (DroneController.shouldRejectAutonomousCommand(commandName)) {
            return CommandResult(MavlinkCommandOutcome.DENIED)
        }
        val seq =
            if (noseForward) {
                DroneController.flyToWaypointNoseForward(
                    latitudeDeg,
                    longitudeDeg,
                    altitudeM,
                    yawDeg,
                    maxSpeedMps,
                )
            } else {
                DroneController.flyToWaypointHoldHeading(
                    latitudeDeg,
                    longitudeDeg,
                    altitudeM,
                    yawDeg,
                    maxSpeedMps,
                )
            }
        val refusal = DroneController.lastWaypointRefusal()
        return if (refusal?.seq == seq && refusal.reason != WaypointRejection.NONE) {
            CommandResult(
                MavlinkCommandOutcome.DENIED,
                detail = refusal.reason.name,
                pending = PendingCommand(PendingKind.WAYPOINT, seq),
            )
        } else {
            CommandResult(
                MavlinkCommandOutcome.ACCEPTED,
                pending = PendingCommand(PendingKind.WAYPOINT, seq),
            )
        }
    }

    override fun nativeTrajectory(
        waypoints: List<Triple<Double, Double, Double>>,
        speedMps: Double,
    ): CommandResult {
        if (DroneController.shouldRejectAutonomousCommand("navigateTrajectoryDJINative")) {
            return CommandResult(MavlinkCommandOutcome.DENIED)
        }
        DroneController.navigateTrajectoryNative(waypoints, speedMps)
        return CommandResult(MavlinkCommandOutcome.ACCEPTED)
    }

    override fun abortNativeMission(): CommandResult {
        DroneController.endMission()
        return CommandResult(MavlinkCommandOutcome.ACCEPTED)
    }

    // The aircraft's own settings (firmware limits, RC mode and pairing) are NOT here: they live
    // in ProcessAircraftSettings so a ground station can read and write them with no screen
    // attached. See LyrebirdAircraftSettingsPort.

    override fun deactivateManualOverride(): CommandResult {
        DroneController.deactivateManualOverride()
        return CommandResult(MavlinkCommandOutcome.ACCEPTED)
    }

    override fun isManualOverrideActive(): Boolean = DroneController.isManualOverrideActive
}
