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
    LOST,
}

/** A place a peer reported itself at, and when it said so. */
internal data class FleetFix(
    val latitudeDeg: Double,
    val longitudeDeg: Double,
    val altitudeAslM: Double,
    val atMs: Long,
)

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
    /**
     * The last place this peer reported a real fix, or null if it never has.
     *
     * A GPS fix comes and goes - a roofline, a canopy, a bench indoors - and a peer whose fix has
     * dropped out is still an aircraft at a real last known place. Keeping that lets its row show
     * a real distance with an age on it instead of a row of dashes, which reads as an aircraft
     * that is nowhere and is how the fleet came to look intermittently broken.
     */
    val lastFix: FleetFix?,
    /**
     * When this peer's app was last seen to restart, or null if it has not been.
     *
     * A restart is the only way another RC can learn that command authority on a peer died with
     * its process, so it is worth saying. It is news, though, and not a condition: it expires a
     * minute later rather than colouring a header for the rest of the session.
     */
    val restartedAtMs: Long?,
)

internal enum class FleetAlertKind {
    /** A peer that was airborne stopped beaconing. The one failure nobody on the ground sees today. */
    PEER_SILENT_WHILE_FLYING,

    /** A peer's app restarted mid-session: its uptime counter went backwards. */
    PEER_APP_RESTARTED,
}

internal data class FleetAlert(
    val kind: FleetAlertKind,
    val severity: ConflictSeverity,
    val summary: String,
    val peerName: String,
)

/** One peer, resolved against the local aircraft, ready to render. */
internal data class FleetPeerView(
    val peer: FleetPeer,
    val liveness: PeerLiveness,
    val ageMs: Long,
    /**
     * Range and bearing to show: this second's when both aircraft have a fix, otherwise what the
     * peer's last known fix gives. Never a projection of a track that was not observed.
     */
    val solution: TrafficSolution?,
    /**
     * Null when [solution] is current, otherwise how old the fix behind it is.
     *
     * Anything but null means "this is where the aircraft was, not where it is", and every
     * readout that shows these numbers has to say so. Its only job is to stop a stale range from
     * being read as a live one.
     */
    val solutionAgeMs: Long?,
    /**
     * Where to draw this peer: where it is, or failing that where it was last seen.
     *
     * Null only when there has never been a place for it. A peer with no marker at all cannot be
     * told apart from a peer that is not on the network, so a last known place is drawn - dimmed,
     * and with its age in the title - rather than omitted.
     */
    val place: FleetFix?,
    /** Null when [place] is where the aircraft is now, otherwise how old the place is. */
    val placeAgeMs: Long?,
) {
    val displayName: String get() = peer.beacon.displayName()

    /** Only geometry from a fix both aircraft hold right now may raise an advisory. */
    val solutionIsCurrent: Boolean get() = solutionAgeMs == null && liveness != PeerLiveness.LOST

    val advisoryLevel: AdvisoryLevel
        get() = if (solutionIsCurrent) solution?.level ?: AdvisoryLevel.NONE else AdvisoryLevel.NONE
}

/**
 * This aircraft, as the first row of its own fleet list.
 *
 * The list carries the pilot's own aircraft for the same reason a chart marks the vessel you are
 * on: every number in the rows below is relative to it, and a column of peers with nothing to be
 * relative to is a column about strangers. It is also the one row that can explain the others - if
 * this device has no fix of its own, every range below is missing or old, and this is where that
 * is said.
 */
internal data class FleetOwnView(
    val deviceId: String,
    val displayName: String,
    /** False while this aircraft has no fix of its own to range from. */
    val hasPosition: Boolean,
)

