package com.lyrebird.rc.server

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.lyrebird.rc.DroneControlProfiles
import com.lyrebird.rc.LrfMeasurement
import com.lyrebird.rc.LyrebirdAircraftSettingsPort
import com.lyrebird.rc.LyrebirdCommandHost
import com.lyrebird.rc.LyrebirdDetectionPort
import com.lyrebird.rc.LyrebirdFlightPort
import com.lyrebird.rc.LyrebirdMediaPort
import com.lyrebird.rc.StreamingMode
import com.lyrebird.rc.controller.ControlAuthority
import com.lyrebird.rc.controller.DroneController
import com.lyrebird.rc.controller.V5MediaPort
import com.lyrebird.rc.edge.ProcessDetectionRuntimeRegistry
import com.lyrebird.rc.edge.V5DetectionPort
import com.lyrebird.rc.logger.LyrebirdFlightLogger
import com.lyrebird.rc.mavlink.MavlinkEndpointConfig
import com.lyrebird.rc.mavlink.MavlinkSystemId
import com.lyrebird.rc.settings.DetectionSource
import com.lyrebird.rc.settings.LyrebirdSettings
import com.lyrebird.rc.settings.SettingsSnapshot
import com.lyrebird.rc.telemetry.GeoPoint3D
import com.lyrebird.rc.telemetry.ProcessTelemetryRuntimeRegistry
import com.lyrebird.rc.util.AppContextHolder
import dji.sdk.keyvalue.key.ProductKey
import dji.sdk.keyvalue.value.product.ProductType
import dji.v5.et.create
import dji.v5.et.get
import java.lang.ref.WeakReference

/**
 * What the process-scoped command surface still needs from an attached screen.
 *
 * Only the parts that genuinely belong to a screen are left: the visible control refreshes and
 * the redraw a changed setting asks for. Everything else the HTTP surface asks for is answered
 * from shared state, so it keeps working with no screen attached at all. Members here fall back
 * honestly when the screen is gone, and the detach is identity-guarded like the network bridges:
 * a stale screen cannot clear a newer one.
 */
internal interface CommandSurfaceUi {
    fun commandSurfaceUpdateManualOverrideUi()

    fun commandSurfaceSetAutoSensingSwitch(checked: Boolean)

    /**
     * Something a visible control reflects changed: re-derive the screen from preferences and
     * the process registries (drone name display, footer, detection switches, menus).
     */
    fun commandSurfaceOnStateChanged()
}

/**
 * The process-scoped implementation of the HTTP command surface.
 *
 * This is the host the network runtime serves through: name, MAVLink identity, WebRTC options,
 * MediaMTX address, streaming restart, the detection settings, the payload reads and flight
 * motion are answered from preferences, the process registries, the payload keys and the
 * aircraft itself, so a ground station can keep configuring, watching and flying an RC whose
 * screen has gone away. What genuinely needs the screen — the visible control refreshes and the
 * dialog-driven encoder restart — is delegated to [CommandSurfaceUi] when one is attached, with
 * the same honest failures the network bridges use when none is.
 *
 * The safety token check lives here on purpose: authorization is process state, so a presented
 * token must verify identically whether or not a screen exists.
 */
internal object ProcessCommandSurface : LyrebirdCommandHost {
    private const val TAG = "LyrebirdCommandSurface"

    /** Accepts the same value a client already had; the drone camera is the only source left. */
    private const val VIDEO_SOURCE_LABEL = "drone"

    /**
     * The shared safety token. Verification cannot depend on a screen being open, so it lives
     * with the command surface rather than in the activity.
     */
    private const val SAFETY_TOKEN = "98"

    @Volatile private var uiRef: WeakReference<CommandSurfaceUi> = WeakReference(null)

    private val handler by lazy { Handler(Looper.getMainLooper()) }

    override val mainHandler: Handler get() = handler

    private val preferences: SharedPreferences
        get() =
            AppContextHolder.context
                ?.getSharedPreferences(LyrebirdSettings.PREFS_FILE, Context.MODE_PRIVATE)
                ?: error("ProcessCommandSurface requires the application context")

