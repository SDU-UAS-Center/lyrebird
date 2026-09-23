package com.lyrebird.rc.logger

import android.os.Build
import android.os.Environment
import android.util.Log
import com.lyrebird.rc.util.AppContextHolder
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Resolves the durable, outside-the-sandbox directories Lyrebird writes into.
 *
 * Split out of the V5 flight logger: the storage layout is shared (settings profiles, backups
 * and fleet profiles all resolve through it), but the logger itself drives DJI log sync and
 * stays in the flavor. The application context comes from [AppContextHolder] rather than an
 * SDK-provided context utility.
 */
internal object FlightLogStorage {
    // Kept as the logger's tag: these are its storage helpers, and existing log greps for
    // flight-log paths should keep matching.
    private const val TAG = "LyrebirdFlightLogger"
    private const val DJI_SYNC_SUB_PATH = "Lyrebird/DJI_FlightRecords"

    fun resolveDjiSyncDir(): File? =
        resolveDurableDir(DJI_SYNC_SUB_PATH, "resolveDjiSyncDir")
            ?: resolveAppExternalDir("DJI_FlightRecords", "resolveDjiSyncDir")

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

    private fun resolveDurableDir(
        subPath: String,
        label: String,
    ): File? {
        if (!hasFullStorageAccess()) return null
        return removableStorageDir(subPath, label) ?: documentsDir(subPath, label)
    }

    private fun removableStorageDir(
        subPath: String,
        label: String,
    ): File? {
        val context = AppContextHolder.context ?: return null
        return runCatching {
            context
                .getExternalFilesDirs(null)
                .drop(1)
                .mapNotNull { appPrivateOnCard -> appPrivateOnCard?.cardRoot() }
                .firstNotNullOfOrNull { root -> ensureDirectory(File(root, subPath)) }
        }.onSuccess { dir ->
            dir?.let { Log.i(TAG, "$label: using SD card root: ${it.absolutePath}") }
        }.onFailure { failure ->
            Log.w(TAG, "$label: SD card root check failed: ${failure.message}")
        }.getOrNull()
    }

    private fun documentsDir(
        subPath: String,
        label: String,
    ): File? {
        val documentsRoot = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        val dir = ensureDirectory(File(documentsRoot, subPath))
        dir?.let { Log.i(TAG, "$label: using Documents: ${it.absolutePath}") }
        return dir
    }

    private fun resolveAppExternalDir(
        subPath: String,
        label: String,
    ): File? {
        Log.w(TAG, "$label: falling back to app-external files dir")
        val context = AppContextHolder.context ?: return null
        return runCatching {
            context.getExternalFilesDir(null)?.let { root -> ensureDirectory(File(root, subPath)) }
        }.onSuccess { dir ->
            dir?.let { Log.w(TAG, "$label: using app-external fallback: ${it.absolutePath}") }
        }.onFailure { failure ->
            Log.e(TAG, "$label: fallback failed: ${failure.message}")
        }.getOrNull()
    }

    private fun hasFullStorageAccess(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    private fun File.cardRoot(): File {
        var root = this
        repeat(4) { root = root.parentFile ?: root }
        return root
    }

    private fun ensureDirectory(dir: File): File? = dir.takeIf { it.mkdirs() || it.isDirectory }
}
