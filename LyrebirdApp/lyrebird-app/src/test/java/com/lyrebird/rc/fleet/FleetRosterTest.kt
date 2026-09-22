package com.lyrebird.rc.fleet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Roster lifecycle: appearing, ageing, going silent while airborne, and restarting.
 *
 * Every step takes an explicit clock, so a peer that has been quiet for two minutes is a value
 * passed in rather than two minutes of waiting.
 */
class FleetRosterTest {

    private val own = FleetBeaconTest.sampleBeacon().copy(
        deviceId = "OWN",
        droneName = "alpha",
        systemId = 142,
        videoPath = "alpha"
    )

    private fun roster() = FleetRoster { "OWN" }
    private fun peer(
        deviceId: String = "PEER",
        name: String = "bravo",
        flying: Boolean = false,
        uptimeMs: Long = 10_000L,
        sequence: Long = 1L
    ) = own.copy(
        deviceId = deviceId,
        droneName = name,
        systemId = 143,
        videoPath = name,
        flying = flying,
        appUptimeMs = uptimeMs,
        sequence = sequence
    )

    @Test
    fun `ignores this device's own beacon echoed back by multicast loopback`() {
        val roster = roster()
        assertFalse(roster.onBeacon(own, "127.0.0.1", 1_000L))
        assertTrue(roster.view(own, 1_000L).isEmpty)
    }

    @Test
    fun `a fresh peer is live`() {
        val roster = roster()
        roster.onBeacon(peer(), "10.0.0.2", 1_000L)
        val view = roster.view(own, 1_000L)
        assertEquals(1, view.peerCount)
        assertEquals(PeerLiveness.LIVE, view.peers.single().liveness)
    }

    @Test
    fun `a peer ages through live then stale then lost`() {
        val roster = roster()
        roster.onBeacon(peer(), "10.0.0.2", 0L)
        assertEquals(PeerLiveness.LIVE, roster.view(own, 1_000L).peers.single().liveness)
        assertEquals(PeerLiveness.STALE, roster.view(own, 5_000L).peers.single().liveness)
        assertEquals(PeerLiveness.LOST, roster.view(own, 30_000L).peers.single().liveness)
    }

    @Test
    fun `a peer that has been gone long enough is forgotten entirely`() {
        val roster = roster()
        roster.onBeacon(peer(), "10.0.0.2", 0L)
        assertTrue(roster.view(own, FleetRoster.FORGET_TIMEOUT_MS + 1_000L).isEmpty)
    }

    /**
     * The duplicate this exists for: one aircraft that changed identity mid-session (installation
     * id first, serial once the link came up) used to sit in the roster as two peers until the
     * silence timeout expired — and the two rows clashed on MAVLink id and name, so every other RC
     * reported a fleet conflict about a device that was not in conflict with anything.
     */
    @Test
    fun `a retired identity leaves at once instead of ageing out`() {
        val roster = roster()
        roster.onBeacon(peer(deviceId = "install-42"), "10.0.0.2", 1_000L)
        roster.onBeacon(peer(deviceId = "SERIAL42"), "10.0.0.2", 1_500L)
        assertEquals("both identities are in the list until one is retired", 2, roster.peerCount())

        assertTrue(roster.forget("install-42"))
        val view = roster.view(own, 1_600L)
        assertEquals(1, view.peerCount)
        assertEquals("SERIAL42", view.peers.single().peer.beacon.deviceId)
        assertFalse("forgetting an id nobody is using changes nothing", roster.forget("install-42"))
    }

    @Test
    fun `a peer that goes silent while airborne raises a critical alert`() {
        val roster = roster()
        roster.onBeacon(peer(flying = true), "10.0.0.2", 0L)
        assertTrue(roster.view(own, 1_000L).alerts.isEmpty())

        val lostView = roster.view(own, 30_000L)
        val alert = lostView.alerts.single()
        assertEquals(FleetAlertKind.PEER_SILENT_WHILE_FLYING, alert.kind)
        assertEquals(ConflictSeverity.CRITICAL, alert.severity)
        assertTrue(alert.summary.contains("bravo"))
    }

    @Test
    fun `a peer that goes silent on the ground raises nothing`() {
        val roster = roster()
        roster.onBeacon(peer(flying = false), "10.0.0.2", 0L)
        assertTrue(roster.view(own, 30_000L).alerts.isEmpty())
    }

