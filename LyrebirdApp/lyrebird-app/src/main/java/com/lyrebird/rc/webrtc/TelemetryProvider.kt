package com.lyrebird.rc.webrtc

import android.util.Log
import dji.sdk.keyvalue.key.BatteryKey
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.GimbalKey
import dji.sdk.keyvalue.value.common.Attitude
import dji.sdk.keyvalue.value.common.LocationCoordinate3D
import dji.sdk.keyvalue.value.common.Velocity3D
import com.lyrebird.rc.controller.DroneController
import dji.v5.manager.KeyManager
import dji.v5.manager.diagnostic.DJIDeviceStatus
import dji.v5.manager.diagnostic.DeviceStatusManager
import dji.v5.ux.detection.DetectedTarget
import dji.v5.et.create

/**
 * Provides synchronized telemetry data from DJI SDK KeyManager.
 * 
 * Values are cached via asynchronous KeyManager listeners so that
 * [captureMetadata] only reads pre-cached volatile fields instead of
 * issuing 11 blocking SDK calls per frame.
 */
object TelemetryProvider {

    private const val TAG = "TelemetryProvider"
    
    /** Current detected targets from AutoSensing – updated by the activity */
    @Volatile
    var currentDetectedTargets: List<DetectedTarget> = emptyList()

    @Volatile
    var currentDetectionSource: String = "none"

    @Volatile
    var currentDetectionActive: Boolean = false

    @Volatile
    var currentDetectionModel: String? = null

    @Volatile
    var currentDetectionThreshold: Float? = null
    
    // DJI Keys for telemetry - created once and reused
    private val location3DKey = FlightControllerKey.KeyAircraftLocation3D.create()
    private val altitudeKey = FlightControllerKey.KeyAltitude.create()
    private val attitudeKey = FlightControllerKey.KeyAircraftAttitude.create()
    private val velocityKey = FlightControllerKey.KeyAircraftVelocity.create()
    private val headingKey = FlightControllerKey.KeyCompassHeading.create()
    private val gimbalAttitudeKey = GimbalKey.KeyGimbalAttitude.create()
    private val satelliteCountKey = FlightControllerKey.KeyGPSSatelliteCount.create()
    private val batteryPercentKey = BatteryKey.KeyChargeRemainingInPercent.create()
    private val isFlyingKey = FlightControllerKey.KeyIsFlying.create()
    private val flightModeStringKey = FlightControllerKey.KeyFlightModeString.create()

    // ---- Cached values updated asynchronously by KeyManager listeners ----
    @Volatile private var cachedLocation = LocationCoordinate3D(0.0, 0.0, 0.0)
    @Volatile private var cachedAltitude = 0.0
    @Volatile private var cachedAttitude = Attitude(0.0, 0.0, 0.0)
    @Volatile private var cachedVelocity = Velocity3D(0.0, 0.0, 0.0)
    @Volatile private var cachedHeading = 0.0
    @Volatile private var cachedGimbalAttitude = Attitude(0.0, 0.0, 0.0)
    @Volatile private var cachedSatelliteCount = 0
    @Volatile private var cachedBatteryPercent = 0
    @Volatile private var cachedIsFlying = false
    @Volatile private var cachedFlightMode = "UNKNOWN"

    @Volatile private var listenersRegistered = false

    /**
     * Start listening for telemetry updates.  Safe to call multiple times;
     * listeners are only registered once.
     */
    fun startListening() {
        if (listenersRegistered) return
        listenersRegistered = true
        Log.d(TAG, "Registering async telemetry listeners")

        val km = KeyManager.getInstance()

        km.listen(location3DKey, this) { _, v ->
            v?.let { cachedLocation = it }
        }
        km.listen(altitudeKey, this) { _, v ->
            v?.let { cachedAltitude = it }
        }
        km.listen(attitudeKey, this) { _, v ->
            v?.let { cachedAttitude = it }
        }
        km.listen(velocityKey, this) { _, v ->
            v?.let { cachedVelocity = it }
        }
        km.listen(headingKey, this) { _, v ->
            v?.let { cachedHeading = it }
        }
        km.listen(gimbalAttitudeKey, this) { _, v ->
            v?.let { cachedGimbalAttitude = it }
        }
        km.listen(satelliteCountKey, this) { _, v ->
            v?.let { cachedSatelliteCount = it }
        }
        km.listen(batteryPercentKey, this) { _, v ->
            v?.let { cachedBatteryPercent = it }
        }
        km.listen(isFlyingKey, this) { _, v ->
            v?.let { cachedIsFlying = it }
        }
        km.listen(flightModeStringKey, this) { _, v ->
            v?.let { cachedFlightMode = it }
        }
    }