    private val settings: LyrebirdSettings get() = LyrebirdSettings(preferences)

    @Synchronized
    fun attachUi(ui: CommandSurfaceUi) {
        uiRef = WeakReference(ui)
    }

    @Synchronized
    fun detachUi(ui: CommandSurfaceUi) {
        if (uiRef.get() === ui) uiRef = WeakReference(null)
    }

    private fun ui(): CommandSurfaceUi? = uiRef.get()

    private fun notifyStateChanged() {
        uiRef.get()?.commandSurfaceOnStateChanged()
    }

    /**
     * The aircraft's name changed underneath the screen (the serial appeared, see
     * [com.lyrebird.rc.telemetry.ProcessTelemetryRuntimeRegistry]): have any attached screen
     * re-read it, the same way a settings command does.
     */
    internal fun onDroneNameChanged() {
        notifyStateChanged()
    }

    // ── Answered from shared state ─────────────────────────────────────────────

    /**
     * The name the aircraft answers to, derived by the one rule in [LyrebirdSettings.droneName].
     *
     * It used to fall back to a constant here while the screen derived a serial-derived name of
     * its own — two answers to the same question, and the name is an address: discovery, the WHIP
     * path and the dashboard's WHEP URL all key on it.
     */
    override val droneName: String get() = settings.droneName(ProcessTelemetryRuntimeRegistry.droneSerial)

    override val media: LyrebirdMediaPort by lazy { V5MediaPort { ProcessMediaRuntimeRegistry.mediaVM() } }

    override val detection: LyrebirdDetectionPort by lazy {
        V5DetectionPort(
            activeProvider = { ProcessDetectionRuntimeRegistry.isAutoSensingActive() },
            targetsProvider = { ProcessDetectionRuntimeRegistry.currentTargets() },
        )
    }

    override fun readSettingsJson(): String = settingsSnapshot().toJson()

    override val streamingModeName: String get() = settings.getStreamingMode().wireName

    /** The same snapshot the HTTP surface serves, for the settings pages' own reads. */
    fun settingsSnapshotForUi(): SettingsSnapshot = settingsSnapshot()

    override fun setDroneName(name: String): Boolean {
        if (!settings.setDroneName(name)) return false
        val trimmed = name.trim()
        LyrebirdFlightLogger.setDroneName(trimmed)
        Log.i(TAG, "Drone name set to: $trimmed")
        notifyStateChanged()
        return true
    }

    override fun setVideoSource(value: String): Boolean = value.equals(VIDEO_SOURCE_LABEL, ignoreCase = true)

    override fun setWebRtcResolution(value: String): Boolean {
        if (!settings.setWebRtcResolution(value)) return false
        mainHandler.post { ProcessStreamingRuntimeRegistry.changeMediaOptions(settings.buildWebRTCOptions()) }
        notifyStateChanged()
        return true
    }

    override fun setWebRtcFps(value: Int): Boolean {
        if (!settings.setWebRtcFps(value)) return false
        mainHandler.post { ProcessStreamingRuntimeRegistry.changeMediaOptions(settings.buildWebRTCOptions()) }
        notifyStateChanged()
        return true
    }

    override fun setEdgeConfidence(threshold: Float): Boolean {
        if (!settings.setEdgeConfidence(threshold)) return false
        ProcessTelemetryRuntimeRegistry.telemetryCoordinator().edgeConfidenceThreshold = threshold
        return true
    }

    override fun setMediamtxServer(value: String): Boolean {
        if (!settings.setMediamtxServer(value)) return false
        val trimmed = value.trim()
        Log.i(TAG, "Mediamtx server set to: ${if (trimmed.isEmpty()) "auto (client IP)" else trimmed}")
        return true
    }

    override fun restartActiveStreaming() {
        ProcessStreamingRuntimeRegistry.restartActiveStreaming()
    }

    override fun startAutoSensing() {
        ProcessDetectionRuntimeRegistry.startSelected()
    }