    @Test
    fun `a peer that landed before going silent is still flagged`() {
        // It was airborne at some point in this session, so its silence is worth reporting even
        // though the last beacon said it was down: the last beacon may simply be old.
        val roster = roster()
        roster.onBeacon(peer(flying = true), "10.0.0.2", 0L)
        roster.onBeacon(peer(flying = false, sequence = 2L), "10.0.0.2", 1_000L)
        assertTrue(roster.view(own, 30_000L).alerts.isNotEmpty())
    }

    @Test
    fun `an uptime counter going backwards is read as a peer app restart`() {
        val roster = roster()
        roster.onBeacon(peer(uptimeMs = 60_000L, sequence = 100L), "10.0.0.2", 0L)
        roster.onBeacon(peer(uptimeMs = 500L, sequence = 1L), "10.0.0.2", 500L)
        val alert = roster.view(own, 600L).alerts.single()
        assertEquals(FleetAlertKind.PEER_APP_RESTARTED, alert.kind)
    }

    /**
     * The uptime on the wire is the app's, not the mesh link's, and this is the case that
     * separates them: a link that comes back up under a running app resets its own sequence
     * without the process having gone anywhere. Reading that as an app restart put a permanent
     * loss warning on a header above a fleet that was live and healthy.
     */
    @Test
    fun `a mesh link restart is not read as an app restart`() {
        val roster = roster()
        roster.onBeacon(peer(uptimeMs = 60_000L, sequence = 100L), "10.0.0.2", 0L)
        roster.onBeacon(peer(uptimeMs = 61_000L, sequence = 1L), "10.0.0.2", 1_000L)
        assertTrue(roster.view(own, 1_100L).alerts.isEmpty())
    }

    @Test
    fun `a restart is announced for a minute and then stops being news`() {
        val roster = roster()
        roster.onBeacon(peer(uptimeMs = 60_000L), "10.0.0.2", 0L)
        roster.onBeacon(peer(uptimeMs = 500L), "10.0.0.2", 500L)
        assertEquals(FleetAlertKind.PEER_APP_RESTARTED, roster.view(own, 600L).alerts.single().kind)
        assertTrue(
            roster.view(own, 500L + FleetRoster.RESTART_ALERT_WINDOW_MS + 1L).alerts.isEmpty()
        )
    }

    @Test
    fun `a lost peer keeps the place it was last seen and loses its advisory`() {
        val roster = roster()
        roster.onBeacon(peer(flying = true), "10.0.0.2", 0L)
        val peerView = roster.view(own, 30_000L).peers.single()
        assertEquals(PeerLiveness.LOST, peerView.liveness)
        assertEquals(
            "its row keeps a range from the last fix",
            0.0,
            peerView.solution!!.slantRangeM,
            0.001,
        )
        assertEquals("but says how old that fix is", 30_000L, peerView.solutionAgeMs)
        assertEquals(AdvisoryLevel.NONE, peerView.advisoryLevel)
        assertEquals("and keeps a place to draw it at", 30_000L, peerView.placeAgeMs)
    }

    /**
     * A fix that comes and goes is the ordinary case on a bench indoors and under cover outdoors,
     * and a peer whose fix has just dropped out is still an aircraft at a real place. It used to
     * show a row of dashes and lose its map marker entirely, which reads as "this aircraft is
     * nowhere" - the one thing that is not true.
     */
    @Test
    fun `a peer whose fix drops out is still ranged against where it was`() {
        val roster = roster()
        roster.onBeacon(peer(), "10.0.0.2", 0L)
        roster.onBeacon(peer().copy(latitudeDeg = 0.0, longitudeDeg = 0.0), "10.0.0.2", 20_000L)
        val peerView = roster.view(own, 20_000L).peers.single()
        assertEquals(PeerLiveness.LIVE, peerView.liveness)
        assertEquals(20_000L, peerView.solutionAgeMs)
        assertEquals(
            "a fix that is not current may not raise an advisory",
            AdvisoryLevel.NONE,
            peerView.advisoryLevel,
        )
        assertEquals(own.latitudeDeg, peerView.place?.latitudeDeg ?: 0.0, 0.0001)
    }

    /**
     * Every range on the strip is measured from this aircraft, so losing this device's own fix for
     * a moment used to blank the whole column at once - every row, none of which had changed - and
     * fill it back in when the fix returned. On a bench indoors that is most of the time.
     */
    @Test
    fun `a blip in this aircraft's own fix does not blank the column`() {
        val roster = roster()
        val shifted = peer().copy(latitudeDeg = own.latitudeDeg + 0.001)
        roster.onBeacon(shifted, "10.0.0.2", 0L)
        roster.view(own, 0L)
        roster.onBeacon(shifted, "10.0.0.2", 10_000L)

        val blind = own.copy(latitudeDeg = 0.0, longitudeDeg = 0.0)
        val peerView = roster.view(blind, 10_000L).peers.single()
        assertNotNull("the range survives its own fix", peerView.solution)
        assertEquals(10_000L, peerView.solutionAgeMs)
        assertEquals("the peer's own place is still this second's", null, peerView.placeAgeMs)
        assertEquals(
            "no advisory may come from geometry that is not current",
            AdvisoryLevel.NONE,
            peerView.advisoryLevel,
        )
    }