    /**
     * Derived take-off readiness. See [FrameMetadata.readyToTakeoff].
     * Mirrors the DJI system-status banner: ready when it reads "Ready to Go (GPS)"
     * ([DJIDeviceStatus.NORMAL]). getCurrentDJIDeviceStatus() is a cheap in-memory
     * getter, so it is safe to call per frame.
     */
    private fun computeReadyToTakeoff(): Boolean =
        DeviceStatusManager.getInstance().getCurrentDJIDeviceStatus() == DJIDeviceStatus.NORMAL

    /** Reason the aircraft cannot take off, or "NONE" when ready. Mirrors the DJI status banner. */
    private fun computeTakeoffBlockReason(): String {
        val status = DeviceStatusManager.getInstance().getCurrentDJIDeviceStatus()
        return if (status == DJIDeviceStatus.NORMAL) "NONE" else status.name
    }

    /**
     * Stop all telemetry listeners.
     */
    fun stopListening() {
        if (!listenersRegistered) return
        listenersRegistered = false
        Log.d(TAG, "Cancelling async telemetry listeners")
        KeyManager.getInstance().cancelListen(this)
    }

    /**
     * Capture current telemetry state.
     * Reads only pre-cached volatile fields — no SDK calls.
     * 
     * @param frameNumber The sequential frame number
     * @param timestampNs The frame timestamp in nanoseconds
     * @param frameWidth The video frame width
     * @param frameHeight The video frame height
     * @param droneName The name/identifier of this drone
     * @return FrameMetadata containing all telemetry synchronized with the frame
     */
    fun captureMetadata(
        frameNumber: Long,
        timestampNs: Long,
        frameWidth: Int,
        frameHeight: Int,
        droneName: String = "drone_1"
    ): FrameMetadata = captureCachedMetadata(frameNumber, timestampNs, frameWidth, frameHeight, droneName)

    private fun captureCachedMetadata(
        frameNumber: Long,
        timestampNs: Long,
        frameWidth: Int,
        frameHeight: Int,
        droneName: String
    ): FrameMetadata {
        val location = cachedLocation
        val attitude = cachedAttitude
        val velocity = cachedVelocity
        val gimbalAttitude = cachedGimbalAttitude

        return FrameMetadata(
            frameNumber = frameNumber,
            timestampNs = timestampNs,
            captureTimeMs = System.currentTimeMillis(),
            droneName = droneName,
            frameWidth = frameWidth,
            frameHeight = frameHeight,
            
            // Position - location3D contains GPS altitude (ASL)
            latitude = location.latitude,
            longitude = location.longitude,
            altitudeASL = location.altitude,
            altitudeAGL = cachedAltitude,
            
            // Aircraft attitude
            aircraftPitch = attitude.pitch,
            aircraftRoll = attitude.roll,
            aircraftYaw = cachedHeading,
            
            // Gimbal attitude
            gimbalPitch = gimbalAttitude.pitch,
            gimbalRoll = gimbalAttitude.roll,
            gimbalYaw = gimbalAttitude.yaw,
            
            // Velocity (NED coordinate system)
            velocityX = velocity.x,
            velocityY = velocity.y,
            velocityZ = velocity.z,
            
            // Additional info
            satelliteCount = cachedSatelliteCount,
            batteryPercent = cachedBatteryPercent,
            isFlying = cachedIsFlying,
            flightMode = cachedFlightMode,
            readyToTakeoff = computeReadyToTakeoff(),
            takeoffBlockReason = computeTakeoffBlockReason(),
            isManualOverrideActive = DroneController.isManualOverrideActive,
            detectedTargets = currentDetectedTargets,
            detectionSource = currentDetectionSource,
            detectionActive = currentDetectionActive,
            detectionModel = currentDetectionModel,
            detectionConfidenceThreshold = currentDetectionThreshold
        )
    }
}
