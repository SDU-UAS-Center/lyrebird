package com.lyrebird.rc.perception

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The blocked arc is the difference between "the aircraft stopped itself" and "the aircraft is
 * stuck": a retry that names a safe bearing must fly, and a retry that names the hazard must be
 * refused. Both behaviour lines are tested here, against the pure class only.
 */
class BlockedArcTest {

    private companion object {
        const val NOW = 1_000_000L
    }

    private fun horizontalBrakeAt(bearingDeg: Double) = BrakeDecision(
        shouldBrake = true,
        reason = BrakeReason.HORIZONTAL,
        clearanceM = 4.0,
        requiredM = 12.0,
        bearingFromNoseDeg = bearingDeg
    )

    @Test
    fun `a horizontal brake produces an arc centred on the measured obstacle`() {
        val arc = BlockedArc.fromBrake(horizontalBrakeAt(bearingDeg = 25.0), NOW)
        assertEquals(25.0, arc!!.bearingFromNoseDeg, 0.001)
        assertEquals(BlockedArc.LOCKOUT_MS, arc.expiresAtElapsedMs - NOW)
    }

    @Test
    fun `a vertical brake blocks nothing`() {
        val arc = BlockedArc.fromBrake(
            BrakeDecision(
                shouldBrake = true,
                reason = BrakeReason.UPWARD,
                clearanceM = 4.0,
                requiredM = 10.0,
                bearingFromNoseDeg = Double.NaN
            ),
            NOW
        )
        assertNull(arc)
    }

    @Test
    fun `a clear decision blocks nothing`() {
        assertNull(BlockedArc.fromBrake(BrakeDecision.CLEAR, NOW))
    }

    @Test
    fun `the arc blocks the bearing it was recorded on`() {
        val arc = BlockedArc.fromBrake(horizontalBrakeAt(bearingDeg = 30.0), NOW)!!
        assertTrue(arc.contains(30.0, NOW))
        assertTrue(arc.contains(40.0, NOW))
        assertTrue(arc.contains(20.0, NOW))
    }

    @Test
    fun `a bearing away from the obstacle is free to fly`() {
        val arc = BlockedArc.fromBrake(horizontalBrakeAt(bearingDeg = 0.0), NOW)!!
        assertFalse("a safe direction must not be refused", arc.contains(90.0, NOW))
        assertFalse(arc.contains(180.0, NOW))
        assertFalse(arc.contains(270.0, NOW))
    }

    @Test
    fun `the arc wraps through north`() {
        // Obstacle at 350 degrees: 5 degrees on the other side of north is inside the arc, and the
        // separation arithmetic has to wrap through zero rather than read 345 degrees of offset.
        val arc = BlockedArc.fromBrake(horizontalBrakeAt(bearingDeg = 350.0), NOW)!!
        assertTrue(arc.contains(5.0, NOW))
        assertTrue(arc.contains(355.0, NOW))
        assertFalse(arc.contains(30.0, NOW))
    }

    @Test
    fun `the arc expires and stops refusing`() {
        val arc = BlockedArc.fromBrake(horizontalBrakeAt(bearingDeg = 0.0), NOW)!!
        assertFalse("a stale arc must not block", arc.contains(0.0, NOW + BlockedArc.LOCKOUT_MS))
    }

    @Test
    fun `the lockout outlives the brake latch`() {
        assertTrue(BlockedArc.LOCKOUT_MS > ObstacleGuard.BRAKE_LATCH_MS)
    }
}
