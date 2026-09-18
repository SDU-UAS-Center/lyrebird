package com.lyrebird.rc.controller

import com.lyrebird.rc.mavlink.MotionCommandPort
import com.lyrebird.rc.mavlink.PendingKind
import com.lyrebird.rc.mavlink.ReachLatch
import com.lyrebird.rc.mavlink.WaypointArrival
import com.lyrebird.rc.mavlink.WaypointRefusal
import com.lyrebird.rc.platform.ManualStick

/**
 * [MotionCommandPort] over the V5 controller.
 *
 * Every method is a direct delegation: the controller keeps the PID/geometry/sequencing and only
 * this seam is V5-shaped, which is what lets the policy above it be written once for every
 * backend.
 */
internal object V5MotionCommandPort : MotionCommandPort {
    override fun shouldRejectAutonomousCommand(commandName: String): Boolean = DroneController.shouldRejectAutonomousCommand(commandName)

    override fun takeoff() = DroneController.startTakeOff()

    override fun land() = DroneController.startLanding()

    override fun returnToHome() = DroneController.startReturnToHome()

    override fun gotoYaw(yawDeg: Double): Long = DroneController.gotoYaw(yawDeg)

    override fun gotoAltitude(altitudeMeters: Double): Long = DroneController.gotoAltitude(altitudeMeters)

    override fun waypoint(
        latitudeDeg: Double,
        longitudeDeg: Double,
        altitudeMeters: Double,
        yawDeg: Double,
        speedMps: Double,
        noseForward: Boolean,
        arrival: WaypointArrival,
    ): Long =
        if (noseForward) {
            DroneController.flyToWaypointNoseForward(latitudeDeg, longitudeDeg, altitudeMeters, yawDeg, speedMps, arrival)
        } else {
            DroneController.flyToWaypointHoldHeading(latitudeDeg, longitudeDeg, altitudeMeters, yawDeg, speedMps, arrival)
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
        DroneController.orbit(
            centreLatitude = centreLatitude,
            centreLongitude = centreLongitude,
            targetAltitude = targetAltitude,
            radiusMeters = radiusMeters,
            tangentialSpeedMps = tangentialSpeedMps,
            clockwise = clockwise,
            arcDegrees = arcDegrees,
            faceCentre = faceCentre,
        )
    }

    override fun abortAllMissions() = DroneController.abortAllMissions()

    override fun endMission() = DroneController.endMission()

    override fun sendManualStick(stick: ManualStick) =
        DroneController.setStick(leftX = stick.leftX, leftY = stick.leftY, rightX = stick.rightX, rightY = stick.rightY)

    override fun enableVirtualStick() = DroneController.enableVirtualStick()

    override fun disableVirtualStick() = DroneController.disableVirtualStick()

    override fun deactivateManualOverride() = DroneController.deactivateManualOverride()

    override val isManualOverrideActive: Boolean get() = DroneController.isManualOverrideActive

    override fun lastWaypointRefusal(): WaypointRefusal? = DroneController.lastWaypointRefusal()

    override fun reachLatch(kind: PendingKind): ReachLatch =
        when (kind) {
            PendingKind.WAYPOINT ->
                ReachLatch(DroneController.getWaypointSeq(), DroneController.isWaypointReached())
            PendingKind.YAW ->
                ReachLatch(DroneController.getYawSeq(), DroneController.isYawReached())
            PendingKind.ALTITUDE ->
                ReachLatch(DroneController.getAltitudeSeq(), DroneController.isAltitudeReached())
            PendingKind.ORBIT ->
                ReachLatch(DroneController.getOrbitSeq(), DroneController.isOrbitComplete())
        }
}
