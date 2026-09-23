package com.lyrebird.rc.fleet

/**
 * When the Flight Deck map should follow the fleet, and when it should leave the pilot alone.
 *
 * The map is small, in a corner, and the pilot is flying: it has to show where this aircraft is and
 * where the others are, without fighting a pilot who is looking somewhere else on it. So there are
 * exactly two states — following, and holding still because a human moved the map — and one rule
 * for going back: [idleResumeMs] after the last human movement.
 *
 * Pure and clock-injected, because all of its interesting behaviour is about time (when a pan
 * counts as finished, when a moving aircraft is worth re-framing) and a test that has to wait five
 * seconds is a test nobody runs.
 */
internal class MapFollowPolicy(
    private val idleResumeMs: Long = 5_000L,
    private val reframeIntervalMs: Long = 1_500L,
    private val moveThresholdDeg: Double = 3e-5,
) {
    /** True while the map should track the aircraft and the fleet by itself. */
    var isFollowing: Boolean = true
        private set

    private var pausedAtMs: Long = 0L
    private var lastReframeAtMs: Long = 0L
    private var lastFramedLat: Double? = null
    private var lastFramedLon: Double? = null
    private var lastPeerCount: Int = 0

    /**
     * A camera change nobody asked for is a human moving the map: stop following, and start the
     * clock that hands control back.
     */
    fun onUserMoved(nowMs: Long) {
        isFollowing = false
        pausedAtMs = nowMs
    }

    /** True once the pilot has been quiet for long enough to take the map back. */
    fun shouldResume(nowMs: Long): Boolean = !isFollowing && nowMs - pausedAtMs >= idleResumeMs

    /**
     * Whether to re-frame now: the aircraft has moved far enough to matter, the set of peers
     * changed (someone joined, left, or got a fix), it has been long enough since the last frame,
     * or [force] says the space itself changed (the map was expanded).
     */
    fun shouldReframe(
        nowMs: Long,
        latitudeDeg: Double?,
        longitudeDeg: Double?,
        peerCount: Int,
        force: Boolean = false,
    ): Boolean {
        if (!isFollowing) return false
        if (latitudeDeg == null || longitudeDeg == null) {
            // No fix yet: nothing to centre on, but a peer appearing is still worth showing.
            return peerCount != lastPeerCount && peerCount > 0
        }
        val moved =
            lastFramedLat == null ||
                lastFramedLon == null ||
                kotlin.math.abs(latitudeDeg - lastFramedLat!!) >= moveThresholdDeg ||
                kotlin.math.abs(longitudeDeg - lastFramedLon!!) >= moveThresholdDeg
        val peersChanged = peerCount != lastPeerCount
        if (!force && !moved && !peersChanged) return false
        if (!force && nowMs - lastReframeAtMs < reframeIntervalMs) return false
        return true
    }

    /** Record what was just framed, so the next call can tell whether anything moved. */
    fun onFramed(
        nowMs: Long,
        latitudeDeg: Double?,
        longitudeDeg: Double?,
        peerCount: Int,
    ) {
        lastReframeAtMs = nowMs
        lastFramedLat = latitudeDeg
        lastFramedLon = longitudeDeg
        lastPeerCount = peerCount
    }

    /** Hand control back to the pilot (a resume), for the same reason they took it. */
    fun resume() {
        isFollowing = true
        pausedAtMs = 0L
        lastFramedLat = null
        lastFramedLon = null
        lastReframeAtMs = 0L
    }
}
