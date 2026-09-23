package com.lyrebird.rc.fleet

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
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
 * above the map and nothing more: a header line and a row per aircraft — this device's own
 * included, first — each row a single monospaced line giving the three things a pilot needs about
 * somebody else's drone: who it is, how far away, and whether it is above or below.
 *
 * What the lines say is [FleetStripText]'s business. All that is decided here is which colour a
 * line's tone means.
 */
internal class FleetStripView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0,
    ) : LinearLayout(context, attrs, defStyleAttr) {
        companion object {
            private const val TEXT_SIZE_SP = 8f
            private const val HEADER_TEXT_SIZE_SP = 8f

            private const val COLOR_SELF = 0xFFFFFFFF.toInt()
            private const val COLOR_WARNING = 0xFFFF5252.toInt()
            private const val COLOR_CAUTION = 0xFFFFB300.toInt()
            private const val COLOR_ADVISORY = 0xFFE0E0E0.toInt()
            private const val COLOR_QUIET = 0xFF9E9E9E.toInt()
            private const val COLOR_STALE = 0xFFBDA35A.toInt()
            private const val COLOR_LOST = 0xFF8D6E63.toInt()
            private const val COLOR_HEADER = 0xFF7FD1FF.toInt()
            private const val BACKGROUND = 0xB3000000.toInt()

            private fun colorFor(tone: FleetStripText.Tone): Int =
                when (tone) {
                    FleetStripText.Tone.SELF -> COLOR_SELF
                    FleetStripText.Tone.SELF_NO_FIX -> COLOR_STALE
                    FleetStripText.Tone.HEADER -> COLOR_HEADER
                    FleetStripText.Tone.HEADER_WARNING -> COLOR_CAUTION
                    FleetStripText.Tone.HEADER_CRITICAL -> COLOR_WARNING
                    FleetStripText.Tone.QUIET -> COLOR_QUIET
                    FleetStripText.Tone.ADVISORY -> COLOR_ADVISORY
                    FleetStripText.Tone.CAUTION -> COLOR_CAUTION
                    FleetStripText.Tone.WARNING -> COLOR_WARNING
                    FleetStripText.Tone.STALE -> COLOR_STALE
                    FleetStripText.Tone.LOST -> COLOR_LOST
                }
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
         * Draw [view], or hide the strip while this device is the only member of its fleet.
         *
         * The strip exists to say what the *other* aircraft are doing. With none of them, a header
         * and one row about this device would take video space to say nothing at all, and the map
         * is where an operator looks when they are alone.
         *
         * Nothing is allocated and no view is touched when the rendered text has not changed,
         * because this is called on every beacon tick while the video pipeline is competing for the
         * same main thread.
         */
        fun render(view: FleetView) {
            if (view.isEmpty) {
                visibility = View.GONE
                lastSignature = null
                return
            }

            val header = FleetStripText.header(view)
            val lines = FleetStripText.rows(view)
            val overflow = FleetStripText.overflow(view)

            val signature = "$header|$lines|$overflow"
            // A hidden strip has to be shown again even when its text has not changed: expanding
            // the map hides it, and collapsing brings the same fleet back. Skipping on the
            // signature alone left it invisible until some peer happened to change something.
            if (signature == lastSignature && visibility == View.VISIBLE) return
            lastSignature = signature
            visibility = View.VISIBLE

            headerView.text = header.text
            headerView.setTextColor(colorFor(header.tone))

            ensureRowCount(lines.size + if (overflow != null) 1 else 0)
            lines.forEachIndexed { index, line -> applyLine(rowViews[index], line) }
            overflow?.let { extra ->
                rowViews[lines.size].apply {
                    text = extra.text
                    setTextColor(colorFor(extra.tone))
                }
            }
        }

        /**
         * A row whose name has an identity colour is drawn as two spans: the name in the colour the
         * aircraft is drawn in on the map, the values in the colour its state deserves.
         */
        private fun applyLine(
            row: TextView,
            line: FleetStripText.Line,
        ) {
            val nameColour = line.nameColour
            if (nameColour == null) {
                row.text = line.text
                row.setTextColor(colorFor(line.tone))
                return
            }
            val span = SpannableString(line.text)
            span.setSpan(ForegroundColorSpan(nameColour), 0, line.nameEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            span.setSpan(
                ForegroundColorSpan(colorFor(line.tone)),
                line.nameEnd,
                line.text.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            row.text = span
        }

        /** Rows are reused across renders; only a change in fleet size touches the view hierarchy. */
        private fun ensureRowCount(count: Int) {
            while (rowViews.size < count) {
                val row =
                    TextView(context).apply {
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

        private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    }
