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

    @Volatile private var observer: Observer? = null
    private var telemetryStarted = false
    private var batteryListenersStarted = false
    private var flightStateObserver: FlightStateObserver? = null
    private var flightStateListenersStarted = false

    @Volatile private var aircraftConnected = false

    @Volatile private var connectionGeneration = 0L

    @Volatile private var lastObservedAtMillis = 0L
    private var generationListenerOwner: Any? = null
    private val connectionState = ConnectionGeneration()

    interface FlightStateObserver {
        fun onFlyingChanged(flying: Boolean)

        fun onFlightModeChanged(mode: FlightMode)

        fun onSatelliteCountChanged(count: Int)
    }

    fun setConnectionState(connected: Boolean) {
        val state = connectionState.update(connected)
        if (aircraftConnected == state.connected && connectionGeneration == state.generation) return
        aircraftConnected = state.connected
        connectionGeneration = state.generation
        cancelGenerationListeners()
        if (connected) registerGenerationListeners()
    }

    fun startTelemetry(observer: Observer) {
        this.observer = observer
        if (telemetryStarted) return
        telemetryStarted = true
        if (aircraftConnected) registerGenerationListeners()
    }

    fun startFlightStateUpdates(observer: FlightStateObserver) {
        flightStateObserver = observer
        flightStateListenersStarted = true
        if (aircraftConnected) registerGenerationListeners()
    }

    fun stop() {
        observer = null
        flightStateObserver = null
        telemetryStarted = false
        batteryListenersStarted = false
        flightStateListenersStarted = false
        cancelGenerationListeners()
        KeyManager.getInstance().cancelListen(this)
    }

    private fun registerGenerationListeners() {
        cancelGenerationListeners()
        val generation = connectionGeneration
        val owner = Any()
        generationListenerOwner = owner
        val keys = KeyManager.getInstance()

        fun current(): Boolean = aircraftConnected && connectionGeneration == generation
        if (telemetryStarted) {
            keys.listen(location3DKey, owner) { _, value ->
                if (!current()) return@listen
                markObserved()
                observer?.onAltitudeChanged(value?.altitude ?: 0.0)
                observer?.onReadingsChanged()
            }
            keys.listen(gimbalAttitudeKey, owner) { _, value ->
                if (!current()) return@listen
                markObserved()
                observer?.onGimbalPitchChanged(value?.pitch ?: 0.0)
                observer?.onReadingsChanged()
            }
            keys.listen(attitudeKey, owner) { _, _ -> if (current()) readingsChanged() }
            keys.listen(compassHeadKey, owner) { _, _ -> if (current()) readingsChanged() }
            keys.listen(flightSpeedKey, owner) { _, _ -> if (current()) readingsChanged() }
            keys.listen(batteryKey, owner) { _, _ -> if (current()) readingsChanged() }
        }
        if (batteryListenersStarted) {
            keys.listen(chargeRemainingKey, owner) { _, newValue ->
                if (current()) {
                    chargeRemainingProcessor.onNext(newValue ?: 0)
                    markObserved()
                }
            }
            keys.listen(goHomeAssessmentKey, owner) { _, newValue ->
                if (current()) {
                    goHomeAssessmentProcessor.onNext(newValue ?: LowBatteryRTHInfo())
                    markObserved()
                }
            }
            keys.listen(seriousLowBatteryKey, owner) { _, newValue ->
                if (current()) {
                    seriousLowBatteryThresholdProcessor.onNext(newValue ?: 0)
                    markObserved()
                }
            }
            keys.listen(lowBatteryKey, owner) { _, newValue ->
                if (current()) {
                    lowBatteryThresholdProcessor.onNext(newValue ?: 0)
                    markObserved()
                }
            }
            keys.listen(timeNeededToLandKey, owner) { _, newValue ->
                if (current()) {
                    timeNeededToLandProcessor.onNext(newValue?.timeNeededToLand ?: 0)
                    markObserved()
                }
            }
        }
        if (flightStateListenersStarted) {
            keys.listen(isFlyingKey, owner) { _, value ->
                if (current()) {
                    markObserved()
                    flightStateObserver?.onFlyingChanged(value ?: false)
                }
            }
            keys.listen(flightModeKey, owner) { _, value ->
                if (current()) {
                    markObserved()
                    flightStateObserver?.onFlightModeChanged(value ?: FlightMode.UNKNOWN)
                }
            }
            keys.listen(satelliteCountKey, owner) { _, value ->
                if (current()) {
                    markObserved()
                    flightStateObserver?.onSatelliteCountChanged(value ?: -1)
                }
            }
        }
    }

    private fun cancelGenerationListeners() {
        generationListenerOwner?.let { KeyManager.getInstance().cancelListen(it) }
        generationListenerOwner = null
    }

    private fun readingsChanged() {
        markObserved()
        observer?.onReadingsChanged()
    }

    private fun markObserved() {
        lastObservedAtMillis = System.currentTimeMillis()
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

    fun readState(): AircraftState =
        AircraftState(
            readings = read(),
            connected = aircraftConnected,
            connectionGeneration = connectionGeneration,
            observedAtMillis = lastObservedAtMillis,
        )

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
        batteryListenersStarted = true
        if (aircraftConnected) registerGenerationListeners()
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
