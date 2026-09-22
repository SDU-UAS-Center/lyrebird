package com.lyrebird.rc.telemetry

import com.lyrebird.rc.fleet.FleetBeacon
import com.lyrebird.rc.mavlink.MavlinkSnapshot

internal fun AircraftReadings.applyTo(coordinator: TelemetryCoordinator) {
    coordinator.location = location
    coordinator.altitudeASL = location.altitudeAslM
    coordinator.altitudeAGL = heightAboveTakeoffM
    coordinator.speed = velocity
    coordinator.attitude = attitude
    coordinator.heading = headingDeg
    coordinator.homeLocation = home
    coordinator.gimbalAttitude = gimbal
    coordinator.gimbalJointAttitude = gimbalJoint
    coordinator.zoomFl = zoom.zoomFocalLengthMm
    coordinator.opticalFl = zoom.opticalFocalLengthMm
    coordinator.hybridFl = zoom.hybridFocalLengthMm
    coordinator.zoomRatio = zoom.zoomRatio
    coordinator.batteryLevel = battery.percentRemaining
    coordinator.satelliteCount = satelliteCount
    coordinator.flightMode = flightMode
    coordinator.isRecording = recording
    coordinator.readyToTakeoff = readyToTakeoff
    coordinator.takeoffBlockReason = takeoffBlockReason
    coordinator.remainingFlightTime = battery.remainingFlightTimeS
    coordinator.timeNeededToGoHome = battery.timeNeededToGoHomeS
    coordinator.timeNeededToLand = battery.timeNeededToLandS
    coordinator.totalTime = battery.timeNeededToGoHomeS + battery.timeNeededToLandS
    coordinator.remainingCharge = remainingCharge
    coordinator.maxRadiusCanFlyAndGoHome = maxReturnRadiusM.toInt()
    coordinator.batteryNeededToGoHome = batteryNeededToGoHomePercent
    coordinator.batteryNeededToLand = batteryNeededToLandPercent
    coordinator.seriousLowBatteryThreshold = battery.seriousLowPercent
    coordinator.lowBatteryThreshold = battery.lowPercent
}

internal fun AircraftReadings.toMavlinkSnapshot(session: MavlinkSnapshot): MavlinkSnapshot =
    session.copy(
        latitudeDeg = location.latitudeDeg,
        longitudeDeg = location.longitudeDeg,
        altitudeAslM = location.altitudeAslM,
        altitudeAglM = heightAboveTakeoffM,
        velocityNorthMps = velocity.northMps,
        velocityEastMps = velocity.eastMps,
        velocityDownMps = velocity.downMps,
        rollDeg = attitude.rollDeg,
        pitchDeg = attitude.pitchDeg,
        yawDeg = attitude.yawDeg,
        headingDeg = headingDeg,
        satelliteCount = satelliteCount,
        batteryPercent = battery.percentRemaining,
        remainingFlightTimeS = battery.remainingFlightTimeS,
        homeLatitudeDeg = home.latitudeDeg,
        homeLongitudeDeg = home.longitudeDeg,
        homeAltitudeAslM = location.altitudeAslM - heightAboveTakeoffM,
        flightMode = flightMode,
        motorsRunning = flying,
        isRecording = recording,
        gimbalRollDeg = gimbal.rollDeg,
        gimbalPitchDeg = gimbal.pitchDeg,
        gimbalYawDeg = gimbal.yawDeg,
        gimbalJointRollDeg = gimbalJoint.rollDeg,
        gimbalJointPitchDeg = gimbalJoint.pitchDeg,
        gimbalJointYawDeg = gimbalJoint.yawDeg,
        zoomFocalLengthMm = zoom.zoomFocalLengthMm,
        opticalFocalLengthMm = zoom.opticalFocalLengthMm,
        hybridFocalLengthMm = zoom.hybridFocalLengthMm,
        readyToTakeoff = readyToTakeoff,
        takeoffBlockReason = takeoffBlockReason,
        timeNeededToGoHomeS = battery.timeNeededToGoHomeS,
        timeNeededToLandS = battery.timeNeededToLandS,
        totalFlightTimeS = battery.timeNeededToGoHomeS + battery.timeNeededToLandS,
        maxRadiusCanFlyAndGoHomeM = maxReturnRadiusM,
        batteryNeededToGoHomePercent = batteryNeededToGoHomePercent,
        batteryNeededToLandPercent = batteryNeededToLandPercent,
    )

internal fun AircraftReadings.toFleetBeacon(
    deviceId: String,
    droneName: String,
    systemId: Int,
    homeSet: Boolean,
    videoPath: String,
    videoServer: String,
): FleetBeacon =
    FleetBeacon(
        deviceId = deviceId,
        droneName = droneName,
        systemId = systemId,
        latitudeDeg = location.latitudeDeg,
        longitudeDeg = location.longitudeDeg,
        altitudeAslM = location.altitudeAslM,
        altitudeAglM = heightAboveTakeoffM,
        velocityNorthMps = velocity.northMps,
        velocityEastMps = velocity.eastMps,
        velocityDownMps = velocity.downMps,
        headingDeg = headingDeg,
        batteryPercent = battery.percentRemaining,
        satelliteCount = satelliteCount,
        flying = flying,
        flightMode = flightMode,
        homeLatitudeDeg = home.latitudeDeg,
        homeLongitudeDeg = home.longitudeDeg,
        homeSet = homeSet,
        videoPath = videoPath,
        videoServer = videoServer,
        appUptimeMs = 0L,
        sequence = 0L,
    )
