package com.lyrebird.rc.controller

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WaypointControlTest {
    @Test
    fun limitedSpeedRespectsTargetMaxSpeedAndAccelerationStep() {
        assertEquals(2.0, WaypointControl.limitedSpeed(5.0, 2.0, 0.0, 5.0), 0.0001)
        assertEquals(1.5, WaypointControl.limitedSpeed(5.0, 5.0, 1.0, 0.5), 0.0001)
        assertEquals(3.0, WaypointControl.limitedSpeed(3.0, 5.0, 3.0, 0.5), 0.0001)
    }

    @Test
    fun limitedSpeedNeverCommandsReverseFromANegativeCeiling() {
        // MAV_CMD_DO_REPOSITION's ground speed is "less than 0 (-1) for default", and
        // QGroundControl sends -1. It arrived here as the ceiling, and a ceiling of -1 m/s used
        // to come straight back out: the aircraft flew away from the waypoint at 1 m/s and never
        // arrived, because the along-track term stays positive the whole time it retreats.
        assertEquals(0.0, WaypointControl.limitedSpeed(5.0, -1.0, 0.0, 5.0), 0.0001)
        assertEquals(0.0, WaypointControl.limitedSpeed(5.0, -1.0, 3.0, 5.0), 0.0001)
        // The slew limit can also go negative while decelerating from a standstill.
        assertEquals(0.0, WaypointControl.limitedSpeed(5.0, 5.0, -2.0, 0.5), 0.0001)
    }

    @Test
    fun bodyVelocityConvertsWorldDirectionToDroneRelativeAxes() {
        val forward = WaypointControl.bodyVelocity(targetSpeed = 2.0, movementDirection = 90.0, currentYaw = 90.0)
        val lateral = WaypointControl.bodyVelocity(targetSpeed = 2.0, movementDirection = 180.0, currentYaw = 90.0)

        assertEquals(2.0, forward.forwardSpeed, 0.0001)
        assertEquals(0.0, forward.lateralSpeed, 0.0001)
        assertEquals(0.0, lateral.forwardSpeed, 0.0001)
        assertEquals(2.0, lateral.lateralSpeed, 0.0001)
    }

    @Test
    fun reachedTargetRequiresDistanceYawAndAltitudeWithinAcceptance() {
        val acceptance = WaypointControl.Acceptance(
            distanceMeters = 1.5,
            yawDegrees = 4.0,
            altitudeMeters = 0.5
        )

        assertTrue(WaypointControl.reachedTarget(1.0, 3.0, 0.2, acceptance))
        assertFalse(WaypointControl.reachedTarget(2.0, 3.0, 0.2, acceptance))
        assertFalse(WaypointControl.reachedTarget(1.0, 5.0, 0.2, acceptance))
        assertFalse(WaypointControl.reachedTarget(1.0, 3.0, 0.6, acceptance))
    }

    @Test
    fun cooldownPlanStartsHoldWhenWaypointIsFirstReached() {
        val plan = WaypointControl.cooldownPlan(
            targetReached = true,
            wasWaypointReached = false,
            reachedAtMs = 0L,
            nowMs = 1_000L,
            holdCooldownMs = 200L
        )

        assertTrue(plan.waypointReached)
        assertEquals(1_000L, plan.reachedAtMs)
        assertFalse(plan.stopAtWaypoint)
    }

    @Test
    fun cooldownPlanStopsAfterHoldExpires() {
        val plan = WaypointControl.cooldownPlan(
            targetReached = true,
            wasWaypointReached = true,
            reachedAtMs = 1_000L,
            nowMs = 1_250L,
            holdCooldownMs = 200L
        )

        assertTrue(plan.waypointReached)
        assertEquals(1_000L, plan.reachedAtMs)
        assertTrue(plan.stopAtWaypoint)
    }

    @Test
    fun cooldownPlanResetsHoldWhenWaypointIsNotReached() {
        val plan = WaypointControl.cooldownPlan(
            targetReached = false,
            wasWaypointReached = true,
            reachedAtMs = 1_000L,
            nowMs = 1_250L,
            holdCooldownMs = 200L
        )

        assertTrue(plan.waypointReached)
        assertEquals(0L, plan.reachedAtMs)
        assertFalse(plan.stopAtWaypoint)
    }
}
