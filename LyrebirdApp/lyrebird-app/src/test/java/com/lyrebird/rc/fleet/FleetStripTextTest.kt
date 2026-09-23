package com.lyrebird.rc.fleet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The strip's wording, which is where every mistake this readout has made so far has been.
 *
 * The rules live in [FleetStripText] rather than in the view for exactly this reason: "which alert
 * does the header name", "whose name is on the first row", "how old is the number in this row" are
 * questions a test can ask, and none of them were answerable while the answers were inside a
 * `LinearLayout`.
 */
class FleetStripTextTest {
    private val own = FleetBeaconTest.sampleBeacon().copy(
        deviceId = "OWN",
        droneName = "alpha",
        systemId = 142,
    )

    private fun fleetView(
        own: FleetOwnView? = FleetOwnView("OWN", "alpha", hasPosition = true),
        peers: List<FleetPeerView> = emptyList(),
        conflicts: List<FleetConflict> = emptyList(),
        alerts: List<FleetAlert> = emptyList(),
    ) = FleetView(own = own, peers = peers, conflicts = conflicts, alerts = alerts)

    private fun solution(
        level: AdvisoryLevel = AdvisoryLevel.NONE,
        slantRangeM: Double = 142.0,
        bearingDeg: Double = 90.0,
        verticalSeparationM: Double = 12.0,
    ) = TrafficSolution(
        horizontalDistanceM = slantRangeM,
        verticalSeparationM = verticalSeparationM,
        slantRangeM = slantRangeM,
        bearingDeg = bearingDeg,
        closingSpeedMps = 0.0,
        timeToClosestApproachS = null,
        closestApproachHorizontalM = null,
        closestApproachVerticalM = null,
        level = level,
    )

    private fun peerView(
        name: String = "bravo",
        liveness: PeerLiveness = PeerLiveness.LIVE,
        solution: TrafficSolution? = solution(),
        solutionAgeMs: Long? = null,
        place: FleetFix? = null,
        placeAgeMs: Long? = null,
        restartedAtMs: Long? = null,
    ): FleetPeerView =
        FleetPeerView(
            peer =
                FleetPeer(
                    beacon = own.copy(deviceId = name.uppercase(), droneName = name, systemId = 143),
                    sourceAddress = "10.0.0.2",
                    firstSeenMs = 0L,
                    lastSeenMs = 1_000L,
                    lastFlyingMs = null,
                    lastFix = null,
                    restartedAtMs = restartedAtMs,
                ),
            liveness = liveness,
            ageMs = 1_000L,
            solution = solution,
            solutionAgeMs = solutionAgeMs,
            place = place,
            placeAgeMs = placeAgeMs,
        )

    private fun alert(
        kind: FleetAlertKind = FleetAlertKind.PEER_APP_RESTARTED,
        severity: ConflictSeverity = ConflictSeverity.WARNING,
    ) = FleetAlert(kind = kind, severity = severity, summary = "something", peerName = "bravo")

    @Test
    fun `the first row is this aircraft`() {
        val rows =
            FleetStripText.rows(
                fleetView(
                    own = FleetOwnView("MINI1", "mini1", hasPosition = true),
                    peers = listOf(peerView()),
                ),
            )
        assertTrue(rows.first().text.startsWith(FleetStripText.SELF_MARKER + "mini1"))
        assertEquals(FleetStripText.Tone.SELF, rows.first().tone)
        assertEquals(2, rows.size)
    }

    /**
     * The colour is the thing a pilot matches between the list and the map, so it belongs to the
     * aircraft rather than to its state: two peers in the same advisory are still two aircraft.
     */
    @Test
    fun `a name is drawn in its aircraft's own colour`() {
        val view =
            fleetView(
                own = FleetOwnView("MINI1", "mini1", hasPosition = true),
                peers = listOf(peerView(name = "mini5")),
            )
        val rows = FleetStripText.rows(view)
        assertEquals(view.memberColours["MINI1"], rows.first().nameColour)
        assertEquals(view.memberColours["MINI5"], rows.last().nameColour)
        assertFalse(
            "two members of a fleet are never drawn in one colour",
            rows.first().nameColour == rows.last().nameColour,
        )
        val named = rows.last().text.substring(0, rows.last().nameEnd)
        assertTrue(named.contains("mini5"))
        assertFalse("the name column stops before the values", named.contains("142m"))
    }

    @Test
    fun `this aircraft's values stay white even though its name has a colour`() {
        val rows = FleetStripText.rows(fleetView(peers = listOf(peerView())))
        assertEquals(FleetStripText.Tone.SELF, rows.first().tone)
    }