    override fun stopAutoSensing() {
        ProcessDetectionRuntimeRegistry.stopSelected()
    }

    /**
     * A request is [ControlAuthority.Source.SAFETY] only when it presents exactly the shared
     * token; everything else is the Pilot Computer. This is process state, so it verifies the
     * same with or without a screen.
     */
    override fun classifyCommandSource(presentedToken: String?): ControlAuthority.Source =
        if (presentedToken == SAFETY_TOKEN) {
            ControlAuthority.Source.SAFETY
        } else {
            ControlAuthority.Source.PILOT
        }

    // ── Delegated to the screen while one is attached ──────────────────────────

    /**
     * Flight motion is the process's: a ground station must be able to fly the aircraft it can
     * already configure, whether or not a screen is showing. The refusals travel with it — see
     * [ProcessHttpFlightPort].
     */
    override val flight: LyrebirdFlightPort get() = ProcessHttpFlightPort

    /**
     * The aircraft's own settings are answered here, not by the screen: they are key writes and
     * reads that a process-owned object can serve whether or not an activity exists, which is
     * what makes an RC whose screen is closed still configurable.
     */
    override val aircraftSettings: LyrebirdAircraftSettingsPort get() = ProcessAircraftSettings

    override fun readThermalMaxTempNow(): Double? = ProcessPayloadCommands.readThermalMaxTempNow()

    override fun hasThermalCamera(): Boolean = ProcessPayloadCommands.hasThermalCamera()

    override fun readLrfMeasurement(): LrfMeasurement = ProcessPayloadCommands.readLrfMeasurement()

    override fun setLrfTarget(target: GeoPoint3D?) = ProcessPayloadCommands.setLrfTarget(target)

    override fun updateManualOverrideUI() {
        ui()?.commandSurfaceUpdateManualOverrideUi()
    }

    override fun setAutoSensingSwitchChecked(checked: Boolean) {
        ui()?.commandSurfaceSetAutoSensingSwitch(checked)
    }

    override fun setMavlinkSystemId(value: Int): Boolean {
        if (value != MavlinkSystemId.AUTO && !MavlinkSystemId.isManual(value)) return false
        val previous = currentMavlinkSystemId()
        if (!settings.setMavlinkSystemId(value)) return false
        Log.i(TAG, "MAVLink vehicle ID set to ${if (value == MavlinkSystemId.AUTO) "automatic" else value}")
        if (previous != value) {
            // The endpoint is process-owned with process-owned callbacks, so the new id takes
            // effect now: stopping and starting it re-reads the config, with or without a screen.
            // (Before this, the restart was the screen's job and the change was logged as
            // "applies when the endpoint next starts" — which, with no screen, never came.)
            ProcessAppRuntime.restartMavlinkEndpointIfServing()
            Log.i(TAG, "MAVLink endpoint restarted for the new vehicle id")
        }
        notifyStateChanged()
        return true
    }

    override fun setDetectionsEnabled(enabled: Boolean) {
        settings.setDetectionsEnabled(enabled)
        applyDetectionSelection()
        notifyStateChanged()
    }

    override fun setDetectionSource(value: String): Boolean {
        val source =
            DetectionSource.entries.firstOrNull { it.prefValue.equals(value, ignoreCase = true) }
                ?: return false
        settings.setDetectionSource(source)
        applyDetectionSelection()
        notifyStateChanged()
        return true
    }

    override fun setStreamingMode(mode: StreamingMode) {
        settings.setStreamingMode(mode)
        if (mode != StreamingMode.WEBRTC &&
            settings.isDetectionsEnabled() &&
            settings.getDetectionSource() == DetectionSource.YOLO_ON_PHONE
        ) {
            // YOLO runs on the phone's own frames, which only exist in WebRTC mode.
            Log.i(TAG, "YOLO edge detection disabled: only supported in WebRTC mode")
            setDetectionsEnabled(false)
        }
        mainHandler.post { ProcessStreamingRuntimeRegistry.changeMediaOptions(settings.buildWebRTCOptions()) }
        ProcessTelemetryRuntimeRegistry.projection().apply()
        notifyStateChanged()
    }

