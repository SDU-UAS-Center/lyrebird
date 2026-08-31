package com.lyrebird.rc.controller

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When a waypoint counts as reached.
 *
 * The acceptance box is half a metre and GPS noise is a good fraction of that, so the question
 * these answer is not "is the aircraft inside the box" but "has it been inside long enough that
 * one bad fix cannot be mistaken for an arrival".
 */
class WaypointArrivalTest {

    @Test
    fun withNoDwellArrivalIsImmediate() {
        // What a pass-through leg wants: the plan asked to fly through, not to wait.
        val plan = WaypointControl.cooldownPlan(
            targetReached = true,
            wasWaypointReached = false,
            reachedAtMs = 0L,
            nowMs = 1_000L,
            holdCooldownMs = 200L,
            dwellMs = 0L
        )
        assertTrue(plan.waypointReached)
    }

    @Test
    fun asingleNoisySampleInsideTheBoxIsNotAnArrival() {
        val first = WaypointControl.cooldownPlan(
            targetReached = true,
            wasWaypointReached = false,
            reachedAtMs = 0L,
            nowMs = 1_000L,
            holdCooldownMs = 200L,
            dwellMs = 300L
        )
        assertFalse("one sample must not latch a 0.5 m box", first.waypointReached)
    }

    @Test
    fun stayingInsideForTheDwellIsAnArrival() {
        val entered = WaypointControl.cooldownPlan(
            targetReached = true, wasWaypointReached = false,
            reachedAtMs = 0L, nowMs = 1_000L, holdCooldownMs = 200L, dwellMs = 300L
        )
        val later = WaypointControl.cooldownPlan(
            targetReached = true, wasWaypointReached = false,
            reachedAtMs = entered.reachedAtMs, nowMs = 1_400L, holdCooldownMs = 200L, dwellMs = 300L
        )
        assertTrue(later.waypointReached)
    }

    @Test
    fun leavingTheBoxRearmsTheDwell() {
        val entered = WaypointControl.cooldownPlan(
            targetReached = true, wasWaypointReached = false,
            reachedAtMs = 0L, nowMs = 1_000L, holdCooldownMs = 200L, dwellMs = 300L
        )
        val left = WaypointControl.cooldownPlan(
            targetReached = false, wasWaypointReached = false,
            reachedAtMs = entered.reachedAtMs, nowMs = 1_100L, holdCooldownMs = 200L, dwellMs = 300L
        )
        // Zeroed, so a run of noisy in-box samples either side of an excursion cannot add up.
        assertTrue(left.reachedAtMs == 0L)

        val reentered = WaypointControl.cooldownPlan(
            targetReached = true, wasWaypointReached = false,
            reachedAtMs = left.reachedAtMs, nowMs = 1_200L, holdCooldownMs = 200L, dwellMs = 300L
        )
        assertFalse("the dwell must start again, not resume", reentered.waypointReached)
    }

    @Test
    fun anEarnedArrivalDoesNotFallAgain() {
        // A consumer polling at 2 Hz would miss the window entirely if it did.
        val drifted = WaypointControl.cooldownPlan(
            targetReached = false, wasWaypointReached = true,
            reachedAtMs = 1_000L, nowMs = 5_000L, holdCooldownMs = 200L, dwellMs = 300L
        )
        assertTrue(drifted.waypointReached)
    }

    @Test
    fun theLoopOnlyStopsAfterTheDwellAndTheCooldown() {
        val justArrived = WaypointControl.cooldownPlan(
            targetReached = true, wasWaypointReached = false,
            reachedAtMs = 1_000L, nowMs = 1_300L, holdCooldownMs = 200L, dwellMs = 300L
        )
        assertTrue(justArrived.waypointReached)
        assertFalse("the hot-swap window must stay open", justArrived.stopAtWaypoint)

        val settled = WaypointControl.cooldownPlan(
            targetReached = true, wasWaypointReached = true,
            reachedAtMs = 1_000L, nowMs = 1_600L, holdCooldownMs = 200L, dwellMs = 300L
        )
        assertTrue(settled.stopAtWaypoint)
    }
}
