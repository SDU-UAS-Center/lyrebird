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
     *
     * DJI's absolute yaw is relative to the aircraft. A yaw sent in the earth frame ([flags] with
     * YAW_IN_EARTH_FRAME, or the legacy YAW_LOCK without YAW_IN_VEHICLE_FRAME) is a compass
     * bearing, so it is turned into an aircraft-relative angle with [vehicleYawDeg] first.
     * Passed straight through, a bearing of -95 degrees reached a Mini as a 95-degree turn and
     * DJI refused the whole rotation, pitch with it.
     */
    fun rotationFor(
        pitchDeg: Float,
        yawDeg: Float,
        pitchRateDegS: Float,
        yawRateDegS: Float,
        flags: Int = 0,
        vehicleYawDeg: Double = 0.0,
    ): GimbalRotation? {
        if (!pitchDeg.isNaN() || !yawDeg.isNaN()) {
            val bodyYawDeg =
                when {
                    yawDeg.isNaN() -> 0.0
                    isEarthFrame(flags) -> wrap180(yawDeg - vehicleYawDeg)
                    else -> yawDeg.toDouble()
                }
            return GimbalRotation(
                mode = GimbalRotationMode.ABSOLUTE,
                pitchDeg = if (pitchDeg.isNaN()) 0.0 else pitchDeg.toDouble(),
                rollDeg = 0.0,
                yawDeg = bodyYawDeg,
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

    /** GIMBAL_MANAGER_FLAGS: YAW_LOCK 16, YAW_IN_VEHICLE_FRAME 32, YAW_IN_EARTH_FRAME 64. */
    private fun isEarthFrame(flags: Int): Boolean =
        (flags and FLAG_YAW_IN_EARTH_FRAME) != 0 ||
            ((flags and FLAG_YAW_LOCK) != 0 && (flags and FLAG_YAW_IN_VEHICLE_FRAME) == 0)

    private const val FLAG_YAW_LOCK = 16
    private const val FLAG_YAW_IN_VEHICLE_FRAME = 32
    private const val FLAG_YAW_IN_EARTH_FRAME = 64

    private fun stepFor(rateDegS: Float): Double = if (rateDegS.isNaN()) 0.0 else rateDegS * RATE_STEP_SECONDS

    /**
     * The aircraft's yaw as the gimbal sees it: world yaw minus joint yaw, wrapped to ±180.
     *
     * Sent as `delta_yaw`, and used to turn an earth-frame yaw target into the vehicle-relative
     * angle DJI's absolute rotation takes. Taking it from the gimbal's own two angles rather than
     * the aircraft attitude keeps the round trip exact: quaternion yaw minus this is DJI's joint
     * yaw to the last bit.
     */
    fun vehicleYawDeg(snapshot: MavlinkSnapshot): Double = wrap180(snapshot.gimbalYawDeg - snapshot.gimbalJointYawDeg)

    internal fun wrap180(degrees: Double): Double {
        val wrapped = ((degrees + HALF_TURN) % FULL_TURN + FULL_TURN) % FULL_TURN - HALF_TURN
        return wrapped
    }

    private const val HALF_TURN = 180.0
    private const val FULL_TURN = 360.0
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
