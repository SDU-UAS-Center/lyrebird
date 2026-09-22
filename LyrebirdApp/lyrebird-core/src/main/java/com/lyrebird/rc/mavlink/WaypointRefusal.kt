package com.lyrebird.rc.mavlink

/**
 * Why a waypoint command was refused before any leg was flown.
 *
 * The seq of a refused command is still published — the ground station's polling and the mission
 * sequencer both key on seq — but no leg is flown and no reach latch arms for it. This type lives
 * in core because both wire surfaces and (later) every SDK adapter must agree on what a refusal
 * means; it previously lived inside the V5 controller.
 */
enum class WaypointRejection {
    /** Nothing refused it; the command may fly. */
    NONE,

    /**
     * The leg starts inside the bearing arc the obstacle guard blocked after its most recent
     * brake. A safe bearing is not rejected: the aircraft can be moved away without any operator
     * intervention. Only the direction known to hold the obstacle is closed, and only while the
     * lockout lasts.
     */
    OBSTACLE_BLOCKED,
}

/** The most recent pre-flight waypoint refusal: the seq it was published under and why. */
data class WaypointRefusal(
    val seq: Long,
    val reason: WaypointRejection,
)
