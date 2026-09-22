package com.lyrebird.rc.controller

import android.util.Log
import com.lyrebird.rc.telemetry.ProcessTelemetryRuntimeRegistry
import dji.sdk.keyvalue.value.gimbal.GimbalAngleRotation
import dji.sdk.keyvalue.value.gimbal.GimbalAngleRotationMode
import dji.sdk.keyvalue.value.gimbal.GimbalMode
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.et.action
import dji.v5.manager.KeyManager

/**
 * [RoiGimbalPort] over the V5 gimbal keys.
 *
 * The mode bookkeeping lives here rather than in the tracker because the mode values are the
 * SDK's: the guard saves whatever the operator had (YAW_FOLLOW when the key cannot be read),
 * requests FREE so yaw stops following the airframe, and puts the saved value back on stop. The
 * tracker only asks for "free" and "restore".
 */
internal class V5RoiGimbalPort : RoiGimbalPort {
    companion object {
        private const val TAG = "LyrebirdRoi"
    }

    private var previousMode: GimbalMode? = null

    override fun freeYawForTracking() {
        val telemetry = ProcessTelemetryRuntimeRegistry.aircraftTelemetry()
        previousMode = KeyManager.getInstance().getValue(telemetry.gimbalModeKey) ?: GimbalMode.YAW_FOLLOW
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
    }

    override fun restoreYawMode() {
        val previous = previousMode ?: return
        previousMode = null
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
    }

    override fun rotateJoint(
        relativePitchDeg: Double,
        relativeYawDeg: Double,
    ) {
        // A zero step on an axis stays an explicit "leave it alone" through the ignore flags,
        // exactly as the payload nudge does, so the two never fight over one axis.
        ProcessTelemetryRuntimeRegistry.aircraftTelemetry().gimbalRotationKey.action(
            GimbalAngleRotation(
                GimbalAngleRotationMode.RELATIVE_ANGLE,
                relativePitchDeg,
                0.0,
                relativeYawDeg,
                relativePitchDeg == 0.0,
                true,
                relativeYawDeg == 0.0,
                0.1,
                false,
                0,
            ),
        )
    }
}
