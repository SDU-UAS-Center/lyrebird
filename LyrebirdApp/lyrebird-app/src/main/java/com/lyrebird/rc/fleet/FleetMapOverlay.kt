package com.lyrebird.rc.fleet

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.Log
import android.util.LruCache
import dji.v5.ux.map.MapWidget
import dji.v5.ux.mapkit.core.models.DJILatLng
import dji.v5.ux.mapkit.core.models.annotations.DJIMarker
import dji.v5.ux.mapkit.core.models.annotations.DJIMarkerOptions
import dji.v5.ux.mapkit.core.models.DJIBitmapDescriptorFactory

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
internal class FleetMapOverlay(private val mapWidget: MapWidget?) {

    companion object {
        private const val TAG = "LyrebirdFleetMap"

        /** Above the fly-zone shading, below this aircraft's own marker: peers never hide it. */
        private const val PEER_MARKER_Z = 5

        private const val ICON_WIDTH_PX = 108
        private const val ICON_HEIGHT_PX = 92
        private const val CHEVRON_HALF_WIDTH_PX = 15f
        private const val CHEVRON_HEIGHT_PX = 34f
        private const val LABEL_TEXT_PX = 22f
        private const val LABEL_PADDING_PX = 5f
        private const val OUTLINE_WIDTH_PX = 3f

        /** Anchor on the chevron's centre so rotation pivots about the aircraft, not the label. */
        private const val ANCHOR_U = 0.5f
        private const val ANCHOR_V = 0.25f

        private const val ICON_CACHE_ENTRIES = 24

        private const val COLOR_WARNING = 0xFFFF5252.toInt()
        private const val COLOR_CAUTION = 0xFFFFB300.toInt()
        private const val COLOR_NORMAL = 0xFF4FC3F7.toInt()
        private const val COLOR_STALE = 0xFFBDA35A.toInt()
        private const val COLOR_LOST = 0xFF8D6E63.toInt()
    }

    private val markers = mutableMapOf<String, DJIMarker>()

    /** Icons are keyed by what they depict, so a peer holding station never redraws one. */
    private val iconCache = LruCache<String, Bitmap>(ICON_CACHE_ENTRIES)

    /**
     * Sync the map to [view]. Call on the main thread.
     *
     * Peers with no GPS fix get no marker at all rather than one at their last known point: a
     * stale position drawn as a current one is worse than an absent marker, because the pilot has
     * no way to tell the two apart.
     */
    fun update(view: FleetView) {
        val map = mapWidget?.map ?: return
        val wanted = view.peers.filter { it.peer.beacon.hasRealPosition() }
        val wantedIds = wanted.map { it.peer.beacon.deviceId }.toSet()

        markers.keys.filter { it !in wantedIds }.forEach { deviceId ->
            runCatching { markers.remove(deviceId)?.remove() }
        }

        wanted.forEach { peer ->
            val beacon = peer.peer.beacon
            val position = DJILatLng(beacon.latitudeDeg, beacon.longitudeDeg)
            val existing = markers[beacon.deviceId]
            runCatching {
                if (existing == null) {
                    val options = DJIMarkerOptions()
                        .position(position)
                        .icon(DJIBitmapDescriptorFactory.fromBitmap(iconFor(peer)))
                        .anchor(ANCHOR_U, ANCHOR_V)
                        .rotation(beacon.headingDeg.toFloat())
                        .zIndex(PEER_MARKER_Z)
                        .title(titleFor(peer))
                        .visible(true)
                    map.addMarker(options)?.let { markers[beacon.deviceId] = it }
                } else {
                    existing.setPosition(position)
                    existing.setRotation(beacon.headingDeg.toFloat())
                    existing.setIcon(DJIBitmapDescriptorFactory.fromBitmap(iconFor(peer)))
                    existing.setTitle(titleFor(peer))
                }
            }.onFailure { error ->
                Log.d(TAG, "Could not draw peer ${beacon.deviceId}: ${error.message}")
            }
        }
    }

    /** Drop every peer marker, for teardown and for when the mesh is switched off. */
    fun clear() {
        markers.values.forEach { marker -> runCatching { marker.remove() } }
        markers.clear()
        iconCache.evictAll()
    }

    private fun titleFor(peer: FleetPeerView): String {
        val beacon = peer.peer.beacon
        val altitude = "${beacon.altitudeAglM.toInt()}m AGL"
        val battery = if (beacon.batteryPercent >= 0) " · ${beacon.batteryPercent}%" else ""
        val range = peer.solution?.let { " · ${FleetGeo.formatDistance(it.slantRangeM)}" }.orEmpty()
        return "${peer.displayName} · $altitude$battery$range"
    }

    private fun iconFor(peer: FleetPeerView): Bitmap {
        val label = peer.displayName
        val color = colorFor(peer)
        val key = "$label|$color"
        iconCache.get(key)?.let { return it }
        val bitmap = drawMarker(label, color)
        iconCache.put(key, bitmap)
        return bitmap
    }

    private fun colorFor(peer: FleetPeerView): Int = when (peer.liveness) {
        PeerLiveness.LOST -> COLOR_LOST
        PeerLiveness.STALE -> COLOR_STALE
        PeerLiveness.LIVE -> when (peer.advisoryLevel) {
            AdvisoryLevel.WARNING -> COLOR_WARNING
            AdvisoryLevel.CAUTION -> COLOR_CAUTION
            else -> COLOR_NORMAL
        }
    }

    /**
     * A chevron with the peer's name beneath it.
     *
     * Drawn in code rather than loaded as a vector so the fill colour can carry the advisory level
     * and the label can carry the name, neither of which a static drawable could do without one
     * asset per aircraft per state.
     */
    private fun drawMarker(label: String, color: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(ICON_WIDTH_PX, ICON_HEIGHT_PX, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val centreX = ICON_WIDTH_PX / 2f
        val noseY = (ICON_HEIGHT_PX - CHEVRON_HEIGHT_PX) / 2f - LABEL_TEXT_PX / 2f
        val tailY = noseY + CHEVRON_HEIGHT_PX

        val chevron = Path().apply {
            moveTo(centreX, noseY)
            lineTo(centreX + CHEVRON_HALF_WIDTH_PX, tailY)
            lineTo(centreX, tailY - CHEVRON_HEIGHT_PX / 4f)
            lineTo(centreX - CHEVRON_HALF_WIDTH_PX, tailY)
            close()
        }

        // Dark outline first, so the shape stays legible over both satellite imagery and a pale
        // street map without needing to know which one the operator picked.
        canvas.drawPath(
            chevron,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = OUTLINE_WIDTH_PX
                this.color = Color.BLACK
            }
        )
        canvas.drawPath(
            chevron,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                this.color = color
            }
        )

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = LABEL_TEXT_PX
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        val baseline = tailY + LABEL_TEXT_PX + LABEL_PADDING_PX
        textPaint.color = Color.BLACK
        textPaint.style = Paint.Style.STROKE
        textPaint.strokeWidth = OUTLINE_WIDTH_PX
        canvas.drawText(label, centreX, baseline, textPaint)
        textPaint.color = Color.WHITE
        textPaint.style = Paint.Style.FILL
        canvas.drawText(label, centreX, baseline, textPaint)

        return bitmap
    }
}
