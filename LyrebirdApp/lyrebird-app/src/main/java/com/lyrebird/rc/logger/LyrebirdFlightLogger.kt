package com.lyrebird.rc.logger

import android.os.Build
import android.os.Environment
import android.util.Log
import dji.v5.utils.common.ContextUtil
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * LyrebirdFlightLogger — persists flight event data to a location that survives
 * app uninstalls and device reflashing.
 *
 * === Lyrebird JSONL logs ===
 * Written to the best available storage (priority order):
 *   1. Removable microSD card root → Lyrebird/FlightLogs/YYYY-MM-DD/ (needs MANAGE_EXTERNAL_STORAGE on API 30+)
 *   2. Documents/lyrebird/FlightLogs/YYYY-MM-DD/  (needs MANAGE_EXTERNAL_STORAGE on API 30+)
 *   3. Android/data/<pkg>/files/FlightLogs/YYYY-MM-DD/  (app-external fallback — deleted on uninstall)
 *
 * One JSONL file is created per flight session. Each line is one JSON event:
 *   {"t":1711188600,"type":"SESSION_START","drone":"scout"}
 *   {"t":1711188610,"type":"COMMAND","cmd":"/send/goto","params":"48.85,2.35,50"}
 *   {"t":1711188615,"type":"TELEMETRY","speed":5.1,"heading":90.0,"batteryLevel":82,...}
 *   {"t":1711188700,"type":"STATUS","status":"RETURNING_HOME"}
 *   {"t":1711188750,"type":"SESSION_END","reason":"landed"}
 *
 * === DJI TXT flight records ===
 * The SDK writes these automatically to:
 *   Android/data/com.com.lyrebird.rc/files/DJI/FlightRecord/
 * That folder lives in app-specific external storage and is deleted on uninstall.
 * On every app launch (and after landing) we mirror any new files to:
 *   Lyrebird/DJI_FlightRecords/  (same storage-priority chain as above)
 * Already-copied files are skipped by filename, so the sync is always safe to repeat.
 */
object LyrebirdFlightLogger {

    private const val TAG = "LyrebirdFlightLogger"

    @Volatile
    var droneName: String = "unknown"
        private set

    @Volatile private var writer: PrintWriter? = null
    @Volatile private var logFile: File? = null
    @Volatile private var sessionActive = false

    /** True while a log file is open. */
    val isSessionActive: Boolean get() = sessionActive

    /** Absolute path of the current log file, or null if no session is open. */
    val currentLogPath: String? get() = logFile?.absolutePath

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Set (or update) the drone name used in log file names.
     * Safe characters only — everything else is replaced with '_'.
     */
    fun setDroneName(name: String) {
        droneName = name.trim().replace(Regex("[^a-zA-Z0-9._-]"), "_").ifBlank { "unknown" }
    }

    /**
     * Open a new JSONL log file and write the SESSION_START marker.
     * If a session is already open it is closed first (handles missed landing events).
     */
    fun startSession() {
        if (sessionActive) endSession("session_restart")
        try {
            val dir = FlightLogStorage.resolveLogDir()
            if (dir == null) {
                Log.e(TAG, "Cannot resolve log directory — logging disabled for this session")
                return
            }
            val timeStr = SimpleDateFormat("HH-mm-ss", Locale.US).format(Date())
            val file = File(dir, "${timeStr}_${droneName}.jsonl")
            logFile = file
            writer = PrintWriter(FileWriter(file, /* append = */ false))
            sessionActive = true
            commitLog("SESSION_START", mapOf("drone" to droneName, "logDir" to dir.absolutePath))
            Log.i(TAG, "Flight log started → ${file.absolutePath}")
        } catch (e: IOException) {
            Log.e(TAG, "Failed to start flight log: ${e.message}")
        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to start flight log: ${e.message}")
        }
    }

    /** Write SESSION_END and close the file. */
    fun endSession(reason: String = "app_stopped") {
        if (!sessionActive) return
        commitLog("SESSION_END", mapOf("reason" to reason))
        flushAndClose()
        sessionActive = false
        Log.i(TAG, "Flight log ended ($reason) → ${logFile?.absolutePath}")
    }

    /**
     * Log a telemetry snapshot.
     * Accepts the raw JSON string from VirtualStickFragment.getTelemetryJson() and
     * splices in "t" and "type" fields without a full re-parse.
     * Call at most every 5 seconds to keep file sizes manageable.
     */
    fun logTelemetry(telemetryJson: String) {
        if (!sessionActive || telemetryJson.isBlank()) return
        val ts = System.currentTimeMillis() / 1000
        // Splice timestamp + type into the existing flat JSON object
        val inner = telemetryJson.trim().removePrefix("{").removeSuffix("}")
        writeLine("""{"t":$ts,"type":"TELEMETRY",$inner}""")
    }

