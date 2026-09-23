package com.lyrebird.rc.fleet

/**
 * The fleet strip, as text.
 *
 * The view's job is to put these lines on screen; deciding what they say lives here, where it can
 * be tested without a device. Every mistake this readout has made so far has been a wording
 * mistake rather than a layout one - a restart announced as a loss, a row that said nothing when a
 * GPS fix came and went, a list that omitted the aircraft reading it - and none of them were
 * visible to a unit test while the rules lived inside a `LinearLayout`.
 *
 * Alignment does the work that labels would otherwise do. Columns are fixed width and the rows are
 * monospaced, so the distances line up and the column can be read at a glance rather than parsed,
 * which is the only way a readout this small earns its space next to a video feed.
 */
internal object FleetStripText {
    /**
     * Rows beyond this collapse into a "+N more" line rather than pushing into the video.
     *
     * This aircraft's own row is one of them, and it never folds away.
     */
    const val MAX_VISIBLE_ROWS = 4

    private const val NAME_WIDTH = 7
    private const val RANGE_WIDTH = 6
    private const val BEARING_WIDTH = 2
    private const val RELATIVE_WIDTH = 5
    private const val AGE_WIDTH = 3
    private const val DASH = "--"
    private const val MILLIS_PER_SECOND = 1_000L
    private const val MAX_AGE_SECONDS = 999L

    /** This aircraft's own marker: not a peer, so not one of the peer dots. */
    const val SELF_MARKER = "◆"

    /**
     * What a line means, so the view can pick a colour without knowing any of the rules below.
     *
     * Kept apart from the colour itself so that this file stays free of Android types, and so that
     * "what should this say" is answered in exactly one place.
     */
    enum class Tone {
        SELF,
        SELF_NO_FIX,
        ADVISORY,
        CAUTION,
        WARNING,
        STALE,
        LOST,
        QUIET,
        HEADER,
        HEADER_WARNING,
        HEADER_CRITICAL,
    }

    data class Line(
        val text: String,
        val tone: Tone,
        /**
         * The colour of the aircraft's own name, when this line names one.
         *
         * Who a row is about and how that aircraft is doing are two different things that used to
         * share a single colour, which meant a fleet with two warnings in it was a fleet with two
         * identical rows. The name now carries identity - the same colour as the chevron on the map
         * - and [tone] carries state.
         */
        val nameColour: Int? = null,
        /** How much of [text] is the name, so the view knows where the name's colour stops. */
        val nameEnd: Int = 0,
    )

    /**
     * The header line: the fleet size, then whatever most deserves the pilot's attention.
     *
     * The count includes this aircraft, because the list below includes it: a header reading
     * "FLEET 2/2" above three rows is a header that has to be explained.
     *
     * A configuration clash outranks everything here. Traffic and alerts change second by second
     * and the rows below already carry them in colour and in words, while a duplicate MAVLink id
     * is a fixed fact about the flight that will not resolve itself and is invisible everywhere
     * else.
     */
    fun header(view: FleetView): Line {
        val base = "FLEET ${view.liveMemberCount}/${view.memberCount}"
        val conflict = view.alertingConflicts.firstOrNull()
        if (conflict != null) return Line("$base !${conflict.shortLabel}", Tone.HEADER_CRITICAL)

        val alert = view.alerts.firstOrNull()
        if (alert != null) return Line("$base ${alertMarker(alert)}", toneFor(alert))

        return when (view.highestAdvisory) {
            AdvisoryLevel.WARNING -> Line("$base !TRAFFIC", Tone.HEADER_WARNING)
            AdvisoryLevel.CAUTION -> Line("$base ~TRAFFIC", Tone.HEADER_WARNING)
            else -> Line(base, Tone.HEADER)
        }
    }

    /**
     * One or two words for the alert the header is carrying.
     *
     * The word has to be the one that is true. Every alert used to be announced as "!LOST", so a
     * fleet that was live and healthy sat under a loss warning whenever any peer's app had
     * restarted - which is exactly how a header teaches its reader to stop looking at it.
     */
    private fun alertMarker(alert: FleetAlert): String =
        when (alert.kind) {
            FleetAlertKind.PEER_SILENT_WHILE_FLYING ->
                if (alert.severity == ConflictSeverity.CRITICAL) "!LOST" else "~LOST"
            FleetAlertKind.PEER_APP_RESTARTED -> "!RESTART"
        }

    private fun toneFor(alert: FleetAlert): Tone =
        if (alert.severity == ConflictSeverity.CRITICAL) Tone.HEADER_CRITICAL else Tone.HEADER_WARNING

