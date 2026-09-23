package com.lyrebird.rc.controller

/**
 * The per-tick timestep of a control loop, measured and clamped.
 *
 * The loops run on the main Looper, whose cadence drifts under load, so each tick measures how
 * long it actually has been since the last one rather than assuming the nominal interval — and
 * clamps the result so a stalled thread cannot inject a huge dt spike into a PID
 * derivative/integral or the acceleration limiter. The first tick has nothing to measure from and
 * uses the nominal interval.
 *
 * Each loop carries its own ceiling: the waypoint controllers brake on a half-second spike, while
 * the trajectory controller tolerates two seconds because its send path re-sends the last setpoint
 * at a higher rate, so a long gap between control ticks cannot leave the sticks unrefreshed. The
 * floor is shared and exists so a re-tick in the same millisecond cannot divide by nothing.
 */
internal class LoopTiming(
    private val nominalIntervalMs: Long,
    private val maxDtSec: Double,
    private val minDtSec: Double = 0.02,
) {
    private var lastTickMs = 0L

    /**
     * The dt to use for a tick at [nowMs], which also becomes the new baseline for the next
     * measurement. A zero baseline means no tick has happened yet.
     */
    fun nextDtSec(nowMs: Long): Double {
        val dtSec =
            if (lastTickMs == 0L) {
                nominalIntervalMs / 1000.0
            } else {
                ((nowMs - lastTickMs) / 1000.0).coerceIn(minDtSec, maxDtSec)
            }
        lastTickMs = nowMs
        return dtSec
    }

    /** Elapsed time since the last tick, or null before the first one — [nowMs] included. */
    fun elapsedSinceLastTickMs(nowMs: Long): Long? = if (lastTickMs == 0L) null else nowMs - lastTickMs
}
