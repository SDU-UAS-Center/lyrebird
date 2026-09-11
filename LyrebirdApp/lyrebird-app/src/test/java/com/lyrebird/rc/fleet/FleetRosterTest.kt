package com.lyrebird.rc.fleet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun `a lost peer gets no traffic solution`() {
        val roster = roster()
        roster.onBeacon(peer(flying = true), "10.0.0.2", 0L)
        val view = roster.view(own, 30_000L)
        assertEquals(AdvisoryLevel.NONE, view.peers.single().advisoryLevel)
        assertTrue(view.peers.single().solution == null)
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
