package com.lyrebird.rc.telemetry

import dji.sdk.keyvalue.key.BatteryKey
import dji.sdk.keyvalue.key.CameraKey
import dji.sdk.keyvalue.key.DJIKey
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.GimbalKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.value.common.Attitude
import dji.sdk.keyvalue.value.common.LocationCoordinate2D
import dji.sdk.keyvalue.value.common.LocationCoordinate3D
import dji.sdk.keyvalue.value.common.Velocity3D
import dji.sdk.keyvalue.value.flightcontroller.FlightMode
import dji.sdk.keyvalue.value.flightcontroller.LowBatteryRTHInfo
import dji.sdk.keyvalue.value.gimbal.GimbalMode
import dji.v5.et.create
import dji.v5.et.get
import dji.v5.manager.KeyManager
import dji.v5.manager.diagnostic.DJIDeviceStatus
import dji.v5.manager.diagnostic.DeviceStatusManager
import dji.v5.ux.core.util.DataProcessor

internal class V5AircraftTelemetrySource : AircraftTelemetrySource {
    companion object {
        private const val MAX_PLAUSIBLE_GIMBAL_DEG = 200.0
    }

    interface Observer {
        fun onAltitudeChanged(altitudeAslM: Double)

        fun onGimbalPitchChanged(pitchDeg: Double)

        fun onReadingsChanged()
    }

    private val telemetryListenerOwner = Any()

    @Volatile private var observer: Observer? = null
    private var telemetryStarted = false

    fun startTelemetry(observer: Observer) {
        this.observer = observer
        if (telemetryStarted) return
        telemetryStarted = true
        val keys = KeyManager.getInstance()
        keys.listen(location3DKey, telemetryListenerOwner) { _, value ->
            this.observer?.onAltitudeChanged(value?.altitude ?: 0.0)
            this.observer?.onReadingsChanged()
        }
        keys.listen(gimbalAttitudeKey, telemetryListenerOwner) { _, value ->
            this.observer?.onGimbalPitchChanged(value?.pitch ?: 0.0)
            this.observer?.onReadingsChanged()
        }
        keys.listen(attitudeKey, telemetryListenerOwner) { _, _ -> this.observer?.onReadingsChanged() }
        keys.listen(compassHeadKey, telemetryListenerOwner) { _, _ -> this.observer?.onReadingsChanged() }
        keys.listen(flightSpeedKey, telemetryListenerOwner) { _, _ -> this.observer?.onReadingsChanged() }
        keys.listen(batteryKey, telemetryListenerOwner) { _, _ -> this.observer?.onReadingsChanged() }
    }

    fun stop() {
        observer = null
        telemetryStarted = false
        KeyManager.getInstance().cancelListen(telemetryListenerOwner)
        KeyManager.getInstance().cancelListen(this)
    }

    override fun read(): AircraftReadings {
        val location = getLocation3D()
        val home = getHomeLocation()
        val velocity = getSpeed()
        val attitude = getAttitude()
        val gimbal = getGimbalAttitude()
        val gimbalJoint = getGimbalJointAttitude()
        val returnInfo = goHomeAssessmentProcessor.value
        return AircraftReadings(
            location = GeoPosition(location.latitude, location.longitude, location.altitude),
            heightAboveTakeoffM = getAltitude(),
            velocity = VelocityNedMps(velocity.x, velocity.y, velocity.z),
            attitude = AttitudeDeg(attitude.roll, attitude.pitch, attitude.yaw),
            headingDeg = getHeading(),
            home = GeoPoint(home.latitude, home.longitude),
            gimbal = AttitudeDeg(gimbal.roll, gimbal.pitch, gimbal.yaw),
            gimbalJoint = AttitudeDeg(gimbalJoint.roll, gimbalJoint.pitch, gimbalJoint.yaw),
            zoom =
                CameraZoomState(
                    getCameraZoomFocalLength(),
                    getCameraOpticalFocalLength(),
                    getCameraHybridFocalLength(),
                    CameraKey.KeyCameraZoomRatios.create().get() ?: 1.0,
                ),
            battery =
                BatteryState(
                    getBatteryLevel(),
                    seriousLowBatteryThresholdProcessor.value,
                    lowBatteryThresholdProcessor.value,
                    returnInfo.remainingFlightTime,
                    getTimeNeededToGoHome(),
                    getTimeNeededToLand(),
                ),
            satelliteCount = getSatelliteCount(),
            flightMode = getFlightMode().name,
            flying = isFlyingKey.get(false),
            recording = isRecordingKey.get() ?: false,
            readyToTakeoff = isReadyToTakeoff(),
            takeoffBlockReason = getTakeoffBlockReason(),
            remainingCharge = chargeRemainingProcessor.value,
            maxReturnRadiusM = returnInfo.maxRadiusCanFlyAndGoHome.toDouble(),
            batteryNeededToGoHomePercent = returnInfo.batteryPercentNeededToGoHome,
            batteryNeededToLandPercent = returnInfo.batteryPercentNeededToLand,
        )
    }

