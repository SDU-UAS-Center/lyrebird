package com.lyrebird.rc.webrtc

import android.util.Log
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Whether [PeriodicKeyframeEncoder] should keep forcing periodic IDRs for one drone's WHIP
 * publish, based on what's actually attached to its MediaMTX path.
 *
 * The forced keyframe (see [PeriodicKeyframeEncoderFactory]) exists only for a consumer with no
 * PLI channel of its own — an RTSP/RTMP/HLS puller attaching to MediaMTX's republish. A pure
 * WHEP/WebRTC viewer already gets a keyframe via real PLI on join and gains nothing from paying
 * for one every interval regardless. This polls MediaMTX's own `/v3/paths/list` — the same API
 * `GroundStation/video_test/webapp/server.py` already reads, just for reader *type* instead of
 * count — to tell the two cases apart. Verified empirically against a running MediaMTX instance
 * (an RTSP reader reports `readers: [{"type": "rtspSession", ...}]`), not assumed from memory.
 *
 * Fails safe: any error, timeout, or unrecognised response leaves [shouldForceKeyframe] at its
 * default `true` — a wasted keyframe is a much smaller problem than an RTSP/HLS consumer
 * silently waiting several seconds for a picture because a flaky poll turned the safety net off.
 */
internal class MediaMtxConsumerWatcher(
    private val pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS
) {
    companion object {
        private const val TAG = "MediaMtxConsumerWatcher"
        private const val DEFAULT_POLL_INTERVAL_MS = 3_000L
        private const val MEDIAMTX_API_PORT = 9997
        private const val CONNECT_TIMEOUT_MS = 2_000
        private const val READ_TIMEOUT_MS = 2_000
    }

    @Volatile var shouldForceKeyframe: Boolean = true
        private set

    private var executor: ScheduledExecutorService? = null
    private val isRunning = AtomicBoolean(false)
    private var lastInboundFramesInError: Int? = null

    /**
     * [host] is the ground-station machine [WhipPublisher] is already publishing to (parsed from
     * the WHIP URL, see [mediaMtxHostAndPathFromWhipUrl]) — MediaMTX's API lives on the same
     * host, port 9997 by convention (the same one `groundstation.md` documents).
     */
    fun start(host: String, pathName: String) {
        if (!isRunning.compareAndSet(false, true)) return
        shouldForceKeyframe = true
        val scheduler = Executors.newSingleThreadScheduledExecutor()
        executor = scheduler
        scheduler.scheduleWithFixedDelay(
            { pollOnce(host, pathName) }, 0, pollIntervalMs, TimeUnit.MILLISECONDS
        )
    }

    /** Safe default restored on stop, so a stale "don't force" decision never outlives this publish. */
    fun stop() {
        if (!isRunning.compareAndSet(true, false)) return
        executor?.shutdownNow()
        executor = null
        shouldForceKeyframe = true
        lastInboundFramesInError = null
    }

    /**
     * The entire body is one [runCatching]: `ScheduledExecutorService.scheduleWithFixedDelay`
     * silently and *permanently* stops rescheduling a periodic task the moment it throws once
     * (documented behavior, not a bug in the executor) -- so letting anything here escape
     * uncaught would kill this poll loop forever with zero indication it ever happened, which is
     * worse than any single failure this method could otherwise report. Logs the outcome either
     * way so "quietly succeeding" and "silently died" are never ambiguous from the outside again.
     */
    private fun pollOnce(host: String, pathName: String) {
        runCatching {
            val body = fetchPathsList(host)
            val decision = shouldForceKeyframeFor(body, pathName)
            val inboundFramesInError = inboundFrameErrorsFor(body, pathName)
            val packetLossRecovery = hasNewInboundFrameErrors(
                lastInboundFramesInError,
                inboundFramesInError
            )
            lastInboundFramesInError = inboundFramesInError
            shouldForceKeyframe = decision || packetLossRecovery
            if (packetLossRecovery) {
                Log.w(
                    TAG,
                    "MediaMTX reported new H264 ingest errors ($inboundFramesInError), " +
                        "forcing a recovery keyframe"
                )
            } else {
                Log.d(TAG, "MediaMTX poll ok, forceKeyframe=$decision")
            }
        }.onFailure { error ->
            shouldForceKeyframe = true
            Log.d(TAG, "MediaMTX paths poll failed, keeping keyframe forcing on: ${error.message}")
        }
    }

    private fun fetchPathsList(host: String): String {
        val url = URL("http://$host:$MEDIAMTX_API_PORT/v3/paths/list")
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            val status = conn.responseCode
            if (status != 200) throw IOException("MediaMTX paths list HTTP $status")
            return conn.inputStream.bufferedReader().readText()
        } finally {
            conn.disconnect()
        }
    }
}

/**
 * Parses a WHIP publish URL (`http://<host>:8889/<drone_name>/whip`, the shape
 * `groundstation.md`/`mavlink.md` document) into the ground-station host and the MediaMTX path
 * name, or null if it doesn't look like that shape. Pure and testable without a real URL/network.
 */
internal fun mediaMtxHostAndPathFromWhipUrl(whipUrl: String): Pair<String, String>? = runCatching {
    val url = URL(whipUrl)
    val pathName = url.path.trim('/').removeSuffix("/whip")
    if (url.host.isBlank() || pathName.isBlank()) null else url.host to pathName
}.getOrNull()

/**
 * Pure parsing: true (force the keyframe) unless [pathName] is found in [pathsListJson] and
 * every one of its readers is a WebRTC session. Malformed JSON, a missing path, a missing
 * readers array, or any non-WebRTC reader (rtspSession, rtmpConn, hlsMuxer, ...) all resolve to
 * true — see the fail-safe note on [MediaMtxConsumerWatcher]. A path with zero readers resolves
 * to false: nothing is attached to miss out on a fast join, so forcing a keyframe would be pure
 * waste.
 */
internal fun shouldForceKeyframeFor(pathsListJson: String, pathName: String): Boolean {
    val path = runCatching {
        val items = JSONObject(pathsListJson).getJSONArray("items")
        (0 until items.length())
            .map { items.getJSONObject(it) }
            .firstOrNull { it.optString("name") == pathName }
    }.getOrNull() ?: return true

    val readers = runCatching { path.getJSONArray("readers") }.getOrNull() ?: return true
    for (i in 0 until readers.length()) {
        val type = readers.optJSONObject(i)?.optString("type").orEmpty()
        if (!type.contains("webRTC", ignoreCase = true)) return true
    }
    return false
}

/** Returns MediaMTX's cumulative H264 ingest-error count for [pathName], or null when unavailable. */
internal fun inboundFrameErrorsFor(pathsListJson: String, pathName: String): Int? = runCatching {
    val items = JSONObject(pathsListJson).getJSONArray("items")
    val path = (0 until items.length())
        .map { items.getJSONObject(it) }
        .firstOrNull { it.optString("name") == pathName }
        ?: return@runCatching null
    if (!path.has("inboundFramesInError") || path.isNull("inboundFramesInError")) {
        null
    } else {
        path.optInt("inboundFramesInError").coerceAtLeast(0)
    }
}.getOrNull()

/** A counter reset after a relay restart is not itself a new packet-loss event. */
internal fun hasNewInboundFrameErrors(previous: Int?, current: Int?): Boolean =
    previous != null && current != null && current > previous
