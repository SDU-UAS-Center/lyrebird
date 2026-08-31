package com.lyrebird.rc.settings

import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.lyrebird.rc.logger.FlightLogStorage

/**
 * First-run prompts that used to wait for the default layout — which only opens once a drone
 * is connected. Both are offered from the initial screen instead, so an operator reinstalling
 * Lyrebird handles storage access and a settings restore before any aircraft work starts.
 *
 * Each prompt shows at most once per process. The default layout calls the same entry point as
 * a fallback for paths that reach it directly; the per-process guards make that a no-op.
 */
object LyrebirdOnboarding {

    private const val TAG = "LyrebirdSettings"
    private const val PREF_DRONE_NAME = "drone_name"
    private const val PREF_STORAGE_PROMPT_DECLINED = "storage_prompt_declined"

    @Volatile
    private var storagePromptShown = false

    @Volatile
    private var restoreOfferShown = false

    /**
     * Show the (optional) storage-access prompt and the settings-restore offer, once each.
     *
     * @param onRestoreApplied invoked on the main thread after the operator chooses Restore, so
     *   the initial screen can refresh values that came back from the backup.
     */
    fun offerOnFirstRun(
        activity: AppCompatActivity,
        prefs: SharedPreferences,
        onRestoreApplied: (() -> Unit)? = null,
    ) {
        ensureManageExternalStoragePermission(activity, prefs)
        if (!restoreOfferShown) {
            offerSettingsRestoreIfFresh(activity, prefs, onRestoreApplied)
        }
    }

    /**
     * On Android 11+ the app needs MANAGE_EXTERNAL_STORAGE to write outside its private
     * directories (SD card root, Documents). The permission is declared in the manifest but
     * must be toggled by the user in Settings.
     */
    private fun ensureManageExternalStoragePermission(
        activity: AppCompatActivity,
        prefs: SharedPreferences,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()) return
        if (storagePromptShown) return
        if (prefs.getBoolean(PREF_STORAGE_PROMPT_DECLINED, false)) {
            Log.i(TAG, "Storage access previously declined — not asking again")
            return
        }
        storagePromptShown = true

        Log.w(TAG, "MANAGE_EXTERNAL_STORAGE not granted — explaining before requesting")
        AlertDialog.Builder(activity)
            .setTitle("Allow file access?")
            .setMessage(
                "Lyrebird can store two things outside the app so they survive an uninstall:\n\n" +
                    "  \u2022  Flight logs and DJI flight records\n" +
                    "  \u2022  Your settings \u2014 drone name, streaming and detection setup\n\n" +
                    "They go in Documents/Lyrebird, where you can copy them off the device or " +
                    "restore them after reinstalling.\n\n" +
                    "This is optional. Decline and Lyrebird works normally, but logs and settings " +
                    "stay inside the app and are lost if it is uninstalled."
            )
            .setPositiveButton("Choose folder access") { _, _ ->
                runCatching {
                    activity.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                }.onFailure { error ->
                    Log.e(TAG, "Cannot open storage settings: ${error.message}", error)
                    Toast.makeText(activity, "Could not open the storage settings screen", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Not now") { _, _ ->
                prefs.edit().putBoolean(PREF_STORAGE_PROMPT_DECLINED, true).apply()
                Log.i(TAG, "Storage access declined by user")
            }
            .setCancelable(true)
            .show()
    }

    /**
     * Offer to restore settings left behind by a previous install.
     *
     * Only asked when this install has no drone name of its own, so it fires after a reinstall
     * rather than every launch. Restoring is the operator's call: a backup can be from a different
     * drone or deployment.
     */
    private fun offerSettingsRestoreIfFresh(
        activity: AppCompatActivity,
        prefs: SharedPreferences,
        onRestoreApplied: (() -> Unit)?,
    ) {
        val hasOwnSettings = !prefs.getString(PREF_DRONE_NAME, "").isNullOrBlank()
        if (hasOwnSettings) return

        // Backup read is file I/O; run it off the main thread so the prompt cannot freeze the
        // UI thread (the restore used to ANR the default layout).
        Thread {
            // Without full storage access the durable directory cannot even be located, so a
            // "no backup" here is not trustworthy — leave restoreOfferShown false and let the
            // next onResume (typically right after the operator grants storage) retry.
            val dir = FlightLogStorage.resolveConfigDir() ?: return@Thread
            restoreOfferShown = true
            val backup = LyrebirdSettingsBackup.read() ?: return@Thread
            Handler(Looper.getMainLooper()).post {
                if (activity.isFinishing || activity.isDestroyed) return@post
                showRestoreDialog(activity, prefs, backup, onRestoreApplied)
            }
        }.start()
    }

    private fun showRestoreDialog(
        activity: AppCompatActivity,
        prefs: SharedPreferences,
        backup: LyrebirdSettingsBackup.Backup,
        onRestoreApplied: (() -> Unit)?,
    ) {
        AlertDialog.Builder(activity)
            .setTitle("Restore previous settings?")
            .setMessage(
                "Settings from a previous Lyrebird install are still on this device" +
                    (if (backup.droneName.isNotBlank()) " for \"${backup.droneName}\"" else "") +
                    ", saved ${backup.savedAt}.\n\n" +
                    "${backup.entryCount} setting(s) can be restored, including the drone name and " +
                    "the streaming and detection setup."
            )
            .setPositiveButton("Restore") { _, _ ->
                // Preference writes are async (apply()) and safe from a background thread; run the
                // restore there so a large backup cannot block the UI thread.
                Thread {
                    val applied = LyrebirdSettingsBackup.restore(prefs, backup)
                    Handler(Looper.getMainLooper()).post {
                        // The layout reads these prefs when it starts (MAVLink endpoint, streaming,
                        // detection), and the drone name is already refreshed live — no restart is
                        // needed unless a service was already running with the old values.
                        Toast.makeText(
                            activity,
                            "Restored $applied setting(s) — takes effect on the next drone connection",
                            Toast.LENGTH_LONG,
                        ).show()
                        onRestoreApplied?.invoke()
                    }
                }.start()
            }
            .setNegativeButton("Start fresh", null)
            .show()
    }
}
