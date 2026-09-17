package com.lyrebird.rc.controller

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.lyrebird.rc.telemetry.ProcessTelemetryRuntimeRegistry
import dji.sdk.keyvalue.value.common.LocationCoordinate3D
import dji.sdk.keyvalue.value.gimbal.GimbalAngleRotation
import dji.sdk.keyvalue.value.gimbal.GimbalAngleRotationMode
import dji.sdk.keyvalue.value.gimbal.GimbalMode
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.et.action
import dji.v5.manager.KeyManager

internal object ProcessRoiRuntimeRegistry {
    private const val TAG = "LyrebirdRoi"
    private const val TRACK_INTERVAL_MS = 200L
    private const val DEADBAND_DEG = 0.5
    private const val MAX_STEP_DEG = 15.0

    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var target: LocationCoordinate3D? = null
    private var previousGimbalMode: GimbalMode? = null
    private var trackingRunnable: Runnable? = null

    fun start(
        latitudeDeg: Double,
        longitudeDeg: Double,
        altitudeM: Double,
    ) {
        target = LocationCoordinate3D(latitudeDeg, longitudeDeg, altitudeM)
        if (trackingRunnable != null) return

        val telemetry = ProcessTelemetryRuntimeRegistry.aircraftTelemetry()
        previousGimbalMode = KeyManager.getInstance().getValue(telemetry.gimbalModeKey) ?: GimbalMode.YAW_FOLLOW
        KeyManager.getInstance().setValue(
            telemetry.gimbalModeKey,
            GimbalMode.FREE,
            object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() {
                    Log.i(TAG, "Gimbal yaw freed for ROI tracking")
                }

                override fun onFailure(error: IDJIError) {
                    Log.w(TAG, "Could not free gimbal yaw for ROI: ${error.description()}")
                }
            },
        )
        trackingRunnable =
            object : Runnable {
                override fun run() {
                    if (target == null) return
                    trackOnce(target ?: return)
                    handler.postDelayed(this, TRACK_INTERVAL_MS)
                }
            }
        handler.post(trackingRunnable ?: return)
        Log.i(TAG, "ROI tracking $latitudeDeg, $longitudeDeg at ${altitudeM}m")
    }

    fun stop() {
        target = null
        trackingRunnable?.let { handler.removeCallbacks(it) }
        trackingRunnable = null
        val previous = previousGimbalMode ?: return
        previousGimbalMode = null
        val telemetry = ProcessTelemetryRuntimeRegistry.aircraftTelemetry()
        KeyManager.getInstance().setValue(
            telemetry.gimbalModeKey,
            previous,
            object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() {
                    Log.i(TAG, "Gimbal yaw follow restored")
                }

                override fun onFailure(error: IDJIError) {
                    Log.w(TAG, "Could not restore gimbal yaw follow: ${error.description()}")
                }
            },
        )
        Log.i(TAG, "ROI tracking cleared")
    }

    fun currentTarget(): LocationCoordinate3D? = target

    private fun trackOnce(target: LocationCoordinate3D) {
        val telemetry = ProcessTelemetryRuntimeRegistry.aircraftTelemetry()
        val position = telemetry.getLocation3D()
        if (position.latitude == 0.0 && position.longitude == 0.0) return

        val aim =
            RoiControl.aimAt(
                bearingToRoiDeg =
                    DroneController
                        .calculateBearing(
                            position.latitude,
                            position.longitude,
                            target.latitude,
                            target.longitude,
                        ).toDouble(),
                groundDistanceM =
                    DroneController.calculateDistance(
                        target.latitude,
                        target.longitude,
                        position.latitude,
                        position.longitude,
                    ),
                altitudeAboveRoiM = position.altitude - target.altitude,
                headingDeg = telemetry.getHeading(),
                aircraftPitchDeg = telemetry.getAttitude().pitch,
            )
        val joint = telemetry.getGimbalJointAttitude()
        val pitchStep = RoiControl.step(aim.pitchDeg - joint.pitch, DEADBAND_DEG, MAX_STEP_DEG)
        val yawStep =
            RoiControl.step(
                RoiControl.normalizeAngle(aim.yawDeg - joint.yaw),
                DEADBAND_DEG,
                MAX_STEP_DEG,
            )
        if (pitchStep == 0.0 && yawStep == 0.0) return

        telemetry.gimbalRotationKey.action(
            GimbalAngleRotation(
                GimbalAngleRotationMode.RELATIVE_ANGLE,
                pitchStep,
                0.0,
                yawStep,
                pitchStep == 0.0,
                true,
                yawStep == 0.0,
                0.1,
                false,
                0,
            ),
        )
    }
}
