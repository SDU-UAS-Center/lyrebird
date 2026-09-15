package com.lyrebird.rc

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.net.Uri
import android.net.wifi.WifiManager
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
import androidx.lifecycle.ViewModelProvider
import com.lyrebird.rc.controller.ControlAuthority
import com.lyrebird.rc.controller.DroneController
import com.lyrebird.rc.controller.Payload
import com.lyrebird.rc.controller.RoiControl
import com.lyrebird.rc.controller.SafetyLatchStore
import com.lyrebird.rc.controller.V5FlightSettingsActions
import com.lyrebird.rc.controller.WaylineMissionHelper
import com.lyrebird.rc.edge.EdgeDetectionConfig
import com.lyrebird.rc.edge.EdgeDetectionController
import com.lyrebird.rc.edge.EdgeDetectionController.EdgeDetectionMetrics
import com.lyrebird.rc.fleet.FleetBeacon
import com.lyrebird.rc.fleet.FleetDeckController
import com.lyrebird.rc.fleet.FleetStripView
import com.lyrebird.rc.logger.LyrebirdFlightLogger
import com.lyrebird.rc.mavlink.CommandProgress
import com.lyrebird.rc.mavlink.CommandResult
import com.lyrebird.rc.mavlink.DetectedTargetSnapshot
import com.lyrebird.rc.mavlink.DistanceTrigger
import com.lyrebird.rc.mavlink.GimbalRotation
import com.lyrebird.rc.mavlink.GimbalRotationMode
import com.lyrebird.rc.mavlink.Mav
import com.lyrebird.rc.mavlink.MavlinkCommandOutcome
import com.lyrebird.rc.mavlink.MavlinkCommandSink
import com.lyrebird.rc.mavlink.MavlinkEndpointConfig
import com.lyrebird.rc.mavlink.MavlinkFtpServer
import com.lyrebird.rc.mavlink.MavlinkMissionSink
import com.lyrebird.rc.mavlink.MavlinkMotionSink
import com.lyrebird.rc.mavlink.MavlinkSnapshot
import com.lyrebird.rc.mavlink.MavlinkSystemId
import com.lyrebird.rc.mavlink.MavlinkTelemetryEndpoint
import com.lyrebird.rc.mavlink.MavlinkVideoStream
import com.lyrebird.rc.mavlink.MissionExecutor
import com.lyrebird.rc.mavlink.MissionItem
import com.lyrebird.rc.mavlink.MissionProgressListener
import com.lyrebird.rc.mavlink.PendingCommand
import com.lyrebird.rc.mavlink.PendingKind
import com.lyrebird.rc.models.BasicAircraftControlVM
import com.lyrebird.rc.models.LiveStreamVM
import com.lyrebird.rc.models.MediaVM
import com.lyrebird.rc.models.PayloadWidgetVM
import com.lyrebird.rc.models.VirtualStickVM
import com.lyrebird.rc.perception.ObstacleBrake
import com.lyrebird.rc.perception.ObstacleGuard
import com.lyrebird.rc.server.DiscoveryAdvertiser
import com.lyrebird.rc.server.LyrebirdDiscoveryManager
import com.lyrebird.rc.server.LyrebirdSession
import com.lyrebird.rc.server.SessionLease
import com.lyrebird.rc.server.TelemetryServer
import com.lyrebird.rc.settings.AircraftStorage
import com.lyrebird.rc.settings.DetectionSource
import com.lyrebird.rc.settings.DroneSettingsProfiles
import com.lyrebird.rc.settings.DroneStorageStatus
import com.lyrebird.rc.settings.FlightDeckSettingsPages
import com.lyrebird.rc.settings.LyrebirdOnboarding
import com.lyrebird.rc.settings.LyrebirdSettings
import com.lyrebird.rc.settings.LyrebirdSettingsBackup
import com.lyrebird.rc.settings.REQUEST_EDGE_LABELS_FILE
import com.lyrebird.rc.settings.REQUEST_EDGE_MODEL_FILE
import com.lyrebird.rc.settings.SettingsDialogViews
import com.lyrebird.rc.settings.SettingsDisplay
import com.lyrebird.rc.settings.SettingsPageActions
import com.lyrebird.rc.settings.SettingsSnapshot
import com.lyrebird.rc.telemetry.DeviceStatusSource
import com.lyrebird.rc.telemetry.GeoPoint3D
import com.lyrebird.rc.telemetry.TelemetryCoordinator
import com.lyrebird.rc.telemetry.V5AircraftTelemetrySource
import com.lyrebird.rc.telemetry.applyTo
import com.lyrebird.rc.telemetry.toFleetBeacon
import com.lyrebird.rc.telemetry.toMavlinkSnapshot
import com.lyrebird.rc.util.NetworkUtils
import com.lyrebird.rc.util.ToastUtils
import com.lyrebird.rc.utils.wpml.WaypointInfoModel
import com.lyrebird.rc.webrtc.TelemetryProvider
import com.lyrebird.rc.webrtc.WebRTCPeerFactory
import com.lyrebird.rc.webrtc.WebRTCStreamMetrics
import com.lyrebird.rc.webrtc.WebRTCStreamer
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
import dji.sdk.keyvalue.value.camera.LaserMeasureState
import dji.sdk.keyvalue.value.camera.SDCardLoadState
import dji.sdk.keyvalue.value.camera.ThermalTemperatureMeasureMode
import dji.sdk.keyvalue.value.common.CameraLensType
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.sdk.keyvalue.value.common.DoubleRect
import dji.sdk.keyvalue.value.common.EmptyMsg
import dji.sdk.keyvalue.value.common.LocationCoordinate3D
import dji.sdk.keyvalue.value.flightcontroller.FlightMode
import dji.sdk.keyvalue.value.gimbal.GimbalAngleRotation
import dji.sdk.keyvalue.value.gimbal.GimbalAngleRotationMode
import dji.sdk.keyvalue.value.gimbal.GimbalMode
import dji.sdk.keyvalue.value.product.ProductType
import dji.sdk.wpmz.value.mission.ActionGimbalRotateParam
import dji.sdk.wpmz.value.mission.ActionStartRecordParam
import dji.sdk.wpmz.value.mission.ActionStopRecordParam
import dji.sdk.wpmz.value.mission.ActionTakePhotoParam
import dji.sdk.wpmz.value.mission.WaylineActionInfo
import dji.sdk.wpmz.value.mission.WaylineActionType
import dji.sdk.wpmz.value.mission.WaylineFinishedAction
import dji.sdk.wpmz.value.mission.WaylineGimbalActuatorRotateMode
import dji.sdk.wpmz.value.mission.WaylineLocationCoordinate3D
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.et.action
import dji.v5.et.create
import dji.v5.et.createCamera
import dji.v5.et.get
import dji.v5.et.set
import dji.v5.manager.KeyManager
import dji.v5.manager.datacenter.MediaDataCenter
import dji.v5.manager.intelligent.AutoSensingInfo
import dji.v5.manager.intelligent.AutoSensingInfoListener
import dji.v5.manager.intelligent.AutoSensingTarget
import dji.v5.manager.intelligent.IntelligentFlightManager
import dji.v5.manager.intelligent.IntelligentModel
import dji.v5.manager.interfaces.ICameraStreamManager
import dji.v5.ux.detection.DetectedTarget
import dji.v5.ux.detection.DetectionOverlayView
import dji.v5.ux.map.MapWidget
import dji.v5.ux.sample.showcase.defaultlayout.DefaultLayoutActivity
import java.io.File
import java.io.OutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.abs

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
    LyrebirdCommandHost {
    companion object {
        private const val TAG = "LyrebirdDefaultLayout"

        /** text_drone_status's own size, from uxsdk_activity_default_layout.xml. */
        private const val DRONE_STATUS_NORMAL_TEXT_SIZE_SP = 11f

        /** Match the normal status size so an alert does not resize the status strip. */
        private const val DRONE_STATUS_ALERT_TEXT_SIZE_SP = 11f

        /** How long to wait for a DJI action callback before reporting the command failed. */
        private const val ACTION_TIMEOUT_MS = 2_000L

        /** How long to wait for a take-off to finish before abandoning a requested climb. */
        private const val TAKEOFF_CLIMB_TIMEOUT_MS = 30_000L
        private const val TAKEOFF_POLL_MS = 500L

        /** Longest a single mission leg may take before the plan is abandoned. */
        private const val MISSION_LEG_TIMEOUT_MS = 300_000L

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
         * How often the gimbal is re-aimed at a tracked point.
         *
         * Five times a second: fast enough that the picture follows rather than catches up, and
         * slow enough that each relative rotation has finished before the next is asked for.
         */
        private const val ROI_TRACK_INTERVAL_MS = 200L

        /** Below this the gimbal is left alone, so measurement noise does not make it hunt. */
        private const val ROI_DEADBAND_DEG = 0.5

        /** Largest single re-aim, so a target set behind the aircraft is a pan and not a whip. */
        private const val ROI_MAX_STEP_DEG = 15.0

        /**
         * Smallest orbit worth flying.
         *
         * A radius near zero is a rotation in place wearing an orbit's clothes, and the radial
         * correction would spend the whole time chasing the aircraft's own position noise across
         * a circle smaller than the error in measuring it.
         */
        private const val MIN_ORBIT_RADIUS_M = 5.0
        private const val MISSION_POLL_MS = 200L

        /** Beyond this, a reported gimbal angle is DJI's unset marker rather than a direction. */
        private const val MAX_PLAUSIBLE_GIMBAL_DEG = 200.0

        /**
         * The settings a ground station may write over MAVLink.
         *
         * Numeric settings only, and deliberately so: PARAM_SET carries a float, and the string
         * settings behind the rest of the /send/set* surface — the drone's name, the video
         * source, the MediaMTX address — have no honest float encoding. Those stay on HTTP until
         * they earn a proper home, rather than being smuggled through as magic numbers.
         */
        private const val PARAM_RTH_ALTITUDE = "LB_RTH_ALT"
        private const val PARAM_MAX_HEIGHT = "LB_MAX_HEIGHT"
        private const val PARAM_MAX_DISTANCE = "LB_MAX_DIST"
        private const val PARAM_DISTANCE_LIMIT = "LB_DIST_LIMIT_EN"
        private const val PARAM_WEBRTC_FPS = "LB_RTC_FPS"
        private const val PARAM_DETECTIONS = "LB_DETECT_EN"
        private const val PARAM_EDGE_CONFIDENCE = "LB_EDGE_CONF"
        private const val PARAM_SURFACE_H264_ENCODER = "LB_SURFACE_H264"
        private const val PARAM_MAVLINK_SYSTEM_ID = "LB_MAV_SYSID"

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

        private const val SETTINGS_BACKUP_DEBOUNCE_MS = 1500L
        private const val FLIGHT_DECK_RESTART_DELAY_MS = 750L

        // A manually configured mediamtxServer override that stops resolving (wrong network,
        // stale address left over from a different deployment) fails silently -- every retry
        // just repeats in Logcat. After this many consecutive WHIP failures with an override
        // active, clear it so the next reconnect falls back to the auto-detected client IP.
        private const val WHIP_OVERRIDE_FAILURE_THRESHOLD = 3
        private const val SAFETY_TOKEN = "98"

        private const val DJI_RTSP_STREAM_PATH = "/streaming/live/1"
    }

    private val liveStreamVM by lazy {
        ViewModelProvider(this)[LiveStreamVM::class.java]
    }

    override val mainHandler = Handler(Looper.getMainLooper())

    private var settingsBackupListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    // : The backup writes a file to Documents/Lyrebird; that I/O must not run on the main
    // : thread (StrictMode flags it). Serialized so debounced writes never pile up.
    private val settingsBackupExecutor =
        java.util.concurrent.Executors
            .newSingleThreadExecutor()
    private val settingsBackupTask =
        Runnable {
            settingsBackupExecutor.execute {
                LyrebirdSettingsBackup.save(sharedPreferences, droneName)
            }
        }
    private val telemetryCoordinator = TelemetryCoordinator()
    private val aircraftTelemetry = V5AircraftTelemetrySource()
    private lateinit var discoveryManager: LyrebirdDiscoveryManager

    // ViewModels for drone control
    private lateinit var basicAircraftControlVM: BasicAircraftControlVM
    private lateinit var virtualStickVM: VirtualStickVM
    lateinit var mediaVM: MediaVM
    lateinit var payloadWidgetVM: PayloadWidgetVM

    override val media: LyrebirdMediaPort by lazy {
        object : LyrebirdMediaPort {
            override fun capturePhotoFileName(): String? = Payload.capturePhoto(mediaVM)?.fileName

            override fun captureThermalJson(): String? = Payload.captureThermal(mediaVM)

            override fun listMediaJson(): String = Payload.listAllMedia(mediaVM)

            override fun sendMediaFile(
                fileName: String,
                outputStream: OutputStream,
            ) {
                Payload.sendMediaFileByName(mediaVM, fileName, outputStream)
            }

            override fun sendErrorResponse(
                message: String,
                outputStream: OutputStream,
            ) {
                Payload.sendErrorResponse(outputStream, message)
            }
        }
    }

    override val detection: LyrebirdDetectionPort by lazy {
        object : LyrebirdDetectionPort {
            override val isAutoSensingActive: Boolean
                get() = this@FlightDeckActivity.isAutoSensingActive

            override fun currentTargets(): List<DetectedTargetSnapshot> =
                currentDetectedTargets.map {
                    DetectedTargetSnapshot(it.type, it.left, it.top, it.right, it.bottom, it.confidence)
                }
        }
    }

    override val flight: LyrebirdFlightPort by lazy {
        object : LyrebirdFlightPort {
            override fun takeoff(): CommandResult {
                DroneController.startTakeOff()
                return CommandResult(MavlinkCommandOutcome.ACCEPTED)
            }

            override fun land(): CommandResult {
                DroneController.startLanding()
                return CommandResult(MavlinkCommandOutcome.ACCEPTED)
            }

            override fun returnToHome(): CommandResult {
                DroneController.startReturnToHome()
                return CommandResult(MavlinkCommandOutcome.ACCEPTED)
            }

            override fun stick(command: StickCommand): CommandResult {
                if (DroneController.shouldRejectAutonomousCommand("stick")) {
                    return CommandResult(MavlinkCommandOutcome.DENIED)
                }
                DroneController.setStick(command.leftX, command.leftY, command.rightX, command.rightY)
                return CommandResult(MavlinkCommandOutcome.ACCEPTED)
            }

            override fun gotoYaw(yawDeg: Double): CommandResult {
                if (DroneController.shouldRejectAutonomousCommand("gotoYaw")) {
                    return CommandResult(MavlinkCommandOutcome.DENIED)
                }
                val seq = DroneController.gotoYaw(yawDeg)
                return CommandResult(
                    MavlinkCommandOutcome.ACCEPTED,
                    pending = PendingCommand(PendingKind.YAW, seq),
                )
            }

            override fun gotoAltitude(altitudeM: Double): CommandResult {
                if (DroneController.shouldRejectAutonomousCommand("gotoAltitude")) {
                    return CommandResult(MavlinkCommandOutcome.DENIED)
                }
                val seq = DroneController.gotoAltitude(altitudeM)
                return CommandResult(
                    MavlinkCommandOutcome.ACCEPTED,
                    pending = PendingCommand(PendingKind.ALTITUDE, seq),
                )
            }

            override fun abortMission(): CommandResult {
                DroneController.setStick(0.0f, 0.0f, 0.0f, 0.0f)
                DroneController.disableVirtualStick()
                return CommandResult(MavlinkCommandOutcome.ACCEPTED)
            }

            override fun abortAll(): CommandResult {
                DroneController.abortAllMissions()
                return CommandResult(MavlinkCommandOutcome.ACCEPTED)
            }

            override fun enableVirtualStick(): CommandResult {
                if (DroneController.shouldRejectAutonomousCommand("enableVirtualStick")) {
                    return CommandResult(MavlinkCommandOutcome.DENIED)
                }
                DroneController.enableVirtualStick()
                return CommandResult(MavlinkCommandOutcome.ACCEPTED)
            }
        }
    }

    // Servers
    private var session: LyrebirdSession? = null

    /**
     * MAVLink 2 telemetry endpoint. On by default (`lb_mav_0_enabled`), following PX4's pattern
     * of switching MAVLink instances on by parameter rather than by build; flight motion is
     * gated separately by `lb_mav_0_allow_flight`.
     */
    private var mavlinkEndpoint: MavlinkTelemetryEndpoint? = null
    private var mavlinkFtpServer: MavlinkFtpServer? = null

    /**
     * Single worker for shutter operations. One thread, so two rapid capture commands queue rather
     * than tripping the shutter concurrently — the DJI media pipeline resolves new files by index
     * and overlapping captures would confuse which file belongs to which command.
     */
    private val captureExecutor =
        java.util.concurrent.Executors
            .newSingleThreadExecutor()

    /**
     * MAVLink FTP worker. Two threads so a slow file download does not block a directory listing:
     * both pull the same DJI media pipeline, but the SDK serialises the pulls themselves.
     */
    private val ftpExecutor =
        java.util.concurrent.Executors
            .newFixedThreadPool(2)
    private var webRTCStreamer: WebRTCStreamer? = null
    private var videoSettingRestartScheduled = false

    @Volatile private var lastWhipUrl: String? = null

    // Remembered for FPS/Quality mode restarts
    @Volatile private var whipConsecutiveFailures = 0

    // Reset on success; drives the override fallback below
    @Volatile private var lastClientIp: String? = null

    private var droneSerialNumber: String = "UNKNOWN"

    /**
     * Awareness of the other Lyrebird aircraft on this network.
     *
     * Null until [startServers] brings it up, and left null entirely when the operator has turned
     * the mesh off. Everything it does is read-only with respect to this aircraft: it publishes a
     * state beacon, draws peers, and warns. No inbound message on its socket can command anything.
     */
    private var fleetController: FleetDeckController? = null

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

                override fun settingsSnapshot() = this@FlightDeckActivity.settingsSnapshot()

                override fun currentMavlinkSystemId() = this@FlightDeckActivity.currentMavlinkSystemId()

                override fun prefIntOrDefault(
                    key: String,
                    fallback: Int,
                ) = this@FlightDeckActivity.prefIntOrDefault(key, fallback)

                override fun setAutomaticDroneName() = this@FlightDeckActivity.setAutomaticDroneName()

                override fun setDroneName(name: String) = this@FlightDeckActivity.setDroneName(name)

                override fun setMavlinkSystemId(value: Int) = this@FlightDeckActivity.setMavlinkSystemId(value)

                override fun isMavlinkFlightAllowed() = this@FlightDeckActivity.isMavlinkFlightAllowed()

                override fun mavlinkFlightAllowedMenuLabel() = this@FlightDeckActivity.mavlinkFlightAllowedMenuLabel()

                override fun setMavlinkFlightAllowed(allowed: Boolean) = this@FlightDeckActivity.setMavlinkFlightAllowed(allowed)

                override fun isDetectionActiveForUi() = this@FlightDeckActivity.isDetectionActiveForUi()

                override fun setDetectionSource(source: DetectionSource) = this@FlightDeckActivity.setDetectionSource(source)

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

                override fun shouldRestartActiveStreaming() = session?.hasTelemetryClients() == true || lastWhipUrl != null

                override fun restartActiveStreaming() = this@FlightDeckActivity.restartActiveStreaming()

                override fun changeVideoOptions() {
                    webRTCStreamer?.changeMediaOptions(settings.buildWebRTCOptions())
                }

                override fun toggleDjiSurfaceH264Encoder() = this@FlightDeckActivity.toggleDjiSurfaceH264Encoder()

                override fun obstacleGuardSummary() = this@FlightDeckActivity.obstacleGuardSummary()

                override fun toggleObstacleGuard() = this@FlightDeckActivity.toggleObstacleGuard()
            },
        )
    }
    override var droneName: String = LyrebirdSettings.DEFAULT_DRONE_NAME

    private val deviceStatusSource by lazy { DeviceStatusSource(applicationContext) }
    private var wifiManager: WifiManager? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    // Keeps the radio in low-latency mode while WHIP is publishing: flight 1 showed silent
    // frame stalls at the encoder with zero RTP loss, a signature of Wi-Fi power save on the
    // publishing device. Acquired when streaming starts, released in onDestroy.
    private var lowLatencyWifiLock: WifiManager.WifiLock? = null

    @Volatile private var lastWebRTCMetrics = WebRTCStreamMetrics()

    @Volatile private var lastNativeStreamStatus: String = "idle"

    @Volatile private var latestAltitudeMetres: Double = 0.0

    @Volatile private var latestGimbalPitchDegrees: Double = 0.0

    // Home point tracking
    private var isHomePointSetLatch = false

    // ==================== AutoSensing (AI Detection) ====================
    var isAutoSensingActive = false
    private var isAutoSensingListenerRegistered = false
    private var edgeDetectionController: EdgeDetectionController? = null

    @Volatile private var lastEdgeMetrics = EdgeDetectionMetrics()

    @Volatile var currentDetectedTargets: List<DetectedTarget> = emptyList()
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

    private val autoSensingInfoListener =
        object : AutoSensingInfoListener {
            override fun onAutoSensingInfoUpdate(info: AutoSensingInfo) {
                if (settings.getDetectionSource() != DetectionSource.DJI_ONBOARD) return
                val targets =
                    info.targets?.mapIndexed { idx, t ->
                        val rect = t.rect
                        // DoubleRect is center-based: (x,y) = center, (width,height) = dimensions
                        val cx = rect?.x ?: 0.0
                        val cy = rect?.y ?: 0.0
                        val hw = (rect?.width ?: 0.0) / 2.0
                        val hh = (rect?.height ?: 0.0) / 2.0
                        DetectedTarget(
                            index = t.targetIndex,
                            type = t.targetType?.name ?: "UNKNOWN",
                            left = cx - hw,
                            top = cy - hh,
                            right = cx + hw,
                            bottom = cy + hh,
                        )
                    } ?: emptyList()
                applyDetectedTargets(targets)
            }

            override fun onTrackingTargetUpdate(target: AutoSensingTarget) = Unit

            override fun onIntelligentModelUpdate(models: MutableList<IntelligentModel>) = Unit

            override fun onRunningIntelligentModelUpdate(modelId: Int) = Unit
        }
    // ==================== End AutoSensing Fields ====================

    // var, not val: on the M400 these are rebound to LEFT_OR_MAIN once the main-camera video is up
    // (see rebindGimbalKeysForM400). Other aircraft keep the default no-index binding.
    var gimbalKey: DJIKey.ActionKey<GimbalAngleRotation, EmptyMsg> = GimbalKey.KeyRotateByAngle.create()
    val zoomKey: DJIKey<Double> = CameraKey.KeyCameraZoomRatios.create()
    val startRecording: DJIKey.ActionKey<EmptyMsg, EmptyMsg> = CameraKey.KeyStartRecord.create()
    val stopRecording: DJIKey.ActionKey<EmptyMsg, EmptyMsg> = CameraKey.KeyStopRecord.create()

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

    @Volatile private var cachedFlightMode: FlightMode = FlightMode.UNKNOWN

    @Volatile private var cachedSatelliteCount = -1

    @Volatile private var idleDetectArmed = false

    @Volatile private var idleOverlayVisible = false
    private val showIdleOverlayRunnable = Runnable { onIdleDetectDebounceElapsed() }

    @Volatile var lrfTargetLocation: LocationCoordinate3D? = null

    /**
     * Range from the last laser lock, in metres, or null when it has not locked.
     *
     * Kept beside the target point because DISTANCE_SENSOR reports the range and
     * LYREBIRD_STATUS reports where that range landed; both come from the same reading, and
     * publishing one without the other would let them drift apart.
     */
    @Volatile
    private var lrfDistanceMeters: Double? = null

    private val productTypeKey: DJIKey<ProductType> = ProductKey.KeyProductType.create()
    private val flightControllerConnectionKey: DJIKey<Boolean> = FlightControllerKey.KeyConnection.create()

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
    private var thermalArmed = false

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

        discoveryManager = LyrebirdDiscoveryManager(this) { droneName }

        // Initialize SharedPreferences
        sharedPreferences = getSharedPreferences("LyrebirdPrefs", Context.MODE_PRIVATE)
        migrateMavlinkFlightDefault()

        // Load or prompt for drone name
        loadDroneName()

        // Setup drone name display
        setupDroneNameDisplay()

        // Initialize ViewModels
        basicAircraftControlVM = ViewModelProvider(this)[BasicAircraftControlVM::class.java]
        virtualStickVM = ViewModelProvider(this)[VirtualStickVM::class.java]

        // Initialize DroneController
        DroneController.init(basicAircraftControlVM, virtualStickVM)

        mediaVM = ViewModelProvider(this)[MediaVM::class.java]
        mediaVM.init()
        mediaVM.setStorage(CameraStorageLocation.SDCARD)
        mediaVM.setComponentIndex(ComponentIndexType.LEFT_OR_MAIN)

        // PayloadWidgetVM drives the payload-release servo for the /send/drop endpoint.
        payloadWidgetVM = ViewModelProvider(this)[PayloadWidgetVM::class.java]

        // Start listening for RC stick inputs (needed for manual override detection)
        virtualStickVM.listenRCStick()

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

        startLocationUpdates()

        wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

        // Acquire Multicast Lock to allow receiving UDP broadcasts
        multicastLock = wifiManager?.createMulticastLock("LyrebirdMulticastLock")
        multicastLock?.setReferenceCounted(true)
        multicastLock?.acquire()

        deviceStatusSource.startSensorUpdates()

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

    override fun updateManualOverrideUI() {
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
    override fun classifyCommandSource(presentedToken: String?): ControlAuthority.Source =
        if (presentedToken == SAFETY_TOKEN) {
            ControlAuthority.Source.SAFETY
        } else {
            ControlAuthority.Source.PILOT
        }

    private fun setupControlAuthorityBanner() {
        // The latch outlives the process: a restart is not a release, so the stored authority for
        // this aircraft is read back here, before the command server can accept anything. The
        // serial is only known once the SDK answers, so it is asked for at each read and written
        // under whatever it is at the time; see AuthorityLatch.restore().
        ControlAuthority.attachPersistence(
            SafetyLatchStore(sharedPreferences),
            aircraftSerial = { droneSerialNumber },
        )
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

    override fun setDjiSurfaceH264Encoder(enabled: Boolean) {
        if (settings.isDjiSurfaceH264EncoderEnabled() == enabled) return
        sharedPreferences
            .edit()
            .putBoolean(WebRTCPeerFactory.PREF_USE_DJI_SURFACE_H264_ENCODER, enabled)
            .apply()
        if (videoSettingRestartScheduled) return
        videoSettingRestartScheduled = true
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

    override fun setStreamingMode(mode: StreamingMode) {
        sharedPreferences.edit().putString(LyrebirdSettings.PREF_STREAMING_MODE, mode.prefValue).apply()
        if (mode != StreamingMode.WEBRTC &&
            settings.isDetectionsEnabled() &&
            settings.getDetectionSource() == DetectionSource.YOLO_ON_PHONE
        ) {
            setDetectionsEnabled(false)
            Toast
                .makeText(
                    this,
                    "YOLO edge detection deactivated (only supported in WebRTC mode)",
                    Toast.LENGTH_LONG,
                ).show()
        }
        rebuildTelemetryCache()
        updateStreamingFooter()
    }

    private fun getRtmpUrl(clientIp: String): String {
        val stored = sharedPreferences.getString(LyrebirdSettings.PREF_RTMP_URL, "")?.trim().orEmpty()
        return stored.ifEmpty { "rtmp://$clientIp:1935/$droneName" }
    }

    private fun resolveRtspPortForStart(): Int {
        val configuredPort = settings.getRtspPort()
        if (!NetworkUtils.isPortInUse(configuredPort)) {
            return configuredPort
        }
        val fallbackPorts = intArrayOf(18554, 28554, 38554)
        return fallbackPorts.firstOrNull { !NetworkUtils.isPortInUse(it) } ?: configuredPort
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
        mapWidget.setOnClickListener {
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
        mapWidget.setMapCenterLock(if (expanded) MapWidget.MapCenterLock.NONE else MapWidget.MapCenterLock.AIRCRAFT)
        mapWidget.setAutoFrameMapEnabled(false)
        // The expanded map is brought to the front over the whole screen, including the corner the
        // fleet strip occupies. The peers are all still on the map itself, so the strip stands down.
        fleetController?.setMapExpanded(expanded)
        mapWidget.bringToFront()
        button?.bringToFront()
        button?.visibility = if (expanded) View.VISIBLE else View.GONE
        button?.contentDescription = if (expanded) "Minimize map" else "Expand map"
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

    private fun getEdgeModelUri(): Uri? = sharedPreferences.getString(LyrebirdSettings.PREF_EDGE_MODEL_URI, null)?.let(Uri::parse)

    private fun getEdgeLabels(): List<String> {
        val labelsUri =
            sharedPreferences
                .getString(
                    LyrebirdSettings.PREF_EDGE_LABELS_URI,
                    null,
                )?.let(Uri::parse) ?: return listOf("person")
        return readEdgeLabels(labelsUri).ifEmpty { listOf("person") }
    }

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
        gimbalKey = GimbalKey.KeyRotateByAngle.create(ComponentIndexType.PORT_3)
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

    private fun thermalCameraIndex(): ComponentIndexType =
        if (isMatrice400()) ComponentIndexType.PORT_3 else ComponentIndexType.LEFT_OR_MAIN

    private fun armThermalMeasurement() {
        if (thermalArmed) return
        thermalArmed = true
        val idx = thermalCameraIndex()
        val lens = CameraLensType.CAMERA_LENS_THERMAL
        Log.i(TAG_THERMAL, "Arming thermal measurement on camera index=$idx lens=THERMAL")

        CameraKey.KeyThermalTemperatureDataEnabled.createCamera(idx, lens).set(
            true,
            onSuccess = { Log.i(TAG_THERMAL, "ThermalTemperatureDataEnabled=true OK") },
            onFailure = { e -> Log.e(TAG_THERMAL, "set TemperatureDataEnabled failed: ${e.description()}") },
        )

        CameraKey.KeyThermalTemperatureMeasureMode.createCamera(idx, lens).set(
            ThermalTemperatureMeasureMode.REGION,
            onSuccess = {
                Log.i(TAG_THERMAL, "MeasureMode=REGION OK; setting full-frame area")
                CameraKey.KeyThermalRegionMetersureArea.createCamera(idx, lens).set(
                    DoubleRect(0.0, 0.0, 1.0, 1.0),
                    onSuccess = { Log.i(TAG_THERMAL, "Region area=full-frame OK") },
                    onFailure = { e -> Log.e(TAG_THERMAL, "set Region area failed: ${e.description()}") },
                )
            },
            onFailure = { e -> Log.e(TAG_THERMAL, "set MeasureMode failed: ${e.description()}") },
        )
    }

    private fun disarmThermalMeasurement() {
        thermalArmed = false
    }

    override fun setAutoSensingSwitchChecked(checked: Boolean) {
        findViewById<Switch>(R.id.sw_auto_sensing)?.isChecked = checked
    }

    override fun readSettingsJson(): String = settingsSnapshot().toJson()

    private fun settingsSnapshot(): SettingsSnapshot {
        val product = productTypeKey.get(ProductType.UNKNOWN)
        return SettingsSnapshot(
            droneName = droneName,
            aircraftSerialNumber = droneSerialNumber,
            mavlinkSystemId = currentMavlinkSystemId(),
            streamingMode = settings.getStreamingMode().prefValue,
            webrtcResolution = settings.getWebRTCResolutionPreset().prefValue,
            webrtcFps = settings.getWebRTCFps(),
            detectionSource = settings.getDetectionSource().prefValue,
            detectionsEnabled = isDetectionActiveForUi(),
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

    override fun setDroneName(name: String): Boolean {
        if (!settings.setDroneName(name)) return false
        val trimmed = name.trim()
        droneName = trimmed
        LyrebirdFlightLogger.setDroneName(trimmed)
        mainHandler.post { updateDroneNameDisplay() }
        Log.i(TAG, "Drone name set to: $trimmed")
        return true
    }

    private fun setAutomaticDroneName() {
        sharedPreferences.edit().putBoolean(LyrebirdSettings.PREF_DRONE_NAME_USER_SET, false).apply()
        applyAutomaticDroneName()
    }

    override fun setMavlinkSystemId(value: Int): Boolean {
        if (value != MavlinkSystemId.AUTO && !MavlinkSystemId.isManual(value)) return false
        val current =
            prefIntOrDefault(
                MavlinkEndpointConfig.PREF_SYSTEM_ID,
                MavlinkEndpointConfig.DEFAULT_SYSTEM_ID,
            )
        if (!settings.setMavlinkSystemId(value)) return false
        if (current != value) restartMavlinkEndpoint()
        mainHandler.post { updateDroneNameDisplay() }
        Log.i(
            TAG,
            "MAVLink vehicle ID set to ${if (value == MavlinkSystemId.AUTO) "automatic" else value}",
        )
        return true
    }

    override fun setVideoSource(value: String): Boolean {
        // The phone-camera and mock-MP4 sources are gone, so the drone camera is all this can
        // be. Accepting the same value a client already had keeps the existing surface working;
        // anything else is refused rather than silently ignored.
        if (!value.equals(VIDEO_SOURCE_LABEL, ignoreCase = true)) return false
        return true
    }

    override fun setWebRtcResolution(value: String): Boolean {
        if (!settings.setWebRtcResolution(value)) return false
        mainHandler.post { webRTCStreamer?.changeMediaOptions(settings.buildWebRTCOptions()) }
        return true
    }

    override fun setWebRtcFps(value: Int): Boolean {
        if (!settings.setWebRtcFps(value)) return false
        mainHandler.post { webRTCStreamer?.changeMediaOptions(settings.buildWebRTCOptions()) }
        return true
    }

    override fun setDetectionSource(value: String): Boolean {
        val source = DetectionSource.entries.firstOrNull { it.prefValue.equals(value, ignoreCase = true) } ?: return false
        mainHandler.post { setDetectionSource(source) }
        return true
    }

    override fun setEdgeConfidence(threshold: Float): Boolean {
        if (!settings.setEdgeConfidence(threshold)) return false
        telemetryCoordinator.edgeConfidenceThreshold = threshold
        return true
    }

    override fun setMediamtxServer(value: String): Boolean {
        if (!settings.setMediamtxServer(value)) return false
        val trimmed = value.trim()
        Log.i(TAG, "Mediamtx server set to: ${if (trimmed.isEmpty()) "auto (client IP)" else trimmed}")
        return true
    }

    override fun readThermalMaxTempNow(): Double? {
        // Make sure the pipeline is armed even if capture is the very first thermal action.
        armThermalMeasurement()
        return runCatching {
            val idx = thermalCameraIndex()
            val lens = CameraLensType.CAMERA_LENS_THERMAL
            val globalMax = CameraKey.KeyThermalGlobalMaxTemperature.createCamera(idx, lens).get()
            val regionMax =
                CameraKey.KeyThermalRegionMetersureTemperature
                    .createCamera(
                        idx,
                        lens,
                    ).get()
                    ?.maxAreaTemperature
            val maxTemp = globalMax ?: regionMax
            Log.i(TAG_THERMAL, "[capture read] idx=$idx globalMax=$globalMax regionMax=$regionMax -> $maxTemp")
            maxTemp
        }.onFailure { Log.e(TAG_THERMAL, "[capture read] error: ${it.message}", it) }.getOrNull()
    }

    override fun readLrfMeasurement(): LrfMeasurement {
        val info = Payload.takeFreshLrfReading()
        val state = info?.laserMeasureState
        val locked = state == LaserMeasureState.NORMAL
        val target =
            if (locked) {
                info
                    ?.location3D
                    ?.takeIf { it.latitude != 0.0 || it.longitude != 0.0 || it.altitude != 0.0 }
                    ?.let { GeoPoint3D(it.latitude, it.longitude, it.altitude) }
            } else {
                null
            }
        return LrfMeasurement(
            distanceMeters = if (locked) info?.distance else null,
            state = state?.name,
            target = target,
        )
    }

    override fun setLrfTarget(target: GeoPoint3D?) {
        lrfTargetLocation = target?.let { LocationCoordinate3D(it.latitudeDeg, it.longitudeDeg, it.altitudeM) }
    }

    override fun hasThermalCamera(): Boolean =
        runCatching {
            // The same key the temperature read uses: it resolves only when a thermal lens is
            // actually present, so this is the honest "would Temp/Thermal do anything" probe.
            CameraKey.KeyThermalGlobalMaxTemperature
                .createCamera(thermalCameraIndex(), CameraLensType.CAMERA_LENS_THERMAL)
                .get() != null
        }.getOrDefault(false)
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
                // avoid repeating connect/disconnect work for a no-op notification.
                if (isConnected != aircraftConnected) {
                    applyAircraftConnectionState(isConnected)
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
            if (::mediaVM.isInitialized) Payload.warmUpMedia(mediaVM)
            // NOTE: the PORT_3 frame detector is armed from applyDetectedDroneProfile (once the
            // product resolves to M400 and PORT_3 is actually streaming), NOT here — at the connect
            // edge the product is still UNRECOGNIZED and PORT_3 has no stream yet.
            // Arm the thermal radiometric pipeline once the product type + PORT_3 payload have
            // had time to come up, so the on-demand read at capture time is warm. This is a
            // one-shot setup (enable temp data + region metering), not a continuous stream.
            mainHandler.postDelayed({ armThermalMeasurement() }, 8000)
        } else {
            Payload.resetMediaWarmup()
            // Reset for the next connection so a reconnect (or a different drone) rebinds again.
            unregisterMainCamFrameDetector()
            gimbalKeysReboundForM400 = false
            disarmThermalMeasurement()
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
                    val serverIp = lastClientIp ?: NetworkUtils.getDeviceIpAddress() ?: "127.0.0.1"
                    val rtmpUrl = getRtmpUrl(serverIp)
                    "RTMP ${if (liveStreamVM.isStreaming()) "running" else "idle"} url $rtmpUrl $lastNativeStreamStatus"
                }
                StreamingMode.RTSP -> {
                    val port = settings.getRtspPort()
                    val user = settings.getRtspUsername()
                    val userPrefix = if (user.isNotEmpty()) "$user@" else ""
                    "RTSP ${if (liveStreamVM.isStreaming()) "running" else "idle"} " +
                        "${userPrefix}port $port path $DJI_RTSP_STREAM_PATH $lastNativeStreamStatus"
                }
                StreamingMode.AGORA -> {
                    val channel = settings.getAgoraChannel().ifBlank { "-" }
                    "AGORA ${if (liveStreamVM.isStreaming()) "running" else "idle"} ch $channel $lastNativeStreamStatus"
                }
                StreamingMode.GB28181 -> {
                    val server = "${settings.getGbServerIp()}:${settings.getGbServerPort()}"
                    "GB28181 ${if (liveStreamVM.isStreaming()) "running" else "idle"} server $server $lastNativeStreamStatus"
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

    /**
     * Hold WIFI_MODE_FULL_LOW_LATENCY while publishing. Flight 1 showed dips with zero RTP loss
     * and a clean phone-side pipeline, consistent with radio power-save stalls between the
     * encoder and the air. Idempotent: repeated streaming restarts keep one lock held.
     */
    private fun acquireLowLatencyWifiLock() {
        if (lowLatencyWifiLock?.isHeld == true) return
        runCatching {
            lowLatencyWifiLock =
                wifiManager?.createWifiLock(
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY,
                    "LyrebirdStreamingWifiLock",
                )
            lowLatencyWifiLock?.acquire()
            Log.i(TAG, "Low-latency Wi-Fi lock acquired for streaming")
        }.onFailure { error ->
            Log.w(TAG, "Could not acquire low-latency Wi-Fi lock: ${error.message}")
        }
    }

    /**
     * Start WHIP publishing on the existing WebRTC streamer.
     * Called automatically when the bridge connects to the telemetry server.
     */
    private fun startActiveStreaming(clientIp: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { startActiveStreaming(clientIp) }
            return
        }
        acquireLowLatencyWifiLock()
        val mode = settings.getStreamingMode()
        Log.i(TAG, "Starting active streaming in mode: ${mode.menuLabel}")

        webRTCStreamer?.stop()

        val startSelectedMode = {
            lastNativeStreamStatus = "starting"
            updateStreamingFooter()

            when (mode) {
                StreamingMode.WEBRTC -> {
                    val whipUrl = buildWhipUrl(clientIp)
                    lastWhipUrl = whipUrl
                    val streamer = webRTCStreamer
                    if (streamer == null) {
                        Log.w(TAG, "Cannot start WHIP - WebRTCStreamer not initialized yet")
                        lastNativeStreamStatus = "error: streamer not initialized"
                        updateStreamingFooter()
                    } else {
                        runCatching {
                            streamer.startWhip(whipUrl, whipUrlProvider = { buildWhipUrl(clientIp) })
                            Log.i(TAG, "WHIP publishing started: $whipUrl")
                            lastNativeStreamStatus = "running"
                            updateStreamingFooter()
                        }.onFailure { error ->
                            Log.e(TAG, "Failed to start WHIP publishing: ${error.message}", error)
                            lastNativeStreamStatus = "error: ${error.message ?: "start failed"}"
                            updateStreamingFooter()
                        }
                    }
                }
                StreamingMode.RTMP -> {
                    val rtmpUrl = getRtmpUrl(clientIp)

                    fun fallbackRtmpUrl(url: String): String? {
                        val match = Regex("^rtmp://([^/]+)/([^/]+)$").matchEntire(url.trim()) ?: return null
                        val hostPort = match.groupValues[1]
                        val stream = match.groupValues[2]
                        return "rtmp://$hostPort/live/$stream"
                    }

                    fun startRtmp(
                        url: String,
                        fallbackAttempt: Boolean = false,
                    ) {
                        Log.i(TAG, "Starting native DJI RTMP streaming to: $url")
                        liveStreamVM.setRTMPConfig(url)
                        liveStreamVM.startStream(
                            object : CommonCallbacks.CompletionCallback {
                                override fun onSuccess() {
                                    if (url != rtmpUrl) {
                                        settings.setRtmpUrl(url)
                                    }
                                    Log.i(TAG, "Native DJI RTMP streaming started successfully")
                                    lastNativeStreamStatus = "running"
                                    mainHandler.post { updateStreamingFooter() }
                                    showStreamToast("RTMP stream started")
                                }

                                override fun onFailure(error: IDJIError) {
                                    val message = error.description()
                                    if (!fallbackAttempt) {
                                        val fallback = fallbackRtmpUrl(url)
                                        if (fallback != null && fallback != url) {
                                            Log.w(TAG, "RTMP failed for $url ($message), retrying with $fallback")
                                            lastNativeStreamStatus = "retrying with $fallback"
                                            mainHandler.post { updateStreamingFooter() }
                                            startRtmp(fallback, true)
                                            return
                                        }
                                    }
                                    Log.e(TAG, "Failed to start native DJI RTMP stream: $message")
                                    lastNativeStreamStatus = "error: $message"
                                    mainHandler.post { updateStreamingFooter() }
                                    showStreamToast("RTMP failed: $message")
                                }
                            },
                        )
                    }

                    startRtmp(rtmpUrl)
                }
                StreamingMode.RTSP -> {
                    val requestedPort = settings.getRtspPort()
                    val port = resolveRtspPortForStart()
                    if (port != requestedPort) {
                        settings.setRtspPort(port)
                        Log.w(TAG, "RTSP port $requestedPort is in use, switching to $port")
                        rebuildTelemetryCache()
                        updateStreamingFooter()
                        showStreamToast("RTSP port $requestedPort busy, switched to $port")
                    }
                    val user = settings.getRtspUsername()
                    val pwd = settings.getRtspPassword()
                    Log.i(TAG, "Starting native DJI RTSP server on port $port")
                    liveStreamVM.setRTSPConfig(user, pwd, port)
                    liveStreamVM.startStream(
                        object : CommonCallbacks.CompletionCallback {
                            override fun onSuccess() {
                                Log.i(TAG, "Native DJI RTSP server started successfully")
                                lastNativeStreamStatus = "running"
                                mainHandler.post { updateStreamingFooter() }
                                showStreamToast("RTSP server started on port $port")
                            }

                            override fun onFailure(error: IDJIError) {
                                Log.e(TAG, "Failed to start native DJI RTSP: ${error.description()}")
                                lastNativeStreamStatus = "error: ${error.description()}"
                                mainHandler.post { updateStreamingFooter() }
                                showStreamToast("RTSP failed: ${error.description()}")
                            }
                        },
                    )
                }
                StreamingMode.AGORA -> {
                    val channel = settings.getAgoraChannel()
                    val token = settings.getAgoraToken()
                    val uid = settings.getAgoraUid()
                    Log.i(TAG, "Starting Agora streaming on channel $channel")
                    liveStreamVM.setAgoraConfig(channel, token, uid)
                    liveStreamVM.startStream(
                        object : CommonCallbacks.CompletionCallback {
                            override fun onSuccess() {
                                Log.i(TAG, "Agora streaming started successfully")
                                lastNativeStreamStatus = "running"
                                mainHandler.post { updateStreamingFooter() }
                                showStreamToast("Agora stream started")
                            }

                            override fun onFailure(error: IDJIError) {
                                Log.e(TAG, "Failed to start Agora: ${error.description()}")
                                lastNativeStreamStatus = "error: ${error.description()}"
                                mainHandler.post { updateStreamingFooter() }
                                showStreamToast("Agora failed: ${error.description()}")
                            }
                        },
                    )
                }
                StreamingMode.GB28181 -> {
                    val ip = settings.getGbServerIp()
                    val port = settings.getGbServerPort()
                    val serverId = settings.getGbServerId()
                    val agentId = settings.getGbAgentId()
                    val channel = settings.getGbChannel()
                    val localPort = settings.getGbLocalPort()
                    val pwd = settings.getGbPassword()
                    Log.i(TAG, "Starting GB28181 streaming to $ip:$port")
                    liveStreamVM.setGB28181(ip, port, serverId, agentId, channel, localPort, pwd)
                    liveStreamVM.startStream(
                        object : CommonCallbacks.CompletionCallback {
                            override fun onSuccess() {
                                Log.i(TAG, "GB28181 streaming started successfully")
                                lastNativeStreamStatus = "running"
                                mainHandler.post { updateStreamingFooter() }
                                showStreamToast("GB28181 stream started")
                            }

                            override fun onFailure(error: IDJIError) {
                                Log.e(TAG, "Failed to start GB28181: ${error.description()}")
                                lastNativeStreamStatus = "error: ${error.description()}"
                                mainHandler.post { updateStreamingFooter() }
                                showStreamToast("GB28181 failed: ${error.description()}")
                            }
                        },
                    )
                }
            }
        }

        if (liveStreamVM.isStreaming()) {
            Log.i(TAG, "Stopping currently active native DJI livestream before restart")
            liveStreamVM.stopStream(
                object : CommonCallbacks.CompletionCallback {
                    override fun onSuccess() {
                        Log.i(TAG, "Native DJI livestream stopped successfully")
                        lastNativeStreamStatus = "stopped"
                        mainHandler.post { updateStreamingFooter() }
                        startSelectedMode()
                    }

                    override fun onFailure(error: IDJIError) {
                        Log.w(TAG, "Failed to stop native DJI livestream before restart: ${error.description()}")
                        lastNativeStreamStatus = "stop failed: ${error.description()}"
                        mainHandler.post { updateStreamingFooter() }
                        startSelectedMode()
                    }
                },
            )
        } else {
            startSelectedMode()
        }
    }

    private fun stopActiveStreaming() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { stopActiveStreaming() }
            return
        }
        Log.i(TAG, "Stopping active streaming...")
        webRTCStreamer?.stop()
        lastNativeStreamStatus = "stopping"
        mainHandler.post { updateStreamingFooter() }
        if (liveStreamVM.isStreaming()) {
            liveStreamVM.stopStream(
                object : CommonCallbacks.CompletionCallback {
                    override fun onSuccess() {
                        Log.i(TAG, "Native DJI livestream stopped successfully")
                        lastNativeStreamStatus = "stopped"
                        mainHandler.post { updateStreamingFooter() }
                    }

                    override fun onFailure(error: IDJIError) {
                        Log.w(TAG, "Failed to stop native DJI livestream: ${error.description()}")
                        lastNativeStreamStatus = "stop failed: ${error.description()}"
                        mainHandler.post { updateStreamingFooter() }
                    }
                },
            )
        } else {
            lastNativeStreamStatus = "stopped"
            mainHandler.post { updateStreamingFooter() }
        }
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
        val publisherHealthy =
            webRTCStreamer?.isRunning() == true &&
                webRTCStreamer?.isPublishing() == true
        if (lastClientIp == clientIp && lastWhipUrl != null) {
            if (publisherHealthy) return
            Log.w(TAG, "Restarting stale WHIP publisher for $clientIp")
        }
        // Guard against stream hijacking: a healthy publisher must not be retargeted just
        // because a different client connected to the telemetry port. Field incidents:
        // phones probing each other's telemetry port made the app repoint WHIP at another
        // phone (which runs no MediaMTX), killing video until an app restart. Only retarget
        // when the current publisher is unhealthy, or when no client was ever recorded.
        if (lastClientIp != null && lastClientIp != clientIp && publisherHealthy) {
            Log.w(
                TAG,
                "Ignoring telemetry client $clientIp while healthy publisher targets $lastClientIp",
            )
            return
        }
        Log.i(TAG, "Starting active streaming for $clientIp")
        lastClientIp = clientIp
        rebuildTelemetryCache()
        startActiveStreaming(clientIp)
    }

    override fun restartActiveStreaming() {
        val lastIp =
            lastClientIp ?: lastWhipUrl?.let { runCatching { Uri.parse(it).host }.getOrNull() } ?: NetworkUtils
                .getDeviceIpAddress() ?: "127.0.0.1"
        startActiveStreaming(lastIp)
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
            DetectionSource.YOLO_ON_PHONE -> edgeDetectionController != null
        }

    private fun detectionMenuLabel(): String =
        if (isDetectionActiveForUi()) {
            "Detections On (${settings.getDetectionSource().menuLabel})"
        } else {
            "Detections Off"
        }

    private fun setDetectionSource(source: DetectionSource) {
        if (source == DetectionSource.DJI_ONBOARD && !aircraftConnected) {
            Toast.makeText(this, "DJI onboard detections need a connected drone", Toast.LENGTH_SHORT).show()
            return
        }

        stopAutoSensing()
        stopEdgeDetection()

        sharedPreferences
            .edit()
            .putString(LyrebirdSettings.PREF_DETECTION_SOURCE, source.prefValue)
            .putBoolean(
                LyrebirdSettings.PREF_EDGE_DETECTION_ENABLED,
                settings.isDetectionsEnabled() && source == DetectionSource.YOLO_ON_PHONE,
            ).apply()

        findViewById<Switch>(R.id.sw_auto_sensing)?.isChecked = settings.isDetectionsEnabled() &&
            source == DetectionSource.DJI_ONBOARD
        findViewById<Switch>(R.id.sw_edge_detection)?.isChecked = settings.isDetectionsEnabled() &&
            source == DetectionSource.YOLO_ON_PHONE

        when (settings.activeDetectionSource()) {
            DetectionSource.NONE -> updateEdgeMetricsView(EdgeDetectionMetrics(status = "off"))
            DetectionSource.DJI_ONBOARD -> startAutoSensing()
            DetectionSource.YOLO_ON_PHONE -> startEdgeDetection()
        }

        updateDetectionTelemetryState()
        rebuildTelemetryCache()
        invalidateOptionsMenu()
    }

    override fun setDetectionsEnabled(enabled: Boolean) {
        if (enabled && settings.getDetectionSource() == DetectionSource.DJI_ONBOARD && !aircraftConnected) {
            Toast.makeText(this, "DJI onboard detections need a connected drone", Toast.LENGTH_SHORT).show()
            return
        }

        stopAutoSensing()
        stopEdgeDetection()

        sharedPreferences
            .edit()
            .putBoolean(LyrebirdSettings.PREF_DETECTIONS_ENABLED, enabled)
            .putBoolean(
                LyrebirdSettings.PREF_EDGE_DETECTION_ENABLED,
                enabled && settings.getDetectionSource() == DetectionSource.YOLO_ON_PHONE,
            ).apply()

        findViewById<Switch>(R.id.sw_auto_sensing)?.isChecked = enabled &&
            settings.getDetectionSource() == DetectionSource.DJI_ONBOARD
        findViewById<Switch>(R.id.sw_edge_detection)?.isChecked = enabled &&
            settings.getDetectionSource() == DetectionSource.YOLO_ON_PHONE

        when (settings.activeDetectionSource()) {
            DetectionSource.NONE -> {
                updateEdgeMetricsView(EdgeDetectionMetrics(status = "off"))
                Toast.makeText(this, "Detections disabled", Toast.LENGTH_SHORT).show()
            }
            DetectionSource.DJI_ONBOARD -> startAutoSensing()
            DetectionSource.YOLO_ON_PHONE -> startEdgeDetection()
        }

        updateDetectionTelemetryState()
        rebuildTelemetryCache()
        invalidateOptionsMenu()
    }

    private fun updateDetectionTelemetryState() {
        val source = settings.activeDetectionSource()
        val selectedSource = settings.getDetectionSource()

        TelemetryProvider.currentDetectionSource = source.prefValue
        TelemetryProvider.currentDetectionActive =
            when (source) {
                DetectionSource.NONE -> false
                DetectionSource.DJI_ONBOARD -> isAutoSensingActive
                DetectionSource.YOLO_ON_PHONE -> edgeDetectionController != null
            }
        TelemetryProvider.currentDetectionModel =
            when (source) {
                DetectionSource.YOLO_ON_PHONE -> sharedPreferences.getString(LyrebirdSettings.PREF_EDGE_MODEL_NAME, null)
                else -> null
            }
        TelemetryProvider.currentDetectionThreshold =
            when (source) {
                DetectionSource.YOLO_ON_PHONE -> settings.getEdgeConfidenceThreshold()
                else -> null
            }

        telemetryCoordinator.isDetectionsEnabled = settings.isDetectionsEnabled()
        telemetryCoordinator.detectionSource = source.prefValue
        telemetryCoordinator.selectedDetectionSource = selectedSource.prefValue
        telemetryCoordinator.detectionMenuLabel = selectedSource.menuLabel
        telemetryCoordinator.isAutoSensingActive = isAutoSensingActive
        telemetryCoordinator.edgeDetectionActive = edgeDetectionController != null
        telemetryCoordinator.edgeModelName = sharedPreferences.getString(LyrebirdSettings.PREF_EDGE_MODEL_NAME, null)
        telemetryCoordinator.edgeLabelsName = sharedPreferences.getString(LyrebirdSettings.PREF_EDGE_LABELS_NAME, null)
        telemetryCoordinator.edgeConfidenceThreshold = settings.getEdgeConfidenceThreshold()
        telemetryCoordinator.detectedTargetsJson = DetectedTarget.listToJsonArray(currentDetectedTargets).toString()
        telemetryCoordinator.detectedTargetsSize = currentDetectedTargets.size
    }

    private fun applyDetectedTargets(targets: List<DetectedTarget>) {
        currentDetectedTargets = targets
        TelemetryProvider.currentDetectedTargets = targets
        updateDetectionTelemetryState()
        rebuildTelemetryCache()
        mainHandler.post { detectionOverlay?.setTargets(targets) }
    }

    override fun startAutoSensing() {
        if (isAutoSensingActive) return
        runCatching {
            val manager = IntelligentFlightManager.getInstance()
            if (!isAutoSensingListenerRegistered) {
                manager.addAutoSensingInfoListener(autoSensingInfoListener)
                isAutoSensingListenerRegistered = true
            }
            manager.startAutoSensing(
                object : CommonCallbacks.CompletionCallback {
                    override fun onSuccess() {
                        isAutoSensingActive = true
                        updateDetectionTelemetryState()
                        rebuildTelemetryCache()
                        Log.i(TAG, "AutoSensing started")
                    }

                    override fun onFailure(error: IDJIError) {
                        isAutoSensingActive = false
                        removeAutoSensingListener()
                        updateDetectionTelemetryState()
                        rebuildTelemetryCache()
                        Log.e(TAG, "AutoSensing start failed: ${error.description()}")
                    }
                },
            )
        }.onFailure { error ->
            updateDetectionTelemetryState()
            rebuildTelemetryCache()
            Log.e(TAG, "AutoSensing start exception: ${error.message}", error)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    override fun stopAutoSensing() {
        clearAutoSensingState()
        if (!isAutoSensingActive) {
            removeAutoSensingListener()
            return
        }
        try {
            IntelligentFlightManager.getInstance().stopAutoSensing(
                object : CommonCallbacks.CompletionCallback {
                    override fun onSuccess() {
                        Log.i(TAG, "AutoSensing stopped")
                    }

                    override fun onFailure(error: IDJIError) {
                        Log.e(TAG, "AutoSensing stop failed: ${error.description()}")
                    }
                },
            )
        } catch (error: Throwable) {
            Log.e(TAG, "AutoSensing stop exception: ${error.message}", error)
        } finally {
            isAutoSensingActive = false
            removeAutoSensingListener()
            updateDetectionTelemetryState()
            rebuildTelemetryCache()
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun removeAutoSensingListener() {
        if (!isAutoSensingListenerRegistered) return
        try {
            IntelligentFlightManager.getInstance().removeAutoSensingInfoListener(autoSensingInfoListener)
        } catch (error: Throwable) {
            Log.e(TAG, "AutoSensing listener removal exception: ${error.message}", error)
        } finally {
            isAutoSensingListenerRegistered = false
        }
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
        if (edgeDetectionController != null) return

        val startCheck = edgeDetectionStartCheck(getEdgeModelUri(), webRTCStreamer)
        if (startCheck !is EdgeDetectionStartCheck.Ready) {
            handleEdgeDetectionStartFailure(startCheck)
            return
        }

        clearAutoSensingState()

        val controller = createEdgeDetectionController(startCheck)
        edgeDetectionController = controller

        configureDetectionOverlay()
        controller.start()
        updateDetectionTelemetryState()
        rebuildTelemetryCache()

        attachEdgeDetectionSource(controller)
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

    private fun createEdgeDetectionController(startCheck: EdgeDetectionStartCheck.Ready): EdgeDetectionController =
        EdgeDetectionController(
            context = applicationContext,
            config =
                EdgeDetectionConfig(
                    modelUri = startCheck.modelUri,
                    labels = getEdgeLabels(),
                    sourceLabel = VIDEO_SOURCE_LABEL,
                    confidenceThreshold = settings.getEdgeConfidenceThreshold(),
                ),
            onTargets = { targets ->
                if (settings.activeDetectionSource() == DetectionSource.YOLO_ON_PHONE) {
                    applyDetectedTargets(targets)
                }
            },
            onMetrics = { metrics ->
                lastEdgeMetrics = metrics
                mainHandler.post { updateEdgeMetricsView(metrics) }
            },
        )

    private fun configureDetectionOverlay() {
        detectionOverlay?.setVideoScaleMode(DetectionOverlayView.VideoScaleMode.CENTER_INSIDE)
        detectionOverlay?.setSourceFrameSize(
            lastWebRTCMetrics.sourceWidth.takeIf { it > 0 } ?: 16,
            lastWebRTCMetrics.sourceHeight.takeIf { it > 0 } ?: 9,
        )
    }

    private fun attachEdgeDetectionSource(controller: EdgeDetectionController) {
        webRTCStreamer?.setEdgeDetectionFrameListener(controller)
    }

    private fun showEdgeDetectionEnabledMessage() {
        Toast.makeText(this, "Edge detection enabled", Toast.LENGTH_SHORT).show()
        Log.i(TAG, "Edge detection enabled")
    }

    private fun stopEdgeDetection() {
        val controller = edgeDetectionController ?: return
        webRTCStreamer?.setEdgeDetectionFrameListener(null)
        controller.dispose()
        edgeDetectionController = null
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
        // checked here rather than folded into the enum's own IDLE label. Sized up and in red
        // rather than sharing the operational status colors: offline needs to read as an alarm,
        // not an operational status.
        if (!aircraftConnected && !aircraftTelemetry.isReadyToTakeoff()) {
            statusTv.text = "OFFLINE"
            statusTv.setTextColor(0xFFFF1744.toInt())
            statusTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, DRONE_STATUS_ALERT_TEXT_SIZE_SP)
            return
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
        if (ObstacleGuard.isLatched) {
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
     * an uninstall. No-op without the optional storage permission.
     */
    private fun startSettingsBackup() {
        settingsBackupListener =
            SharedPreferences.OnSharedPreferenceChangeListener { prefs, _ ->
                mainHandler.removeCallbacks(settingsBackupTask)
                // Settings arrive in bursts while a dialog is being filled in; coalesce them.
                mainHandler.postDelayed(settingsBackupTask, SETTINGS_BACKUP_DEBOUNCE_MS)
            }
        sharedPreferences.registerOnSharedPreferenceChangeListener(settingsBackupListener)
        mainHandler.postDelayed(settingsBackupTask, SETTINGS_BACKUP_DEBOUNCE_MS)
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
        val mavlinkUp = mavlinkEndpoint != null
        val httpUp = session?.status?.httpPort != null
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
        aircraftTelemetry.startFlightStateUpdates(
            object : V5AircraftTelemetrySource.FlightStateObserver {
                override fun onFlyingChanged(flying: Boolean) {
                    val wasFlying = DroneController.isAirborne
                    DroneController.isAirborne = flying
                    mainHandler.post { updateDroneStatusView(DroneController.droneStatus) }
                    // Flight log session lifecycle: open a new file on takeoff, close it on landing.
                    if (!wasFlying && flying) {
                        LyrebirdFlightLogger.startSession()
                        // Start AutoSensing on takeoff if DJI onboard detections are selected.
                        if (settings.activeDetectionSource() == DetectionSource.DJI_ONBOARD && !isAutoSensingActive) {
                            startAutoSensing()
                        }
                    } else if (wasFlying && !flying) {
                        // 10-second grace period before closing in case of brief mid-air telemetry glitch.
                        mainHandler.postDelayed({
                            if (!DroneController.isAirborne) {
                                LyrebirdFlightLogger.endSession("landed")
                                // Sync DJI TXT records — idempotent, safe to run immediately.
                                // Any file the SDK hasn't finalised yet will be picked up next launch.
                                syncDjiFlightLogsInBackground()
                            }
                        }, 10_000L)
                    }
                }

                override fun onFlightModeChanged(mode: FlightMode) {
                    mainHandler.post {
                        cachedFlightMode = mode
                        reevaluateAircraftIdle()
                    }
                    // Detect RTH triggered from the RC controller, not from our server HTTP request.
                    if (mode == FlightMode.GO_HOME &&
                        DroneController.droneStatus != DroneController.DroneStatus.RETURNING_HOME
                    ) {
                        mainHandler.post { DroneController.activateManualOverride() }
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
     * Detects the aircraft sitting idle on the ground (motors off, connected, not airborne)
     * and shows a notice telling the pilot to wake it with the manual both-sticks-down-and-
     * inwards gesture (there is no reliable app-side motor-start on every airframe). The overlay
     * is delayed by [idleDetectDebounceMs] so transient states never flash it.
     */
    private fun setupAircraftIdleMonitor() {
        val readings = aircraftTelemetry.readState().readings
        cachedFlightMode = FlightMode.entries.firstOrNull { it.name == readings.flightMode } ?: FlightMode.UNKNOWN
        cachedSatelliteCount = readings.satelliteCount
        reevaluateAircraftIdle()
    }

    private fun isAircraftIdle(): Boolean =
        aircraftConnected &&
            !DroneController.isAirborne &&
            cachedFlightMode == FlightMode.UNKNOWN &&
            cachedSatelliteCount <= 0

    private fun idleStateSummary(): String =
        "airborne=${DroneController.isAirborne} connected=$aircraftConnected " +
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
        aircraftTelemetry.startTelemetry(
            object : V5AircraftTelemetrySource.Observer {
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

    private fun loadDroneName() {
        val storedName = sharedPreferences.getString(LyrebirdSettings.PREF_DRONE_NAME, "")?.trim().orEmpty()
        val explicit = sharedPreferences.getBoolean(LyrebirdSettings.PREF_DRONE_NAME_USER_SET, false)
        droneName = if (explicit) storedName else defaultDroneName()
        sharedPreferences
            .edit()
            .putString(LyrebirdSettings.PREF_DRONE_NAME, droneName)
            .putBoolean(LyrebirdSettings.PREF_DRONE_NAME_USER_SET, explicit)
            .apply()
        Log.i(TAG, "Loaded ${if (explicit) "user" else "automatic"} drone name: $droneName")
        LyrebirdFlightLogger.setDroneName(droneName)
    }

    private fun defaultDroneName(): String {
        val suffix =
            droneSerialNumber
                .trim()
                .takeLast(8)
                .ifEmpty { "unknown" }
                .replace(Regex("[^a-zA-Z0-9_]"), "_")
                .lowercase()
        return "lb_$suffix"
    }

    private fun applyAutomaticDroneName() {
        if (sharedPreferences.getBoolean(LyrebirdSettings.PREF_DRONE_NAME_USER_SET, false)) return
        val generated = defaultDroneName()
        if (droneName == generated) return
        droneName = generated
        sharedPreferences.edit().putString(LyrebirdSettings.PREF_DRONE_NAME, generated).apply()
        LyrebirdFlightLogger.setDroneName(generated)
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

    /**
     * Switch the camera between stills and video, from a plan's MAV_CMD_SET_CAMERA_MODE.
     *
     * MAV_CAMERA_MODE's survey mode is stills flown on a grid, which is a property of the flight
     * rather than of the camera, so DJI has nothing separate to put it in and it maps to stills.
     */
    private fun setCameraMode(mavCameraMode: Int) {
        val mode =
            when (mavCameraMode) {
                Mav.CAMERA_MODE_VIDEO -> CameraMode.VIDEO_NORMAL
                Mav.CAMERA_MODE_IMAGE, Mav.CAMERA_MODE_IMAGE_SURVEY -> CameraMode.PHOTO_NORMAL
                else -> {
                    Log.w(TAG, "Unknown MAV_CAMERA_MODE $mavCameraMode; camera left as it is")
                    return
                }
            }
        if (KeyManager.getInstance().getValue(cameraModeKey) == mode) return
        KeyManager.getInstance().setValue(
            cameraModeKey,
            mode,
            object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() {
                    Log.i(TAG, "Camera mode set to $mode by plan")
                }

                override fun onFailure(error: IDJIError) {
                    Log.w(TAG, "Plan could not set camera mode: ${error.description()}")
                }
            },
        )
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

    private fun buildWhipUrl(clientIp: String): String {
        val safeDroneName =
            droneName.trim().ifEmpty {
                LyrebirdSettings.DEFAULT_DRONE_NAME
            }

        val configuredServer =
            sharedPreferences
                .getString(LyrebirdSettings.PREF_MEDIAMTX_SERVER, "")
                ?.trim()
                .orEmpty()

        val hostAndPort =
            if (configuredServer.isEmpty()) {
                "$clientIp:$MEDIAMTX_WHIP_PORT"
            } else {
                var normalized =
                    configuredServer
                        .removePrefix("http://")
                        .removePrefix("https://")
                        .trimEnd('/')
                if (!normalized.contains(':')) {
                    normalized = "$normalized:$MEDIAMTX_WHIP_PORT"
                }
                normalized
            }

        return "http://$hostAndPort/$safeDroneName/whip"
    }

    private fun startLocationUpdates() {
        if (!deviceStatusSource.startLocationUpdates()) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1)
        }
    }

    private fun startServers() {
        val deviceIp = NetworkUtils.getDeviceIpAddress()

        // The lease, the servers, then the announcement — the order is the point. The aircraft
        // used to register mDNS and open its discovery sockets before finding out whether the
        // command and telemetry ports could even be bound, so a failed bind left a standing
        // advertisement for a service nobody was listening on.
        session =
            LyrebirdSession(
                lease = SessionLease(),
                http = SimpleHttpServer(HTTP_PORT, this, mavlinkCommandSink),
                telemetry = buildTelemetryServer(),
                advertiser = DiscoveryAdvertiser(discoveryManager) { droneSerialNumber },
                httpPort = HTTP_PORT,
                telemetryPort = TELEMETRY_PORT,
            )
        val sessionStatus = session?.start()
        Log.i(TAG, "Session on $deviceIp: ${sessionStatus?.summary()}")
        sessionStatus?.takeIf { it.blockedByAnotherSession }?.let {
            ToastUtils.showLongToast(it.summary())
        }

        // Fleet mesh. Discovery answers a ground station asking "who is out there"; this is the
        // same question asked between aircraft, which nothing on the device could answer before.
        startFleetMesh()

        // Obstacle guard. Opt-in, and silent unless it fires.
        startObstacleGuard()

        // Start the MAVLink 2 telemetry endpoint (no-op unless enabled by preference).
        startMavlinkEndpoint()

        // The endpoint has now either bound or logged why not, so this is the first point where
        // the status line reflects what actually came up.
        updateMavlinkHttpStatusView()

        // WebRTC video via WHIP — create the shared frame source/publisher.
        // WHIP publishing starts automatically when bridge connects to telemetry.
        runCatching {
            webRTCStreamer =
                WebRTCStreamer(
                    context = applicationContext,
                    cameraIndex = ComponentIndexType.LEFT_OR_MAIN,
                    droneName = droneName,
                    options = settings.buildWebRTCOptions(),
                )
            webRTCStreamer?.listener =
                object : WebRTCStreamer.WebRTCStreamerListener {
                    override fun onServerStarted(
                        ip: String,
                        port: Int,
                    ) {
                        Log.i(TAG, "WHIP publishing from $ip")
                        whipConsecutiveFailures = 0
                    }

                    override fun onServerStopped() {
                        Log.i(TAG, "WebRTC streamer stopped")
                    }

                    override fun onServerError(error: String) {
                        Log.e(TAG, "WebRTC error: $error")
                        whipConsecutiveFailures++
                        if (whipConsecutiveFailures >= WHIP_OVERRIDE_FAILURE_THRESHOLD) {
                            whipConsecutiveFailures = 0
                            val configuredServer =
                                sharedPreferences
                                    .getString(LyrebirdSettings.PREF_MEDIAMTX_SERVER, "")
                                    ?.trim()
                                    .orEmpty()
                            if (configuredServer.isNotEmpty()) {
                                Log.w(
                                    TAG,
                                    "WHIP failed $WHIP_OVERRIDE_FAILURE_THRESHOLD times in a row against " +
                                        "configured mediamtxServer '$configuredServer' -- clearing it so the " +
                                        "next reconnect falls back to the auto-detected client IP",
                                )
                                sharedPreferences.edit().remove(LyrebirdSettings.PREF_MEDIAMTX_SERVER).apply()
                            }
                        }
                    }

                    override fun onMetrics(metrics: WebRTCStreamMetrics) {
                        lastWebRTCMetrics = metrics
                        rebuildTelemetryCache()
                        mainHandler.post { updateWebRTCMetricsView(metrics) }
                    }
                }
            Log.i(TAG, "WebRTC streamer ready (starts on first telemetry client)")

            // If the telemetry callback already fired before streamer was ready, start now
            val pendingUrl = lastWhipUrl
            if (pendingUrl != null) {
                val pendingIp = runCatching { Uri.parse(pendingUrl).host }.getOrNull() ?: "127.0.0.1"
                Log.i(TAG, "Deferred streaming start: $pendingIp")
                mainHandler.post { startActiveStreaming(pendingIp) }
            }
        }.onFailure { error ->
            Log.e(TAG, "Error creating WebRTC streamer: ${error.message}", error)
        }
    }

    /**
     * The telemetry server, with the bridge-attached callback the session cannot know about.
     *
     * The first ground station to attach is what starts active streaming, so this wiring stays
     * here while the server's lifecycle belongs to the session.
     */
    private fun buildTelemetryServer(): TelemetryServer =
        TelemetryServer(TELEMETRY_PORT, ::getTelemetryJson, ::getGapTelemetryJson).apply {
            onFirstClientConnected = { clientIp ->
                Log.i(TAG, "First telemetry client from $clientIp — starting active streaming")
                mainHandler.post {
                    startStreamingForClient(clientIp)
                }
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

        disarmThermalMeasurement()

        deviceStatusSource.stop()

        try {
            // Stop AutoSensing
            stopAutoSensing()

            stopEdgeDetection()

            // Stop the session: it withdraws the advertisement, stops both servers, and gives
            // the lease back, in that order. Stopping the servers by hand here is what used to
            // leave the mDNS registration standing with nothing behind it.
            session?.stop()
            session = null
            mavlinkEndpoint?.stop()
            mavlinkEndpoint = null
            captureExecutor.shutdownNow()
            ftpExecutor.shutdownNow()
            mavlinkFtpServer?.shutdown()
            mavlinkFtpServer = null
            // Unregister the settings backup listener and stop its writer: the SharedPreferences
            // singleton otherwise keeps the listener (and the activity through it) alive, and the
            // executor would keep writing after destroy.
            settingsBackupListener?.let { sharedPreferences.unregisterOnSharedPreferenceChangeListener(it) }
            settingsBackupListener = null
            settingsBackupExecutor.shutdownNow()
            webRTCStreamer?.listener = null
            stopActiveStreaming()
            WebRTCPeerFactory.reset()
            // The fleet link holds a repeating main-thread callback that must not outlive the
            // activity. Its sockets are its own; the session's discovery sockets are already down.
            stopObstacleGuard()
            stopFleetMesh()
            webRTCStreamer = null

            // Release Multicast Lock
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
            }

            // Release the low-latency Wi-Fi lock held while WHIP publishing was active.
            if (lowLatencyWifiLock?.isHeld == true) {
                lowLatencyWifiLock?.release()
            }

            // Cancel key listeners
            aircraftTelemetry.stop()
            KeyManager.getInstance().cancelListen(this)

            // Detach the M400 main-camera first-frame detector if still registered
            unregisterMainCamFrameDetector()

            // Cancel H20T payload (LRF + thermal) key listeners
            Payload.destroy()

            // Release MediaVM (thermal capture) listeners and media manager
            if (::mediaVM.isInitialized) {
                mediaVM.destroy()
            }

            // Clean up DroneController listeners and resources
            DroneController.manualOverrideListener = null
            DroneController.droneStatusListener = null
            ControlAuthority.listener = null
            DroneController.destroy()

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
                        LyrebirdFlightLogger.setVehicleSerial(droneSerialNumber)
                        // The latch is per airframe, and this is the moment the airframe becomes
                        // known: read its latch now, so a takeover recorded for it is in force
                        // before its first command, and one recorded for another aircraft is left
                        // where it belongs.
                        ControlAuthority.restoreLatch()
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
                                    restartMavlinkEndpoint()
                                }
                                Log.i(TAG, "Applied per-drone settings profile for $droneSerialNumber")
                            }
                        }
                        applyAutomaticDroneName()
                        if (!configuredMavlinkSystemIdIsManual() && previousSystemId != currentMavlinkSystemId()) {
                            restartMavlinkEndpoint()
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

    /** The point the gimbal is tracking, or null when nothing is. */
    @Volatile private var roiTarget: LocationCoordinate3D? = null
    private var roiTrackingRunnable: Runnable? = null

    /** The gimbal mode before ROI tracking freed the yaw axis, restored when tracking stops. */
    @Volatile private var roiPreviousGimbalMode: GimbalMode? = null

    /**
     * Set when a ground station's ARM command was accepted. DJI has no arming state — motors
     * spin only when a takeoff actually runs — so the heartbeat otherwise never reports armed and
     * QGroundControl's arm wait times out with "vehicle rejected arming" while the aircraft is
     * already taking off. Cleared by a DISARM, and the armed flag also stands on real motor
     * activity regardless of this.
     */
    @Volatile private var armedCommanded = false

    /**
     * Keep the camera on one position on the ground until told to stop.
     *
     * A repeating correction rather than a single aim, because the target is fixed and the
     * aircraft is not: flying past a point changes both the bearing to it and the angle down to
     * it continuously, so an ROI set once and left alone would only be correct at the instant it
     * was set.
     *
     * Closed-loop on the gimbal's own reported joint angles, and commanded as a *relative*
     * rotation rather than an absolute one. That is the deliberate part. Whether DJI's absolute
     * gimbal yaw is referenced to north or to the aircraft heading is exactly the sort of
     * question this project has twice had to settle by flying rather than by reading, and a
     * relative rotation does not raise it: the desired and the measured angle are both joint
     * angles, so their difference is a rotation the gimbal can simply be asked to make.
     */
    private fun startRoiTracking(
        latitudeDeg: Double,
        longitudeDeg: Double,
        altitudeM: Double,
    ) {
        roiTarget = LocationCoordinate3D(latitudeDeg, longitudeDeg, altitudeM)
        if (roiTrackingRunnable != null) return
        // The camera owns the yaw axis while it tracks a point. In yaw-follow mode the aircraft's
        // turns yank the lens toward the flight direction, so the follow mode and the ROI loop
        // fight at the tracking rate — a gimbal that swings between looking ahead and looking at
        // the target. Free yaw is what the loop expects, so the mode is switched before the loop
        // starts and restored when it stops.
        roiPreviousGimbalMode = KeyManager.getInstance().getValue(aircraftTelemetry.gimbalModeKey) ?: GimbalMode.YAW_FOLLOW
        KeyManager.getInstance().setValue(
            aircraftTelemetry.gimbalModeKey,
            GimbalMode.FREE,
            object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() {
                    Log.i(TAG, "Gimbal yaw freed for ROI tracking")
                }

                override fun onFailure(error: IDJIError) {
                    Log.w(TAG, "Could not free gimbal yaw for ROI: ${error.description()}")
                }
            },
        )
        val runnable =
            object : Runnable {
                override fun run() {
                    val target = roiTarget ?: return
                    trackRoiOnce(target)
                    mainHandler.postDelayed(this, ROI_TRACK_INTERVAL_MS)
                }
            }
        roiTrackingRunnable = runnable
        mainHandler.post(runnable)
        Log.i(TAG, "ROI tracking $latitudeDeg, $longitudeDeg at ${altitudeM}m")
    }

    private fun stopRoiTracking() {
        roiTarget = null
        roiTrackingRunnable?.let { mainHandler.removeCallbacks(it) }
        roiTrackingRunnable = null
        // Give the yaw axis back to the aircraft: with no point to hold, the gimbal follows the
        // nose again as it did before the ROI was set.
        roiPreviousGimbalMode?.let { previous ->
            roiPreviousGimbalMode = null
            KeyManager.getInstance().setValue(
                aircraftTelemetry.gimbalModeKey,
                previous,
                object : CommonCallbacks.CompletionCallback {
                    override fun onSuccess() {
                        Log.i(TAG, "Gimbal yaw follow restored")
                    }

                    override fun onFailure(error: IDJIError) {
                        Log.w(TAG, "Could not restore gimbal yaw follow: ${error.description()}")
                    }
                },
            )
        }
        Log.i(TAG, "ROI tracking cleared")
    }

    /** One correction: where the gimbal should point, less where it reports pointing. */
    private fun trackRoiOnce(target: LocationCoordinate3D) {
        val position = aircraftTelemetry.getLocation3D()
        // Before a fix there is no bearing to compute, and the aircraft's own position would
        // read as the Gulf of Guinea. Waiting is the honest answer; the next tick tries again.
        if (position.latitude == 0.0 && position.longitude == 0.0) return

        val aim =
            RoiControl.aimAt(
                bearingToRoiDeg =
                    DroneController
                        .calculateBearing(
                            position.latitude,
                            position.longitude,
                            target.latitude,
                            target.longitude,
                        ).toDouble(),
                groundDistanceM =
                    DroneController.calculateDistance(
                        target.latitude,
                        target.longitude,
                        position.latitude,
                        position.longitude,
                    ),
                altitudeAboveRoiM = position.altitude - target.altitude,
                headingDeg = aircraftTelemetry.getHeading(),
                aircraftPitchDeg = aircraftTelemetry.getAttitude().pitch,
            )

        val joint = aircraftTelemetry.getGimbalJointAttitude()
        val pitchStep =
            RoiControl.step(
                aim.pitchDeg - joint.pitch,
                ROI_DEADBAND_DEG,
                ROI_MAX_STEP_DEG,
            )
        val yawStep =
            RoiControl.step(
                RoiControl.normalizeAngle(aim.yawDeg - joint.yaw),
                ROI_DEADBAND_DEG,
                ROI_MAX_STEP_DEG,
            )
        if (pitchStep == 0.0 && yawStep == 0.0) return
        mavlinkCommandSink.nudgeGimbal(pitchStep, yawStep)
    }

    private fun isHomeSet(): Boolean {
        val shouldLatchHomePoint =
            !isHomePointSetLatch &&
                !aircraftTelemetry.readState().readings.flying &&
                run {
                    val home = aircraftTelemetry.getHomeLocation()
                    val hasHomeCoordinates = home.latitude != 0.0 && home.longitude != 0.0
                    if (!hasHomeCoordinates) {
                        false
                    } else {
                        val current = aircraftTelemetry.getLocation3D()
                        val distance =
                            DroneController.calculateDistance(
                                current.latitude,
                                current.longitude,
                                home.latitude,
                                home.longitude,
                            )
                        distance < 0.5
                    }
                }

        if (shouldLatchHomePoint) {
            isHomePointSetLatch = true
        }

        return isHomePointSetLatch
    }

    private fun getTelemetryJson(): String = telemetryCoordinator.getTelemetryJson()

    private fun getGapTelemetryJson(): String = telemetryCoordinator.getGapTelemetryJson()

    private fun rebuildTelemetryCache() {
        telemetryCoordinator.droneName = droneName

        // Streaming Config
        val activeMode = settings.getStreamingMode()
        telemetryCoordinator.streamingMode = activeMode.prefValue
        telemetryCoordinator.rtspPort = settings.getRtspPort()
        telemetryCoordinator.rtspUser = settings.getRtspUsername()
        telemetryCoordinator.streamRequiresAuth = activeMode == StreamingMode.RTSP &&
            settings.getRtspUsername().isNotEmpty() &&
            settings.getRtspPassword().isNotEmpty()
        val serverIp = lastClientIp ?: "127.0.0.1"
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

    /**
     * Read the MAVLink endpoint settings, PX4-instance style.
     *
     * On by default: MAVLink and HTTP run side by side out of the box. Flight motion is the part
     * switched on deliberately, per aircraft, via `lb_mav_0_allow_flight`; the endpoint itself can
     * be turned off in the field with `adb shell` or the settings backup file by setting
     * `lb_mav_0_enabled` to false.
     */
    private fun readMavlinkConfig(): MavlinkEndpointConfig {
        // These preferences are edited by hand in the field (adb, or the settings backup file), so
        // a value stored with the wrong type must not crash the app on startup.
        val port =
            prefIntOrDefault(
                MavlinkEndpointConfig.PREF_PORT,
                MavlinkEndpointConfig.DEFAULT_GCS_PORT,
            )
        // One system id per aircraft, so QGroundControl does not merge two drones into one vehicle.
        // 0 (the default) derives the id from the drone name once renamed, and the serial before that.
        val systemId = currentMavlinkSystemId()
        return MavlinkEndpointConfig(
            enabled =
                runCatching {
                    sharedPreferences.getBoolean(MavlinkEndpointConfig.PREF_ENABLED, true)
                }.getOrDefault(true),
            targetHost =
                runCatching {
                    sharedPreferences.getString(MavlinkEndpointConfig.PREF_HOST, "")
                }.getOrNull().orEmpty(),
            targetPort = port,
            listenPort = port,
            mode =
                MavlinkEndpointConfig.Profile.fromPref(
                    runCatching {
                        sharedPreferences.getString(MavlinkEndpointConfig.PREF_MODE, null)
                    }.getOrNull(),
                ),
            systemId = systemId,
            signingKeyHex =
                runCatching {
                    sharedPreferences.getString(MavlinkEndpointConfig.PREF_SIGNING_KEY, "")
                }.getOrNull().orEmpty(),
            missionExecutor =
                MissionExecutor.fromPref(
                    runCatching {
                        sharedPreferences.getString(MavlinkEndpointConfig.PREF_MISSION_EXECUTOR, null)
                    }.getOrNull(),
                ),
        )
    }

    /** The full DJI serial is immutable; the editable drone name is never an ID input. */
    private fun sysIdKey(): String = droneSerialNumber.trim().ifEmpty { "UNKNOWN" }

    private fun configuredMavlinkSystemId(): Int =
        prefIntOrDefault(
            MavlinkEndpointConfig.PREF_SYSTEM_ID,
            MavlinkEndpointConfig.DEFAULT_SYSTEM_ID,
        )

    private fun configuredMavlinkSystemIdIsManual(): Boolean = MavlinkSystemId.isManual(configuredMavlinkSystemId())

    private fun currentMavlinkSystemId(): Int = MavlinkSystemId.resolve(configuredMavlinkSystemId(), sysIdKey())

    /** Read an int preference that may have been stored as a string by a hand edit. */
    private fun prefIntOrDefault(
        key: String,
        fallback: Int,
    ): Int =
        runCatching { sharedPreferences.getInt(key, fallback) }
            .recoverCatching { sharedPreferences.getString(key, null)?.toInt() ?: fallback }
            .getOrDefault(fallback)

    /**
     * Whether a home point is somewhere rather than the SDK's unset value.
     *
     * Deliberately not the same question as [isHomeSet], which is a latch meaning "home was
     * recorded on this flight". DJI knows where home is well before that closes, and the check
     * that matters for arithmetic is whether the numbers are a place at all.
     */
    private fun hasRealHomeCoordinates(
        latitude: Double,
        longitude: Double,
    ): Boolean =
        (latitude != 0.0 || longitude != 0.0) &&
            latitude in -90.0..90.0 &&
            longitude in -180.0..180.0

    /**
     * Stable identity for this device on the fleet mesh.
     *
     * Prefers the aircraft serial, which is immutable, already the key for per-drone settings
     * profiles, and the same thing the MAVLink system id is derived from — so a peer's roster
     * entry and its MAVLink vehicle refer to provably the same airframe.
     */
    private fun fleetDeviceId(): String {
        val serial = droneSerialNumber.trim()
        if (DroneSettingsProfiles.isUsableSerial(serial)) return serial
        return sharedPreferences
            .getString(LyrebirdSettings.PREF_FLEET_INSTALL_ID, null)
            ?.takeIf { it.isNotBlank() }
            ?: generateFleetInstallId()
    }

    private fun generateFleetInstallId(): String {
        val generated =
            "rc-" +
                java.util.UUID
                    .randomUUID()
                    .toString()
                    .take(LyrebirdSettings.FLEET_INSTALL_ID_LENGTH)
        sharedPreferences.edit().putString(LyrebirdSettings.PREF_FLEET_INSTALL_ID, generated).apply()
        Log.i(TAG, "Generated a fleet install id for a device with no aircraft bound: $generated")
        return generated
    }

    /**
     * This aircraft's state, in the shape the fleet mesh publishes.
     *
     * Reads the same accessors as [buildMavlinkSnapshot] rather than reusing its result, for the
     * same reason that one reads accessors rather than the cached telemetry JSON: two surfaces
     * reporting different numbers for the same instant is the defect worth designing out, and the
     * accessors are the single source both sides agree on.
     *
     * Called from the mesh's sender thread, as the MAVLink stream loop calls its own builder.
     */
    private fun buildFleetBeacon(): FleetBeacon? {
        if (!::sharedPreferences.isInitialized) return null
        return aircraftTelemetry.readState().readings.toFleetBeacon(
            deviceId = fleetDeviceId(),
            droneName = droneName,
            systemId = currentMavlinkSystemId(),
            homeSet = isHomeSet(),
            // The MediaMTX path this aircraft publishes to, resolved exactly as buildWhipUrl
            // resolves it, so a clash the dashboard would suffer is a clash the mesh can see.
            videoPath = droneName.trim().ifEmpty { LyrebirdSettings.DEFAULT_DRONE_NAME },
            videoServer = settings.getMediamtxServer(),
        )
    }

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

    private fun startFleetMesh() {
        if (fleetController != null) return
        val controller =
            FleetDeckController(
                activity = this,
                prefs = sharedPreferences,
                stripView = attachFleetStrip(),
                mapWidget = mapWidget,
                deviceIdProvider = ::fleetDeviceId,
                beaconProvider = ::buildFleetBeacon,
            )
        controller.start()
        controller.setMapExpanded(sharedPreferences.getBoolean(LyrebirdSettings.PREF_MAP_EXPANDED, false))
        fleetController = controller
    }

    private fun stopFleetMesh() {
        fleetController?.stop()
        fleetController = null
    }

    /**
     * Arm the obstacle guard, if the operator has opted in.
     *
     * Off by default: it changes what the aircraft does in flight, and a feature that does that
     * should be switched on deliberately rather than inherited from an app update.
     */
    private fun startObstacleGuard() {
        ObstacleGuard.motionProvider = {
            val speed = aircraftTelemetry.getSpeed()
            ObstacleGuard.Motion(
                velocityNorthMps = speed.x,
                velocityEastMps = speed.y,
                velocityDownMps = speed.z,
                headingDeg = aircraftTelemetry.getHeading(),
            )
        }
        ObstacleGuard.onBrake = { event ->
            LyrebirdFlightLogger.logStatus(
                "OBSTACLE_STOP ${event.reason.name} " +
                    "clearance=${"%.1f".format(event.clearanceM)}m " +
                    "required=${"%.1f".format(event.requiredM)}m",
            )
            // Status only, on the readout the pilot already watches. Nothing pops up over the
            // video: an aircraft that has just stopped itself needs the pilot looking outside,
            // not reading a notification.
            mainHandler.post { updateDroneStatusView(DroneController.droneStatus) }
        }
        ObstacleGuard.start(sharedPreferences)
    }

    private fun stopObstacleGuard() {
        ObstacleGuard.stop()
        ObstacleGuard.motionProvider = null
        ObstacleGuard.onBrake = null
    }

    private fun obstacleGuardSummary(): String =
        when {
            !ObstacleGuard.isEnabled(sharedPreferences) -> "Off"
            ObstacleGuard.isRunning -> "On, ${ObstacleBrake.DEFAULT_MARGIN_M.toInt()}m standoff"
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
        val enabling = !ObstacleGuard.isEnabled(sharedPreferences)
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

    /**
     * One consistent read of aircraft state for the MAVLink endpoint.
     *
     * Deliberately reads the same accessors that feed [rebuildRealTelemetryCache] rather than the
     * cached JSON, so the two surfaces cannot report different numbers for the same instant while
     * both are live.
     *
     * DJI reports no arming state, so `motorsRunning` comes from KeyIsFlying — the only honest
     * source. Deriving it from the flight mode, as the ground-station helper currently does,
     * reports armed while the aircraft is sitting on the ground.
     */
    private fun buildMavlinkSnapshot(): MavlinkSnapshot {
        val readings = aircraftTelemetry.readState().readings
        val lrfTarget = lrfTargetLocation

        return readings.toMavlinkSnapshot(
            MavlinkSnapshot(
                droneName = droneName,
                homeSet = isHomeSet(),
                manualOverrideActive = DroneController.isManualOverrideActive,
                armedCommanded = armedCommanded,
                // The sequencer flies through virtual stick, so the mode DJI reports (OFFBOARD) would
                // hide a mission that is actually under way; the heartbeat prefers MISSION instead.
                missionActive = mavlinkMissionSink.isRunning,
                lrfDistanceM = lrfDistanceMeters,
                lrfTargetLatitudeDeg = lrfTarget?.latitude,
                lrfTargetLongitudeDeg = lrfTarget?.longitude,
                lrfTargetAltitudeM = lrfTarget?.altitude,
                waypointReached = DroneController.isWaypointReached(),
                waypointSeq = DroneController.getWaypointSeq(),
                yawReached = DroneController.isYawReached(),
                yawSeq = DroneController.getYawSeq(),
                altitudeReached = DroneController.isAltitudeReached(),
                altitudeSeq = DroneController.getAltitudeSeq(),
                // The same answers GET /config gives, so a ground station on MAVLink alone still
                // learns how to reach the other surfaces and what this airframe carries.
                ipAddress = NetworkUtils.getDeviceIpAddress() ?: "",
                httpPort = HTTP_PORT,
                telemetryPort = TELEMETRY_PORT,
                videoMode = settings.getStreamingMode().prefValue,
                hasThermal = runCatching { hasThermalCamera() }.getOrDefault(false),
                autoSensingActive = isAutoSensingActive,
                detectionSource = settings.getDetectionSource().prefValue,
                detectionConfidenceThreshold = settings.getEdgeConfidenceThreshold(),
                detectedTargets =
                    currentDetectedTargets.map {
                        DetectedTargetSnapshot(it.type, it.left, it.top, it.right, it.bottom, it.confidence)
                    },
            ),
        )
    }

    /**
     * The video stream to advertise to a ground station, or null while nothing is publishing.
     *
     * Derived from the WHIP URL the app is already publishing to, so the ground-station address is
     * never configured twice: MediaMTX ingests the WHIP publish and republishes the same stream on
     * RTSP, which is the transport QGroundControl can actually play. Returning null while no
     * stream is up is deliberate — advertising a dead RTSP URL makes a ground station sit in a
     * connect-retry loop, which is worse than reporting no stream.
     */
    private fun currentMavlinkVideoStream(): MavlinkVideoStream? = MavlinkVideoStream.fromWhipUrl(lastWhipUrl, droneName)

    /**
     * Apply one parameter write from a ground station.
     *
     * An allowlist, not a passthrough. Most of the published list is read-only by nature — PID
     * gains belong to the control profile, and the PX4 compatibility parameters are constants
     * that exist only to satisfy QGroundControl's setup checks. Writing those would either do
     * nothing or quietly change flight behaviour from a settings dialog, so anything not named
     * here is refused rather than accepted and dropped.
     */
    private fun applyMavlinkParameter(
        name: String,
        value: Float,
    ): CommandResult =
        when (name) {
            PARAM_MAX_HEIGHT ->
                awaitParameterWrite { done ->
                    DroneController.setMaxFlightHeight(value.toInt())
                    done(true)
                }

            PARAM_MAX_DISTANCE ->
                awaitParameterWrite { done ->
                    DroneController.setMaxFlightDistance(value.toInt())
                    done(true)
                }

            PARAM_DISTANCE_LIMIT ->
                awaitParameterWrite { done ->
                    DroneController.setDistanceLimitEnabled(value >= 0.5f)
                    done(true)
                }

            PARAM_WEBRTC_FPS ->
                if (setWebRtcFps(value.toInt())) {
                    CommandResult(MavlinkCommandOutcome.ACCEPTED)
                } else {
                    CommandResult(MavlinkCommandOutcome.DENIED, "Unsupported frame rate")
                }

            PARAM_DETECTIONS -> {
                setDetectionsEnabled(value >= 0.5f)
                CommandResult(MavlinkCommandOutcome.ACCEPTED)
            }

            PARAM_SURFACE_H264_ENCODER -> {
                setDjiSurfaceH264Encoder(value >= 0.5f)
                CommandResult(MavlinkCommandOutcome.ACCEPTED)
            }

            PARAM_MAVLINK_SYSTEM_ID -> {
                val systemId = value.toInt()
                if (value != systemId.toFloat() || !setMavlinkSystemId(systemId)) {
                    CommandResult(
                        MavlinkCommandOutcome.DENIED,
                        "Use 0 for automatic or 1-99 for a manual vehicle ID",
                    )
                } else {
                    CommandResult(MavlinkCommandOutcome.ACCEPTED)
                }
            }

            PARAM_EDGE_CONFIDENCE ->
                if (setEdgeConfidence(value)) {
                    CommandResult(MavlinkCommandOutcome.ACCEPTED)
                } else {
                    CommandResult(MavlinkCommandOutcome.DENIED, "Threshold out of range")
                }

            PARAM_RTH_ALTITUDE -> {
                val altitude = value.toInt()
                if (altitude <= 0) {
                    CommandResult(MavlinkCommandOutcome.DENIED, "RTH altitude must be positive")
                } else {
                    // Waited on rather than fired and forgotten, because the PARAM_VALUE sent back
                    // immediately afterwards is meant to report what the parameter now holds. Without
                    // the wait it reports the value from before the write, and a ground station
                    // correctly concludes the write did not take.
                    awaitParameterWrite { done -> DroneController.setRTHAltitude(altitude, done) }
                }
            }

            else -> {
                Log.d(TAG, "Refusing write to read-only parameter $name")
                CommandResult(MavlinkCommandOutcome.DENIED, "$name is read-only")
            }
        }

    /**
     * Run an asynchronous parameter write and wait, briefly, for the aircraft to confirm it.
     *
     * Bounded so a key the aircraft never answers cannot wedge the endpoint's receive thread —
     * a timeout is reported as a failure, which is what it is.
     */
    private fun awaitParameterWrite(write: ((Boolean) -> Unit) -> Unit): CommandResult {
        val latch = java.util.concurrent.CountDownLatch(1)
        val succeeded =
            java.util.concurrent.atomic
                .AtomicBoolean(false)
        write { ok ->
            succeeded.set(ok)
            latch.countDown()
        }
        val answered = latch.await(ACTION_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        return when {
            !answered -> CommandResult(MavlinkCommandOutcome.FAILED, "Aircraft did not answer")
            succeeded.get() -> CommandResult(MavlinkCommandOutcome.ACCEPTED)
            else -> CommandResult(MavlinkCommandOutcome.FAILED, "Aircraft refused the write")
        }
    }

    /**
     * The active control profile, published as read-only MAVLink parameters.
     *
     * Two reasons this exists now rather than in a later phase. It is what a ground station needs
     * to finish connecting — QGroundControl's camera manager discards every message, including the
     * camera heartbeat, until its initial-connect state machine completes, and that machine blocks
     * on the parameter download. And it makes the per-airframe tuning visible in a standard
     * parameter editor instead of being a constant nobody outside the source can see.
     *
     * Read-only for now: these are published, not settable. Making them writable is a change with
     * its own safety review, since they are the gains an autonomous control loop flies on.
     */
    private fun mavlinkParameters(): List<Pair<String, Float>> {
        val profile = DroneControlProfiles.activeProfile()
        return listOf(
            "LB_DIST_KP" to profile.distanceKp.toFloat(),
            "LB_DIST_KI" to profile.distanceKi.toFloat(),
            "LB_DIST_KD" to profile.distanceKd.toFloat(),
            "LB_YAW_KP" to profile.yawKp.toFloat(),
            "LB_YAW_RATE_MAX" to profile.maxYawRateDegS.toFloat(),
            "LB_SPD_MAX" to profile.maxHorizontalSpeedMps.toFloat(),
            "LB_ACC_MAX" to profile.maxHorizontalAccelMps2.toFloat(),
            "LB_SPD_CRUISE" to profile.defaultCruiseSpeedMps.toFloat(),
            "LB_WP_ACC_RAD" to DroneController.WP_ACCEPT_DISTANCE_M.toFloat(),
            "LB_WP_ACC_ALT" to DroneController.WP_ACCEPT_ALTITUDE_M.toFloat(),
            "LB_WP_ACC_YAW" to DroneController.WP_ACCEPT_YAW_DEG.toFloat(),
            // The one writable parameter. Published so a ground station can read it back after a
            // write and see what actually took, which is what makes PARAM_SET meaningful.
            PARAM_RTH_ALTITUDE to DroneController.getRTHAltitude().toFloat(),
            PARAM_MAX_HEIGHT to DroneController.getMaxFlightHeight().toFloat(),
            PARAM_MAX_DISTANCE to DroneController.getMaxFlightDistance().toFloat(),
            PARAM_DISTANCE_LIMIT to if (DroneController.getDistanceLimitEnabled()) 1f else 0f,
            PARAM_WEBRTC_FPS to settings.getWebRTCFps().toFloat(),
            PARAM_DETECTIONS to if (settings.isDetectionsEnabled()) 1f else 0f,
            PARAM_EDGE_CONFIDENCE to settings.getEdgeConfidenceThreshold(),
            PARAM_SURFACE_H264_ENCODER to if (settings.isDjiSurfaceH264EncoderEnabled()) 1f else 0f,
            PARAM_MAVLINK_SYSTEM_ID to currentMavlinkSystemId().toFloat(),
            // QGC's PX4 airframe component reads this one PX4 parameter and pops a "Parameters
            // are missing from firmware" dialog when it is absent. 4001 is PX4's "Generic
            // Quadcopter" airframe id; published read-only like the rest of the list.
            "SYS_AUTOSTART" to 4001f,
            // PX4 radio parameters. COM_RC_IN_MODE=1 tells QGC the RC comes from a joystick
            // rather than a MAVLink RC link, which makes its Radio setup task not-required (the
            // DJI remote is not exposed over MAVLink, so a calibration wizard would have nothing
            // to calibrate). The RC_MAP_* pins are 0 = unmapped, which is honest: there are no
            // MAVLink RC channels to map. Without these, QGC reports them missing and lists a
            // "Configuration tasks remain" setup task on every connect.
            "COM_RC_IN_MODE" to 1f,
            "RC_MAP_ROLL" to 0f,
            "RC_MAP_PITCH" to 0f,
            "RC_MAP_YAW" to 0f,
            "RC_MAP_THROTTLE" to 0f,
            // PX4 sensor calibration. QGC's Sensors setup task requires CAL_GYRO0_ID and
            // CAL_ACC0_ID to be non-zero before it is complete, and reports them missing on every
            // connect otherwise ("Parameters are missing ... Configuration tasks remain"). DJI
            // calibrates its IMU in the factory, so these are published as already-calibrated
            // device ids (any non-zero value satisfies QGC) rather than exposed for recalibration.
            "CAL_GYRO0_ID" to 131074f,
            "CAL_ACC0_ID" to 131330f,
            "CAL_MAG0_ID" to 131586f,
        )
    }

    /**
     * Payload and camera commands reachable over MAVLink.
     *
     * Deliberately excludes every command that could move the aircraft. The set here is the same
     * work the equivalent HTTP endpoints do, called through the same view models, so the two
     * surfaces cannot drift in behaviour — which is the failure that killed the previous
     * ground-station MAVLink proxy.
     *
     * Commands run on the main thread because the DJI view models expect it, and the endpoint
     * calls this from its receive thread.
     */
    private val mavlinkCommandSink =
        object : MavlinkCommandSink {
            override fun setGimbal(rotation: GimbalRotation): CommandResult {
                // An explicit aim ends the tracking. Otherwise the two fight at 5 Hz and the operator
                // loses, which looks like a gimbal that ignores its commands rather than like a
                // region of interest that is still set.
                stopRoiTracking()
                return rotateGimbal(rotation)
            }

            /** The aim itself, with no effect on tracking — what the ROI loop drives. */
            private fun rotateGimbal(rotation: GimbalRotation): CommandResult {
                gimbalKey.action(
                    GimbalAngleRotation(
                        if (rotation.mode == GimbalRotationMode.ABSOLUTE) {
                            GimbalAngleRotationMode.ABSOLUTE_ANGLE
                        } else {
                            GimbalAngleRotationMode.RELATIVE_ANGLE
                        },
                        rotation.pitchDeg,
                        rotation.rollDeg,
                        rotation.yawDeg,
                        rotation.pitchIgnored,
                        rotation.rollIgnored,
                        rotation.yawIgnored,
                        0.1,
                        false,
                        0,
                    ),
                )
                return CommandResult(MavlinkCommandOutcome.ACCEPTED)
            }

            override fun setGimbalRelative(
                pitchDeg: Double,
                yawDeg: Double,
            ): CommandResult {
                stopRoiTracking()
                return nudgeGimbal(pitchDeg, yawDeg)
            }

            /** A relative nudge that leaves tracking alone, so the ROI loop can use it. */
            fun nudgeGimbal(
                pitchDeg: Double,
                yawDeg: Double,
            ): CommandResult {
                // A zero delta on an axis means "leave it alone", which is what the ignore flags say
                // — sending zero as a relative angle would be the same thing, but saying it through
                // the flag is what keeps a two-axis nudge from fighting itself.
                return rotateGimbal(
                    GimbalRotation(
                        mode = GimbalRotationMode.RELATIVE,
                        pitchDeg = pitchDeg,
                        rollDeg = 0.0,
                        yawDeg = yawDeg,
                        pitchIgnored = pitchDeg == 0.0,
                        rollIgnored = true,
                        yawIgnored = yawDeg == 0.0,
                    ),
                )
            }

            override fun setRegionOfInterest(
                latitudeDeg: Double,
                longitudeDeg: Double,
                altitudeM: Double,
            ): CommandResult {
                if (!latitudeDeg.isFinite() || !longitudeDeg.isFinite()) {
                    return CommandResult(MavlinkCommandOutcome.DENIED, "ROI needs a real position")
                }
                startRoiTracking(latitudeDeg, longitudeDeg, altitudeM.takeIf { it.isFinite() } ?: 0.0)
                return CommandResult(MavlinkCommandOutcome.ACCEPTED)
            }

            override fun clearRegionOfInterest(): CommandResult {
                stopRoiTracking()
                return CommandResult(MavlinkCommandOutcome.ACCEPTED)
            }

            override fun measureLrf(): CommandResult {
                val info =
                    Payload.takeFreshLrfReading()
                        ?: return CommandResult(MavlinkCommandOutcome.FAILED, "No rangefinder reading")
                if (info.laserMeasureState != LaserMeasureState.NORMAL) {
                    // The laser did not lock — no distance, and no point to geo-reference.
                    return CommandResult(
                        MavlinkCommandOutcome.FAILED,
                        "Laser state ${info.laserMeasureState}",
                    )
                }
                lrfDistanceMeters = info.distance
                info.location3D
                    ?.takeIf { it.latitude != 0.0 || it.longitude != 0.0 || it.altitude != 0.0 }
                    // Surfaced on the telemetry stream as lrfTarget, exactly as the HTTP route does.
                    ?.let { lrfTargetLocation = it }
                // Centimetres: the distance is metres with a useful fraction.
                return CommandResult(
                    MavlinkCommandOutcome.ACCEPTED,
                    resultValue = ((info.distance ?: 0.0) * 100).toInt(),
                )
            }

            override fun captureTemperature(): CommandResult {
                val maxTemp =
                    readThermalMaxTempNow()
                        ?: return CommandResult(MavlinkCommandOutcome.FAILED, "No thermal reading")
                // Hundredths of a degree, so a fractional reading survives an integer field.
                return CommandResult(
                    MavlinkCommandOutcome.ACCEPTED,
                    resultValue = (maxTemp * 100).toInt(),
                )
            }

            override fun captureThermalImage(): CommandResult {
                // The descriptor names the per-lens files the shutter stored; downloads stay on the
                // media surface (by name), exactly as they do over HTTP. Only the shutter is commanded
                // here — there is no room for the descriptor in an ack.
                val descriptor =
                    Payload.captureThermal(mediaVM)
                        ?: return CommandResult(
                            MavlinkCommandOutcome.FAILED,
                            "Thermal capture produced no file",
                        )
                return CommandResult(MavlinkCommandOutcome.ACCEPTED, detail = descriptor)
            }

            override fun dropPayload(): CommandResult {
                val profile = DroneControlProfiles.activeProfile()
                val indexType =
                    profile.payloadIndexType
                        ?: return CommandResult(
                            MavlinkCommandOutcome.UNSUPPORTED,
                            "${profile.displayName} has no payload drop port",
                        )
                val dropped =
                    Payload.dropPayload(
                        payloadWidgetVM,
                        indexType,
                        profile.dropArmSwitchIndex,
                        profile.dropReleaseButtonIndex,
                    )
                return if (dropped) {
                    CommandResult(MavlinkCommandOutcome.ACCEPTED)
                } else {
                    CommandResult(MavlinkCommandOutcome.FAILED, "Drop refused by the payload")
                }
            }

            override fun setAutoSensing(enabled: Boolean): CommandResult {
                // On the main thread, as the HTTP route does: this drives the detector and its UI
                // switch, and the MAVLink receive thread is not where either belongs.
                mainHandler.post {
                    if (enabled) startAutoSensing() else stopAutoSensing()
                    setAutoSensingSwitchChecked(enabled)
                }
                return CommandResult(MavlinkCommandOutcome.ACCEPTED)
            }

            override fun setParameter(
                name: String,
                value: Float,
            ): CommandResult = applyMavlinkParameter(name, value)

            override fun setTextParameter(
                name: String,
                value: String,
            ): CommandResult {
                val applied =
                    when (name) {
                        PARAM_DRONE_NAME -> setDroneName(value)
                        PARAM_VIDEO_SOURCE -> setVideoSource(value)
                        PARAM_MEDIAMTX -> setMediamtxServer(value)
                        PARAM_DETECTION_SOURCE -> setDetectionSource(value)
                        PARAM_RC_CONTROL_MODE -> DroneController.setRcControlMode(value)
                        PARAM_RTC_RESOLUTION -> setWebRtcResolution(value)
                        PARAM_STREAMING_MODE -> {
                            val mode = StreamingMode.entries.firstOrNull { it.prefValue == value }
                            if (mode == null) {
                                false
                            } else {
                                setStreamingMode(mode)
                                true
                            }
                        }

                        else -> {
                            Log.d(TAG, "Refusing write to unknown text parameter $name")
                            return CommandResult(MavlinkCommandOutcome.DENIED, "$name is not writable")
                        }
                    }
                // The detail carries the value the setting now holds, so the acknowledgement echoes
                // what took rather than what was asked for — which is how a caller detects a value
                // the aircraft rejected as out of range or unknown.
                val current = textParameters().firstOrNull { it.first == name }?.second.orEmpty()
                return if (applied) {
                    CommandResult(MavlinkCommandOutcome.ACCEPTED, current)
                } else {
                    CommandResult(MavlinkCommandOutcome.DENIED, current)
                }
            }

            override fun textParameters(): List<Pair<String, String>> =
                listOf(
                    PARAM_DRONE_NAME to droneName,
                    PARAM_VIDEO_SOURCE to VIDEO_SOURCE_LABEL,
                    PARAM_MEDIAMTX to settings.getMediamtxServer(),
                    PARAM_DETECTION_SOURCE to settings.getDetectionSource().prefValue,
                    PARAM_RC_CONTROL_MODE to DroneController.getRcControlMode(),
                    PARAM_RTC_RESOLUTION to settings.getWebRTCResolutionPreset().prefValue,
                    PARAM_STREAMING_MODE to settings.getStreamingMode().prefValue,
                )

            override fun setCameraZoom(zoomRatio: Float): CommandResult {
                if (zoomRatio <= 0f) return CommandResult(MavlinkCommandOutcome.FAILED)
                zoomKey.set(zoomRatio.toDouble())
                // set() is fire-and-forget; the ratio the aircraft settled on is reported in
                // telemetry, which is where a ground station should read it back from.
                return CommandResult(MavlinkCommandOutcome.ACCEPTED)
            }

            override fun startVideoRecording(): CommandResult = awaitAction(startRecording)

            override fun stopVideoRecording(): CommandResult = awaitAction(stopRecording)

            /**
             * Trip one shutter.
             *
             * Runs on a worker rather than inline: tripping a shutter and waiting for the file to
             * appear takes seconds, and this is called from the endpoint's single receive thread —
             * blocking it would stall every other inbound message, including the ground station's own
             * heartbeat handling.
             *
             * So the command is acknowledged as accepted and the real outcome follows as
             * CAMERA_IMAGE_CAPTURED, whose `capture_result` reports whether a photo actually
             * happened. That split is what the message exists for.
             *
             * Uses the generic photo path, not the thermal one: a Mini 3 has a single lens and no
             * thermal file to find, so labelling the result thermal/wide/zoom would be meaningless.
             *
             * The endpoint is optional rather than required: the distance-triggered capture loop
             * inside a flying mission trips this same shutter, and a mission must not stop
             * photographing because the MAVLink endpoint happens to be mid-restart. Without an
             * endpoint there is simply no CAMERA_IMAGE_CAPTURED to send, so the outcome is logged
             * instead of reported — the shutter still fires.
             */
            override fun captureImage(): CommandResult {
                val endpoint = mavlinkEndpoint
                endpoint?.reportCaptureStarted()
                captureExecutor.execute {
                    val file =
                        runCatching { Payload.capturePhoto(mediaVM) }
                            .onFailure { Log.e(TAG, "Capture failed: ${it.message}", it) }
                            .getOrNull()
                    if (file == null) {
                        Log.w(TAG, "Capture produced no file")
                    }
                    endpoint?.reportImageCaptured(file != null, file?.fileName.orEmpty())
                }
                return CommandResult(MavlinkCommandOutcome.ACCEPTED)
            }
        }

    /**
     * Issue a DJI action key and report what actually happened.
     *
     * The SDK's action callbacks are asynchronous while the command sink is synchronous, so this
     * waits briefly for the result. Returning ACCEPTED without waiting is what the first version
     * of this did, and it told a ground station that recording had stopped while the camera was
     * still rolling — an ack that carries no information is worse than a slow one.
     *
     * The wait is bounded: a command the aircraft never answers becomes FAILED rather than
     * blocking the endpoint's receive thread.
     */
    private fun awaitAction(key: DJIKey.ActionKey<EmptyMsg, EmptyMsg>): CommandResult {
        val latch = java.util.concurrent.CountDownLatch(1)
        val succeeded =
            java.util.concurrent.atomic
                .AtomicBoolean(false)
        key.action(
            {
                succeeded.set(true)
                latch.countDown()
            },
            { error ->
                Log.w(TAG, "DJI action failed: ${error.description()}")
                latch.countDown()
            },
        )
        val answered = latch.await(ACTION_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        return when {
            !answered -> CommandResult(MavlinkCommandOutcome.FAILED)
            succeeded.get() -> CommandResult(MavlinkCommandOutcome.ACCEPTED)
            else -> CommandResult(MavlinkCommandOutcome.FAILED)
        }
    }

    /**
     * Returns a refusal when MAVLink-commanded motion is blocked, or null when it may proceed.
     *
     * Lives on the activity rather than inside one sink because both the motion sink and the
     * mission sink fly the aircraft, and a gate that only one of them consulted would be a hole
     * rather than a gate.
     */
    private fun mavlinkFlightGate(): CommandResult? {
        if (!sharedPreferences.getBoolean(MavlinkEndpointConfig.PREF_ALLOW_FLIGHT, true)) {
            // Silent otherwise: the sender gets MAV_RESULT_DENIED over the wire and it lands in
            // the flight log, but nobody standing at the aircraft would ever see either of those
            // in the moment — a ground station could sit there commanding takeoff on a fresh
            // install after the setting has explicitly been blocked and the pilot would have no
            // idea why the command was refused.
            ToastUtils.showToast(
                "MAVLink flight command blocked — flight control not allowed " +
                    "(enable it from the settings menu)",
            )
            return CommandResult(MavlinkCommandOutcome.DENIED)
        }
        // A frame signed with the configured key is the Safety Computer; anything else is the
        // Pilot. Before signing every MAVLink command was the Pilot unconditionally, so an
        // installation that configures no key sees exactly the behaviour it saw before.
        val source =
            if (mavlinkEndpoint?.isTrustedOrigin == true) {
                ControlAuthority.Source.SAFETY
            } else {
                ControlAuthority.Source.PILOT
            }
        if (!ControlAuthority.authorizeControlCommand(source)) {
            ToastUtils.showToast(
                "MAVLink flight command blocked — the Safety Computer has control",
            )
            return CommandResult(MavlinkCommandOutcome.DENIED)
        }
        return null
    }

    /**
     * Stop a running plan before taking the aircraft somewhere else.
     *
     * Without this the sequencer keeps its own state: an operator pressing Land or Return in a
     * ground station would land the aircraft, and the sequencer -- which only watches the reach
     * latch -- would then issue the next leg and fly it away again. A guided command supersedes a
     * mission, which is what every other autopilot does and what an operator reaching for Land
     * plainly means.
     */
    private fun supersedeMission(reason: String) {
        if (mavlinkMissionSink.isRunning) {
            Log.i(TAG, "Stopping the running mission: superseded by $reason")
            mavlinkMissionSink.stopMission()
        }
    }

    /**
     * Flight-motion commands over MAVLink, behind the safety gate.
     *
     * Three layers, checked in order:
     *   1. lb_mav_0_allow_flight — enabled by default; an explicit settings choice can block it.
     *   2. command authority — MAVLink speaks as the Pilot, so it is refused once the Safety
     *      Computer has seized control over HTTP.
     *   3. the RC manual-override latch — closed-loop commands (reposition, yaw) are refused while
     *      the physical RC pilot has taken over.
     */
    private val mavlinkMotionSink =
        object : MavlinkMotionSink {
            override fun takeoff(altitudeM: Float?): CommandResult {
                mavlinkFlightGate()?.let { return it }
                if (DroneController.shouldRejectAutonomousCommand("takeoff")) {
                    return CommandResult(MavlinkCommandOutcome.DENIED)
                }
                DroneController.startTakeOff()
                if (altitudeM != null) climbAfterTakeoff(altitudeM.toDouble())
                return CommandResult(MavlinkCommandOutcome.ACCEPTED)
            }

            override fun land(): CommandResult {
                mavlinkFlightGate()?.let { return it }
                if (DroneController.shouldRejectAutonomousCommand("land")) {
                    return CommandResult(MavlinkCommandOutcome.DENIED)
                }
                supersedeMission("land")
                DroneController.startLanding()
                return CommandResult(MavlinkCommandOutcome.ACCEPTED)
            }

            override fun returnToHome(): CommandResult {
                mavlinkFlightGate()?.let { return it }
                if (DroneController.shouldRejectAutonomousCommand("return to home")) {
                    return CommandResult(MavlinkCommandOutcome.DENIED)
                }
                supersedeMission("return to home")
                DroneController.startReturnToHome()
                return CommandResult(MavlinkCommandOutcome.ACCEPTED)
            }

            override fun reposition(
                latitudeDeg: Double,
                longitudeDeg: Double,
                altitudeMeters: Double,
                yawDeg: Double,
                groundSpeedMps: Double,
            ): CommandResult {
                mavlinkFlightGate()?.let { return it }
                if (DroneController.shouldRejectAutonomousCommand("reposition")) {
                    return CommandResult(MavlinkCommandOutcome.DENIED)
                }
                supersedeMission("reposition")

                // DO_REPOSITION carries three "leave this one alone" sentinels, and QGroundControl
                // sends all three. None of them are values to fly to, and passing them through as if
                // they were is what made a goto fly backwards and an altitude change do nothing.

                // param1 is "ground speed, less than 0 (-1) for default". As a speed it is harmless;
                // as the *ceiling* the waypoint loop clamps to, -1 m/s is a command to retreat.
                val speed =
                    groundSpeedMps
                        .takeIf { it.isFinite() && it > 0.0 }
                        ?: DroneControlProfiles.activeProfile().defaultCruiseSpeedMps

                // param5/param6 NaN mean "hold the current position and change only the altitude",
                // which is how QGC expresses Change Altitude. A COMMAND_INT carries them as scaled
                // int32, where NaN converts to 0 -- a real coordinate in the Gulf of Guinea rather
                // than a marker -- so the zero case has to be caught alongside the non-finite one.
                val holdingPosition =
                    !latitudeDeg.isFinite() ||
                        !longitudeDeg.isFinite() ||
                        (
                            abs(latitudeDeg) < REPOSITION_COORD_EPSILON &&
                                abs(longitudeDeg) < REPOSITION_COORD_EPSILON
                        )
                if (holdingPosition) {
                    // Three of QGroundControl's guided actions are this one command, told apart only
                    // by which parameters are real: Go to location carries a position, Change
                    // altitude carries only an altitude, and Set heading carries only a yaw. So a
                    // command with no position is not automatically an altitude change, and reading
                    // it as one silently discarded the heading the operator had just dialled in.
                    //
                    // Neither is routed to a waypoint at the current position, which would be the
                    // obvious way to express "stay here": a zero-length leg has no bearing, so the
                    // nose-forward controller reads atan2(0, 0) and turns the aircraft north first.
                    return if (!yawDeg.isNaN()) {
                        val seq = DroneController.gotoYaw(yawDeg)
                        CommandResult(
                            MavlinkCommandOutcome.ACCEPTED,
                            pending = PendingCommand(PendingKind.YAW, seq),
                        )
                    } else {
                        val seq =
                            DroneController.gotoAltitude(
                                // A Change altitude always names one. Defended anyway, because an
                                // altitude of NaN reaches the vertical controller as a setpoint and
                                // every comparison against it is false, so the aircraft would hold
                                // whatever throttle it had rather than refuse.
                                altitudeMeters.takeIf { it.isFinite() } ?: aircraftTelemetry.getLocation3D().altitude,
                            )
                        CommandResult(
                            MavlinkCommandOutcome.ACCEPTED,
                            pending = PendingCommand(PendingKind.ALTITUDE, seq),
                        )
                    }
                }

                // param4 NaN means "use the vehicle's heading mode", exactly as it does in a mission
                // item. Honouring it here too is what lets a single reposition express nose-forward,
                // which is otherwise only reachable by uploading a one-item plan. The arrival heading
                // is then the heading the aircraft already holds: "do not change yaw" cannot mean
                // "finish by rotating to north", which is what a hardcoded zero asked for.
                // param7 NaN means "keep the altitude you are at", the same "leave this alone" that
                // the other three parameters express. Flown as a setpoint it is not refused: every
                // comparison against NaN is false, so the aircraft never reaches the altitude and
                // never reports arriving.
                val altitude = altitudeMeters.takeIf { it.isFinite() } ?: aircraftTelemetry.getLocation3D().altitude
                val seq =
                    if (yawDeg.isNaN()) {
                        DroneController.flyToWaypointNoseForward(
                            latitudeDeg,
                            longitudeDeg,
                            altitude,
                            aircraftTelemetry.getHeading(),
                            speed,
                        )
                    } else {
                        DroneController.flyToWaypointHoldHeading(
                            latitudeDeg,
                            longitudeDeg,
                            altitude,
                            yawDeg,
                            speed,
                        )
                    }
                // A refused leg is a refusal, not a pending flight: the controller published the
                // seq exactly so a caller correlating on it can tell the two apart. Answering
                // ACCEPTED here is what made MAVLink and HTTP disagree about the same command —
                // HTTP inspects this same refusal, the reposition path did not — and left a ground
                // station awaiting a leg the aircraft never started flying.
                val refusal = DroneController.lastWaypointRefusal()
                if (refusal?.seq == seq && refusal.reason != DroneController.WaypointRejection.NONE) {
                    return CommandResult(
                        MavlinkCommandOutcome.DENIED,
                        "Waypoint refused: ${refusal.reason}",
                    )
                }
                return CommandResult(
                    MavlinkCommandOutcome.ACCEPTED,
                    pending = PendingCommand(PendingKind.WAYPOINT, seq),
                )
            }

            override fun setYaw(yawDeg: Double): CommandResult {
                mavlinkFlightGate()?.let { return it }
                if (DroneController.shouldRejectAutonomousCommand("yaw")) {
                    return CommandResult(MavlinkCommandOutcome.DENIED)
                }
                supersedeMission("yaw")
                val seq = DroneController.gotoYaw(yawDeg)
                return CommandResult(
                    MavlinkCommandOutcome.ACCEPTED,
                    pending = PendingCommand(PendingKind.YAW, seq),
                )
            }

            @Suppress("LongParameterList")
            override fun orbit(
                latitudeDeg: Double,
                longitudeDeg: Double,
                altitudeMeters: Double,
                radiusMeters: Double,
                tangentialSpeedMps: Double,
                clockwise: Boolean,
                arcDegrees: Double,
                faceCentre: Boolean,
            ): CommandResult {
                mavlinkFlightGate()?.let { return it }
                if (DroneController.shouldRejectAutonomousCommand("orbit")) {
                    return CommandResult(MavlinkCommandOutcome.DENIED)
                }
                if (!latitudeDeg.isFinite() || !longitudeDeg.isFinite()) {
                    return CommandResult(MavlinkCommandOutcome.DENIED, "Orbit needs a real centre")
                }
                // A radius of zero is a rotation in place dressed as an orbit, and the radial term
                // would divide the aircraft's own position noise by nothing to correct it.
                if (radiusMeters < MIN_ORBIT_RADIUS_M) {
                    return CommandResult(
                        MavlinkCommandOutcome.DENIED,
                        "Orbit radius must be at least $MIN_ORBIT_RADIUS_M m",
                    )
                }
                supersedeMission("orbit")
                DroneController.orbit(
                    centreLatitude = latitudeDeg,
                    centreLongitude = longitudeDeg,
                    // As everywhere else on this surface, an unset altitude is the one being held.
                    targetAltitude =
                        altitudeMeters.takeIf { it.isFinite() }
                            ?: aircraftTelemetry.getLocation3D().altitude,
                    radiusMeters = radiusMeters,
                    tangentialSpeedMps = tangentialSpeedMps,
                    clockwise = clockwise,
                    arcDegrees = arcDegrees,
                    faceCentre = faceCentre,
                )
                // Acknowledged immediately rather than held pending until the lap completes: a full
                // orbit takes minutes, and QGroundControl's orbit tool re-issues DO_ORBIT as its
                // parameters change — a pending ack makes it refuse with "Waiting on previous
                // response to same command" for the whole lap. The orbit still finishes on its own;
                // completion is reported on the telemetry stream, not by blocking the next command.
                return CommandResult(MavlinkCommandOutcome.ACCEPTED)
            }

            override fun abortToPositionHold(): CommandResult {
                mavlinkFlightGate()?.let { return it }
                if (DroneController.shouldRejectAutonomousCommand("abort")) {
                    return CommandResult(MavlinkCommandOutcome.DENIED)
                }
                supersedeMission("abort")
                // The union of the three HTTP aborts: stop the PID loops, neutralise the sticks and
                // leave virtual stick, and end any DJI wayline. Each is safe when nothing is running.
                DroneController.abortAllMissions()
                DroneController.setStick(0f, 0f, 0f, 0f)
                DroneController.disableVirtualStick()
                runCatching { DroneController.endMission() }
                return CommandResult(MavlinkCommandOutcome.ACCEPTED)
            }

            override fun enableOffboard(): CommandResult {
                mavlinkFlightGate()?.let { return it }
                if (DroneController.shouldRejectAutonomousCommand("enableVirtualStick")) {
                    return CommandResult(MavlinkCommandOutcome.DENIED)
                }
                DroneController.enableVirtualStick()
                return CommandResult(MavlinkCommandOutcome.ACCEPTED)
            }

            override fun manualControl(
                roll: Float,
                pitch: Float,
                throttle: Float,
                yaw: Float,
            ): CommandResult {
                mavlinkFlightGate()?.let { return it }
                // Refused while the pilot has the sticks, exactly as /send/stick is. The latch
                // already drops virtual stick, so these would most likely be ignored anyway — but
                // "most likely ignored" is not the guarantee to rely on when the pilot has taken
                // over, and the two surfaces disagreeing about it is its own bug.
                if (DroneController.shouldRejectAutonomousCommand("stick")) {
                    return CommandResult(MavlinkCommandOutcome.DENIED)
                }
                // DJI's sticks: left is yaw/throttle, right is roll/pitch. MAVLink's axes are named
                // for what they do, so the mapping is by meaning rather than by position.
                DroneController.setStick(
                    leftX = yaw,
                    leftY = throttle,
                    rightX = roll,
                    rightY = pitch,
                )
                return CommandResult(MavlinkCommandOutcome.ACCEPTED)
            }

            override fun setAltitude(altitudeMeters: Double): CommandResult {
                mavlinkFlightGate()?.let { return it }
                if (DroneController.shouldRejectAutonomousCommand("altitude")) {
                    return CommandResult(MavlinkCommandOutcome.DENIED)
                }
                supersedeMission("altitude change")
                val seq = DroneController.gotoAltitude(altitudeMeters)
                return CommandResult(
                    MavlinkCommandOutcome.ACCEPTED,
                    pending = PendingCommand(PendingKind.ALTITUDE, seq),
                )
            }

            override fun releaseManualOverride(): CommandResult {
                // Deliberately not behind the flight gate: this grants authority rather than using
                // it, and the commands it re-enables are each gated in their own right.
                DroneController.deactivateManualOverride()
                return CommandResult(MavlinkCommandOutcome.ACCEPTED)
            }

            override fun releaseSafetyControl(): CommandResult {
                // Deliberately not behind the flight gate: this is the operation that returns
                // authority to the Pilot, so it must be reachable precisely while the Safety
                // Computer holds control (the gate would refuse everything once SAFETY seized it).
                // Only a frame signed with the configured key may release — an unsigned frame is
                // the Pilot, and the Pilot cannot release safety. HTTP's /releaseSafetyControl has
                // the same rule, enforced with its X-Safety-Token header instead.
                val source =
                    if (mavlinkEndpoint?.isTrustedOrigin == true) {
                        ControlAuthority.Source.SAFETY
                    } else {
                        ControlAuthority.Source.PILOT
                    }
                return if (ControlAuthority.releaseSafetyControl(source)) {
                    CommandResult(MavlinkCommandOutcome.ACCEPTED)
                } else {
                    CommandResult(
                        MavlinkCommandOutcome.DENIED,
                        "Only the Safety Computer can release safety control",
                    )
                }
            }

            /**
             * Whether the movement with this seq has arrived.
             *
             * The seq comparison is what makes the answer trustworthy: the latch is a single shared
             * flag, so without it a leftover `true` from the previous movement reads as this one
             * arriving instantly. A manual override is reported as a failure rather than as a wait,
             * because the command is not going to complete once the pilot has the sticks.
             */
            override fun pollCompletion(pending: PendingCommand): CommandProgress {
                if (DroneController.isManualOverrideActive) return CommandProgress.ABANDONED
                // A refused waypoint is a refusal the moment it is detected, not a command that
                // runs until somebody gives up waiting: the leg was never issued to the airframe,
                // so no reach latch will ever close for it.
                if (pending.kind == PendingKind.WAYPOINT) {
                    val refusal = DroneController.lastWaypointRefusal()
                    if (refusal?.seq == pending.seq &&
                        refusal.reason != DroneController.WaypointRejection.NONE
                    ) {
                        return CommandProgress.ABANDONED
                    }
                }
                val (currentSeq, reached) =
                    when (pending.kind) {
                        PendingKind.WAYPOINT ->
                            DroneController.getWaypointSeq() to DroneController.isWaypointReached()
                        PendingKind.YAW ->
                            DroneController.getYawSeq() to DroneController.isYawReached()
                        PendingKind.ALTITUDE ->
                            DroneController.getAltitudeSeq() to DroneController.isAltitudeReached()
                        PendingKind.ORBIT ->
                            DroneController.getOrbitSeq() to DroneController.isOrbitComplete()
                    }
                return when {
                    // A newer command took over. Ordinary, not a failure: this is what re-issuing a
                    // goto looks like from the perspective of the one it replaced.
                    currentSeq > pending.seq -> CommandProgress.SUPERSEDED
                    currentSeq == pending.seq && reached -> CommandProgress.ARRIVED
                    else -> CommandProgress.RUNNING
                }
            }

            override fun arm(): CommandResult {
                // DJI has no arming: motors spin up when the takeoff command actually runs. QGC's
                // takeoff sequence arms right after NAV_TAKEOFF is accepted, so this is a gated no-op
                // that keeps the sequence moving rather than an honest refusal that aborts it. The
                // heartbeat reports armed from [armedCommanded] so QGC's arm wait sees a result.
                mavlinkFlightGate()?.let { return it }
                if (DroneController.shouldRejectAutonomousCommand("arm")) {
                    return CommandResult(MavlinkCommandOutcome.DENIED)
                }
                armedCommanded = true
                Log.i(TAG, "Vehicle armed (commanded; DJI has no arming state)")
                return CommandResult(MavlinkCommandOutcome.ACCEPTED)
            }

            override fun disarm(): CommandResult {
                mavlinkFlightGate()?.let { return it }
                if (DroneController.shouldRejectAutonomousCommand("disarm")) {
                    return CommandResult(MavlinkCommandOutcome.DENIED)
                }
                armedCommanded = false
                Log.i(TAG, "Vehicle disarmed (commanded)")
                return CommandResult(MavlinkCommandOutcome.ACCEPTED)
            }
        }

    /**
     * Climb to a requested altitude once the take-off has finished.
     *
     * DJI's take-off takes no height, so an altitude asked for in `MAV_CMD_NAV_TAKEOFF` has to be
     * reached by a second movement afterwards. Waiting matters: issuing the climb while the
     * aircraft is still in its take-off sequence would have the altitude loop fight DJI for the
     * sticks, so this waits for the aircraft to report itself flying and out of the TAKING_OFF
     * state before starting.
     *
     * Runs on the capture worker rather than the endpoint's receive thread, and gives up rather
     * than climbing late if the take-off never completes — a climb that begins minutes afterwards
     * would be a surprise, not a service.
     */
    private fun climbAfterTakeoff(altitudeMeters: Double) {
        captureExecutor.execute {
            val deadline = System.currentTimeMillis() + TAKEOFF_CLIMB_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                val airborne =
                    aircraftTelemetry.readState().readings.flying &&
                        DroneController.droneStatus != DroneController.DroneStatus.TAKING_OFF
                if (airborne) {
                    Log.i(TAG, "Take-off complete; climbing to ${altitudeMeters}m")
                    mainHandler.post { DroneController.gotoAltitude(altitudeMeters) }
                    return@execute
                }
                runCatching { Thread.sleep(TAKEOFF_POLL_MS) }.onFailure {
                    Thread.currentThread().interrupt()
                    return@execute
                }
            }
            Log.w(TAG, "Take-off did not complete in time; not climbing to ${altitudeMeters}m")
        }
    }

    /**
     * Flies an uploaded plan.
     *
     * The onboard executor is the interesting half. Until now the sequencing lived on the ground
     * station: it sent one waypoint, watched the reach latch, and sent the next — which is why
     * the seq-tracked reach flags exist at all. MAVLink expects the vehicle to own that state,
     * because MISSION_CURRENT and MISSION_ITEM_REACHED come from the aircraft, so this moves the
     * loop into the app.
     *
     * Each item picks its own controller through param4: NaN means fly nose-forward, a value
     * means hold that heading. One plan can mix them, which the two separate HTTP endpoints
     * cannot express.
     */
    private val mavlinkMissionSink =
        object : MavlinkMissionSink {
            private var listener: MissionProgressListener? = null

            @Volatile
            private var missionThread: Thread? = null

            @Volatile
            private var running = false

            /**
             * Distance-triggered capture state for the plan being flown, or null when the plan set
             * no trigger distance. [CMD_DO_SET_CAM_TRIGG_DIST] arms it; the accumulate-and-compare
             * arithmetic lives in [DistanceTrigger], and the fix it last advanced from is the anchor
             * below.
             */
            private var distanceTrigger: DistanceTrigger? = null

            /** The fix [distanceTrigger] last measured from, so consecutive fixes make a path. */
            private var triggerAnchorLat: Double? = null
            private var triggerAnchorLon: Double? = null

            override val isRunning: Boolean get() = running

            override fun setProgressListener(listener: MissionProgressListener?) {
                this.listener = listener
            }

            override fun startMission(
                items: List<MissionItem>,
                startIndex: Int,
                executor: MissionExecutor,
            ): CommandResult {
                mavlinkFlightGate()?.let { return it }
                // A plan is an autonomous command like any other. The sequencer aborts on the first
                // leg if the latch is set, but refusing it here says so plainly rather than
                // accepting a mission that is going to stop immediately.
                if (DroneController.shouldRejectAutonomousCommand("mission")) {
                    return CommandResult(MavlinkCommandOutcome.DENIED)
                }
                // Idempotent: QGC enters mission mode with SET_MODE(MISSION) and then sends
                // MISSION_START, so a running plan must not be stopped and restarted by the second
                // command of the pair.
                if (running) return CommandResult(MavlinkCommandOutcome.ACCEPTED)
                stopMission()
                return when (executor) {
                    MissionExecutor.DJI_NATIVE -> startNative(items)
                    MissionExecutor.ONBOARD -> startOnboard(items, startIndex)
                }
            }

            /**
             * Hand the whole list to DJI's wayline engine.
             *
             * DJI's own take-off (to [WaylineMissionHelper]'s `securityTakeOffHeight`) and its wayline
             * action framework mean this carries much more of a plan than a bare waypoint path: a
             * leading NAV_TAKEOFF's altitude becomes the take-off height, a trailing LAND/RTL becomes
             * the mission's finish action, DO_CHANGE_SPEED becomes a per-leg [WaylineWaypoint.speed],
             * param4 becomes a fixed heading, and camera/gimbal items become wayline actions
             * (translated by [translatePlanActionToWaylineAction]) triggered at the waypoint they sit
             * after — the same "takes effect where it sits" semantics [executePlanAction] uses.
             *
             * `DO_SET_ROI`/`DO_SET_ROI_LOCATION` are compiled rather than dropped: MAVLink's ROI is
             * modal (it stays in force until `DO_SET_ROI_NONE` or a non-location `DO_SET_ROI`), so
             * `currentRoi` below is carried across items the same way and stamped onto every waypoint
             * model built while it is active, driving DJI's own `TOWARD_POI` yaw and gimbal modes —
             * see [WaylineMissionHelper.createWaypointFromLatLon]. `CMD_SET_CAMERA_MODE` remains
             * skipped; DJI's wayline engine has no camera-mode concept.
             */
            private fun startNative(items: List<MissionItem>): CommandResult {
                var speed =
                    items.firstNotNullOfOrNull { it.speedMps }
                        ?: DroneControlProfiles.activeProfile().defaultCruiseSpeedMps
                var currentRoi: WaylineLocationCoordinate3D? = null
                // Modal, exactly like the ROI above: a trigger distance set by one item stays in
                // force for every waypoint built after it, until another item changes or clears it.
                var currentDistanceTriggerM: Double? = null
                val pendingActions = mutableListOf<WaylineActionInfo>()
                val waypointModels = mutableListOf<WaypointInfoModel>()
                for (item in items) {
                    if (item.isWaypoint) {
                        val heading = if (item.noseForward) null else item.param4.toDouble()
                        waypointModels.add(
                            WaylineMissionHelper
                                .createWaypointFromLatLon(
                                    item.latitudeDeg,
                                    item.longitudeDeg,
                                    item.altitudeM,
                                    waypointModels.size,
                                    headingDeg = heading,
                                    speedMps = speed,
                                    roiTarget = currentRoi,
                                    extraActions = pendingActions.toList(),
                                ).apply {
                                    distanceIntervalMeters = currentDistanceTriggerM
                                },
                        )
                        pendingActions.clear()
                        continue
                    }
                    item.speedMps?.let { speed = it }
                    when (item.command) {
                        Mav.CMD_DO_SET_ROI_LOCATION ->
                            currentRoi = WaylineLocationCoordinate3D(item.latitudeDeg, item.longitudeDeg, item.altitudeM)
                        Mav.CMD_DO_SET_ROI_NONE -> currentRoi = null
                        Mav.CMD_DO_SET_ROI ->
                            currentRoi =
                                if (item.param1.toInt() == Mav.ROI_MODE_LOCATION) {
                                    WaylineLocationCoordinate3D(item.latitudeDeg, item.longitudeDeg, item.altitudeM)
                                } else {
                                    null
                                }
                        // A zero or negative distance is MAVLink's way of turning the trigger off;
                        // WPML expresses the same thing by the waypoint carrying no interval.
                        Mav.CMD_DO_SET_CAM_TRIGG_DIST ->
                            currentDistanceTriggerM = item.param1.toDouble().takeIf { it > 0.0 }
                        else -> translatePlanActionToWaylineAction(item)?.let { pendingActions.add(it) }
                    }
                }
                if (waypointModels.size < 2) {
                    // DJI's wayline engine needs a path, not a point.
                    return CommandResult(
                        MavlinkCommandOutcome.DENIED,
                        "DJI native missions need at least two waypoints",
                    )
                }
                // Actions after the last leg (e.g. a final photo before landing) have no later
                // waypoint to attach to, so they ride along with the last one instead of being lost.
                if (pendingActions.isNotEmpty()) {
                    val last = waypointModels.last()
                    last.actionInfos = ArrayList(last.actionInfos + pendingActions)
                }

                val finishAction =
                    when (
                        items
                            .lastOrNull {
                                it.command == Mav.CMD_NAV_LAND || it.command == Mav.CMD_NAV_RETURN_TO_LAUNCH
                            }?.command
                    ) {
                        Mav.CMD_NAV_LAND -> WaylineFinishedAction.AUTO_LAND
                        Mav.CMD_NAV_RETURN_TO_LAUNCH -> WaylineFinishedAction.GO_HOME
                        else -> WaylineFinishedAction.NO_ACTION
                    }
                val takeoffHeightM =
                    items
                        .firstOrNull { it.command == Mav.CMD_NAV_TAKEOFF }
                        ?.altitudeM
                        ?.takeIf { it > 0.0 } ?: 20.0
                val missionConfig =
                    WaylineMissionHelper.createMissionConfig(
                        finishAction = finishAction,
                        securityTakeOffHeightM = takeoffHeightM,
                    )

                running = true
                // Distinct from NAVIGATING: DJI's own wayline engine is flying this, not the app's
                // virtual-stick loop, and the status badge showing MANUAL for a mission that is
                // flying perfectly fine was ambient RC stick noise being read as a takeover — see the
                // MISSION exclusion in VirtualStickVM.tryUpdateVirtualStickByRc().
                DroneController.markMissionActive()
                listener?.onItemStarted(0)
                DroneController.navigateWaylineMissionNative(
                    waypointModels,
                    missionConfig,
                    speed,
                    onProgress = { waypointIndex -> listener?.onItemStarted(waypointIndex) },
                    onFinished = { success ->
                        running = false
                        DroneController.clearMissionActiveIfStillSet()
                        listener?.onMissionFinished(success)
                    },
                )
                return CommandResult(MavlinkCommandOutcome.ACCEPTED)
            }

            /**
             * Translate one non-waypoint, non-ROI plan item into the DJI wayline action it maps to,
             * or null when there is none. ROI items are handled separately in [startNative], since
             * they set waypoint-level yaw/gimbal state rather than a one-shot triggered action;
             * [Mav.CMD_SET_CAMERA_MODE] has no wayline equivalent and is the one item still skipped
             * outright.
             */
            private fun translatePlanActionToWaylineAction(item: MissionItem): WaylineActionInfo? =
                when (item.command) {
                    Mav.CMD_IMAGE_START_CAPTURE ->
                        WaylineActionInfo().apply {
                            actionType = WaylineActionType.TAKE_PHOTO
                            takePhotoParam = ActionTakePhotoParam().apply { payloadPositionIndex = 0 }
                        }
                    Mav.CMD_VIDEO_START_CAPTURE ->
                        WaylineActionInfo().apply {
                            actionType = WaylineActionType.START_RECORD
                            startRecordParam = ActionStartRecordParam().apply { payloadPositionIndex = 0 }
                        }
                    Mav.CMD_VIDEO_STOP_CAPTURE ->
                        WaylineActionInfo().apply {
                            actionType = WaylineActionType.STOP_RECORD
                            stopRecordParam = ActionStopRecordParam().apply { payloadPositionIndex = 0 }
                        }
                    Mav.CMD_DO_GIMBAL_MANAGER_PITCHYAW ->
                        WaylineActionInfo().apply {
                            actionType = WaylineActionType.GIMBAL_ROTATE
                            gimbalRotateParam =
                                ActionGimbalRotateParam().apply {
                                    payloadPositionIndex = 0
                                    rotateMode = WaylineGimbalActuatorRotateMode.ABSOLUTE_ANGLE
                                    enablePitch = item.param1.isFinite()
                                    pitch = item.param1.toDouble()
                                    enableYaw = item.param2.isFinite()
                                    yaw = item.param2.toDouble()
                                }
                        }
                    else -> null
                }

            /**
             * Sequence the items ourselves, one waypoint at a time.
             *
             * Runs on its own thread because it waits: each leg is issued, then the reach latch is
             * polled until the matching seq reports arrival. Comparing the seq rather than just the
             * boolean is what stops a stale latch from a previous leg being read as this one's
             * arrival — the same reason the seq mechanism exists on the HTTP surface.
             */
            private fun startOnboard(
                items: List<MissionItem>,
                startIndex: Int,
            ): CommandResult {
                val lastLegIndex = items.indexOfLast { it.isWaypoint }
                if (lastLegIndex < 0) {
                    return CommandResult(MavlinkCommandOutcome.DENIED, "No waypoints in plan")
                }
                var speed =
                    items.firstNotNullOfOrNull { it.speedMps }
                        ?: DroneControlProfiles.activeProfile().defaultCruiseSpeedMps

                // A fresh plan means a fresh trigger state: the armed distance and the accumulated
                // ground both belong to whichever plan set them, not to a previously flown one.
                distanceTrigger = null
                triggerAnchorLat = null
                triggerAnchorLon = null
                running = true
                missionThread =
                    thread(name = "MavlinkMission", start = true) {
                        // Every item in order, not only the waypoints. Walking the waypoints alone meant
                        // a plan's camera and gimbal actions were carried through the upload and then
                        // silently dropped, so a survey flew the right path and photographed nothing.
                        for ((index, item) in items.withIndex()) {
                            if (!running) break
                            if (index < startIndex) continue

                            if (!item.isWaypoint) {
                                // Actions take effect where they sit in the plan and do not block: their
                                // whole purpose is to be in force for the legs that follow.
                                item.speedMps?.let { speed = it }
                                listener?.onItemStarted(index)
                                if (!executePlanAction(item)) {
                                    listener?.onMissionFinished(false)
                                    running = false
                                    return@thread
                                }
                                listener?.onItemReached(index)
                                continue
                            }

                            listener?.onItemStarted(index)
                            val seq = flyLeg(item, speed, isLast = index == lastLegIndex)
                            if (!awaitLeg(seq)) {
                                // Interrupted, overridden, or timed out — stop rather than skipping on.
                                listener?.onMissionFinished(false)
                                running = false
                                return@thread
                            }
                            listener?.onItemReached(index)
                        }
                        listener?.onMissionFinished(running)
                        running = false
                    }
                return CommandResult(MavlinkCommandOutcome.ACCEPTED)
            }

            /**
             * Carry out a plan item that is not a leg.
             *
             * Returns false only for the items that end the plan — a land or a return has nothing
             * after it, and continuing to the next waypoint would fly away from a descent already
             * under way. A payload action that fails is logged and the plan carries on: a camera that
             * will not switch mode is a worse photograph, not a reason to abandon a survey mid-air.
             */
            private fun executePlanAction(item: MissionItem): Boolean {
                when (item.command) {
                    Mav.CMD_SET_CAMERA_MODE -> setCameraMode(item.param2.toInt())
                    Mav.CMD_IMAGE_START_CAPTURE -> mavlinkCommandSink.captureImage()
                    Mav.CMD_VIDEO_START_CAPTURE -> mavlinkCommandSink.startVideoRecording()
                    Mav.CMD_VIDEO_STOP_CAPTURE -> mavlinkCommandSink.stopVideoRecording()
                    Mav.CMD_DO_GIMBAL_MANAGER_PITCHYAW ->
                        mavlinkCommandSink.setGimbal(
                            GimbalRotation(
                                mode = GimbalRotationMode.ABSOLUTE,
                                pitchDeg = item.param1.toDouble(),
                                rollDeg = 0.0,
                                yawDeg = item.param2.toDouble(),
                                pitchIgnored = !item.param1.isFinite(),
                                rollIgnored = true,
                                yawIgnored = !item.param2.isFinite(),
                            ),
                        )
                    // The legacy mount-control command: pitch is param1, yaw is param3 (param2 is
                    // roll, which no DJI gimbal here supports).
                    Mav.CMD_DO_MOUNT_CONTROL ->
                        mavlinkCommandSink.setGimbal(
                            GimbalRotation(
                                mode = GimbalRotationMode.ABSOLUTE,
                                pitchDeg = item.param1.toDouble(),
                                rollDeg = 0.0,
                                yawDeg = item.param3.toDouble(),
                                pitchIgnored = !item.param1.isFinite(),
                                rollIgnored = true,
                                yawIgnored = !item.param3.isFinite(),
                            ),
                        )
                    // Arm the distance-triggered capture loop. A zero or negative distance is the
                    // MAVLink way of turning it off again, so it disarms rather than being ignored.
                    Mav.CMD_DO_SET_CAM_TRIGG_DIST -> {
                        val intervalM = item.param1.toDouble()
                        if (intervalM > 0.0) {
                            distanceTrigger = DistanceTrigger(intervalM)
                            Log.i(TAG, "Distance-triggered capture armed: one photo every ${intervalM}m")
                        } else {
                            distanceTrigger = null
                            Log.i(TAG, "Distance-triggered capture disarmed")
                        }
                        Unit
                    } Mav.CMD_DO_SET_ROI_LOCATION ->
                        mavlinkCommandSink.setRegionOfInterest(
                            item.latitudeDeg,
                            item.longitudeDeg,
                            item.altitudeM,
                        )
                    Mav.CMD_DO_SET_ROI_NONE -> mavlinkCommandSink.clearRegionOfInterest()
                    Mav.CMD_DO_SET_ROI ->
                        if (item.param1.toInt() == Mav.ROI_MODE_LOCATION) {
                            mavlinkCommandSink.setRegionOfInterest(
                                item.latitudeDeg,
                                item.longitudeDeg,
                                item.altitudeM,
                            )
                        } else {
                            mavlinkCommandSink.clearRegionOfInterest()
                        }
                    Mav.CMD_NAV_TAKEOFF -> {
                        mavlinkMotionSink.takeoff(item.altitudeM.toFloat().takeIf { it > 0f })
                        if (!awaitAirborne()) {
                            Log.w(TAG, "Take-off did not complete in time; aborting mission")
                            return false
                        }
                    }
                    Mav.CMD_NAV_LAND -> {
                        mavlinkMotionSink.land()
                        return false
                    }
                    Mav.CMD_NAV_RETURN_TO_LAUNCH -> {
                        mavlinkMotionSink.returnToHome()
                        return false
                    }
                    // A speed change has already been folded into the running speed above; there is
                    // nothing else to do with it.
                    else -> Log.d(TAG, "Plan item ${item.command} has no action")
                }
                return true
            }

            /**
             * Issue one leg with the controller its param4 asks for, returning the command's seq.
             *
             * The arrival criteria travel with it. Without them every leg is treated as a
             * destination, so a plan is flown as a series of stops rather than as a trajectory —
             * which is what the aircraft did before it read param1 and param2.
             */
            private fun flyLeg(
                item: MissionItem,
                speed: Double,
                isLast: Boolean,
            ): Long {
                val yaw = if (item.noseForward) 0.0 else item.param4.toDouble()
                val arrival =
                    DroneController.WaypointArrival(
                        acceptanceRadiusM = item.acceptanceRadiusM,
                        holdSeconds = item.holdSeconds,
                        // The final leg is never a pass-through, whatever the plan says: there is nothing
                        // after it to fly on to, so the aircraft settles there.
                        passThrough = item.passThrough && !isLast,
                    )
                return if (item.noseForward) {
                    DroneController.flyToWaypointNoseForward(
                        item.latitudeDeg,
                        item.longitudeDeg,
                        item.altitudeM,
                        yaw,
                        speed,
                        arrival,
                    )
                } else {
                    DroneController.flyToWaypointHoldHeading(
                        item.latitudeDeg,
                        item.longitudeDeg,
                        item.altitudeM,
                        yaw,
                        speed,
                        arrival,
                    )
                }
            }

            /**
             * Block until a take-off just commanded has actually left the ground.
             *
             * [executePlanAction] returns as soon as [MavlinkMotionSink.takeoff] is issued, because
             * DJI's own take-off climb is asynchronous. Without this wait the sequencer moved
             * straight on to the first waypoint while the aircraft was still in its take-off
             * sequence, which had the waypoint controller fight DJI for the sticks and left
             * [awaitLeg] polling a seq the aircraft was never going to report — the mission looked
             * stalled rather than flown. Uses the same airborne test as [climbAfterTakeoff], since it
             * is the same transition being waited for.
             */
            private fun awaitAirborne(): Boolean {
                val deadline = System.currentTimeMillis() + TAKEOFF_CLIMB_TIMEOUT_MS
                while (running && System.currentTimeMillis() < deadline) {
                    val airborne =
                        aircraftTelemetry.readState().readings.flying &&
                            DroneController.droneStatus != DroneController.DroneStatus.TAKING_OFF
                    if (airborne) return true
                    runCatching { Thread.sleep(TAKEOFF_POLL_MS) }.onFailure {
                        Thread.currentThread().interrupt()
                        return false
                    }
                }
                return false
            }

            /** Wait for the leg with this seq to report reached. False if it did not. */
            private fun awaitLeg(seq: Long): Boolean {
                val deadline = System.currentTimeMillis() + MISSION_LEG_TIMEOUT_MS
                while (running && System.currentTimeMillis() < deadline) {
                    if (DroneController.isManualOverrideActive) return false
                    // A refused leg was never going to be flown, so waiting can only end at the
                    // timeout. The obstacle guard publishes the refusal with the same seq the leg
                    // was issued under, which is exactly what makes the two matchable here.
                    val refusal = DroneController.lastWaypointRefusal()
                    if (refusal?.seq == seq && refusal.reason != DroneController.WaypointRejection.NONE) {
                        Log.w(TAG, "Mission leg seq=$seq refused (${refusal.reason}); stopping plan")
                        return false
                    }
                    if (DroneController.getWaypointSeq() == seq && DroneController.isWaypointReached()) {
                        return true
                    }
                    maybeCaptureByDistance()
                    runCatching { Thread.sleep(MISSION_POLL_MS) }.onFailure {
                        Thread.currentThread().interrupt()
                        return false
                    }
                }
                return false
            }

            /**
             * Trip the shutter when the distance trigger's accumulated ground has crossed another
             * interval.
             *
             * Called from the leg-wait poll, so it advances at that cadence (5 Hz) rather than
             * exactly on the crossing; a survey's intervals are metres apart, so the error is well
             * under a metre at the speeds a mapping flight flies. Ground truth is the aircraft's
             * own reported position — the same source navigation uses, so a photo and a leg's
             * arrival can never disagree about where the aircraft was.
             *
             * The first fix only anchors: there is nothing yet to have travelled from, and the
             * SDK's unset (0, 0) is a real place in the Atlantic rather than a distance of zero.
             */
            private fun maybeCaptureByDistance() {
                val trigger = distanceTrigger ?: return
                val location = aircraftTelemetry.getLocation3D()
                val lastLat = triggerAnchorLat
                val lastLon = triggerAnchorLon
                if (lastLat == null ||
                    lastLon == null ||
                    (location.latitude == 0.0 && location.longitude == 0.0)
                ) {
                    triggerAnchorLat = location.latitude
                    triggerAnchorLon = location.longitude
                    return
                }
                val travelled =
                    DroneController.calculateDistance(
                        lastLat,
                        lastLon,
                        location.latitude,
                        location.longitude,
                    )
                triggerAnchorLat = location.latitude
                triggerAnchorLon = location.longitude
                if (trigger.addTravelled(travelled)) {
                    Log.i(TAG, "Distance trigger: capturing (${"%.1f".format(travelled)}m since last fix)")
                    mavlinkCommandSink.captureImage()
                }
            }

            override fun stopMission(): CommandResult {
                running = false
                missionThread?.interrupt()
                missionThread = null
                mainHandler.post { DroneController.abortAllMissions() }
                return CommandResult(MavlinkCommandOutcome.ACCEPTED)
            }
        }

    private fun startMavlinkEndpoint() {
        val config = readMavlinkConfig()
        if (!config.enabled) {
            Log.i(TAG, "MAVLink endpoint disabled (${MavlinkEndpointConfig.PREF_ENABLED}=false)")
            return
        }
        runCatching {
            val ftpServer =
                MavlinkFtpServer(
                    object : MavlinkFtpServer.FtpFileSource {
                        override fun listFiles(): List<Pair<String, Long>> = Payload.listMediaFiles(mediaVM)

                        override fun readFileBytes(name: String): ByteArray? = Payload.downloadMediaBytes(mediaVM, name)
                    },
                    ftpExecutor,
                )
            val endpoint =
                MavlinkTelemetryEndpoint(
                    config,
                    ::buildMavlinkSnapshot,
                    ::currentMavlinkVideoStream,
                    ::mavlinkParameters,
                    mavlinkCommandSink,
                    mavlinkMotionSink,
                    mavlinkMissionSink,
                    ftpServer,
                    commandLog = { command, result ->
                        LyrebirdFlightLogger.logMavlinkCommand(
                            command = command.command,
                            params =
                                listOf(
                                    command.param1,
                                    command.param2,
                                    command.param3,
                                    command.param4,
                                    command.param5,
                                    command.param6,
                                    command.param7,
                                ),
                            result = result.mavResult,
                            signed = mavlinkEndpoint?.isTrustedOrigin == true,
                            senderSystem = command.senderSystem,
                        )
                    },
                )
            endpoint.onPeerDiscovered = { peer ->
                Log.i(TAG, "MAVLink ground station at $peer")
                // A MAVLink ground station appearing is the same event as the first TCP
                // telemetry client connecting, and it has to start the video the same way.
                // Without this the WHIP publish only ever begins when something connects to the
                // telemetry port, so a purely MAVLink ground station gets full telemetry and no
                // picture — which is what a field test found.
                val peerIp = peer.substringBefore(':')
                if (peerIp.isNotBlank()) {
                    mainHandler.post { startStreamingForClient(peerIp) }
                }
            }
            // Only claim the endpoint once it actually holds its socket: the status line and the
            // command log both read this field, and a taken UDP port must not read as MAVLink up.
            if (endpoint.start()) {
                mavlinkEndpoint = endpoint
                mavlinkFtpServer = ftpServer
            } else {
                Log.w(TAG, "MAVLink endpoint did not start; UDP ${config.listenPort} is not ours")
            }
        }.onFailure { error ->
            Log.e(TAG, "Error starting MAVLink endpoint: ${error.message}", error)
        }
    }

    private fun restartMavlinkEndpoint() {
        mainHandler.post {
            mavlinkEndpoint?.stop()
            mavlinkEndpoint = null
            mavlinkFtpServer?.shutdown()
            mavlinkFtpServer = null
            if (!isDestroyed && !isFinishing) startMavlinkEndpoint()
        }
    }

    private fun rebuildRealTelemetryCache() {
        val readings = aircraftTelemetry.readState().readings
        readings.applyTo(telemetryCoordinator)
        val location = readings.location
        val homeLocation = readings.home
        // Zero until home is a real place. DJI reports (0, 0) before it has a home point, and
        // that is a real spot in the Atlantic: measuring to it produced a confident 2,559 km
        // from a stationary aircraft, which is worse than reporting nothing because it looks
        // like an answer.
        telemetryCoordinator.distanceToHome =
            if (
                hasRealHomeCoordinates(homeLocation.latitudeDeg, homeLocation.longitudeDeg)
            ) {
                DroneController.calculateDistance(
                    location.latitudeDeg,
                    location.longitudeDeg,
                    homeLocation.latitudeDeg,
                    homeLocation.longitudeDeg,
                )
            } else {
                0.0
            }
        telemetryCoordinator.waypointReached = DroneController.isWaypointReached()
        telemetryCoordinator.intermediaryWaypointReached = DroneController.isIntermediaryWaypointReached()
        telemetryCoordinator.yawReached = DroneController.isYawReached()
        telemetryCoordinator.altitudeReached = DroneController.isAltitudeReached()
        telemetryCoordinator.homeSet = isHomeSet()
        telemetryCoordinator.waypointSeq = DroneController.getWaypointSeq()
        telemetryCoordinator.yawSeq = DroneController.getYawSeq()
        telemetryCoordinator.altitudeSeq = DroneController.getAltitudeSeq()
        // A laser fix is a place on the globe, not a place relative to take-off, so it maps to the
        // three-field point rather than to the aircraft's own position type.
        telemetryCoordinator.lrfTarget =
            lrfTargetLocation?.let {
                GeoPoint3D(
                    latitudeDeg = it.latitude,
                    longitudeDeg = it.longitude,
                    altitudeM = it.altitude,
                )
            }
        telemetryCoordinator.isManualOverrideActive = DroneController.isManualOverrideActive
        telemetryCoordinator.isAutoSensingActive = isAutoSensingActive
    }

    // ==================== HTTP Server ====================
}
