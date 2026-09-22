package com.lyrebird.rc.fleet

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.BitmapDrawable
import android.util.Log
import android.util.LruCache
import dji.v5.ux.map.MapWidget
import dji.v5.ux.mapkit.core.models.DJIBitmapDescriptor
import dji.v5.ux.mapkit.core.models.DJIBitmapDescriptorFactory
import dji.v5.ux.mapkit.core.models.DJILatLng
import dji.v5.ux.mapkit.core.models.annotations.DJIMarker
import dji.v5.ux.mapkit.core.models.annotations.DJIMarkerOptions
import kotlin.math.ceil

/**
 * Draws the rest of the fleet on the Flight Deck map.
 *
 * The map widget already plots this aircraft and its home point; what it cannot know is that
 * three other aircraft are in the air two hundred metres away, because nothing in the DJI SDK has
 * ever been told they exist. Each peer becomes a heading-oriented chevron carrying its name, so
 * the same glance that answers "where am I" answers "where is everyone else".
 *
 * Markers are added straight to the widget's map through its public accessor rather than by
 * modifying the widget, which keeps the vendored UXSDK unpatched and means a future SDK drop does
 * not take this feature with it.
 */
internal class FleetMapOverlay(
    private val mapWidget: MapWidget?,
) {
    companion object {
        private const val TAG = "LyrebirdFleetMap"

        /**
         * The layer the peer chevrons are drawn on: above the widget's home (5) and gimbal (6)
         * markers, below its aircraft marker (7).
         *
         * A parked aircraft is standing on its own home point, so on the ground the widget's home
         * marker is drawn exactly where a peer's chevron is - and two aircraft on one launch mat is
         * the normal case, not an edge case. This aircraft's own marker keeps the top of the stack:
         * it is the one being flown.
         */
        private const val PEER_MARKER_Z = 6

        /**
         * Names above everything, this aircraft's own marker included.
         *
         * A name is the only thing on the map that cannot be inferred from anything else, and it is
         * smaller than every marker it competes with. A chevron drawn over a name hides the answer
         * to "which aircraft is that"; a name drawn over a chevron leaves the chevron visible around
         * it, and this aircraft's marker bigger than either.
         */
        private const val PEER_LABEL_Z = 8

        // Icon geometry. The compact map is 150x100dp, so these are the sizes that keep a chevron
        // and a readable name legible there and on the full-screen map.
        private const val CHEVRON_HALF_WIDTH_PX = 32f
        private const val CHEVRON_HEIGHT_PX = 74f
        private const val CHEVRON_PAD_PX = 10
        private const val OUTLINE_WIDTH_PX = 6f

        private const val COMPACT_CHEVRON_HALF_WIDTH_PX = 15f
        private const val COMPACT_CHEVRON_HEIGHT_PX = 34f
        private const val COMPACT_CHEVRON_PAD_PX = 5
        private const val COMPACT_OUTLINE_WIDTH_PX = 3f

        // This aircraft's own chevron is a step larger than a peer's: the one on this screen is
        // the one the operator is standing next to, and it should be the first one they find. Both
        // sets change with the map, so it grows with the others when the map is expanded.
        private const val OWN_CHEVRON_HALF_WIDTH_PX = 50f
        private const val OWN_CHEVRON_HEIGHT_PX = 116f
        private const val OWN_CHEVRON_PAD_PX = 14
        private const val OWN_OUTLINE_WIDTH_PX = 8f

        private const val COMPACT_OWN_CHEVRON_HALF_WIDTH_PX = 24f
        private const val COMPACT_OWN_CHEVRON_HEIGHT_PX = 54f
        private const val COMPACT_OWN_CHEVRON_PAD_PX = 7
        private const val COMPACT_OWN_OUTLINE_WIDTH_PX = 4f

        /** The ring on this aircraft's own marker. Not a state: a "this one is you". */
        private const val COLOR_OWN_RING = 0xFFFFFFFF.toInt()

        // The name under the chevron, which is a marker of its own: the chevron turns with the
        // aircraft's heading and the name must not, or a drone flying east is labelled sideways.
        private const val LABEL_TEXT_PX = 44f
        private const val LABEL_PAD_PX = 6
        private const val LABEL_CLEARANCE_PX = 4
        private const val LABEL_OUTLINE_PX = 4f

        private const val COMPACT_LABEL_TEXT_PX = 20f
        private const val COMPACT_LABEL_PAD_PX = 3
        private const val COMPACT_LABEL_CLEARANCE_PX = 2
        private const val COMPACT_LABEL_OUTLINE_PX = 2f

        /** Anchors: the chevron centres on the aircraft, its name hangs directly below it. */
        private const val ANCHOR_CENTRE = 0.5f
        private const val ANCHOR_TOP = 0f

        private const val ICON_CACHE_ENTRIES = 24

        private const val COLOR_WARNING = 0xFFFF5252.toInt()
        private const val COLOR_CAUTION = 0xFFFFB300.toInt()
        private const val COLOR_NORMAL = 0xFF4FC3F7.toInt()
        private const val COLOR_STALE = 0xFFBDA35A.toInt()
        private const val COLOR_LOST = 0xFF8D6E63.toInt()

        /** A peer drawn where it was last seen rather than where it is: present, but not current. */
        private const val COLOR_GHOST = 0xFF9E9E9E.toInt()

        /** How solid a last known place is drawn, so "not current" survives at icon size. */
        private const val GHOST_FILL_ALPHA = 110

        /** How much wider the black halo is than the state ring drawn inside it. */
        private const val HALO_SCALE = 2.2f

        private const val MILLIS_PER_SECOND = 1_000L
    }

    private val markers = mutableMapOf<String, DJIMarker>()

    /** The name under each chevron, a marker of its own so it can stay upright while the icon turns. */
    private val labels = mutableMapOf<String, DJIMarker>()

    /** Icons are keyed by what they depict, so a peer holding station never redraws one. */
    private val iconCache = LruCache<String, Bitmap>(ICON_CACHE_ENTRIES)

    /** True while the map is the small corner one; the icon set is scaled to fit it. */
    private var compact = true

    /** What the widget's own aircraft marker is currently wearing, so it is re-skinned on change. */
    private var aircraftIconKey: String? = null

    /**
     * Switch between the compact and the expanded icon set.
     *
     * The bitmap is built once per (name, colour, size), so the cache is dropped on a size change;
     * leaving it would redraw every peer at the old size until its colour happened to change.
     */
    fun setCompact(value: Boolean) {
        if (compact == value) return
        compact = value
        iconCache.evictAll()
        // The widget's marker is re-skinned at the new size on the next tick.
        aircraftIconKey = null
    }

    /**
     * Sync the map to [view]. Call on the main thread.
     *
     * A peer whose fix has dropped out keeps a marker at the last place it was seen, drawn in the
     * ghost colour with its age in the title. It used to lose its marker instead, on the principle
     * that a stale position drawn as a current one is worse than no marker at all - which is true,
     * but the answer to it is to draw the position as stale. Without that the fleet map quietly
     * loses aircraft, and one that is missing cannot be told apart from one that left the network.
     */
    fun update(view: FleetView) {
        val map = mapWidget?.map ?: return
        val colours = view.memberColours
        updateOwnAircraft(view.own, colours)

        val wanted = view.peers.filter { it.place != null }
        val wantedIds = wanted.map { it.peer.beacon.deviceId }.toSet()

        (markers.keys + labels.keys).toSet().filter { it !in wantedIds }.forEach { deviceId ->
            runCatching { markers.remove(deviceId)?.remove() }
            runCatching { labels.remove(deviceId)?.remove() }
        }

        wanted.forEach { peer ->
            val beacon = peer.peer.beacon
            val place = peer.place ?: return@forEach
            val position = DJILatLng(place.latitudeDeg, place.longitudeDeg)
            val identity = colours[beacon.deviceId] ?: FleetPalette.UNKNOWN
            runCatching {
                val chevron = DJIBitmapDescriptorFactory.fromBitmap(chevronFor(peer, identity))
                val name = DJIBitmapDescriptorFactory.fromBitmap(labelFor(peer, identity))
                upsert(markers, beacon.deviceId, { map.addMarker(chevronOptions(position, chevron)) }) {
                    it.setPosition(position)
                    it.setRotation(beacon.headingDeg.toFloat())
                    it.setIcon(chevron)
                    it.setTitle(titleFor(peer))
                }
                // No rotation on this one: a name that turns with the aircraft is a name nobody
                // reads half the time.
                upsert(labels, beacon.deviceId, { map.addMarker(labelOptions(position, name)) }) {
                    it.setPosition(position)
                    it.setIcon(name)
                }
            }.onFailure { error ->
                Log.d(TAG, "Could not draw peer ${beacon.deviceId}: ${error.message}")
            }
        }
    }

    /**
     * Re-skin the map widget's own aircraft marker in this aircraft's colour, one size up.
     *
     * The widget already draws, positions and rotates a marker for this aircraft, and it does it
     * from the SDK rather than from a beacon. Drawing our own underneath it was tried and is wrong
     * twice over: the widget's red arrow has the higher z-index, so it sits on top, and two markers
     * for one aircraft is a pile-up. So its marker is kept and given our icon instead, which is
     * also how this aircraft comes to be drawn in the colour its row on the strip carries.
     */
    private fun updateOwnAircraft(
        own: FleetOwnView?,
        colours: Map<String, Int>,
    ) {
        val widget = mapWidget ?: return
        val identity = own?.let { colours[it.deviceId] } ?: return
        val key = "$identity|$compact"
        if (key == aircraftIconKey) return
        val icon = ownChevronFor(identity)
        runCatching {
            widget.setAircraftMarkerIcon(
                BitmapDrawable(widget.resources, icon),
                ANCHOR_CENTRE,
                ANCHOR_CENTRE,
            )
            aircraftIconKey = key
        }.onFailure { error ->
            Log.d(TAG, "Could not set this aircraft's marker icon: ${error.message}")
        }
    }

    /**
     * Apply [configure] to a peer's marker of this kind, adding it the first time.
     *
     * The map is built asynchronously and can refuse a marker before it is ready, so a peer with no
     * marker yet is simply tried again on the next tick rather than being remembered as drawn.
     */
    private inline fun upsert(
        store: MutableMap<String, DJIMarker>,
        deviceId: String,
        add: () -> DJIMarker?,
        configure: (DJIMarker) -> Unit,
    ) {
        val existing = store[deviceId]
        if (existing != null) {
            configure(existing)
            return
        }
        add()?.let { marker ->
            store[deviceId] = marker
            configure(marker)
        }
    }

    private fun chevronOptions(
        position: DJILatLng,
        icon: DJIBitmapDescriptor,
    ): DJIMarkerOptions =
        DJIMarkerOptions()
            .position(position)
            .icon(icon)
            .anchor(ANCHOR_CENTRE, ANCHOR_CENTRE)
            .zIndex(PEER_MARKER_Z)
            .visible(true)

    private fun labelOptions(
        position: DJILatLng,
        icon: DJIBitmapDescriptor,
    ): DJIMarkerOptions =
        DJIMarkerOptions()
            .position(position)
            .icon(icon)
            .anchor(ANCHOR_CENTRE, ANCHOR_TOP)
            .zIndex(PEER_LABEL_Z)
            .visible(true)

    /** Drop every peer marker, for teardown and for when the mesh is switched off. */
    fun clear() {
        markers.values.forEach { marker -> runCatching { marker.remove() } }
        markers.clear()
        labels.values.forEach { marker -> runCatching { marker.remove() } }
        labels.clear()
        iconCache.evictAll()
    }

    private fun titleFor(peer: FleetPeerView): String {
        val beacon = peer.peer.beacon
        val altitude = "${beacon.altitudeAglM.toInt()}m AGL"
        val battery = if (beacon.batteryPercent >= 0) " · ${beacon.batteryPercent}%" else ""
        val range = peer.solution?.let { " · ${FleetGeo.formatDistance(it.slantRangeM)}" }.orEmpty()
        val seen =
            peer.placeAgeMs
                ?.let { " · last seen ${it / MILLIS_PER_SECOND}s ago" }
                .orEmpty()
        return "${peer.displayName} · $altitude$battery$range$seen"
    }

    /**
     * The peer's chevron: its identity colour filled in, its state drawn as the ring around it.
     *
     * Two things a pilot needs from a marker - which aircraft this is, and whether to look at it -
     * used to compete for the same fill colour, which made every aircraft in a warning look alike.
     * The fill now matches the name on the strip, and warning, caution, lagging and lost are the
     * ring drawn over it. The name is a separate marker, so this bitmap is only the shape.
     */
    private fun chevronFor(
        peer: FleetPeerView,
        identity: Int,
    ): Bitmap {
        val ghosting = peer.placeAgeMs != null
        val fill = if (ghosting) FleetPalette.dimmed(identity, GHOST_FILL_ALPHA) else identity
        val ring = ringColorFor(peer)
        val key = "chevron|$fill|$ring|$compact"
        iconCache.get(key)?.let { return it }
        val bitmap = drawChevron(peerGeometry(), fill, ring)
        iconCache.put(key, bitmap)
        return bitmap
    }

    /** This aircraft's own chevron, in its own colour with a white ring rather than a state. */
    private fun ownChevronFor(identity: Int): Bitmap {
        val key = "own|$identity|$compact"
        iconCache.get(key)?.let { return it }
        val bitmap = drawChevron(ownGeometry(), identity, COLOR_OWN_RING)
        iconCache.put(key, bitmap)
        return bitmap
    }

    private fun peerGeometry(): ChevronGeometry =
        if (compact) {
            ChevronGeometry(
                COMPACT_CHEVRON_HALF_WIDTH_PX,
                COMPACT_CHEVRON_HEIGHT_PX,
                COMPACT_CHEVRON_PAD_PX,
                COMPACT_OUTLINE_WIDTH_PX,
            )
        } else {
            ChevronGeometry(CHEVRON_HALF_WIDTH_PX, CHEVRON_HEIGHT_PX, CHEVRON_PAD_PX, OUTLINE_WIDTH_PX)
        }

    private fun ownGeometry(): ChevronGeometry =
        if (compact) {
            ChevronGeometry(
                COMPACT_OWN_CHEVRON_HALF_WIDTH_PX,
                COMPACT_OWN_CHEVRON_HEIGHT_PX,
                COMPACT_OWN_CHEVRON_PAD_PX,
                COMPACT_OWN_OUTLINE_WIDTH_PX,
            )
        } else {
            ChevronGeometry(
                OWN_CHEVRON_HALF_WIDTH_PX,
                OWN_CHEVRON_HEIGHT_PX,
                OWN_CHEVRON_PAD_PX,
                OWN_OUTLINE_WIDTH_PX,
            )
        }

    /**
     * The peer's name on its own layer, in the colour its row carries on the strip.
     *
     * It sits as close under the arrow as the arrow allows at its current heading, which is what
     * [FleetIconLayout.labelOffsetPx] works out; nothing else about the bitmap depends on where the
     * aircraft is pointing, which is why the name can stay upright while the chevron turns.
     */
    private fun labelFor(
        peer: FleetPeerView,
        identity: Int,
    ): Bitmap {
        val colour = if (peer.placeAgeMs != null) COLOR_GHOST else identity
        val geometry = peerGeometry()
        val gap =
            FleetIconLayout.labelOffsetPx(
                halfWidthPx = geometry.halfWidthPx,
                heightPx = geometry.heightPx,
                headingDeg = peer.peer.beacon.headingDeg,
                clearancePx = if (compact) COMPACT_LABEL_CLEARANCE_PX else LABEL_CLEARANCE_PX,
            )
        val key = "label|${peer.displayName}|$colour|$gap|$compact"
        iconCache.get(key)?.let { return it }
        val bitmap = drawLabel(peer.displayName, colour, gap)
        iconCache.put(key, bitmap)
        return bitmap
    }

    private fun ringColorFor(peer: FleetPeerView): Int =
        when {
            peer.placeAgeMs != null -> COLOR_GHOST
            peer.liveness == PeerLiveness.LOST -> COLOR_LOST
            peer.liveness == PeerLiveness.STALE -> COLOR_STALE
            else ->
                when (peer.advisoryLevel) {
                    AdvisoryLevel.WARNING -> COLOR_WARNING
                    AdvisoryLevel.CAUTION -> COLOR_CAUTION
                    else -> COLOR_NORMAL
                }
        }

    /**
     * A chevron in its aircraft's colour, ringed by its state.
     *
     * Drawn in code rather than loaded as a vector so the fill can carry identity and the ring can
     * carry the advisory level, neither of which a static drawable could do without one asset per
     * aircraft per state.
     */
    private fun drawChevron(
        geometry: ChevronGeometry,
        fill: Int,
        ring: Int,
    ): Bitmap {
        // The padding is room for the halo, which would otherwise be clipped at the bitmap's edge.
        val halfWidth = geometry.halfWidthPx
        val height = geometry.heightPx
        val pad = geometry.padPx
        val outlineWidth = geometry.outlineWidthPx

        val bitmap = Bitmap.createBitmap((halfWidth * 2).toInt() + pad * 2, height.toInt() + pad * 2, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val centreX = bitmap.width / 2f
        val noseY = pad.toFloat()
        val tailY = noseY + height

        val chevron =
            Path().apply {
                moveTo(centreX, noseY)
                lineTo(centreX + halfWidth, tailY)
                lineTo(centreX, tailY - height / 4f)
                lineTo(centreX - halfWidth, tailY)
                close()
            }

        // Black halo first, so the shape stays legible over both satellite imagery and a pale
        // street map without needing to know which one the operator picked.
        canvas.drawPath(
            chevron,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = outlineWidth * HALO_SCALE
                color = Color.BLACK
            },
        )
        canvas.drawPath(
            chevron,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = fill
            },
        )
        // The state ring sits on the outline, half over the fill and half over the halo, so both
        // the aircraft's colour and its state are visible at once and at icon size.
        canvas.drawPath(
            chevron,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = outlineWidth
                color = ring
            },
        )

        return bitmap
    }

    /**
     * A name, drawn as outlined text and placed clear of the chevron above it.
     *
     * A dark plate behind the label was tried here and reads worse: it covers the map, and it puts
     * a second shape next to every chevron that has nothing to do with what the chevron means. The
     * outline is kept, thinner than it originally was - a stroke as thick as a fifth of the glyph
     * height was what made the letters look smeared, not the idea of outlining them.
     *
     * The name starts outside the circle the chevron turns through rather than just below the
     * aircraft, because the arrow sweeps that circle as the heading changes and a name inside it is
     * struck through whenever the aircraft points at its own label.
     */
    private fun drawLabel(
        label: String,
        colour: Int,
        gap: Int,
    ): Bitmap {
        val labelTextPx = if (compact) COMPACT_LABEL_TEXT_PX else LABEL_TEXT_PX
        val pad = if (compact) COMPACT_LABEL_PAD_PX else LABEL_PAD_PX
        val outlineWidth = if (compact) COMPACT_LABEL_OUTLINE_PX else LABEL_OUTLINE_PX

        val textPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = labelTextPx
                textAlign = Paint.Align.LEFT
                isFakeBoldText = true
            }
        val metrics = textPaint.fontMetrics
        val textWidth = ceil(textPaint.measureText(label)).toInt()
        val textHeight = ceil(metrics.descent - metrics.ascent).toInt()

        // The gap is transparent and is what keeps the name out from under the arrow; the padding
        // is room for the outline, which would otherwise be clipped at the edges.
        val bitmap =
            Bitmap.createBitmap(textWidth + pad * 2, textHeight + pad * 2 + gap, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val baseline = gap + pad - metrics.ascent
        textPaint.style = Paint.Style.STROKE
        textPaint.strokeWidth = outlineWidth
        textPaint.color = Color.BLACK
        canvas.drawText(label, pad.toFloat(), baseline, textPaint)
        textPaint.style = Paint.Style.FILL
        textPaint.color = colour
        canvas.drawText(label, pad.toFloat(), baseline, textPaint)
        return bitmap
    }

    /** One chevron's measurements, so this aircraft's marker and a peer's share one drawing path. */
    private data class ChevronGeometry(
        val halfWidthPx: Float,
        val heightPx: Float,
        val padPx: Int,
        val outlineWidthPx: Float,
    )
}
