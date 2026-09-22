package com.lyrebird.rc.fleet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Marker layout: the room a turning chevron needs, and where its name may start.
 *
 * The rule this pins is the one the map kept getting wrong. A chevron rotates with the aircraft's
 * heading, so its reach is the diagonal of its box rather than either of its dimensions, and a name
 * anchored inside that reach is struck through whenever the aircraft points at it.
 */
class FleetIconLayoutTest {
    @Test
    fun `the circle that contains an arrow is the bound the heading-aware reach stays under`() {
        // The compact peer chevron: 30 px wide, 34 px tall.
        assertEquals(23, FleetIconLayout.markerReachPx(halfWidthPx = 15f, heightPx = 34f))
        for (heading in 0 until 360 step 5) {
            val reach = FleetIconLayout.reachDownPx(15f, 34f, heading.toDouble())
            assertTrue(
                "heading $heading reached $reach, past the safe bound",
                reach <= FleetIconLayout.markerReachPx(15f, 34f),
            )
        }
    }

    @Test
    fun `nose north the tail hangs lowest, and nose east or west the arrow reaches least`() {
        // Nose north: the wings are level and the lowest point is the tail line, half the height.
        assertEquals(17, FleetIconLayout.reachDownPx(15f, 34f, 0.0))
        // Nose east: the wings are vertical and the deepest point is a wing tip, half the width.
        assertEquals(15, FleetIconLayout.reachDownPx(15f, 34f, 90.0))
        // The worst case is a wing pointing straight down, and that is the full diagonal.
        assertEquals(23, FleetIconLayout.reachDownPx(15f, 34f, 41.0))
    }

    /**
     * The complaint this exists for: a name cleared against the full diagonal sits about a
     * centimetre below the arrow at every heading but the two or three that need it.
     */
    @Test
    fun `a name sits closer than the safe circle unless the heading demands otherwise`() {
        val noseUp = FleetIconLayout.labelOffsetPx(15f, 34f, headingDeg = 0.0, clearancePx = 2)
        val noseEast = FleetIconLayout.labelOffsetPx(15f, 34f, headingDeg = 90.0, clearancePx = 2)
        val worst = FleetIconLayout.labelOffsetPx(15f, 34f, headingDeg = 41.0, clearancePx = 2)
        assertEquals(20, noseUp)
        assertEquals(20, noseEast)
        assertEquals(28, worst)
        assertTrue("nose up, the name is inside where the safe circle would have put it", noseUp < 25)
    }

    @Test
    fun `the offset never sits inside what the arrow reaches`() {
        for (heading in 0 until 360 step 3) {
            val reach = FleetIconLayout.reachDownPx(32f, 74f, heading.toDouble())
            val offset = FleetIconLayout.labelOffsetPx(32f, 74f, heading.toDouble(), clearancePx = 4)
            assertTrue("heading $heading put the name at $offset over a reach of $reach", offset >= reach)
        }
    }

    @Test
    fun `the name moves in steps, so it does not twitch with every degree of yaw`() {
        val offsets = (0 until 360 step 3).map { FleetIconLayout.labelOffsetPx(15f, 34f, it.toDouble(), 2) }
        assertTrue("a small turn must not move the name", offsets.distinct().size < offsets.size / 2)
        assertTrue(
            "the name only ever moves in whole steps",
            offsets.all { it % FleetIconLayout.LABEL_STEP_PX == 0 },
        )
    }
}