    /** A peer's marker is about where the peer is; only its range needs this aircraft's own fix. */
    @Test
    fun `a peer's place survives this aircraft having no fix at all`() {
        val roster = roster()
        roster.onBeacon(peer(), "10.0.0.2", 0L)
        val blind = own.copy(latitudeDeg = 0.0, longitudeDeg = 0.0)
        val peerView = roster.view(blind, 1_000L).peers.single()
        assertNotNull(peerView.place)
        assertTrue("but there is nothing to measure a range from", peerView.solution == null)
    }

    @Test
    fun `a peer that has never had a fix has nowhere to be drawn`() {
        val roster = roster()
        roster.onBeacon(peer().copy(latitudeDeg = 0.0, longitudeDeg = 0.0), "10.0.0.2", 1_000L)
        val peerView = roster.view(own, 1_000L).peers.single()
        assertTrue(peerView.solution == null)
        assertTrue(peerView.place == null)
    }

    @Test
    fun `a last known place stops being shown once it is too old to mean anything`() {
        val roster = roster()
        roster.onBeacon(peer(), "10.0.0.2", 0L)
        roster.onBeacon(peer().copy(latitudeDeg = 0.0, longitudeDeg = 0.0), "10.0.0.2", 1_000L)
        val aged = roster.view(own, 1_000L + FleetRoster.FIX_MEMORY_MS + 1L).peers.single()
        assertTrue(aged.solution == null)
        assertTrue(aged.place == null)
    }

    @Test
    fun `the fleet list starts with this aircraft`() {
        val roster = roster()
        roster.onBeacon(peer(), "10.0.0.2", 1_000L)
        val view = roster.view(own, 1_000L)
        assertEquals("alpha", view.own?.displayName)
        assertEquals("the count includes it", 2, view.memberCount)
        assertEquals(2, view.liveMemberCount)
    }

    @Test
    fun `this aircraft reports whether it has a fix to measure from`() {
        val roster = roster()
        roster.onBeacon(peer(), "10.0.0.2", 1_000L)
        assertTrue(roster.view(own, 1_000L).own!!.hasPosition)
        assertFalse(
            roster.view(own.copy(latitudeDeg = 0.0, longitudeDeg = 0.0), 1_000L).own!!.hasPosition
        )
    }

    @Test
    fun `the most urgent peer is listed first`() {
        val roster = roster()
        // Far away, so only an advisory.
        roster.onBeacon(
            peer(deviceId = "FAR", name = "far", flying = true)
                .copy(latitudeDeg = own.latitudeDeg + 0.002),
            "10.0.0.3",
            0L
        )
        // Practically on top of this aircraft.
        roster.onBeacon(
            peer(deviceId = "NEAR", name = "near", flying = true)
                .copy(latitudeDeg = own.latitudeDeg + 0.0001),
            "10.0.0.4",
            0L
        )
        val view = roster.view(own, 500L)
        assertEquals("near", view.peers.first().displayName)
        assertEquals(AdvisoryLevel.WARNING, view.highestAdvisory)
    }

    @Test
    fun `live count distinguishes reachable peers from remembered ones`() {
        val roster = roster()
        roster.onBeacon(peer(deviceId = "A", name = "a"), "10.0.0.2", 0L)
        roster.onBeacon(peer(deviceId = "B", name = "b"), "10.0.0.3", 20_000L)
        val view = roster.view(own, 20_500L)
        assertEquals(2, view.peerCount)
        assertEquals(1, view.liveCount)
    }

    @Test
    fun `conflicts are reported through the composed view`() {
        val roster = roster()
        roster.onBeacon(peer().copy(systemId = own.systemId), "10.0.0.2", 0L)
        val view = roster.view(own, 500L)
        assertTrue(view.hasCriticalConflict)
    }

    @Test
    fun `clearing drops every peer`() {
        val roster = roster()
        roster.onBeacon(peer(), "10.0.0.2", 0L)
        roster.clear()
        assertTrue(roster.view(own, 500L).isEmpty)
    }
}
