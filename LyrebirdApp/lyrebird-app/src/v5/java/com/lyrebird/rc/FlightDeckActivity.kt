package com.lyrebird.rc

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.DocumentsContract
import android.util.Log
import android.util.TypedValue
import android.view.Choreographer
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import android.widget.ToggleButton
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import com.lyrebird.rc.DJIAircraftMainActivity
import com.lyrebird.rc.controller.ControlAuthority
import com.lyrebird.rc.controller.DroneController
import com.lyrebird.rc.controller.Payload
import com.lyrebird.rc.controller.ProcessAircraftSessionRegistry
import com.lyrebird.rc.controller.ProcessRoiRuntimeRegistry
import com.lyrebird.rc.controller.V5FlightSettingsActions
import com.lyrebird.rc.edge.DetectionRuntimeCallbacks
import com.lyrebird.rc.edge.DetectionTelemetryProjection
import com.lyrebird.rc.edge.DetectionWire
import com.lyrebird.rc.edge.EdgeDetectionController.EdgeDetectionMetrics
import com.lyrebird.rc.edge.ProcessDetectionRuntimeRegistry
import com.lyrebird.rc.fleet.FleetDeckController
import com.lyrebird.rc.fleet.FleetMeshSessionRegistry
import com.lyrebird.rc.fleet.FleetStripView
import com.lyrebird.rc.logger.LyrebirdFlightLogger
import com.lyrebird.rc.mavlink.DetectedTargetSnapshot
import com.lyrebird.rc.mavlink.MavlinkCommandSink
import com.lyrebird.rc.mavlink.MavlinkEndpointConfig
import com.lyrebird.rc.mavlink.MavlinkMissionSink
import com.lyrebird.rc.mavlink.MavlinkMotionSink
import com.lyrebird.rc.mavlink.MavlinkSystemId
import com.lyrebird.rc.models.MediaVM
import com.lyrebird.rc.perception.ObstacleBrake
import com.lyrebird.rc.perception.ObstacleGuard
import com.lyrebird.rc.server.CommandSurfaceUi
import com.lyrebird.rc.server.NetworkRuntimeCallbacks
import com.lyrebird.rc.server.ObstacleGuardUi
import com.lyrebird.rc.server.ObstacleRuntimeBrake
import com.lyrebird.rc.server.ProcessAppRuntime
import com.lyrebird.rc.server.ProcessCommandSurface
import com.lyrebird.rc.server.ProcessFleetRuntime
import com.lyrebird.rc.server.ProcessFlightCommands
import com.lyrebird.rc.server.ProcessMavlinkCallbacks
import com.lyrebird.rc.server.ProcessMavlinkRuntimeRegistry
import com.lyrebird.rc.server.ProcessMediaRuntimeRegistry
import com.lyrebird.rc.server.ProcessNetworkRuntimeRegistry
import com.lyrebird.rc.server.ProcessObstacleGuard
import com.lyrebird.rc.server.ProcessObstacleRuntimeRegistry
import com.lyrebird.rc.server.ProcessPayloadCommands
import com.lyrebird.rc.server.ProcessPayloadRuntimeRegistry
import com.lyrebird.rc.server.ProcessSettingsBackup
import com.lyrebird.rc.server.ProcessStreamingRuntimeRegistry
import com.lyrebird.rc.server.StreamingRuntimeUi
import com.lyrebird.rc.settings.AircraftStorage
import com.lyrebird.rc.settings.DetectionSource
import com.lyrebird.rc.settings.DroneSettingsProfiles
import com.lyrebird.rc.settings.DroneStorageStatus
import com.lyrebird.rc.settings.FlightDeckSettingsPages
import com.lyrebird.rc.settings.LyrebirdOnboarding
import com.lyrebird.rc.settings.LyrebirdSettings
import com.lyrebird.rc.settings.REQUEST_EDGE_LABELS_FILE
import com.lyrebird.rc.settings.REQUEST_EDGE_MODEL_FILE
import com.lyrebird.rc.settings.SettingsDialogViews
import com.lyrebird.rc.settings.SettingsDisplay
import com.lyrebird.rc.settings.SettingsPageActions
import com.lyrebird.rc.telemetry.AircraftFlightMode
import com.lyrebird.rc.telemetry.AircraftTelemetryListener
import com.lyrebird.rc.telemetry.ProcessTelemetryRuntimeRegistry
import com.lyrebird.rc.telemetry.V5AircraftTelemetrySource
import com.lyrebird.rc.telemetry.applyTo
import com.lyrebird.rc.util.NetworkUtils
import com.lyrebird.rc.util.ToastUtils
import com.lyrebird.rc.webrtc.TelemetryProvider
import com.lyrebird.rc.webrtc.WebRTCStreamMetrics
import com.lyrebird.rc.webrtc.WebRTCStreamer
import com.lyrebird.rc.webrtc.WhipEndpoint
import dji.sdk.keyvalue.key.CameraKey
import dji.sdk.keyvalue.key.DJIKey
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.GimbalKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.key.ProductKey
import dji.sdk.keyvalue.key.RemoteControllerKey
import dji.sdk.keyvalue.value.camera.CameraMode
import dji.sdk.keyvalue.value.camera.CameraStorageInfos
import dji.sdk.keyvalue.value.camera.CameraStorageLocation
import dji.sdk.keyvalue.value.camera.SDCardLoadState
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.sdk.keyvalue.value.common.EmptyMsg
import dji.sdk.keyvalue.value.product.ProductType
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.et.action
import dji.v5.et.create
import dji.v5.et.get
import dji.v5.et.set
import dji.v5.manager.KeyManager
import dji.v5.manager.datacenter.MediaDataCenter
import dji.v5.manager.interfaces.ICameraStreamManager
import dji.v5.ux.detection.DetectedTarget
import dji.v5.ux.detection.DetectionOverlayView
import dji.v5.ux.map.MapWidget
import dji.v5.ux.sample.showcase.defaultlayout.DefaultLayoutActivity
import java.io.File

/**
 * Lyrebird Default Layout Activity
 *
 * Extends the DJI DefaultLayoutActivity to add:
 * - HTTP Command Server (port 8080) for drone control
 * - Telemetry Server (port 8081) for real-time telemetry data
 * - WHIP publishing for WebRTC video streaming through MediaMTX
 * - mDNS/Bonjour service advertising for automatic discovery
 */