    /**
     * Its own line is the one row that can explain the others: with no fix of its own, every range
     * below is missing or old, and this is where the operator finds that out.
     */
    @Test
    fun `this aircraft with no fix says so instead of zeroes`() {
        val rows =
            FleetStripText.rows(
                fleetView(
                    own = FleetOwnView("MINI1", "mini1", hasPosition = false),
                    peers = listOf(peerView()),
                ),
            )
        assertEquals(FleetStripText.Tone.SELF_NO_FIX, rows.first().tone)
        assertTrue(rows.first().text.contains("--"))
        assertFalse(rows.first().text.contains("0m"))
    }

    @Test
    fun `the header counts this aircraft as a member of its own fleet`() {
        val header =
            FleetStripText.header(
                fleetView(peers = listOf(peerView(), peerView(name = "mini5"))),
            )
        assertEquals("FLEET 3/3", header.text)
    }

    /**
     * The header had one word for every alert, so a restart was announced as a loss and a fleet
     * that was live and healthy sat under a warning. A header that cries wolf is a header nobody
     * reads when it is right.
     */
    @Test
    fun `a restarted app is announced as a restart, not as a loss`() {
        val header = FleetStripText.header(fleetView(peers = listOf(peerView()), alerts = listOf(alert())))
        assertEquals("FLEET 2/2 !RESTART", header.text)
        assertEquals(FleetStripText.Tone.HEADER_WARNING, header.tone)
    }

    @Test
    fun `only a silent aircraft is announced as lost`() {
        val quiet =
            FleetStripText.header(
                fleetView(
                    peers = listOf(peerView(liveness = PeerLiveness.LOST)),
                    alerts = listOf(alert(FleetAlertKind.PEER_SILENT_WHILE_FLYING, ConflictSeverity.CRITICAL)),
                ),
            )
        assertEquals("FLEET 1/2 !LOST", quiet.text)
        assertEquals(FleetStripText.Tone.HEADER_CRITICAL, quiet.tone)

        val lagging =
            FleetStripText.header(
                fleetView(
                    peers = listOf(peerView(liveness = PeerLiveness.STALE)),
                    alerts = listOf(alert(FleetAlertKind.PEER_SILENT_WHILE_FLYING, ConflictSeverity.WARNING)),
                ),
            )
        // Lagging is not live: the count tracks beacons heard from, and a stale peer is not one.
        assertEquals("FLEET 1/2 ~LOST", lagging.text)
    }

    @Test
    fun `a number taken from an old fix carries its age`() {
        val current = FleetStripText.rows(fleetView(peers = listOf(peerView())))
        assertFalse("a current range says nothing about age", current.last().text.contains("s"))

        val old = FleetStripText.rows(fleetView(peers = listOf(peerView(solutionAgeMs = 35_000L))))
        assertTrue(old.last().text.endsWith("35s"))
    }

    @Test
    fun `a peer with no geometry at all shows dashes`() {
        val rows = FleetStripText.rows(fleetView(peers = listOf(peerView(name = "mini5", solution = null))))
        assertTrue(rows.last().text.contains("--"))
        assertTrue(rows.last().text.endsWith("--"))
    }

    @Test
    fun `peers that do not fit fold away, and this aircraft never does`() {
        val peers =
            listOf(
                peerView(name = "mini2"),
                peerView(name = "mini3"),
                peerView(name = "mini4"),
                peerView(name = "mini5"),
            )
        val view =
            fleetView(
                own = FleetOwnView("MINI1", "mini1", hasPosition = true),
                peers = peers,
            )
        val rows = FleetStripText.rows(view)
        assertEquals(FleetStripText.MAX_VISIBLE_ROWS, rows.size)
        assertTrue(rows.first().text.contains("mini1"))
        assertEquals("+1 more", FleetStripText.overflow(view)?.text)
        assertEquals(FleetStripText.Tone.QUIET, FleetStripText.overflow(view)?.tone)
    }

    @Test
    fun `rows and header are the same width whatever they contain`() {
        val rows =
            FleetStripText.rows(
                fleetView(
                    peers =
                        listOf(
                            peerView(),
                            peerView(name = "mini5", solution = null, liveness = PeerLiveness.LOST),
                        ),
                ),
            )
        assertEquals(
            "columns line up across rows with and without values",
            1,
            rows.map { it.text.length }.toSet().size,
        )
    }
}
