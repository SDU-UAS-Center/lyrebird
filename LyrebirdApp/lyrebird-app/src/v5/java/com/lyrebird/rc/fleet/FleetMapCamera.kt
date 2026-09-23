package com.lyrebird.rc.fleet

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import dji.v5.ux.map.MapWidget
import dji.v5.ux.mapkit.core.camera.DJICameraUpdateFactory
import dji.v5.ux.mapkit.core.models.DJICameraPosition
import dji.v5.ux.mapkit.core.models.DJILatLng
import dji.v5.ux.mapkit.core.models.DJILatLngBounds
import kotlin.math.abs

/**
 * Keeps the Flight Deck map on the fleet, and hands it to the pilot the moment they touch it.
 *
 * The map is 150x100dp in a corner. Left to the SDK's aircraft lock it centres on this aircraft at
 * whatever zoom it happened to have, which is why peers two hundred metres away — the ones this
 * whole feature exists to show — were drawn somewhere off the edge of it. So the camera is driven
 * from here instead: frame this aircraft together with every peer that has a fix, at the zoom that
 * fits them, and re-frame as they move.
 *
 * A pilot who pans or pinches is looking at something else on purpose. Their view is left exactly
 * where they put it, and control comes back [MapFollowPolicy] seconds after they stop. Nothing here
 * moves the map while a finger is on it, which is the one rule that makes an automatic map usable
 * in a cockpit.
 *
 * DJI types are used inside method bodies and fields of this class only; it is a v5 file by
 * construction (MapWidget is vendored UXSDK).
 */
