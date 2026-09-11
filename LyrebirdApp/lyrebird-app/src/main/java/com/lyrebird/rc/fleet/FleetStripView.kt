package com.lyrebird.rc.fleet

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The fleet, as one narrow column on the Flight Deck.
 *
 * Sized for the screen it actually runs on. An RC Pro is a short landscape display already
 * carrying a video feed, a map, a status row and two columns of widgets, so this gets one column
 * above the map and nothing more: a header line and a row per aircraft, each row a single
 * monospaced line giving the three things a pilot needs about someone else's drone — who it is,
 * how far away, and whether it is above or below.
 *
 * Alignment does the work that labels would otherwise do. Columns are fixed width and the rows
 * are monospaced, so the distances line up and the column can be read at a glance rather than
 * parsed, which is the only way a readout this small earns its space next to a video feed.
 */
internal class FleetStripView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    companion object {
        /** Rows beyond this collapse into a "+N more" line rather than pushing into the video. */
        const val MAX_VISIBLE_ROWS = 4

        private const val TEXT_SIZE_SP = 8f
        private const val HEADER_TEXT_SIZE_SP = 8f
        private const val NAME_WIDTH = 7

        private const val COLOR_WARNING = 0xFFFF5252.toInt()
        private const val COLOR_CAUTION = 0xFFFFB300.toInt()
        private const val COLOR_ADVISORY = 0xFFE0E0E0.toInt()
        private const val COLOR_QUIET = 0xFF9E9E9E.toInt()
        private const val COLOR_STALE = 0xFFBDA35A.toInt()
        private const val COLOR_LOST = 0xFF8D6E63.toInt()
        private const val COLOR_HEADER = 0xFF7FD1FF.toInt()
        private const val COLOR_CONFLICT = 0xFFFF5252.toInt()
        private const val BACKGROUND = 0xB3000000.toInt()
    }

    /** Fired when the operator taps the strip, to open the full fleet page. */
    var onStripClicked: (() -> Unit)? = null

    private val headerView = TextView(context)
    private val rowViews = mutableListOf<TextView>()

    /**
     * What the strip currently says, so an unchanged fleet costs nothing to re-render.
     *
     * Compared as rendered text rather than as the view model. A [FleetView] carries each peer's
     * age in milliseconds, which differs on every tick even when nothing the strip displays has
     * moved, so comparing models would never match and the guard would never fire.
     */
    private var lastSignature: String? = null

    init {
        orientation = VERTICAL
        gravity = Gravity.END
        setBackgroundColor(BACKGROUND)
        setPadding(dp(4), dp(2), dp(4), dp(2))
        isClickable = true
        setOnClickListener { onStripClicked?.invoke() }

        headerView.apply {
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, HEADER_TEXT_SIZE_SP)
            setTextColor(COLOR_HEADER)
            includeFontPadding = false
            maxLines = 1
        }
        addView(headerView)
        visibility = View.GONE
    }

    /**
     * Draw [view], or hide the strip when this device is alone on the network.
     *
     * Nothing is allocated and no view is touched when the rendered text has not changed, because
     * this is called on every beacon tick while the video pipeline is competing for the same main
     * thread.
     */
    fun render(view: FleetView) {
        if (view.isEmpty) {
            visibility = View.GONE
            lastSignature = null
            return
        }

        val header = headerText(view)
        val rows = view.peers.take(MAX_VISIBLE_ROWS)
        val rowTexts = rows.map { rowText(it) to colorFor(it) }
        val overflow = if (view.peerCount > MAX_VISIBLE_ROWS) {
            "+${view.peerCount - MAX_VISIBLE_ROWS} more"
        } else {
            null
        }

        val signature = "$header|$rowTexts|$overflow|${view.hasCriticalConflict}"
        if (signature == lastSignature) return
        lastSignature = signature
        visibility = View.VISIBLE

        headerView.text = header
        headerView.setTextColor(if (view.hasCriticalConflict) COLOR_CONFLICT else COLOR_HEADER)

        ensureRowCount(rowTexts.size + if (overflow != null) 1 else 0)
        rowTexts.forEachIndexed { index, (text, color) ->
            rowViews[index].apply {
                this.text = text
                setTextColor(color)
            }
        }
        overflow?.let {
            rowViews[rowTexts.size].apply {
                text = it
                setTextColor(COLOR_QUIET)
            }
        }
    }

    /**
     * Header line: the fleet size, then whatever most deserves the pilot's attention.
     *
     * A configuration clash outranks traffic here. Traffic changes second by second and the rows
     * below already carry it in colour, while a duplicate system id is a fixed fact about the
     * flight that will not resolve itself and is invisible everywhere else.
     */
    private fun headerText(view: FleetView): String {
        val base = "FLEET ${view.liveCount}/${view.peerCount}"
        val conflict = view.alertingConflicts.firstOrNull()
        if (conflict != null) return "$base !${conflict.shortLabel}"
        val alert = view.alerts.firstOrNull()
        if (alert != null) return "$base !LOST"
        return when (view.highestAdvisory) {
            AdvisoryLevel.WARNING -> "$base !TRAFFIC"
            AdvisoryLevel.CAUTION -> "$base ~TRAFFIC"
            else -> base
        }
    }

    /**
     * One aircraft on one line: state dot, name, range, bearing, relative height.
     *
     * A peer with no fix shows dashes rather than zeroes. Rendering an unknown range as "0m"
     * would be the most alarming possible way to say "I do not know where this aircraft is".
     */
    private fun rowText(peer: FleetPeerView): String {
        val dot = when (peer.liveness) {
            PeerLiveness.LIVE -> "●"
            PeerLiveness.STALE -> "◐"
            PeerLiveness.LOST -> "○"
        }
        val name = peer.displayName.take(NAME_WIDTH).padEnd(NAME_WIDTH)
        val solution = peer.solution
        return if (solution == null) {
            "$dot$name ${"--".padStart(6)}    ${"--".padStart(5)}"
        } else {
            val range = FleetGeo.formatDistance(solution.slantRangeM).padStart(6)
            val bearing = FleetGeo.compassPoint(solution.bearingDeg).padStart(2)
            val relative = FleetGeo.formatRelativeAltitude(solution.verticalSeparationM).padStart(5)
            "$dot$name $range $bearing $relative"
        }
    }

    private fun colorFor(peer: FleetPeerView): Int = when (peer.liveness) {
        PeerLiveness.LOST -> COLOR_LOST
        PeerLiveness.STALE -> COLOR_STALE
        PeerLiveness.LIVE -> when (peer.advisoryLevel) {
            AdvisoryLevel.WARNING -> COLOR_WARNING
            AdvisoryLevel.CAUTION -> COLOR_CAUTION
            AdvisoryLevel.ADVISORY -> COLOR_ADVISORY
            AdvisoryLevel.NONE -> COLOR_QUIET
        }
    }

    /** Rows are reused across renders; only a change in fleet size touches the view hierarchy. */
    private fun ensureRowCount(count: Int) {
        while (rowViews.size < count) {
            val row = TextView(context).apply {
                typeface = Typeface.MONOSPACE
                setTextSize(TypedValue.COMPLEX_UNIT_SP, TEXT_SIZE_SP)
                setTextColor(Color.WHITE)
                includeFontPadding = false
                maxLines = 1
            }
            rowViews += row
            addView(row)
        }
        rowViews.forEachIndexed { index, row ->
            row.visibility = if (index < count) View.VISIBLE else View.GONE
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
