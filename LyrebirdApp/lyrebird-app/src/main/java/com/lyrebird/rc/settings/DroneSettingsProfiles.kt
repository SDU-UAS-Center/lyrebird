package com.lyrebird.rc.settings

import android.content.SharedPreferences
import android.os.Handler
import android.util.Log
import com.lyrebird.rc.logger.FlightLogStorage
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * Per-aircraft settings profiles, keyed by the DJI serial number.
 *
 * One phone/RC serves several aircraft, and each aircraft remembers its own settings: when the
 * serial changes, the outgoing aircraft's per-drone preferences are snapshotted to
 * `Lyrebird/Config/drone-<serial>.json` (beside the settings backup, so both survive an
 * uninstall) and the incoming aircraft's profile — if one exists — is applied over the
 * preferences. The phone's own preferences (MAVLink flight gate, safety, UI state) stay
 * device-wide and are never swapped; only the keys listed in [perDroneKeys] travel with the
 * aircraft.
 */
object DroneSettingsProfiles {

    private const val TAG = "DroneSettingsProfiles"
    private const val FILE_PREFIX = "drone-"
    private const val FILE_SUFFIX = ".json"

    /** Device-wide preference remembering which aircraft is currently connected. */
    const val PREF_CURRENT_SERIAL = "lb_current_aircraft_serial"

    /** Serialized worker so profile writes never interleave. */
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "drone-settings-profiles").apply { isDaemon = true }
    }

    fun isUsableSerial(serial: String): Boolean =
        serial.isNotBlank() && serial.trim() != "UNKNOWN"

    fun profileFile(serial: String): File? =
        FlightLogStorage.resolveConfigDir()?.let { dir ->
            File(dir, FILE_PREFIX + sanitize(serial) + FILE_SUFFIX)
        }

    /**
     * The aircraft changed (or its serial resolved for the first time).
     *
     * Saves the outgoing aircraft's per-drone settings under its serial, remembers the new
     * serial as current, and applies the new aircraft's profile if one exists. [onProfileApplied]
     * is posted on [mainHandler] and reports whether a stored profile was actually applied.
     */
    fun onAircraftChanged(
        prefs: SharedPreferences,
        perDroneKeys: Set<String>,
        newSerial: String,
        mainHandler: Handler,
        onProfileApplied: (Boolean) -> Unit
    ) {
        val serial = newSerial.trim()
        if (!isUsableSerial(serial)) {
            Log.d(TAG, "Ignoring unusable serial '$serial'")
            return
        }
        val oldSerial = prefs.getString(PREF_CURRENT_SERIAL, "")?.trim().orEmpty()
        // Snapshot synchronously on the caller thread: the preferences still describe the
        // outgoing aircraft, and a background snapshot could capture the incoming one instead.
        val oldValues: JSONObject? = if (oldSerial != serial && isUsableSerial(oldSerial)) {
            snapshotSettings(prefs, perDroneKeys)
        } else {
            null
        }

        prefs.edit().putString(PREF_CURRENT_SERIAL, serial).apply()
        Log.i(TAG, "Aircraft changed: '$oldSerial' -> '$serial'")

        executor.execute {
            oldValues?.let { writeProfile(oldSerial, it) }
            val profile = readProfile(serial)
            val applied = profile != null
            if (applied) {
                applySettings(prefs, profile)
                Log.i(TAG, "Applied settings profile for '$serial'")
            } else {
                Log.i(TAG, "No settings profile for '$serial' — keeping current defaults")
            }
            mainHandler.post { onProfileApplied(applied) }
        }
    }

    /**
     * Persist the current per-drone settings under the currently connected aircraft. Called at
     * teardown so settings changed mid-flight survive even when no serial switch happened.
     */
    fun saveCurrentProfile(prefs: SharedPreferences, perDroneKeys: Set<String>) {
        val serial = prefs.getString(PREF_CURRENT_SERIAL, "")?.trim().orEmpty()
        if (!isUsableSerial(serial)) return
        val values = snapshotSettings(prefs, perDroneKeys)
        executor.execute { writeProfile(serial, values) }
    }

    // -- Helpers kept internal so the round-trip is unit-tested -----------------

    /** The per-drone preference values, with types preserved for the restore. */
    internal fun snapshotSettings(prefs: SharedPreferences, keys: Set<String>): JSONObject {
        val values = JSONObject()
        prefs.all.forEach { (key, value) ->
            if (key in keys && value !is Set<*>) values.put(key, value)
        }
        return values
    }

    /** Write [values] over the preferences, leaving every other preference untouched. */
    internal fun applySettings(prefs: SharedPreferences, values: JSONObject) {
        val editor = prefs.edit()
        values.keys().forEach { key ->
            when (val value = values.opt(key)) {
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Double -> editor.putFloat(key, value.toFloat())
                else -> Log.d(TAG, "Skipping unsupported profile value for '$key'")
            }
        }
        editor.apply()
    }

    internal fun encodeProfile(serial: String, values: JSONObject): JSONObject =
        JSONObject()
            .put("serial", serial)
            .put("savedAt", System.currentTimeMillis() / 1000)
            .put("values", values)

    internal fun parseProfile(text: String): JSONObject? =
        runCatching { JSONObject(text).optJSONObject("values") }
            .onFailure { Log.w(TAG, "Unreadable settings profile: ${it.message}") }
            .getOrNull()

    // -- Storage ---------------------------------------------------------------

    private fun writeProfile(serial: String, values: JSONObject) {
        val target = profileFile(serial) ?: return
        runCatching {
            target.writeText(encodeProfile(serial, values).toString(2))
            Log.i(TAG, "Saved settings profile for '$serial' → ${target.absolutePath}")
        }.onFailure { error ->
            Log.w(TAG, "Could not save settings profile for '$serial': ${error.message}")
        }
    }

    private fun readProfile(serial: String): JSONObject? {
        val source = profileFile(serial)?.takeIf { it.isFile } ?: return null
        return runCatching {
            parseProfile(source.readText()).also { values ->
                if (values != null) {
                    Log.i(TAG, "Loaded settings profile for '$serial' from ${source.absolutePath}")
                }
            }
        }.onFailure { error ->
            Log.w(TAG, "Could not read settings profile for '$serial': ${error.message}")
        }.getOrNull()
    }

    private fun sanitize(serial: String): String =
        serial.replace(Regex("[^a-zA-Z0-9_-]"), "_")
}
