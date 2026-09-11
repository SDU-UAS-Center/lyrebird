package com.lyrebird.rc.fleet

/**
 * How recently a peer was heard from.
 *
 * Beacons arrive twice a second, so silence is measured in missed beacons rather than in a guess
 * about the network. A handful of dropped multicast datagrams on a busy access point is normal
 * and must not flicker the roster; a peer that has said nothing for several seconds has either
 * lost the network, lost power, or lost its app, and all three are worth seeing.
 */
internal enum class PeerLiveness {
    /** Beaconing normally. */
    LIVE,

    /** Missed enough beacons to notice. Usually a congested link. */
    STALE,

    /** Gone. Position, if shown at all, is where it was last seen. */
    LOST
}

/** A peer as the roster remembers it, with the bookkeeping liveness needs. */
internal data class FleetPeer(
    val beacon: FleetBeacon,
    val sourceAddress: String,
    val firstSeenMs: Long,
    val lastSeenMs: Long,
    /**
     * When this peer was last seen airborne, or null if it never has been.
     *
     * Null rather than zero: zero is a valid wall-clock instant, and conflating "never flew" with
     * a timestamp is exactly the confusion that would silence the alert this field exists to
     * raise.
     */
    val lastFlyingMs: Long?,
    /** True when the peer's uptime counter went backwards, meaning its app restarted. */
    val restartedSinceFirstSeen: Boolean
)

internal enum class FleetAlertKind {
    /** A peer that was airborne stopped beaconing. The one failure nobody on the ground sees today. */
    PEER_SILENT_WHILE_FLYING,

    /** A peer's app restarted mid-session: its uptime counter went backwards. */
    PEER_APP_RESTARTED
}

internal data class FleetAlert(
    val kind: FleetAlertKind,
    val severity: ConflictSeverity,
    val summary: String,
    val peerName: String
)

/** One peer, resolved against the local aircraft, ready to render. */
internal data class FleetPeerView(
    val peer: FleetPeer,
    val liveness: PeerLiveness,
    val ageMs: Long,
    /** Null when either aircraft lacks a usable GPS fix. */
    val solution: TrafficSolution?
) {
    val displayName: String get() = peer.beacon.displayName()
    val advisoryLevel: AdvisoryLevel get() = solution?.level ?: AdvisoryLevel.NONE
}

/** Everything the Flight Deck draws in one consistent read. */
internal data class FleetView(
    val peers: List<FleetPeerView>,
    val conflicts: List<FleetConflict>,
    val alerts: List<FleetAlert>
) {
    val peerCount: Int get() = peers.size
    val liveCount: Int get() = peers.count { it.liveness == PeerLiveness.LIVE }

    /**
     * Conflicts worth interrupting the pilot for, as opposed to worth listing.
     *
     * Overlapping home points is the reason this distinction exists. It is real information and
     * belongs on the pre-flight page, but launching several aircraft from one mat is the ordinary
     * way a field day starts, so raising it on the deck would put a permanent warning on a screen
     * during entirely normal operations — and a warning that is always on is one nobody reads when
     * it finally means something.
     */
    val alertingConflicts: List<FleetConflict>
        get() = conflicts.filter { it.severity != ConflictSeverity.INFO }
    val highestAdvisory: AdvisoryLevel
        get() = peers.maxOfOrNull { it.advisoryLevel } ?: AdvisoryLevel.NONE
    val hasCriticalConflict: Boolean
        get() = conflicts.any { it.severity == ConflictSeverity.CRITICAL }
    val isEmpty: Boolean get() = peers.isEmpty()

    companion object {
        val EMPTY = FleetView(emptyList(), emptyList(), emptyList())
    }
}

/**
 * The fleet as this device understands it.
 *
 * Holds one entry per peer device, ages them out, and composes the view the UI renders. All state
 * changes take a caller-supplied `nowMs`, so the whole lifecycle — appearing, going stale, going
 * silent while flying, restarting — is testable without waiting in real time.
 *
 * Thread-safe: beacons arrive on the mesh receive thread while the UI thread reads snapshots.
 */
internal class FleetRoster(private val ownDeviceId: () -> String) {

    companion object {
        /** Beacon period is 500 ms, so this is five missed beacons. */
        const val LIVE_TIMEOUT_MS = 2_500L

        /** Beyond this a peer is treated as gone rather than merely lagging. */
        const val STALE_TIMEOUT_MS = 12_000L

        /**
         * How long a vanished peer stays on the roster.
         *
         * Long, deliberately. A peer that disappears while airborne is exactly the case the
         * operator most needs to keep looking at, and dropping the row the moment it goes quiet
         * would erase the evidence along with the aircraft's last known position.
         */
        const val FORGET_TIMEOUT_MS = 120_000L
    }

    private val peers = LinkedHashMap<String, FleetPeer>()

