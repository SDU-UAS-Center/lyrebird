package com.lyrebird.rc.fleet

import android.app.Activity
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import androidx.appcompat.app.AlertDialog
import dji.v5.ux.map.MapWidget
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Everything the Flight Deck needs from the fleet mesh, in one object.
 *
 * `FlightDeckActivity` is already very large, and the mesh touches four separate concerns —
 * a socket, a roster, two views and a page. Keeping them here means the activity holds one field
 * and calls four methods, and it means the mesh can be switched off in one place.
 *
 * The controller owns the refresh cadence rather than reacting to each inbound beacon. Beacons
 * from N aircraft arrive at 2N hertz on a network thread, and redrawing a map marker on each one
 * would put the fleet on the main thread more often than the video pipeline is. Instead the roster
 * absorbs beacons as they land and the UI samples it on a fixed timer.
 *
 * ## Nothing here interrupts
 *
 * The pilot is flying an aircraft. Everything this feature knows is conveyed by the colour and
 * content of a readout they can choose to look at: no toasts, no dialogs that open by themselves,
 * no take-off gates, nothing that takes focus or covers the video. A warning that appears
 * uninvited over a live camera feed is worse than the situation it is describing, and one that
 * appears often enough becomes the thing people learn to dismiss without reading. The only dialog
 * in this file opens because someone tapped to open it.
 */