internal class FleetMapCamera(
    private val mapWidget: MapWidget?,
    private val ownPosition: () -> Pair<Double, Double>?,
) {
    companion object {
        private const val TAG = "LyrebirdFleetCamera"

        /** How often the aircraft position is checked against the framed view. */
        private const val TICK_MS = 1_000L

        /**
         * How long a camera change is assumed to be ours. An animation reports intermediate
         * positions that match nothing we asked for, and counting those as pilot input would stop
         * the map following every time it followed.
         */
        private const val ANIMATION_MS = 700L

        private const val COMPACT_PADDING_PX = 30
        private const val EXPANDED_PADDING_PX = 90

        /**
         * The smallest area framed around the aircraft, as a half-extent in degrees (~60m across).
         *
         * The frame is built symmetrically around the aircraft, so what the camera centres on is
         * the aircraft itself rather than the middle of wherever the fleet happens to be: a
         * bounds call centres the box, and a box drawn around an off-to-one-side fleet would put
         * this aircraft near an edge. This is the size that box takes when there are no peers to
         * make it bigger.
         */
        private const val MIN_HALF_EXTENT_DEG = 2.7e-4

        private const val TARGET_EPSILON_DEG = 1e-4
        private const val ZOOM_EPSILON = 0.05f
    }

    private val handler = Handler(Looper.getMainLooper())
    private val policy = MapFollowPolicy()

    private var expanded = false
    private var applyingUntilMs = 0L
    private var listenerAttached = false
    private var requestedTarget: DJILatLng? = null
    private var requestedZoom: Float? = null

    /** Peers with a fix, as of the fleet controller's last refresh. */
    private var peerPositions: List<DJILatLng> = emptyList()

    private val tick =
        object : Runnable {
            override fun run() {
                // The UXSDK builds its map asynchronously inside the widget, so the camera it
                // drives may not exist yet when this is installed; the tick is also the retry.
                attachCameraListener()
                reframeIfWanted(force = false)
                handler.postDelayed(this, TICK_MS)
            }
        }

    /** Start following. Safe to call again after [release]. */
    fun install() {
        attachCameraListener()
        handler.removeCallbacks(tick)
        handler.post(tick)
        reframeIfWanted(force = true)
    }

    private fun attachCameraListener() {
        if (listenerAttached) return
        val map = mapWidget?.map ?: return
        map.setOnCameraChangeListener { position -> onCameraChanged(position) }
        listenerAttached = true
    }

    /** The map's own size changed (compact <-> expanded): fill the new space. */
    fun setExpanded(value: Boolean) {
        if (expanded == value) return
        expanded = value
        policy.resume()
        reframeIfWanted(force = true)
    }

    /** The fleet as of this refresh, from the controller that samples the mesh. */
    fun setPeers(positions: List<DJILatLng>) {
        peerPositions = positions
        // A peer appearing (or leaving) is worth a re-frame even if this aircraft is stationary.
        reframeIfWanted(force = false)
    }

    fun release() {
        handler.removeCallbacks(tick)
        if (listenerAttached) {
            mapWidget?.map?.removeAllOnCameraChangeListeners()
            listenerAttached = false
        }
    }

    private fun onCameraChanged(position: DJICameraPosition) {
        if (SystemClock.elapsedRealtime() < applyingUntilMs) return
        val target = requestedTarget ?: return
        val sameTarget =
            abs(position.position.latitude - target.latitude) < TARGET_EPSILON_DEG &&
                abs(position.position.longitude - target.longitude) < TARGET_EPSILON_DEG
        val sameZoom = requestedZoom?.let { z -> abs(position.zoom - z) < ZOOM_EPSILON } ?: true
        if (sameTarget && sameZoom) return

        // Not ours: the pilot moved the map. Leave it alone and start the idle clock.
        if (policy.isFollowing) {
            Log.i(TAG, "Pilot moved the map; leaving it alone until they stop")
        }
        policy.onUserMoved(SystemClock.elapsedRealtime())
    }

    private fun reframeIfWanted(force: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (policy.shouldResume(now)) {
            policy.resume()
            Log.i(TAG, "Pilot idle: the map follows the fleet again")
        }
        val own = ownPosition()
        if (force) policy.resume()
        val peers = peerPositions
        if (!policy.shouldReframe(now, own?.first, own?.second, peers.size, force)) return

        val corners = framingCorners(own, peers)
        if (corners.isEmpty()) return
        frame(corners, now, own, peers.size)
    }

    private fun frame(
        points: List<DJILatLng>,
        nowMs: Long,
        own: Pair<Double, Double>?,
        peerCount: Int,
    ) {
        val map = mapWidget?.map ?: return
        val bounds = DJILatLngBounds.fromLatLngs(points)
        val centre =
            if (own != null) {
                // Symmetric about the aircraft: the bounds engine centres the box, so a box drawn
                // from the aircraft outwards to the farthest peer puts the aircraft in the middle
                // and every peer inside it.
                DJILatLng(own.first, own.second)
            } else {
                DJILatLng(
                    points.map { it.latitude }.average(),
                    points.map { it.longitude }.average(),
                )
            }
        requestedTarget = centre
        requestedZoom = null
        applyingUntilMs = nowMs + ANIMATION_MS
        policy.onFramed(nowMs, own?.first, own?.second, peerCount)
        runCatching {
            map.animateCamera(
                DJICameraUpdateFactory.newLatLngBounds(
                    bounds,
                    if (expanded) EXPANDED_PADDING_PX else COMPACT_PADDING_PX,
                ),
            )
        }.onFailure { error -> Log.d(TAG, "Could not frame the fleet: ${error.message}") }
    }

    /**
     * The corners to frame: the aircraft, extended symmetrically to include every peer.
     *
     * Returns the points as two opposite corners rather than a fleet of points so the bounds —
     * and therefore the camera's centre — is the aircraft, not the fleet's average position.
     */
    private fun framingCorners(
        own: Pair<Double, Double>?,
        peers: List<DJILatLng>,
    ): List<DJILatLng> {
        if (own == null) return peers
        val (latitude, longitude) = own
        val latitudeDelta = maxOf(peers.maxOfOrNull { abs(it.latitude - latitude) } ?: 0.0, MIN_HALF_EXTENT_DEG)
        val longitudeDelta = maxOf(peers.maxOfOrNull { abs(it.longitude - longitude) } ?: 0.0, MIN_HALF_EXTENT_DEG)
        return listOf(
            DJILatLng(latitude + latitudeDelta, longitude + longitudeDelta),
            DJILatLng(latitude - latitudeDelta, longitude - longitudeDelta),
        )
    }
}