    // Battery and flight time data processors
    internal val chargeRemainingProcessor: DataProcessor<Int> = DataProcessor.create(0)

    internal val goHomeAssessmentProcessor: DataProcessor<LowBatteryRTHInfo> = DataProcessor.create(LowBatteryRTHInfo())

    internal val seriousLowBatteryThresholdProcessor: DataProcessor<Int> = DataProcessor.create(0)

    internal val lowBatteryThresholdProcessor: DataProcessor<Int> = DataProcessor.create(0)

    internal val timeNeededToLandProcessor: DataProcessor<Int> = DataProcessor.create(0)

// DJI Keys
    internal val chargeRemainingKey = KeyTools.createKey(BatteryKey.KeyChargeRemainingInPercent)

    internal val goHomeAssessmentKey = KeyTools.createKey(FlightControllerKey.KeyLowBatteryRTHInfo)

    internal val seriousLowBatteryKey = KeyTools.createKey(FlightControllerKey.KeySeriousLowBatteryWarningThreshold)

    internal val lowBatteryKey = KeyTools.createKey(FlightControllerKey.KeyLowBatteryWarningThreshold)

    internal val timeNeededToLandKey = KeyTools.createKey(FlightControllerKey.KeyLowBatteryRTHInfo)

    internal val isRecordingKey: DJIKey<Boolean> = CameraKey.KeyIsRecording.create()

    internal val location3DKey: DJIKey<LocationCoordinate3D> = FlightControllerKey.KeyAircraftLocation3D.create()

    internal val satelliteCountKey: DJIKey<Int> = FlightControllerKey.KeyGPSSatelliteCount.create()

    internal var gimbalAttitudeKey: DJIKey<Attitude> = GimbalKey.KeyGimbalAttitude.create()

    internal var gimbalJointAttitudeKey: DJIKey<Attitude> = GimbalKey.KeyGimbalJointAttitude.create()

    internal var gimbalModeKey: DJIKey<GimbalMode> = GimbalKey.KeyGimbalMode.create()

    internal val compassHeadKey: DJIKey<Double> = FlightControllerKey.KeyCompassHeading.create()

    internal val altitudeKey: DJIKey<Double> = FlightControllerKey.KeyAltitude.create()

    internal val homeLocationKey: DJIKey<LocationCoordinate2D> = FlightControllerKey.KeyHomeLocation.create()

    internal val flightSpeedKey: DJIKey<Velocity3D> = FlightControllerKey.KeyAircraftVelocity.create()

    internal val attitudeKey: DJIKey<Attitude> = FlightControllerKey.KeyAircraftAttitude.create()

    internal val cameraZoomFocalLengthKey: DJIKey<Int> = CameraKey.KeyCameraZoomFocalLength.create()

    internal val cameraOpticalFocalLengthKey: DJIKey<Int> = CameraKey.KeyCameraOpticalZoomFocalLength.create()

    internal val cameraHybridFocalLengthKey: DJIKey<Int> = CameraKey.KeyCameraHybridZoomFocalLength.create()

    internal val batteryKey: DJIKey<Int> = BatteryKey.KeyChargeRemainingInPercent.create()

    internal val flightModeKey: DJIKey<FlightMode> = FlightControllerKey.KeyFlightMode.create()

    internal val isFlyingKey: DJIKey<Boolean> = FlightControllerKey.KeyIsFlying.create()