    /** This aircraft first, then the peers; the list never begins with somebody else. */
    fun rows(view: FleetView): List<Line> {
        val colours = view.memberColours
        val self =
            view.own?.let { listOf(ownLine(it, colours[it.deviceId])) }.orEmpty()
        return self +
            view.peers
                .take(MAX_VISIBLE_ROWS - self.size)
                .map { peer -> peerLine(peer, colours[peer.peer.beacon.deviceId]) }
    }

    /** The peers the strip has no room for, or null when it showed all of them. */
    fun overflow(view: FleetView): Line? {
        val reserved = if (view.own == null) 0 else 1
        val hidden = view.peerCount - view.peers.take(MAX_VISIBLE_ROWS - reserved).size
        return if (hidden > 0) Line("+$hidden more", Tone.QUIET) else null
    }

    /**
     * This aircraft's own line: the reference every row below it is measured from.
     *
     * Range and relative altitude are zero because that is what they are - it is where the other
     * numbers are measured from - and bearing is a dash because a bearing to yourself is not a
     * thing. With no fix of its own the whole row goes to dashes and to the stale colour, and that
     * is on purpose: it is the explanation for whatever the rows below are showing.
     */
    private fun ownLine(
        own: FleetOwnView,
        nameColour: Int?,
    ): Line {
        val name = own.displayName.take(NAME_WIDTH).padEnd(NAME_WIDTH)
        val tone = if (own.hasPosition) Tone.SELF else Tone.SELF_NO_FIX
        val nameEnd = SELF_MARKER.length + NAME_WIDTH
        if (!own.hasPosition) {
            return Line("$SELF_MARKER$name ${unknownColumns()}", tone, nameColour, nameEnd)
        }
        val zero = "0m"
        return Line(
            "$SELF_MARKER$name ${zero.padStart(RANGE_WIDTH)} ${DASH.padStart(BEARING_WIDTH)} " +
                "${zero.padStart(RELATIVE_WIDTH)}",
            tone,
            nameColour,
            nameEnd,
        )
    }

    /**
     * One peer on one line: state dot, name, range, bearing, relative height, and how old any of
     * that is when it is not this second's.
     *
     * A peer with no fix at all shows dashes rather than zeroes. Rendering an unknown range as
     * "0m" would be the most alarming possible way to say "I do not know where this aircraft is".
     */
    private fun peerLine(
        peer: FleetPeerView,
        nameColour: Int?,
    ): Line {
        val dot =
            when (peer.liveness) {
                PeerLiveness.LIVE -> "\u25CF"
                PeerLiveness.STALE -> "\u25D0"
                PeerLiveness.LOST -> "\u25CB"
            }
        val tone = toneFor(peer)
        val name = peer.displayName.take(NAME_WIDTH).padEnd(NAME_WIDTH)
        val nameEnd = dot.length + NAME_WIDTH
        val solution = peer.solution
        if (solution == null) return Line("$dot$name ${unknownColumns()}", tone, nameColour, nameEnd)
        val range = FleetGeo.formatDistance(solution.slantRangeM).padStart(RANGE_WIDTH)
        val bearing = FleetGeo.compassPoint(solution.bearingDeg).padStart(BEARING_WIDTH)
        val relative = FleetGeo.formatRelativeAltitude(solution.verticalSeparationM).padStart(RELATIVE_WIDTH)
        return Line("$dot$name $range $bearing $relative${ageSuffix(peer)}", tone, nameColour, nameEnd)
    }

    /**
     * How old the geometry is, when it is not this second's.
     *
     * Trailing rather than leading so that the columns stay in the same place on every row: a
     * marker that pushed the range along by one character would cost more readability than the
     * marker itself is worth.
     */
    private fun ageSuffix(peer: FleetPeerView): String {
        val ageMs = peer.solutionAgeMs ?: return ""
        val seconds = (ageMs / MILLIS_PER_SECOND).coerceAtMost(MAX_AGE_SECONDS)
        return " ${"${seconds}s".padStart(AGE_WIDTH)}"
    }

    private fun toneFor(peer: FleetPeerView): Tone =
        when (peer.liveness) {
            PeerLiveness.LOST -> Tone.LOST
            PeerLiveness.STALE -> Tone.STALE
            PeerLiveness.LIVE ->
                when (peer.advisoryLevel) {
                    AdvisoryLevel.WARNING -> Tone.WARNING
                    AdvisoryLevel.CAUTION -> Tone.CAUTION
                    AdvisoryLevel.ADVISORY -> Tone.ADVISORY
                    AdvisoryLevel.NONE -> Tone.QUIET
                }
        }

    /** The three value columns in one piece, so every row that has no answer is the same width. */
    private fun unknownColumns(): String = "${DASH.padStart(RANGE_WIDTH)} ${DASH.padStart(BEARING_WIDTH)} ${DASH.padStart(RELATIVE_WIDTH)}"
}