    /**
     * Record a beacon. Returns false when it was this device's own, echoed back by the network.
     *
     * Multicast loopback is on by default on Android, so a device hears itself. Filtering by
     * device id rather than by source address is what makes that reliable: the address a datagram
     * appears to come from depends on which interface the stack picked.
     */
    @Synchronized
    fun onBeacon(beacon: FleetBeacon, sourceAddress: String, nowMs: Long): Boolean {
        if (beacon.deviceId == ownDeviceId()) return false
        val existing = peers[beacon.deviceId]
        val restarted = existing != null &&
            (beacon.appUptimeMs < existing.beacon.appUptimeMs || beacon.sequence < existing.beacon.sequence)
        peers[beacon.deviceId] = FleetPeer(
            beacon = beacon,
            sourceAddress = sourceAddress,
            firstSeenMs = existing?.firstSeenMs ?: nowMs,
            lastSeenMs = nowMs,
            lastFlyingMs = if (beacon.flying) nowMs else existing?.lastFlyingMs,
            restartedSinceFirstSeen = restarted || (existing?.restartedSinceFirstSeen ?: false)
        )
        return true
    }

    @Synchronized
    fun forgetStalePeers(nowMs: Long) {
        peers.entries.removeAll { nowMs - it.value.lastSeenMs > FORGET_TIMEOUT_MS }
    }

    @Synchronized
    fun clear() {
        peers.clear()
    }

    @Synchronized
    fun peerBeacons(): List<FleetBeacon> = peers.values.map { it.beacon }

    @Synchronized
    fun peerCount(): Int = peers.size

    /**
     * Compose the current view against [own], the local aircraft's own beacon.
     *
     * Rows are ordered by urgency and then by range, so the aircraft the pilot should look at
     * first is the one at the top of a strip that may only have room for three.
     */
    @Synchronized
    fun view(own: FleetBeacon, nowMs: Long): FleetView {
        forgetStalePeers(nowMs)
        if (peers.isEmpty()) return FleetView.EMPTY

        val views = peers.values.map { peer ->
            val ageMs = nowMs - peer.lastSeenMs
            val liveness = livenessOf(ageMs)
            FleetPeerView(
                peer = peer,
                liveness = liveness,
                ageMs = ageMs,
                // A peer that has gone quiet has no current track, so projecting one would be a
                // fabrication. Its row keeps a range from the last fix, but no advisory.
                solution = if (liveness == PeerLiveness.LOST) null else solveAgainst(own, peer)
            )
        }.sortedWith(
            compareByDescending<FleetPeerView> { it.advisoryLevel.ordinal }
                .thenBy { it.solution?.slantRangeM ?: Double.MAX_VALUE }
                .thenBy { it.displayName }
        )

        return FleetView(
            peers = views,
            conflicts = FleetConflicts.detect(own, views.map { it.peer.beacon }),
            alerts = alertsFor(views, nowMs)
        )
    }

    private fun solveAgainst(own: FleetBeacon, peer: FleetPeer): TrafficSolution? =
        TrafficAdvisory.solve(
            ownLatitudeDeg = own.latitudeDeg,
            ownLongitudeDeg = own.longitudeDeg,
            ownAltitudeAslM = own.altitudeAslM,
            ownVelocityNorthMps = own.velocityNorthMps,
            ownVelocityEastMps = own.velocityEastMps,
            ownVelocityDownMps = own.velocityDownMps,
            ownFlying = own.flying,
            peer = peer.beacon
        )

    private fun livenessOf(ageMs: Long): PeerLiveness = when {
        ageMs <= LIVE_TIMEOUT_MS -> PeerLiveness.LIVE
        ageMs <= STALE_TIMEOUT_MS -> PeerLiveness.STALE
        else -> PeerLiveness.LOST
    }

    /**
     * Liveness alerts.
     *
     * The important one is a peer that was airborne and has stopped speaking. Command authority
     * lives in each app's memory and dies with it, so an app that crashes on one RC leaves an
     * aircraft flying under no supervision that any other RC can see. This is the only place in
     * Lyrebird where that becomes visible from another device.
     */
    private fun alertsFor(views: List<FleetPeerView>, nowMs: Long): List<FleetAlert> {
        val alerts = mutableListOf<FleetAlert>()
        views.forEach { view ->
            val wasFlying = view.peer.lastFlyingMs != null
            if (wasFlying && view.liveness != PeerLiveness.LIVE) {
                val silentForS = (nowMs - view.peer.lastSeenMs) / MILLIS_PER_SECOND
                alerts += FleetAlert(
                    kind = FleetAlertKind.PEER_SILENT_WHILE_FLYING,
                    severity = if (view.liveness == PeerLiveness.LOST) {
                        ConflictSeverity.CRITICAL
                    } else {
                        ConflictSeverity.WARNING
                    },
                    summary = "${view.displayName} was airborne and has been silent for ${silentForS}s.",
                    peerName = view.displayName
                )
            }
            if (view.peer.restartedSinceFirstSeen && view.liveness == PeerLiveness.LIVE) {
                alerts += FleetAlert(
                    kind = FleetAlertKind.PEER_APP_RESTARTED,
                    severity = ConflictSeverity.WARNING,
                    summary = "${view.displayName} restarted its app during this session.",
                    peerName = view.displayName
                )
            }
        }
        return alerts.sortedByDescending { it.severity.ordinal }
    }
}

private const val MILLIS_PER_SECOND = 1_000L
