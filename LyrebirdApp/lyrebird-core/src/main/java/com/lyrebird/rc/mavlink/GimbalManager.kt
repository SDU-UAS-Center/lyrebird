package com.lyrebird.rc.mavlink

/**
 * The gimbal v2 manager Lyrebird presents for its single gimbal.
 *
 * QGroundControl shows gimbal controls only once one component has answered for a gimbal three
 * ways, all naming the same device id: GIMBAL_MANAGER_INFORMATION (on request),
 * GIMBAL_MANAGER_STATUS (streamed) and GIMBAL_DEVICE_ATTITUDE_STATUS (streamed). The camera
 * component plays the manager, and because manager and device are the same component the device
 * id is 1 — MAVLink's "non-MAVLink gimbal behind this manager" — rather than 0, which QGC
 * rejects in the information message and resolves to a nonexistent device in the attitude one.
 */
object GimbalManager {
    /** Device id for "the gimbal this component manages"; 1-6 are reserved for exactly that. */
    const val DEVICE_ID = 1

    /**
     * How far one rate command turns the gimbal. QGC repeats a held rate command every 500 ms
     * and sends a zero rate on release, so applying each as a 500 ms step approximates the
     * continuous turn DJI's angle-only rotation API cannot express.
     */
    const val RATE_STEP_SECONDS = 0.5

    /**
     * The rotation a DO_GIMBAL_MANAGER_PITCHYAW asks for, or null when it asks for nothing.
     *
     * NaN means "unset" on this command. An angle wins over a rate, as on PX4. A NaN angle keeps
     * its axis where it is — passed through, it would reach DJI as a NaN target. With both angles
     * unset, the rates become one relative step; a zero rate (QGC's button release) moves nothing.
     */
    fun rotationFor(
        pitchDeg: Float,
        yawDeg: Float,
        pitchRateDegS: Float,
        yawRateDegS: Float,
    ): GimbalRotation? {
        if (!pitchDeg.isNaN() || !yawDeg.isNaN()) {
            return GimbalRotation(
                mode = GimbalRotationMode.ABSOLUTE,
                pitchDeg = if (pitchDeg.isNaN()) 0.0 else pitchDeg.toDouble(),
                rollDeg = 0.0,
                yawDeg = if (yawDeg.isNaN()) 0.0 else yawDeg.toDouble(),
                pitchIgnored = pitchDeg.isNaN(),
                rollIgnored = true,
                yawIgnored = yawDeg.isNaN(),
            )
        }
        val pitchStep = stepFor(pitchRateDegS)
        val yawStep = stepFor(yawRateDegS)
        if (pitchStep == 0.0 && yawStep == 0.0) return null
        return GimbalRotation(
            mode = GimbalRotationMode.RELATIVE,
            pitchDeg = pitchStep,
            rollDeg = 0.0,
            yawDeg = yawStep,
            pitchIgnored = pitchStep == 0.0,
            rollIgnored = true,
            yawIgnored = yawStep == 0.0,
        )
    }

    private fun stepFor(rateDegS: Float): Double = if (rateDegS.isNaN()) 0.0 else rateDegS * RATE_STEP_SECONDS
}

/**
 * Who holds primary and secondary control of the gimbal, as GIMBAL_MANAGER_STATUS reports it.
 *
 * Reported, not enforced: pitch/yaw commands are accepted from anyone, as they were before this
 * existed, so ground stations that never send DO_GIMBAL_MANAGER_CONFIGURE keep working. QGC
 * needs the report to see that it is in control, or it never sends a setpoint.
 */
data class GimbalControl(
    val primarySystem: Int = 0,
    val primaryComponent: Int = 0,
    val secondarySystem: Int = 0,
    val secondaryComponent: Int = 0,
) {
    /**
     * Apply a DO_GIMBAL_MANAGER_CONFIGURE from [senderSystem]/[senderComponent].
     *
     * Per id pair: -1 leaves it unchanged, -2 puts the sender in control, -3 releases it if the
     * sender holds it, and any other value (0 = nobody) is taken as given.
     */
    fun configure(
        primarySystemParam: Float,
        primaryComponentParam: Float,
        secondarySystemParam: Float,
        secondaryComponentParam: Float,
        senderSystem: Int,
        senderComponent: Int,
    ): GimbalControl {
        val (newPrimarySystem, newPrimaryComponent) =
            apply(primarySystem, primaryComponent, primarySystemParam, primaryComponentParam, senderSystem, senderComponent)
        val (newSecondarySystem, newSecondaryComponent) =
            apply(
                secondarySystem,
                secondaryComponent,
                secondarySystemParam,
                secondaryComponentParam,
                senderSystem,
                senderComponent,
            )
        return GimbalControl(newPrimarySystem, newPrimaryComponent, newSecondarySystem, newSecondaryComponent)
    }

    private fun apply(
        system: Int,
        component: Int,
        systemParam: Float,
        componentParam: Float,
        senderSystem: Int,
        senderComponent: Int,
    ): Pair<Int, Int> =
        when (systemParam.toInt()) {
            LEAVE_UNCHANGED -> system to component
            SET_SENDER -> senderSystem to senderComponent
            RELEASE_IF_SENDER ->
                if (system == senderSystem && component == senderComponent) 0 to 0 else system to component
            else -> (systemParam.toInt() and 0xFF) to (componentParam.toInt() and 0xFF)
        }

    private companion object {
        const val LEAVE_UNCHANGED = -1
        const val SET_SENDER = -2
        const val RELEASE_IF_SENDER = -3
    }
}
