package com.lyrebird.rc.platform

/*
 * The flight-control primitives the shared controller is built on.
 *
 * Deliberately small: takeoff/land/return-to-home, acquiring and releasing app control, and typed
 * setpoint sends. PID gains, geometry, arrival thresholds and sequencing stay in the shared
 * controller — this port is only the hardware conversation. Units and reference frames are in the
 * names, because a bare `pitch`/`yaw` pair is exactly how a metres-versus-degrees or
 * body-versus-north mistake survives review; the V5 adapter additionally has one field-name
 * inversion that had to be established by flying, and it lives there, once.
 */

/** How to read [FlightSetpoint.vertical]. */
enum class VerticalReference {
    /** Vertical speed in metres per second, positive up. */
    UP_VELOCITY_MPS,

    /** Altitude hold target in metres above mean sea level, in the datum the aircraft reports. */
    ALTITUDE_MSL_M,
}

/** How to read [FlightSetpoint.yaw]. */
enum class YawReference {
    /** Yaw rate in degrees per second, positive clockwise seen from above. */
    RATE_CW_DPS,

    /** Absolute heading in degrees clockwise from north. */
    HEADING_DEG,
}

/**
 * One virtual-stick setpoint: body-frame horizontal velocity plus one vertical and one yaw intent.
 *
 * Horizontal intent is always body frame — forward is the nose, right is starboard. The SDK's own
 * field names do not decide the axes (the V5 adapter documents the inversion), so the adapter owns
 * that translation and this type stays in Lyrebird's vocabulary. [vertical] and [yaw] carry their
 * own reference, so "hold 42 m" and "climb at 2 m/s" are different values rather than the same
 * number under a mode the caller must remember to set.
 */
data class FlightSetpoint(
    /** Forward velocity in metres per second along the nose. */
    val forwardMps: Double = 0.0,
    /** Rightward velocity in metres per second, to starboard. */
    val rightMps: Double = 0.0,
    /** Vertical intent; metres or metres-per-second per [verticalReference]. */
    val vertical: Double = 0.0,
    val verticalReference: VerticalReference = VerticalReference.UP_VELOCITY_MPS,
    /** Yaw intent; degrees or degrees-per-second per [yawReference]. */
    val yaw: Double = 0.0,
    val yawReference: YawReference = YawReference.RATE_CW_DPS,
)

/** Normalized manual stick input: each axis runs -1..1, exactly as a transmitter's sticks do. */
data class ManualStick(
    val leftX: Float = 0f,
    val leftY: Float = 0f,
    val rightX: Float = 0f,
    val rightY: Float = 0f,
)

/**
 * The flight hardware, as the shared controller sees it.
 *
 * Completion semantics: [onResult] reports whether the requested state was reached *or already
 * held*. An SDK failure saying "already enabled/disabled" is not a failure for callers — the
 * control loops re-arm for every command — and it is indistinguishable from a real error at the
 * callback, so adapters report `true` for both. What actually took effect is visible through the
 * live virtual-stick state, which the loops already consult before continuing; adapters should
 * expose or check that rather than pretending the callback is a state read.
 */
interface FlightPrimitives {
    fun takeoff(onResult: (Boolean) -> Unit)

    fun land(onResult: (Boolean) -> Unit)

    fun returnToHome(onResult: (Boolean) -> Unit)

    /** Enter advanced virtual-stick control. */
    fun acquireControl(onResult: (Boolean) -> Unit)

    /** Leave virtual-stick control. */
    fun releaseControl(onResult: (Boolean) -> Unit)

    /** Send one setpoint; meaningful only while control is acquired. */
    fun send(setpoint: FlightSetpoint)

    /** Send normalized manual stick input, for the RC-style path. */
    fun sendManualStick(stick: ManualStick)
}
