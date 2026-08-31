package com.lyrebird.rc.settings

import android.content.SharedPreferences
import android.util.Log
import com.lyrebird.rc.logger.FlightLogStorage
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Mirrors the app's settings to a file outside the app sandbox so they survive an uninstall.
 *
 * Reinstalling Lyrebird from a differently-signed build requires uninstalling first, which wipes
 * every preference on the device — the drone name, streaming transport, detection setup and the
 * rest. Flight logs already live in `Documents/Lyrebird` for the same reason; this puts the
 * settings beside them.
 *
 * Restoring is deliberately not automatic. A recovered backup can be from a different drone or a
 * different deployment, so the app offers it and the operator decides.
 *
 * All of this is best-effort: it needs the optional full-storage permission, and every entry point
 * degrades to a no-op when that is not granted.
 */
object LyrebirdSettingsBackup {

    private const val TAG = "LyrebirdSettings"
    private const val FILE_NAME = "lyrebird-settings.json"
    private const val KEY_SAVED_AT = "savedAt"
    private const val KEY_DRONE_NAME = "droneName"
    private const val KEY_VALUES = "values"

    /** The backup file, or null when there is no durable directory to write into. */
    fun backupFile(): File? = FlightLogStorage.resolveConfigDir()?.let { File(it, FILE_NAME) }

    /** True when a recoverable backup exists on this device. */
    fun hasBackup(): Boolean = backupFile()?.isFile == true

    /**
     * Write every preference plus the drone name to the durable file.
     *
     * Types are preserved so the restore round-trips: [SharedPreferences] distinguishes a string
     * "true" from a boolean, and putting the wrong one back would break the reader.
     */
    fun save(prefs: SharedPreferences, droneName: String) {
        val target = backupFile() ?: return
        runCatching {
            val values = JSONObject()
            prefs.all.forEach { (key, value) ->
                when (value) {
                    // Sets are not round-tripped: nothing in Lyrebird stores one, and guessing
                    // the element type on the way back would be worse than dropping it.
                    is Set<*> -> Log.d(TAG, "Skipping set-valued preference $key")
                    null -> Log.d(TAG, "Skipping null preference $key")
                    else -> values.put(key, value)
                }
            }
            val payload = JSONObject()
                .put(KEY_SAVED_AT, SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date()))
                .put(KEY_DRONE_NAME, droneName)
                .put(KEY_VALUES, values)
            target.writeText(payload.toString(2))
            Log.i(TAG, "Saved ${values.length()} settings to ${target.absolutePath}")
        }.onFailure { error ->
            Log.w(TAG, "Could not save settings backup: ${error.message}")
        }
    }

    /** Read the backup, or null when there is none or it cannot be parsed. */
    fun read(): Backup? {
        val source = backupFile()?.takeIf { it.isFile } ?: return null
        return runCatching {
            val payload = JSONObject(source.readText())
            Backup(
                savedAt = payload.optString(KEY_SAVED_AT, "unknown"),
                droneName = payload.optString(KEY_DRONE_NAME, ""),
                values = payload.optJSONObject(KEY_VALUES) ?: JSONObject()
            )
        }.onFailure { error ->
            Log.w(TAG, "Could not read settings backup: ${error.message}")
        }.getOrNull()
    }

    /**
     * Apply a backup over the current preferences.
     *
     * Returns the number of entries written. The caller is expected to have asked the operator
     * first — see the class comment.
     */
    fun restore(prefs: SharedPreferences, backup: Backup): Int {
        val editor = prefs.edit()
        var applied = 0
        backup.values.keys().forEach { key ->
            when (val value = backup.values.get(key)) {
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Double -> editor.putFloat(key, value.toFloat())
                is String -> editor.putString(key, value)
                else -> {
                    Log.d(TAG, "Skipping unsupported backup entry $key")
                    return@forEach
                }
            }
            applied++
        }
        editor.apply()
        Log.i(TAG, "Restored $applied settings saved at ${backup.savedAt}")
        return applied
    }

    /** A parsed backup file. */
    data class Backup(
        val savedAt: String,
        val droneName: String,
        val values: JSONObject
    ) {
        val entryCount: Int get() = values.length()
    }
}
