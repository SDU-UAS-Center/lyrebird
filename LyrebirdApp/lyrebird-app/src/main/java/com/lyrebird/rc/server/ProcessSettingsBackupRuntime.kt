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

internal object ProcessSettingsBackupRuntimeRegistry {
    private val runtime = ProcessSettingsBackupRuntime()

    fun attach(
        preferences: SharedPreferences,
        callbacks: SettingsBackupRuntimeCallbacks,
    ) = runtime.attach(preferences, callbacks)

    fun detach(callbacks: SettingsBackupRuntimeCallbacks) = runtime.detach(callbacks)

    fun scheduleInitialBackup() = runtime.scheduleInitialBackup()
}

private class ProcessSettingsBackupRuntime {
    companion object {
        private const val DEBOUNCE_MS = 1_500L
    }

    private val mainHandler = Handler(Looper.getMainLooper())
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
            executor.execute { LyrebirdSettingsBackup.save(prefs, droneName) }
        }
    private val preferenceListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            mainHandler.removeCallbacks(backupTask)
            mainHandler.postDelayed(backupTask, DEBOUNCE_MS)
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
        mainHandler.removeCallbacks(backupTask)
        mainHandler.postDelayed(backupTask, DEBOUNCE_MS)
    }
}
