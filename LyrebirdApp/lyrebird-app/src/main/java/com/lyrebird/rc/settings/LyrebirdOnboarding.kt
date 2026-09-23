package com.lyrebird.rc.settings

import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.lyrebird.rc.logger.FlightLogStorage

/**
 * First-run prompts that used to wait for the default layout — which only opens once a drone
 * is connected. The settings-restore offer is shown from the initial screen instead, so an
 * operator reinstalling Lyrebird can bring their setup back before any aircraft work starts.
 *
 * File access is deliberately not prompted here: it is one row of the permissions modal (see
 * DJIMainActivity), which explains every grant in one place and can be reopened at any time.
 *
 * The prompt shows at most once per process. The default layout calls the same entry point as
 * a fallback for paths that reach it directly; the per-process guard makes that a no-op.
 */
object LyrebirdOnboarding {
    private const val TAG = "LyrebirdSettings"
    private const val PREF_DRONE_NAME = "drone_name"

    @Volatile
    private var restoreOfferShown = false

    /**
     * True while the restore prompt is on screen. The home screen uses it to hold back the
     * automatic Flight Deck open: a dialog the operator is reading must not be jumped over.
     */
    @Volatile
    var dialogShowing: Boolean = false
        private set

    /**
     * Offer to restore settings left behind by a previous install, once per process.
     *
     * @param onRestoreApplied invoked on the main thread after the operator chooses Restore, so
     *   the initial screen can refresh values that came back from the backup.
     */
    fun offerOnFirstRun(
        activity: AppCompatActivity,
        prefs: SharedPreferences,
        onRestoreApplied: (() -> Unit)? = null,
    ) {
        if (!restoreOfferShown) {
            offerSettingsRestoreIfFresh(activity, prefs, onRestoreApplied)
        }
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
        dialogShowing = true
        AlertDialog
            .Builder(activity)
            .setTitle("Restore previous settings?")
            .setMessage(
                "Settings from a previous Lyrebird install are still on this device" +
                    (if (backup.droneName.isNotBlank()) " for \"${backup.droneName}\"" else "") +
                    ", saved ${backup.savedAt}.\n\n" +
                    "${backup.entryCount} setting(s) can be restored, including the drone name and " +
                    "the streaming and detection setup.",
            ).setPositiveButton("Restore") { _, _ ->
                // Preference writes are async (apply()) and safe from a background thread; run the
                // restore there so a large backup cannot block the UI thread.
                Thread {
                    val applied = LyrebirdSettingsBackup.restore(prefs, backup)
                    Handler(Looper.getMainLooper()).post {
                        // The layout reads these prefs when it starts (MAVLink endpoint, streaming,
                        // detection), and the drone name is already refreshed live — no restart is
                        // needed unless a service was already running with the old values.
                        Toast
                            .makeText(
                                activity,
                                "Restored $applied setting(s) — takes effect on the next drone connection",
                                Toast.LENGTH_LONG,
                            ).show()
                        onRestoreApplied?.invoke()
                    }
                }.start()
            }.setNegativeButton("Start fresh", null)
            .setOnDismissListener { dialogShowing = false }
            .show()
    }
}