    /**
     * Log an HTTP command.
     * [endpoint] is the URI (e.g. "/send/goto"), [params] is the POST body.
     */
    fun logCommand(endpoint: String, params: String = "") {
        val fields = mutableMapOf<String, Any>("cmd" to endpoint)
        if (params.isNotBlank()) fields["params"] = params.take(300) // cap length
        commitLog("COMMAND", fields)
    }

    /**
     * Log a MAVLink command and what it was answered with.
     *
     * Logged in the same stream and the same shape as an HTTP command, because after the fact
     * the question is what the aircraft was asked to do, not which socket carried the request.
     *
     * The parameters are recorded raw, before any interpretation. That is the point of them:
     * the two defects this logging was added for -- a goto that flew backwards and an altitude
     * change that did nothing -- were both a sentinel value in a parameter being flown as if it
     * were a real one, and both are obvious in a line that shows `p1=-1.0` or a NaN latitude and
     * invisible in anything recorded further downstream. There were no MAVLink lines at all when
     * they were first seen in the field, so there was nothing to look at.
     */
    fun logMavlinkCommand(
        command: Int,
        params: List<Float>,
        result: Int,
        signed: Boolean,
        senderSystem: Int
    ) {
        commitLog(
            "COMMAND",
            mapOf(
                "cmd" to "MAV_CMD_$command",
                "via" to "mavlink",
                "params" to params.joinToString(",") { formatParam(it) },
                "result" to result,
                "signed" to signed,
                "from" to senderSystem
            )
        )
    }

    /** NaN and the negative sentinels have to survive into the log as themselves, not as 0. */
    private fun formatParam(value: Float): String = when {
        value.isNaN() -> "NaN"
        value.isInfinite() -> if (value > 0) "Inf" else "-Inf"
        else -> value.toString()
    }

    /** Log a drone status / mode change (e.g. "NAVIGATING", "RETURNING_HOME"). */
    fun logStatus(status: String) {
        commitLog("STATUS", mapOf("status" to status))
    }

    /**
     * Sync DJI SDK-managed flight records from [djiLogPath] to the Lyrebird log root.
     *
     * This is a simple one-way mirror: any TXT/CSV/CLOG file in the DJI folder that does
     * not already exist (by name) in the Lyrebird destination is copied over.
     * Existing files are left untouched (no overwrite), so the call is safe to run at
     * every app launch without duplicating anything.
     *
     * Returns the number of files newly copied.
     */
    fun syncDjiFlightLogs(djiLogPath: String): Int {
        return DjiFlightLogSync.sync(djiLogPath, FlightLogStorage.resolveDjiSyncDir())
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun commitLog(type: String, fields: Map<String, Any> = emptyMap()) {
        if (!sessionActive) return
        runCatching {
            val obj = JSONObject()
            obj.put("t", System.currentTimeMillis() / 1000)
            obj.put("type", type)
            fields.forEach { (k, v) -> obj.put(k, v) }
            writeLine(obj.toString())
        }.onFailure { failure -> Log.w(TAG, "commitLog error: ${failure.message}") }
    }

    @Synchronized
    private fun writeLine(line: String) {
        runCatching {
            writer?.println(line)
            writer?.flush()
        }.onFailure { failure -> Log.w(TAG, "writeLine error: ${failure.message}") }
    }

    @Synchronized
    private fun flushAndClose() {
        runCatching {
            writer?.flush()
            writer?.close()
        }.onFailure { failure ->
            Log.w(TAG, "flushAndClose error: ${failure.message}")
        }
        writer = null
    }
}

private object DjiFlightLogSync {
    private const val TAG = "LyrebirdFlightLogger"
    private val djiFlightRecordExtensions = setOf("txt", "csv", "clog")

    fun sync(djiLogPath: String, destDir: File?): Int {
        val sourceDir = djiLogPath.takeIf { it.isNotBlank() }?.let(::File)
        return when {
            sourceDir == null -> logSkipped("empty source path")
            !sourceDir.isDirectory -> logSkipped("source does not exist: $djiLogPath")
            destDir == null -> logFailed("could not create destination directory")
            else -> copyDjiFlightLogs(sourceDir, destDir)
        }
    }

