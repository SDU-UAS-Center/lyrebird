package com.lyrebird.rc.server

import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import com.lyrebird.rc.settings.LyrebirdSettingsBackup
import java.lang.ref.WeakReference
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

internal interface SettingsBackupRuntimeCallbacks {
    val runtimeSettingsBackupDroneName: String
}

/**
 * Schedules the debounce, injectable so the runtime can be exercised on the host JVM where a
 * main looper does not exist.
 */
internal interface BackupScheduler {
    fun postDelayed(
        delayMs: Long,
        action: Runnable,
    )

    fun cancel(action: Runnable)
}

private class MainHandlerBackupScheduler : BackupScheduler {
    // Created on first use: constructing the process runtime must not require a prepared looper.
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    override fun postDelayed(
        delayMs: Long,
        action: Runnable,
    ) {
        handler.postDelayed(action, delayMs)
    }

    override fun cancel(action: Runnable) {
        handler.removeCallbacks(action)
    }
}

internal object ProcessSettingsBackupRuntimeRegistry {
    private val runtime = ProcessSettingsBackupRuntime()

    fun attach(
        preferences: SharedPreferences,
        callbacks: SettingsBackupRuntimeCallbacks,
    ) = runtime.attach(preferences, callbacks)

    fun detach(callbacks: SettingsBackupRuntimeCallbacks) = runtime.detach(callbacks)

    fun scheduleInitialBackup() = runtime.scheduleInitialBackup()
}

internal class ProcessSettingsBackupRuntime(
    private val scheduler: BackupScheduler = MainHandlerBackupScheduler(),
    private val writeBackup: (SharedPreferences, String) -> Unit = { prefs, droneName ->
        LyrebirdSettingsBackup.save(prefs, droneName)
    },
) {
    companion object {
        private const val DEBOUNCE_MS = 1_500L
    }

    private val executor: ExecutorService =
        Executors.newSingleThreadExecutor { task ->
            Thread(task, "lyrebird-settings-backup").apply { isDaemon = true }
        }
    private var preferences: SharedPreferences? = null
    private var registeredPreferences: SharedPreferences? = null
    private var callbacksRef: WeakReference<SettingsBackupRuntimeCallbacks> = WeakReference(null)
    private val backupTask =
        Runnable {
            val prefs = preferences ?: return@Runnable
            val droneName = callbacksRef.get()?.runtimeSettingsBackupDroneName ?: return@Runnable
            executor.execute { writeBackup(prefs, droneName) }
        }
    private val preferenceListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            // Re-arm, don't stack: one write once the changes stop landing.
            scheduler.cancel(backupTask)
            scheduler.postDelayed(DEBOUNCE_MS, backupTask)
        }

    @Synchronized
    fun attach(
        preferences: SharedPreferences,
        callbacks: SettingsBackupRuntimeCallbacks,
    ) {
        this.preferences = preferences
        callbacksRef = WeakReference(callbacks)
        if (registeredPreferences !== preferences) {
            registeredPreferences?.unregisterOnSharedPreferenceChangeListener(preferenceListener)
            preferences.registerOnSharedPreferenceChangeListener(preferenceListener)
            registeredPreferences = preferences
        }
    }

    fun detach(callbacks: SettingsBackupRuntimeCallbacks) {
        if (callbacksRef.get() === callbacks) callbacksRef.clear()
    }

    fun scheduleInitialBackup() {
        scheduler.cancel(backupTask)
        scheduler.postDelayed(DEBOUNCE_MS, backupTask)
    }
}
