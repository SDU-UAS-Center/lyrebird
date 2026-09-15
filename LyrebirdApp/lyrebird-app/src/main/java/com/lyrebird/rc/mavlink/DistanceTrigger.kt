package com.lyrebird.rc.mavlink

/**
 * Accumulates ground covered and says when a distance-triggered capture is due.
 *
 * This is the arithmetic behind MAVLink's `DO_SET_CAM_TRIGG_DIST`: the plan names a distance, and
 * the aircraft photographs every time it has travelled that far. A survey flies a grid and gets a
 * photo per cell without the plan carrying a photo item per frame.
 *
 * Deliberately free of position types and distance maths: the caller measures the distance
 * between two fixes — with the same calculation navigation uses, so a photo and a leg's arrival
 * can never disagree about where the aircraft was — and this class only decides whether that
 * distance completed an interval. Keeping the two apart is what makes the cadence testable
 * without an aircraft, a controller, or the DJI SDK.
 *
 * Not thread-safe: it is advanced by the mission's own sequencing thread.
 */
internal class DistanceTrigger(private val intervalM: Double) {

    init {
        require(intervalM > 0.0) { "A capture interval must be positive, got $intervalM" }
    }

    /** Metres travelled since the last capture came due. */
    private var travelledM = 0.0

    /** Metres still to cover before the next capture, for logging and tests. */
    val remainingM: Double get() = intervalM - travelledM

    /**
     * Add ground covered since the previous fix.
     *
     * Returns true when a capture is now due. The interval is then subtracted rather than the
     * accumulator being zeroed, so the small overshoot from sampling a continuous path at a fixed
     * poll rate carries into the next interval instead of being lost — otherwise every interval
     * would come out slightly long, and a long survey would drift behind its grid.
     *
     * A single large step (a resumed fix, a GPS jump) releases at most one capture: a survey has
     * no use for a burst of back-to-back shutters, and one photo is the honest answer for one
     * interval that can be shown to have been completed.
     */
    fun addTravelled(distanceM: Double): Boolean {
        if (!distanceM.isFinite() || distanceM <= 0.0) return false
        travelledM += distanceM
        if (travelledM < intervalM) return false
        travelledM -= intervalM
        return true
    }
}