/** Everything the Flight Deck draws in one consistent read. */
internal data class FleetView(
    /** This aircraft itself, or null when the roster had nothing to say about it. */
    val own: FleetOwnView?,
    val peers: List<FleetPeerView>,
    val conflicts: List<FleetConflict>,
    val alerts: List<FleetAlert>,
) {
    val peerCount: Int get() = peers.size
    val liveCount: Int get() = peers.count { it.liveness == PeerLiveness.LIVE }

    /** The fleet as the list counts it: this aircraft is a member of its own fleet list. */
    val memberCount: Int get() = peers.size + if (own == null) 0 else 1
    val liveMemberCount: Int get() = liveCount + if (own == null) 0 else 1

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

    /**
     * The identity colour of every member, this aircraft included.
     *
     * Computed here rather than by each readout so that a name on the strip and a chevron on the
     * map cannot disagree about who is who, and so that the collision that keeps two members apart
     * is resolved once instead of twice.
     */
    val memberColours: Map<String, Int> =
        FleetPalette.assign(
            own?.let { FleetPalette.Member(it.deviceId, it.displayName) },
            peers.map { FleetPalette.Member(it.peer.beacon.deviceId, it.displayName) },
        )

    companion object {
        val EMPTY = FleetView(null, emptyList(), emptyList(), emptyList())
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
internal class FleetRoster(
    private val ownDeviceId: () -> String,
) {
    companion object {
        /** Beacon period is 500 ms, so this is five missed beacons. */
        const val LIVE_TIMEOUT_MS = 2_500L

        /** Beyond this a peer is treated as gone rather than merely lagging. */
        const val STALE_TIMEOUT_MS = 12_000L

        /** How long a peer's last fix stays worth showing after its GPS drops out. */
        const val FIX_MEMORY_MS = 60_000L

        /** How long a peer's app restart stays in the header. It is news, and news expires. */
        const val RESTART_ALERT_WINDOW_MS = 60_000L

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
     * The last place this aircraft itself reported.
     *
     * Kept for the same reason a peer's is: every range on the strip is measured from here, so
     * losing this device's own fix for a moment used to blank every row in the column at once.
     */
    private var ownFix: FleetFix? = null

    /**
     * Record a beacon. Returns false when it was this device's own, echoed back by the network.
     *
     * Multicast loopback is on by default on Android, so a device hears itself. Filtering by
     * device id rather than by source address is what makes that reliable: the address a datagram
     * appears to come from depends on which interface the stack picked.
     */
    @Synchronized
    fun onBeacon(
        beacon: FleetBeacon,
        sourceAddress: String,
        nowMs: Long,
    ): Boolean {
        if (beacon.deviceId == ownDeviceId()) return false
        val existing = peers[beacon.deviceId]
        // The uptime on the wire is the app's, not the mesh link's, so this fires when the process
        // that holds command authority over that aircraft really restarted - and not when the
        // link merely came back up underneath it.
        val restarted = existing != null && beacon.appUptimeMs < existing.beacon.appUptimeMs
        peers[beacon.deviceId] =
            FleetPeer(
                beacon = beacon,
                sourceAddress = sourceAddress,
                firstSeenMs = existing?.firstSeenMs ?: nowMs,
                lastSeenMs = nowMs,
                lastFlyingMs = if (beacon.flying) nowMs else existing?.lastFlyingMs,
                lastFix =
                    if (beacon.hasRealPosition()) {
                        FleetFix(beacon.latitudeDeg, beacon.longitudeDeg, beacon.altitudeAslM, nowMs)
                    } else {
                        existing?.lastFix
                    },
                restartedAtMs = if (restarted) nowMs else existing?.restartedAtMs,
            )
        return true
    }

    @Synchronized
    fun forgetStalePeers(nowMs: Long) {
        peers.entries.removeAll { nowMs - it.value.lastSeenMs > FORGET_TIMEOUT_MS }
    }

    /**
     * Drop a peer that said its identity is gone, as opposed to one that went quiet.
     *
     * The two need different treatment: silence keeps a row for [FORGET_TIMEOUT_MS] on purpose (
     * an aircraft that disappears airborne is what the operator most needs to keep seeing), while a
     * retired id is superseded, and keeping it would list one aircraft twice and have its two rows
     * collide with each other on MAVLink id. Returns true when a row was actually removed.
     */
    @Synchronized
    fun forget(deviceId: String): Boolean = peers.remove(deviceId) != null

    @Synchronized
    fun clear() {
        peers.clear()
        ownFix = null
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
    fun view(
        own: FleetBeacon,
        nowMs: Long,
    ): FleetView {
        forgetStalePeers(nowMs)
        if (peers.isEmpty()) return FleetView.EMPTY

        val ranging = ownForRanging(own, nowMs)
        val views =
            peers.values
                .map { peer -> viewOf(ranging.beacon, ranging.ageMs, peer, nowMs) }
                .sortedWith(
                    compareByDescending<FleetPeerView> { it.advisoryLevel.ordinal }
                        .thenBy { it.solution?.slantRangeM ?: Double.MAX_VALUE }
                        .thenBy { it.displayName },
                )

        return FleetView(
            own =
                FleetOwnView(
                    deviceId = own.deviceId,
                    displayName = own.displayName(),
                    hasPosition = own.hasRealPosition(),
                ),
            peers = views,
            conflicts = FleetConflicts.detect(own, views.map { it.peer.beacon }),
            alerts = alertsFor(views, nowMs),
        )
    }

    /** The position every range on the strip is measured from, and how old it is. */
    private data class RangingOwn(
        val beacon: FleetBeacon,
        val ageMs: Long,
    )

    /**
     * Where this aircraft should measure from: this second's fix, or its last known one.
     *
     * Every range on the strip is measured from here, so a blip in this device's own fix used to
     * blank the whole column at once - a row of dashes for every peer, none of which had changed -
     * and then fill it back in when the fix returned. The last known place is used instead, with
     * its age carried into every row it produced, so the rows keep saying something true and say
     * how old it is rather than pretending to know nothing.
     */
    private fun ownForRanging(
        own: FleetBeacon,
        nowMs: Long,
    ): RangingOwn {
        if (own.hasRealPosition()) {
            ownFix = FleetFix(own.latitudeDeg, own.longitudeDeg, own.altitudeAslM, nowMs)
            return RangingOwn(own, 0L)
        }
        val remembered =
            ownFix?.takeIf { nowMs - it.atMs <= FIX_MEMORY_MS } ?: return RangingOwn(own, 0L)
        return RangingOwn(
            own.copy(
                latitudeDeg = remembered.latitudeDeg,
                longitudeDeg = remembered.longitudeDeg,
                altitudeAslM = remembered.altitudeAslM,
            ),
            nowMs - remembered.atMs,
        )
    }

    /**
     * One peer against this aircraft: what to draw, what to show, and how old each is.
     *
     * Three cases, in order. A fix on both sides now gives this second's geometry, and everything
     * that follows from it. A peer that has gone quiet keeps the place it was last seen at, aged,
     * and loses its advisory: its track is a memory, not a track. A peer whose own GPS has dropped
     * out keeps the last place it reported, likewise aged - the distance to a real place is still
     * real, and the age is the only thing standing between that and a live range.
     *
     * The reason both of the last two are carried rather than dropped is the same: a row of dashes
     * and a marker that vanishes read as "this aircraft is nowhere", which is the one thing that is
     * not true. A last known place with an age on it is weaker than a live fix and stronger than
     * saying nothing, and the operator can see which one they have.
     *
     * A last known place is kept whether or not this aircraft can range from its own: a peer's
     * marker is about where the peer is, and only its *range* needs this aircraft to know where it
     * is itself.
     */
    private fun viewOf(
        own: FleetBeacon,
        ownAgeMs: Long,
        peer: FleetPeer,
        nowMs: Long,
    ): FleetPeerView {
        val ageMs = nowMs - peer.lastSeenMs
        val liveness = livenessOf(ageMs)
        val quiet = liveness == PeerLiveness.LOST
        val beaconFix =
            if (peer.beacon.hasRealPosition()) {
                FleetFix(
                    peer.beacon.latitudeDeg,
                    peer.beacon.longitudeDeg,
                    peer.beacon.altitudeAslM,
                    peer.lastSeenMs,
                )
            } else {
                null
            }

        if (beaconFix != null && !quiet && ownAgeMs == 0L) {
            val current = solveAgainst(own, peer)
            if (current != null) {
                return FleetPeerView(
                    peer = peer,
                    liveness = liveness,
                    ageMs = ageMs,
                    solution = current,
                    solutionAgeMs = null,
                    place = beaconFix,
                    placeAgeMs = null,
                )
            }
        }

        val place = beaconFix ?: peer.lastFix?.takeIf { nowMs - it.atMs <= FIX_MEMORY_MS }
        if (place == null) {
            return FleetPeerView(
                peer = peer,
                liveness = liveness,
                ageMs = ageMs,
                solution = null,
                solutionAgeMs = null,
                place = null,
                placeAgeMs = null,
            )
        }

        // A quiet peer's own fix is as old as its silence: nothing has arrived since it.
        val placeAgeMs =
            when {
                quiet -> ageMs
                beaconFix != null -> null
                else -> nowMs - place.atMs
            }
        val geometry = rangeAgainst(own, place)
        // The geometry is as current as its oldest input, and that is the age it is shown with.
        val geometryAgeMs = maxOf(ownAgeMs, placeAgeMs ?: 0L)
        return FleetPeerView(
            peer = peer,
            liveness = liveness,
            ageMs = ageMs,
            solution = geometry,
            solutionAgeMs = if (geometry == null || geometryAgeMs == 0L) null else geometryAgeMs,
            place = place,
            placeAgeMs = placeAgeMs,
        )
    }

    /** Range and bearing to a peer's last known fix. No track, so no advisory. */
    private fun rangeAgainst(
        own: FleetBeacon,
        fix: FleetFix,
    ): TrafficSolution? =
        TrafficAdvisory.rangeOnly(
            ownLatitudeDeg = own.latitudeDeg,
            ownLongitudeDeg = own.longitudeDeg,
            ownAltitudeAslM = own.altitudeAslM,
            peerLatitudeDeg = fix.latitudeDeg,
            peerLongitudeDeg = fix.longitudeDeg,
            peerAltitudeAslM = fix.altitudeAslM,
        )

    private fun solveAgainst(
        own: FleetBeacon,
        peer: FleetPeer,
    ): TrafficSolution? =
        TrafficAdvisory.solve(
            ownLatitudeDeg = own.latitudeDeg,
            ownLongitudeDeg = own.longitudeDeg,
            ownAltitudeAslM = own.altitudeAslM,
            ownVelocityNorthMps = own.velocityNorthMps,
            ownVelocityEastMps = own.velocityEastMps,
            ownVelocityDownMps = own.velocityDownMps,
            ownFlying = own.flying,
            peer = peer.beacon,
        )

    private fun livenessOf(ageMs: Long): PeerLiveness =
        when {
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
    private fun alertsFor(
        views: List<FleetPeerView>,
        nowMs: Long,
    ): List<FleetAlert> {
        val alerts = mutableListOf<FleetAlert>()
        views.forEach { view ->
            val wasFlying = view.peer.lastFlyingMs != null
            if (wasFlying && view.liveness != PeerLiveness.LIVE) {
                val silentForS = (nowMs - view.peer.lastSeenMs) / MILLIS_PER_SECOND
                alerts +=
                    FleetAlert(
                        kind = FleetAlertKind.PEER_SILENT_WHILE_FLYING,
                        severity =
                            if (view.liveness == PeerLiveness.LOST) {
                                ConflictSeverity.CRITICAL
                            } else {
                                ConflictSeverity.WARNING
                            },
                        summary = "${view.displayName} was airborne and has been silent for ${silentForS}s.",
                        peerName = view.displayName,
                    )
            }
            val restartedAtMs = view.peer.restartedAtMs
            if (restartedAtMs != null && nowMs - restartedAtMs <= RESTART_ALERT_WINDOW_MS) {
                alerts +=
                    FleetAlert(
                        kind = FleetAlertKind.PEER_APP_RESTARTED,
                        severity = ConflictSeverity.WARNING,
                        summary = "${view.displayName} restarted its app during this session.",
                        peerName = view.displayName,
                    )
            }
        }
        return alerts.sortedByDescending { it.severity.ordinal }
    }
}

private const val MILLIS_PER_SECOND = 1_000L
