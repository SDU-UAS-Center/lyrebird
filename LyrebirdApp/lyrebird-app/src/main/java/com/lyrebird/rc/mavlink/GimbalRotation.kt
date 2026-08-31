package com.lyrebird.rc.mavlink

/**
 * SDK-free description of a gimbal rotation, so the HTTP surface and the MAVLink command path can
 * express the same request without importing DJI types.
 *
 * Mirrors DJI's `GimbalAngleRotation`, omitting the three fields every Lyrebird call site holds
 * constant (0.1s duration, no joint reference, no timeout). Those stay in the activity, which is
 * where the SDK types live.
 */
internal enum class GimbalRotationMode { ABSOLUTE, RELATIVE }

internal data class GimbalRotation(
    val mode: GimbalRotationMode,
    val pitchDeg: Double,
    val rollDeg: Double,
    val yawDeg: Double,
    /** When true the matching axis keeps its current angle rather than taking the given value. */
    val pitchIgnored: Boolean,
    val rollIgnored: Boolean,
    val yawIgnored: Boolean
)
