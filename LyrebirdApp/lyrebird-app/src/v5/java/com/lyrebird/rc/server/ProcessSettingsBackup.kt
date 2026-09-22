package com.lyrebird.rc.server

import android.content.Context
import android.content.SharedPreferences
import com.lyrebird.rc.settings.LyrebirdSettings
import com.lyrebird.rc.telemetry.ProcessTelemetryRuntimeRegistry

/**
 * The settings backup, owned by the process.
 *
 * The backup mirrors the settings file to Documents/Lyrebird so an operator can recover them after
 * an uninstall. It used to be attached by the Flight Deck screen, which had exactly one
 * consequence: any setting changed while no screen was attached — by HTTP, by MAVLink, or by the
 * per-drone profile a peer pushes — was written to the settings file and never to the backup. The
 * only thing the screen supplied was the name to file it under, and that is the same name rule the
 * rest of the app uses, resolved from preferences and the aircraft's serial.
 */
internal object ProcessSettingsBackup : SettingsBackupHost {
    @Volatile private var preferences: SharedPreferences? = null

    /**
     * Attach the backup and write one immediately.
     *
     * Called when the SDK registers (see [ProcessAppRuntime]): the serial is what the file name is
     * derived from, and it only exists from there. Idempotent — attaching twice must not register a
     * second preference listener.
     */
    fun attach(context: Context) {
        val prefs =
            context.applicationContext.getSharedPreferences(
                LyrebirdSettings.PREFS_FILE,
                Context.MODE_PRIVATE,
            )
        preferences = prefs
        ProcessSettingsBackupRuntimeRegistry.attach(prefs, this)
        ProcessSettingsBackupRuntimeRegistry.scheduleInitialBackup()
    }

    override val backupDroneName: String
        get() =
            preferences
                ?.let { LyrebirdSettings(it).droneName(ProcessTelemetryRuntimeRegistry.droneSerial) }
                ?: LyrebirdSettings.deriveDroneName(ProcessTelemetryRuntimeRegistry.droneSerial)
}
