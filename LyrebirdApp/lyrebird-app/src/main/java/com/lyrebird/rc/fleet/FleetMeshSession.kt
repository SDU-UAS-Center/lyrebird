package com.lyrebird.rc.fleet

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.lyrebird.rc.settings.LyrebirdSettings
import java.lang.ref.WeakReference

internal interface FleetRuntimeCallbacks {
    val fleetDeviceIdForRuntime: String
    val fleetDroneNameForRuntime: String

    fun buildFleetBeaconForRuntime(): FleetBeacon?
}

/** Process-scoped fleet network owner. It contains no Activity or view references. */
internal class FleetMeshSession(
    context: Context,
    private val prefs: SharedPreferences,
) {
    companion object {
        private const val TAG = "LyrebirdFleetRuntime"
    }

    private val appContext = context.applicationContext
    private var callbacksRef: WeakReference<FleetRuntimeCallbacks> = WeakReference(null)
    private val roster = FleetRoster { callbacksRef.get()?.fleetDeviceIdForRuntime.orEmpty() }
    private var link: FleetLink? = null

    fun attach(callbacks: FleetRuntimeCallbacks) {
        callbacksRef = WeakReference(callbacks)
    }

    /**
     * Drop the runtime callbacks again.
     *
     * Process teardown only: no screen may call this. The mesh belongs to the process, and while it
     * is running it is bound to the callbacks it started with (see [start]), so a detach cannot
     * silence a live link -- but every screen that attaches here attaches the same process-owned
     * object, so a screen's teardown used to clear an attachment made by the process or by another
     * screen, and that did silence the mesh for the rest of the process's life.
     */
    fun detach(callbacks: FleetRuntimeCallbacks) {
        if (callbacksRef.get() === callbacks) callbacksRef.clear()
    }

    fun start() {
        if (!prefs.getBoolean(LyrebirdSettings.PREF_FLEET_ENABLED, true) || link != null) return
        val callbacks = callbacksRef.get() ?: return
        // Bound once, here, instead of reading the registry on every tick. The registry reference
        // is droppable, and a mesh that goes quiet keeps a bound socket and a populated roster:
        // from every other RC it is indistinguishable from an aircraft that stopped, with no log
        // line either way. That is exactly how a device on the bench spent an afternoon invisible
        // to the fleet while its own strip looked correct.
        link =
            FleetLink(
                appContext,
                roster,
                ownDeviceId = { callbacks.fleetDeviceIdForRuntime },
                beaconProvider = { callbacks.buildFleetBeaconForRuntime() },
            ).apply {
                onSettingsOffered = { FleetProfileStore.store(it) }
                start()
            }
        Log.i(TAG, "Fleet mesh runtime started")
    }

    fun stop() {
        link?.stop()
        link = null
        FleetProfileStore.clearSessionRecords()
        Log.i(TAG, "Fleet mesh runtime stopped")
    }

    fun isRunning(): Boolean = link?.isRunning == true

    fun peerCount(): Int = roster.peerCount()

    fun currentView(): FleetView {
        val own = link?.lastSentBeacon ?: callbacksRef.get()?.buildFleetBeaconForRuntime() ?: return FleetView.EMPTY
        return roster.view(own, System.currentTimeMillis())
    }

    fun shareProfile(): Boolean {
        val active = link ?: return false
        val callbacks = callbacksRef.get() ?: return false
        val own = callbacks.buildFleetBeaconForRuntime() ?: return false
        val payload = FleetSettingsShare.buildOffer(prefs, callbacks.fleetDeviceIdForRuntime, own.droneName)
        return active.offerSettings(payload)
    }
}

internal object FleetMeshSessionRegistry {
    private var session: FleetMeshSession? = null

    @Synchronized
    fun attach(
        context: Context,
        prefs: SharedPreferences,
        callbacks: FleetRuntimeCallbacks,
    ): FleetMeshSession {
        val current = session ?: FleetMeshSession(context, prefs).also { session = it }
        current.attach(callbacks)
        current.start()
        return current
    }

    @Synchronized
    fun detach(callbacks: FleetRuntimeCallbacks) {
        session?.detach(callbacks)
    }

    @Synchronized
    fun stop() {
        session?.stop()
        session = null
    }
}