internal class FleetDeckController(
    private val activity: Activity,
    private val prefs: SharedPreferences,
    private val stripView: FleetStripView?,
    mapWidget: MapWidget?,
    /** This device's stable fleet identity. Cheap: consulted on every inbound datagram. */
    private val deviceIdProvider: () -> String,
    /** The local aircraft's current state, or null while identity is still resolving. */
    private val beaconProvider: () -> FleetBeacon?
) {

    companion object {
        private const val TAG = "LyrebirdFleet"

        /** Preference gate, so the mesh can be turned off without uninstalling anything. */
        const val PREF_FLEET_ENABLED = "lb_fleet_enabled"

        /** Matches the beacon period: the strip never shows state older than one beacon. */
        private const val REFRESH_INTERVAL_MS = 500L
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val roster = FleetRoster(deviceIdProvider)
    private val mapOverlay = FleetMapOverlay(mapWidget)
    private var link: FleetLink? = null
    private var mapExpanded = false
    private var lastSharedAtMs: Long = 0L

    private val refreshRunnable = object : Runnable {
        override fun run() {
            refresh()
            mainHandler.postDelayed(this, REFRESH_INTERVAL_MS)
        }
    }

    val isEnabled: Boolean
        get() = prefs.getBoolean(PREF_FLEET_ENABLED, true)

    fun start() {
        if (!isEnabled) {
            Log.i(TAG, "Fleet mesh disabled by preference")
            return
        }
        if (link != null) return
        val started = FleetLink(
            activity.applicationContext, roster, deviceIdProvider, beaconProvider
        ).apply {
            onSettingsOffered = { offer -> fileOffer(offer) }
        }
        started.start()
        link = started
        stripView?.onStripClicked = { showFleetDialog() }
        mainHandler.post(refreshRunnable)
    }

    fun stop() {
        mainHandler.removeCallbacks(refreshRunnable)
        link?.stop()
        link = null
        stripView?.onStripClicked = null
        stripView?.visibility = View.GONE
        mapOverlay.clear()
        FleetProfileStore.clearSessionRecords()
    }

    /** The expanded map covers the strip's corner, so the strip stands down while it is open. */
    fun setMapExpanded(expanded: Boolean) {
        mapExpanded = expanded
        if (expanded) stripView?.visibility = View.GONE
    }

    fun peerCount(): Int = roster.peerCount()

    /**
     * Publish this device's shareable settings to the fleet as a profile.
     *
     * Peers file it; none of them change anything. Returns false when the mesh is not running.
     */
    fun shareProfileWithFleet(): Boolean {
        val active = link ?: return false
        val own = beaconProvider() ?: return false
        val payload = FleetSettingsShare.buildOffer(prefs, deviceIdProvider(), own.droneName)
        val sent = active.offerSettings(payload)
        if (sent) lastSharedAtMs = System.currentTimeMillis()
        return sent
    }

    /**
     * A peer published its profile. File it and say nothing.
     *
     * Runs on the mesh receive thread; the store does its own synchronisation and touches no view,
     * so there is nothing to hop to the main thread for. The operator finds this on the fleet page
     * when they go looking, which is the point.
     */
    private fun fileOffer(offer: FleetSettingsOffer) {
        FleetProfileStore.store(offer)
    }

    /**
     * The fleet as of now, ranged against this aircraft.
     *
     * Prefers the beacon the sender thread last published over building a fresh one. It is at most
     * one beacon period old, it is exactly the state the peers were told about, and reusing it
     * keeps a dozen synchronous DJI key reads off the main thread twice a second.
     */
    private fun currentView(): FleetView {
        val own = link?.lastSentBeacon ?: beaconProvider() ?: return FleetView.EMPTY
        return roster.view(own, System.currentTimeMillis())
    }

    private fun refresh() {
        val view = currentView()
        if (!mapExpanded) stripView?.render(view)
        mapOverlay.update(view)
    }

    /**
     * The full fleet page: every peer, then anything wrong with the fleet as a whole.
     *
     * This is the pre-flight check, and it is a page the operator opens rather than a gate that
     * blocks take-off. Lyrebird does not know why two aircraft share a name, and an app that
     * refused to fly on the strength of an unauthenticated multicast datagram would be a worse
     * failure than the one it was trying to prevent.
     */
    fun showFleetDialog() {
        val view = currentView()
        AlertDialog.Builder(activity)
            .setTitle("Fleet (${view.peerCount} peer${if (view.peerCount == 1) "" else "s"})")
            .setMessage(fleetPageText(view))
            .setPositiveButton("Close", null)
            .setNeutralButton("Share my profile") { _, _ -> confirmShareProfile() }
            .show()
    }

    private fun fleetPageText(view: FleetView): String = buildString {
        if (view.isEmpty) {
            append("No other Lyrebird aircraft on this network.\n\n")
            append("Every device beacons on ")
            append("${FleetLink.MULTICAST_GROUP}:${FleetLink.MULTICAST_PORT}. ")
            append("A peer missing here is usually on a different access point.")
        } else {
            append("AIRCRAFT\n")
            view.peers.forEach { peer -> append(peerLine(peer)).append('\n') }
        }
        if (view.alerts.isNotEmpty()) {
            append("\nALERTS\n")
            view.alerts.forEach { append("• ${it.summary}\n") }
        }
        if (view.conflicts.isNotEmpty()) {
            append("\nCONFLICTS\n")
            view.conflicts.forEach { append("• ${it.summary}\n") }
        } else if (!view.isEmpty) {
            append("\nNo configuration conflicts.")
        }
        appendProfileSection()
    }

    /**
     * What the mesh has filed, and what this device has published.
     *
     * Named as files on disk rather than as an offer to apply: this section exists so an operator
     * knows the profiles arrived and where to find them, not so they can act on one from here.
     */
    private fun StringBuilder.appendProfileSection() {
        val stored = FleetProfileStore.storedThisSession()
        if (stored.isEmpty() && lastSharedAtMs == 0L) return
        append("\n\nPROFILES\n")
        if (lastSharedAtMs != 0L) {
            append("• Shared mine at ${clockTime(lastSharedAtMs)}\n")
        }
        stored.forEach { profile ->
            val from = profile.fromDroneName.ifBlank { profile.fromDeviceId.takeLast(6) }
            append("• From $from at ${clockTime(profile.receivedAtMs)}, ")
            append("${profile.keyCount} settings → ${profile.file.name}\n")
        }
        if (stored.isNotEmpty()) {
            append("\nSaved, not applied. Nothing on this device changed. ")
            append("Load one from settings when you want it.")
        }
    }

    private fun peerLine(peer: FleetPeerView): String {
        val beacon = peer.peer.beacon
        val state = when (peer.liveness) {
            PeerLiveness.LIVE -> if (beacon.flying) "flying" else "on ground"
            PeerLiveness.STALE -> "stale ${peer.ageMs / MILLIS_PER_SECOND}s"
            PeerLiveness.LOST -> "lost ${peer.ageMs / MILLIS_PER_SECOND}s"
        }
        val range = peer.solution?.let {
            " · ${FleetGeo.formatDistance(it.slantRangeM)} ${FleetGeo.compassPoint(it.bearingDeg)}" +
                " · ${FleetGeo.formatRelativeAltitude(it.verticalSeparationM)}"
        }.orEmpty()
        val battery = if (beacon.batteryPercent >= 0) " · ${beacon.batteryPercent}%" else ""
        val closing = peer.solution
            ?.takeIf { it.level.atLeast(AdvisoryLevel.CAUTION) && it.timeToClosestApproachS != null }
            ?.let { " · closing, CPA ${it.timeToClosestApproachS?.toInt()}s" }
            .orEmpty()
        return "• ${peer.displayName} (MAV ${beacon.systemId}) · $state$range$battery$closing"
    }

    private fun confirmShareProfile() {
        val peers = roster.peerCount()
        val keys = FleetSettingsShare.SHAREABLE_KEYS.size
        AlertDialog.Builder(activity)
            .setTitle("Share profile with $peers aircraft?")
            .setMessage(
                "Publishes this device's video and detection settings ($keys keys) to every " +
                    "peer, which saves them as a profile file.\n\n" +
                    "Nothing changes on any aircraft. Drone names, MAVLink IDs and all flight " +
                    "settings are never shared."
            )
            .setPositiveButton("Share") { _, _ ->
                shareProfileWithFleet()
                // Reopen the page so the result is visible where the operator already is, rather
                // than as a notification over the video feed.
                showFleetDialog()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun clockTime(epochMs: Long): String =
        SimpleDateFormat("HH:mm", Locale.US).format(Date(epochMs))
}

private const val MILLIS_PER_SECOND = 1_000L
