package com.lyrebird.rc.platform

import com.lyrebird.rc.models.BasicAircraftControlVM
import com.lyrebird.rc.models.VirtualStickVM
import dji.sdk.keyvalue.value.common.EmptyMsg
import dji.sdk.keyvalue.value.flightcontroller.FlightCoordinateSystem
import dji.sdk.keyvalue.value.flightcontroller.RollPitchControlMode
import dji.sdk.keyvalue.value.flightcontroller.VerticalControlMode
import dji.sdk.keyvalue.value.flightcontroller.VirtualStickFlightControlParam
import dji.sdk.keyvalue.value.flightcontroller.YawControlMode
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.aircraft.virtualstick.Stick

/**
 * [FlightPrimitives] over the V5 view models.
 *
 * The one piece of hard-won SDK knowledge in here is the BODY-frame field inversion: the SDK's
 * `pitch` field drives lateral (left/right) motion and `roll` drives forward/backward, the
 * opposite of what the names suggest. It was established empirically on the airframe and every
 * waypoint/orbit loop depends on it, so the swap lives here, once, together with the mode
 * mapping — instead of being re-derived at seven construction sites.
 */
internal class V5FlightPrimitives(
    private val basicControl: BasicAircraftControlVM?,
    private val virtualStick: VirtualStickVM?,
) : FlightPrimitives {
    override fun takeoff(onResult: (Boolean) -> Unit) {
        val control = basicControl
        if (control == null) {
            onResult(false)
            return
        }
        control.startTakeOff(completion(onResult))
    }

    override fun land(onResult: (Boolean) -> Unit) {
        val control = basicControl
        if (control == null) {
            onResult(false)
            return
        }
        control.startLanding(completion(onResult))
    }

    override fun returnToHome(onResult: (Boolean) -> Unit) {
        val control = basicControl
        if (control == null) {
            onResult(false)
            return
        }
        control.startReturnToHome(completion(onResult))
    }

    override fun acquireControl(onResult: (Boolean) -> Unit) {
        val stick = virtualStick
        if (stick == null) {
            onResult(false)
            return
        }
        // The SDK's own sequence: advanced mode first, then enable. Re-enabling an armed mode
        // reports "already enabled" as a failure, which is not an error for the loops.
        stick.enableVirtualStickAdvancedMode()
        stick.enableVirtualStick(
            object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() = onResult(true)

                override fun onFailure(error: IDJIError) = onResult(true)
            },
        )
    }

    override fun releaseControl(onResult: (Boolean) -> Unit) {
        val stick = virtualStick
        if (stick == null) {
            onResult(false)
            return
        }
        stick.disableVirtualStick(
            object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() = onResult(true)

                // "Already disabled" is indistinguishable from a real failure here, and a monitor
                // that had no control authority is not an error; the live stick state is what the
                // loops check.
                override fun onFailure(error: IDJIError) = onResult(true)
            },
        )
    }

    override fun send(setpoint: FlightSetpoint) {
        val stick = virtualStick ?: return
        val param =
            VirtualStickFlightControlParam().apply {
                // See the class note: forward is carried by the SDK's "roll" field and rightward
                // motion by "pitch" in the BODY frame.
                this.pitch = setpoint.rightMps
                this.roll = setpoint.forwardMps
                this.yaw = setpoint.yaw
                this.verticalThrottle = setpoint.vertical
                this.verticalControlMode =
                    when (setpoint.verticalReference) {
                        VerticalReference.UP_VELOCITY_MPS -> VerticalControlMode.VELOCITY
                        VerticalReference.ALTITUDE_MSL_M -> VerticalControlMode.POSITION
                    }
                this.yawControlMode =
                    when (setpoint.yawReference) {
                        YawReference.RATE_CW_DPS -> YawControlMode.ANGULAR_VELOCITY
                        YawReference.HEADING_DEG -> YawControlMode.ANGLE
                    }
                this.rollPitchControlMode = RollPitchControlMode.VELOCITY
                this.rollPitchCoordinateSystem = FlightCoordinateSystem.BODY
            }
        stick.sendVirtualStickAdvancedParam(param)
    }

    override fun sendManualStick(stick: ManualStick) {
        val vm = virtualStick ?: return
        vm.setLeftPosition(
            (stick.leftX * Stick.MAX_STICK_POSITION_ABS).toInt(),
            (stick.leftY * Stick.MAX_STICK_POSITION_ABS).toInt(),
        )
        vm.setRightPosition(
            (stick.rightX * Stick.MAX_STICK_POSITION_ABS).toInt(),
            (stick.rightY * Stick.MAX_STICK_POSITION_ABS).toInt(),
        )
    }

    private fun completion(onResult: (Boolean) -> Unit): CommonCallbacks.CompletionCallbackWithParam<EmptyMsg> =
        object : CommonCallbacks.CompletionCallbackWithParam<EmptyMsg> {
            override fun onSuccess(t: EmptyMsg?) = onResult(true)

            override fun onFailure(error: IDJIError) = onResult(false)
        }
}