    internal fun setupBatteryAndRthListeners() {
        KeyManager.getInstance().listen(chargeRemainingKey, this) { _, newValue ->
            chargeRemainingProcessor.onNext(newValue ?: 0)
        }
        KeyManager.getInstance().listen(goHomeAssessmentKey, this) { _, newValue ->
            goHomeAssessmentProcessor.onNext(newValue ?: LowBatteryRTHInfo())
        }
        KeyManager.getInstance().listen(seriousLowBatteryKey, this) { _, newValue ->
            seriousLowBatteryThresholdProcessor.onNext(newValue ?: 0)
        }
        KeyManager.getInstance().listen(lowBatteryKey, this) { _, newValue ->
            lowBatteryThresholdProcessor.onNext(newValue ?: 0)
        }
        KeyManager.getInstance().listen(timeNeededToLandKey, this) { _, newValue ->
            timeNeededToLandProcessor.onNext(newValue?.timeNeededToLand ?: 0)
        }
    }

    internal fun getLocation3D(): LocationCoordinate3D = location3DKey.get(LocationCoordinate3D(0.0, 0.0, .0))

    internal fun getAltitude(): Double = altitudeKey.get(0.0)

    internal fun getSatelliteCount(): Int = satelliteCountKey.get(-1)

    internal fun getGimbalAttitude(): Attitude = sanitisedAttitude(gimbalAttitudeKey.get())

    internal fun getGimbalJointAttitude(): Attitude = sanitisedAttitude(gimbalJointAttitudeKey.get())

/**
     * A gimbal attitude with DJI's unset marker replaced by zero.
     *
     * When the gimbal saturates -- the aircraft tilted past what it can compensate for -- DJI
     * reports 6553.5 on the affected axis, which is 65535/10 and not an angle. Publishing it
     * unchanged put a 6553-degree pitch on the telemetry stream, where anything reading it as a
     * number took it seriously. A sweep of the aircraft by hand produced it in 23 of 91 samples,
     * so this is the normal case at the edges of travel rather than a rare fault.
     */
    internal fun sanitisedAttitude(attitude: Attitude?): Attitude {
        if (attitude == null) return Attitude(0.0, 0.0, 0.0)

        fun axis(value: Double?): Double = if (value == null || kotlin.math.abs(value) > MAX_PLAUSIBLE_GIMBAL_DEG) 0.0 else value
        return Attitude(axis(attitude.pitch), axis(attitude.roll), axis(attitude.yaw))
    }

    internal fun getHeading(): Double = compassHeadKey.get(0.0)

    internal fun getHomeLocation(): LocationCoordinate2D = homeLocationKey.get(LocationCoordinate2D())

    internal fun getSpeed(): Velocity3D = flightSpeedKey.get(Velocity3D(0.0, 0.0, 0.0))

    internal fun getAttitude(): Attitude = attitudeKey.get(Attitude(0.0, 0.0, 0.0))

    internal fun getCameraZoomFocalLength(): Int = cameraZoomFocalLengthKey.get(-1)

    internal fun getCameraOpticalFocalLength(): Int = cameraOpticalFocalLengthKey.get(-1)

    internal fun getCameraHybridFocalLength(): Int = cameraHybridFocalLengthKey.get(-1)

    internal fun getBatteryLevel(): Int = batteryKey.get(-1)

    internal fun getFlightMode(): FlightMode = flightModeKey.get(FlightMode.UNKNOWN)

/**
     * Whether the aircraft is ready to take off / arm.
     *
     * Mirrors the DJI system-status banner: ready when it reads "Ready to Go (GPS)",
     * i.e. [DJIDeviceStatus.NORMAL]. Any other status counts as not ready.
     */
    internal fun isReadyToTakeoff(): Boolean = DeviceStatusManager.getInstance().getCurrentDJIDeviceStatus() == DJIDeviceStatus.NORMAL

/** Reason the aircraft cannot take off, or "NONE" when ready. Mirrors the DJI status banner. */
    internal fun getTakeoffBlockReason(): String {
        val status = DeviceStatusManager.getInstance().getCurrentDJIDeviceStatus()
        return if (status == DJIDeviceStatus.NORMAL) "NONE" else status.name
    }

    internal fun getTimeNeededToGoHome(): Int = goHomeAssessmentProcessor.value.timeNeededToGoHome

    internal fun getTimeNeededToLand(): Int = timeNeededToLandProcessor.value
}
