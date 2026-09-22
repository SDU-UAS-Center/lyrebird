package com.lyrebird.rc.mavlink

import com.lyrebird.rc.platform.ManualStick

/**
 * The flight-command surface the shared MAVLink motion policy drives.
 *
 * The policy (authority gates, refusal handling, pending/arrival accounting, the DJI stick-axis
 * mapping) is the same for every SDK generation; only this port differs. It is deliberately the
 * seq-shaped, latch-shaped surface the existing controller already publishes — moving to it must
 * not change what a command returns, because the ground station correlates on those seqs.
 */
interface MotionCommandPort {
    /** True while the physical RC pilot's manual-override latch blocks autonomous commands. */
    fun shouldRejectAutonomousCommand(commandName: String = ""): Boolean

    fun takeoff()

    fun land()

    fun returnToHome()

    /**
     * Whether the aircraft link can carry a flight command right now.
     *
     * DJI answers a takeoff/land/RTH call whether or not anything is listening, and its failure
     * callback lands after the ground station's reply has gone out. The three immediate flight
     * actions ask this first and answer `FAILED` while the aircraft is off or asleep in DJI's
     * low-power standby, instead of reporting a dispatch that could not happen. Adapters that
     * cannot tell return true.
     */
    fun canDispatchFlightCommands(): Boolean

    /** Rotate to [yawDeg]; returns the seq published as `yawSeq`. */
    fun gotoYaw(yawDeg: Double): Long

    /** Climb or descend to [altitudeMeters]; returns the seq published as `altitudeSeq`. */
    fun gotoAltitude(altitudeMeters: Double): Long

    /**
     * Fly to the waypoint; returns the seq the reach latch and any refusal are published under.
     * [noseForward] selects the nose-follows-path controller over the hold-heading one, and
     * [arrival] carries the plan's arrival criteria — a lone goto passes [WaypointArrival.DEFAULT].
     */
    fun waypoint(
        latitudeDeg: Double,
        longitudeDeg: Double,
        altitudeMeters: Double,
        yawDeg: Double,
        speedMps: Double,
        noseForward: Boolean,
        arrival: WaypointArrival = WaypointArrival.DEFAULT,
    ): Long

    @Suppress("LongParameterList")
    fun orbit(
        centreLatitude: Double,
        centreLongitude: Double,
        targetAltitude: Double,
        radiusMeters: Double,
        tangentialSpeedMps: Double,
        clockwise: Boolean,
        arcDegrees: Double,
        faceCentre: Boolean,
    )

    /** The union abort: stops loops, neutralizes the sticks and ends a native mission. */
    fun abortAllMissions()

    /** Ends the app's active command without the full abort sequence. */
    fun endMission()

    fun sendManualStick(stick: ManualStick)

    fun enableVirtualStick()

    fun disableVirtualStick()

    fun deactivateManualOverride()

    val isManualOverrideActive: Boolean

    /** The most recent pre-flight waypoint refusal, or null when the last command flew. */
    fun lastWaypointRefusal(): WaypointRefusal?

    /** The seq and reach latch of the given movement kind. */
    fun reachLatch(kind: PendingKind): ReachLatch
}

/** The current movement seq and whether it has arrived. */
data class ReachLatch(
    val seq: Long,
    val reached: Boolean,
)
