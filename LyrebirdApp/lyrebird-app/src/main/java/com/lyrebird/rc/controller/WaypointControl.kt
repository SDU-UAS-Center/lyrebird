package com.lyrebird.rc.controller

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

internal object WaypointControl {
    data class Acceptance(
        val distanceMeters: Double,
        val yawDegrees: Double,
        val altitudeMeters: Double
    )

    data class BodyVelocity(
        val forwardSpeed: Double,
        val lateralSpeed: Double
    )

    data class CooldownPlan(
        val waypointReached: Boolean,
        val reachedAtMs: Long,
        val stopAtWaypoint: Boolean
    )

    /**
     * The speed to command this tick: the distance PID's ask, held under the leg's ceiling and
     * under the acceleration slew limit.
     *
     * The result is floored at zero because every ceiling here is a *limit*, and a limit that
     * has gone negative is a bad input rather than an instruction to reverse. MAVLink's
     * `MAV_CMD_DO_REPOSITION` carries exactly such an input: its ground-speed parameter is
     * documented as "less than 0 (-1) for default", and QGroundControl sends -1. Reaching this
     * function it became a ceiling of -1 m/s, so the aircraft flew away from the waypoint at
     * walking pace and never arrived — the along-track term stays positive while it retreats, so
     * nothing ever reversed it.
     *
     * That specific sentinel is resolved where it is understood, at the MAVLink boundary. This
     * floor is the backstop for every other route to the same state, because no caller of this
     * function has ever meant "fly backwards": the two waypoint controllers each decide direction
     * themselves, after this, from the along-track sign or from the body-frame projection.
     */
    fun limitedSpeed(
        pidSpeed: Double,
        targetMaxSpeed: Double,
        lastCommandedSpeed: Double,
        maxSpeedStep: Double
    ): Double {
        return pidSpeed
            .coerceAtMost(targetMaxSpeed)
            .coerceAtMost(lastCommandedSpeed + maxSpeedStep)
            .coerceAtLeast(0.0)
    }

    fun bodyVelocity(targetSpeed: Double, movementDirection: Double, currentYaw: Double): BodyVelocity {
        val movementDirectionRelative = normalizeAngle(movementDirection - currentYaw)
        return BodyVelocity(
            forwardSpeed = targetSpeed * cos(Math.toRadians(movementDirectionRelative)),
            lateralSpeed = targetSpeed * sin(Math.toRadians(movementDirectionRelative))
        )
    }

    fun reachedTarget(distance: Double, yawError: Double, altitudeError: Double, acceptance: Acceptance): Boolean {
        return distance < acceptance.distanceMeters &&
            abs(yawError) < acceptance.yawDegrees &&
            abs(altitudeError) < acceptance.altitudeMeters
    }

    fun cooldownPlan(
        targetReached: Boolean,
        wasWaypointReached: Boolean,
        reachedAtMs: Long,
        nowMs: Long,
        holdCooldownMs: Long,
        /**
         * How long the aircraft must stay inside the acceptance box before it counts as arrived.
         *
         * Zero keeps the original behaviour, which is what a pass-through leg wants. Anything
         * else filters the failure this exists for: the box is half a metre and GPS noise is a
         * good fraction of that, so a single sample can place the aircraft inside while it is
         * genuinely a metre out. Without a dwell that one sample is permanent, because the latch
         * does not fall again once set.
         */
        dwellMs: Long = 0L
    ): CooldownPlan {
        if (!targetReached) {
            return CooldownPlan(
                waypointReached = wasWaypointReached,
                // Zero re-arms the dwell: leaving the box means the next entry starts counting
                // again, so a run of noisy in-box samples cannot accumulate into an arrival.
                reachedAtMs = 0L,
                stopAtWaypoint = false
            )
        }

        val insideSinceMs = if (reachedAtMs != 0L) reachedAtMs else nowMs
        val dwelledLongEnough = nowMs - insideSinceMs >= dwellMs
        // Sticky once earned, and only once earned. A consumer polling at 2 Hz would miss the
        // arrival entirely if the latch fell again the moment the aircraft drifted a centimetre.
        val reached = wasWaypointReached || dwelledLongEnough
        return CooldownPlan(
            waypointReached = reached,
            reachedAtMs = insideSinceMs,
            stopAtWaypoint = reached && nowMs - insideSinceMs >= dwellMs + holdCooldownMs
        )
    }

    private fun normalizeAngle(angle: Double): Double {
        var adjustedAngle = angle % 360.0
        if (adjustedAngle > 180.0) adjustedAngle -= 360.0
        if (adjustedAngle < -180.0) adjustedAngle += 360.0
        return adjustedAngle
    }
}
