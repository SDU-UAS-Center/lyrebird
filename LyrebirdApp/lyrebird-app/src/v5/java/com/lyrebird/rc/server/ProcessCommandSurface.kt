package com.lyrebird.rc.server

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.lyrebird.rc.DroneControlProfiles
import com.lyrebird.rc.LrfMeasurement
import com.lyrebird.rc.LyrebirdCommandHost
import com.lyrebird.rc.LyrebirdDetectionPort
import com.lyrebird.rc.LyrebirdFlightPort
import com.lyrebird.rc.LyrebirdMediaPort
import com.lyrebird.rc.StickCommand
import com.lyrebird.rc.StreamingMode
import com.lyrebird.rc.controller.ControlAuthority
import com.lyrebird.rc.controller.DroneController
import com.lyrebird.rc.controller.V5MediaPort
import com.lyrebird.rc.edge.ProcessDetectionRuntimeRegistry
import com.lyrebird.rc.edge.V5DetectionPort
import com.lyrebird.rc.logger.LyrebirdFlightLogger
import com.lyrebird.rc.mavlink.CommandResult
import com.lyrebird.rc.mavlink.MavlinkCommandOutcome
import com.lyrebird.rc.mavlink.MavlinkEndpointConfig
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
 * The flight commands, the thermal reads and the visible control refreshes are the parts that
 * genuinely belong to the screen today; everything else the HTTP surface asks for is answered
 * from shared state, so it keeps working with no screen attached at all. Members here fall back
 * honestly when the screen is gone, and the detach is identity-guarded like the network bridges:
 * a stale screen cannot clear a newer one.
 */
internal interface CommandSurfaceUi {
    val commandSurfaceFlight: LyrebirdFlightPort

    fun commandSurfaceReadThermalMaxTempNow(): Double?

    fun commandSurfaceHasThermalCamera(): Boolean

    fun commandSurfaceReadLrfMeasurement(): LrfMeasurement

    fun commandSurfaceSetLrfTarget(target: GeoPoint3D?)

    fun commandSurfaceUpdateManualOverrideUi()

    fun commandSurfaceSetAutoSensingSwitch(checked: Boolean)

    fun commandSurfaceSetMavlinkSystemId(value: Int): Boolean

    fun commandSurfaceSetDetectionsEnabled(enabled: Boolean)

    fun commandSurfaceSetDetectionSource(value: String): Boolean

    fun commandSurfaceSetStreamingMode(mode: StreamingMode)

    fun commandSurfaceSetDjiSurfaceH264Encoder(enabled: Boolean)

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
 * MediaMTX address, streaming restart and the detection toggles are answered from preferences
 * and the process registries, so a ground station can keep configuring and watching an RC whose
 * screen has gone away. Parts that still need the screen (flight motion, thermal and LRF reads,
 * the dialog-driven encoder restart) are delegated to [CommandSurfaceUi] when one is attached,
 * with the same honest failures the network bridges use when none is.
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

    // ── Answered from shared state ─────────────────────────────────────────────

    override val droneName: String
        get() =
            preferences
                .getString(LyrebirdSettings.PREF_DRONE_NAME, "")
                ?.trim()
                .takeUnless { it.isNullOrEmpty() }
                ?: LyrebirdSettings.DEFAULT_DRONE_NAME

    override val media: LyrebirdMediaPort by lazy { V5MediaPort { ProcessMediaRuntimeRegistry.mediaVM() } }

    override val detection: LyrebirdDetectionPort by lazy {
        V5DetectionPort(
            activeProvider = { ProcessDetectionRuntimeRegistry.isAutoSensingActive() },
            targetsProvider = { ProcessDetectionRuntimeRegistry.currentTargets() },
        )
    }

    override fun readSettingsJson(): String = settingsSnapshot().toJson()

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

    override val flight: LyrebirdFlightPort
        get() = ui()?.commandSurfaceFlight ?: DetachedFlightPort

    override fun readThermalMaxTempNow(): Double? = ui()?.commandSurfaceReadThermalMaxTempNow()

    override fun hasThermalCamera(): Boolean = ui()?.commandSurfaceHasThermalCamera() == true

    override fun readLrfMeasurement(): LrfMeasurement = ui()?.commandSurfaceReadLrfMeasurement() ?: LrfMeasurement(null, null, null)

    override fun setLrfTarget(target: GeoPoint3D?) {
        ui()?.commandSurfaceSetLrfTarget(target)
    }

    override fun updateManualOverrideUI() {
        ui()?.commandSurfaceUpdateManualOverrideUi()
    }

    override fun setAutoSensingSwitchChecked(checked: Boolean) {
        ui()?.commandSurfaceSetAutoSensingSwitch(checked)
    }

    override fun setMavlinkSystemId(value: Int): Boolean = ui()?.commandSurfaceSetMavlinkSystemId(value) == true

    override fun setDetectionsEnabled(enabled: Boolean) {
        ui()?.commandSurfaceSetDetectionsEnabled(enabled)
    }

    override fun setDetectionSource(value: String): Boolean = ui()?.commandSurfaceSetDetectionSource(value) == true

    override fun setStreamingMode(mode: StreamingMode) {
        ui()?.commandSurfaceSetStreamingMode(mode)
    }

    override fun setDjiSurfaceH264Encoder(enabled: Boolean) {
        ui()?.commandSurfaceSetDjiSurfaceH264Encoder(enabled)
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
            rcControlMode = DroneController.getRcControlMode(),
            rcPairingStatus = DroneController.getRcPairingStatus(),
            hdFrequencyBand = DroneController.getHdFrequencyBand(),
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

/**
 * Every flight command fails honestly while no screen is attached: the motion layer is not
 * reachable from the process yet, and reporting success for a command nobody executed would be
 * the one answer a ground station must never get.
 */
private object DetachedFlightPort : LyrebirdFlightPort {
    private fun detached() = CommandResult(MavlinkCommandOutcome.FAILED, "runtime detached")

    override fun takeoff() = detached()

    override fun land() = detached()

    override fun returnToHome() = detached()

    override fun stick(command: StickCommand) = detached()

    override fun gotoYaw(yawDeg: Double) = detached()

    override fun gotoAltitude(altitudeM: Double) = detached()

    override fun abortMission() = detached()

    override fun abortAll() = detached()

    override fun enableVirtualStick() = detached()

    override fun waypoint(
        latitudeDeg: Double,
        longitudeDeg: Double,
        altitudeM: Double,
        yawDeg: Double,
        maxSpeedMps: Double,
        noseForward: Boolean,
    ) = detached()

    override fun nativeTrajectory(
        waypoints: List<Triple<Double, Double, Double>>,
        speedMps: Double,
    ) = detached()

    override fun abortNativeMission() = detached()

    override fun setRthAltitude(altitudeM: Int) = detached()

    override fun setMaxFlightHeight(heightM: Int) = detached()

    override fun setMaxFlightDistance(distanceM: Int) = detached()

    override fun setDistanceLimitEnabled(enabled: Boolean) = detached()

    override fun setRcControlMode(mode: String) = detached()

    override fun requestRcPairing() = detached()

    override fun stopRcPairing() = detached()

    override fun deactivateManualOverride() = detached()

    override fun isManualOverrideActive(): Boolean = false
}
