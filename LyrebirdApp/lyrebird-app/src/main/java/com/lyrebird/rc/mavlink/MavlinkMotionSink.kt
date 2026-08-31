package com.lyrebird.rc.mavlink

/**
 * The flight-motion commands the MAVLink endpoint may execute.
 *
 * This is deliberately a separate surface from [MavlinkCommandSink]: the payload sink promises
 * "nothing here can move the aircraft", and keeping that true is what made the payload slice
 * reviewable without a flight-safety argument. Motion lives here, behind its own gate, so that
 * reviewing or disabling flight motion never touches the payload path.
 *
 * The gate is implemented by the host activity (where the prefs, authority latch and controllers
 * live) and must hold before any motion executes:
 *   1. the `lb_mav_0_allow_flight` preference is true (ships enabled);
 *   2. command authority is held (the Safety Computer has not seized control);
 *   3. the RC manual-override latch is clear, for the closed-loop commands that fight the pilot.
 */
internal interface MavlinkMotionSink {

    /** Begin a takeoff. */
    /**
     * Take off, and climb to [altitudeM] when one was asked for.
     *
     * DJI exposes no takeoff-altitude concept — `startTakeOff()` climbs to the aircraft's own
     * default and takes no argument — so an altitude is honoured by climbing after the takeoff
     * completes, not as part of it. That is a close but not identical reading of
     * `MAV_CMD_NAV_TAKEOFF`, and the difference is visible: the aircraft levels off at DJI's
     * default first, then continues.
     *
     * @param altitudeM metres above the take-off point, or null to leave the aircraft at DJI's
     *   default height. QGroundControl sends this in param7.
     */
    fun takeoff(altitudeM: Float?): CommandResult

    /** Begin an auto-land at the current position. */
    fun land(): CommandResult

    /** Begin return-to-launch. */
    fun returnToHome(): CommandResult

    /** Fly to a position, holding the given heading on arrival. */
    /**
     * Fly to one point.
     *
     * [yawDeg] follows the same convention as a mission item's param4: NaN asks for the vehicle's
     * own heading mode (nose along the path), a value asks for that heading to be held for the
     * whole leg. Keeping the two identical means a ground station does not have to know whether
     * its goto became a reposition or a one-item plan.
     */
    fun reposition(
        latitudeDeg: Double,
        longitudeDeg: Double,
        altitudeMeters: Double,
        yawDeg: Double,
        groundSpeedMps: Double
    ): CommandResult

    /** Rotate in place to an absolute yaw, in degrees. */
    fun setYaw(yawDeg: Double): CommandResult

    /**
     * Fly a circle around a point.
     *
     * @param clockwise seen from above. MAVLink carries the direction as the sign of the radius,
     *   which is unpacked before it reaches here so that a negative radius cannot be flown as a
     *   negative distance.
     * @param arcDegrees how far around to go; zero means keep going until something supersedes
     *   it, which is what a ground station means by an orbit with no limit.
     * @param faceCentre keep the nose pointed at the centre rather than holding the current
     *   heading — MAV_ORBIT_YAW_BEHAVIOUR, reduced to the two this airframe can tell apart.
     */
    @Suppress("LongParameterList")
    fun orbit(
        latitudeDeg: Double,
        longitudeDeg: Double,
        altitudeMeters: Double,
        radiusMeters: Double,
        tangentialSpeedMps: Double,
        clockwise: Boolean,
        arcDegrees: Double,
        faceCentre: Boolean
    ): CommandResult

    /**
     * An arming request. DJI has no arm/disarm: motors spin up when a takeoff starts and stop
     * after touchdown. The request is acknowledged as a no-op (still behind the gate) so a ground
     * station's takeoff sequence does not abort on a refused arm.
     */
    /**
     * Stop everything and hold position.
     *
     * The HTTP surface has three aborts with different scopes — the PID mission, DJI's wayline
     * engine, and everything. MAVLink expresses an abort as a mode change to position hold, which
     * has one meaning, so all three arrive here and this does the union of the three. Widening an
     * abort is the safe direction to be wrong in; narrowing it is not.
     */
    fun abortToPositionHold(): CommandResult

    /** Enter the mode that accepts stick input, DJI's virtual stick. */
    fun enableOffboard(): CommandResult

    /** Stick input, each axis in -1..1. Requires offboard. */
    fun manualControl(roll: Float, pitch: Float, throttle: Float, yaw: Float): CommandResult

    /** Climb or descend to an altitude above home, holding position. */
    fun setAltitude(altitudeMeters: Double): CommandResult

    /** Clear the manual-override latch, re-enabling autonomous commands. */
    fun releaseManualOverride(): CommandResult

    /**
     * Return command authority to the Pilot.
     *
     * Only a frame signed with the Safety Computer's key may do this — the same authority that
     * seized control — so this is the MAVLink counterpart of HTTP's /releaseSafetyControl.
     * Deliberately not behind the flight gate: it is the operation that undoes a seizure, so it
     * must be reachable precisely while the Safety Computer holds control.
     */
    fun releaseSafetyControl(): CommandResult

    /**
     * How a pending command is getting on.
     *
     * Polled rather than pushed because the underlying controller signals arrival by setting a
     * latch, not by calling back.
     */
    fun pollCompletion(pending: PendingCommand): CommandProgress

    fun arm(): CommandResult

    /** A disarming request, likewise acknowledged as a no-op behind the gate. */
    fun disarm(): CommandResult
}