    private fun copyDjiFlightLogs(sourceDir: File, destDir: File): Int {
        return runCatching {
            sourceDir.walk()
                .filter { it.isDjiFlightRecord() }
                .count { source -> copyDjiFlightLogIfNew(source, File(destDir, source.name)) }
        }.onSuccess { copied ->
            Log.i(TAG, "syncDjiFlightLogs: $copied new file(s) copied to ${destDir.absolutePath}")
        }.onFailure { failure ->
            Log.e(TAG, "syncDjiFlightLogs error: ${failure.message}")
        }.getOrDefault(0)
    }

    private fun copyDjiFlightLogIfNew(source: File, dest: File): Boolean {
        if (dest.exists()) return false
        return runCatching {
            source.copyTo(dest)
            Log.i(TAG, "DJI log synced: ${source.name}")
            true
        }.onFailure { failure ->
            Log.w(TAG, "Failed to sync ${source.name}: ${failure.message}")
        }.getOrDefault(false)
    }

    private fun File.isDjiFlightRecord(): Boolean {
        return isFile && extension.lowercase() in djiFlightRecordExtensions
    }

    private fun logSkipped(reason: String): Int {
        Log.w(TAG, "syncDjiFlightLogs: $reason")
        return 0
    }

    private fun logFailed(reason: String): Int {
        Log.e(TAG, "syncDjiFlightLogs: $reason")
        return 0
    }
}

/** Resolves the durable, outside-the-sandbox directories Lyrebird writes into. */
internal object FlightLogStorage {
    private const val TAG = "LyrebirdFlightLogger"
    private const val DJI_SYNC_SUB_PATH = "Lyrebird/DJI_FlightRecords"

    fun resolveDjiSyncDir(): File? {
        return resolveDurableDir(DJI_SYNC_SUB_PATH, "resolveDjiSyncDir")
            ?: resolveAppExternalDir("DJI_FlightRecords", "resolveDjiSyncDir")
    }

    /**
     * Durable location for recoverable configuration, beside the flight logs and outside the app
     * sandbox so it survives an uninstall. Null when full storage access has not been granted —
     * the permission is optional, and callers fall back to keeping settings in the app only.
     */
    fun resolveConfigDir(): File? = resolveDurableDir("Lyrebird/Config", "resolveConfigDir")

    fun resolveLogDir(): File? {
        val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val subPath = "Lyrebird/FlightLogs/$dateStr"
        return resolveDurableDir(subPath, "resolveLogDir")
            ?: resolveAppExternalDir("FlightLogs/$dateStr", "resolveLogDir")
    }

    private fun resolveDurableDir(subPath: String, label: String): File? {
        if (!hasFullStorageAccess()) return null
        return removableStorageDir(subPath, label) ?: documentsDir(subPath, label)
    }

    private fun removableStorageDir(subPath: String, label: String): File? {
        val context = ContextUtil.getContext()
        return runCatching {
            context.getExternalFilesDirs(null)
                .drop(1)
                .mapNotNull { appPrivateOnCard -> appPrivateOnCard?.cardRoot() }
                .firstNotNullOfOrNull { root -> ensureDirectory(File(root, subPath)) }
        }.onSuccess { dir ->
            dir?.let { Log.i(TAG, "$label: using SD card root: ${it.absolutePath}") }
        }.onFailure { failure ->
            Log.w(TAG, "$label: SD card root check failed: ${failure.message}")
        }.getOrNull()
    }

    private fun documentsDir(subPath: String, label: String): File? {
        val documentsRoot = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        val dir = ensureDirectory(File(documentsRoot, subPath))
        dir?.let { Log.i(TAG, "$label: using Documents: ${it.absolutePath}") }
        return dir
    }

    private fun resolveAppExternalDir(subPath: String, label: String): File? {
        Log.w(TAG, "$label: falling back to app-external files dir")
        val context = ContextUtil.getContext()
        return runCatching {
            context.getExternalFilesDir(null)?.let { root -> ensureDirectory(File(root, subPath)) }
        }.onSuccess { dir ->
            dir?.let { Log.w(TAG, "$label: using app-external fallback: ${it.absolutePath}") }
        }.onFailure { failure ->
            Log.e(TAG, "$label: fallback failed: ${failure.message}")
        }.getOrNull()
    }

    private fun hasFullStorageAccess(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()
    }

    private fun File.cardRoot(): File {
        var root = this
        repeat(4) { root = root.parentFile ?: root }
        return root
    }

    private fun ensureDirectory(dir: File): File? {
        return dir.takeIf { it.mkdirs() || it.isDirectory }
    }
}