class FlightDeckActivity :
    DefaultLayoutActivity(),
    CommandSurfaceUi,
    NetworkRuntimeCallbacks,
    StreamingRuntimeUi,
    ObstacleGuardUi,
    DetectionRuntimeCallbacks {
    companion object {
        private const val TAG = "LyrebirdDefaultLayout"

        /** text_drone_status's own size, from uxsdk_activity_default_layout.xml. */
        private const val DRONE_STATUS_NORMAL_TEXT_SIZE_SP = 11f

        /** Match the normal status size so an alert does not resize the status strip. */
        private const val DRONE_STATUS_ALERT_TEXT_SIZE_SP = 11f

        /**
         * Below this, a DO_REPOSITION coordinate is read as "unset" rather than as a position.
         *
         * The command's latitude and longitude are NaN when only the altitude is meant to change,
         * but COMMAND_INT stores them as int32 scaled by 1e7, and NaN converts to zero. Zero is a
         * real coordinate, so the marker and a genuine position off the coast of Africa are
         * indistinguishable — a 1e-7 degree window (about a centimetre) is where that trade is
         * made, since no operator repositions an aircraft to within a centimetre of the equator.
         */
        private const val REPOSITION_COORD_EPSILON = 1e-7

        /**
         * Smallest orbit worth flying.
         *
         * A radius near zero is a rotation in place wearing an orbit's clothes, and the radial
         * correction would spend the whole time chasing the aircraft's own position noise across
         * a circle smaller than the error in measuring it.
         */
        private const val MIN_ORBIT_RADIUS_M = 5.0

        /** Beyond this, a reported gimbal angle is DJI's unset marker rather than a direction. */
        private const val MAX_PLAUSIBLE_GIMBAL_DEG = 200.0

        /**
         * The string-valued settings, carried by the extended parameter protocol.
         *
         * These are the ones with no honest float encoding — a name, a source, a server address.
         * Squeezing them through PARAM_SET would have meant inventing a private numbering that
         * nobody outside this file could read.
         */
        private const val PARAM_DRONE_NAME = "LB_DRONE_NAME"
        private const val PARAM_VIDEO_SOURCE = "LB_VIDEO_SRC"

        /**
         * The one video source there is.
         *
         * Named rather than inlined because three surfaces publish it: the settings JSON, the
         * edge detector's source label, and the LB_VIDEO_SRC parameter.
         */
        private const val VIDEO_SOURCE_LABEL = "drone"
        private const val PARAM_MEDIAMTX = "LB_MEDIAMTX"
        private const val PARAM_DETECTION_SOURCE = "LB_DETECT_SRC"
        private const val PARAM_RC_CONTROL_MODE = "LB_RC_MODE"
        private const val PARAM_RTC_RESOLUTION = "LB_RTC_RES"
        private const val PARAM_STREAMING_MODE = "LB_STREAM_MODE"
        private const val TAG_THERMAL = "LyrebirdThermal"
        private const val MEDIAMTX_WHIP_PORT = 8889 // mediamtx WebRTC port for WHIP publish

        private const val FLIGHT_DECK_RESTART_DELAY_MS = 750L

        private const val SAFETY_TOKEN = "98"

        private const val DJI_RTSP_STREAM_PATH = "/streaming/live/1"
    }

    val mainHandler = Handler(Looper.getMainLooper())

    private val telemetryCoordinator get() = ProcessTelemetryRuntimeRegistry.telemetryCoordinator()
    private val aircraftTelemetry get() = ProcessTelemetryRuntimeRegistry.aircraftTelemetry()

    private val mediaVM: MediaVM get() = ProcessMediaRuntimeRegistry.mediaVM()
    private val payloadWidgetVM get() = ProcessPayloadRuntimeRegistry.payloadWidgetVM()

    private val mavlinkCommandSink: MavlinkCommandSink
        get() = ProcessPayloadCommands.sink

    private val mavlinkMotionSink: MavlinkMotionSink
        get() = ProcessFlightCommands.motionSink

    private val mavlinkMissionSink: MavlinkMissionSink
        get() = ProcessFlightCommands.missionSink

    // Servers

    private var videoSettingRestartScheduled = false

    private var droneSerialNumber: String = "UNKNOWN"

    /**
     * Awareness of the other Lyrebird aircraft on this network.
     *
     * Null until [startServers] brings it up, and left null entirely when the operator has turned
     * the mesh off. Everything it does is read-only with respect to this aircraft: it publishes a
     * state beacon, draws peers, and warns. No inbound message on its socket can command anything.
     */
    private var fleetController: FleetDeckController? = null
    private var fleetMeshSession: com.lyrebird.rc.fleet.FleetMeshSession? = null

    // Drone Configuration
    private lateinit var sharedPreferences: SharedPreferences
    private val settings by lazy { LyrebirdSettings(sharedPreferences) }
    private val settingsDialogViews: SettingsDialogViews by lazy { SettingsDialogViews(this) { settingsPages.showLyrebirdSettingsMenu() } }
    private val settingsPages: FlightDeckSettingsPages by lazy {
        FlightDeckSettingsPages(
            this,
            sharedPreferences,
            settings,
            settingsDialogViews,
            object : SettingsPageActions {
                override val flight = V5FlightSettingsActions
                override val aircraftConnected get() = this@FlightDeckActivity.aircraftConnected
                override val droneName get() = this@FlightDeckActivity.droneName
                override val fleetPeerCount get() = fleetController?.peerCount()

                override fun showFleetDialog(): Boolean {
                    val controller = fleetController ?: return false
                    controller.showFleetDialog()
                    return true
                }

                override fun settingsSnapshot() = ProcessCommandSurface.settingsSnapshotForUi()

                override fun currentMavlinkSystemId() = this@FlightDeckActivity.currentMavlinkSystemId()

                override fun prefIntOrDefault(
                    key: String,
                    fallback: Int,
                ) = this@FlightDeckActivity.prefIntOrDefault(key, fallback)

                override fun setAutomaticDroneName() = this@FlightDeckActivity.setAutomaticDroneName()

                override fun setDroneName(name: String) = ProcessCommandSurface.setDroneName(name)

                override fun setMavlinkSystemId(value: Int) = this@FlightDeckActivity.setMavlinkSystemId(value)

                override fun isMavlinkFlightAllowed() = this@FlightDeckActivity.isMavlinkFlightAllowed()

                override fun mavlinkFlightAllowedMenuLabel() = this@FlightDeckActivity.mavlinkFlightAllowedMenuLabel()

                override fun setMavlinkFlightAllowed(allowed: Boolean) = this@FlightDeckActivity.setMavlinkFlightAllowed(allowed)

                override fun isDetectionActiveForUi() = this@FlightDeckActivity.isDetectionActiveForUi()

                override fun setDetectionSource(source: DetectionSource) {
                    // Enabling the onboard detector with no aircraft to run it is refused where the
                    // refusal can be explained; the state change itself belongs to the surface.
                    if (source == DetectionSource.DJI_ONBOARD && !aircraftConnected) {
                        Toast.makeText(this@FlightDeckActivity, "DJI onboard detections need a connected drone", Toast.LENGTH_SHORT).show()
                        return
                    }
                    ProcessCommandSurface.setDetectionSource(source.prefValue)
                }

                override fun setDetectionsEnabled(enabled: Boolean) = this@FlightDeckActivity.setDetectionsEnabled(enabled)

                override fun applyEdgeConfidenceSelection(threshold: Float) {
                    if (settings.isEdgeDetectionEnabled()) {
                        stopEdgeDetection()
                        startEdgeDetection()
                    } else {
                        updateEdgeMetricsView(lastEdgeMetrics.copy(confidenceThreshold = threshold))
                    }
                }

                override fun invalidateOptionsMenu() = this@FlightDeckActivity.invalidateOptionsMenu()

                override fun showEdgeFilePicker(
                    requestCode: Int,
                    title: String,
                ) = this@FlightDeckActivity.showEdgeFilePicker(requestCode, title)

                override fun getDroneStorageStatus(
                    location: AircraftStorage,
                    label: String,
                ) = this@FlightDeckActivity.getDroneStorageStatus(CameraStorageLocation.valueOf(location.name), label)

                override fun formatDroneStorage(
                    location: AircraftStorage,
                    label: String,
                ) = this@FlightDeckActivity.formatDroneStorage(CameraStorageLocation.valueOf(location.name), label)

                override fun getRtmpUrl(clientIp: String) = this@FlightDeckActivity.getRtmpUrl(clientIp)

                override fun setStreamingMode(mode: StreamingMode) = this@FlightDeckActivity.setStreamingMode(mode)

                override fun shouldRestartActiveStreaming() =
                    ProcessNetworkRuntimeRegistry.hasTelemetryClients() || ProcessStreamingRuntimeRegistry.hasTarget()

                override fun restartActiveStreaming() = ProcessCommandSurface.restartActiveStreaming()

                override fun changeVideoOptions() {
                    ProcessStreamingRuntimeRegistry.changeMediaOptions(settings.buildWebRTCOptions())
                }

                override fun toggleDjiSurfaceH264Encoder() = this@FlightDeckActivity.toggleDjiSurfaceH264Encoder()

                override fun obstacleGuardSummary() = this@FlightDeckActivity.obstacleGuardSummary()

                override fun toggleObstacleGuard() = this@FlightDeckActivity.toggleObstacleGuard()
            },
        )
    }

    var droneName: String = LyrebirdSettings.DEFAULT_DRONE_NAME

    private val deviceStatusSource get() = ProcessTelemetryRuntimeRegistry.deviceStatusSource()

    @Volatile private var lastWebRTCMetrics = WebRTCStreamMetrics()

    @Volatile private var lastNativeStreamStatus: String = "idle"

    @Volatile private var latestAltitudeMetres: Double = 0.0

    @Volatile private var latestGimbalPitchDegrees: Double = 0.0

    /**
     * This screen's own telemetry subscriptions.
     *
     * The process runtime subscribes at attach and keeps its handle; closing these on destroy
     * detaches only this screen, and a second screen attaching is never disturbed by it.
     */
    private var telemetrySubscription: AutoCloseable? = null
    private var flightStateSubscription: AutoCloseable? = null

    // Home point tracking

    // ==================== AutoSensing (AI Detection) ====================
    private val isAutoSensingActive: Boolean
        get() = ProcessDetectionRuntimeRegistry.isAutoSensingActive()

    @Volatile private var lastEdgeMetrics = EdgeDetectionMetrics()

    @Volatile var currentDetectedTargets: List<DetectedTargetSnapshot> = emptyList()
    private var detectionOverlay: DetectionOverlayView? = null
    private var pendingEdgePickerRequestCode: Int? = null
    private val edgeFilePickerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val requestCode = pendingEdgePickerRequestCode
            pendingEdgePickerRequestCode = null
            if (requestCode == null || result.resultCode != RESULT_OK) return@registerForActivityResult
            val uri = result.data?.data ?: return@registerForActivityResult
            when (requestCode) {
                REQUEST_EDGE_MODEL_FILE -> storeEdgeModelSelection(uri)
                REQUEST_EDGE_LABELS_FILE ->
                    storeEdgeFileSelection(
                        uri,
                        LyrebirdSettings.PREF_EDGE_LABELS_URI,
                        LyrebirdSettings.PREF_EDGE_LABELS_NAME,
                        "Edge labels",
                    )
            }
        }

    // ==================== End AutoSensing Fields ====================

    // Aircraft idle (low-power / eco) detection.
    // DJI exposes no arming/eco key here (KeyAreMotorsOn is unreliable — it reports true/null in
    // the low-power standby, so it never goes false when the aircraft is genuinely idle). Idle is
    // therefore inferred from the aircraft going quiet on the ground: connected + not airborne +
    // flight mode UNKNOWN + no GPS fix. Field-verified 2026-08-27 (mini1): in the true idle state
    // the telemetry shows flightMode=UNKNOWN, satelliteCount=-1, no camera frames.
    // The signature (UNKNOWN mode + no GPS) only appears in the true quiet state, so a short
    // debounce is safe — just enough to survive a transient frame loss.
    // Short enough that the idle notice does not feel laggy, long enough that a transient
    // flight-mode blip does not flash it.
    private val idleDetectDebounceMs = 1_500L

    @Volatile private var cachedFlightMode: AircraftFlightMode = AircraftFlightMode.UNKNOWN

    @Volatile private var cachedSatelliteCount = -1

    @Volatile private var idleDetectArmed = false

    @Volatile private var idleOverlayVisible = false
    private val showIdleOverlayRunnable = Runnable { onIdleDetectDebounceElapsed() }

    private val productTypeKey: DJIKey<ProductType> = ProductKey.KeyProductType.create()
    private val flightControllerConnectionKey: DJIKey<Boolean> = FlightControllerKey.KeyConnection.create()

    /**
     * The remote controller's own link to the aircraft — not the products' view of it.
     *
     * Field-verified 2026-09-21 (mini1, RC-N3, aircraft powered off): this key stays TRUE with the
     * aircraft switched off, so it cannot tell standby from gone — see [isAircraftAsleep] for the
     * keys that do. It is kept as a logged signal because it does move on wake, and because the
     * next person to reach for "the RC link" deserves to know it did not work.
     */
    private val remoteControllerLinkKey: DJIKey<Boolean> = RemoteControllerKey.KeyConnection.create()

    /**
     * Diagnostic-only: the overlay gates on [flightControllerConnectionKey] specifically, not on
     * "is anything connected". These exist so a stuck "Waiting for the aircraft…" overlay can be
     * told apart from a genuinely disconnected aircraft — e.g. video already streaming (so
     * [productConnectionKey]/[cameraConnectionKey] are true) while the flight controller
     * component's own key has not flipped yet. See [logConnectionKeySnapshot].
     */
    private val productConnectionKey: DJIKey<Boolean> = ProductKey.KeyConnection.create()
    private val cameraConnectionKey: DJIKey<Boolean> =
        KeyTools.createKey(
            CameraKey.KeyConnection,
            ComponentIndexType.LEFT_OR_MAIN,
        )
    private val cameraModeKey: DJIKey<CameraMode> =
        KeyTools.createKey(
            CameraKey.KeyCameraMode,
            ComponentIndexType.LEFT_OR_MAIN,
        )
    private val cameraStorageLocationKey: DJIKey<CameraStorageLocation> =
        KeyTools.createKey(
            CameraKey.KeyCameraStorageLocation,
            ComponentIndexType.LEFT_OR_MAIN,
        )
    private val cameraStorageInfosKey: DJIKey<CameraStorageInfos> =
        KeyTools.createKey(
            CameraKey.KeyCameraStorageInfos,
            ComponentIndexType.LEFT_OR_MAIN,
        )

    @Volatile
    private var aircraftConnected = false

    // ==================== Initial-loading overlay ====================

    /** Minimum time the loading overlay stays visible, so every launch shows it briefly. */
    private val loadingMinVisibleMs = 2_000L

    /** Safety net: hide the overlay even if the aircraft never connects (mock/phone use). */
    private val loadingTimeoutMs = 20_000L

    private var loadingShownAtMs = 0L

    private val hideLoadingOverlayRunnable =
        Runnable {
            logConnectionKeySnapshot("20s fallback timeout — hiding overlay regardless")
            mainHandler.removeCallbacks(loadingDiagnosticRunnable)
            showLoadingOverlay(false)
        }

    /**
     * Diagnostic-only: while the loading overlay is up, print what each connection key actually
     * reads every couple of seconds — see [logConnectionKeySnapshot]. Started when the overlay is
     * shown, stopped the moment it is hidden (either path). Filter logcat for "Connection
     * snapshot" to watch it live.
     */
    private val loadingDiagnosticRunnable: Runnable =
        object : Runnable {
            override fun run() {
                logConnectionKeySnapshot("loading overlay still up")
                mainHandler.postDelayed(this, 2_000L)
            }
        }

    private fun logConnectionKeySnapshot(context: String) {
        val overlayVisible = findViewById<View>(R.id.lyrebird_loading_overlay)?.visibility == View.VISIBLE
        Log.i(
            TAG,
            "Connection snapshot ($context): flightController=${flightControllerConnectionKey.get(false)} " +
                "product=${productConnectionKey.get(false)} camera=${cameraConnectionKey.get(false)} " +
                "rcLink=${isRemoteControllerLinkUp()} asleep=${isAircraftAsleep()} " +
                "productType=${productTypeKey.get(ProductType.UNKNOWN)} aircraftConnected=$aircraftConnected " +
                "overlayVisible=$overlayVisible",
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // The launch/connect phase leaves the controls unresponsive for a while: cover it with
        // a spinner + message instead of a dead-looking screen. Hidden once the aircraft
        // connects (or after loadingTimeoutMs as a fallback for mock/phone use).
        loadingShownAtMs = SystemClock.elapsedRealtime()
        showLoadingOverlay(true, "Starting Lyrebird…", "Registering with the DJI SDK…")
        mainHandler.postDelayed(hideLoadingOverlayRunnable, loadingTimeoutMs)
        mainHandler.post(loadingDiagnosticRunnable)

        // Initialize SharedPreferences
        sharedPreferences = getSharedPreferences(LyrebirdSettings.PREFS_FILE, Context.MODE_PRIVATE)
        // The process attached the telemetry runtime when the SDK registered, without waiting for
        // this screen (ProcessAppRuntime.onSdkRegistered). Repeating it here is harmless — every
        // part of it is idempotent — and covers a screen that came up before the callback ran.
        ProcessTelemetryRuntimeRegistry.attach(applicationContext)
        migrateMavlinkFlightDefault()

        // Load or prompt for drone name
        loadDroneName()

        // Setup drone name display
        setupDroneNameDisplay()

        ProcessAircraftSessionRegistry.start()

        ProcessMediaRuntimeRegistry.start()

        // PayloadWidgetVM drives the payload-release servo for the /send/drop endpoint.
        // Setup Manual Override checkbox
        setupManualOverrideCheckbox()

        // Setup AI Detection (AutoSensing) toggle & overlay
        setupAutoSensingToggle()
        setupEdgeDetectionToggle()
        updateDetectionTelemetryState()
        setupAircraftConnectionListener()
        setupAircraftIdleMonitor()
        setupMapExpandToggle()

        setupDetectedDroneProfileListener()
        updateWebRTCMetricsView(WebRTCStreamMetrics())
        updateEdgeMetricsView(lastEdgeMetrics)

        // Setup drone status indicator
        setupDroneStatusView()

        // Setup Pilot/Safety authority banner
        setupControlAuthorityBanner()

        // The process starts the phone-status listeners with the telemetry runtime; on a screen
        // this is where the location permission gets asked for, which is the one part of it that
        // needs an activity (see ProcessTelemetryRuntimeRegistry.attach).
        startLocationUpdates()

        // Get drone serial number
        fetchDroneSerialNumber()

        // Setup key listeners for telemetry
        setupKeyListeners()

        // Default field workflow: video mode, and SD card recording when available.
        scheduleDefaultCameraRecordingConfiguration()

        // First-run prompts (file access + settings restore) are offered from the initial
        // screen; this is the fallback for paths that reach the layout directly.
        LyrebirdOnboarding.offerOnFirstRun(this, sharedPreferences)

        // Keep that copy current from here on.
        startSettingsBackup()

        // Sync any DJI TXT flight records accumulated since the last launch.
        syncDjiFlightLogsInBackground()

        // Start all servers
        updateLoadingDetail("Starting HTTP, telemetry and MAVLink servers…")
        startServers()

        // Show IP address
        showServerInfo()

        updateLoadingDetail("Waiting for the aircraft to connect…")
    }

    // ==================== Mode Toggle (AUTO / MANUAL) ====================

    private fun setupManualOverrideCheckbox() {
        updateManualOverrideUI()

        findViewById<Switch>(R.id.cb_manual_override)?.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                DroneController.activateManualOverride()
            } else {
                DroneController.deactivateManualOverride()
            }
            updateManualOverrideUI()
        }

        DroneController.manualOverrideListener =
            object : DroneController.ManualOverrideListener {
                override fun onManualOverrideActivated() {
                    mainHandler.post { updateManualOverrideUI() }
                }
            }
    }

    fun updateManualOverrideUI() {
        val isManual = DroneController.isManualOverrideActive
        // Blue = autonomous, Red = manual
        val color = if (isManual) 0xFFF44336.toInt() else 0xFF2196F3.toInt()
        val tint = ColorStateList.valueOf(color)
        findViewById<Switch>(R.id.cb_manual_override)?.let { sw ->
            sw.setOnCheckedChangeListener(null)
            sw.isChecked = isManual
            sw.text = if (isManual) "MANUAL" else "AUTO"
            sw.setTextColor(color)
            sw.trackTintList = tint
            sw.thumbTintList = ColorStateList.valueOf(if (isManual) 0xFFB71C1C.toInt() else 0xFF1565C0.toInt())
            sw.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) {
                    DroneController.activateManualOverride()
                } else {
                    DroneController.deactivateManualOverride()
                }
                updateManualOverrideUI()
            }
        }
    }

    // ==================== End Mode Toggle ====================

    // ==================== Pilot / Safety Authority ====================

    /**
     * Classify an incoming HTTP request by its X-Safety-Token header.
     * A request is [ControlAuthority.Source.SAFETY] only when a safety token is configured AND
     * the request presents exactly that token; otherwise it is the Pilot Computer.
     */

    private fun setupControlAuthorityBanner() {
        // The latch outlives the process and is restored by the telemetry runtime as soon as the
        // SDK reports the serial (it owns the persistence attachment so that a command path with
        // no screen still reads the right airframe's latch). This screen only draws the banner.
        ControlAuthority.listener =
            object : ControlAuthority.Listener {
                override fun onAuthorityChanged(authority: ControlAuthority.Authority) {
                    mainHandler.post { updateControlAuthorityBanner(authority) }
                }
            }
        // Draws the banner from whatever the latch says, which after a takeover that survived a
        // restart is SAFETY — the operator sees it before the aircraft is even connected.
        updateControlAuthorityBanner(ControlAuthority.active)
    }

    private fun updateControlAuthorityBanner(authority: ControlAuthority.Authority) {
        val tv = findViewById<TextView>(R.id.text_control_authority) ?: return
        when (authority) {
            // ponytail: pilot control is the normal state, no banner needed
            ControlAuthority.Authority.PILOT -> tv.visibility = View.GONE
            ControlAuthority.Authority.SAFETY -> {
                tv.text = "SAFETY COMPUTER IN CONTROL"
                tv.setTextColor(0xFFF44336.toInt()) // red
                tv.visibility = View.VISIBLE
            }
        }
    }

    // ==================== End Pilot / Safety Authority ====================

    private fun toggleDjiSurfaceH264Encoder() {
        val enabled = !settings.isDjiSurfaceH264EncoderEnabled()
        AlertDialog
            .Builder(this)
            .setTitle("Restart Flight Deck to apply?")
            .setMessage(
                "Changing the experimental surface H264 encoder will close Flight Deck, " +
                    "return to the main screen, and reopen Flight Deck. Video and telemetry " +
                    "will briefly stop.",
            ).setPositiveButton("Restart Flight Deck") { _, _ ->
                setDjiSurfaceH264Encoder(enabled)
                Toast.makeText(this, "Restarting Flight Deck...", Toast.LENGTH_SHORT).show()
            }.setNegativeButton("Cancel", null)
            .show()
    }

    fun setDjiSurfaceH264Encoder(enabled: Boolean) {
        if (settings.isDjiSurfaceH264EncoderEnabled() == enabled) return
        // The preference itself is process state (it applies even when nobody is here to restart
        // anything); the restart below is inherently a screen action.
        ProcessCommandSurface.setDjiSurfaceH264Encoder(enabled)
        if (videoSettingRestartScheduled) return
        videoSettingRestartScheduled = true
        // Logged because this one setting restarts the screen, and it can be set remotely (a
        // ground station pushing LB_SURFACE_H264): without a line here, the app bouncing through
        // DJIAircraftMainActivity looks like a crash and the pushed setting that caused it is
        // nowhere in the log.
        Log.i(
            TAG,
            "Surface H264 encoder set to $enabled; restarting Flight Deck in " +
                "$FLIGHT_DECK_RESTART_DELAY_MS ms to apply it",
        )
        mainHandler.postDelayed({
            videoSettingRestartScheduled = false
            restartFlightDeckForVideoSetting()
        }, FLIGHT_DECK_RESTART_DELAY_MS)
    }

    private fun restartFlightDeckForVideoSetting() {
        if (isFinishing || isDestroyed) return
        val intent =
            Intent(this, DJIAircraftMainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(DJIAircraftMainActivity.EXTRA_REOPEN_FLIGHT_DECK, true)
            }
        startActivity(intent)
        finish()
    }

    fun setStreamingMode(mode: StreamingMode) {
        val yoloWouldStop =
            mode != StreamingMode.WEBRTC &&
                settings.isDetectionsEnabled() &&
                settings.getDetectionSource() == DetectionSource.YOLO_ON_PHONE
        ProcessCommandSurface.setStreamingMode(mode)
        if (yoloWouldStop) {
            Toast
                .makeText(
                    this,
                    "YOLO edge detection deactivated (only supported in WebRTC mode)",
                    Toast.LENGTH_LONG,
                ).show()
        }
    }

    private fun getRtmpUrl(clientIp: String): String {
        val stored = sharedPreferences.getString(LyrebirdSettings.PREF_RTMP_URL, "")?.trim().orEmpty()
        return stored.ifEmpty { "rtmp://$clientIp:1935/$droneName" }
    }

    private fun storeEdgeModelSelection(uri: Uri) {
        val displayName =
            storeEdgeFileSelection(uri, LyrebirdSettings.PREF_EDGE_MODEL_URI, LyrebirdSettings.PREF_EDGE_MODEL_NAME, "Edge model")
        trySelectSiblingEdgeLabels(uri, displayName)
        if (settings.activeDetectionSource() == DetectionSource.YOLO_ON_PHONE) {
            stopEdgeDetection()
            startEdgeDetection()
        }
    }

    private fun storeEdgeFileSelection(
        uri: Uri,
        uriPref: String,
        namePref: String,
        label: String,
    ): String {
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }.onFailure { Log.d(TAG, "Could not persist $label URI permission: ${it.message}") }
        val displayName = uri.lastPathSegment?.substringAfterLast('/') ?: uri.toString().substringAfterLast('/')
        sharedPreferences
            .edit()
            .putString(uriPref, uri.toString())
            .putString(namePref, displayName)
            .apply()
        if (settings.activeDetectionSource() == DetectionSource.YOLO_ON_PHONE) {
            stopEdgeDetection()
            startEdgeDetection()
        }
        Toast.makeText(this, "$label selected: $displayName", Toast.LENGTH_SHORT).show()
        invalidateOptionsMenu()
        return displayName
    }

    private fun setupMapExpandToggle() {
        val button = findViewById<ToggleButton>(R.id.button_map_expand) ?: return
        val expanded = sharedPreferences.getBoolean(LyrebirdSettings.PREF_MAP_EXPANDED, false)
        button.isChecked = expanded
        applyMapExpandedState(expanded)
        button.setOnCheckedChangeListener { _, isChecked ->
            sharedPreferences.edit().putBoolean(LyrebirdSettings.PREF_MAP_EXPANDED, isChecked).apply()
            applyMapExpandedState(isChecked)
        }
        // Tapping the small map opens it, from a transparent catcher laid over it: the map hands
        // touches to its own gestures and to its markers, so a tap that lands on the aircraft
        // marker never reaches a map-click listener (see the layout).
        findViewById<View>(R.id.map_tap_catcher)?.setOnClickListener {
            if (!button.isChecked) button.isChecked = true
        }
    }

    private fun applyMapExpandedState(expanded: Boolean) {
        val button = findViewById<ToggleButton>(R.id.button_map_expand)
        val compactWidth = resources.getDimensionPixelSize(R.dimen.uxsdk_150_dp)
        val compactHeight = resources.getDimensionPixelSize(R.dimen.uxsdk_100_dp)
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels
        val width =
            if (expanded) {
                (screenWidth - settingsDialogViews.dpToPx(24)).coerceAtLeast(compactWidth)
            } else {
                compactWidth
            }
        val height =
            if (expanded) {
                (screenHeight - settingsDialogViews.dpToPx(96)).coerceAtLeast(compactHeight)
            } else {
                compactHeight
            }
        mapWidget.layoutParams =
            mapWidget.layoutParams.apply {
                this.width = width
                this.height = height
            }
        // The camera is driven by the fleet camera policy, which frames this aircraft together
        // with the peers that have a fix (see FleetMapCamera): the SDK's own centre lock would
        // recentre at whatever zoom it happened to hold, which is how peers off the edge of the
        // small map stayed invisible. The lock is therefore left off, and the policy is what
        // follows — pausing the moment the pilot moves the map themselves.
        mapWidget.setMapCenterLock(MapWidget.MapCenterLock.NONE)
        mapWidget.setAutoFrameMapEnabled(false)
        // The expanded map is brought to the front over the whole screen, including the corner the
        // fleet strip occupies. The peers are all still on the map itself, so the strip stands down.
        fleetController?.setMapExpanded(expanded)
        mapWidget.bringToFront()
        button?.bringToFront()
        // Only the collapse control exists once the map is open; while it is small the map itself
        // is the control, so there is nothing to show and nothing to explain.
        button?.visibility = if (expanded) View.VISIBLE else View.GONE
        button?.contentDescription = if (expanded) "Minimize map" else "Expand map"
        // The catcher goes on top of the map while it is compact, and away once it is not.
        findViewById<View>(R.id.map_tap_catcher)?.apply {
            visibility = if (expanded) View.GONE else View.VISIBLE
            if (!expanded) bringToFront()
        }
        mapWidget.requestLayout()
    }

    private fun trySelectSiblingEdgeLabels(
        modelUri: Uri,
        modelName: String,
    ) {
        val labelsUri = findSiblingLabelsUri(modelUri, modelName) ?: return
        val labelsName =
            labelsUri.lastPathSegment?.substringAfterLast('/') ?: labelsUri.toString().substringAfterLast('/')
        if (readEdgeLabels(labelsUri).isEmpty()) return
        runCatching {
            contentResolver.takePersistableUriPermission(labelsUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }.onFailure { Log.d(TAG, "Could not persist auto edge labels URI permission: ${it.message}") }
        sharedPreferences
            .edit()
            .putString(LyrebirdSettings.PREF_EDGE_LABELS_URI, labelsUri.toString())
            .putString(LyrebirdSettings.PREF_EDGE_LABELS_NAME, labelsName)
            .apply()
        Toast.makeText(this, "Edge labels auto-selected: $labelsName", Toast.LENGTH_SHORT).show()
    }

    private fun findSiblingLabelsUri(
        modelUri: Uri,
        modelName: String,
    ): Uri? {
        val folderId =
            if (DocumentsContract.isDocumentUri(this, modelUri)) {
                runCatching { DocumentsContract.getDocumentId(modelUri) }
                    .getOrNull()
                    ?.substringBeforeLast('/', missingDelimiterValue = "")
                    ?.takeIf { it.isNotBlank() }
            } else {
                null
            }

        return folderId?.let { parentFolderId ->
            val candidateNames = candidateLabelNames(modelName)
            val siblingMatch =
                candidateNames
                    .asSequence()
                    .map { DocumentsContract.buildDocumentUri(modelUri.authority, "$parentFolderId/$it") }
                    .firstOrNull { readEdgeLabels(it).isNotEmpty() }

            siblingMatch ?: run {
                val candidateNameSet = candidateNames.map { it.lowercase(java.util.Locale.US) }.toSet()
                val childrenUri = DocumentsContract.buildChildDocumentsUri(modelUri.authority, parentFolderId)
                runCatching {
                    contentResolver
                        .query(
                            childrenUri,
                            arrayOf(
                                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                            ),
                            null,
                            null,
                            null,
                        )?.use { cursor ->
                            val idIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                            val nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                            while (cursor.moveToNext()) {
                                val name = cursor.getString(nameIndex) ?: continue
                                if (candidateNameSet.contains(name.lowercase(java.util.Locale.US))) {
                                    return@use DocumentsContract.buildDocumentUri(
                                        modelUri.authority,
                                        cursor.getString(idIndex),
                                    )
                                }
                            }
                            null
                        }
                }.getOrElse { error ->
                    Log.d(TAG, "Could not scan model sibling labels: ${error.message}")
                    null
                }
            }
        }
    }

    private fun candidateLabelNames(modelName: String): List<String> {
        val base = modelName.substringBeforeLast('.')
        val simplified =
            base
                .removeSuffix("_dynamic_range_quant")
                .removeSuffix("_float32")
                .removeSuffix("_float16")
                .removeSuffix("_int8")
                .replace(Regex("_320$"), "")
        return listOf(
            "$base.txt",
            "${base}_labels.txt",
            "$simplified.txt",
            "${simplified}_labels.txt",
        )
    }

    private fun getEdgeModelUri(): Uri? = settings.getEdgeModelUri()

    private fun readEdgeLabels(labelsUri: Uri): List<String> =
        runCatching {
            contentResolver
                .openInputStream(labelsUri)
                ?.bufferedReader()
                ?.useLines { lines ->
                    lines.map { it.trim() }.filter { it.isNotEmpty() }.toList()
                }.orEmpty()
        }.getOrElse { error ->
            Log.e(TAG, "Failed to read edge labels: ${error.message}", error)
            emptyList()
        }

    private fun showEdgeFilePicker(
        requestCode: Int,
        title: String,
    ) {
        val intent =
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_TITLE, title)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            }
        pendingEdgePickerRequestCode = requestCode
        edgeFilePickerLauncher.launch(intent)
    }

    // ===== M400: rebind gimbal keys to PORT_3 once the PORT_3 camera video is up =====
    // The no-index gimbal keys can resolve to the wrong gimbal on the multi-port M400. We wait for
    // the first frame on the PORT_3 camera (proof that gimbal/camera is live), then recreate the
    // gimbal keys bound explicitly to PORT_3 and enable RC-stick gimbal control. One-shot per connection.
    @Volatile
    private var gimbalKeysReboundForM400 = false

    @Volatile
    private var mainCamFrameDetectorRegistered = false

    private val cameraStreamManager: ICameraStreamManager
        get() = MediaDataCenter.getInstance().cameraStreamManager

    @Volatile private var lastDetectedFrameWidth = 0

    @Volatile private var lastDetectedFrameHeight = 0

    // One-shot frame listener: the first frame on PORT_3 triggers the rebind, then detaches.
    private val mainCamFirstFrameListener =
        object : ICameraStreamManager.CameraFrameListener {
            override fun onFrame(
                frameData: ByteArray,
                offset: Int,
                length: Int,
                width: Int,
                height: Int,
                format: ICameraStreamManager.FrameFormat,
            ) {
                lastDetectedFrameWidth = width
                lastDetectedFrameHeight = height
                mainHandler.post { onMainCameraFirstFrame() }
            }
        }

    private fun isMatrice400(): Boolean = ProductKey.KeyProductType.create().get(ProductType.UNKNOWN) == ProductType.DJI_MATRICE_400

    private fun registerMainCamFrameDetector() {
        if (mainCamFrameDetectorRegistered || gimbalKeysReboundForM400) return
        runCatching {
            // FPVWidget renders PORT_3 via a surface (hardware path) which does NOT trigger the YUV
            // frame callback. Explicitly enable the stream so addFrameListener actually gets frames.
            cameraStreamManager.enableStream(ComponentIndexType.PORT_3, true)
            cameraStreamManager.addFrameListener(
                ComponentIndexType.PORT_3,
                ICameraStreamManager.FrameFormat.NV21,
                mainCamFirstFrameListener,
            )
            mainCamFrameDetectorRegistered = true
            Log.i(TAG, "PORT_3 frame detector armed (stream enabled)")
        }.onFailure {
            Log.w(TAG, "Could not register PORT_3 frame detector: ${it.message}")
        }
    }

    private fun unregisterMainCamFrameDetector() {
        if (!mainCamFrameDetectorRegistered) return
        runCatching { cameraStreamManager.removeFrameListener(mainCamFirstFrameListener) }
        mainCamFrameDetectorRegistered = false
    }

    // First frame on the main camera arrived. Detach the detector, then rebind on M400 only.
    private fun onMainCameraFirstFrame() {
        // Dedupe: several frames may have queued before the first post ran. Only the first proceeds.
        if (!mainCamFrameDetectorRegistered) return
        unregisterMainCamFrameDetector()

        val m400 = isMatrice400()
        Log.i(TAG, "PORT_3 first frame ${lastDetectedFrameWidth}x$lastDetectedFrameHeight (M400=$m400)")

        if (gimbalKeysReboundForM400 || !m400) return
        gimbalKeysReboundForM400 = true

        // Wait 10s after the first frame before touching the gimbal — the gimbal/payload may still be
        // initialising on PORT_3 right after the stream comes up; issuing acquire/enable too early
        // is unreliable. The one-shot flag above already prevents a second scheduling.
        mainHandler.postDelayed({ initialiseM400Gimbal() }, 10000)
    }

    // M400-only: rebind the gimbal keys to PORT_3 and point the RC at the PORT_3 gimbal so the
    // physical dial/sticks drive it. Called 10s after the first PORT_3 frame.
    private fun initialiseM400Gimbal() {
        val reboundGimbalKey = GimbalKey.KeyRotateByAngle.create(ComponentIndexType.PORT_3)
        ProcessPayloadCommands.rebindGimbalKey(reboundGimbalKey)
        aircraftTelemetry.gimbalRotationKey = reboundGimbalKey
        aircraftTelemetry.gimbalAttitudeKey = GimbalKey.KeyGimbalAttitude.create(ComponentIndexType.PORT_3)
        aircraftTelemetry.gimbalJointAttitudeKey = GimbalKey.KeyGimbalJointAttitude.create(ComponentIndexType.PORT_3)
        aircraftTelemetry.gimbalModeKey = GimbalKey.KeyGimbalMode.create(ComponentIndexType.PORT_3)
        Log.i(TAG, "M400: rebound gimbal keys to PORT_3 (10s after first PORT_3 video frame)")

        // M400 is single-operator and this RC already owns gimbal authority, but the RC defaults to
        // controlling the wrong gimbal so the dial does nothing. KeyControllingGimbal selects which
        // gimbal the physical dial/sticks drive; point it at PORT_3 (the payload camera in view).
        val current = RemoteControllerKey.KeyControllingGimbal.create().get()
        Log.i(TAG, "M400: RC controllingGimbal before=$current -> setting PORT_3")
        RemoteControllerKey.KeyControllingGimbal.create().set(
            ComponentIndexType.PORT_3,
            onSuccess = { Log.i(TAG, "M400: RC now controlling PORT_3 gimbal") },
            onFailure = { error -> Log.e(TAG, "M400: set controllingGimbal failed: ${error.description()}") },
        )
    }

    // The thermal reads, their arming and the laser measurement moved to ProcessPayloadCommands:
    // a ground station asking for the hottest point or the last laser fix must not need the
    // screen that draws them (see ProcessPayloadCommands.readThermalMaxTempNow / readLrfMeasurement).

    private fun setAutomaticDroneName() {
        sharedPreferences.edit().putBoolean(LyrebirdSettings.PREF_DRONE_NAME_USER_SET, false).apply()
        applyAutomaticDroneName()
    }

    /** The pref and its endpoint restart live in the process surface; this keeps the caller's API. */
    fun setMavlinkSystemId(value: Int): Boolean = ProcessCommandSurface.setMavlinkSystemId(value)

    fun setDetectionSource(value: String): Boolean {
        if (value.equals(DetectionSource.DJI_ONBOARD.prefValue, ignoreCase = true) && !aircraftConnected) {
            Toast.makeText(this, "DJI onboard detections need a connected drone", Toast.LENGTH_SHORT).show()
            return true
        }
        return ProcessCommandSurface.setDetectionSource(value)
    }

    fun setAutoSensingSwitchChecked(checked: Boolean) {
        findViewById<Switch>(R.id.sw_auto_sensing)?.isChecked = checked
    }

    // ==================== End Thermal max-temperature readout ====================

    private fun setupAircraftConnectionListener() {
        // The product and camera keys can remain true for the RC session after the aircraft has
        // powered down. The flight-controller key is the aircraft-side connection signal and also
        // matches the DJI top-bar "Aircraft disconnected" state.
        val initialConnectionState = isAircraftPresent()
        applyAircraftConnectionState(initialConnectionState)

        fun refreshConnectionState() {
            mainHandler.post {
                val isConnected = isAircraftPresent()
                // DJI's KeyManager can re-notify the same value with nothing having changed;
                // avoid repeating connect/disconnect work for a no-op notification. The badge is
                // re-derived either way: whether the aircraft is in standby or gone is decided by
                // keys (camera, product type) that move without the flight-controller key moving.
                if (isConnected != aircraftConnected) {
                    applyAircraftConnectionState(isConnected)
                } else {
                    updateDroneStatusView(DroneController.droneStatus)
                    reevaluateAircraftIdle()
                }
            }
        }
        KeyManager.getInstance().listen(flightControllerConnectionKey, this) { _, _ ->
            refreshConnectionState()
        }
        KeyManager.getInstance().listen(productConnectionKey, this) { _, _ ->
            refreshConnectionState()
        }
        KeyManager.getInstance().listen(cameraConnectionKey, this) { _, _ ->
            refreshConnectionState()
        }
        // The product type is listened to as well as the connection keys: it is one of the two
        // signals that tell an aircraft in standby from one that has been switched off, and it
        // changes without the flight-controller key moving at all (both states have it false).
        KeyManager.getInstance().listen(productTypeKey, this) { _, _ ->
            refreshConnectionState()
        }
        KeyManager.getInstance().listen(remoteControllerLinkKey, this) { _, _ ->
            mainHandler.post {
                // Nothing here decides the badge (see isAircraftAsleep), but the link state is
                // worth a line every time it moves while the badge is in question.
                logConnectionKeySnapshot("remote controller link changed")
                updateDroneStatusView(DroneController.droneStatus)
                reevaluateAircraftIdle()
            }
        }
    }

    private fun isAircraftPresent(): Boolean = flightControllerConnectionKey.get(false)

    private fun applyAircraftConnectionState(isConnected: Boolean) {
        val wasConnected = aircraftConnected
        aircraftConnected = isConnected
        aircraftTelemetry.setConnectionState(isConnected)
        logConnectionKeySnapshot("flightController listener fired: $wasConnected -> $isConnected")
        if (isConnected && !wasConnected) {
            // The startup serial fetch fails when the app boots before the aircraft links (the
            // normal RC case); re-fetch on connect so this aircraft's settings profile — name,
            // manual sysid, streaming — is restored for exactly the drone that connected.
            fetchDroneSerialNumber()
        }
        if (!isConnected && settings.isDetectionsEnabled() && settings.getDetectionSource() == DetectionSource.DJI_ONBOARD) {
            setDetectionsEnabled(false)
        }
        // Warm the media list on connect so the first photo capture isn't cold (the first
        // whole-card fetch is slow and otherwise blows past the capture client's timeout).
        if (isConnected) {
            // Initial-loading overlay: the aircraft is here, so take it down — but keep it up for
            // at least loadingMinVisibleMs so the launch flash isn't a one-frame flicker.
            mainHandler.removeCallbacks(hideLoadingOverlayRunnable)
            mainHandler.removeCallbacks(loadingDiagnosticRunnable)
            val remainingMs =
                loadingMinVisibleMs - (SystemClock.elapsedRealtime() - loadingShownAtMs)
            if (remainingMs > 0) {
                mainHandler.postDelayed({ showLoadingOverlay(false) }, remainingMs)
            } else {
                showLoadingOverlay(false)
            }
            Payload.warmUpMedia(mediaVM)
            // NOTE: the PORT_3 frame detector is armed from applyDetectedDroneProfile (once the
            // product resolves to M400 and PORT_3 is actually streaming), NOT here — at the connect
            // edge the product is still UNRECOGNIZED and PORT_3 has no stream yet.
            // The thermal radiometric pipeline is armed by the process on the same connection
            // event (see ProcessTelemetryRuntimeRegistry's listener), so it is warm for a read
            // that arrives over HTTP or MAVLink with no screen attached.
        } else {
            Payload.resetMediaWarmup()
            // Reset for the next connection so a reconnect (or a different drone) rebinds again.
            unregisterMainCamFrameDetector()
            gimbalKeysReboundForM400 = false
        }
        invalidateOptionsMenu()
        updateDroneStatusView(DroneController.droneStatus)
        reevaluateAircraftIdle()
    }

    private fun setupDetectedDroneProfileListener() {
        applyDetectedDroneProfile(productTypeKey.get(ProductType.UNKNOWN) ?: ProductType.UNKNOWN)
        KeyManager.getInstance().listen(productTypeKey, this) { _, newValue ->
            mainHandler.post {
                applyDetectedDroneProfile(newValue ?: ProductType.UNKNOWN)
            }
        }
    }

    private fun applyDetectedDroneProfile(productType: ProductType) {
        val controlProfile = DroneControlProfiles.fromProductType(productType)
        val controlLabel =
            when (controlProfile) {
                DroneControlProfile.MATRICE_300_RTK -> "CTRL M300"
                DroneControlProfile.MATRICE_350_RTK -> "CTRL M350"
                DroneControlProfile.MATRICE_400 -> "CTRL M400"
                DroneControlProfile.MINI_4_PRO -> "CTRL MINI4"
                DroneControlProfile.MAVIC_3_ENTERPRISE -> "CTRL MAVIC3"
            }
        findViewById<TextView>(R.id.text_control_profile)?.text = controlLabel
        Log.i(TAG, "Detected product $productType -> using ${controlProfile.displayName} profile")

        // M400 resolved (and PORT_3 should be streaming by now): arm the PORT_3 frame detector that
        // rebinds the gimbal keys + enables RC-stick gimbal control. Guarded one-shot per connection.
        if (productType == ProductType.DJI_MATRICE_400) {
            registerMainCamFrameDetector()
        }
    }

    private fun updateWebRTCMetricsView(metrics: WebRTCStreamMetrics) {
        lastWebRTCMetrics = metrics
        detectionOverlay?.setVideoScaleMode(DetectionOverlayView.VideoScaleMode.CENTER_INSIDE)
        if (metrics.sourceWidth > 0 && metrics.sourceHeight > 0) {
            detectionOverlay?.setSourceFrameSize(metrics.sourceWidth, metrics.sourceHeight)
        }
        updateStreamingFooter()
    }

    private fun updateStreamingFooter() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { updateStreamingFooter() }
            return
        }
        val footer = findViewById<TextView>(R.id.text_webrtc_metrics) ?: return
        val mode = settings.getStreamingMode()
        val message =
            when (mode) {
                StreamingMode.WEBRTC -> lastWebRTCMetrics.compactLabel()
                StreamingMode.RTMP -> {
                    val serverIp = ProcessStreamingRuntimeRegistry.currentClientIp() ?: NetworkUtils.getDeviceIpAddress() ?: "127.0.0.1"
                    val rtmpUrl = getRtmpUrl(serverIp)
                    "RTMP ${if (ProcessStreamingRuntimeRegistry.isNativeStreaming()) "running" else "idle"} url $rtmpUrl $lastNativeStreamStatus"
                }
                StreamingMode.RTSP -> {
                    val port = settings.getRtspPort()
                    val user = settings.getRtspUsername()
                    val userPrefix = if (user.isNotEmpty()) "$user@" else ""
                    "RTSP ${if (ProcessStreamingRuntimeRegistry.isNativeStreaming()) "running" else "idle"} " +
                        "${userPrefix}port $port path $DJI_RTSP_STREAM_PATH $lastNativeStreamStatus"
                }
                StreamingMode.AGORA -> {
                    val channel = settings.getAgoraChannel().ifBlank { "-" }
                    "AGORA ${if (ProcessStreamingRuntimeRegistry.isNativeStreaming()) "running" else "idle"} ch $channel $lastNativeStreamStatus"
                }
                StreamingMode.GB28181 -> {
                    val server = "${settings.getGbServerIp()}:${settings.getGbServerPort()}"
                    "GB28181 ${if (ProcessStreamingRuntimeRegistry.isNativeStreaming()) "running" else "idle"} server $server $lastNativeStreamStatus"
                }
            }
        footer.text = message
    }

    @Suppress("ktlint:standard:max-line-length")
    private fun WebRTCStreamMetrics.toTelemetryJson(): String {
        fun escapeJson(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")
        val lastErrorJson = lastError?.let { "\"${escapeJson(it)}\"" } ?: "null"
        // qualityLimitationReason/framesEncodedNotSent/sendBitrateBps: send-side network stats
        // from WhipPublisher.getStats() (frame-drop investigation, Phase 1) -- absent outside an
        // active WHIP publish or before the first stats poll, hence the null-safe encoding.
        val qualityLimitationReasonJson = qualityLimitationReason?.let { "\"${escapeJson(it)}\"" } ?: "null"
        val framesEncodedNotSentJson = framesEncodedNotSent?.toString() ?: "null"
        val sendBitrateBpsJson = sendBitrateBps?.toString() ?: "null"
        val framesEncodedJson = framesEncoded?.toString() ?: "null"
        val framesSentJson = framesSent?.toString() ?: "null"
        return """{"sourceWidth":$sourceWidth,"sourceHeight":$sourceHeight,"outputWidth":$outputWidth,"outputHeight":$outputHeight,"requestedWidth":$requestedWidth,"requestedHeight":$requestedHeight,"targetFps":$targetFps,"inputFps":$inputFps,"outputFps":$outputFps,"droppedFps":$droppedFps,"averageFrameProcessingMs":$averageFrameProcessingMs,"totalFrames":$totalFrames,"totalDroppedFrames":$totalDroppedFrames,"processingErrors":$processingErrors,"observerCount":$observerCount,"activeCamera":"${escapeJson(
            activeCamera,
        )}","status":"${escapeJson(
            status,
        )}","configuredFps":$configuredFps,"saturationState":"${escapeJson(
            saturationState,
        )}","scaleMode":"${escapeJson(
            scaleMode,
        )}","recoveryCount":$recoveryCount,"lastError":$lastErrorJson,"qualityLimitationReason":$qualityLimitationReasonJson,"framesEncodedNotSent":$framesEncodedNotSentJson,"sendBitrateBps":$sendBitrateBpsJson,"framesEncoded":$framesEncodedJson,"framesSent":$framesSentJson}"""
    }

    private fun showStreamToast(msg: String) {
        mainHandler.post {
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Begin publishing video for a ground station at [clientIp], once.
     *
     * Shared by the TCP telemetry server and the MAVLink endpoint so the two announce a ground
     * station the same way. Repeat calls for a client already streaming are ignored: peer
     * discovery can fire again after a ground station restarts, and tearing the encoder down to
     * rebuild the identical publish would drop the picture for everyone watching it.
     */
    private fun startStreamingForClient(clientIp: String) {
        ProcessStreamingRuntimeRegistry.startForClient(clientIp)
    }

    fun restartActiveStreaming() {
        ProcessStreamingRuntimeRegistry.restartActiveStreaming()
    }

    // ==================== End Video Mode Toggle ====================

    // ==================== AutoSensing (AI Detection) Toggle ====================

    private fun setupAutoSensingToggle() {
        detectionOverlay = findViewById(R.id.detection_overlay)

        val sw = findViewById<Switch>(R.id.sw_auto_sensing) ?: return
        sw.setOnCheckedChangeListener(null)
        sw.isChecked = settings.isDetectionsEnabled() && settings.getDetectionSource() == DetectionSource.DJI_ONBOARD
        sw.visibility = android.view.View.GONE
    }

    private fun isDetectionActiveForUi(): Boolean =
        when (settings.activeDetectionSource()) {
            DetectionSource.NONE -> false
            DetectionSource.DJI_ONBOARD -> isAutoSensingActive
            DetectionSource.YOLO_ON_PHONE -> ProcessDetectionRuntimeRegistry.isLocalActive()
        }

    private fun detectionMenuLabel(): String =
        if (isDetectionActiveForUi()) {
            "Detections On (${settings.getDetectionSource().menuLabel})"
        } else {
            "Detections Off"
        }

    fun setDetectionsEnabled(enabled: Boolean) {
        if (enabled && settings.getDetectionSource() == DetectionSource.DJI_ONBOARD && !aircraftConnected) {
            Toast.makeText(this, "DJI onboard detections need a connected drone", Toast.LENGTH_SHORT).show()
            return
        }
        // Persisting, starting or stopping the pipeline and re-deriving this screen all belong to
        // the process surface: the same command arrives over HTTP, where no screen may exist.
        ProcessCommandSurface.setDetectionsEnabled(enabled)
    }

    private fun updateDetectionTelemetryState() {
        val selectedSource = settings.getDetectionSource()
        val projected =
            DetectionTelemetryProjection.project(
                selectedSource = selectedSource.prefValue,
                enabled = settings.isDetectionsEnabled(),
                onboardActive = isAutoSensingActive,
                localActive = ProcessDetectionRuntimeRegistry.isLocalActive(),
                modelName = sharedPreferences.getString(LyrebirdSettings.PREF_EDGE_MODEL_NAME, null),
                threshold = settings.getEdgeConfidenceThreshold(),
            )

        TelemetryProvider.currentDetectionSource = projected.source
        TelemetryProvider.currentDetectionActive = projected.active
        TelemetryProvider.currentDetectionModel = projected.modelName
        TelemetryProvider.currentDetectionThreshold = projected.threshold

        telemetryCoordinator.isDetectionsEnabled = settings.isDetectionsEnabled()
        telemetryCoordinator.detectionSource = projected.source
        telemetryCoordinator.selectedDetectionSource = selectedSource.prefValue
        telemetryCoordinator.detectionMenuLabel = selectedSource.menuLabel
        telemetryCoordinator.isAutoSensingActive = isAutoSensingActive
        telemetryCoordinator.edgeDetectionActive = ProcessDetectionRuntimeRegistry.isLocalActive()
        telemetryCoordinator.edgeModelName = sharedPreferences.getString(LyrebirdSettings.PREF_EDGE_MODEL_NAME, null)
        telemetryCoordinator.edgeLabelsName = sharedPreferences.getString(LyrebirdSettings.PREF_EDGE_LABELS_NAME, null)
        telemetryCoordinator.edgeConfidenceThreshold = settings.getEdgeConfidenceThreshold()
        telemetryCoordinator.detectedTargetsJson = DetectionWire.targetsJson(currentDetectedTargets)
        telemetryCoordinator.detectedTargetsSize = currentDetectedTargets.size

        // Both switches are drawn from preferences rather than from whichever control was tapped,
        // so a change made over HTTP — or one the runtime made on its own — lands here like any
        // other. Without this the switches kept showing the last on-screen selection.
        findViewById<Switch>(R.id.sw_auto_sensing)?.isChecked =
            settings.isDetectionsEnabled() &&
            selectedSource == DetectionSource.DJI_ONBOARD
        findViewById<Switch>(R.id.sw_edge_detection)?.isChecked =
            settings.isDetectionsEnabled() &&
            selectedSource == DetectionSource.YOLO_ON_PHONE
    }

    private fun applyDetectedTargets(targets: List<DetectedTargetSnapshot>) {
        currentDetectedTargets = targets
        TelemetryProvider.currentDetectedTargets = targets
        updateDetectionTelemetryState()
        rebuildTelemetryCache()
        mainHandler.post { detectionOverlay?.setTargets(targets.mapIndexed(::toOverlayTarget)) }
    }

    /** The overlay draws the UXSDK view type; the pipeline above it carries the neutral snapshot. */
    private fun toOverlayTarget(
        index: Int,
        target: DetectedTargetSnapshot,
    ) = DetectedTarget(
        index = index,
        type = target.type,
        left = target.left,
        top = target.top,
        right = target.right,
        bottom = target.bottom,
        confidence = target.confidence,
    )

    fun startAutoSensing() {
        ProcessDetectionRuntimeRegistry.startSelected()
    }

    @Suppress("TooGenericExceptionCaught")
    fun stopAutoSensing() {
        ProcessDetectionRuntimeRegistry.stopSelected()
    }

    private fun clearAutoSensingState() {
        currentDetectedTargets = emptyList()
        TelemetryProvider.currentDetectedTargets = emptyList()
        updateDetectionTelemetryState()
        rebuildTelemetryCache()
        mainHandler.post { detectionOverlay?.clearTargets() }
    }

    // ==================== End AutoSensing Toggle ====================

    // ==================== Edge Detection Toggle ====================

    private fun setupEdgeDetectionToggle() {
        val sw = findViewById<Switch>(R.id.sw_edge_detection) ?: return
        sw.setOnCheckedChangeListener(null)
        sw.isChecked = settings.isDetectionsEnabled() && settings.getDetectionSource() == DetectionSource.YOLO_ON_PHONE
        sw.visibility = android.view.View.GONE
        updateEdgeDetectionToggleUi(settings.isDetectionsEnabled() && settings.getDetectionSource() == DetectionSource.YOLO_ON_PHONE)
    }

    private sealed interface EdgeDetectionStartCheck {
        data class Ready(
            val modelUri: Uri,
        ) : EdgeDetectionStartCheck

        data class UnsupportedStreamingMode(
            val streamingMode: StreamingMode,
        ) : EdgeDetectionStartCheck

        object MissingModel : EdgeDetectionStartCheck

        object WaitingForDjiVideo : EdgeDetectionStartCheck
    }

    private fun startEdgeDetection() {
        val streamer = ProcessStreamingRuntimeRegistry.streamer()
        val startCheck = edgeDetectionStartCheck(getEdgeModelUri(), streamer)
        if (startCheck !is EdgeDetectionStartCheck.Ready) {
            handleEdgeDetectionStartFailure(startCheck)
            return
        }

        clearAutoSensingState()

        configureDetectionOverlay()
        ProcessDetectionRuntimeRegistry.startSelected()
        updateDetectionTelemetryState()
        rebuildTelemetryCache()

        showEdgeDetectionEnabledMessage()
    }

    private fun edgeDetectionStartCheck(
        modelUri: Uri?,
        streamer: WebRTCStreamer?,
    ): EdgeDetectionStartCheck =
        when {
            settings.getStreamingMode() != StreamingMode.WEBRTC -> {
                EdgeDetectionStartCheck.UnsupportedStreamingMode(settings.getStreamingMode())
            }
            modelUri == null -> {
                EdgeDetectionStartCheck.MissingModel
            }
            streamer == null -> {
                EdgeDetectionStartCheck.WaitingForDjiVideo
            }
            else -> {
                EdgeDetectionStartCheck.Ready(modelUri = modelUri)
            }
        }

    private fun handleEdgeDetectionStartFailure(startCheck: EdgeDetectionStartCheck) {
        when (startCheck) {
            is EdgeDetectionStartCheck.UnsupportedStreamingMode -> {
                setDetectionsEnabled(false)
                Toast
                    .makeText(
                        this,
                        "Edge detection with custom YOLO is not supported in ${startCheck.streamingMode.menuLabel} mode",
                        Toast.LENGTH_LONG,
                    ).show()
            }
            is EdgeDetectionStartCheck.MissingModel -> {
                setDetectionsEnabled(false)
                updateEdgeMetricsView(
                    EdgeDetectionMetrics(status = "no-model", source = VIDEO_SOURCE_LABEL),
                )
                showEdgeFilePicker(REQUEST_EDGE_MODEL_FILE, "Select YOLO TFLite model")
                Toast.makeText(this, "Select a YOLO .tflite model first", Toast.LENGTH_SHORT).show()
            }
            EdgeDetectionStartCheck.WaitingForDjiVideo -> {
                Toast.makeText(this, "Edge detector will be ready after video starts", Toast.LENGTH_SHORT).show()
            }
            is EdgeDetectionStartCheck.Ready -> Unit
        }
    }

    private fun configureDetectionOverlay() {
        detectionOverlay?.setVideoScaleMode(DetectionOverlayView.VideoScaleMode.CENTER_INSIDE)
        detectionOverlay?.setSourceFrameSize(
            lastWebRTCMetrics.sourceWidth.takeIf { it > 0 } ?: 16,
            lastWebRTCMetrics.sourceHeight.takeIf { it > 0 } ?: 9,
        )
    }

    private fun showEdgeDetectionEnabledMessage() {
        Toast.makeText(this, "Edge detection enabled", Toast.LENGTH_SHORT).show()
        Log.i(TAG, "Edge detection enabled")
    }

    private fun stopEdgeDetection() {
        if (!ProcessDetectionRuntimeRegistry.isLocalActive()) return
        ProcessDetectionRuntimeRegistry.stopSelected()
        clearAutoSensingState()
        updateDetectionTelemetryState()
        rebuildTelemetryCache()
        updateEdgeMetricsView(EdgeDetectionMetrics(status = "off"))
        Toast.makeText(this, "Edge detection disabled", Toast.LENGTH_SHORT).show()
        Log.i(TAG, "Edge detection disabled")
    }

    private fun updateEdgeDetectionToggleUi(isEnabled: Boolean) {
        findViewById<Switch>(R.id.sw_edge_detection)?.let { switch ->
            switch.text = if (isEnabled) "EDGE DETECT" else "EDGE OFF"
            switch.setTextColor(if (isEnabled) 0xFFFFD166.toInt() else 0xFFDDDDDD.toInt())
        }
    }

    private fun updateEdgeMetricsView(metrics: EdgeDetectionMetrics) {
        lastEdgeMetrics = metrics
        findViewById<TextView>(R.id.text_edge_metrics)?.apply {
            // "off" is EdgeDetectionMetrics' own default/inactive state (see its data class
            // default), not just one status among several worth displaying -- a static zeroed
            // line for a feature that isn't running is noise, not information, so hide the row
            // entirely instead.
            if (metrics.status == "off") {
                visibility = View.GONE
            } else {
                visibility = View.VISIBLE
                text = metrics.compactLabel()
            }
        }
    }

    // ==================== End Edge Detection Toggle ====================

    // ==================== Drone Status View ====================

    private fun setupDroneStatusView() {
        DroneController.droneStatusListener =
            object : DroneController.DroneStatusListener {
                override fun onDroneStatusChanged(status: DroneController.DroneStatus) {
                    LyrebirdFlightLogger.logStatus(status.name)
                    mainHandler.post { updateDroneStatusView(status) }
                }
            }
        updateDroneStatusView(DroneController.droneStatus)
    }

    private fun updateDroneStatusView(appStatus: DroneController.DroneStatus) {
        val statusTv = findViewById<TextView>(R.id.text_drone_status) ?: return
        // DroneController.droneStatus has no "disconnected" case of its own — it stays at its
        // IDLE default whether or not an aircraft was ever connected — so that has to be
        // checked here rather than folded into the enum's own IDLE label.
        if (!aircraftConnected) {
            if (isAircraftAsleep()) {
                // Asleep in DJI's low-power (eco) standby: the flight controller stopped
                // answering while the aircraft is still recognised (see isAircraftAsleep), so the
                // aircraft is known and the way back is a stick gesture, not a connection to
                // chase. Amber, not the red alarm OFFLINE uses. A switched-off aircraft fails
                // this check — nothing of it is recognised any more — and reads OFFLINE.
                statusTv.text = "ECO"
                statusTv.setTextColor(0xFFFFC107.toInt())
                statusTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, DRONE_STATUS_ALERT_TEXT_SIZE_SP)
                return
            }
            // Nothing on the product link either. The ready-to-take-off check keeps an aircraft
            // that is still coming up from flashing OFFLINE while its device status settles.
            if (!aircraftTelemetry.isReadyToTakeoff()) {
                statusTv.text = "OFFLINE"
                statusTv.setTextColor(0xFFFF1744.toInt())
                statusTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, DRONE_STATUS_ALERT_TEXT_SIZE_SP)
                return
            }
        }
        // Upgrade IDLE → HOVERING when the FC says the drone is airborne
        val resolved =
            if (appStatus == DroneController.DroneStatus.IDLE && aircraftTelemetry.readState().readings.flying) {
                DroneController.DroneStatus.HOVERING
            } else {
                appStatus
            }
        // The obstacle guard outranks the operational status while it is latched. It is the one
        // state where the aircraft stopped itself, so it is what the pilot needs to read first —
        // shown here in the indicator they already watch rather than as anything that pops up.
        if (ProcessObstacleRuntimeRegistry.isLatched()) {
            statusTv.text = "OBSTACLE"
            statusTv.setTextColor(0xFFFF1744.toInt())
            statusTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, DRONE_STATUS_ALERT_TEXT_SIZE_SP)
            return
        }
        val (label, color) =
            when (resolved) {
                DroneController.DroneStatus.IDLE -> Pair("IDLE", 0xFFFF9800.toInt())
                DroneController.DroneStatus.TAKING_OFF -> Pair("TAKEOFF", 0xFFFFC107.toInt())
                DroneController.DroneStatus.HOVERING -> Pair("HOVER", 0xFF4CAF50.toInt())
                DroneController.DroneStatus.NAVIGATING -> Pair("NAV", 0xFF2196F3.toInt())
                DroneController.DroneStatus.LANDING -> Pair("LAND", 0xFFFF9800.toInt())
                DroneController.DroneStatus.RETURNING_HOME -> Pair("RTH", 0xFFFF9800.toInt())
                DroneController.DroneStatus.MANUAL_OVERRIDE -> Pair("MANUAL", 0xFFF44336.toInt())
                DroneController.DroneStatus.ABORTING -> Pair("ABORT", 0xFFF44336.toInt())
                DroneController.DroneStatus.MISSION -> Pair("MISSION", 0xFF00BCD4.toInt())
            }
        statusTv.setTextSize(
            TypedValue.COMPLEX_UNIT_SP,
            if (resolved == DroneController.DroneStatus.IDLE) {
                DRONE_STATUS_ALERT_TEXT_SIZE_SP
            } else {
                DRONE_STATUS_NORMAL_TEXT_SIZE_SP
            },
        )
        statusTv.text = label
        statusTv.setTextColor(color)
    }

    // ==================== End Drone Status View ====================

    /**
     * Mirror settings to Documents/Lyrebird whenever they change, so they can be recovered after
     * an uninstall.
     *
     * The process owns this (see ProcessSettingsBackup): it attaches when the SDK registers, and
     * the only thing it needed from a screen was the name to file the backup under.
     */
    private fun startSettingsBackup() {
        ProcessSettingsBackup.attach(applicationContext)
    }

    /**
     * Copy DJI SDK-managed TXT flight records into the Lyrebird DJI_FlightRecords folder.
     * Runs on a background thread. Already-copied files are skipped (by filename).
     */
    private fun syncDjiFlightLogsInBackground() {
        Thread {
            runCatching {
                val djiPath = File(getExternalFilesDir(null), "DJI/FlightRecord").absolutePath
                val count = LyrebirdFlightLogger.syncDjiFlightLogs(djiPath)
                if (count > 0) {
                    mainHandler.post {
                        Toast
                            .makeText(
                                this,
                                "Synced $count DJI flight log(s) to Lyrebird folder",
                                Toast.LENGTH_SHORT,
                            ).show()
                    }
                }
            }.onFailure { error ->
                Log.w(TAG, "syncDjiFlightLogsInBackground: ${error.message}", error)
            }
        }.start()
    }

    private fun updateAltitudeView() {
        findViewById<TextView>(R.id.text_altitude)?.text =
            "ALT ${latestAltitudeMetres.toInt()}m  GIM ${latestGimbalPitchDegrees.toInt()}°"
    }

    /**
     * Compact link status beside the CTRL chip: "MAVLINK" and "HTTP" are colored independently
     * (MAVLink blue when up, HTTP green when up, red for whichever is down) since the two
     * protocols can be up/down independently of each other.
     */
    private fun updateMavlinkHttpStatusView() {
        val statusTv = findViewById<TextView>(R.id.text_mavlink_http_status) ?: return
        val mavlinkUp = ProcessMavlinkRuntimeRegistry.isUp()
        val httpUp = ProcessNetworkRuntimeRegistry.status().httpPort != null
        val mavlinkColor = if (mavlinkUp) 0xFF2196F3.toInt() else 0xFFFF1744.toInt()
        val httpColor = if (httpUp) 0xFF4CAF50.toInt() else 0xFFFF1744.toInt()

        val text = "MAVLINK HTTP"
        val spannable = android.text.SpannableString(text)
        spannable.setSpan(
            android.text.style.ForegroundColorSpan(mavlinkColor),
            0,
            "MAVLINK".length,
            android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        spannable.setSpan(
            android.text.style.ForegroundColorSpan(httpColor),
            "MAVLINK ".length,
            text.length,
            android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        statusTv.text = spannable
    }

    private fun setupDroneNameDisplay() {
        // Find the TextView in the layout
        val droneNameText = findViewById<TextView>(R.id.text_drone_name)
        droneNameText?.let {
            // Set initial text
            it.text = "$droneName · V${currentMavlinkSystemId()}"

            // Make it clickable to change drone name
            it.setOnClickListener {
                settingsPages.showDroneNameDialog(isFirstTime = false)
            }
        }

        findViewById<ImageButton>(R.id.button_lyrebird_settings)?.setOnClickListener {
            settingsPages.showLyrebirdSettingsMenu()
        }
    }

    private fun updateDroneNameDisplay() {
        val droneNameText = findViewById<TextView>(R.id.text_drone_name)
        droneNameText?.text = "$droneName · V${currentMavlinkSystemId()}"
    }

    private fun setupKeyListeners() {
        aircraftTelemetry.setupBatteryAndRthListeners()
        setupStorageListeners()
        setupFlightStateListeners()
        setupTelemetryListeners()
    }

    private fun setupStorageListeners() {
        KeyManager.getInstance().listen(cameraStorageInfosKey, this) { _, newValue ->
            if (isSdCardInserted(newValue)) {
                preferSdCardStorage(newValue)
            }
        }
    }

    private fun setupFlightStateListeners() {
        flightStateSubscription?.close()
        flightStateSubscription =
            aircraftTelemetry.subscribe(
                object : AircraftTelemetryListener {
                    override fun onFlyingChanged(flying: Boolean) {
                        // The airborne latch, the flight-log session and takeoff-time detection
                        // live in the process runtime's own subscription; this screen only
                        // refreshes what it displays.
                        mainHandler.post { updateDroneStatusView(DroneController.droneStatus) }
                    }

                    override fun onFlightModeChanged(mode: AircraftFlightMode) {
                        mainHandler.post {
                            cachedFlightMode = mode
                            reevaluateAircraftIdle()
                        }
                    }

                    override fun onSatelliteCountChanged(count: Int) {
                        mainHandler.post {
                            cachedSatelliteCount = count
                            reevaluateAircraftIdle()
                        }
                    }
                },
            )
    }

    // ==================== Aircraft idle (low-power / eco) detection ====================

    /**
     * Detects the aircraft sitting idle on the ground and shows a notice telling the pilot to wake
     * it with the manual both-sticks-down-and-inwards gesture (there is no reliable app-side
     * motor-start on every airframe). Two shapes reach it, and the gesture is the way out of both:
     *
     *  - low-power (eco) standby, where the flight controller has stopped answering while the
     *    product link stays up — the aircraft is asleep, motors off, GPS and camera quiet;
     *  - awake with motors off: no flight mode and no satellite fix yet.
     *
     * The overlay is delayed by [idleDetectDebounceMs] so transient states never flash it.
     */
    private fun setupAircraftIdleMonitor() {
        val readings = aircraftTelemetry.readState().readings
        cachedFlightMode = V5AircraftTelemetrySource.neutralFlightMode(readings.flightMode)
        cachedSatelliteCount = readings.satelliteCount
        reevaluateAircraftIdle()
    }

    /**
     * The aircraft is still here but not answering the flight controller.
     *
     * None of the connection keys separates standby from switched-off on their own: the product
     * key and the RC-to-aircraft link stay true for the rest of the RC session either way (both
     * field-verified 2026-09-21 on mini1 with the aircraft powered off), and the flight-controller
     * key is false in both. What does differ is whether anything of the aircraft is still being
     * recognised: an aircraft in standby is still resolved with its camera attached, while a
     * powered-off one leaves the product type UNRECOGNIZED and the camera key false. Requiring one
     * of those is what stops the badge claiming ECO at an aircraft that is gone.
     */
    private fun isAircraftAsleep(): Boolean = !aircraftConnected && productConnectionKey.get(false) && isAircraftStillRecognised()

    /** Whether DJI still resolves part of the aircraft, as opposed to having forgotten all of it. */
    private fun isAircraftStillRecognised(): Boolean {
        if (cameraConnectionKey.get(false)) return true
        val productType = productTypeKey.get(ProductType.UNKNOWN)
        return productType != ProductType.UNKNOWN && productType != ProductType.UNRECOGNIZED
    }

    /** On the link at all: answering the flight controller, or asleep with the aircraft still linked. */
    private fun isAircraftOnLink(): Boolean = aircraftConnected || isAircraftAsleep()

    private fun isRemoteControllerLinkUp(): Boolean = runCatching { remoteControllerLinkKey.get(false) }.getOrDefault(false)

    private fun isAircraftIdle(): Boolean =
        isAircraftOnLink() &&
            !DroneController.isAirborne &&
            (
                // Asleep: nothing is being reported, which is the state itself.
                !aircraftConnected ||
                    // Awake with motors off: no flight mode resolved and no satellite fix yet.
                    (cachedFlightMode == AircraftFlightMode.UNKNOWN && cachedSatelliteCount <= 0)
            )

    private fun idleStateSummary(): String =
        "airborne=${DroneController.isAirborne} connected=$aircraftConnected asleep=${isAircraftAsleep()} " +
            "product=${productConnectionKey.get(false)} camera=${cameraConnectionKey.get(false)} " +
            "rcLink=${isRemoteControllerLinkUp()} productType=${productTypeKey.get(ProductType.UNKNOWN)} " +
            "flightMode=$cachedFlightMode sats=$cachedSatelliteCount"

    private fun reevaluateAircraftIdle() {
        val isIdle = isAircraftIdle()
        if (isIdle && !idleDetectArmed && !idleOverlayVisible) {
            idleDetectArmed = true
            Log.i(TAG, "Aircraft idle candidate (${idleStateSummary()}) — arming $idleDetectDebounceMs ms debounce")
            mainHandler.postDelayed(showIdleOverlayRunnable, idleDetectDebounceMs)
        } else if (!isIdle) {
            // Hide whenever the aircraft leaves the idle signature, even if the debounce already
            // fired — otherwise a brief false idle would leave the overlay stuck on screen. The
            // overlay re-arms on the next idle, so a recurring idle shows again.
            idleDetectArmed = false
            mainHandler.removeCallbacks(showIdleOverlayRunnable)
            showIdleOverlay(false)
            Log.i(TAG, "Aircraft no longer idle (${idleStateSummary()}) — overlay hidden")
        }
    }

    private fun onIdleDetectDebounceElapsed() {
        idleDetectArmed = false
        if (isAircraftIdle()) {
            showIdleOverlay(true)
            Log.i(TAG, "Aircraft idle overlay SHOWN — ${idleStateSummary()}")
        }
    }

    private fun showIdleOverlay(visible: Boolean) {
        idleOverlayVisible = visible
        findViewById<View>(R.id.aircraft_idle_overlay)?.let { overlay ->
            overlay.visibility = if (visible) View.VISIBLE else View.GONE
        }
    }

    /**
     * Update the loading overlay's status line without touching its visibility.
     *
     * Narrating a startup phase must never force the overlay back on: the aircraft can (and
     * commonly does) connect before this point is reached, hiding the overlay already — calling
     * [showLoadingOverlay] with `visible = true` here would silently re-show it with a now-false
     * label, and nothing else would hide it again until some unrelated event happened to fire.
     * That was a real bug, caught via the diagnostic logging below: the overlay sat on "Waiting
     * for the aircraft to connect…" for ~23 seconds after the aircraft had already connected,
     * because this line had reopened it right after the connect listener closed it.
     */
    private fun updateLoadingDetail(detail: String) {
        findViewById<TextView>(R.id.lyrebird_loading_detail)?.text = detail
    }

    /**
     * @param detail a smaller status line under [message] naming the startup phase under way
     *   (registering with the DJI SDK, starting servers, waiting for the aircraft) — otherwise
     *   the overlay is just a spinner with no indication of what it is actually doing.
     */
    private fun showLoadingOverlay(
        visible: Boolean,
        message: String? = null,
        detail: String? = null,
    ) {
        findViewById<View>(R.id.lyrebird_loading_overlay)?.let { overlay ->
            overlay.visibility = if (visible) View.VISIBLE else View.GONE
            if (!visible) {
                // Diagnostic-only: this line running is not the same thing as the screen actually
                // showing it — the main thread can be busy enough right after connect (heavy
                // setup, a streaming reconnect loop) that a View.GONE here sits un-rendered for
                // seconds, which looks exactly like a stuck modal even though the state changed
                // instantly. These two callbacks prove whether that is happening: the first fires
                // once the main-thread queue is free to run more work, the second once the next
                // VSYNC frame is actually drawn.
                val hiddenAtMs = SystemClock.elapsedRealtime()
                overlay.post {
                    Log.i(TAG, "Loading overlay: main thread free ${SystemClock.elapsedRealtime() - hiddenAtMs}ms after GONE")
                }
                Choreographer.getInstance().postFrameCallback {
                    Log.i(TAG, "Loading overlay: next frame drawn ${SystemClock.elapsedRealtime() - hiddenAtMs}ms after GONE")
                }
            }
        }
        message?.let { text ->
            findViewById<TextView>(R.id.lyrebird_loading_message)?.text = text
        }
        detail?.let { text ->
            findViewById<TextView>(R.id.lyrebird_loading_detail)?.text = text
        }
    }

    private fun setupTelemetryListeners() {
        telemetrySubscription?.close()
        telemetrySubscription =
            aircraftTelemetry.subscribe(
                object : AircraftTelemetryListener {
                    override fun onAltitudeChanged(altitudeAslM: Double) {
                        latestAltitudeMetres = altitudeAslM
                        mainHandler.post { updateAltitudeView() }
                    }

                    override fun onGimbalPitchChanged(pitchDeg: Double) {
                        latestGimbalPitchDegrees = pitchDeg
                        mainHandler.post { updateAltitudeView() }
                    }

                    override fun onReadingsChanged() = rebuildTelemetryCache()
                },
            )
        updateAltitudeView()
    }

    /**
     * Resolve the name this aircraft answers to, and show it.
     *
     * The serial is not known yet when this runs at startup, and the rule used to be applied
     * anyway: an automatically named device resolved "UNKNOWN" into `lb_unknown`, wrote that over
     * its own name and published it — in the bottom bar, in discovery, in telemetry and on the
     * dashboard — until the serial arrived seconds later and [applyAutomaticDroneName] renamed it.
     * The name is an address; a placeholder that looks like one is worse than no name, so while the
     * serial is unknown the last known name stands and nothing is written.
     */
    private fun loadDroneName() {
        val storedName = sharedPreferences.getString(LyrebirdSettings.PREF_DRONE_NAME, "")?.trim().orEmpty()
        val explicit = sharedPreferences.getBoolean(LyrebirdSettings.PREF_DRONE_NAME_USER_SET, false)
        val serialKnown = droneSerialNumber.isNotBlank() && droneSerialNumber != LyrebirdSettings.UNKNOWN_SERIAL
        // The same rule the process uses when no screen is involved (LyrebirdSettings.droneName):
        // the operator's name when they set one, the serial-derived one otherwise.
        droneName =
            when {
                explicit && storedName.isNotEmpty() -> storedName
                serialKnown -> LyrebirdSettings.deriveDroneName(droneSerialNumber)
                storedName.isNotEmpty() -> storedName
                else -> LyrebirdSettings.DEFAULT_DRONE_NAME
            }
        if (explicit || serialKnown || storedName.isNotEmpty()) {
            sharedPreferences
                .edit()
                .putString(LyrebirdSettings.PREF_DRONE_NAME, droneName)
                .putBoolean(LyrebirdSettings.PREF_DRONE_NAME_USER_SET, explicit)
                .apply()
        }
        Log.i(TAG, "Loaded ${if (explicit) "user" else "automatic"} drone name: $droneName")
        // Whoever names the aircraft also shows it: a caller that forgot used to leave the bottom
        // bar printing the layout's own placeholder while the rest of the app used the real name.
        // The process applies the name to the telemetry frame and the flight log from the
        // preference write above (see ProcessTelemetryRuntimeRegistry).
        updateDroneNameDisplay()
    }

    private fun defaultDroneName(): String = LyrebirdSettings.deriveDroneName(droneSerialNumber)

    /**
     * Take the serial-derived name once the serial is known.
     *
     * The process does the same thing at the same moment (see the telemetry runtime's serial
     * listener), so a device with no screen gets named too; this path is what refreshes the bottom
     * bar of a screen that is open.
     */
    private fun applyAutomaticDroneName() {
        val applied = LyrebirdSettings(sharedPreferences).applyAutomaticDroneName(droneSerialNumber) ?: return
        droneName = applied
        mainHandler.post { updateDroneNameDisplay() }
    }

    private fun scheduleDefaultCameraRecordingConfiguration() {
        val delaysMs = longArrayOf(0L, 2_000L, 6_000L)
        delaysMs.forEach { delayMs ->
            mainHandler.postDelayed({ configureDefaultCameraRecording() }, delayMs)
        }
    }

    private fun configureDefaultCameraRecording() {
        setDefaultVideoMode()
        preferSdCardStorage(KeyManager.getInstance().getValue(cameraStorageInfosKey))
    }

    private fun setDefaultVideoMode() {
        val currentMode = KeyManager.getInstance().getValue(cameraModeKey)
        if (currentMode == CameraMode.VIDEO_NORMAL) {
            return
        }

        KeyManager
            .getInstance()
            .setValue(
                cameraModeKey,
                CameraMode.VIDEO_NORMAL,
                object : CommonCallbacks.CompletionCallback {
                    override fun onSuccess() {
                        Log.i(TAG, "Default camera mode set to video")
                    }

                    override fun onFailure(error: IDJIError) {
                        Log.w(TAG, "Could not set default camera mode to video: ${error.description()}")
                    }
                },
            )
    }

    private fun preferSdCardStorage(storageInfos: CameraStorageInfos?) {
        if (!isSdCardInserted(storageInfos)) {
            Log.i(TAG, "SD card storage not selected: SD card is not inserted")
            return
        }

        val currentLocation = KeyManager.getInstance().getValue(cameraStorageLocationKey)
        if (currentLocation == CameraStorageLocation.SDCARD) {
            return
        }

        KeyManager.getInstance().setValue(
            cameraStorageLocationKey,
            CameraStorageLocation.SDCARD,
            object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() {
                    Log.i(TAG, "Default camera storage set to SD card")
                }

                override fun onFailure(error: IDJIError) {
                    Log.w(TAG, "Could not set default camera storage to SD card: ${error.description()}")
                }
            },
        )
    }

    private fun isSdCardInserted(storageInfos: CameraStorageInfos?): Boolean =
        storageInfos
            ?.cameraStorageInfoList
            ?.firstOrNull { it.storageType == CameraStorageLocation.SDCARD }
            ?.storageState == SDCardLoadState.INSERTED

    private fun getDroneStorageStatus(
        location: CameraStorageLocation,
        label: String,
    ): DroneStorageStatus {
        val storageInfos: CameraStorageInfos? = KeyManager.getInstance().getValue(cameraStorageInfosKey)
        val info = storageInfos?.cameraStorageInfoList?.firstOrNull { it.storageType == location }
        val parts =
            listOfNotNull(
                info?.getStorageLeftCapacity()?.takeIf { it >= 0 }?.let { "${formatCapacity(it)} free" },
                info?.getStorageState()?.name?.takeIf { it.isNotBlank() && it != "UNKNOWN" },
                info?.getAvailableVideoDuration()?.takeIf { it >= 0 }?.let { "video ${formatDuration(it)}" },
            )
        return DroneStorageStatus(label, parts.ifEmpty { listOf("status unavailable") }.joinToString(", "))
    }

    private fun formatCapacity(megabytes: Int): String = SettingsDisplay.capacity(megabytes)

    private fun formatDuration(seconds: Int): String = SettingsDisplay.duration(seconds)

    private fun formatDroneStorage(
        location: CameraStorageLocation,
        label: String,
    ) {
        Toast.makeText(this, "Formatting $label...", Toast.LENGTH_SHORT).show()
        val key = KeyTools.createKey(CameraKey.KeyFormatStorage, ComponentIndexType.LEFT_OR_MAIN)
        KeyManager
            .getInstance()
            .performAction(
                key,
                location,
                object : CommonCallbacks.CompletionCallbackWithParam<EmptyMsg> {
                    override fun onSuccess(result: EmptyMsg?) {
                        mainHandler.post {
                            Toast.makeText(this@FlightDeckActivity, "$label formatted", Toast.LENGTH_LONG).show()
                        }
                        Log.i(TAG, "Formatted drone $label")
                    }

                    override fun onFailure(error: IDJIError) {
                        val message = "Failed to format $label: ${error.description()}"
                        mainHandler.post {
                            Toast.makeText(this@FlightDeckActivity, message, Toast.LENGTH_LONG).show()
                        }
                        Log.e(TAG, message)
                    }
                },
            )
    }

    private fun buildWhipUrl(clientIp: String): String =
        WhipEndpoint.url(
            clientIp = clientIp,
            droneName = droneName,
            configuredServer = settings.getMediamtxServer(),
        )

    private fun startLocationUpdates() {
        if (!deviceStatusSource.startLocationUpdates()) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1)
        }
    }

    override val runtimeDroneSerial: String
        get() = droneSerialNumber

    override fun telemetryJson(): String = getTelemetryJson()

    override fun gapTelemetryJson(): String = getGapTelemetryJson()

    override fun onTelemetryClient(clientIp: String) {
        ProcessStreamingRuntimeRegistry.startForClient(clientIp)
    }

    // The streamer's configuration (drone name, WebRTC options, MediaMTX address, WHIP URL) is not
    // here any more: the process composes it from preferences and the aircraft serial
    // (V5StreamingSettings), which is what lets the publisher exist with no screen attached. What
    // is left is what a screen does with a stream: show its state.

    override fun streamingUiOnMetrics(metrics: WebRTCStreamMetrics) {
        lastWebRTCMetrics = metrics
        rebuildTelemetryCache()
        mainHandler.post {
            updateWebRTCMetricsView(metrics)
            updateStreamingFooter()
        }
    }

    override fun streamingUiOnState(state: String) {
        lastNativeStreamStatus = state
        mainHandler.post { updateStreamingFooter() }
    }

    override fun streamingUiOnMessage(message: String) = showStreamToast(message)

    override fun streamingUiOnConfigChanged() {
        rebuildTelemetryCache()
        updateStreamingFooter()
    }

    override fun streamingUiRebuildTelemetryCache() {
        rebuildTelemetryCache()
    }

    override fun runtimeDetectionTargetsChanged(targets: List<DetectedTargetSnapshot>) {
        applyDetectedTargets(targets)
    }

    override fun runtimeDetectionMetricsChanged(metrics: EdgeDetectionMetrics) {
        lastEdgeMetrics = metrics
        mainHandler.post { updateEdgeMetricsView(metrics) }
    }

    override fun runtimeDetectionActiveChanged(active: Boolean) {
        mainHandler.post {
            updateDetectionTelemetryState()
            rebuildTelemetryCache()
        }
    }

    override fun runtimeDetectionUnsupported(mode: StreamingMode) {
        mainHandler.post {
            handleEdgeDetectionStartFailure(EdgeDetectionStartCheck.UnsupportedStreamingMode(mode))
        }
    }

    override fun runtimeDetectionNeedsModel() {
        mainHandler.post {
            handleEdgeDetectionStartFailure(EdgeDetectionStartCheck.MissingModel)
        }
    }

    // The obstacle guard's answers (motion, authority, stop) come from ProcessObstacleGuard in the
    // process: the guard has to be there for a mission a ground station started with no screen
    // open, and the screen that used to supply them is the one thing guaranteed to be gone. What
    // is left here is what a brake looks like on screen.

    override fun obstacleGuardOnBrake(event: ObstacleRuntimeBrake) {
        mainHandler.post { updateDroneStatusView(DroneController.droneStatus) }
    }

    // The MAVLink endpoint's callbacks (config, snapshot, video stream, parameters, command log,
    // peer discovery) are implemented by ProcessMavlinkCallbacks in the process: the endpoint
    // binds at app start, with or without a screen, and a ground station that pushes a new
    // vehicle id no longer has to wait for one to open.

    // The fleet mesh's identity and beacon come from the process (ProcessFleetRuntime), not from
    // this screen: a screen that is closed must not take the device off the mesh, and a screen
    // whose serial field resets mid-session is exactly how one aircraft came to appear twice on
    // every peer's roster. This activity keeps only the rendering side (FleetDeckController).

    private fun startServers() {
        // The streaming runtime itself is process-owned and was attached when the SDK registered,
        // with its configuration read from preferences; this screen only draws its state
        // (StreamingRuntimeUi).
        ProcessStreamingRuntimeRegistry.attachUi(this)
        ProcessNetworkRuntimeRegistry.commandLogger = { uri, postData ->
            LyrebirdFlightLogger.logCommand(uri, postData)
        }
        ProcessCommandSurface.attachUi(this)
        ProcessNetworkRuntimeRegistry.attachCallbacks(this)
        // The session was brought up with the process (see ProcessAppRuntime); start() is
        // idempotent while it is serving, and retries a session that failed to bind.
        val sessionStatus = ProcessNetworkRuntimeRegistry.start()
        Log.i(TAG, "Network session: ${sessionStatus.summary()}")
        sessionStatus.takeIf { it.blockedByAnotherSession }?.let {
            ToastUtils.showLongToast(it.summary())
        }
        if (!sessionStatus.leaseHeld || !sessionStatus.isServing) {
            Log.w(TAG, "Runtime startup skipped because this app does not own a serving session")
            ProcessStreamingRuntimeRegistry.detachUi(this)
            updateMavlinkHttpStatusView()
            return
        }

        // Warmed here for latency, not because the screen owns it: the same call is what a ground
        // station's connection triggers when no screen is attached.
        ProcessStreamingRuntimeRegistry.prepare()
        ProcessDetectionRuntimeRegistry.attach(applicationContext, this)
        // The guard arms with the process; attaching here (idempotent) covers a screen that came up
        // before the process boot did, and draws nothing on its own.
        ProcessObstacleRuntimeRegistry.attachUi(this)
        ProcessObstacleGuard.attach(applicationContext)

        // Fleet mesh. Discovery answers a ground station asking "who is out there"; this is the
        // same question asked between aircraft, which nothing on the device could answer before.
        startFleetMesh()

        // The MAVLink endpoint belongs to the process (it attached with the session boot); a
        // screen only reports its state. The retry covers the one case the boot cannot: a session
        // that was blocked by the other APK and only came up when this screen asked again.
        ProcessAppRuntime.startMavlinkEndpointIfServing()

        // The endpoint has now either bound or logged why not, so this is the first point where
        // the status line reflects what actually came up.
        updateMavlinkHttpStatusView()

        // A process-scoped publisher may already be active after recreation. The target policy
        // leaves a healthy publisher alone and restarts a stale one against the new callbacks.
        if (ProcessStreamingRuntimeRegistry.hasTarget()) {
            ProcessStreamingRuntimeRegistry.restartActiveStreaming()
        }
    }

    private fun showServerInfo() {
        val deviceIp = NetworkUtils.getDeviceIpAddress() ?: "Unknown"
        val message =
            """
            Lyrebird Servers Started
            IP: $deviceIp
            HTTP Commands: $HTTP_PORT
            Telemetry: $TELEMETRY_PORT
            Video: WHIP (auto on bridge connect)
            """.trimIndent()

        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        Log.i(TAG, message)
    }

    // Fault barrier: the DJI SDK does not document an exception hierarchy for these calls, so a
    // narrower catch would let an unanticipated type escape. This boundary must degrade, not throw.
    @Suppress("TooGenericExceptionCaught")
    override fun onDestroy() {
        settingsDialogViews.dismiss()
        detachDefaultLayoutHsiWidgets()

        try {
            // Stop AutoSensing
            stopAutoSensing()

            stopEdgeDetection()

            // The fleet link holds a repeating main-thread callback that must not outlive the
            // activity. Its sockets are its own; the session's discovery sockets are already down.
            stopFleetMesh()

            // Detach this screen from the process-scoped command surface. The network runtime
            // keeps serving through the surface itself — settings and telemetry no longer need a
            // screen — so there is no host to unplug here; only the weak UI reference goes away.
            ProcessCommandSurface.detachUi(this)
            // Only this screen's callbacks: the session, the media source and the command sinks
            // are process-owned now, and detaching them here would unplug the command path the
            // runtime is supposed to keep serving.
            ProcessNetworkRuntimeRegistry.detachCallbacks(this)
            ProcessStreamingRuntimeRegistry.detachUi(this)
            ProcessObstacleRuntimeRegistry.detachUi(this)
            ProcessDetectionRuntimeRegistry.detach(this)

            // This screen's own telemetry subscriptions; the process runtime's own subscription
            // is not this activity's to close.
            telemetrySubscription?.close()
            flightStateSubscription?.close()

            // Cancel key listeners
            KeyManager.getInstance().cancelListen(this)

            // Detach the M400 main-camera first-frame detector if still registered
            unregisterMainCamFrameDetector()

            // UI listeners are activity-bound; the process aircraft session remains available to
            // the network runtimes and the next activity instance.
            DroneController.manualOverrideListener = null
            DroneController.droneStatusListener = null
            ControlAuthority.listener = null

            // Close the active flight log if the app is killed mid-flight
            LyrebirdFlightLogger.endSession("app_stopped")

            // Persist this aircraft's settings so the next flight on the same drone restores them.
            DroneSettingsProfiles.saveCurrentProfile(sharedPreferences, LyrebirdSettings.PER_DRONE_PROFILE_KEYS)

            mainHandler.removeCallbacksAndMessages(null)

            Log.i(TAG, "All servers stopped")
        } finally {
            super.onDestroy()
        }
    }

    override fun onPause() {
        // DefaultLayoutActivity contains SurfaceViews owned by the DJI UXSDK. Hide the whole
        // layout before another activity becomes visible so its last compositor frame cannot
        // bleed into the welcome screen during the activity transition.
        findViewById<View>(R.id.root_view)?.visibility = View.INVISIBLE
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        findViewById<View>(R.id.root_view)?.visibility = View.VISIBLE
    }

    private fun detachDefaultLayoutHsiWidgets() {
        runCatching {
            val hsiWidget = horizontalSituationIndicatorWidget ?: return
            val parent = hsiWidget.parent as? ViewGroup ?: return
            parent.removeView(hsiWidget)
        }.onFailure { error ->
            Log.w(TAG, "Failed to detach HSI widgets during destroy: ${error.message}", error)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, "Change Drone Name")
        menu.add(0, 20, 1, "Configure Stream/WebRTC...")
        menu.add(0, 21, 2, detectionMenuLabel()).apply {
            isCheckable = true
            isChecked = isDetectionActiveForUi()
        }
        menu.add(0, 10, 3, "Detection Settings...")
        menu.add(0, 22, 4, mavlinkFlightAllowedMenuLabel()).apply {
            isCheckable = true
            isChecked = isMavlinkFlightAllowed()
        }
        var nextOrder = 5
        menu.add(0, 3, nextOrder++, "Format Drone SD Card")
        menu.add(0, 4, nextOrder, "Format Drone Internal Storage")
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean =
        if (handleLyrebirdMenuItem(item.itemId)) true else super.onOptionsItemSelected(item)

    private fun handleLyrebirdMenuItem(itemId: Int): Boolean {
        val action =
            when (itemId) {
                1 -> {
                    { settingsPages.showDroneNameDialog(isFirstTime = false) }
                }
                2, 5, 7, 9, 20 -> settingsPages::showStreamSettingsDialog
                21 -> {
                    { setDetectionsEnabled(!isDetectionActiveForUi()) }
                }
                22 -> ::toggleMavlinkFlightAllowed
                8, 10 -> settingsPages::showDetectionSettingsDialog
                11 -> {
                    { showEdgeFilePicker(REQUEST_EDGE_MODEL_FILE, "Select YOLO TFLite model") }
                }
                12 -> {
                    { showEdgeFilePicker(REQUEST_EDGE_LABELS_FILE, "Select model labels") }
                }
                13 -> settingsPages::showEdgeConfidenceDialog
                3 -> {
                    { settingsPages.showFormatStorageDialog(AircraftStorage.SDCARD, "SD card") }
                }
                4 -> {
                    { settingsPages.showFormatStorageDialog(AircraftStorage.INTERNAL, "internal storage") }
                }
                else -> return false
            }
        action()
        return true
    }

    // ==================== Utility Methods ====================

    private fun fetchDroneSerialNumber() {
        runCatching {
            // Get drone serial number from DJI SDK
            val serialKey = KeyTools.createKey(FlightControllerKey.KeySerialNumber)
            KeyManager.getInstance().getValue(
                serialKey,
                object : dji.v5.common.callback.CommonCallbacks.CompletionCallbackWithParam<String> {
                    override fun onSuccess(serialNumber: String?) {
                        val previousSystemId = currentMavlinkSystemId()
                        val previousConfiguredId = configuredMavlinkSystemId()
                        droneSerialNumber = serialNumber?.trim()?.takeIf { it.isNotEmpty() } ?: "UNKNOWN"
                        Log.i(TAG, "Drone serial number: $droneSerialNumber")
                        // The process records the serial in the flight log and its video manifest
                        // when the serial listener fires (ProcessTelemetryRuntimeRegistry).
                        DroneSettingsProfiles.onAircraftChanged(
                            sharedPreferences,
                            LyrebirdSettings.PER_DRONE_PROFILE_KEYS,
                            droneSerialNumber,
                            mainHandler,
                        ) { profileApplied ->
                            if (profileApplied) {
                                // The restored profile may carry this aircraft's name and streaming
                                // settings; re-derive the name and its display from them.
                                loadDroneName()
                                updateDroneNameDisplay()
                                // The pre-profile sysid check above could not see a restored manual
                                // sysid; restart the endpoint when the profile changed it.
                                if (configuredMavlinkSystemId() != previousConfiguredId) {
                                    ProcessAppRuntime.restartMavlinkEndpointIfServing()
                                }
                                Log.i(TAG, "Applied per-drone settings profile for $droneSerialNumber")
                            }
                        }
                        applyAutomaticDroneName()
                        if (!configuredMavlinkSystemIdIsManual() && previousSystemId != currentMavlinkSystemId()) {
                            ProcessAppRuntime.restartMavlinkEndpointIfServing()
                        }
                    }

                    override fun onFailure(error: dji.v5.common.error.IDJIError) {
                        droneSerialNumber = "UNKNOWN"
                        Log.w(TAG, "Failed to get drone serial: ${error.description()}")
                    }
                },
            )
        }.onFailure { error ->
            droneSerialNumber = "UNKNOWN"
            Log.e(TAG, "Error fetching drone serial: ${error.message}", error)
        }
    }

    // ==================== Telemetry Data ====================

    private fun startRoiTracking(
        latitudeDeg: Double,
        longitudeDeg: Double,
        altitudeM: Double,
    ) = ProcessRoiRuntimeRegistry.start(latitudeDeg, longitudeDeg, altitudeM)

    private fun stopRoiTracking() = ProcessRoiRuntimeRegistry.stop()

    private fun getTelemetryJson(): String = telemetryCoordinator.getTelemetryJson()

    private fun getGapTelemetryJson(): String = telemetryCoordinator.getGapTelemetryJson()

    private fun rebuildTelemetryCache() {
        // Streaming Config
        val activeMode = settings.getStreamingMode()
        telemetryCoordinator.streamingMode = activeMode.wireName
        telemetryCoordinator.rtspPort = settings.getRtspPort()
        telemetryCoordinator.rtspUser = settings.getRtspUsername()
        telemetryCoordinator.streamRequiresAuth = activeMode == StreamingMode.RTSP &&
            settings.getRtspUsername().isNotEmpty() &&
            settings.getRtspPassword().isNotEmpty()
        val serverIp = ProcessStreamingRuntimeRegistry.currentClientIp() ?: "127.0.0.1"
        telemetryCoordinator.rtmpUrl = getRtmpUrl(serverIp)

        // Compute exact consumption path dynamically for backend and telemetry exposure.
        // No credentials in the published URL: the path describes where the stream is, and the
        // client that pulls it authenticates with credentials held on its own side — which is
        // what the bridge does. Telemetry reaches every client that connects to the port, so
        // anything embedded here is disclosed to all of them.
        val phoneIp = NetworkUtils.getDeviceIpAddress() ?: "127.0.0.1"
        val port = settings.getRtspPort()
        val path =
            when (activeMode) {
                StreamingMode.WEBRTC -> "whip"
                StreamingMode.RTSP -> "rtsp://$phoneIp:$port$DJI_RTSP_STREAM_PATH"
                StreamingMode.RTMP -> getRtmpUrl(serverIp)
                StreamingMode.AGORA -> "agora://${settings.getAgoraChannel()}"
                StreamingMode.GB28181 -> "gb28181://${settings.getGbServerIp()}:${settings.getGbServerPort()}/${settings.getGbChannel()}"
            }
        telemetryCoordinator.consumptionPath = path

        rebuildRealTelemetryCache()

        deviceStatusSource.snapshot().applyTo(telemetryCoordinator)

        // WebRTC Metrics
        telemetryCoordinator.webRtcMetricsJson = lastWebRTCMetrics.toTelemetryJson()

        // Rebuild cache inside the coordinator
        telemetryCoordinator.rebuildTelemetryCache()
    }

    // ==================== MAVLink ====================

    /** Promote the old shipped-off default once; later explicit blocks remain operator choices. */
    private fun migrateMavlinkFlightDefault() {
        if (sharedPreferences.getBoolean(LyrebirdSettings.PREF_MAVLINK_FLIGHT_DEFAULT_MIGRATED, false)) return
        sharedPreferences
            .edit()
            .putBoolean(MavlinkEndpointConfig.PREF_ALLOW_FLIGHT, true)
            .putBoolean(LyrebirdSettings.PREF_MAVLINK_FLIGHT_DEFAULT_MIGRATED, true)
            .apply()
    }

    private fun isMavlinkFlightAllowed(): Boolean = sharedPreferences.getBoolean(MavlinkEndpointConfig.PREF_ALLOW_FLIGHT, true)

    private fun mavlinkFlightAllowedMenuLabel(): String =
        if (isMavlinkFlightAllowed()) "MAVLink Flight: Allowed" else "MAVLink Flight: Blocked"

    /**
     * Toggle `lb_mav_0_allow_flight` from the settings menu, rather than only adb or the settings
     * backup file — a fresh install or a restored-from-a-different-device backup should not need
     * a computer to fly again. Turning it on is confirmed, since it lets any MAVLink ground
     * station reaching this endpoint command takeoff, landing, RTH and missions; turning it back
     * off only removes capability, so that direction is immediate.
     */
    private fun toggleMavlinkFlightAllowed() {
        if (isMavlinkFlightAllowed()) {
            setMavlinkFlightAllowed(false)
            return
        }
        AlertDialog
            .Builder(this)
            .setTitle("Allow MAVLink flight control?")
            .setMessage(
                "A MAVLink ground station (QGroundControl or similar) will be able to command " +
                    "takeoff, landing, return-to-home and missions on this aircraft.",
            ).setPositiveButton("Allow") { _, _ -> setMavlinkFlightAllowed(true) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setMavlinkFlightAllowed(allowed: Boolean) {
        sharedPreferences.edit().putBoolean(MavlinkEndpointConfig.PREF_ALLOW_FLIGHT, allowed).apply()
        invalidateOptionsMenu()
        Toast
            .makeText(
                this,
                if (allowed) "MAVLink flight control allowed" else "MAVLink flight control blocked",
                Toast.LENGTH_SHORT,
            ).show()
    }

    // The MAVLink identity is resolved where the endpoint that publishes it lives
    // (ProcessMavlinkCallbacks): the screen shows and edits it, but a second copy of the rule
    // here is a second answer to "which vehicle id is this aircraft flying under".
    private fun configuredMavlinkSystemId(): Int = ProcessMavlinkCallbacks.configuredMavlinkSystemId()

    private fun configuredMavlinkSystemIdIsManual(): Boolean = MavlinkSystemId.isManual(configuredMavlinkSystemId())

    private fun currentMavlinkSystemId(): Int = ProcessMavlinkCallbacks.currentMavlinkSystemId()

    /** Read an int preference that may have been stored as a string by a hand edit. */
    private fun prefIntOrDefault(
        key: String,
        fallback: Int,
    ): Int = ProcessMavlinkCallbacks.prefIntOrDefault(key, fallback)

    // fleetDeviceId / generateFleetInstallId / buildFleetBeacon moved to ProcessFleetRuntime:
    // identity that flapped when this screen recreated is what made one aircraft appear twice on
    // every peer's roster, so the mesh now reads them from the process registries instead.

    /**
     * Build the fleet strip and hang it in the placeholder the layout reserves above the map.
     *
     * Created here rather than named in the XML because that layout lives in the `:uxsdk` module,
     * which cannot see app classes at compile time; the container is the seam between the two.
     */
    private fun attachFleetStrip(): FleetStripView? {
        val container =
            findViewById<android.widget.FrameLayout>(R.id.fleet_strip_container)
                ?: return null
        container.findViewById<FleetStripView>(R.id.fleet_strip)?.let { return it }
        return FleetStripView(this).apply {
            id = R.id.fleet_strip
            container.addView(this)
        }
    }

    /**
     * Where this aircraft is, for the fleet map's camera, or null while there is no fix.
     *
     * A null fix is not a position: the camera would happily centre on (0, 0) in the Gulf of
     * Guinea, which is what an unfixed GPS reports.
     */
    private fun aircraftPositionOrNull(): Pair<Double, Double>? {
        val location = aircraftTelemetry.getLocation3D()
        val latitude = location.latitude
        val longitude = location.longitude
        if (latitude == 0.0 && longitude == 0.0) return null
        return latitude to longitude
    }

    private fun startFleetMesh() {
        if (fleetController != null) return
        // The runtime callbacks are the process's (ProcessAppRuntime attached ProcessFleetRuntime
        // before this screen existed); a screen attach only needs the registry to exist and be
        // started, and must not hand the mesh a screen-scoped identity provider.
        val mesh = FleetMeshSessionRegistry.attach(applicationContext, sharedPreferences, ProcessFleetRuntime)
        val controller =
            FleetDeckController(
                activity = this,
                prefs = sharedPreferences,
                stripView = attachFleetStrip(),
                mapWidget = mapWidget,
                mesh = mesh,
                ownPosition = ::aircraftPositionOrNull,
            )
        controller.start()
        controller.setMapExpanded(sharedPreferences.getBoolean(LyrebirdSettings.PREF_MAP_EXPANDED, false))
        fleetMeshSession = mesh
        fleetController = controller
    }

    private fun stopFleetMesh() {
        fleetController?.detachUi()
        fleetController = null
        // Deliberately no registry detach: the callbacks are the process's, attached before this
        // screen existed (ProcessAppRuntime), and because every screen attaches that same object,
        // a screen's teardown could clear an attachment the process was still using -- which left
        // the running mesh with no identity and no beacon, silently, for the rest of the process's
        // life. The mesh keeps running and keeps talking; ProcessAppRuntime stops it with the
        // process.
        fleetMeshSession = null
    }

    /**
     * Arm the obstacle guard, if the operator has opted in.
     *
     * Off by default: it changes what the aircraft does in flight, and a feature that does that
     * should be switched on deliberately rather than inherited from an app update. The guard itself
     * is process-owned and already armed with the session; this is the same attach, repeated safely
     * for the case of a screen that enabled it without the process having done so.
     */
    private fun startObstacleGuard() {
        ProcessObstacleGuard.attach(applicationContext)
    }

    private fun stopObstacleGuard() {
        ProcessObstacleRuntimeRegistry.stop()
    }

    private fun obstacleGuardSummary(): String =
        when {
            !ProcessObstacleRuntimeRegistry.isEnabled() -> "Off"
            ProcessObstacleRuntimeRegistry.isRunning() -> "On, ${ObstacleBrake.DEFAULT_MARGIN_M.toInt()}m standoff"
            else -> "On, sensors unavailable"
        }

    /**
     * Turn the obstacle guard on or off.
     *
     * The confirmation exists because this is the one Lyrebird setting that changes what the
     * aircraft does without anyone commanding it, and because what it does not do matters as much
     * as what it does. An operator who believes they have collision avoidance will fly differently
     * from one who knows they have a backstop that cannot see wires.
     */
    private fun toggleObstacleGuard() {
        val enabling = !ProcessObstacleRuntimeRegistry.isEnabled()
        if (!enabling) {
            sharedPreferences.edit().putBoolean(ObstacleGuard.PREF_ENABLED, false).apply()
            stopObstacleGuard()
            settingsPages.showLyrebirdSettingsMenu()
            return
        }
        AlertDialog
            .Builder(this)
            .setTitle("Enable obstacle guard?")
            .setMessage(
                "While Lyrebird is flying a waypoint or trajectory itself, the aircraft's " +
                    "obstacle sensors are watched along its direction of travel. If the stopping " +
                    "distance is gone, Lyrebird cancels its own control loop and the aircraft " +
                    "holds position.\n\n" +
                    "It never steers around anything, and it never overrides you on the sticks.\n\n" +
                    "It cannot see into the airframe's blind arcs, and these sensors do not " +
                    "reliably detect wires, thin branches or netting. Treat it as a backstop, " +
                    "not as a reason to fly closer to anything.",
            ).setPositiveButton("Enable") { _, _ ->
                sharedPreferences.edit().putBoolean(ObstacleGuard.PREF_ENABLED, true).apply()
                startObstacleGuard()
                settingsPages.showLyrebirdSettingsMenu()
            }.setNegativeButton("Cancel", null)
            .show()
    }

    // The MAVLink snapshot, the advertised video stream and the published parameter list moved to
    // ProcessMavlinkCallbacks with the endpoint itself: they read the process projection, the
    // process registries and preferences, so nothing about them needed this screen.

    private fun rebuildRealTelemetryCache() {
        // The readings, mission latches and sensor flags are applied by the runtime projection —
        // the same one that keeps the TCP frame moving with no screen attached. Re-applying here
        // makes a settings change visible immediately rather than at the next telemetry event.
        ProcessTelemetryRuntimeRegistry.projection().apply()
    }

    // ── Command surface UI delegate ────────────────────────────────────────────

    // Flight motion is not here: a ground station flies the aircraft through
    // ProcessHttpFlightPort, with or without a screen (see ProcessCommandSurface).

    override fun commandSurfaceUpdateManualOverrideUi() {
        mainHandler.post { updateManualOverrideUI() }
    }

    override fun commandSurfaceSetAutoSensingSwitch(checked: Boolean) {
        mainHandler.post { setAutoSensingSwitchChecked(checked) }
    }

    override fun commandSurfaceOnStateChanged() {
        mainHandler.post {
            // A command that arrived over HTTP (or MAVLink) changed shared state; re-derive this
            // screen from preferences and the registries so the visible controls agree.
            loadDroneName()
            updateDroneNameDisplay()
            updateManualOverrideUI()
            updateDetectionTelemetryState()
            rebuildTelemetryCache()
            updateStreamingFooter()
            invalidateOptionsMenu()
        }
    }

    // ==================== HTTP Server ====================
}
