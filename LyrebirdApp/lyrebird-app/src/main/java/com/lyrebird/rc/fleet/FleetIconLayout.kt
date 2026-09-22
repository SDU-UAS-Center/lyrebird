package com.lyrebird.rc.fleet

import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Where a map marker and its name go, as arithmetic.
 *
 * The chevron turns with the aircraft's heading, so a name placed too close is struck through by the
 * arrow whenever the heading happens to point that way - which is what "the label is under the
 * arrow" means in practice, and it comes and goes with the heading.
 *
 * The obvious answer, the circle that contains the arrow at any heading, is safe and always too far:
 * it is the arrow's diagonal, which only applies at the handful of headings where a wing points
 * straight down. What the arrow reaches downwards is a property of the heading - its wings swing in
 * and out of the way as it turns - so that is what is computed, at the heading the aircraft is
 * actually on.
 *
 * Kept out of the view for the usual reason: this is a rule about layout, and a rule is worth a
 * test.
 */
internal object FleetIconLayout {
    /**
     * How far below the aircraft the chevron reaches at this heading, in pixels.
     *
     * The chevron is a triangle with a notch in its tail rather than a box. Pointing north the tail
     * hangs lowest, pointing east or west it is the nose and a wing, and at about forty-five degrees
     * a wing is at its lowest and the arrow reaches its full diagonal. The outline's four points are
     * the whole shape as far as this is concerned.
     */
    fun reachDownPx(
        halfWidthPx: Float,
        heightPx: Float,
        headingDeg: Double,
    ): Int {
        val heading = Math.toRadians(headingDeg)
        // Unrotated, the icon points north: nose at the top, two tail corners and the notch below.
        val outline =
            listOf(
                0f to -heightPx / 2f,
                halfWidthPx to heightPx / 2f,
                0f to heightPx / 4f,
                -halfWidthPx to heightPx / 2f,
            )
        // Turning a point clockwise by phi drops it by x·sin(phi) + y·cos(phi).
        val deepest = outline.maxOf { (x, y) -> x * sin(heading) + y * cos(heading) }
        // Nose east, the wing tip is exactly half the width below the aircraft, and the sine of a
        // right angle comes back a hair over one: without this the name would be a pixel lower for
        // no reason at four headings out of the compass.
        return ceil(deepest - GEOMETRY_EPSILON).toInt().coerceAtLeast(0)
    }

    /** Slack for the last bit of a double, so an exact boundary is not rounded up over. */
    private const val GEOMETRY_EPSILON = 1e-6

    /**
     * The circle that contains the arrow at any heading: safe everywhere, close nowhere.
     *
     * Kept as the bound [reachDownPx] must never exceed, which is what makes it testable against a
     * known-safe answer.
     */
    fun markerReachPx(
        halfWidthPx: Float,
        heightPx: Float,
    ): Int = ceil(hypot(halfWidthPx, heightPx / 2f)).toInt()

    /**
     * How far the name moves in one step.
     *
     * The name has to move when the arrow turns into it, and it should be the only thing on the map
     * that does not twitch: quantising the position means a label is redrawn and moved when the
     * arrow has turned far enough to matter, rather than every time the nose does.
     */
    const val LABEL_STEP_PX = 4

    /** Where a name starts, measured down from the aircraft: outside the arrow, plus some air. */
    fun labelOffsetPx(
        halfWidthPx: Float,
        heightPx: Float,
        headingDeg: Double,
        clearancePx: Int,
    ): Int {
        val needed = reachDownPx(halfWidthPx, heightPx, headingDeg) + clearancePx
        return ceil(needed.toDouble() / LABEL_STEP_PX).toInt() * LABEL_STEP_PX
    }
}
