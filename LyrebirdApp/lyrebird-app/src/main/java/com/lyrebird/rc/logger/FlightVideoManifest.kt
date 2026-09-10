package com.lyrebird.rc.logger

import android.util.Log
import dji.v5.manager.datacenter.MediaDataCenter
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Writes, next to each flight's JSONL log, a manifest naming the videos the aircraft recorded on
 * its SD card during that flight.
 *
 * No video bytes are moved: the aircraft's media index is read through the SDK and each video
 * whose recording start (the timestamp baked into DJI's `DJI_YYYYMMDDHHMMSS_XXXX_D.MP4` names)
 * falls inside the flight-session window is listed with its companions (.SRT/.LRF) and a proposed
 * prefixed name. The actual rename is a plain filesystem operation for whoever has the card
 * mounted, or for the download path — the manifest just makes the mapping durable beside the
 * flight log it belongs to.
 */
object FlightVideoManifest {

    private const val TAG = "FlightVideoManifest"

    /** Clock skew and recording-start margin around the session window, in seconds. */
    const val WINDOW_MARGIN_SECONDS = 120L

    private val DJI_VIDEO_NAME = Regex("^DJI_(\\d{14})_\\d{4}_[A-Z]\\.(MP4|MOV)$", RegexOption.IGNORE_CASE)

    /** One video file on the SD card, reduced to what the manifest needs. */
    data class VideoEntry(
        val fileName: String,
        val sizeBytes: Long,
        val subFiles: List<String>
    )

    /**
     * Write `<log stem>.videos.json` beside [logFile] and append a VIDEOS line to the log itself.
     * Runs on its own thread; never throws.
     */
    fun writeForSession(
        logFile: File?,
        droneName: String,
        vehicleSerial: String,
        windowSec: Pair<Long, Long>?
    ) {
        if (logFile == null || windowSec == null) return
        Thread {
            runCatching {
                val matched = matchFlightVideos(pullSdCardVideos(), windowSec, WINDOW_MARGIN_SECONDS)
                val manifest = manifestJson(logFile.name, droneName, vehicleSerial, windowSec, matched)
                val manifestName = logFile.name.removeSuffix(".jsonl") + ".videos.json"
                File(logFile.parentFile, manifestName).writeText(manifest.toString(2))
                appendLogLine(logFile, matched, manifestName)
                Log.i(TAG, "Wrote ${matched.size} video reference(s) → $manifestName")
            }.onFailure { error ->
                Log.w(TAG, "Flight video manifest failed: ${error.message}")
            }
        }.start()
    }

    // -- Matching (pure, unit-tested) ------------------------------------------

    /** Videos whose recording start falls inside the session window, widened by [marginSec]. */
    internal fun matchFlightVideos(
        entries: List<VideoEntry>,
        windowSec: Pair<Long, Long>,
        marginSec: Long
    ): List<VideoEntry> {
        val lo = windowSec.first - marginSec
        val hi = windowSec.second + marginSec
        return entries.filter { entry ->
            val start = parseDjiVideoTimestamp(entry.fileName)
            start != null && start in lo..hi
        }
    }

    /** Recording-start epoch seconds from DJI's filename, or null when it is not a DJI video. */
    internal fun parseDjiVideoTimestamp(fileName: String): Long? {
        val match = DJI_VIDEO_NAME.matchEntire(fileName) ?: return null
        return runCatching {
            SimpleDateFormat("yyyyMMddHHmmss", Locale.US).parse(match.groupValues[1])!!.time / 1000
        }.getOrNull()
    }

    internal fun manifestJson(
        logName: String,
        droneName: String,
        vehicleSerial: String,
        windowSec: Pair<Long, Long>,
        videos: List<VideoEntry>
    ): JSONObject {
        val prefix = sanitize("${droneName}_${vehicleSerial}")
        val array = JSONArray()
        videos.forEach { entry ->
            array.put(
                JSONObject()
                    .put("original", entry.fileName)
                    .put("recordStartEpochSec", parseDjiVideoTimestamp(entry.fileName) ?: -1L)
                    .put("sizeBytes", entry.sizeBytes)
                    .put("subFiles", JSONArray(entry.subFiles))
                    .put("proposedName", "${prefix}_${entry.fileName}")
            )
        }
        return JSONObject()
            .put("flightLog", logName)
            .put("droneName", droneName)
            .put("vehicleSerial", vehicleSerial)
            .put("sessionStartEpochSec", windowSec.first)
            .put("sessionEndEpochSec", windowSec.second)
            .put("videos", array)
    }

    internal fun sanitize(value: String): String =
        value.replace(Regex("[^a-zA-Z0-9._-]"), "_").trim('_').ifBlank { "unknown" }

    // -- SDK pull and log append (integration side) ------------------------------

    private fun pullSdCardVideos(): List<VideoEntry> {
        val list = MediaDataCenter.getInstance().mediaManager.mediaFileListData?.data ?: return emptyList()
        return list.mapNotNull { file ->
            file.fileName?.takeIf { it.isNotBlank() }?.let { name ->
                VideoEntry(
                    fileName = name,
                    sizeBytes = file.fileSize,
                    subFiles = file.subMediaFile.orEmpty().mapNotNull { it.fileName }
                )
            }
        }
    }

    private fun appendLogLine(logFile: File, videos: List<VideoEntry>, manifestName: String) {
        runCatching {
            val names = JSONArray(videos.map { it.fileName })
            val line = JSONObject()
                .put("t", System.currentTimeMillis() / 1000)
                .put("type", "VIDEOS")
                .put("manifest", manifestName)
                .put("files", names)
            logFile.appendText(line.toString() + "\n")
        }.onFailure { error ->
            Log.w(TAG, "Could not append VIDEOS line to flight log: ${error.message}")
        }
    }
}