    /**
     * Persist the experimental encoder preference.
     *
     * The restart that makes it take effect is a screen action (Flight Deck closes and reopens), so
     * it stays with the screen: the preference is written and read here so the setting survives and
     * applies even when nobody is attached to restart anything.
     */
    override fun setDjiSurfaceH264Encoder(enabled: Boolean) {
        if (settings.isDjiSurfaceH264EncoderEnabled() == enabled) return
        settings.setDjiSurfaceH264EncoderEnabled(enabled)
        Log.i(TAG, "Surface H264 encoder preference set to $enabled")
        notifyStateChanged()
    }

    /**
     * Follow a detection setting change with the runtime that implements it.
     *
     * The screen used to do this while it was open, which is why detections stopped the moment it
     * closed. The registry reads the selection from settings itself, so both the HTTP path and the
     * settings pages end up in the same state, with or without a screen.
     */
    private fun applyDetectionSelection() {
        when (settings.activeDetectionSource()) {
            DetectionSource.NONE -> ProcessDetectionRuntimeRegistry.stopSelected()
            DetectionSource.DJI_ONBOARD, DetectionSource.YOLO_ON_PHONE -> ProcessDetectionRuntimeRegistry.startSelected()
        }
        ProcessTelemetryRuntimeRegistry.projection().apply()
    }

    // ── Settings snapshot ──────────────────────────────────────────────────────

    private fun settingsSnapshot(): SettingsSnapshot {
        // Built per call, not held in a field: object initialisation must not touch DJI classes,
        // or the surface becomes unloadable in host JVM tests that exercise its detach paths.
        val product = ProductKey.KeyProductType.create().get(ProductType.UNKNOWN)
        return SettingsSnapshot(
            droneName = droneName,
            aircraftSerialNumber = ProcessTelemetryRuntimeRegistry.droneSerial,
            mavlinkSystemId = currentMavlinkSystemId(),
            streamingMode = settings.getStreamingMode().prefValue,
            webrtcResolution = settings.getWebRTCResolutionPreset().prefValue,
            webrtcFps = settings.getWebRTCFps(),
            detectionSource = settings.getDetectionSource().prefValue,
            detectionsEnabled = isDetectionActiveForSurface(),
            edgeConfidenceThreshold = settings.getEdgeConfidenceThreshold(),
            mediamtxServer = settings.getMediamtxServer(),
            rthAltitude = DroneController.getRTHAltitude(),
            rthAltitudeEffective = DroneController.getEffectiveRTHAltitude(),
            rthAltitudeStatus = DroneController.getRTHAltitudeStatus(),
            maxFlightHeight = DroneController.getMaxFlightHeight(),
            maxFlightDistance = DroneController.getMaxFlightDistance(),
            distanceLimitEnabled = DroneController.getDistanceLimitEnabled(),
            rcControlMode = ProcessAircraftSettings.rcControlMode(),
            rcPairingStatus = ProcessAircraftSettings.rcPairingStatus(),
            hdFrequencyBand = ProcessAircraftSettings.hdFrequencyBand(),
            detectedAircraft = product.name,
            controlProfile = DroneControlProfiles.fromProductType(product).displayName,
        )
    }

    private fun isDetectionActiveForSurface(): Boolean =
        when (settings.activeDetectionSource()) {
            DetectionSource.NONE -> false
            DetectionSource.DJI_ONBOARD -> ProcessDetectionRuntimeRegistry.isAutoSensingActive()
            DetectionSource.YOLO_ON_PHONE -> ProcessDetectionRuntimeRegistry.isLocalActive()
        }

    private fun currentMavlinkSystemId(): Int =
        preferences.getInt(MavlinkEndpointConfig.PREF_SYSTEM_ID, MavlinkEndpointConfig.DEFAULT_SYSTEM_ID)
}
