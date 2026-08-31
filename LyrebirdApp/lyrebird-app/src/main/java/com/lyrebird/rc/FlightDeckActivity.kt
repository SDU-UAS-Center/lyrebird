package com.lyrebird.rc

import android.app.Dialog
import android.content.Intent
import android.content.ContentResolver
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.DocumentsContract
import java.io.File
import com.lyrebird.rc.settings.LyrebirdOnboarding
import com.lyrebird.rc.settings.LyrebirdSettingsBackup
import android.util.Log
import android.util.TypedValue
import android.widget.Toast
import android.widget.TextView
import android.widget.ArrayAdapter
import android.widget.Button
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.content.Context
import android.Manifest
import android.content.pm.PackageManager
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.hardware.Sensor
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.content.res.ColorStateList
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.SurfaceTexture
import android.widget.CheckBox
import android.media.MediaPlayer
import android.media.Image
import android.media.ImageReader
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.Switch
import android.widget.ToggleButton
import android.widget.EditText
import android.widget.LinearLayout
import android.view.Menu
import android.view.MenuItem
import android.view.Surface
import android.view.TextureView
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.wifi.WifiManager
import android.net.Uri
import android.os.BatteryManager
import android.os.HandlerThread
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.lyrebird.rc.controller.ControlAuthority
import com.lyrebird.rc.controller.DroneController
import com.lyrebird.rc.edge.EdgeDetectionController
import com.lyrebird.rc.edge.EdgeDetectionController.EdgeDetectionMetrics
import com.lyrebird.rc.edge.EdgeDetectionConfig
import com.lyrebird.rc.controller.Payload
import com.lyrebird.rc.controller.RoiControl
import dji.v5.ux.detection.DetectedTarget
import dji.v5.ux.detection.DetectionOverlayView
import com.lyrebird.rc.logger.LyrebirdFlightLogger
import com.lyrebird.rc.models.BasicAircraftControlVM
import com.lyrebird.rc.models.MediaVM
import com.lyrebird.rc.models.PayloadWidgetVM
import com.lyrebird.rc.models.VirtualStickVM
import com.lyrebird.rc.mavlink.MavlinkEndpointConfig
import com.lyrebird.rc.mavlink.MavlinkSnapshot
import com.lyrebird.rc.mavlink.CommandResult
import com.lyrebird.rc.mavlink.GimbalRotation
import com.lyrebird.rc.mavlink.Mav
import com.lyrebird.rc.mavlink.GimbalRotationMode
import com.lyrebird.rc.mavlink.MavlinkCommandOutcome
import com.lyrebird.rc.mavlink.MavlinkCommandSink
import com.lyrebird.rc.mavlink.MavlinkMotionSink
import com.lyrebird.rc.mavlink.MavlinkSystemId
import com.lyrebird.rc.mavlink.MavlinkMissionSink
import com.lyrebird.rc.mavlink.MissionExecutor
import com.lyrebird.rc.mavlink.MissionItem
import com.lyrebird.rc.mavlink.MissionProgressListener
import com.lyrebird.rc.mavlink.PendingCommand
import com.lyrebird.rc.mavlink.PendingKind
import com.lyrebird.rc.mavlink.CommandProgress
import com.lyrebird.rc.mavlink.DetectedTargetSnapshot
import com.lyrebird.rc.mavlink.MavlinkVideoStream
import com.lyrebird.rc.mavlink.MavlinkTelemetryEndpoint
import com.lyrebird.rc.mavlink.MavlinkFtpServer
import com.lyrebird.rc.controller.WaylineMissionHelper
import com.lyrebird.rc.utils.wpml.WaypointInfoModel
import dji.sdk.wpmz.value.mission.ActionGimbalRotateParam
import dji.sdk.wpmz.value.mission.ActionStartRecordParam
import dji.sdk.wpmz.value.mission.ActionStopRecordParam
import dji.sdk.wpmz.value.mission.ActionTakePhotoParam
import dji.sdk.wpmz.value.mission.WaylineActionInfo
import dji.sdk.wpmz.value.mission.WaylineActionType
import dji.sdk.wpmz.value.mission.WaylineFinishedAction
import dji.sdk.wpmz.value.mission.WaylineGimbalActuatorRotateMode
import dji.sdk.wpmz.value.mission.WaylineLocationCoordinate3D
import com.lyrebird.rc.server.TelemetryServer
import com.lyrebird.rc.webrtc.WebRTCMediaOptions
import com.lyrebird.rc.webrtc.WebRTCPeerFactory
import com.lyrebird.rc.webrtc.WebRTCStreamer
import com.lyrebird.rc.webrtc.WebRTCStreamer.VideoSourceMode
import com.lyrebird.rc.webrtc.WebRTCStreamMetrics
import com.lyrebird.rc.webrtc.SharedPhoneCameraFrameSource
import com.lyrebird.rc.webrtc.TelemetryProvider
import com.lyrebird.rc.telemetry.TelemetryCoordinator
import com.lyrebird.rc.telemetry.MockTelemetrySnapshot
import com.lyrebird.rc.util.NetworkUtils
import com.lyrebird.rc.util.ToastUtils
import com.lyrebird.rc.server.LyrebirdDiscoveryManager
import androidx.lifecycle.ViewModelProvider
import com.lyrebird.rc.models.LiveStreamVM
import dji.v5.manager.datacenter.livestream.LiveStreamSettings
import dji.v5.manager.datacenter.livestream.LiveStreamType
import dji.sdk.keyvalue.key.BatteryKey
import dji.sdk.keyvalue.key.CameraKey
import dji.sdk.keyvalue.key.DJIKey
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.GimbalKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.key.ProductKey
import dji.sdk.keyvalue.value.common.Attitude
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.sdk.keyvalue.value.common.EmptyMsg
import dji.sdk.keyvalue.value.common.LocationCoordinate2D
import dji.sdk.keyvalue.value.common.LocationCoordinate3D
import dji.sdk.keyvalue.value.common.Velocity3D
import dji.sdk.keyvalue.value.camera.CameraMode
import dji.sdk.keyvalue.value.camera.CameraStorageInfos
import dji.sdk.keyvalue.value.camera.CameraStorageLocation
import dji.sdk.keyvalue.value.camera.SDCardLoadState
import dji.sdk.keyvalue.value.camera.LaserMeasureState
import dji.sdk.keyvalue.value.camera.ThermalTemperatureMeasureMode
import dji.sdk.keyvalue.value.common.CameraLensType
import dji.sdk.keyvalue.value.flightcontroller.FlightMode
import dji.sdk.keyvalue.value.flightcontroller.LowBatteryRTHInfo
import dji.sdk.keyvalue.value.gimbal.GimbalAngleRotation
import dji.sdk.keyvalue.value.gimbal.GimbalAngleRotationMode
import dji.sdk.keyvalue.value.gimbal.GimbalMode
import dji.sdk.keyvalue.key.RemoteControllerKey
import dji.sdk.keyvalue.value.product.ProductType
import dji.v5.manager.datacenter.MediaDataCenter
import dji.v5.manager.interfaces.ICameraStreamManager
import dji.v5.et.action
import dji.v5.et.create
import dji.v5.et.createCamera
import dji.v5.et.get
import dji.v5.et.set
import dji.v5.manager.KeyManager
import dji.v5.manager.diagnostic.DJIDeviceStatus
import dji.v5.manager.diagnostic.DeviceStatusManager
import dji.v5.ux.core.util.DataProcessor
import dji.v5.ux.map.MapWidget
import dji.v5.ux.sample.showcase.defaultlayout.DefaultLayoutActivity
import dji.v5.manager.intelligent.AutoSensingInfo
import dji.v5.manager.intelligent.AutoSensingInfoListener
import dji.v5.manager.intelligent.AutoSensingTarget
import dji.v5.manager.intelligent.IntelligentFlightManager
import dji.v5.manager.intelligent.IntelligentModel
import dji.v5.manager.intelligent.TargetType
import dji.v5.manager.intelligent.smarttrack.SmartTrackTarget
import dji.v5.manager.intelligent.spotlight.SpotLightTarget
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.sdk.keyvalue.value.common.DoubleRect
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.abs
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo

/**
 * Lyrebird Default Layout Activity
 * 
 * Extends the DJI DefaultLayoutActivity to add:
 * - HTTP Command Server (port 8080) for drone control
 * - Telemetry Server (port 8081) for real-time telemetry data
 * - WHIP publishing for WebRTC video streaming through MediaMTX
 * - mDNS/Bonjour service advertising for automatic discovery
 */
/** Video delivery mode the bridge is currently publishing with. */
enum class StreamingMode(val menuLabel: String, val prefValue: String) {
    WEBRTC("WebRTC (WHIP)", "webrtc"),
    RTMP("RTMP Push", "rtmp"),
    RTSP("RTSP Server Pull", "rtsp"),
    AGORA("Agora.io WebRTC", "agora"),
    GB28181("GB28181 Surveillance", "gb28181");

    companion object {
        fun fromPref(value: String?): StreamingMode {
            return entries.firstOrNull { it.prefValue == value } ?: WEBRTC
        }
    }
}

class FlightDeckActivity : DefaultLayoutActivity(), LyrebirdCommandHost {

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

        /** The one parameter a ground station may write. See applyMavlinkParameter. */
        /**
         * The settings a ground station may write over MAVLink.
         *
         * Numeric settings only, and deliberately so: PARAM_SET carries a float, and the string
         * settings behind the rest of the /send/set* surface — the drone's name, the video
         * source, the MediaMTX address — have no honest float encoding. Those stay on HTTP until
         * they earn a proper home, rather than being smuggled through as magic numbers.
         */
        /** Beyond this, a reported gimbal angle is DJI's unset marker rather than a direction. */
        private const val MAX_PLAUSIBLE_GIMBAL_DEG = 200.0

        private const val PARAM_RTH_ALTITUDE = "LB_RTH_ALT"
        private const val PARAM_MAX_HEIGHT = "LB_MAX_HEIGHT"
        private const val PARAM_MAX_DISTANCE = "LB_MAX_DIST"
        private const val PARAM_DISTANCE_LIMIT = "LB_DIST_LIMIT_EN"
        private const val PARAM_WEBRTC_FPS = "LB_RTC_FPS"
        private const val PARAM_DETECTIONS = "LB_DETECT_EN"
        private const val PARAM_EDGE_CONFIDENCE = "LB_EDGE_CONF"

        /**
         * The string-valued settings, carried by the extended parameter protocol.
         *
         * These are the ones with no honest float encoding — a name, a source, a server address.
         * Squeezing them through PARAM_SET would have meant inventing a private numbering that
         * nobody outside this file could read.
         */
        private const val PARAM_DRONE_NAME = "LB_DRONE_NAME"
        private const val PARAM_VIDEO_SOURCE = "LB_VIDEO_SRC"
        private const val PARAM_MEDIAMTX = "LB_MEDIAMTX"
        private const val PARAM_DETECTION_SOURCE = "LB_DETECT_SRC"
        private const val PARAM_RC_CONTROL_MODE = "LB_RC_MODE"
        private const val PARAM_RTC_RESOLUTION = "LB_RTC_RES"
        private const val PARAM_STREAMING_MODE = "LB_STREAM_MODE"
        private const val TAG_THERMAL = "LyrebirdThermal"
        private const val MEDIAMTX_WHIP_PORT = 8889  // mediamtx WebRTC port for WHIP publish
        private const val PREF_DRONE_NAME = "drone_name"
        private const val PREF_MAVLINK_FLIGHT_DEFAULT_MIGRATED = "lb_mav_0_allow_flight_default_v2"
        private const val SETTINGS_BACKUP_DEBOUNCE_MS = 1500L
        private const val PREF_MEDIAMTX_SERVER = "mediamtx_server"
        private const val SAFETY_TOKEN = "98"
        private const val PREF_WEBRTC_FPS = "webrtc_fps"
        private const val PREF_WEBRTC_RESOLUTION = "webrtc_resolution"
        private const val PREF_MOCK_VIDEO_ENABLED = "mock_video_enabled"
        private const val PREF_MAP_EXPANDED = "map_expanded"
        private const val PREF_DETECTIONS_ENABLED = "detections_enabled"
        private const val PREF_DETECTION_SOURCE = "detection_source"
        private const val PREF_EDGE_DETECTION_ENABLED = "edge_detection_enabled"
        private const val PREF_VIDEO_SOURCE = "video_source"
        private const val PREF_EDGE_MODEL_URI = "edge_model_uri"
        private const val PREF_EDGE_MODEL_NAME = "edge_model_name"
        private const val PREF_EDGE_LABELS_URI = "edge_labels_uri"
        private const val PREF_EDGE_LABELS_NAME = "edge_labels_name"
        private const val PREF_EDGE_CONFIDENCE_THRESHOLD = "edge_confidence_threshold"
        private const val PREF_STREAMING_MODE = "streaming_mode"
        private const val PREF_RTMP_URL = "rtmp_url"
        private const val PREF_RTSP_PORT = "rtsp_port"
        private const val PREF_RTSP_USER = "rtsp_user"
        private const val PREF_RTSP_PWD = "rtsp_pwd"
        private const val DJI_RTSP_STREAM_PATH = "/streaming/live/1"
        private const val PREF_AGORA_CHANNEL = "agora_channel"
        private const val PREF_AGORA_TOKEN = "agora_token"
        private const val PREF_AGORA_UID = "agora_uid"
        private const val PREF_GB_SERVER_IP = "gb_server_ip"
        private const val PREF_GB_SERVER_PORT = "gb_server_port"
        private const val PREF_GB_SERVER_ID = "gb_server_id"
        private const val PREF_GB_AGENT_ID = "gb_agent_id"
        private const val PREF_GB_CHANNEL = "gb_channel"
        private const val PREF_GB_LOCAL_PORT = "gb_local_port"
        private const val PREF_GB_PASSWORD = "gb_password"
        private const val DEFAULT_WEBRTC_FPS = 10
        private const val DEFAULT_EDGE_CONFIDENCE_THRESHOLD = 0.25f
        private const val REQUEST_PHONE_CAMERA_SOURCE = 2
        private const val REQUEST_EDGE_MODEL_FILE = 3
        private const val REQUEST_EDGE_LABELS_FILE = 4
        private const val PHONE_EDGE_FRAME_INTERVAL_NS = 200_000_000L
        private val EDGE_CONFIDENCE_OPTIONS = floatArrayOf(
            0.10f,
            0.15f,
            0.20f,
            0.25f,
            0.30f,
            0.40f,
            0.50f,
            0.60f,
            0.70f
        )
        private const val DEFAULT_DRONE_NAME = "drone_1"
        private val WEBRTC_FPS_OPTIONS = intArrayOf(5, 10, 15, 20, 25, 30)
    }

    private enum class StreamResolutionPreset(
        val prefValue: String,
        val menuLabel: String,
        val width: Int,
        val height: Int,
        val bitrate: Int
    ) {
        AUTO("auto", "Auto / native", 0, 0, 6_000_000),
        FULL_HD("1080p", "1080p", 1920, 1080, 8_000_000),
        HD("720p", "720p", 1280, 720, 2_000_000),
        SD("480p", "480p", 640, 480, 1_500_000);

        companion object {
            fun fromPref(value: String?): StreamResolutionPreset {
                return entries.firstOrNull { it.prefValue == value } ?: AUTO
            }
        }
    }

    private enum class DetectionSource(
        val prefValue: String,
        val menuLabel: String
    ) {
        NONE("none", "None"),
        DJI_ONBOARD("dji_onboard", "DJI onboard"),
        YOLO_ON_PHONE("yolo_on_phone", "YOLO on phone");

        companion object {
            fun fromPref(value: String?): DetectionSource {
                return entries.firstOrNull { it.prefValue == value } ?: NONE
            }
        }
    }


    private val liveStreamVM by lazy {
        ViewModelProvider(this)[LiveStreamVM::class.java]
    }

    override val mainHandler = Handler(Looper.getMainLooper())

    private var settingsBackupListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    //: The backup writes a file to Documents/Lyrebird; that I/O must not run on the main
    //: thread (StrictMode flags it). Serialized so debounced writes never pile up.
    private val settingsBackupExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val settingsBackupTask = Runnable {
        settingsBackupExecutor.execute {
            LyrebirdSettingsBackup.save(sharedPreferences, droneName)
        }
    }
    private val telemetryCoordinator = TelemetryCoordinator()
    private lateinit var discoveryManager: LyrebirdDiscoveryManager
    
    // ViewModels for drone control
    private lateinit var basicAircraftControlVM: BasicAircraftControlVM
    private lateinit var virtualStickVM: VirtualStickVM
    override lateinit var mediaVM: MediaVM
    override lateinit var payloadWidgetVM: PayloadWidgetVM
    
    // Servers
    private var httpServer: SimpleHttpServer? = null
    private var telemetryServer: TelemetryServer? = null

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
    private val captureExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

    /**
     * MAVLink FTP worker. Two threads so a slow file download does not block a directory listing:
     * both pull the same DJI media pipeline, but the SDK serialises the pulls themselves.
     */
    private val ftpExecutor = java.util.concurrent.Executors.newFixedThreadPool(2)
    private var webRTCStreamer: WebRTCStreamer? = null
    private var lyrebirdSettingsDialog: Dialog? = null
    @Volatile private var lastWhipUrl: String? = null  // Remembered for FPS/Quality mode restarts
    @Volatile private var lastClientIp: String? = null
    
    private var droneSerialNumber: String = "UNKNOWN"
    
    // Drone Configuration
    private lateinit var sharedPreferences: SharedPreferences
    override var droneName: String = DEFAULT_DRONE_NAME

    // Phone Location
    private var locationManager: LocationManager? = null
    private var phoneLocation: Location? = null
    // Static listener holding only a WeakReference to the activity. On some platforms the
    // framework LocationManager keeps its LocationListenerTransport in a native global even
    // after removeUpdates(); an anonymous listener's implicit outer reference would then pin
    // the destroyed activity (LeakCanary: ~7.8 MB). A WeakReference cannot.
    private val locationListener = PhoneLocationListener(this)

    private class PhoneLocationListener(
        activity: FlightDeckActivity
    ) : LocationListener {
        private val activityRef = WeakReference(activity)
        override fun onLocationChanged(location: Location) {
            val activity = activityRef.get() ?: return
            activity.phoneLocation = location
            activity.refreshMockTelemetryMode()
        }
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) = Unit
    }

    // Phone Sensors & Status
    private var sensorManager: SensorManager? = null
    private var wifiManager: WifiManager? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var batteryManager: BatteryManager? = null
        private var mockPreviewPlayer: MediaPlayer? = null
    @Volatile private var lastWebRTCMetrics = WebRTCStreamMetrics()
    @Volatile private var lastNativeStreamStatus: String = "idle"
    
    private var phoneHeading: Double = 0.0
    private var phonePressure: Float = 0.0f
    @Volatile private var latestAltitudeMetres: Double = 0.0
    @Volatile private var latestGimbalPitchDegrees: Double = 0.0
    
    private val accelerometerReading = FloatArray(3)
    private val magnetometerReading = FloatArray(3)
    private val rotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)
    
    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
                System.arraycopy(event.values, 0, accelerometerReading, 0, accelerometerReading.size)
            } else if (event.sensor.type == Sensor.TYPE_MAGNETIC_FIELD) {
                System.arraycopy(event.values, 0, magnetometerReading, 0, magnetometerReading.size)
            } else if (event.sensor.type == Sensor.TYPE_PRESSURE) {
                phonePressure = event.values[0]
            }
            
            updateOrientationAngles()
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
            // Do nothing
        }
    }
    
    // Home point tracking
    private var isHomePointSetLatch = false

    // ==================== AutoSensing (AI Detection) ====================
    override var isAutoSensingActive = false
    private var isAutoSensingListenerRegistered = false
    private var edgeDetectionController: EdgeDetectionController? = null
    @Volatile private var lastEdgeMetrics = EdgeDetectionMetrics()
    @Volatile override var currentDetectedTargets: List<DetectedTarget> = emptyList()
    private var detectionOverlay: DetectionOverlayView? = null
    private var pendingVideoSourceAfterPermission: VideoSourceMode? = null
    private var phoneCameraDevice: CameraDevice? = null
    private var phoneCameraSession: CameraCaptureSession? = null
    private var phoneCameraThread: HandlerThread? = null
    private var phoneCameraHandler: Handler? = null
    private var phonePreviewSurface: Surface? = null
    private var phoneImageReader: ImageReader? = null
    private val phoneInferenceBusy = AtomicBoolean(false)
    @Volatile private var lastPhoneEdgeFrameNs = 0L
    private var pendingEdgePickerRequestCode: Int? = null
    private val edgeFilePickerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val requestCode = pendingEdgePickerRequestCode
        pendingEdgePickerRequestCode = null
        if (requestCode == null || result.resultCode != RESULT_OK) return@registerForActivityResult
        val uri = result.data?.data ?: return@registerForActivityResult
        when (requestCode) {
            REQUEST_EDGE_MODEL_FILE -> storeEdgeModelSelection(uri)
            REQUEST_EDGE_LABELS_FILE -> storeEdgeFileSelection(
                uri,
                PREF_EDGE_LABELS_URI,
                PREF_EDGE_LABELS_NAME,
                "Edge labels"
            )
        }
    }

    private val autoSensingInfoListener = object : AutoSensingInfoListener {
        override fun onAutoSensingInfoUpdate(info: AutoSensingInfo) {
            if (getDetectionSource() != DetectionSource.DJI_ONBOARD) return
            val targets = info.targets?.mapIndexed { idx, t ->
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
                    bottom = cy + hh
                )
            } ?: emptyList()
            applyDetectedTargets(targets)
        }

        override fun onTrackingTargetUpdate(target: AutoSensingTarget) = Unit

        override fun onIntelligentModelUpdate(models: MutableList<IntelligentModel>) = Unit

        override fun onRunningIntelligentModelUpdate(modelId: Int) = Unit
    }
    // ==================== End AutoSensing Fields ====================

    // Battery and flight time data processors
    private val chargeRemainingProcessor: DataProcessor<Int> = DataProcessor.create(0)
    private val goHomeAssessmentProcessor: DataProcessor<LowBatteryRTHInfo> = DataProcessor.create(LowBatteryRTHInfo())
    private val seriousLowBatteryThresholdProcessor: DataProcessor<Int> = DataProcessor.create(0)
    private val lowBatteryThresholdProcessor: DataProcessor<Int> = DataProcessor.create(0)
    private val timeNeededToLandProcessor: DataProcessor<Int> = DataProcessor.create(0)

    // DJI Keys
    private val chargeRemainingKey = KeyTools.createKey(BatteryKey.KeyChargeRemainingInPercent)
    private val goHomeAssessmentKey = KeyTools.createKey(FlightControllerKey.KeyLowBatteryRTHInfo)
    private val seriousLowBatteryKey = KeyTools.createKey(FlightControllerKey.KeySeriousLowBatteryWarningThreshold)
    private val lowBatteryKey = KeyTools.createKey(FlightControllerKey.KeyLowBatteryWarningThreshold)
    private val timeNeededToLandKey = KeyTools.createKey(FlightControllerKey.KeyLowBatteryRTHInfo)

    // var, not val: on the M400 these are rebound to LEFT_OR_MAIN once the main-camera video is up
    // (see rebindGimbalKeysForM400). Other aircraft keep the default no-index binding.
    override var gimbalKey: DJIKey.ActionKey<GimbalAngleRotation, EmptyMsg> = GimbalKey.KeyRotateByAngle.create()
    override val zoomKey: DJIKey<Double> = CameraKey.KeyCameraZoomRatios.create()
    override val startRecording: DJIKey.ActionKey<EmptyMsg, EmptyMsg> = CameraKey.KeyStartRecord.create()
    override val stopRecording: DJIKey.ActionKey<EmptyMsg, EmptyMsg> = CameraKey.KeyStopRecord.create()
    private val isRecordingKey: DJIKey<Boolean> = CameraKey.KeyIsRecording.create()

    private val location3DKey: DJIKey<LocationCoordinate3D> = FlightControllerKey.KeyAircraftLocation3D.create()
    private val satelliteCountKey: DJIKey<Int> = FlightControllerKey.KeyGPSSatelliteCount.create()
    private var gimbalAttitudeKey: DJIKey<Attitude> = GimbalKey.KeyGimbalAttitude.create()
    private var gimbalJointAttitudeKey: DJIKey<Attitude> = GimbalKey.KeyGimbalJointAttitude.create()
    private var gimbalModeKey: DJIKey<GimbalMode> = GimbalKey.KeyGimbalMode.create()
    private val compassHeadKey: DJIKey<Double> = FlightControllerKey.KeyCompassHeading.create()
    private val altitudeKey: DJIKey<Double> = FlightControllerKey.KeyAltitude.create()
    private val homeLocationKey: DJIKey<LocationCoordinate2D> = FlightControllerKey.KeyHomeLocation.create()
    private val flightSpeedKey: DJIKey<Velocity3D> = FlightControllerKey.KeyAircraftVelocity.create()
    private val attitudeKey: DJIKey<Attitude> = FlightControllerKey.KeyAircraftAttitude.create()
    private val cameraZoomFocalLengthKey: DJIKey<Int> = CameraKey.KeyCameraZoomFocalLength.create()
    private val cameraOpticalFocalLengthKey: DJIKey<Int> = CameraKey.KeyCameraOpticalZoomFocalLength.create()
    private val cameraHybridFocalLengthKey: DJIKey<Int> = CameraKey.KeyCameraHybridZoomFocalLength.create()
    private val batteryKey: DJIKey<Int> = BatteryKey.KeyChargeRemainingInPercent.create()
    private val flightModeKey: DJIKey<FlightMode> = FlightControllerKey.KeyFlightMode.create()
    private val isFlyingKey: DJIKey<Boolean> = FlightControllerKey.KeyIsFlying.create()

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



    @Volatile override var lrfTargetLocation: LocationCoordinate3D? = null

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
    private val cameraConnectionKey: DJIKey<Boolean> = KeyTools.createKey(
        CameraKey.KeyConnection,
        ComponentIndexType.LEFT_OR_MAIN
    )
    private val cameraModeKey: DJIKey<CameraMode> = KeyTools.createKey(
        CameraKey.KeyCameraMode,
        ComponentIndexType.LEFT_OR_MAIN
    )
    private val cameraStorageLocationKey: DJIKey<CameraStorageLocation> = KeyTools.createKey(
        CameraKey.KeyCameraStorageLocation,
        ComponentIndexType.LEFT_OR_MAIN
    )
    private val cameraStorageInfosKey: DJIKey<CameraStorageInfos> = KeyTools.createKey(
        CameraKey.KeyCameraStorageInfos,
        ComponentIndexType.LEFT_OR_MAIN
    )
    private var thermalArmed = false

    private data class DroneStorageStatus(
        val label: String,
        val summary: String
    ) {
        val menuLabel: String
            get() = "$label (${summary})"

        val dialogText: String
            get() = "$label: $summary"
    }

    private data class SettingsActionRow(
        val title: String,
        val detail: String? = null,
        val enabled: Boolean = true
    )

    @Volatile
    private var aircraftConnected = false

    // ==================== Initial-loading overlay ====================

    /** Minimum time the loading overlay stays visible, so every launch shows it briefly. */
    private val loadingMinVisibleMs = 2_000L

    /** Safety net: hide the overlay even if the aircraft never connects (mock/phone use). */
    private val loadingTimeoutMs = 20_000L

    private var loadingShownAtMs = 0L

    private val hideLoadingOverlayRunnable = Runnable {
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
    private val loadingDiagnosticRunnable: Runnable = object : Runnable {
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
                "overlayVisible=$overlayVisible"
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
        setupVideoSourceState()
        setupMockVideoPreview()
        setupPhoneVideoPreview()
        setupMapExpandToggle()

        setupDetectedDroneProfileListener()
        updateWebRTCMetricsView(WebRTCStreamMetrics())
        updateEdgeMetricsView(lastEdgeMetrics)

        // Setup drone status indicator
        setupDroneStatusView()

        // Setup Pilot/Safety authority banner
        setupControlAuthorityBanner()

        // Initialize LocationManager from the APPLICATION context. The framework can keep the
        // LocationManager's transport in a native global after removeUpdates(); if the manager
        // were bound to the activity context, its mContext would then pin the destroyed activity
        // (LeakCanary). The application context is process-scoped, so it cannot leak the activity.
        locationManager = applicationContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        startLocationUpdates()

        // Initialize Phone Sensors & Managers
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        
        // Acquire Multicast Lock to allow receiving UDP broadcasts
        multicastLock = wifiManager?.createMulticastLock("LyrebirdMulticastLock")
        multicastLock?.setReferenceCounted(true)
        multicastLock?.acquire()
        
        batteryManager = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        startSensorUpdates()
        
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
            if (isChecked) DroneController.activateManualOverride()
            else DroneController.deactivateManualOverride()
            updateManualOverrideUI()
        }

        DroneController.manualOverrideListener = object : DroneController.ManualOverrideListener {
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
                if (isChecked) DroneController.activateManualOverride()
                else DroneController.deactivateManualOverride()
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
    override fun classifyCommandSource(presentedToken: String?): ControlAuthority.Source {
        return if (presentedToken == SAFETY_TOKEN)
            ControlAuthority.Source.SAFETY
        else
            ControlAuthority.Source.PILOT
    }

    private fun setupControlAuthorityBanner() {
        ControlAuthority.listener = object : ControlAuthority.Listener {
            override fun onAuthorityChanged(authority: ControlAuthority.Authority) {
                mainHandler.post { updateControlAuthorityBanner(authority) }
            }
        }
        updateControlAuthorityBanner(ControlAuthority.active)
    }

    private fun updateControlAuthorityBanner(authority: ControlAuthority.Authority) {
        val tv = findViewById<TextView>(R.id.text_control_authority) ?: return
        when (authority) {
            // ponytail: pilot control is the normal state, no banner needed
            ControlAuthority.Authority.PILOT -> tv.visibility = View.GONE
            ControlAuthority.Authority.SAFETY -> {
                tv.text = "SAFETY COMPUTER IN CONTROL"
                tv.setTextColor(0xFFF44336.toInt())  // red
                tv.visibility = View.VISIBLE
            }
        }
    }

    // ==================== End Pilot / Safety Authority ====================

    private fun buildWebRTCOptions(): WebRTCMediaOptions {
        val preset = getWebRTCResolutionPreset()
        return if (preset == StreamResolutionPreset.AUTO) {
            WebRTCMediaOptions.native().copy(fps = getWebRTCFps())
        } else {
            WebRTCMediaOptions(
                videoResolutionWidth = preset.width,
                videoResolutionHeight = preset.height,
                fps = getWebRTCFps(),
                videoBitrate = preset.bitrate,
                videoCodec = "H264"
            )
        }
    }

    private fun getWebRTCFps(): Int {
        val storedFps = sharedPreferences.getInt(PREF_WEBRTC_FPS, DEFAULT_WEBRTC_FPS)
        return if (WEBRTC_FPS_OPTIONS.contains(storedFps)) storedFps else DEFAULT_WEBRTC_FPS
    }

    private fun getWebRTCResolutionPreset(): StreamResolutionPreset {
        return StreamResolutionPreset.fromPref(
            sharedPreferences.getString(PREF_WEBRTC_RESOLUTION, StreamResolutionPreset.AUTO.prefValue)
        )
    }

    private fun isDjiSurfaceH264EncoderEnabled(): Boolean = sharedPreferences.getBoolean(
        WebRTCPeerFactory.PREF_USE_DJI_SURFACE_H264_ENCODER,
        false
    )

    private fun toggleDjiSurfaceH264Encoder() {
        val enabled = !isDjiSurfaceH264EncoderEnabled()
        sharedPreferences.edit()
            .putBoolean(WebRTCPeerFactory.PREF_USE_DJI_SURFACE_H264_ENCODER, enabled)
            .apply()
        Toast.makeText(
            this,
            "Surface H264 encoder ${if (enabled) "enabled" else "disabled"}; restart app to apply",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun getVideoSourceMode(): VideoSourceMode {
        return VideoSourceMode.fromPref(sharedPreferences.getString(PREF_VIDEO_SOURCE, VideoSourceMode.DJI.prefValue))
    }

    private fun getStreamingMode(): StreamingMode {
        return StreamingMode.fromPref(sharedPreferences.getString(PREF_STREAMING_MODE, StreamingMode.WEBRTC.prefValue))
    }

    override fun setStreamingMode(mode: StreamingMode) {
        sharedPreferences.edit().putString(PREF_STREAMING_MODE, mode.prefValue).apply()
        if (mode != StreamingMode.WEBRTC && isDetectionsEnabled()
            && getDetectionSource() == DetectionSource.YOLO_ON_PHONE) {
            setDetectionsEnabled(false)
            Toast.makeText(
                this,
                "YOLO edge detection deactivated (only supported in WebRTC mode)",
                Toast.LENGTH_LONG
            ).show()
        }
        rebuildTelemetryCache()
        updateStreamingFooter()
    }

    private fun getRtmpUrl(clientIp: String): String {
        val stored = sharedPreferences.getString(PREF_RTMP_URL, "")?.trim().orEmpty()
        return stored.ifEmpty { "rtmp://$clientIp:1935/$droneName" }
    }

    private fun setRtmpUrl(url: String) {
        sharedPreferences.edit().putString(PREF_RTMP_URL, url.trim()).apply()
    }

    private fun getRtspPort(): Int = sharedPreferences.getInt(PREF_RTSP_PORT, 8554)
    private fun setRtspPort(port: Int) = sharedPreferences.edit().putInt(PREF_RTSP_PORT, port).apply()

    private fun resolveRtspPortForStart(): Int {
        val configuredPort = getRtspPort()
        if (!NetworkUtils.isPortInUse(configuredPort)) {
            return configuredPort
        }
        val fallbackPorts = intArrayOf(18554, 28554, 38554)
        return fallbackPorts.firstOrNull { !NetworkUtils.isPortInUse(it) } ?: configuredPort
    }

    private fun getRtspUsername(): String = sharedPreferences.getString(PREF_RTSP_USER, "admin") ?: "admin"
    private fun setRtspUsername(user: String) = sharedPreferences.edit().putString(PREF_RTSP_USER, user.trim()).apply()

    private fun getRtspPassword(): String = sharedPreferences.getString(PREF_RTSP_PWD, "lyrebird") ?: "lyrebird"
    private fun setRtspPassword(pwd: String) = sharedPreferences.edit().putString(PREF_RTSP_PWD, pwd).apply()

    private fun getAgoraChannel(): String = sharedPreferences.getString(PREF_AGORA_CHANNEL, "") ?: ""
    private fun setAgoraChannel(ch: String) = sharedPreferences.edit().putString(PREF_AGORA_CHANNEL, ch.trim()).apply()

    private fun getAgoraToken(): String = sharedPreferences.getString(PREF_AGORA_TOKEN, "") ?: ""
    private fun setAgoraToken(tok: String) = sharedPreferences.edit().putString(PREF_AGORA_TOKEN, tok.trim()).apply()

    private fun getAgoraUid(): String = sharedPreferences.getString(PREF_AGORA_UID, "") ?: ""
    private fun setAgoraUid(uid: String) = sharedPreferences.edit().putString(PREF_AGORA_UID, uid.trim()).apply()

    private fun getGbServerIp(): String = sharedPreferences.getString(PREF_GB_SERVER_IP, "") ?: ""
    private fun setGbServerIp(ip: String) = sharedPreferences.edit().putString(PREF_GB_SERVER_IP, ip.trim()).apply()

    private fun getGbServerPort(): Int = sharedPreferences.getInt(PREF_GB_SERVER_PORT, 5060)
    private fun setGbServerPort(port: Int) = sharedPreferences.edit().putInt(PREF_GB_SERVER_PORT, port).apply()

    private fun getGbServerId(): String = sharedPreferences.getString(PREF_GB_SERVER_ID, "") ?: ""
    private fun setGbServerId(id: String) = sharedPreferences.edit().putString(PREF_GB_SERVER_ID, id.trim()).apply()

    private fun getGbAgentId(): String = sharedPreferences.getString(PREF_GB_AGENT_ID, "") ?: ""
    private fun setGbAgentId(id: String) = sharedPreferences.edit().putString(PREF_GB_AGENT_ID, id.trim()).apply()

    private fun getGbChannel(): String = sharedPreferences.getString(PREF_GB_CHANNEL, "") ?: ""
    private fun setGbChannel(ch: String) = sharedPreferences.edit().putString(PREF_GB_CHANNEL, ch.trim()).apply()

    private fun getGbLocalPort(): Int = sharedPreferences.getInt(PREF_GB_LOCAL_PORT, 5061)
    private fun setGbLocalPort(port: Int) = sharedPreferences.edit().putInt(PREF_GB_LOCAL_PORT, port).apply()

    private fun getGbPassword(): String = sharedPreferences.getString(PREF_GB_PASSWORD, "") ?: ""
    private fun setGbPassword(pwd: String) = sharedPreferences.edit().putString(PREF_GB_PASSWORD, pwd).apply()

    private fun setupVideoSourceState() {
        if (!sharedPreferences.contains(PREF_VIDEO_SOURCE)) {
            val legacyMock = sharedPreferences.getBoolean(PREF_MOCK_VIDEO_ENABLED, false)
            sharedPreferences.edit()
                .putString(
                    PREF_VIDEO_SOURCE,
                    if (legacyMock) VideoSourceMode.MOCK.prefValue else VideoSourceMode.DJI.prefValue
                )
                .apply()
        }
        updateMockVideoVisibility()
        updatePhonePreviewVisibility()
        refreshMockTelemetryMode()
    }

    private fun setVideoSourceMode(mode: VideoSourceMode) {
        if (mode == VideoSourceMode.PHONE && !ensureCameraPermissionForPhoneSource(mode)) return
        sharedPreferences.edit()
            .putString(PREF_VIDEO_SOURCE, mode.prefValue)
            .putBoolean(PREF_MOCK_VIDEO_ENABLED, mode == VideoSourceMode.MOCK)
            .apply()
        webRTCStreamer?.setVideoSourceMode(mode)
        updateMockVideoVisibility()
        updatePhonePreviewVisibility()
        refreshMockTelemetryMode()
        invalidateOptionsMenu()
        val label = "Video source: ${mode.menuLabel}"
        Toast.makeText(this, label, Toast.LENGTH_SHORT).show()
        Log.i(TAG, label)
        if (activeDetectionSource() == DetectionSource.YOLO_ON_PHONE) {
            stopEdgeDetection()
            startEdgeDetection()
        }
    }

    private fun ensureCameraPermissionForPhoneSource(mode: VideoSourceMode): Boolean {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            return true
        }
        pendingVideoSourceAfterPermission = mode
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQUEST_PHONE_CAMERA_SOURCE)
        Toast.makeText(this, "Camera permission is needed for phone video source", Toast.LENGTH_SHORT).show()
        return false
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PHONE_CAMERA_SOURCE) {
            val pendingMode = pendingVideoSourceAfterPermission
            pendingVideoSourceAfterPermission = null
            if (pendingMode == VideoSourceMode.PHONE
                && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                setVideoSourceMode(VideoSourceMode.PHONE)
            } else {
                Toast.makeText(
                    this,
                    "Phone camera source unavailable without camera permission",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun storeEdgeModelSelection(uri: Uri) {
        val displayName = storeEdgeFileSelection(uri, PREF_EDGE_MODEL_URI, PREF_EDGE_MODEL_NAME, "Edge model")
        trySelectSiblingEdgeLabels(uri, displayName)
        if (activeDetectionSource() == DetectionSource.YOLO_ON_PHONE) {
            stopEdgeDetection()
            startEdgeDetection()
        }
    }

    private fun storeEdgeFileSelection(uri: Uri, uriPref: String, namePref: String, label: String): String {
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }.onFailure { Log.d(TAG, "Could not persist $label URI permission: ${it.message}") }
        val displayName = uri.lastPathSegment?.substringAfterLast('/') ?: uri.toString().substringAfterLast('/')
        sharedPreferences.edit()
            .putString(uriPref, uri.toString())
            .putString(namePref, displayName)
            .apply()
        if (activeDetectionSource() == DetectionSource.YOLO_ON_PHONE) {
            stopEdgeDetection()
            startEdgeDetection()
        }
        Toast.makeText(this, "$label selected: $displayName", Toast.LENGTH_SHORT).show()
        invalidateOptionsMenu()
        return displayName
    }

    private fun setupMapExpandToggle() {
        val button = findViewById<ToggleButton>(R.id.button_map_expand) ?: return
        val expanded = sharedPreferences.getBoolean(PREF_MAP_EXPANDED, false)
        button.isChecked = expanded
        applyMapExpandedState(expanded)
        button.setOnCheckedChangeListener { _, isChecked ->
            sharedPreferences.edit().putBoolean(PREF_MAP_EXPANDED, isChecked).apply()
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
        val width = if (expanded) {
            (screenWidth - dpToPx(24)).coerceAtLeast(compactWidth)
        } else {
            compactWidth
        }
        val height = if (expanded) {
            (screenHeight - dpToPx(96)).coerceAtLeast(compactHeight)
        } else {
            compactHeight
        }
        mapWidget.layoutParams = mapWidget.layoutParams.apply {
            this.width = width
            this.height = height
        }
        mapWidget.setMapCenterLock(if (expanded) MapWidget.MapCenterLock.NONE else MapWidget.MapCenterLock.AIRCRAFT)
        mapWidget.setAutoFrameMapEnabled(false)
        mapWidget.bringToFront()
        button?.bringToFront()
        button?.visibility = if (expanded) View.VISIBLE else View.GONE
        button?.contentDescription = if (expanded) "Minimize map" else "Expand map"
        mapWidget.requestLayout()
    }

    private fun dpToPx(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun actionRowAdapter(rows: List<SettingsActionRow>): ArrayAdapter<SettingsActionRow> {
        return object : ArrayAdapter<SettingsActionRow>(this, 0, rows) {
            override fun isEnabled(position: Int): Boolean = getItem(position)?.enabled == true

            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val row = getItem(position) ?: SettingsActionRow("")
                val root = (convertView as? LinearLayout) ?: LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(dpToPx(18), dpToPx(12), dpToPx(14), dpToPx(12))
                    minimumHeight = dpToPx(68)
                }
                root.removeAllViews()
                root.alpha = if (row.enabled) 1.0f else 0.45f
                root.background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(0xFFF7F9FC.toInt())
                    setStroke(dpToPx(1), 0xFFE1E7EF.toInt())
                }

                val textColumn = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                textColumn.addView(TextView(context).apply {
                    text = row.title
                    setTextColor(0xFF1F2937.toInt())
                    textSize = 15f
                    setTypeface(
                        ResourcesCompat.getFont(this@FlightDeckActivity, R.font.space_grotesk),
                        android.graphics.Typeface.BOLD
                    )
                })
                row.detail?.takeIf { it.isNotBlank() }?.let { detail ->
                    textColumn.addView(TextView(context).apply {
                        text = detail
                        setTextColor(0xFF5F6F82.toInt())
                        textSize = 13f
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    })
                }
                root.addView(textColumn)
                root.addView(TextView(context).apply {
                    text = "›"
                    setTextColor(0xFF78C7FF.toInt())
                    textSize = 24f
                    setPadding(dpToPx(12), 0, 0, 0)
                    visibility = if (row.enabled) View.VISIBLE else View.INVISIBLE
                })
                return root
            }
        }
    }

    private fun trySelectSiblingEdgeLabels(modelUri: Uri, modelName: String) {
        val labelsUri = findSiblingLabelsUri(modelUri, modelName) ?: return
        val labelsName =
            labelsUri.lastPathSegment?.substringAfterLast('/') ?: labelsUri.toString().substringAfterLast('/')
        if (readEdgeLabels(labelsUri).isEmpty()) return
        runCatching {
            contentResolver.takePersistableUriPermission(labelsUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }.onFailure { Log.d(TAG, "Could not persist auto edge labels URI permission: ${it.message}") }
        sharedPreferences.edit()
            .putString(PREF_EDGE_LABELS_URI, labelsUri.toString())
            .putString(PREF_EDGE_LABELS_NAME, labelsName)
            .apply()
        Toast.makeText(this, "Edge labels auto-selected: $labelsName", Toast.LENGTH_SHORT).show()
    }

    private fun findSiblingLabelsUri(modelUri: Uri, modelName: String): Uri? {
        val folderId = if (DocumentsContract.isDocumentUri(this, modelUri)) {
            runCatching { DocumentsContract.getDocumentId(modelUri) }
                .getOrNull()
                ?.substringBeforeLast('/', missingDelimiterValue = "")
                ?.takeIf { it.isNotBlank() }
        } else {
            null
        }

        return folderId?.let { parentFolderId ->
            val candidateNames = candidateLabelNames(modelName)
            val siblingMatch = candidateNames
                .asSequence()
                .map { DocumentsContract.buildDocumentUri(modelUri.authority, "$parentFolderId/$it") }
                .firstOrNull { readEdgeLabels(it).isNotEmpty() }

            siblingMatch ?: run {
                val candidateNameSet = candidateNames.map { it.lowercase(java.util.Locale.US) }.toSet()
                val childrenUri = DocumentsContract.buildChildDocumentsUri(modelUri.authority, parentFolderId)
                runCatching {
                    contentResolver.query(
                        childrenUri,
                        arrayOf(
                            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                            DocumentsContract.Document.COLUMN_DISPLAY_NAME
                        ),
                        null,
                        null,
                        null
                    )?.use { cursor ->
                        val idIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                        val nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                        while (cursor.moveToNext()) {
                            val name = cursor.getString(nameIndex) ?: continue
                            if (candidateNameSet.contains(name.lowercase(java.util.Locale.US))) {
                                return@use DocumentsContract.buildDocumentUri(
                                    modelUri.authority,
                                    cursor.getString(idIndex)
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
        val simplified = base
            .removeSuffix("_dynamic_range_quant")
            .removeSuffix("_float32")
            .removeSuffix("_float16")
            .removeSuffix("_int8")
            .replace(Regex("_320$"), "")
        return listOf(
            "$base.txt",
            "${base}_labels.txt",
            "$simplified.txt",
            "${simplified}_labels.txt"
        )
    }

    private fun getEdgeModelUri(): Uri? {
        return sharedPreferences.getString(PREF_EDGE_MODEL_URI, null)?.let(Uri::parse)
    }

    private fun getEdgeLabels(): List<String> {
        val labelsUri = sharedPreferences.getString(
            PREF_EDGE_LABELS_URI,
            null
        )?.let(Uri::parse) ?: return listOf("person")
        return readEdgeLabels(labelsUri).ifEmpty { listOf("person") }
    }

    private fun getEdgeConfidenceThreshold(): Float {
        return sharedPreferences.getFloat(PREF_EDGE_CONFIDENCE_THRESHOLD, DEFAULT_EDGE_CONFIDENCE_THRESHOLD)
            .coerceIn(0.01f, 0.99f)
    }

    private fun readEdgeLabels(labelsUri: Uri): List<String> {
        return runCatching {
            contentResolver.openInputStream(labelsUri)?.bufferedReader()?.useLines { lines ->
                lines.map { it.trim() }.filter { it.isNotEmpty() }.toList()
            }.orEmpty()
        }.getOrElse { error ->
            Log.e(TAG, "Failed to read edge labels: ${error.message}", error)
            emptyList()
        }
    }

    private fun showEdgeFilePicker(requestCode: Int, title: String) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_TITLE, title)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        pendingEdgePickerRequestCode = requestCode
        edgeFilePickerLauncher.launch(intent)
    }

    private fun isMockVideoEnabled(): Boolean {
        return getVideoSourceMode() == VideoSourceMode.MOCK
    }

    private fun shouldUseMockTelemetry(): Boolean {
        return getVideoSourceMode() == VideoSourceMode.MOCK || getVideoSourceMode() == VideoSourceMode.PHONE
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
    private val mainCamFirstFrameListener = object : ICameraStreamManager.CameraFrameListener {
        override fun onFrame(
            frameData: ByteArray, offset: Int, length: Int,
            width: Int, height: Int, format: ICameraStreamManager.FrameFormat
        ) {
            lastDetectedFrameWidth = width
            lastDetectedFrameHeight = height
            mainHandler.post { onMainCameraFirstFrame() }
        }
    }

    private fun isMatrice400(): Boolean =
        ProductKey.KeyProductType.create().get(ProductType.UNKNOWN) == ProductType.DJI_MATRICE_400

    private fun registerMainCamFrameDetector() {
        if (mainCamFrameDetectorRegistered || gimbalKeysReboundForM400) return
        runCatching {
            // FPVWidget renders PORT_3 via a surface (hardware path) which does NOT trigger the YUV
            // frame callback. Explicitly enable the stream so addFrameListener actually gets frames.
            cameraStreamManager.enableStream(ComponentIndexType.PORT_3, true)
            cameraStreamManager.addFrameListener(
                ComponentIndexType.PORT_3,
                ICameraStreamManager.FrameFormat.NV21,
                mainCamFirstFrameListener
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
        Log.i(TAG, "PORT_3 first frame ${lastDetectedFrameWidth}x${lastDetectedFrameHeight} (M400=$m400)")

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
        gimbalAttitudeKey = GimbalKey.KeyGimbalAttitude.create(ComponentIndexType.PORT_3)
        gimbalJointAttitudeKey = GimbalKey.KeyGimbalJointAttitude.create(ComponentIndexType.PORT_3)
        gimbalModeKey = GimbalKey.KeyGimbalMode.create(ComponentIndexType.PORT_3)
        Log.i(TAG, "M400: rebound gimbal keys to PORT_3 (10s after first PORT_3 video frame)")

        // M400 is single-operator and this RC already owns gimbal authority, but the RC defaults to
        // controlling the wrong gimbal so the dial does nothing. KeyControllingGimbal selects which
        // gimbal the physical dial/sticks drive; point it at PORT_3 (the payload camera in view).
        val current = RemoteControllerKey.KeyControllingGimbal.create().get()
        Log.i(TAG, "M400: RC controllingGimbal before=$current -> setting PORT_3")
        RemoteControllerKey.KeyControllingGimbal.create().set(
            ComponentIndexType.PORT_3,
            onSuccess = { Log.i(TAG, "M400: RC now controlling PORT_3 gimbal") },
            onFailure = { error -> Log.e(TAG, "M400: set controllingGimbal failed: ${error.description()}") }
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

        CameraKey.KeyThermalTemperatureDataEnabled.createCamera(idx, lens).set(true,
            onSuccess = { Log.i(TAG_THERMAL, "ThermalTemperatureDataEnabled=true OK") },
            onFailure = { e -> Log.e(TAG_THERMAL, "set TemperatureDataEnabled failed: ${e.description()}") })

        CameraKey.KeyThermalTemperatureMeasureMode.createCamera(idx, lens).set(ThermalTemperatureMeasureMode.REGION,
            onSuccess = {
                Log.i(TAG_THERMAL, "MeasureMode=REGION OK; setting full-frame area")
                CameraKey.KeyThermalRegionMetersureArea.createCamera(idx, lens).set(DoubleRect(0.0, 0.0, 1.0, 1.0),
                    onSuccess = { Log.i(TAG_THERMAL, "Region area=full-frame OK") },
                    onFailure = { e -> Log.e(TAG_THERMAL, "set Region area failed: ${e.description()}") })
            },
            onFailure = { e -> Log.e(TAG_THERMAL, "set MeasureMode failed: ${e.description()}") })
    }

    private fun disarmThermalMeasurement() {
        thermalArmed = false
    }

    override fun setAutoSensingSwitchChecked(checked: Boolean) {
        findViewById<Switch>(R.id.sw_auto_sensing)?.isChecked = checked
    }

    private fun jsonEscape(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")

    override fun readSettingsJson(): String {
        return buildString {
            append("{")
            append("\"droneName\":\"${jsonEscape(droneName)}\",")
            append("\"videoSource\":\"${getVideoSourceMode().prefValue}\",")
            append("\"streamingMode\":\"${getStreamingMode().prefValue}\",")
            append("\"webrtcResolution\":\"${getWebRTCResolutionPreset().prefValue}\",")
            append("\"webrtcFps\":${getWebRTCFps()},")
            append("\"detectionSource\":\"${getDetectionSource().prefValue}\",")
            append("\"detectionsEnabled\":${isDetectionActiveForUi()},")
            append("\"edgeConfidenceThreshold\":${getEdgeConfidenceThreshold()},")
            append("\"mediamtxServer\":\"${jsonEscape(getMediamtxServer())}\",")
            append("\"rthAltitude\":${DroneController.getRTHAltitude()},")
            append("\"maxFlightHeight\":${DroneController.getMaxFlightHeight()},")
            append("\"maxFlightDistance\":${DroneController.getMaxFlightDistance()},")
            append("\"distanceLimitEnabled\":${DroneController.getDistanceLimitEnabled()},")
            append("\"rcControlMode\":\"${DroneController.getRcControlMode()}\",")
            append("\"rcPairingStatus\":\"${DroneController.getRcPairingStatus()}\",")
            append("\"hdFrequencyBand\":\"${DroneController.getHdFrequencyBand()}\",")
            // Read-only: which aircraft the SDK actually detected and which control profile
            // (speed limits, PID gains, gimbal/payload wiring) was selected for it, so an
            // operator can confirm the right profile is active without opening the app.
            val detectedProductType = productTypeKey.get(ProductType.UNKNOWN) ?: ProductType.UNKNOWN
            val activeControlProfile = DroneControlProfiles.fromProductType(detectedProductType)
            append("\"detectedAircraft\":\"${jsonEscape(detectedProductType.name)}\",")
            append("\"controlProfile\":\"${jsonEscape(activeControlProfile.displayName)}\",")
            // UI grouping metadata: each setting key maps to a group slug so
            // consumers (dashboard, ROS, ...) can render settings in sections.
            append("\"groups\":{")
            append("\"droneName\":\"identity\",")
            append("\"detectedAircraft\":\"identity\",")
            append("\"controlProfile\":\"identity\",")
            append("\"videoSource\":\"video\",")
            append("\"streamingMode\":\"video\",")
            append("\"webrtcResolution\":\"video\",")
            append("\"webrtcFps\":\"video\",")
            append("\"mediamtxServer\":\"video\",")
            append("\"rthAltitude\":\"flight\",")
            append("\"maxFlightHeight\":\"flight\",")
            append("\"maxFlightDistance\":\"flight\",")
            append("\"distanceLimitEnabled\":\"flight\",")
            append("\"detectionsEnabled\":\"detection\",")
            append("\"detectionSource\":\"detection\",")
            append("\"edgeConfidenceThreshold\":\"detection\",")
            append("\"rcControlMode\":\"rc\"")
            append("}")
            append("}")
        }
    }

    override fun setDroneName(name: String): Boolean {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed.length > 32) return false
        droneName = trimmed
        sharedPreferences.edit().putString(PREF_DRONE_NAME, trimmed).apply()
        LyrebirdFlightLogger.setDroneName(trimmed)
        mainHandler.post { updateDroneNameDisplay() }
        Log.i(TAG, "Drone name set to: $trimmed")
        return true
    }

    override fun setVideoSource(value: String): Boolean {
        val mode = VideoSourceMode.entries.firstOrNull { it.prefValue.equals(value, ignoreCase = true) } ?: return false
        mainHandler.post { setVideoSourceMode(mode) }
        return true
    }

    override fun setWebRtcResolution(value: String): Boolean {
        val preset = StreamResolutionPreset.entries.firstOrNull { it.prefValue.equals(value, ignoreCase = true) } ?: return false
        sharedPreferences.edit().putString(PREF_WEBRTC_RESOLUTION, preset.prefValue).apply()
        mainHandler.post { webRTCStreamer?.changeMediaOptions(buildWebRTCOptions()) }
        return true
    }

    override fun setWebRtcFps(value: Int): Boolean {
        if (!WEBRTC_FPS_OPTIONS.contains(value)) return false
        sharedPreferences.edit().putInt(PREF_WEBRTC_FPS, value).apply()
        mainHandler.post { webRTCStreamer?.changeMediaOptions(buildWebRTCOptions()) }
        return true
    }

    override fun setDetectionSource(value: String): Boolean {
        val source = DetectionSource.entries.firstOrNull { it.prefValue.equals(value, ignoreCase = true) } ?: return false
        mainHandler.post { setDetectionSource(source) }
        return true
    }

    override fun setEdgeConfidence(threshold: Float): Boolean {
        if (EDGE_CONFIDENCE_OPTIONS.none { kotlin.math.abs(it - threshold) < 0.001f }) return false
        sharedPreferences.edit().putFloat(PREF_EDGE_CONFIDENCE_THRESHOLD, threshold).apply()
        telemetryCoordinator.edgeConfidenceThreshold = threshold
        return true
    }

    override fun setMediamtxServer(value: String): Boolean {
        val trimmed = value.trim()
        if (trimmed.length > 200) return false
        sharedPreferences.edit().putString(PREF_MEDIAMTX_SERVER, trimmed).apply()
        Log.i(TAG, "Mediamtx server set to: ${if (trimmed.isEmpty()) "auto (client IP)" else trimmed}")
        return true
    }

    private fun getMediamtxServer(): String =
        sharedPreferences.getString(PREF_MEDIAMTX_SERVER, "")?.trim().orEmpty()

    override fun readThermalMaxTempNow(): Double? {
        // Make sure the pipeline is armed even if capture is the very first thermal action.
        armThermalMeasurement()
        return runCatching {
            val idx = thermalCameraIndex()
            val lens = CameraLensType.CAMERA_LENS_THERMAL
            val globalMax = CameraKey.KeyThermalGlobalMaxTemperature.createCamera(idx, lens).get()
            val regionMax = CameraKey.KeyThermalRegionMetersureTemperature.createCamera(
                idx,
                lens
            ).get()?.maxAreaTemperature
            val maxTemp = globalMax ?: regionMax
            Log.i(TAG_THERMAL, "[capture read] idx=$idx globalMax=$globalMax regionMax=$regionMax -> $maxTemp")
            maxTemp
        }.onFailure { Log.e(TAG_THERMAL, "[capture read] error: ${it.message}", it) }.getOrNull()
    }

    override fun hasThermalCamera(): Boolean = runCatching {
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
        applyAircraftConnectionState(initialConnectionState, forceDroneSourceDefault = initialConnectionState)

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

    private fun applyAircraftConnectionState(isConnected: Boolean, forceDroneSourceDefault: Boolean = false) {
        val wasConnected = aircraftConnected
        aircraftConnected = isConnected
        logConnectionKeySnapshot("flightController listener fired: $wasConnected -> $isConnected")
        if (shouldSwitchToDroneVideoSource(isConnected, wasConnected, forceDroneSourceDefault)) {
            sharedPreferences.edit()
                .putString(PREF_VIDEO_SOURCE, VideoSourceMode.DJI.prefValue)
                .putBoolean(PREF_MOCK_VIDEO_ENABLED, false)
                .apply()
            webRTCStreamer?.setVideoSourceMode(VideoSourceMode.DJI)
        }
        if (!isConnected && isDetectionsEnabled() && getDetectionSource() == DetectionSource.DJI_ONBOARD) {
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
        if (isConnected && sharedPreferences.getBoolean(PREF_MOCK_VIDEO_ENABLED, false)) {
            sharedPreferences.edit().putBoolean(PREF_MOCK_VIDEO_ENABLED, false).apply()
            webRTCStreamer?.setMockVideoEnabled(false)
        }
        updateMockVideoVisibility()
        updatePhonePreviewVisibility()
        refreshMockTelemetryMode()
        invalidateOptionsMenu()
        updateDroneStatusView(DroneController.droneStatus)
        reevaluateAircraftIdle()
    }

    private fun shouldSwitchToDroneVideoSource(
        isConnected: Boolean,
        wasConnected: Boolean,
        forceDroneSourceDefault: Boolean
    ): Boolean {
        val shouldSelectDroneSource = forceDroneSourceDefault || !wasConnected
        return isConnected && shouldSelectDroneSource && getVideoSourceMode() != VideoSourceMode.DJI
    }

    private fun updateMockVideoVisibility() {
        findViewById<Switch>(R.id.sw_mock_video)?.let { switch ->
            switch.visibility = android.view.View.GONE
            switch.isChecked = isMockVideoEnabled()
            updateMockVideoToggleUi(switch.isChecked)
        }
        updateMockPreviewVisibility()
    }

    private fun setupPhoneVideoPreview() {
        findViewById<TextureView>(R.id.phone_camera_preview)?.surfaceTextureListener =
            object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                updatePhonePreviewVisibility()
            }

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                stopPhoneCameraPreview()
                return true
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
        }
        updatePhonePreviewVisibility()
    }

    private fun updatePhonePreviewVisibility() {
        val preview = findViewById<TextureView>(R.id.phone_camera_preview)
        val shouldShow = getVideoSourceMode() == VideoSourceMode.PHONE
        preview?.visibility = if (shouldShow) android.view.View.VISIBLE else android.view.View.GONE
        findViewById<TextView>(R.id.phone_camera_preview_label)?.visibility =
            if (shouldShow) android.view.View.VISIBLE else android.view.View.GONE
        if (shouldShow && preview?.isAvailable == true) {
            detectionOverlay?.setVideoScaleMode(DetectionOverlayView.VideoScaleMode.CENTER_CROP)
            startPhoneCameraPreview(preview.surfaceTexture ?: return)
        } else if (!shouldShow) {
            stopPhoneCameraPreview()
        }
    }

    private fun configurePhonePreviewTransform(preview: TextureView, sourceWidth: Int, sourceHeight: Int) {
        val viewWidth = preview.width.toFloat().takeIf { it > 0f } ?: return
        val viewHeight = preview.height.toFloat().takeIf { it > 0f } ?: return
        // Context.getDisplay() is API 30+; ContextCompat.getDisplayOrDefault returns null
        // below that (no crash on API 24-29 devices, which lint flagged as a NewApi crash risk).
        val rotation = ContextCompat.getDisplayOrDefault(this)?.rotation ?: Surface.ROTATION_0
        val matrix = Matrix()
        val viewRect = RectF(0f, 0f, viewWidth, viewHeight)
        val centerX = viewRect.centerX()
        val centerY = viewRect.centerY()
        if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
            val bufferRect = RectF(0f, 0f, sourceHeight.toFloat(), sourceWidth.toFloat()).apply {
                offset(centerX - centerX(), centerY - centerY())
            }
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
            val scale = maxOf(viewHeight / sourceHeight.toFloat(), viewWidth / sourceWidth.toFloat())
            matrix.postScale(scale, scale, centerX, centerY)
            matrix.postRotate(90f * (rotation - 2), centerX, centerY)
        } else {
            val scale = maxOf(viewWidth / sourceWidth.toFloat(), viewHeight / sourceHeight.toFloat())
            matrix.postScale(scale, scale, centerX, centerY)
        }
        preview.setTransform(matrix)
    }

    private fun startPhoneCameraPreview(surfaceTexture: SurfaceTexture) {
        val canStart = getVideoSourceMode() == VideoSourceMode.PHONE &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
            phoneCameraDevice == null
        if (!canStart) return

        runCatching {
            val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: cameraManager.cameraIdList.firstOrNull()

            if (cameraId == null) {
                Log.e(TAG, "No phone camera available for preview")
                stopPhoneCameraPreview()
            } else {
                val characteristics = cameraManager.getCameraCharacteristics(cameraId)
                val previewSize = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    ?.getOutputSizes(SurfaceTexture::class.java)
                    ?.sortedWith(compareBy(
                        { kotlin.math.abs(it.width - 1920) + kotlin.math.abs(it.height - 1080) },
                        { it.width * it.height }
                    ))
                    ?.firstOrNull()
                val phoneFrameSize = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    ?.getOutputSizes(ImageFormat.YUV_420_888)
                    ?.sortedWith(compareBy(
                        { kotlin.math.abs(it.width - 1280) + kotlin.math.abs(it.height - 720) },
                        { it.width * it.height }
                    ))
                    ?.firstOrNull()

                val width = previewSize?.width ?: 1920
                val height = previewSize?.height ?: 1080
                surfaceTexture.setDefaultBufferSize(width, height)
                val surface = Surface(surfaceTexture)
                phonePreviewSurface = surface
                findViewById<TextureView>(R.id.phone_camera_preview)?.let {
                    configurePhonePreviewTransform(it, width, height)
                }

                val thread = HandlerThread("LyrebirdPhonePreview").also { it.start() }
                phoneCameraThread = thread
                phoneCameraHandler = Handler(thread.looper)
                val frameWidth = phoneFrameSize?.width ?: 1280
                val frameHeight = phoneFrameSize?.height ?: 720
                detectionOverlay?.setSourceFrameSize(frameWidth, frameHeight)
                phoneImageReader = ImageReader.newInstance(frameWidth, frameHeight, ImageFormat.YUV_420_888, 3).apply {
                    setOnImageAvailableListener({ reader -> handlePhoneInferenceImage(reader) }, phoneCameraHandler)
                }
                Log.i(TAG, "Phone shared frame reader configured: ${frameWidth}x${frameHeight}")

                cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        phoneCameraDevice = camera
                        createPhonePreviewSession(camera, surface)
                        Log.i(TAG, "Phone camera preview opened: $cameraId ${width}x${height}")
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        Log.w(TAG, "Phone camera preview disconnected")
                        stopPhoneCameraPreview()
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        Log.e(TAG, "Phone camera preview error: $error")
                        stopPhoneCameraPreview()
                    }
                }, phoneCameraHandler)
            }
        }.onFailure { error ->
            Log.e(TAG, "Failed to start phone camera preview: ${error.message}", error)
            stopPhoneCameraPreview()
        }
    }

    private fun createPhonePreviewSession(camera: CameraDevice, surface: Surface) {
        runCatching {
            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
                phoneImageReader?.surface?.let { addTarget(it) }
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            }
            val surfaces = listOfNotNull(surface, phoneImageReader?.surface)
            val callback = object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    phoneCameraSession = session
                    runCatching { session.setRepeatingRequest(request.build(), null, phoneCameraHandler) }
                        .onFailure { Log.e(TAG, "Failed to start phone preview repeating request: ${it.message}", it) }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e(TAG, "Phone camera preview session configure failed")
                    stopPhoneCameraPreview()
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val outputConfigs = surfaces.map { OutputConfiguration(it) }
                val executor = phoneCameraHandler?.let { handler ->
                    java.util.concurrent.Executor { command -> handler.post(command) }
                } ?: mainExecutor
                val sessionConfig = SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    outputConfigs,
                    executor,
                    callback
                )
                camera.createCaptureSession(sessionConfig)
            } else {
                @Suppress("DEPRECATION")
                camera.createCaptureSession(surfaces, callback, phoneCameraHandler)
            }
        }.onFailure { error ->
            Log.e(TAG, "Failed to create phone camera preview session: ${error.message}", error)
            stopPhoneCameraPreview()
        }
    }

    private fun stopPhoneCameraPreview() {
        runCatching { phoneCameraSession?.stopRepeating() }
        runCatching { phoneCameraSession?.close() }
        phoneCameraSession = null
        runCatching { phoneCameraDevice?.close() }
        phoneCameraDevice = null
        runCatching { phoneImageReader?.close() }
        phoneImageReader = null
        runCatching { phonePreviewSurface?.release() }
        phonePreviewSurface = null
        phoneCameraThread?.quitSafely()
        phoneCameraThread = null
        phoneCameraHandler = null
        phoneInferenceBusy.set(false)
        lastPhoneEdgeFrameNs = 0L
    }

    private fun handlePhoneInferenceImage(reader: ImageReader) {
        val image = reader.acquireLatestImage() ?: return
        val timestampNs = System.nanoTime()
        val isPhoneSource = getVideoSourceMode() == VideoSourceMode.PHONE
        if (!isPhoneSource) {
            image.close()
        } else {
            SharedPhoneCameraFrameSource.offerImage(image, timestampNs)
            val controller = edgeDetectionController
            val canRunInference = controller != null &&
                timestampNs - lastPhoneEdgeFrameNs >= PHONE_EDGE_FRAME_INTERVAL_NS &&
                phoneInferenceBusy.compareAndSet(false, true)
            if (!canRunInference) {
                image.close()
            } else {
                lastPhoneEdgeFrameNs = timestampNs
                controller.onYuv420Image(image, timestampNs) {
                    phoneInferenceBusy.set(false)
                }
            }
        }
    }

    private fun setupMockVideoPreview() {
        findViewById<TextureView>(R.id.mock_video_preview)?.surfaceTextureListener =
            object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                updateMockPreviewVisibility()
            }

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                stopMockVideoPreview()
                return true
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
        }
        updateMockPreviewVisibility()
    }

    private fun updateMockPreviewVisibility() {
        val preview = findViewById<TextureView>(R.id.mock_video_preview)
        val label = findViewById<TextView>(R.id.mock_video_preview_label)
        val shouldShow = isMockVideoEnabled()
        preview?.visibility = if (shouldShow) android.view.View.VISIBLE else android.view.View.GONE
        label?.visibility = if (shouldShow) android.view.View.VISIBLE else android.view.View.GONE
        if (shouldShow && preview?.isAvailable == true) {
            startMockVideoPreview(preview.surfaceTexture ?: return)
        } else if (!shouldShow) {
            stopMockVideoPreview()
        }
    }

    private fun startMockVideoPreview(surfaceTexture: SurfaceTexture) {
        if (!isMockVideoEnabled()) return
        if (mockPreviewPlayer != null) {
            runCatching { mockPreviewPlayer?.start() }
            return
        }

        runCatching {
            val descriptor = assets.openFd("mock_video/jellyfish_1080_10s_5mb.mp4")
            val surface = Surface(surfaceTexture)
            mockPreviewPlayer = MediaPlayer().apply {
                setDataSource(descriptor.fileDescriptor, descriptor.startOffset, descriptor.length)
                setSurface(surface)
                isLooping = true
                setOnPreparedListener { player -> player.start() }
                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "Mock preview player error: what=$what extra=$extra")
                    true
                }
                prepareAsync()
            }
            descriptor.close()
            surface.release()
        }.onFailure { error ->
            Log.e(TAG, "Failed to start mock preview: ${error.message}", error)
            stopMockVideoPreview()
        }
    }

    private fun stopMockVideoPreview() {
        mockPreviewPlayer?.let { player ->
            runCatching {
                player.stop()
            }
            runCatching {
                player.reset()
                player.release()
            }
        }
        mockPreviewPlayer = null
    }

    private fun updateMockVideoToggleUi(isEnabled: Boolean) {
        findViewById<Switch>(R.id.sw_mock_video)?.let { switch ->
            switch.text = if (isEnabled) "MOCK VIDEO" else "DJI VIDEO"
            switch.setTextColor(if (isEnabled) 0xFFFFD166.toInt() else 0xFFDDDDDD.toInt())
        }
    }

    private fun refreshMockTelemetryMode() {
        TelemetryProvider.configureMockTelemetry(
            enabled = shouldUseMockTelemetry(),
            baseLatitude = phoneLocation?.latitude,
            baseLongitude = phoneLocation?.longitude,
            baseAltitude = phoneLocation?.altitude
        )
        rebuildTelemetryCache()
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
        val controlLabel = when (controlProfile) {
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
        if (getVideoSourceMode() == VideoSourceMode.DJI) {
            detectionOverlay?.setVideoScaleMode(DetectionOverlayView.VideoScaleMode.CENTER_INSIDE)
        }
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
        val mode = getStreamingMode()
        val message = when (mode) {
            StreamingMode.WEBRTC -> lastWebRTCMetrics.compactLabel()
            StreamingMode.RTMP -> {
                val serverIp = lastClientIp ?: NetworkUtils.getDeviceIpAddress() ?: "127.0.0.1"
                val rtmpUrl = getRtmpUrl(serverIp)
                "RTMP ${if (liveStreamVM.isStreaming()) "running" else "idle"} url $rtmpUrl $lastNativeStreamStatus"
            }
            StreamingMode.RTSP -> {
                val port = getRtspPort()
                val user = getRtspUsername()
                val userPrefix = if (user.isNotEmpty()) "$user@" else ""
                "RTSP ${if (liveStreamVM.isStreaming()) "running" else "idle"} ${userPrefix}port $port path $DJI_RTSP_STREAM_PATH $lastNativeStreamStatus"
            }
            StreamingMode.AGORA -> {
                val channel = getAgoraChannel().ifBlank { "-" }
                "AGORA ${if (liveStreamVM.isStreaming()) "running" else "idle"} ch $channel $lastNativeStreamStatus"
            }
            StreamingMode.GB28181 -> {
                val server = "${getGbServerIp()}:${getGbServerPort()}"
                "GB28181 ${if (liveStreamVM.isStreaming()) "running" else "idle"} server $server $lastNativeStreamStatus"
            }
        }
        footer.text = message
    }

    private fun WebRTCStreamMetrics.toTelemetryJson(): String {
        fun escapeJson(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")
        val lastErrorJson = lastError?.let { "\"${escapeJson(it)}\"" } ?: "null"
        // qualityLimitationReason/framesEncodedNotSent/sendBitrateBps: send-side network stats
        // from WhipPublisher.getStats() (frame-drop investigation, Phase 1) -- absent outside an
        // active WHIP publish or before the first stats poll, hence the null-safe encoding.
        val qualityLimitationReasonJson = qualityLimitationReason?.let { "\"${escapeJson(it)}\"" } ?: "null"
        val framesEncodedNotSentJson = framesEncodedNotSent?.toString() ?: "null"
        val sendBitrateBpsJson = sendBitrateBps?.toString() ?: "null"
        return """{"sourceWidth":$sourceWidth,"sourceHeight":$sourceHeight,"outputWidth":$outputWidth,"outputHeight":$outputHeight,"requestedWidth":$requestedWidth,"requestedHeight":$requestedHeight,"targetFps":$targetFps,"inputFps":$inputFps,"outputFps":$outputFps,"droppedFps":$droppedFps,"averageFrameProcessingMs":$averageFrameProcessingMs,"totalFrames":$totalFrames,"totalDroppedFrames":$totalDroppedFrames,"processingErrors":$processingErrors,"observerCount":$observerCount,"activeCamera":"${escapeJson(activeCamera)}","status":"${escapeJson(status)}","configuredFps":$configuredFps,"saturationState":"${escapeJson(saturationState)}","scaleMode":"${escapeJson(scaleMode)}","recoveryCount":$recoveryCount,"lastError":$lastErrorJson,"qualityLimitationReason":$qualityLimitationReasonJson,"framesEncodedNotSent":$framesEncodedNotSentJson,"sendBitrateBps":$sendBitrateBpsJson}"""
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
        val mode = getStreamingMode()
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
                            streamer.startWhip(whipUrl)
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

                    fun startRtmp(url: String, fallbackAttempt: Boolean = false) {
                        Log.i(TAG, "Starting native DJI RTMP streaming to: $url")
                        liveStreamVM.setRTMPConfig(url)
                        liveStreamVM.startStream(object : CommonCallbacks.CompletionCallback {
                            override fun onSuccess() {
                                if (url != rtmpUrl) {
                                    setRtmpUrl(url)
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
                        })
                    }

                    startRtmp(rtmpUrl)
                }
                StreamingMode.RTSP -> {
                    val requestedPort = getRtspPort()
                    val port = resolveRtspPortForStart()
                    if (port != requestedPort) {
                        setRtspPort(port)
                        Log.w(TAG, "RTSP port $requestedPort is in use, switching to $port")
                        rebuildTelemetryCache()
                        updateStreamingFooter()
                        showStreamToast("RTSP port $requestedPort busy, switched to $port")
                    }
                    val user = getRtspUsername()
                    val pwd = getRtspPassword()
                    Log.i(TAG, "Starting native DJI RTSP server on port $port")
                    liveStreamVM.setRTSPConfig(user, pwd, port)
                    liveStreamVM.startStream(object : CommonCallbacks.CompletionCallback {
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
                    })
                }
                StreamingMode.AGORA -> {
                    val channel = getAgoraChannel()
                    val token = getAgoraToken()
                    val uid = getAgoraUid()
                    Log.i(TAG, "Starting Agora streaming on channel $channel")
                    liveStreamVM.setAgoraConfig(channel, token, uid)
                    liveStreamVM.startStream(object : CommonCallbacks.CompletionCallback {
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
                    })
                }
                StreamingMode.GB28181 -> {
                    val ip = getGbServerIp()
                    val port = getGbServerPort()
                    val serverId = getGbServerId()
                    val agentId = getGbAgentId()
                    val channel = getGbChannel()
                    val localPort = getGbLocalPort()
                    val pwd = getGbPassword()
                    Log.i(TAG, "Starting GB28181 streaming to $ip:$port")
                    liveStreamVM.setGB28181(ip, port, serverId, agentId, channel, localPort, pwd)
                    liveStreamVM.startStream(object : CommonCallbacks.CompletionCallback {
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
                    })
                }
            }
        }

        if (liveStreamVM.isStreaming()) {
            Log.i(TAG, "Stopping currently active native DJI livestream before restart")
            liveStreamVM.stopStream(object : CommonCallbacks.CompletionCallback {
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
            })
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
            liveStreamVM.stopStream(object : CommonCallbacks.CompletionCallback {
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
            })
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
        if (lastClientIp == clientIp && lastWhipUrl != null) return
        Log.i(TAG, "Starting active streaming for $clientIp")
        lastClientIp = clientIp
        rebuildTelemetryCache()
        startActiveStreaming(clientIp)
    }

    override fun restartActiveStreaming() {
        val lastIp = lastClientIp ?: lastWhipUrl?.let { runCatching { Uri.parse(it).host }.getOrNull() } ?: NetworkUtils
            .getDeviceIpAddress() ?: "127.0.0.1"
        startActiveStreaming(lastIp)
    }

    // ==================== End Video Mode Toggle ====================

    // ==================== AutoSensing (AI Detection) Toggle ====================

    private fun setupAutoSensingToggle() {
        detectionOverlay = findViewById(R.id.detection_overlay)

        val sw = findViewById<Switch>(R.id.sw_auto_sensing) ?: return
        sw.setOnCheckedChangeListener(null)
        sw.isChecked = isDetectionsEnabled() && getDetectionSource() == DetectionSource.DJI_ONBOARD
        sw.visibility = android.view.View.GONE
    }

    private fun isDetectionsEnabled(): Boolean {
        return sharedPreferences.getBoolean(
            PREF_DETECTIONS_ENABLED,
            sharedPreferences.getString(
                PREF_DETECTION_SOURCE,
                null
            ) != null && getDetectionSource() != DetectionSource.NONE
        )
    }

    private fun activeDetectionSource(): DetectionSource {
        return if (isDetectionsEnabled()) getDetectionSource() else DetectionSource.NONE
    }

    private fun isDetectionActiveForUi(): Boolean {
        return when (activeDetectionSource()) {
            DetectionSource.NONE -> false
            DetectionSource.DJI_ONBOARD -> isAutoSensingActive
            DetectionSource.YOLO_ON_PHONE -> edgeDetectionController != null
        }
    }

    private fun detectionMenuLabel(): String {
        return if (isDetectionActiveForUi()) {
            "Detections On (${getDetectionSource().menuLabel})"
        } else {
            "Detections Off"
        }
    }

    private fun getDetectionSource(): DetectionSource {
        val stored = sharedPreferences.getString(PREF_DETECTION_SOURCE, null)
        if (stored == null && sharedPreferences.getBoolean(PREF_EDGE_DETECTION_ENABLED, false)) {
            // Legacy migration: edge detection used to be a standalone toggle.
            return DetectionSource.YOLO_ON_PHONE
        }
        // "none" (or an unset pref) stays NONE — detections are off by default.
        return DetectionSource.fromPref(stored)
    }

    private fun setDetectionSource(source: DetectionSource) {
        if (source == DetectionSource.DJI_ONBOARD && !aircraftConnected) {
            Toast.makeText(this, "DJI onboard detections need a connected drone", Toast.LENGTH_SHORT).show()
            return
        }

        stopAutoSensing()
        stopEdgeDetection()

        sharedPreferences.edit()
            .putString(PREF_DETECTION_SOURCE, source.prefValue)
            .putBoolean(
                PREF_EDGE_DETECTION_ENABLED,
                isDetectionsEnabled() && source == DetectionSource.YOLO_ON_PHONE
            )
            .apply()

        findViewById<Switch>(R.id.sw_auto_sensing)?.isChecked = isDetectionsEnabled()
            && source == DetectionSource.DJI_ONBOARD
        findViewById<Switch>(R.id.sw_edge_detection)?.isChecked = isDetectionsEnabled()
            && source == DetectionSource.YOLO_ON_PHONE

        when (activeDetectionSource()) {
            DetectionSource.NONE -> updateEdgeMetricsView(EdgeDetectionMetrics(status = "off"))
            DetectionSource.DJI_ONBOARD -> startAutoSensing()
            DetectionSource.YOLO_ON_PHONE -> startEdgeDetection()
        }

        updateDetectionTelemetryState()
        rebuildTelemetryCache()
        invalidateOptionsMenu()
    }

    override fun setDetectionsEnabled(enabled: Boolean) {
        if (enabled && getDetectionSource() == DetectionSource.DJI_ONBOARD && !aircraftConnected) {
            Toast.makeText(this, "DJI onboard detections need a connected drone", Toast.LENGTH_SHORT).show()
            return
        }

        stopAutoSensing()
        stopEdgeDetection()

        sharedPreferences.edit()
            .putBoolean(PREF_DETECTIONS_ENABLED, enabled)
            .putBoolean(PREF_EDGE_DETECTION_ENABLED, enabled && getDetectionSource() == DetectionSource.YOLO_ON_PHONE)
            .apply()

        findViewById<Switch>(R.id.sw_auto_sensing)?.isChecked = enabled
            && getDetectionSource() == DetectionSource.DJI_ONBOARD
        findViewById<Switch>(R.id.sw_edge_detection)?.isChecked = enabled
            && getDetectionSource() == DetectionSource.YOLO_ON_PHONE

        when (activeDetectionSource()) {
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
        val source = activeDetectionSource()
        val isMock = shouldUseMockTelemetry()
        val selectedSource = getDetectionSource()
        
        TelemetryProvider.currentDetectionSource = source.prefValue
        TelemetryProvider.currentDetectionActive = when (source) {
            DetectionSource.NONE -> false
            DetectionSource.DJI_ONBOARD -> isAutoSensingActive
            DetectionSource.YOLO_ON_PHONE -> edgeDetectionController != null
        }
        TelemetryProvider.currentDetectionModel = when (source) {
            DetectionSource.YOLO_ON_PHONE -> sharedPreferences.getString(PREF_EDGE_MODEL_NAME, null)
            else -> null
        }
        TelemetryProvider.currentDetectionThreshold = when (source) {
            DetectionSource.YOLO_ON_PHONE -> getEdgeConfidenceThreshold()
            else -> null
        }

        telemetryCoordinator.isMockEnabled = isMock
        telemetryCoordinator.isDetectionsEnabled = isDetectionsEnabled()
        telemetryCoordinator.detectionSource = source.prefValue
        telemetryCoordinator.selectedDetectionSource = selectedSource.prefValue
        telemetryCoordinator.detectionMenuLabel = selectedSource.menuLabel
        telemetryCoordinator.isAutoSensingActive = isAutoSensingActive
        telemetryCoordinator.edgeDetectionActive = edgeDetectionController != null
        telemetryCoordinator.edgeModelName = sharedPreferences.getString(PREF_EDGE_MODEL_NAME, null)
        telemetryCoordinator.edgeLabelsName = sharedPreferences.getString(PREF_EDGE_LABELS_NAME, null)
        telemetryCoordinator.edgeConfidenceThreshold = getEdgeConfidenceThreshold()
        telemetryCoordinator.detectedTargetsJson = DetectedTarget.listToJsonArray(currentDetectedTargets).toString()
        telemetryCoordinator.detectedTargetsSize = currentDetectedTargets.size
    }

    private fun showDetectionSourceDialog() {
        val allSources = arrayOf(DetectionSource.DJI_ONBOARD, DetectionSource.YOLO_ON_PHONE)
        val labels = allSources.map { source ->
            if (source == DetectionSource.DJI_ONBOARD && !aircraftConnected) {
                "${source.menuLabel} (connect drone)"
            } else {
                source.menuLabel
            }
        }.toTypedArray()
        val checkedIndex = allSources.indexOf(getDetectionSource()).coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle("Detection source")
            .setSingleChoiceItems(labels, checkedIndex) { dialog, which ->
                setDetectionSource(allSources[which])
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDetectionSettingsDialog() {
        val modelName = sharedPreferences.getString(PREF_EDGE_MODEL_NAME, "Select...")
        val labelsName = sharedPreferences.getString(PREF_EDGE_LABELS_NAME, "Default person")
        val confidence = (getEdgeConfidenceThreshold() * 100).toInt()
        val rows = listOf(
            SettingsActionRow("Source", getDetectionSource().menuLabel),
            SettingsActionRow("YOLO model", modelName),
            SettingsActionRow("YOLO labels", labelsName),
            SettingsActionRow("YOLO confidence", "$confidence%")
        )

        AlertDialog.Builder(this)
            .setTitle("Detection Settings")
            .setAdapter(actionRowAdapter(rows)) { dialog, which ->
                dialog.dismiss()
                when (which) {
                    0 -> showDetectionSourceDialog()
                    1 -> showEdgeFilePicker(REQUEST_EDGE_MODEL_FILE, "Select YOLO TFLite model")
                    2 -> showEdgeFilePicker(REQUEST_EDGE_LABELS_FILE, "Select model labels")
                    3 -> showEdgeConfidenceDialog()
                }
            }
            .setNegativeButton("Close", null)
            .show()
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
            manager.startAutoSensing(object : CommonCallbacks.CompletionCallback {
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
            })
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
            IntelligentFlightManager.getInstance().stopAutoSensing(object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() {
                    Log.i(TAG, "AutoSensing stopped")
                }
                override fun onFailure(error: IDJIError) {
                    Log.e(TAG, "AutoSensing stop failed: ${error.description()}")
                }
            })
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
        sw.isChecked = isDetectionsEnabled() && getDetectionSource() == DetectionSource.YOLO_ON_PHONE
        sw.visibility = android.view.View.GONE
        updateEdgeDetectionToggleUi(isDetectionsEnabled() && getDetectionSource() == DetectionSource.YOLO_ON_PHONE)
    }

    private fun isEdgeDetectionEnabled(): Boolean {
        return isDetectionsEnabled() && getDetectionSource() == DetectionSource.YOLO_ON_PHONE
    }

    private sealed interface EdgeDetectionStartCheck {
        data class Ready(val sourceMode: VideoSourceMode, val modelUri: Uri) : EdgeDetectionStartCheck
        data class UnsupportedSource(val sourceMode: VideoSourceMode) : EdgeDetectionStartCheck
        data class UnsupportedStreamingMode(val streamingMode: StreamingMode) : EdgeDetectionStartCheck
        data class MissingModel(val sourceMode: VideoSourceMode) : EdgeDetectionStartCheck
        object WaitingForDjiVideo : EdgeDetectionStartCheck
    }

    private fun startEdgeDetection() {
        if (edgeDetectionController != null) return

        val startCheck = edgeDetectionStartCheck(getVideoSourceMode(), getEdgeModelUri(), webRTCStreamer)
        if (startCheck !is EdgeDetectionStartCheck.Ready) {
            handleEdgeDetectionStartFailure(startCheck)
            return
        }

        clearAutoSensingState()

        val controller = createEdgeDetectionController(startCheck)
        edgeDetectionController = controller

        configureDetectionOverlay(startCheck.sourceMode)
        controller.start()
        updateDetectionTelemetryState()
        rebuildTelemetryCache()

        attachEdgeDetectionSource(startCheck.sourceMode, controller)
        showEdgeDetectionEnabledMessage()
    }

    private fun edgeDetectionStartCheck(
        sourceMode: VideoSourceMode,
        modelUri: Uri?,
        streamer: WebRTCStreamer?
    ): EdgeDetectionStartCheck {
        return when {
            getStreamingMode() != StreamingMode.WEBRTC -> {
                EdgeDetectionStartCheck.UnsupportedStreamingMode(getStreamingMode())
            }
            sourceMode == VideoSourceMode.MOCK -> {
                EdgeDetectionStartCheck.UnsupportedSource(sourceMode)
            }
            modelUri == null -> {
                EdgeDetectionStartCheck.MissingModel(sourceMode)
            }
            sourceMode == VideoSourceMode.DJI && streamer == null -> {
                EdgeDetectionStartCheck.WaitingForDjiVideo
            }
            else -> {
                EdgeDetectionStartCheck.Ready(sourceMode = sourceMode, modelUri = modelUri)
            }
        }
    }

    private fun handleEdgeDetectionStartFailure(startCheck: EdgeDetectionStartCheck) {
        when (startCheck) {
            is EdgeDetectionStartCheck.UnsupportedStreamingMode -> {
                setDetectionsEnabled(false)
                Toast.makeText(
                    this,
                    "Edge detection with custom YOLO is not supported in ${startCheck.streamingMode.menuLabel} mode",
                    Toast.LENGTH_LONG
                ).show()
            }
            is EdgeDetectionStartCheck.UnsupportedSource -> {
                setDetectionsEnabled(false)
                updateEdgeMetricsView(
                    EdgeDetectionMetrics(status = "source", source = startCheck.sourceMode.prefValue)
                )
                Toast.makeText(
                    this,
                    "Edge detection supports drone and phone camera sources",
                    Toast.LENGTH_SHORT
                ).show()
            }
            is EdgeDetectionStartCheck.MissingModel -> {
                setDetectionsEnabled(false)
                updateEdgeMetricsView(
                    EdgeDetectionMetrics(status = "no-model", source = startCheck.sourceMode.prefValue)
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

    private fun createEdgeDetectionController(
        startCheck: EdgeDetectionStartCheck.Ready
    ): EdgeDetectionController {
        return EdgeDetectionController(
            context = applicationContext,
            config = EdgeDetectionConfig(
                modelUri = startCheck.modelUri,
                labels = getEdgeLabels(),
                sourceLabel = startCheck.sourceMode.prefValue,
                confidenceThreshold = getEdgeConfidenceThreshold()
            ),
            onTargets = { targets ->
                if (activeDetectionSource() == DetectionSource.YOLO_ON_PHONE) {
                    applyDetectedTargets(targets)
                }
            },
            onMetrics = { metrics ->
                lastEdgeMetrics = metrics
                mainHandler.post { updateEdgeMetricsView(metrics) }
            }
        )
    }

    private fun configureDetectionOverlay(sourceMode: VideoSourceMode) {
        when (sourceMode) {
            VideoSourceMode.DJI -> {
                detectionOverlay?.setVideoScaleMode(DetectionOverlayView.VideoScaleMode.CENTER_INSIDE)
                detectionOverlay?.setSourceFrameSize(
                    lastWebRTCMetrics.sourceWidth.takeIf { it > 0 } ?: 16,
                    lastWebRTCMetrics.sourceHeight.takeIf { it > 0 } ?: 9
                )
            }
            VideoSourceMode.PHONE -> {
                detectionOverlay?.setVideoScaleMode(DetectionOverlayView.VideoScaleMode.CENTER_CROP)
            }
            VideoSourceMode.MOCK -> Unit
        }
    }

    private fun attachEdgeDetectionSource(
        sourceMode: VideoSourceMode,
        controller: EdgeDetectionController
    ) {
        if (sourceMode == VideoSourceMode.DJI) {
            webRTCStreamer?.setEdgeDetectionFrameListener(controller)
            return
        }
        webRTCStreamer?.setEdgeDetectionFrameListener(null)
        stopPhoneCameraPreview()
        updatePhonePreviewVisibility()
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
        phoneInferenceBusy.set(false)
        if (getVideoSourceMode() == VideoSourceMode.PHONE) {
            stopPhoneCameraPreview()
            updatePhonePreviewVisibility()
        }
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
        DroneController.droneStatusListener = object : DroneController.DroneStatusListener {
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
        if (!aircraftConnected) {
            statusTv.text = "OFFLINE"
            statusTv.setTextColor(0xFFFF1744.toInt())
            statusTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, DRONE_STATUS_ALERT_TEXT_SIZE_SP)
            return
        }
        // Upgrade IDLE → HOVERING when the FC says the drone is airborne
        val resolved = if (appStatus == DroneController.DroneStatus.IDLE && isFlyingKey.get(false)) {
            DroneController.DroneStatus.HOVERING
        } else {
            appStatus
        }
        val (label, color) = when (resolved) {
            DroneController.DroneStatus.IDLE            -> Pair("IDLE",       0xFFFF9800.toInt())
            DroneController.DroneStatus.TAKING_OFF      -> Pair("TAKEOFF",    0xFFFFC107.toInt())
            DroneController.DroneStatus.HOVERING        -> Pair("HOVER",      0xFF4CAF50.toInt())
            DroneController.DroneStatus.NAVIGATING      -> Pair("NAV",        0xFF2196F3.toInt())
            DroneController.DroneStatus.LANDING         -> Pair("LAND",       0xFFFF9800.toInt())
            DroneController.DroneStatus.RETURNING_HOME  -> Pair("RTH",        0xFFFF9800.toInt())
            DroneController.DroneStatus.MANUAL_OVERRIDE -> Pair("MANUAL",     0xFFF44336.toInt())
            DroneController.DroneStatus.ABORTING        -> Pair("ABORT",      0xFFF44336.toInt())
            DroneController.DroneStatus.MISSION         -> Pair("MISSION",    0xFF00BCD4.toInt())
        }
        statusTv.setTextSize(
            TypedValue.COMPLEX_UNIT_SP,
            if (resolved == DroneController.DroneStatus.IDLE) {
                DRONE_STATUS_ALERT_TEXT_SIZE_SP
            } else {
                DRONE_STATUS_NORMAL_TEXT_SIZE_SP
            }
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
        settingsBackupListener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, _ ->
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
                        Toast.makeText(
                            this,
                            "Synced $count DJI flight log(s) to Lyrebird folder",
                            Toast.LENGTH_SHORT
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
        val httpUp = httpServer != null
        val mavlinkColor = if (mavlinkUp) 0xFF2196F3.toInt() else 0xFFFF1744.toInt()
        val httpColor = if (httpUp) 0xFF4CAF50.toInt() else 0xFFFF1744.toInt()

        val text = "MAVLINK HTTP"
        val spannable = android.text.SpannableString(text)
        spannable.setSpan(
            android.text.style.ForegroundColorSpan(mavlinkColor),
            0, "MAVLINK".length,
            android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        spannable.setSpan(
            android.text.style.ForegroundColorSpan(httpColor),
            "MAVLINK ".length, text.length,
            android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        statusTv.text = spannable
    }

    private fun setupDroneNameDisplay() {
        // Find the TextView in the layout
        val droneNameText = findViewById<TextView>(R.id.text_drone_name)
        droneNameText?.let {
            // Set initial text
            it.text = droneName
            
            // Make it clickable to change drone name
            it.setOnClickListener {
                showDroneNameDialog(isFirstTime = false)
            }
        }

        findViewById<ImageButton>(R.id.button_lyrebird_settings)?.setOnClickListener {
            showLyrebirdSettingsMenu()
        }
    }

    private fun showLyrebirdSettingsMenu() {
        val sdCardStatus = getDroneStorageStatus(CameraStorageLocation.SDCARD, "SD card")
        val internalStatus = getDroneStorageStatus(CameraStorageLocation.INTERNAL, "Internal")
        val previousDialog = lyrebirdSettingsDialog
        val dialog = Dialog(this, R.style.LyrebirdSettingsDialog).apply {
            setContentView(R.layout.dialog_lyrebird_settings_cockpit)
            setCancelable(false)
            setCanceledOnTouchOutside(false)
            setOnDismissListener {
                if (lyrebirdSettingsDialog === this) lyrebirdSettingsDialog = null
            }
        }
        lyrebirdSettingsDialog = dialog

        dialog.findViewById<TextView>(R.id.text_settings_hint)?.text =
            getString(R.string.lyrebird_settings_hint, droneName)
        dialog.findViewById<TextView>(R.id.text_settings_summary)?.text =
            "${getStreamingMode().menuLabel}  /  ${getWebRTCFps()} fps  /  " +
                "${getWebRTCResolutionPreset().menuLabel}  /  " +
                "MAVLink ${if (isMavlinkFlightAllowed()) "allowed" else "blocked"}"

        val cockpit = dialog.findViewById<LinearLayout>(R.id.settings_cockpit_content)
        val columns = listOf(
            LinearLayout(this),
            LinearLayout(this),
            LinearLayout(this),
            LinearLayout(this)
        )
        columns.forEachIndexed { index, column ->
            column.orientation = LinearLayout.VERTICAL
            column.layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.MATCH_PARENT,
                1f
            ).apply {
                if (index > 0) marginStart = dpToPx(5)
            }
            cockpit?.addView(column)
        }

        val aircraftColumn = columns[0]
        addCockpitSection(aircraftColumn, "AIRCRAFT")
        addCockpitRow(aircraftColumn, "Drone name", droneName) { showBrandedDroneNamePage() }
        val detectedProductType = productTypeKey.get(ProductType.UNKNOWN) ?: ProductType.UNKNOWN
        addCockpitRow(aircraftColumn, "Detected aircraft", detectedProductType.name)
        addCockpitRow(
            aircraftColumn,
            "Control profile",
            DroneControlProfiles.fromProductType(detectedProductType).displayName
        )
        addCockpitSection(aircraftColumn, "REMOTE CONTROLLER")
        addCockpitRow(
            aircraftColumn,
            "RC stick mode",
            DroneController.getRcControlMode().uppercase()
        ) { showBrandedRcControlModePage() }
        addCockpitRow(aircraftColumn, "RC pairing", DroneController.getRcPairingStatus().replaceFirstChar { it.uppercase() }) {
            showBrandedRcPairingPage()
        }
        addCockpitRow(aircraftColumn, "HD frequency", DroneController.getHdFrequencyBand())
        addCockpitRow(
            aircraftColumn,
            "MAVLink flight",
            if (isMavlinkFlightAllowed()) "Allowed" else "Blocked"
        ) { showBrandedMavlinkPage() }

        val videoColumn = columns[1]
        addCockpitSection(videoColumn, "VIDEO / STREAM")
        addCockpitRow(videoColumn, "Protocol", getStreamingMode().menuLabel) {
            showBrandedStreamSettingsPage()
        }
        addCockpitRow(videoColumn, "Video source", getVideoSourceMode().menuLabel) {
            showBrandedVideoSourcePage()
        }
        addCockpitRow(videoColumn, "Resolution", getWebRTCResolutionPreset().menuLabel) {
            showBrandedResolutionPage()
        }
        addCockpitRow(videoColumn, "Frame rate", "${getWebRTCFps()} fps") {
            showBrandedFpsPage()
        }
        addCockpitRow(videoColumn, "WHIP server", getMediamtxServer().ifEmpty { "Auto" }) {
            showBrandedMediamtxServerPage()
        }
        addCockpitRow(
            videoColumn,
            "Surface H264",
            if (isDjiSurfaceH264EncoderEnabled()) "Experimental / on" else "Default encoder"
        ) {
            toggleDjiSurfaceH264Encoder()
            showLyrebirdSettingsMenu()
        }

        val flightColumn = columns[2]
        addCockpitSection(flightColumn, "FLIGHT LIMITS")
        addCockpitRow(flightColumn, "RTH altitude", formatCockpitLimit(DroneController.getRTHAltitude())) {
            showBrandedRthAltitudePage()
        }
        addCockpitRow(
            flightColumn,
            "Max flight height",
            formatCockpitLimit(DroneController.getMaxFlightHeight())
        ) { showBrandedMaxFlightHeightPage() }
        addCockpitRow(
            flightColumn,
            "Max distance from home",
            formatCockpitLimit(DroneController.getMaxFlightDistance())
        ) { showBrandedMaxFlightDistancePage() }
        addCockpitRow(
            flightColumn,
            "Distance limit",
            if (DroneController.getDistanceLimitEnabled()) "Enabled" else "Disabled"
        ) {
            DroneController.setDistanceLimitEnabled(!DroneController.getDistanceLimitEnabled())
            showLyrebirdSettingsMenu()
        }

        val detectionColumn = columns[3]
        addCockpitSection(detectionColumn, "DETECTION")
        addCockpitRow(
            detectionColumn,
            "Detections",
            if (isDetectionActiveForUi()) "Enabled" else "Disabled"
        ) {
            setDetectionsEnabled(!isDetectionActiveForUi())
            showLyrebirdSettingsMenu()
        }
        addCockpitRow(detectionColumn, "Detection source", getDetectionSource().menuLabel) {
            showBrandedDetectionSettingsPage()
        }
        addCockpitRow(
            detectionColumn,
            "Confidence",
            "${(getEdgeConfidenceThreshold() * 100).toInt()}%"
        ) { showBrandedConfidencePage() }
        addCockpitSection(detectionColumn, "STORAGE")
        addCockpitStorageRow(detectionColumn, "SD card", sdCardStatus.summary, R.drawable.uxsdk_ic_sdcard) {
            showBrandedFormatStoragePage(CameraStorageLocation.SDCARD, "SD card")
        }
        addCockpitStorageRow(detectionColumn, "Internal storage", internalStatus.summary, R.drawable.uxsdk_ic_emmc) {
            showBrandedFormatStoragePage(CameraStorageLocation.INTERNAL, "Internal storage")
        }

        dialog.findViewById<ImageButton>(R.id.button_settings_close)?.setOnClickListener {
            dialog.dismiss()
        }
        dialog.findViewById<ImageButton>(R.id.button_settings_overflow)?.setOnClickListener { anchor ->
            showLyrebirdSettingsOverflow(anchor)
        }
        dialog.setOnKeyListener { _, keyCode, event ->
            if (keyCode == android.view.KeyEvent.KEYCODE_BACK &&
                event.action == android.view.KeyEvent.ACTION_DOWN
            ) {
                dialog.dismiss()
                true
            } else {
                false
            }
        }
        dialog.window?.setWindowAnimations(0)
        previousDialog?.window?.setWindowAnimations(0)
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(
                android.view.WindowManager.LayoutParams.MATCH_PARENT,
                android.view.WindowManager.LayoutParams.MATCH_PARENT
            )
        }
        previousDialog?.dismiss()
    }

    private fun addCockpitSection(container: LinearLayout, label: String) {
        container.addView(TextView(this).apply {
            text = label
            setTextColor(ContextCompat.getColor(this@FlightDeckActivity, R.color.lyrebird_orange))
            textSize = 9f
            typeface = ResourcesCompat.getFont(this@FlightDeckActivity, R.font.space_grotesk)
            letterSpacing = 0.12f
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(18)
            )
        })
    }

    private fun addCockpitRow(
        container: LinearLayout,
        title: String,
        detail: String,
        onClick: (() -> Unit)? = null
    ) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dpToPx(7), 0, dpToPx(7), 0)
            setBackgroundResource(R.drawable.lyrebird_settings_row)
            isClickable = onClick != null
            isFocusable = onClick != null
            onClick?.let { setOnClickListener { it() } }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(28)
            ).apply { bottomMargin = dpToPx(3) }
        }
        row.addView(TextView(this).apply {
            text = title
            setTextColor(ContextCompat.getColor(this@FlightDeckActivity, R.color.lyrebird_text))
            textSize = 10.5f
            typeface = ResourcesCompat.getFont(this@FlightDeckActivity, R.font.space_grotesk)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(TextView(this).apply {
            text = detail.ifBlank { "Unavailable" }
            setTextColor(ContextCompat.getColor(this@FlightDeckActivity, R.color.lyrebird_muted))
            textSize = 9.5f
            typeface = ResourcesCompat.getFont(this@FlightDeckActivity, R.font.dm_sans)
            gravity = android.view.Gravity.END
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        container.addView(row)
    }

    private fun addCockpitStorageRow(
        container: LinearLayout,
        title: String,
        detail: String,
        iconRes: Int,
        onClick: () -> Unit
    ) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dpToPx(8), dpToPx(3), dpToPx(8), dpToPx(3))
            setBackgroundResource(R.drawable.lyrebird_settings_row)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(44)
            ).apply { bottomMargin = dpToPx(4) }
        }
        row.addView(ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dpToPx(22), dpToPx(22))
            setImageResource(iconRes)
            imageTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this@FlightDeckActivity, R.color.lyrebird_orange)
            )
        })
        row.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            ).apply { marginStart = dpToPx(8) }
            addView(TextView(this@FlightDeckActivity).apply {
                text = title
                setTextColor(ContextCompat.getColor(this@FlightDeckActivity, R.color.lyrebird_text))
                textSize = 12f
                typeface = ResourcesCompat.getFont(this@FlightDeckActivity, R.font.space_grotesk)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            addView(TextView(this@FlightDeckActivity).apply {
                text = detail.ifBlank { "Status unavailable" }
                setTextColor(ContextCompat.getColor(this@FlightDeckActivity, R.color.lyrebird_muted))
                textSize = 10.5f
                typeface = ResourcesCompat.getFont(this@FlightDeckActivity, R.font.dm_sans)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
        })
        container.addView(row)
    }

    private fun formatCockpitLimit(value: Int): String =
        if (value >= 0) "$value m" else "Unavailable"

    private fun showLyrebirdSettingsOverflow(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(0, 1, 0, "Change drone name")
            menu.add(0, 20, 1, "Stream / WebRTC settings")
            menu.add(0, 10, 2, "Detection settings")
            menu.add(0, 22, 3, mavlinkFlightAllowedMenuLabel())
            menu.add(0, 3, 4, "Format SD card")
            menu.add(0, 4, 5, "Format internal storage")
            setOnMenuItemClickListener { item ->
                openBrandedSettingsItem(item.itemId)
                true
            }
            show()
        }
    }

    private fun openBrandedSettingsItem(itemId: Int) {
        when (itemId) {
            1 -> showBrandedDroneNamePage()
            20 -> showBrandedStreamSettingsPage()
            21 -> {
                setDetectionsEnabled(!isDetectionActiveForUi())
                showLyrebirdSettingsMenu()
            }
            10 -> showBrandedDetectionSettingsPage()
            22 -> showBrandedMavlinkPage()
            3 -> showBrandedFormatStoragePage(CameraStorageLocation.SDCARD, "SD card")
            4 -> showBrandedFormatStoragePage(CameraStorageLocation.INTERNAL, "Internal storage")
        }
    }

    private fun showBrandedSettingsSubpage(
        title: String,
        subtitle: String,
        onBack: () -> Unit = ::showLyrebirdSettingsMenu,
        configure: (LinearLayout, Dialog) -> Unit
    ) {
        val previousDialog = lyrebirdSettingsDialog
        val dialog = Dialog(this, R.style.LyrebirdSettingsDialog).apply {
            setContentView(R.layout.dialog_lyrebird_settings_subpage)
            setCancelable(false)
            setCanceledOnTouchOutside(false)
        }
        lyrebirdSettingsDialog = dialog
        dialog.findViewById<TextView>(R.id.text_subpage_title)?.text = title
        dialog.findViewById<TextView>(R.id.text_subpage_hint)?.text = subtitle
        dialog.findViewById<ImageButton>(R.id.button_settings_back)?.setOnClickListener {
            onBack()
        }
        dialog.findViewById<ImageButton>(R.id.button_subpage_close)?.setOnClickListener {
            dialog.dismiss()
        }
        dialog.setOnDismissListener {
            if (lyrebirdSettingsDialog === dialog) lyrebirdSettingsDialog = null
        }
        dialog.setOnKeyListener { _, keyCode, event ->
            if (keyCode == android.view.KeyEvent.KEYCODE_BACK &&
                event.action == android.view.KeyEvent.ACTION_DOWN) {
                onBack()
                true
            } else {
                false
            }
        }
        dialog.window?.setWindowAnimations(0)
        previousDialog?.window?.setWindowAnimations(0)
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(
                android.view.WindowManager.LayoutParams.MATCH_PARENT,
                android.view.WindowManager.LayoutParams.MATCH_PARENT
            )
        }
        previousDialog?.dismiss()
        val content = dialog.findViewById<LinearLayout>(R.id.settings_subpage_content)
        if (content != null) configure(content, dialog)
    }

    private fun addBrandedSettingsSection(container: LinearLayout, label: String) {
        container.addView(TextView(this).apply {
            text = label
            setTextColor(ContextCompat.getColor(this@FlightDeckActivity, R.color.lyrebird_orange))
            textSize = 11f
            typeface = ResourcesCompat.getFont(this@FlightDeckActivity, R.font.space_grotesk)
            letterSpacing = 0.16f
            setPadding(0, dpToPx(6), 0, dpToPx(8))
        })
    }

    private fun addBrandedSettingsRow(
        container: LinearLayout,
        title: String,
        detail: String,
        icon: Int? = null,
        onClick: (() -> Unit)? = null
    ) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            minimumHeight = dpToPx(70)
            setPadding(dpToPx(16), dpToPx(10), dpToPx(14), dpToPx(10))
            setBackgroundResource(R.drawable.lyrebird_settings_row)
            isClickable = onClick != null
            isFocusable = onClick != null
            onClick?.let { setOnClickListener { it() } }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dpToPx(10)
            }
        }
        icon?.let { iconRes ->
            row.addView(android.widget.ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(dpToPx(28), dpToPx(28))
                setImageResource(iconRes)
                imageTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(this@FlightDeckActivity, R.color.lyrebird_teal)
                )
            })
        }
        row.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (icon != null) marginStart = dpToPx(14)
            }
            addView(TextView(this@FlightDeckActivity).apply {
                text = title
                setTextColor(ContextCompat.getColor(this@FlightDeckActivity, R.color.lyrebird_text))
                textSize = 15f
                typeface = ResourcesCompat.getFont(this@FlightDeckActivity, R.font.space_grotesk)
            })
            if (detail.isNotBlank()) addView(TextView(this@FlightDeckActivity).apply {
                text = detail
                setTextColor(ContextCompat.getColor(this@FlightDeckActivity, R.color.lyrebird_muted))
                textSize = 12f
                typeface = ResourcesCompat.getFont(this@FlightDeckActivity, R.font.dm_sans)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, dpToPx(2), 0, 0)
            })
        })
        if (onClick != null) row.addView(TextView(this).apply {
            text = "›"
            setTextColor(ContextCompat.getColor(this@FlightDeckActivity, R.color.lyrebird_teal))
            textSize = 26f
        })
        container.addView(row)
    }

    private fun addBrandedSettingsButton(
        container: LinearLayout,
        label: String,
        onClick: () -> Unit,
        destructive: Boolean = false
    ) {
        container.addView(Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 14f
            typeface = ResourcesCompat.getFont(this@FlightDeckActivity, R.font.space_grotesk)
            setTextColor(
                ContextCompat.getColor(
                    this@FlightDeckActivity,
                    if (destructive) R.color.lyrebird_text else R.color.lyrebird_background
                )
            )
            background = ContextCompat.getDrawable(
                this@FlightDeckActivity,
                if (destructive) R.drawable.lyrebird_settings_row else R.drawable.lyrebird_settings_action
            )
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(52)
            ).apply { bottomMargin = dpToPx(12) }
        })
    }

    private fun showBrandedChoicePage(
        title: String,
        subtitle: String,
        labels: List<String>,
        selectedIndex: Int,
        onSelected: (Int) -> Unit,
        returnPage: () -> Unit,
        onBack: () -> Unit = returnPage
    ) {
        showBrandedSettingsSubpage(title, subtitle, onBack) { container, dialog ->
            labels.forEachIndexed { index, label ->
                addBrandedSettingsRow(
                    container,
                    label,
                    if (index == selectedIndex) "Selected" else "",
                    onClick = {
                        onSelected(index)
                        returnPage()
                    }
                )
            }
        }
    }

    private fun showBrandedEditPage(
        title: String,
        subtitle: String,
        currentValue: String,
        hint: String,
        saveLabel: String = "Save",
        onSave: (String) -> Unit,
        returnPage: () -> Unit,
        onBack: () -> Unit = returnPage
    ) {
        showBrandedSettingsSubpage(title, subtitle, onBack) { container, dialog ->
            val input = EditText(this).apply {
                setText(currentValue)
                this.hint = hint
                setSingleLine(true)
                setTextColor(ContextCompat.getColor(this@FlightDeckActivity, R.color.lyrebird_text))
                setHintTextColor(ContextCompat.getColor(this@FlightDeckActivity, R.color.lyrebird_muted))
                setPadding(dpToPx(16), dpToPx(12), dpToPx(16), dpToPx(12))
                setBackgroundResource(R.drawable.lyrebird_settings_row)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dpToPx(58)
                ).apply { bottomMargin = dpToPx(18) }
            }
            container.addView(input)
            addBrandedSettingsButton(container, saveLabel, {
                onSave(input.text.toString().trim())
                returnPage()
            })
        }
    }

    private fun showBrandedIntegerEditPage(
        title: String,
        subtitle: String,
        currentValue: Int,
        hint: String,
        minimum: Int = 1,
        onSave: (Int) -> Unit,
        returnPage: () -> Unit = ::showLyrebirdSettingsMenu,
        onBack: () -> Unit = returnPage
    ) {
        showBrandedSettingsSubpage(title, subtitle, onBack) { container, dialog ->
            val input = EditText(this).apply {
                setText(if (currentValue >= 0) currentValue.toString() else "")
                this.hint = hint
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                setSingleLine(true)
                setTextColor(ContextCompat.getColor(this@FlightDeckActivity, R.color.lyrebird_text))
                setHintTextColor(ContextCompat.getColor(this@FlightDeckActivity, R.color.lyrebird_muted))
                setPadding(dpToPx(16), dpToPx(12), dpToPx(16), dpToPx(12))
                setBackgroundResource(R.drawable.lyrebird_settings_row)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dpToPx(58)
                ).apply { bottomMargin = dpToPx(18) }
            }
            container.addView(input)
            addBrandedSettingsButton(container, "Save", {
                val value = input.text.toString().trim().toIntOrNull()
                if (value == null || value < minimum) {
                    Toast.makeText(
                        this,
                        "Enter a whole number of at least $minimum",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    onSave(value)
                    returnPage()
                }
            })
        }
    }

    private fun showBrandedRcControlModePage() {
        val modes = listOf("jp", "usa", "ch", "custom")
        showBrandedChoicePage(
            "RC STICK MODE",
            "Choose the stick mapping used by the remote controller",
            modes.map { it.uppercase() },
            modes.indexOf(DroneController.getRcControlMode()).coerceAtLeast(0),
            onSelected = { index -> DroneController.setRcControlMode(modes[index]) },
            returnPage = ::showLyrebirdSettingsMenu,
            onBack = ::showLyrebirdSettingsMenu
        )
    }

    private fun showBrandedRcPairingPage() {
        showBrandedSettingsSubpage("RC PAIRING", "Link the remote controller to this aircraft") { container, _ ->
            addBrandedSettingsSection(container, "CURRENT STATUS")
            addBrandedSettingsRow(container, "Pairing status", DroneController.getRcPairingStatus())
            addBrandedSettingsButton(container, "Start pairing", {
                DroneController.requestRcPairing()
                showBrandedRcPairingPage()
            })
            addBrandedSettingsButton(container, "Stop pairing", {
                DroneController.stopRcPairing()
                showBrandedRcPairingPage()
            })
        }
    }

    private fun showBrandedRthAltitudePage() {
        showBrandedIntegerEditPage(
            "RTH ALTITUDE",
            "Height used when return-to-home is commanded",
            DroneController.getRTHAltitude(),
            "Altitude in metres",
            onSave = { DroneController.setRTHAltitude(it) }
        )
    }

    private fun showBrandedMaxFlightHeightPage() {
        showBrandedIntegerEditPage(
            "MAX FLIGHT HEIGHT",
            "Upper altitude limit reported by the aircraft",
            DroneController.getMaxFlightHeight(),
            "Height in metres",
            onSave = { DroneController.setMaxFlightHeight(it) }
        )
    }

    private fun showBrandedMaxFlightDistancePage() {
        showBrandedIntegerEditPage(
            "MAX DISTANCE FROM HOME",
            "Horizontal distance limit from the recorded home point",
            DroneController.getMaxFlightDistance(),
            "Distance in metres",
            onSave = { DroneController.setMaxFlightDistance(it) }
        )
    }

    private fun showBrandedDroneNamePage() {
        showBrandedEditPage(
            "DRONE NAME",
            "A stable name used by telemetry and video paths",
            droneName,
            "e.g. mini3, alpha, scout",
            onSave = { value ->
                droneName = value.ifBlank { DEFAULT_DRONE_NAME }
                sharedPreferences.edit().putString(PREF_DRONE_NAME, droneName).apply()
                LyrebirdFlightLogger.setDroneName(droneName)
                updateDroneNameDisplay()
                Toast.makeText(this, "Drone name saved: $droneName", Toast.LENGTH_SHORT).show()
            },
            returnPage = ::showLyrebirdSettingsMenu,
            onBack = ::showLyrebirdSettingsMenu
        )
    }

    private fun showBrandedStreamSettingsPage() {
        val mode = getStreamingMode()
        showBrandedSettingsSubpage("STREAM / WEBRTC", "Media path and sender configuration") { container, _ ->
            addBrandedSettingsSection(container, "PROTOCOL")
            addBrandedSettingsRow(container, "Streaming protocol", mode.menuLabel, R.drawable.uxsdk_ic_setting_hd) {
                showBrandedStreamingModePage()
            }
            when (mode) {
                StreamingMode.WEBRTC -> {
                    addBrandedSettingsSection(container, "WEBRTC")
                    val server = sharedPreferences.getString(PREF_MEDIAMTX_SERVER, "")?.trim().orEmpty()
                    addBrandedSettingsRow(container, "WHIP server", server.ifEmpty { "Auto" }) {
                        showBrandedMediamtxServerPage()
                    }
                    addBrandedSettingsRow(container, "Video source", getVideoSourceMode().menuLabel) {
                        showBrandedVideoSourcePage()
                    }
                    addBrandedSettingsRow(container, "Frame rate", "${getWebRTCFps()} fps") {
                        showBrandedFpsPage()
                    }
                    addBrandedSettingsRow(container, "Resolution", getWebRTCResolutionPreset().menuLabel) {
                        showBrandedResolutionPage()
                    }
                    addBrandedSettingsRow(
                        container,
                        "Surface H264 encoder",
                        if (isDjiSurfaceH264EncoderEnabled()) "Experimental / enabled" else "Default WebRTC encoder"
                    ) {
                        toggleDjiSurfaceH264Encoder()
                        showBrandedStreamSettingsPage()
                    }
                }
                StreamingMode.RTMP -> addBrandedSettingsRow(container, "RTMP server", getRtmpUrl(NetworkUtils.getDeviceIpAddress() ?: "127.0.0.1")) {
                    showRtmpConfigDialog()
                }
                StreamingMode.RTSP -> addBrandedSettingsRow(container, "RTSP configuration", "Port ${getRtspPort()}") {
                    showRtspConfigDialog()
                }
                StreamingMode.AGORA -> addBrandedSettingsRow(container, "Agora configuration", getAgoraChannel().ifEmpty { "None" }) {
                    showAgoraConfigDialog()
                }
                StreamingMode.GB28181 -> addBrandedSettingsRow(container, "GB28181 configuration", getGbServerIp().ifEmpty { "None" }) {
                    showGb28181ConfigDialog()
                }
            }
        }
    }

    private fun showBrandedStreamingModePage() {
        val modes = StreamingMode.entries.toList()
        showBrandedChoicePage(
            "STREAMING PROTOCOL",
            "Choose the transport used by this aircraft",
            modes.map { it.menuLabel },
            modes.indexOf(getStreamingMode()).coerceAtLeast(0),
            onSelected = { index ->
                setStreamingMode(modes[index])
                if (telemetryServer?.hasClients() == true || lastWhipUrl != null) restartActiveStreaming()
            },
            returnPage = ::showBrandedStreamSettingsPage,
            onBack = ::showBrandedStreamSettingsPage
        )
    }

    private fun showBrandedMediamtxServerPage() {
        showBrandedEditPage(
            "WHIP SERVER",
            "Leave blank to use the first telemetry client address",
            sharedPreferences.getString(PREF_MEDIAMTX_SERVER, "").orEmpty(),
            "host or host:port",
            onSave = { value ->
                sharedPreferences.edit().putString(PREF_MEDIAMTX_SERVER, value).apply()
            },
            returnPage = ::showBrandedStreamSettingsPage,
            onBack = ::showBrandedStreamSettingsPage
        )
    }

    private fun showBrandedFpsPage() {
        showBrandedChoicePage(
            "WEBRTC FRAME RATE",
            "The requested sender cadence",
            WEBRTC_FPS_OPTIONS.map { "$it fps" },
            WEBRTC_FPS_OPTIONS.indexOf(getWebRTCFps()).coerceAtLeast(0),
            onSelected = { index ->
                val fps = WEBRTC_FPS_OPTIONS[index]
                sharedPreferences.edit().putInt(PREF_WEBRTC_FPS, fps).apply()
                webRTCStreamer?.changeMediaOptions(buildWebRTCOptions())
            },
            returnPage = ::showBrandedStreamSettingsPage,
            onBack = ::showBrandedStreamSettingsPage
        )
    }

    private fun showBrandedResolutionPage() {
        val presets = StreamResolutionPreset.entries.toList()
        showBrandedChoicePage(
            "WEBRTC RESOLUTION",
            "Choose the sender output size",
            presets.map { if (it.width > 0 && it.height > 0) "${it.menuLabel} (${it.width}x${it.height})" else it.menuLabel },
            presets.indexOf(getWebRTCResolutionPreset()).coerceAtLeast(0),
            onSelected = { index ->
                sharedPreferences.edit().putString(PREF_WEBRTC_RESOLUTION, presets[index].prefValue).apply()
                webRTCStreamer?.changeMediaOptions(buildWebRTCOptions())
            },
            returnPage = ::showBrandedStreamSettingsPage,
            onBack = ::showBrandedStreamSettingsPage
        )
    }

    private fun showBrandedVideoSourcePage() {
        val sources = WebRTCStreamer.VideoSourceMode.entries.toList()
        showBrandedChoicePage(
            "VIDEO SOURCE",
            "Choose the pixels supplied to the stream",
            sources.map { it.menuLabel },
            sources.indexOf(getVideoSourceMode()).coerceAtLeast(0),
            onSelected = { index -> setVideoSourceMode(sources[index]) },
            returnPage = ::showBrandedStreamSettingsPage,
            onBack = ::showBrandedStreamSettingsPage
        )
    }

    private fun showBrandedDetectionSettingsPage() {
        showBrandedSettingsSubpage("DETECTION", "On-device and aircraft perception controls") { container, _ ->
            addBrandedSettingsSection(container, "SOURCE")
            addBrandedSettingsRow(container, "Detection source", getDetectionSource().menuLabel, R.drawable.uxsdk_ic_vision_sensors) {
                showBrandedDetectionSourcePage()
            }
            addBrandedSettingsRow(container, "YOLO model", sharedPreferences.getString(PREF_EDGE_MODEL_NAME, "Select...").orEmpty()) {
                showEdgeFilePicker(REQUEST_EDGE_MODEL_FILE, "Select YOLO TFLite model")
            }
            addBrandedSettingsRow(container, "YOLO labels", sharedPreferences.getString(PREF_EDGE_LABELS_NAME, "Default person").orEmpty()) {
                showEdgeFilePicker(REQUEST_EDGE_LABELS_FILE, "Select model labels")
            }
            addBrandedSettingsRow(container, "Confidence threshold", "${(getEdgeConfidenceThreshold() * 100).toInt()}%") {
                showBrandedConfidencePage()
            }
        }
    }

    private fun showBrandedDetectionSourcePage() {
        val sources = listOf(DetectionSource.DJI_ONBOARD, DetectionSource.YOLO_ON_PHONE)
        showBrandedChoicePage(
            "DETECTION SOURCE",
            "Choose where target detections are produced",
            sources.map { it.menuLabel },
            sources.indexOf(getDetectionSource()).coerceAtLeast(0),
            onSelected = { index -> setDetectionSource(sources[index]) },
            returnPage = ::showBrandedDetectionSettingsPage,
            onBack = ::showBrandedDetectionSettingsPage
        )
    }

    private fun showBrandedConfidencePage() {
        showBrandedChoicePage(
            "CONFIDENCE THRESHOLD",
            "Minimum confidence for reported targets",
            EDGE_CONFIDENCE_OPTIONS.map { "${(it * 100).toInt()}%" },
            EDGE_CONFIDENCE_OPTIONS.indexOfFirst { kotlin.math.abs(it - getEdgeConfidenceThreshold()) < 0.001f }
                .coerceAtLeast(0),
            onSelected = { index ->
                val threshold = EDGE_CONFIDENCE_OPTIONS[index]
                sharedPreferences.edit().putFloat(PREF_EDGE_CONFIDENCE_THRESHOLD, threshold).apply()
                if (isEdgeDetectionEnabled()) {
                    stopEdgeDetection()
                    startEdgeDetection()
                } else {
                    updateEdgeMetricsView(lastEdgeMetrics.copy(confidenceThreshold = threshold))
                }
            },
            returnPage = ::showBrandedDetectionSettingsPage,
            onBack = ::showBrandedDetectionSettingsPage
        )
    }

    private fun showBrandedMavlinkPage() {
        if (isMavlinkFlightAllowed()) {
            setMavlinkFlightAllowed(false)
            showLyrebirdSettingsMenu()
            return
        }
        showBrandedSettingsSubpage("MAVLINK FLIGHT CONTROL", "This grants command authority to connected ground stations") { container, dialog ->
            addBrandedSettingsSection(container, "SAFETY")
            addBrandedSettingsRow(
                container,
                "Allow flight commands",
                "Takeoff, landing, RTH and missions will be accepted",
                R.drawable.uxsdk_ic_drone
            )
            addBrandedSettingsButton(container, "Allow flight control", {
                setMavlinkFlightAllowed(true)
                showLyrebirdSettingsMenu()
            })
        }
    }

    private fun showBrandedFormatStoragePage(location: CameraStorageLocation, label: String) {
        val status = getDroneStorageStatus(location, label)
        showBrandedSettingsSubpage("FORMAT $label".uppercase(), "Destructive media operation") { container, dialog ->
            addBrandedSettingsSection(container, "CURRENT STATUS")
            addBrandedSettingsRow(container, status.label, status.summary, R.drawable.uxsdk_ic_sdcard)
            container.addView(TextView(this).apply {
                text = "This deletes all media on the drone $label. Stop recording first, then continue only if you are sure."
                setTextColor(ContextCompat.getColor(this@FlightDeckActivity, R.color.lyrebird_danger))
                textSize = 14f
                setPadding(0, dpToPx(8), 0, dpToPx(18))
            })
            addBrandedSettingsButton(container, "Format $label", {
                dialog.dismiss()
                formatDroneStorage(location, label)
            }, destructive = true)
        }
    }
    
    private fun updateDroneNameDisplay() {
        val droneNameText = findViewById<TextView>(R.id.text_drone_name)
        droneNameText?.text = droneName
    }

    private fun setupKeyListeners() {
        setupBatteryAndRthListeners()
        setupStorageListeners()
        setupFlightStateListeners()
        setupTelemetryListeners()
    }

    private fun setupBatteryAndRthListeners() {
        KeyManager.getInstance().listen(chargeRemainingKey, this) { _, newValue ->
            chargeRemainingProcessor.onNext(newValue ?: 0)
        }
        KeyManager.getInstance().listen(goHomeAssessmentKey, this) { _, newValue ->
            goHomeAssessmentProcessor.onNext(newValue ?: LowBatteryRTHInfo())
        }
        KeyManager.getInstance().listen(seriousLowBatteryKey, this) { _, newValue ->
            seriousLowBatteryThresholdProcessor.onNext(newValue ?: 0)
        }
        KeyManager.getInstance().listen(lowBatteryKey, this) { _, newValue ->
            lowBatteryThresholdProcessor.onNext(newValue ?: 0)
        }
        KeyManager.getInstance().listen(timeNeededToLandKey, this) { _, newValue ->
            timeNeededToLandProcessor.onNext(newValue?.timeNeededToLand ?: 0)
        }
    }

    private fun setupStorageListeners() {
        KeyManager.getInstance().listen(cameraStorageInfosKey, this) { _, newValue ->
            if (isSdCardInserted(newValue)) {
                preferSdCardStorage(newValue)
            }
        }
    }

    private fun setupFlightStateListeners() {
        // Keep isAirborne in DroneController in sync with FC telemetry — used by
        // VirtualStickVM to gate manual-override detection: only fire when airborne
        // (prevents ground-level RC drift false-positives) or during autonomous flight.
        KeyManager.getInstance().listen(isFlyingKey, this) { _, newValue ->
            val flying = newValue ?: false
            val wasFlying = DroneController.isAirborne
            DroneController.isAirborne = flying
            mainHandler.post { updateDroneStatusView(DroneController.droneStatus) }
            // Flight log session lifecycle: open a new file on takeoff, close it on landing.
            if (!wasFlying && flying) {
                LyrebirdFlightLogger.startSession()
                // Start AutoSensing on takeoff if DJI onboard detections are selected.
                if (activeDetectionSource() == DetectionSource.DJI_ONBOARD && !isAutoSensingActive) {
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
        setupRthModeOverrideListener()
    }

    private fun setupRthModeOverrideListener() {
        // Detect RTH triggered from the RC controller (not from our server HTTP request).
        // When the server triggers RTH it calls startReturnToHome() which sets droneStatus
        // to RETURNING_HOME BEFORE the DJI SDK switches to GO_HOME flight mode.
        // If we see GO_HOME but our status is not RETURNING_HOME, the pilot pressed the
        // RTH button on the physical controller → activate manual override so the server
        // cannot accidentally interfere with the returning drone.
        KeyManager.getInstance().listen(flightModeKey, this) { _, newValue ->
            mainHandler.post {
                cachedFlightMode = newValue ?: FlightMode.UNKNOWN
                reevaluateAircraftIdle()
            }
            if (newValue == FlightMode.GO_HOME &&
                DroneController.droneStatus != DroneController.DroneStatus.RETURNING_HOME) {
                mainHandler.post { DroneController.activateManualOverride() }
            }
        }
    }

    // ==================== Aircraft idle (low-power / eco) detection ====================

    /**
     * Detects the aircraft sitting idle on the ground (motors off, connected, not airborne)
     * and shows a notice telling the pilot to wake it with the manual both-sticks-down-and-
     * inwards gesture (there is no reliable app-side motor-start on every airframe). The overlay
     * is delayed by [idleDetectDebounceMs] so transient states never flash it.
     */
    private fun setupAircraftIdleMonitor() {
        cachedFlightMode = KeyManager.getInstance().getValue(flightModeKey) ?: FlightMode.UNKNOWN
        cachedSatelliteCount = KeyManager.getInstance().getValue(satelliteCountKey) ?: -1
        // Flight mode is also observed by setupRthModeOverrideListener (same key, same observer),
        // which keeps cachedFlightMode in sync and re-evaluates idle.
        KeyManager.getInstance().listen(satelliteCountKey, this) { _, newValue ->
            mainHandler.post {
                cachedSatelliteCount = newValue ?: -1
                reevaluateAircraftIdle()
            }
        }
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
     * @param detail a smaller status line under [message] naming the startup phase under way
     *   (registering with the DJI SDK, starting servers, waiting for the aircraft) — otherwise
     *   the overlay is just a spinner with no indication of what it is actually doing.
     */
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

    private fun showLoadingOverlay(visible: Boolean, message: String? = null, detail: String? = null) {
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
        // Keep altitude display in sync with every position update
        KeyManager.getInstance().listen(location3DKey, this) { _, newValue ->
            latestAltitudeMetres = newValue?.altitude ?: 0.0
            mainHandler.post { updateAltitudeView() }
            rebuildTelemetryCache()
        }
        KeyManager.getInstance().listen(gimbalAttitudeKey, this) { _, newValue ->
            latestGimbalPitchDegrees = newValue?.pitch ?: 0.0
            mainHandler.post { updateAltitudeView() }
            rebuildTelemetryCache()
        }
        updateAltitudeView()
        // High-frequency keys: rebuild cache on every SDK push
        KeyManager.getInstance().listen(attitudeKey, this) { _, _ -> rebuildTelemetryCache() }
        KeyManager.getInstance().listen(compassHeadKey, this) { _, _ -> rebuildTelemetryCache() }
        KeyManager.getInstance().listen(flightSpeedKey, this) { _, _ -> rebuildTelemetryCache() }
        KeyManager.getInstance().listen(batteryKey, this) { _, _ -> rebuildTelemetryCache() }
    }
    
    private fun loadDroneName() {
        val storedName = sharedPreferences.getString(PREF_DRONE_NAME, DEFAULT_DRONE_NAME)?.trim().orEmpty()
        droneName = storedName.ifEmpty { DEFAULT_DRONE_NAME }

        if (storedName.isEmpty()) {
            // Persist a safe fallback to avoid generating malformed URLs like //whip.
            sharedPreferences.edit().putString(PREF_DRONE_NAME, droneName).apply()
        }

        if (storedName.isEmpty()) {
            // First time - prompt user for drone name
            mainHandler.post {
                showDroneNameDialog(isFirstTime = true)
            }
        } else {
            Log.i(TAG, "Loaded drone name: $droneName")
            LyrebirdFlightLogger.setDroneName(droneName)
        }
    }
    
    private fun showDroneNameDialog(isFirstTime: Boolean = false) {
        val input = EditText(this)
        input.hint = "e.g., drone_01, alpha, scout"
        if (!isFirstTime) {
            input.setText(droneName)
        }
        
        val builder = AlertDialog.Builder(this)
            .setTitle(if (isFirstTime) "Drone Name" else "Change Drone Name")
            .setMessage(if (isFirstTime) "Please enter a unique name for this drone:" else "Enter new name for this drone:")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    droneName = name
                    sharedPreferences.edit().putString(PREF_DRONE_NAME, droneName).apply()
                    LyrebirdFlightLogger.setDroneName(droneName)
                    Log.i(TAG, "Drone name set to: $droneName")
                    Toast.makeText(this, "Drone name saved: $droneName", Toast.LENGTH_SHORT).show()
                    updateDroneNameDisplay()
                } else {
                    droneName = DEFAULT_DRONE_NAME
                    sharedPreferences.edit().putString(PREF_DRONE_NAME, droneName).apply()
                    LyrebirdFlightLogger.setDroneName(droneName)
                    Toast.makeText(this, "Using default name: $droneName", Toast.LENGTH_SHORT).show()
                    updateDroneNameDisplay()
                }
            }
        
        if (isFirstTime) {
            builder.setCancelable(false)
        } else {
            builder.setNegativeButton("Cancel", null)
        }
        
        builder.show()
    }

    private fun showMediamtxServerDialog() {
        val input = EditText(this)
        val current = sharedPreferences.getString(PREF_MEDIAMTX_SERVER, "").orEmpty()
        input.hint = "host o host:puerto (ej: 10.233.132.21:8889)"
        input.setText(current)

        AlertDialog.Builder(this)
            .setTitle("WHIP / mediamtx server")
            .setMessage("Opcional: si se deja vacío, se usa la IP del primer cliente de telemetría.")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val value = input.text.toString().trim()
                sharedPreferences.edit().putString(PREF_MEDIAMTX_SERVER, value).apply()
                val shown = if (value.isEmpty()) "auto (client IP)" else value
                Log.i(TAG, "Mediamtx server set to: $shown")
                Toast.makeText(this, "WHIP server: $shown", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showWebRTCFpsDialog() {
        val currentFps = getWebRTCFps()
        val labels = WEBRTC_FPS_OPTIONS.map { "$it fps" }.toTypedArray()
        val checkedIndex = WEBRTC_FPS_OPTIONS.indexOf(currentFps).coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle("WebRTC frame rate")
            .setSingleChoiceItems(labels, checkedIndex) { dialog, which ->
                val selectedFps = WEBRTC_FPS_OPTIONS[which]
                sharedPreferences.edit().putInt(PREF_WEBRTC_FPS, selectedFps).apply()
                webRTCStreamer?.changeMediaOptions(buildWebRTCOptions())
                Toast.makeText(this, "WebRTC FPS: $selectedFps", Toast.LENGTH_SHORT).show()
                Log.i(TAG, "WebRTC frame rate set to $selectedFps fps")
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showWebRTCResolutionDialog() {
        val presets = StreamResolutionPreset.entries.toTypedArray()
        val labels = presets.map {
            if (it.width > 0 && it.height > 0) "${it.menuLabel} (${it.width}x${it.height})" else it.menuLabel
        }.toTypedArray()
        val checkedIndex = presets.indexOf(getWebRTCResolutionPreset()).coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle("WebRTC resolution")
            .setSingleChoiceItems(labels, checkedIndex) { dialog, which ->
                val selectedPreset = presets[which]
                sharedPreferences.edit().putString(PREF_WEBRTC_RESOLUTION, selectedPreset.prefValue).apply()
                webRTCStreamer?.changeMediaOptions(buildWebRTCOptions())
                Toast.makeText(this, "WebRTC resolution: ${selectedPreset.menuLabel}", Toast.LENGTH_SHORT).show()
                Log.i(
                    TAG,
                    "WebRTC resolution set to ${if (selectedPreset.width > 0 && selectedPreset.height > 0) "${selectedPreset.width}x${selectedPreset.height}" else "native source"}"
                )
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showVideoSourceDialog() {
        val sources = VideoSourceMode.entries.toTypedArray()
        val labels = sources.map { source ->
            when (source) {
                VideoSourceMode.DJI -> "Drone camera"
                VideoSourceMode.PHONE -> "Phone back camera"
                VideoSourceMode.MOCK -> "Mock MP4"
            }
        }.toTypedArray()
        val checkedIndex = sources.indexOf(getVideoSourceMode()).coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle("Video source")
            .setSingleChoiceItems(labels, checkedIndex) { dialog, which ->
                setVideoSourceMode(sources[which])
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showStreamSettingsDialog() {
        val mode = getStreamingMode()
        val rows = mutableListOf<SettingsActionRow>()
        rows.add(SettingsActionRow("Streaming protocol", mode.menuLabel))

        when (mode) {
            StreamingMode.WEBRTC -> {
                val configuredServer = sharedPreferences.getString(PREF_MEDIAMTX_SERVER, "")?.trim().orEmpty()
                val serverLabel = configuredServer.ifEmpty { "Auto" }
                rows.add(SettingsActionRow("WHIP server", serverLabel))
                rows.add(SettingsActionRow("Video source", getVideoSourceMode().menuLabel))
                rows.add(SettingsActionRow("WebRTC FPS", "${getWebRTCFps()} fps"))
                rows.add(SettingsActionRow("WebRTC resolution", getWebRTCResolutionPreset().menuLabel))
                rows.add(
                    SettingsActionRow(
                        "Surface H264 encoder",
                        if (isDjiSurfaceH264EncoderEnabled()) {
                            "Experimental / enabled (restart required)"
                        } else {
                            "Default WebRTC encoder"
                        }
                    )
                )
            }
            StreamingMode.RTMP -> {
                val rtmpUrl = getRtmpUrl(NetworkUtils.getDeviceIpAddress() ?: "127.0.0.1")
                rows.add(SettingsActionRow("RTMP Server URL", rtmpUrl))
            }
            StreamingMode.RTSP -> {
                val port = getRtspPort()
                rows.add(SettingsActionRow("RTSP Config", "Port $port"))
            }
            StreamingMode.AGORA -> {
                val channel = getAgoraChannel().ifEmpty { "None" }
                rows.add(SettingsActionRow("Agora.io Config", "Channel: $channel"))
            }
            StreamingMode.GB28181 -> {
                val ip = getGbServerIp().ifEmpty { "None" }
                rows.add(SettingsActionRow("GB28181 Config", "Server: $ip"))
            }
        }

        AlertDialog.Builder(this)
            .setTitle("Video Streaming Configuration")
            .setAdapter(actionRowAdapter(rows)) { dialog, which ->
                dialog.dismiss()
                if (which == 0) {
                    showStreamingModeDialog()
                } else {
                    when (mode) {
                        StreamingMode.WEBRTC -> {
                            when (which) {
                                1 -> showMediamtxServerDialog()
                                2 -> showVideoSourceDialog()
                                3 -> showWebRTCFpsDialog()
                                4 -> showWebRTCResolutionDialog()
                                5 -> toggleDjiSurfaceH264Encoder()
                            }
                        }
                        StreamingMode.RTMP -> showRtmpConfigDialog()
                        StreamingMode.RTSP -> showRtspConfigDialog()
                        StreamingMode.AGORA -> showAgoraConfigDialog()
                        StreamingMode.GB28181 -> showGb28181ConfigDialog()
                    }
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showStreamingModeDialog() {
        val modes = StreamingMode.entries.toTypedArray()
        val labels = modes.map { it.menuLabel }.toTypedArray()
        val checkedIndex = modes.indexOf(getStreamingMode()).coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle("Select Streaming Protocol")
            .setSingleChoiceItems(labels, checkedIndex) { dialog, which ->
                val selectedMode = modes[which]
                setStreamingMode(selectedMode)
                dialog.dismiss()
                if (telemetryServer?.hasClients() == true || lastWhipUrl != null) {
                    restartActiveStreaming()
                }
                showStreamSettingsDialog()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showRtmpConfigDialog() {
        val input = EditText(this).apply {
            setText(getRtmpUrl(NetworkUtils.getDeviceIpAddress() ?: "127.0.0.1"))
            hint = "rtmp://<host>:<port>/live/stream_id"
        }
        AlertDialog.Builder(this)
            .setTitle("Configure RTMP URL")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val url = input.text.toString().trim()
                setRtmpUrl(url)
                if (url.isNotEmpty() && (telemetryServer?.hasClients() == true || lastWhipUrl != null)) {
                    restartActiveStreaming()
                }
                showStreamSettingsDialog()
            }
            .setNegativeButton("Cancel") { _, _ -> showStreamSettingsDialog() }
            .show()
    }

    private fun showRtspConfigDialog() {
        val context = this
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 20)
        }
        val portInput = EditText(context).apply {
            setText(getRtspPort().toString())
            hint = "RTSP Server Port (e.g. 8554)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        val userInput = EditText(context).apply {
            setText(getRtspUsername())
            hint = "Username (Optional)"
        }
        val pwdInput = EditText(context).apply {
            setText(getRtspPassword())
            hint = "Password (Optional)"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        layout.addView(TextView(context).apply { text = "RTSP Server Port" })
        layout.addView(portInput)
        layout.addView(TextView(context).apply { text = "Username" })
        layout.addView(userInput)
        layout.addView(TextView(context).apply { text = "Password" })
        layout.addView(pwdInput)

        AlertDialog.Builder(context)
            .setTitle("RTSP Server Configuration")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val port = portInput.text.toString().toIntOrNull() ?: 8554
                setRtspPort(port)
                setRtspUsername(userInput.text.toString())
                setRtspPassword(pwdInput.text.toString())
                if (telemetryServer?.hasClients() == true || lastWhipUrl != null) {
                    restartActiveStreaming()
                }
                showStreamSettingsDialog()
            }
            .setNegativeButton("Cancel") { _, _ -> showStreamSettingsDialog() }
            .show()
    }

    private fun showAgoraConfigDialog() {
        val context = this
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 20)
        }
        val channelInput = EditText(context).apply {
            setText(getAgoraChannel())
            hint = "Agora Channel Name"
        }
        val tokenInput = EditText(context).apply {
            setText(getAgoraToken())
            hint = "Agora Token (Optional)"
        }
        val uidInput = EditText(context).apply {
            setText(getAgoraUid())
            hint = "Agora User ID (UID, e.g. 0)"
        }
        layout.addView(TextView(context).apply { text = "Channel ID" })
        layout.addView(channelInput)
        layout.addView(TextView(context).apply { text = "Token" })
        layout.addView(tokenInput)
        layout.addView(TextView(context).apply { text = "User ID (UID)" })
        layout.addView(uidInput)

        AlertDialog.Builder(context)
            .setTitle("Agora.io Configuration")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                setAgoraChannel(channelInput.text.toString())
                setAgoraToken(tokenInput.text.toString())
                setAgoraUid(uidInput.text.toString())
                if (telemetryServer?.hasClients() == true || lastWhipUrl != null) {
                    restartActiveStreaming()
                }
                showStreamSettingsDialog()
            }
            .setNegativeButton("Cancel") { _, _ -> showStreamSettingsDialog() }
            .show()
    }

    private fun showGb28181ConfigDialog() {
        val context = this
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 20)
        }
        val ipInput = EditText(context).apply {
            setText(getGbServerIp())
            hint = "SIP Server IP"
        }
        val portInput = EditText(context).apply {
            setText(getGbServerPort().toString())
            hint = "SIP Server Port (e.g. 5060)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        val serverIdInput = EditText(context).apply {
            setText(getGbServerId())
            hint = "SIP Server ID (20 characters)"
        }
        val agentIdInput = EditText(context).apply {
            setText(getGbAgentId())
            hint = "SIP Agent ID (20 characters)"
        }
        val channelInput = EditText(context).apply {
            setText(getGbChannel())
            hint = "Video Channel ID (20 characters)"
        }
        val localPortInput = EditText(context).apply {
            setText(getGbLocalPort().toString())
            hint = "Local SIP Port (e.g. 5061)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        val pwdInput = EditText(context).apply {
            setText(getGbPassword())
            hint = "Password"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        val scroll = android.widget.ScrollView(context).apply {
            addView(layout)
        }

        layout.addView(TextView(context).apply { text = "SIP Server IP" })
        layout.addView(ipInput)
        layout.addView(TextView(context).apply { text = "SIP Server Port" })
        layout.addView(portInput)
        layout.addView(TextView(context).apply { text = "Server ID" })
        layout.addView(serverIdInput)
        layout.addView(TextView(context).apply { text = "Agent ID" })
        layout.addView(agentIdInput)
        layout.addView(TextView(context).apply { text = "Channel ID" })
        layout.addView(channelInput)
        layout.addView(TextView(context).apply { text = "Local Port" })
        layout.addView(localPortInput)
        layout.addView(TextView(context).apply { text = "Password" })
        layout.addView(pwdInput)

        AlertDialog.Builder(context)
            .setTitle("GB28181 Configuration")
            .setView(scroll)
            .setPositiveButton("Save") { _, _ ->
                setGbServerIp(ipInput.text.toString())
                setGbServerPort(portInput.text.toString().toIntOrNull() ?: 5060)
                setGbServerId(serverIdInput.text.toString())
                setGbAgentId(agentIdInput.text.toString())
                setGbChannel(channelInput.text.toString())
                setGbLocalPort(localPortInput.text.toString().toIntOrNull() ?: 5061)
                setGbPassword(pwdInput.text.toString())
                if (telemetryServer?.hasClients() == true || lastWhipUrl != null) {
                    restartActiveStreaming()
                }
                showStreamSettingsDialog()
            }
            .setNegativeButton("Cancel") { _, _ -> showStreamSettingsDialog() }
            .show()
    }

    private fun showEdgeConfidenceDialog() {
        val currentThreshold = getEdgeConfidenceThreshold()
        val labels = EDGE_CONFIDENCE_OPTIONS.map { "${(it * 100).toInt()}%" }.toTypedArray()
        val checkedIndex = EDGE_CONFIDENCE_OPTIONS.indexOfFirst { kotlin.math.abs(it - currentThreshold) < 0.001f }
            .takeIf { it >= 0 }
            ?: EDGE_CONFIDENCE_OPTIONS.indexOfFirst { kotlin.math.abs(it - DEFAULT_EDGE_CONFIDENCE_THRESHOLD) < 0.001f }
                .coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle("Edge confidence threshold")
            .setSingleChoiceItems(labels, checkedIndex) { dialog, which ->
                val selectedThreshold = EDGE_CONFIDENCE_OPTIONS[which]
                sharedPreferences.edit().putFloat(PREF_EDGE_CONFIDENCE_THRESHOLD, selectedThreshold).apply()
                if (isEdgeDetectionEnabled()) {
                    stopEdgeDetection()
                    startEdgeDetection()
                } else {
                    updateEdgeMetricsView(lastEdgeMetrics.copy(confidenceThreshold = selectedThreshold))
                }
                invalidateOptionsMenu()
                Toast.makeText(
                    this,
                    "Edge confidence: ${(selectedThreshold * 100).toInt()}%",
                    Toast.LENGTH_SHORT
                ).show()
                Log.i(TAG, "Edge confidence threshold set to $selectedThreshold")
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showFormatStorageDialog(location: CameraStorageLocation, label: String) {
        val status = getDroneStorageStatus(location, label)
        AlertDialog.Builder(this)
            .setTitle("Format $label")
            .setMessage("${status.dialogText}\n\nThis deletes all media on the drone $label. Stop recording first, then continue only if you are sure.")
            .setPositiveButton("Format") { _, _ ->
                formatDroneStorage(location, label)
            }
            .setNegativeButton("Cancel", null)
            .show()
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
        val mode = when (mavCameraMode) {
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
            }
        )
    }

    private fun setDefaultVideoMode() {
        val currentMode = KeyManager.getInstance().getValue(cameraModeKey)
        if (currentMode == CameraMode.VIDEO_NORMAL) {
            return
        }

        KeyManager.getInstance()
            .setValue(cameraModeKey, CameraMode.VIDEO_NORMAL, object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() {
                Log.i(TAG, "Default camera mode set to video")
            }

            override fun onFailure(error: IDJIError) {
                Log.w(TAG, "Could not set default camera mode to video: ${error.description()}")
            }
        })
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

        KeyManager.getInstance().setValue(cameraStorageLocationKey, CameraStorageLocation.SDCARD, object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() {
                Log.i(TAG, "Default camera storage set to SD card")
            }

            override fun onFailure(error: IDJIError) {
                Log.w(TAG, "Could not set default camera storage to SD card: ${error.description()}")
            }
        })
    }

    private fun isSdCardInserted(storageInfos: CameraStorageInfos?): Boolean {
        return storageInfos
            ?.cameraStorageInfoList
            ?.firstOrNull { it.storageType == CameraStorageLocation.SDCARD }
            ?.storageState == SDCardLoadState.INSERTED
    }

    private fun getDroneStorageStatus(location: CameraStorageLocation, label: String): DroneStorageStatus {
        val storageInfos: CameraStorageInfos? = KeyManager.getInstance().getValue(cameraStorageInfosKey)
        val info = storageInfos?.cameraStorageInfoList?.firstOrNull { it.storageType == location }
        val parts = listOfNotNull(
            info?.getStorageLeftCapacity()?.takeIf { it >= 0 }?.let { "${formatCapacity(it)} free" },
            info?.getStorageState()?.name?.takeIf { it.isNotBlank() && it != "UNKNOWN" },
            info?.getAvailableVideoDuration()?.takeIf { it >= 0 }?.let { "video ${formatDuration(it)}" }
        )
        return DroneStorageStatus(label, parts.ifEmpty { listOf("status unavailable") }.joinToString(", "))
    }

    private fun formatCapacity(megabytes: Int): String {
        return if (megabytes >= 1024) {
            String.format(java.util.Locale.US, "%.1f GB", megabytes / 1024.0)
        } else {
            "$megabytes MB"
        }
    }

    private fun formatDuration(seconds: Int): String {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
    }

    private fun formatDroneStorage(location: CameraStorageLocation, label: String) {
        Toast.makeText(this, "Formatting $label...", Toast.LENGTH_SHORT).show()
        val key = KeyTools.createKey(CameraKey.KeyFormatStorage, ComponentIndexType.LEFT_OR_MAIN)
        KeyManager.getInstance()
            .performAction(key, location, object : CommonCallbacks.CompletionCallbackWithParam<EmptyMsg> {
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
        })
    }

    private fun buildWhipUrl(clientIp: String): String {
        val safeDroneName = droneName.trim().ifEmpty {
            DEFAULT_DRONE_NAME
        }

        val configuredServer = sharedPreferences.getString(PREF_MEDIAMTX_SERVER, "")
            ?.trim()
            .orEmpty()

        val hostAndPort = if (configuredServer.isEmpty()) {
            "$clientIp:$MEDIAMTX_WHIP_PORT"
        } else {
            var normalized = configuredServer
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
        if (ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) != PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED) {
            // Request permissions if not granted
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1)
            return
        }
        runCatching {
            locationManager?.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 1f, locationListener)
            locationManager?.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000L, 1f, locationListener)
        }.onFailure { error ->
            Log.e(TAG, "Error requesting location updates: ${error.message}", error)
        }
    }

    private fun startSensorUpdates() {
        sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.also { accelerometer ->
            sensorManager?.registerListener(sensorListener, accelerometer, SensorManager.SENSOR_DELAY_NORMAL)
        }
        sensorManager?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.also { magneticField ->
            sensorManager?.registerListener(sensorListener, magneticField, SensorManager.SENSOR_DELAY_NORMAL)
        }
        sensorManager?.getDefaultSensor(Sensor.TYPE_PRESSURE)?.also { pressure ->
            sensorManager?.registerListener(sensorListener, pressure, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }
    
    private fun updateOrientationAngles() {
        // Update rotation matrix, which is needed to update orientation angles.
        SensorManager.getRotationMatrix(rotationMatrix, null, accelerometerReading, magnetometerReading)
        // "rotationMatrix" now has up-to-date information.

        SensorManager.getOrientation(rotationMatrix, orientationAngles)
        // "orientationAngles" now has up-to-date information.
        
        // Convert azimuth to degrees (0-360)
        var azimuth = Math.toDegrees(orientationAngles[0].toDouble())
        if (azimuth < 0) {
            azimuth += 360.0
        }
        phoneHeading = azimuth
    }

    private fun startServers() {
        val deviceIp = NetworkUtils.getDeviceIpAddress()
        
        // Start mDNS/Zeroconf service registration (RECOMMENDED for discovery)
        discoveryManager.registerMdnsService(droneSerialNumber, HTTP_PORT, TELEMETRY_PORT)
        
        // Start Discovery Server (UDP broadcast/multicast fallback)
        discoveryManager.startDiscoveryServer()
        
        // Start HTTP Command Server
        if (!NetworkUtils.isPortInUse(HTTP_PORT)) {
            runCatching {
                httpServer = SimpleHttpServer(HTTP_PORT, this, mavlinkCommandSink)
                httpServer?.start()
                Log.i(TAG, "HTTP server started on $deviceIp:$HTTP_PORT")
            }.onFailure { error ->
                Log.e(TAG, "Error starting HTTP server: ${error.message}", error)
            }
        } else {
            Log.w(TAG, "HTTP port $HTTP_PORT already in use")
        }

        // Start Telemetry Server
        if (!NetworkUtils.isPortInUse(TELEMETRY_PORT)) {
            runCatching {
                telemetryServer = TelemetryServer(TELEMETRY_PORT, ::getTelemetryJson, ::getGapTelemetryJson)
                telemetryServer?.onFirstClientConnected = { clientIp ->
                    Log.i(TAG, "First telemetry client from $clientIp — starting active streaming")
                    mainHandler.post {
                        startStreamingForClient(clientIp)
                    }
                }
                telemetryServer?.start()
                Log.i(TAG, "Telemetry server started on $deviceIp:$TELEMETRY_PORT")
            }.onFailure { error ->
                Log.e(TAG, "Error starting telemetry server: ${error.message}", error)
            }
        } else {
            Log.w(TAG, "Telemetry port $TELEMETRY_PORT already in use")
        }

        // Start the MAVLink 2 telemetry endpoint (no-op unless enabled by preference).
        startMavlinkEndpoint()

        // Both server-start attempts above have now either succeeded or logged why not, so this
        // is the first point where httpServer/mavlinkEndpoint reflect what actually came up.
        updateMavlinkHttpStatusView()

        // WebRTC video via WHIP — create the shared frame source/publisher.
        // WHIP publishing starts automatically when bridge connects to telemetry.
        runCatching {
            webRTCStreamer = WebRTCStreamer(
                context = applicationContext,
                cameraIndex = ComponentIndexType.LEFT_OR_MAIN,
                droneName = droneName,
                options = buildWebRTCOptions(),
                mockVideoEnabled = isMockVideoEnabled()
            )
            webRTCStreamer?.setVideoSourceMode(getVideoSourceMode())
            webRTCStreamer?.listener = object : WebRTCStreamer.WebRTCStreamerListener {
                override fun onServerStarted(ip: String, port: Int) {
                    Log.i(TAG, "WHIP publishing from $ip")
                }
                override fun onServerStopped() {
                    Log.i(TAG, "WebRTC streamer stopped")
                }
                override fun onServerError(error: String) {
                    Log.e(TAG, "WebRTC error: $error")
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

    private fun showServerInfo() {
        val deviceIp = NetworkUtils.getDeviceIpAddress() ?: "Unknown"
        val message = """
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
        detachDefaultLayoutHsiWidgets()

        disarmThermalMeasurement()

        // Unregister system-service listeners FIRST and each on its own guard. The framework
        // LocationManager keeps locationListener in a native global, so if a later teardown
        // step throws and skips this removal, the listener pins the destroyed activity
        // (~8.5 MB leak caught by LeakCanary). These must not depend on the block below.
        try {
            locationManager?.removeUpdates(locationListener)
        } catch (e: Exception) {
            Log.w(TAG, "Error removing location updates: ${e.message}")
        }
        try {
            sensorManager?.unregisterListener(sensorListener)
        } catch (e: Exception) {
            Log.w(TAG, "Error unregistering sensor listener: ${e.message}")
        }

        try {
            // Stop AutoSensing
            stopAutoSensing()

            stopMockVideoPreview()
            stopEdgeDetection()

            // Stop all servers
            telemetryServer?.onFirstClientConnected = null
            telemetryServer?.stop()
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
            discoveryManager.stopDiscoveryServer()
            // Must stop the HTTP server, not just drop the reference: its accept thread and
            // worker pool hold this activity via the command handler. Without stop() the
            // threads keep the destroyed activity alive forever (LeakCanary: recurring
            // FlightDeckActivity + MediaVM leaks via SimpleHttpServer).
            httpServer?.stop()
            httpServer = null
            telemetryServer = null
            webRTCStreamer = null
 
            // Unregister mDNS service
            discoveryManager.unregisterMdnsService()

            // (location + sensor listeners already unregistered at the top of onDestroy)

            // Release Multicast Lock
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
            }

            // Cancel key listeners
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

            mainHandler.removeCallbacksAndMessages(null)
            stopPhoneCameraPreview()

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
    
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return if (handleLyrebirdMenuItem(item.itemId)) true else super.onOptionsItemSelected(item)
    }

    private fun handleLyrebirdMenuItem(itemId: Int): Boolean {
        val action = when (itemId) {
            1 -> { { showDroneNameDialog(isFirstTime = false) } }
            2, 5, 7, 9, 20 -> ::showStreamSettingsDialog
            21 -> { { setDetectionsEnabled(!isDetectionActiveForUi()) } }
            22 -> ::toggleMavlinkFlightAllowed
            8, 10 -> ::showDetectionSettingsDialog
            11 -> { { showEdgeFilePicker(REQUEST_EDGE_MODEL_FILE, "Select YOLO TFLite model") } }
            12 -> { { showEdgeFilePicker(REQUEST_EDGE_LABELS_FILE, "Select model labels") } }
            13 -> ::showEdgeConfidenceDialog
            6 -> ::showVideoSourceDialog
            3 -> { { showFormatStorageDialog(CameraStorageLocation.SDCARD, "SD card") } }
            4 -> { { showFormatStorageDialog(CameraStorageLocation.INTERNAL, "internal storage") } }
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
            KeyManager.getInstance().getValue(serialKey, object : dji.v5.common.callback.CommonCallbacks.CompletionCallbackWithParam<String> {
                override fun onSuccess(serialNumber: String?) {
                    droneSerialNumber = serialNumber?.takeLast(8) ?: "UNKNOWN"
                    Log.i(TAG, "Drone serial number: $droneSerialNumber")
                }
                override fun onFailure(error: dji.v5.common.error.IDJIError) {
                    droneSerialNumber = "UNKNOWN"
                    Log.w(TAG, "Failed to get drone serial: ${error.description()}")
                }
            })
        }.onFailure { error ->
            droneSerialNumber = "UNKNOWN"
            Log.e(TAG, "Error fetching drone serial: ${error.message}", error)
        }
    }



    // ==================== Telemetry Data ====================

    private fun getLocation3D(): LocationCoordinate3D = location3DKey.get(LocationCoordinate3D(0.0, 0.0, .0))
    private fun getAltitude(): Double = altitudeKey.get(0.0)
    private fun getSatelliteCount(): Int = satelliteCountKey.get(-1)
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
    private fun startRoiTracking(latitudeDeg: Double, longitudeDeg: Double, altitudeM: Double) {
        roiTarget = LocationCoordinate3D(latitudeDeg, longitudeDeg, altitudeM)
        if (roiTrackingRunnable != null) return
        // The camera owns the yaw axis while it tracks a point. In yaw-follow mode the aircraft's
        // turns yank the lens toward the flight direction, so the follow mode and the ROI loop
        // fight at the tracking rate — a gimbal that swings between looking ahead and looking at
        // the target. Free yaw is what the loop expects, so the mode is switched before the loop
        // starts and restored when it stops.
        roiPreviousGimbalMode = KeyManager.getInstance().getValue(gimbalModeKey) ?: GimbalMode.YAW_FOLLOW
        KeyManager.getInstance().setValue(
            gimbalModeKey,
            GimbalMode.FREE,
            object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() {
                    Log.i(TAG, "Gimbal yaw freed for ROI tracking")
                }

                override fun onFailure(error: IDJIError) {
                    Log.w(TAG, "Could not free gimbal yaw for ROI: ${error.description()}")
                }
            }
        )
        val runnable = object : Runnable {
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
                gimbalModeKey,
                previous,
                object : CommonCallbacks.CompletionCallback {
                    override fun onSuccess() {
                        Log.i(TAG, "Gimbal yaw follow restored")
                    }

                    override fun onFailure(error: IDJIError) {
                        Log.w(TAG, "Could not restore gimbal yaw follow: ${error.description()}")
                    }
                }
            )
        }
        Log.i(TAG, "ROI tracking cleared")
    }

    /** One correction: where the gimbal should point, less where it reports pointing. */
    private fun trackRoiOnce(target: LocationCoordinate3D) {
        val position = getLocation3D()
        // Before a fix there is no bearing to compute, and the aircraft's own position would
        // read as the Gulf of Guinea. Waiting is the honest answer; the next tick tries again.
        if (position.latitude == 0.0 && position.longitude == 0.0) return

        val aim = RoiControl.aimAt(
            bearingToRoiDeg = DroneController.calculateBearing(
                position.latitude, position.longitude, target.latitude, target.longitude
            ).toDouble(),
            groundDistanceM = DroneController.calculateDistance(
                target.latitude, target.longitude, position.latitude, position.longitude
            ),
            altitudeAboveRoiM = position.altitude - target.altitude,
            headingDeg = getHeading(),
            aircraftPitchDeg = getAttitude().pitch
        )

        val joint = getGimbalJointAttitude()
        val pitchStep = RoiControl.step(
            aim.pitchDeg - joint.pitch, ROI_DEADBAND_DEG, ROI_MAX_STEP_DEG
        )
        val yawStep = RoiControl.step(
            RoiControl.normalizeAngle(aim.yawDeg - joint.yaw), ROI_DEADBAND_DEG, ROI_MAX_STEP_DEG
        )
        if (pitchStep == 0.0 && yawStep == 0.0) return
        mavlinkCommandSink.nudgeGimbal(pitchStep, yawStep)
    }

    private fun getGimbalAttitude(): Attitude = sanitisedAttitude(gimbalAttitudeKey.get())
    private fun getGimbalJointAttitude(): Attitude = sanitisedAttitude(gimbalJointAttitudeKey.get())

    /**
     * A gimbal attitude with DJI's unset marker replaced by zero.
     *
     * When the gimbal saturates -- the aircraft tilted past what it can compensate for -- DJI
     * reports 6553.5 on the affected axis, which is 65535/10 and not an angle. Publishing it
     * unchanged put a 6553-degree pitch on the telemetry stream, where anything reading it as a
     * number took it seriously. A sweep of the aircraft by hand produced it in 23 of 91 samples,
     * so this is the normal case at the edges of travel rather than a rare fault.
     */
    private fun sanitisedAttitude(attitude: Attitude?): Attitude {
        if (attitude == null) return Attitude(0.0, 0.0, 0.0)
        fun axis(value: Double?): Double =
            if (value == null || kotlin.math.abs(value) > MAX_PLAUSIBLE_GIMBAL_DEG) 0.0 else value
        return Attitude(axis(attitude.pitch), axis(attitude.roll), axis(attitude.yaw))
    }
    private fun getHeading(): Double = compassHeadKey.get(0.0)
    private fun getHomeLocation(): LocationCoordinate2D = homeLocationKey.get(LocationCoordinate2D())
    private fun getSpeed(): Velocity3D = flightSpeedKey.get(Velocity3D(0.0, 0.0, 0.0))
    private fun getAttitude(): Attitude = attitudeKey.get(Attitude(0.0, 0.0, 0.0))
    private fun getCameraZoomFocalLength(): Int = cameraZoomFocalLengthKey.get(-1)
    private fun getCameraOpticalFocalLength(): Int = cameraOpticalFocalLengthKey.get(-1)
    private fun getCameraHybridFocalLength(): Int = cameraHybridFocalLengthKey.get(-1)
    private fun getBatteryLevel(): Int = batteryKey.get(-1)
    private fun getFlightMode(): FlightMode = flightModeKey.get(FlightMode.UNKNOWN)

    /**
     * Whether the aircraft is ready to take off / arm.
     *
     * Mirrors the DJI system-status banner: ready when it reads "Ready to Go (GPS)",
     * i.e. [DJIDeviceStatus.NORMAL]. Any other status counts as not ready.
     */
    private fun isReadyToTakeoff(): Boolean =
        DeviceStatusManager.getInstance().getCurrentDJIDeviceStatus() == DJIDeviceStatus.NORMAL

    /** Reason the aircraft cannot take off, or "NONE" when ready. Mirrors the DJI status banner. */
    private fun getTakeoffBlockReason(): String {
        val status = DeviceStatusManager.getInstance().getCurrentDJIDeviceStatus()
        return if (status == DJIDeviceStatus.NORMAL) "NONE" else status.name
    }
    private fun getTimeNeededToGoHome(): Int = goHomeAssessmentProcessor.value.timeNeededToGoHome
    private fun getTimeNeededToLand(): Int = timeNeededToLandProcessor.value

    private fun isHomeSet(): Boolean {
        val shouldLatchHomePoint = !isHomePointSetLatch && !isFlyingKey.get(false) && run {
            val home = getHomeLocation()
            val hasHomeCoordinates = home.latitude != 0.0 && home.longitude != 0.0
            if (!hasHomeCoordinates) {
                false
            } else {
                val current = getLocation3D()
                val distance = DroneController.calculateDistance(
                    current.latitude, current.longitude,
                    home.latitude, home.longitude
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
        val isMock = shouldUseMockTelemetry()
        telemetryCoordinator.isMockEnabled = isMock
        telemetryCoordinator.droneName = droneName

        // Streaming Config
        val activeMode = getStreamingMode()
        telemetryCoordinator.streamingMode = activeMode.prefValue
        telemetryCoordinator.rtspPort = getRtspPort()
        telemetryCoordinator.rtspUser = getRtspUsername()
        telemetryCoordinator.rtspPwd = getRtspPassword()
        val serverIp = lastClientIp ?: "127.0.0.1"
        telemetryCoordinator.rtmpUrl = getRtmpUrl(serverIp)

        // Compute exact consumption path dynamically for backend and telemetry exposure
        val phoneIp = NetworkUtils.getDeviceIpAddress() ?: "127.0.0.1"
        val user = getRtspUsername()
        val pwd = getRtspPassword()
        val port = getRtspPort()
        val path = when (activeMode) {
            StreamingMode.WEBRTC -> "whip"
            StreamingMode.RTSP -> {
                if (user.isNotEmpty() && pwd.isNotEmpty()) {
                    "rtsp://$user:$pwd@$phoneIp:$port$DJI_RTSP_STREAM_PATH"
                } else {
                    "rtsp://$phoneIp:$port$DJI_RTSP_STREAM_PATH"
                }
            }
            StreamingMode.RTMP -> getRtmpUrl(serverIp)
            StreamingMode.AGORA -> "agora://${getAgoraChannel()}"
            StreamingMode.GB28181 -> "gb28181://${getGbServerIp()}:${getGbServerPort()}/${getGbChannel()}"
        }
        telemetryCoordinator.consumptionPath = path

        if (isMock) {
            val sdkMock = TelemetryProvider.currentMockTelemetry(droneName)
            telemetryCoordinator.mockSnapshot = MockTelemetrySnapshot(
                velocity = sdkMock.velocity.toString(),
                heading = sdkMock.heading,
                attitude = sdkMock.attitude.toString(),
                location = sdkMock.location.toString(),
                altitudeAGL = sdkMock.altitudeAGL,
                gimbalAttitude = sdkMock.gimbalAttitude.toString(),
                batteryPercent = sdkMock.batteryPercent,
                satelliteCount = sdkMock.satelliteCount,
                flightMode = sdkMock.flightMode,
                isFlying = sdkMock.isFlying,
                locationLatitude = sdkMock.location.latitude,
                locationLongitude = sdkMock.location.longitude
            )
        } else {
            telemetryCoordinator.mockSnapshot = null
            rebuildRealTelemetryCache()
        }

        // Phone Status (always updated for both mock and real modes)
        telemetryCoordinator.phoneLatitude = phoneLocation?.latitude ?: 0.0
        telemetryCoordinator.phoneLongitude = phoneLocation?.longitude ?: 0.0
        telemetryCoordinator.phoneHeading = phoneHeading
        telemetryCoordinator.phonePressure = phonePressure
        telemetryCoordinator.phoneBattery =
            batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        telemetryCoordinator.wifiRssi = currentWifiRssi()

        // WebRTC Metrics
        telemetryCoordinator.webRtcMetricsJson = lastWebRTCMetrics.toTelemetryJson()

        // Rebuild cache inside the coordinator
        telemetryCoordinator.rebuildTelemetryCache()
    }

    private fun currentWifiRssi(): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val network = connectivityManager?.activeNetwork ?: return -100
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return -100
            if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return -100
            return (capabilities.transportInfo as? android.net.wifi.WifiInfo)?.rssi ?: -100
        }
        @Suppress("DEPRECATION")
        return wifiManager?.connectionInfo?.rssi ?: -100
    }

    // ==================== MAVLink ====================

    /** Promote the old shipped-off default once; later explicit blocks remain operator choices. */
    private fun migrateMavlinkFlightDefault() {
        if (sharedPreferences.getBoolean(PREF_MAVLINK_FLIGHT_DEFAULT_MIGRATED, false)) return
        sharedPreferences.edit()
            .putBoolean(MavlinkEndpointConfig.PREF_ALLOW_FLIGHT, true)
            .putBoolean(PREF_MAVLINK_FLIGHT_DEFAULT_MIGRATED, true)
            .apply()
    }

    private fun isMavlinkFlightAllowed(): Boolean =
        sharedPreferences.getBoolean(MavlinkEndpointConfig.PREF_ALLOW_FLIGHT, true)

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
        AlertDialog.Builder(this)
            .setTitle("Allow MAVLink flight control?")
            .setMessage(
                "A MAVLink ground station (QGroundControl or similar) will be able to command " +
                    "takeoff, landing, return-to-home and missions on this aircraft."
            )
            .setPositiveButton("Allow") { _, _ -> setMavlinkFlightAllowed(true) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setMavlinkFlightAllowed(allowed: Boolean) {
        sharedPreferences.edit().putBoolean(MavlinkEndpointConfig.PREF_ALLOW_FLIGHT, allowed).apply()
        invalidateOptionsMenu()
        Toast.makeText(
            this,
            if (allowed) "MAVLink flight control allowed" else "MAVLink flight control blocked",
            Toast.LENGTH_SHORT
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
        val port = prefIntOrDefault(
            MavlinkEndpointConfig.PREF_PORT, MavlinkEndpointConfig.DEFAULT_GCS_PORT
        )
        // One system id per aircraft, so QGroundControl does not merge two drones into one vehicle.
        // 0 (the default) derives the id from the drone name once renamed, and the serial before that.
        val systemId = MavlinkSystemId.resolve(
            prefIntOrDefault(
                MavlinkEndpointConfig.PREF_SYSTEM_ID, MavlinkEndpointConfig.DEFAULT_SYSTEM_ID
            ),
            sysIdKey()
        )
        return MavlinkEndpointConfig(
            enabled = runCatching {
                sharedPreferences.getBoolean(MavlinkEndpointConfig.PREF_ENABLED, true)
            }.getOrDefault(true),
            targetHost = runCatching {
                sharedPreferences.getString(MavlinkEndpointConfig.PREF_HOST, "")
            }.getOrNull().orEmpty(),
            targetPort = port,
            listenPort = port,
            mode = MavlinkEndpointConfig.Profile.fromPref(
                runCatching {
                    sharedPreferences.getString(MavlinkEndpointConfig.PREF_MODE, null)
                }.getOrNull()
            ),
            systemId = systemId,
            signingKeyHex = runCatching {
                sharedPreferences.getString(MavlinkEndpointConfig.PREF_SIGNING_KEY, "")
            }.getOrNull().orEmpty(),
            missionExecutor = MissionExecutor.fromPref(
                runCatching {
                    sharedPreferences.getString(MavlinkEndpointConfig.PREF_MISSION_EXECUTOR, null)
                }.getOrNull()
            )
        )
    }

    /**
     * The stable identity the MAVLink system id is derived from: the drone name once the operator
     * has renamed it, otherwise the aircraft serial number. Every device shares the default name,
     * so keying off the name alone would give every un-renamed drone the same id.
     */
    private fun sysIdKey(): String {
        val name = droneName.trim()
        return if (name.isNotEmpty() && name != DEFAULT_DRONE_NAME) name else droneSerialNumber
    }

    /** Read an int preference that may have been stored as a string by a hand edit. */
    private fun prefIntOrDefault(key: String, fallback: Int): Int =
        runCatching { sharedPreferences.getInt(key, fallback) }
            .recoverCatching { sharedPreferences.getString(key, null)?.toInt() ?: fallback }
            .getOrDefault(fallback)

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
    /**
     * Whether a home point is somewhere rather than the SDK's unset value.
     *
     * Deliberately not the same question as [isHomeSet], which is a latch meaning "home was
     * recorded on this flight". DJI knows where home is well before that closes, and the check
     * that matters for arithmetic is whether the numbers are a place at all.
     */
    private fun hasRealHomeCoordinates(latitude: Double, longitude: Double): Boolean =
        (latitude != 0.0 || longitude != 0.0) &&
            latitude in -90.0..90.0 &&
            longitude in -180.0..180.0

    private fun buildMavlinkSnapshot(): MavlinkSnapshot {
        val location = getLocation3D()
        val homeLocation = getHomeLocation()
        val speed = getSpeed()
        val attitude = getAttitude()
        val altitudeAgl = getAltitude()
        val gimbalAttitude = getGimbalAttitude()
        val gimbalJoint = getGimbalJointAttitude()
        val goHomeInfo = goHomeAssessmentProcessor.value
        val lrfTarget = lrfTargetLocation

        return MavlinkSnapshot(
            droneName = droneName,
            latitudeDeg = location.latitude,
            longitudeDeg = location.longitude,
            altitudeAslM = location.altitude,
            altitudeAglM = altitudeAgl,
            velocityNorthMps = speed.x,
            velocityEastMps = speed.y,
            velocityDownMps = speed.z,
            rollDeg = attitude.roll,
            pitchDeg = attitude.pitch,
            yawDeg = attitude.yaw,
            headingDeg = getHeading(),
            satelliteCount = getSatelliteCount(),
            batteryPercent = getBatteryLevel(),
            remainingFlightTimeS = goHomeAssessmentProcessor.value.remainingFlightTime,
            homeLatitudeDeg = homeLocation.latitude,
            homeLongitudeDeg = homeLocation.longitude,
            // DJI's home point carries no altitude, so the take-off altitude AMSL is recovered
            // from the difference between the two altitudes the SDK does report.
            homeAltitudeAslM = location.altitude - altitudeAgl,
            homeSet = isHomeSet(),
            flightMode = getFlightMode().name,
            motorsRunning = isFlyingKey.get(false),
            manualOverrideActive = DroneController.isManualOverrideActive,
            armedCommanded = armedCommanded,
            // The sequencer flies through virtual stick, so the mode DJI reports (OFFBOARD) would
            // hide a mission that is actually under way; the heartbeat prefers MISSION instead.
            missionActive = mavlinkMissionSink.isRunning,
            isRecording = isRecordingKey.get() ?: false,

            gimbalRollDeg = gimbalAttitude.roll,
            gimbalPitchDeg = gimbalAttitude.pitch,
            gimbalYawDeg = gimbalAttitude.yaw,
            gimbalJointPitchDeg = gimbalJoint.pitch,
            gimbalJointRollDeg = gimbalJoint.roll,
            gimbalJointYawDeg = gimbalJoint.yaw,
            zoomFocalLengthMm = getCameraZoomFocalLength(),
            opticalFocalLengthMm = getCameraOpticalFocalLength(),
            hybridFocalLengthMm = getCameraHybridFocalLength(),

            lrfDistanceM = lrfDistanceMeters,
            lrfTargetLatitudeDeg = lrfTarget?.latitude,
            lrfTargetLongitudeDeg = lrfTarget?.longitude,
            lrfTargetAltitudeM = lrfTarget?.altitude,

            readyToTakeoff = isReadyToTakeoff(),
            takeoffBlockReason = getTakeoffBlockReason(),

            timeNeededToGoHomeS = getTimeNeededToGoHome(),
            timeNeededToLandS = getTimeNeededToLand(),
            totalFlightTimeS = getTimeNeededToGoHome() + getTimeNeededToLand(),
            maxRadiusCanFlyAndGoHomeM = goHomeInfo.maxRadiusCanFlyAndGoHome.toDouble(),
            batteryNeededToGoHomePercent = goHomeInfo.batteryPercentNeededToGoHome,
            batteryNeededToLandPercent = goHomeInfo.batteryPercentNeededToLand,

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
            videoMode = getStreamingMode().prefValue,
            hasThermal = runCatching { hasThermalCamera() }.getOrDefault(false),

            autoSensingActive = isAutoSensingActive,
            detectionSource = getDetectionSource().prefValue,
            detectionConfidenceThreshold = getEdgeConfidenceThreshold(),
            detectedTargets = currentDetectedTargets.map {
                DetectedTargetSnapshot(it.type, it.left, it.top, it.right, it.bottom, it.confidence)
            }
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
    private fun currentMavlinkVideoStream(): MavlinkVideoStream? =
        MavlinkVideoStream.fromWhipUrl(lastWhipUrl, droneName)

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
    /**
     * Apply one parameter write from a ground station.
     *
     * An allowlist, not a passthrough. Most of the published list is read-only by nature — PID
     * gains belong to the control profile, and the PX4 compatibility parameters are constants
     * that exist only to satisfy QGroundControl's setup checks. Writing those would either do
     * nothing or quietly change flight behaviour from a settings dialog, so anything not named
     * here is refused rather than accepted and dropped.
     */
    private fun applyMavlinkParameter(name: String, value: Float): CommandResult = when (name) {
        PARAM_MAX_HEIGHT -> awaitParameterWrite { done ->
            DroneController.setMaxFlightHeight(value.toInt())
            done(true)
        }

        PARAM_MAX_DISTANCE -> awaitParameterWrite { done ->
            DroneController.setMaxFlightDistance(value.toInt())
            done(true)
        }

        PARAM_DISTANCE_LIMIT -> awaitParameterWrite { done ->
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
        val succeeded = java.util.concurrent.atomic.AtomicBoolean(false)
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
            PARAM_WEBRTC_FPS to getWebRTCFps().toFloat(),
            PARAM_DETECTIONS to if (isDetectionsEnabled()) 1f else 0f,
            PARAM_EDGE_CONFIDENCE to getEdgeConfidenceThreshold(),
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
            "CAL_MAG0_ID" to 131586f
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
    private val mavlinkCommandSink = object : MavlinkCommandSink {

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
                    0
                )
            )
            return CommandResult(MavlinkCommandOutcome.ACCEPTED)
        }

        override fun setGimbalRelative(pitchDeg: Double, yawDeg: Double): CommandResult {
            stopRoiTracking()
            return nudgeGimbal(pitchDeg, yawDeg)
        }

        /** A relative nudge that leaves tracking alone, so the ROI loop can use it. */
        fun nudgeGimbal(pitchDeg: Double, yawDeg: Double): CommandResult {
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
                    yawIgnored = yawDeg == 0.0
                )
            )
        }

        override fun setRegionOfInterest(
            latitudeDeg: Double,
            longitudeDeg: Double,
            altitudeM: Double
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
            val info = Payload.takeFreshLrfReading()
                ?: return CommandResult(MavlinkCommandOutcome.FAILED, "No rangefinder reading")
            if (info.laserMeasureState != LaserMeasureState.NORMAL) {
                // The laser did not lock — no distance, and no point to geo-reference.
                return CommandResult(
                    MavlinkCommandOutcome.FAILED, "Laser state ${info.laserMeasureState}"
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
                resultValue = ((info.distance ?: 0.0) * 100).toInt()
            )
        }

        override fun captureTemperature(): CommandResult {
            val maxTemp = readThermalMaxTempNow()
                ?: return CommandResult(MavlinkCommandOutcome.FAILED, "No thermal reading")
            // Hundredths of a degree, so a fractional reading survives an integer field.
            return CommandResult(
                MavlinkCommandOutcome.ACCEPTED,
                resultValue = (maxTemp * 100).toInt()
            )
        }

        override fun captureThermalImage(): CommandResult {
            // The descriptor names the per-lens files the shutter stored; downloads stay on the
            // media surface (by name), exactly as they do over HTTP. Only the shutter is commanded
            // here — there is no room for the descriptor in an ack.
            val descriptor = Payload.captureThermal(mediaVM)
                ?: return CommandResult(
                    MavlinkCommandOutcome.FAILED, "Thermal capture produced no file"
                )
            return CommandResult(MavlinkCommandOutcome.ACCEPTED, detail = descriptor)
        }

        override fun dropPayload(): CommandResult {
            val profile = DroneControlProfiles.activeProfile()
            val indexType = profile.payloadIndexType
                ?: return CommandResult(
                    MavlinkCommandOutcome.UNSUPPORTED,
                    "${profile.displayName} has no payload drop port"
                )
            val dropped = Payload.dropPayload(
                payloadWidgetVM, indexType,
                profile.dropArmSwitchIndex, profile.dropReleaseButtonIndex
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

        override fun setParameter(name: String, value: Float): CommandResult =
            applyMavlinkParameter(name, value)

        override fun setTextParameter(name: String, value: String): CommandResult {
            val applied = when (name) {
                PARAM_DRONE_NAME -> setDroneName(value)
                PARAM_VIDEO_SOURCE -> setVideoSource(value)
                PARAM_MEDIAMTX -> setMediamtxServer(value)
                PARAM_DETECTION_SOURCE -> setDetectionSource(value)
                PARAM_RC_CONTROL_MODE -> DroneController.setRcControlMode(value)
                PARAM_RTC_RESOLUTION -> setWebRtcResolution(value)
                PARAM_STREAMING_MODE -> {
                    val mode = StreamingMode.entries.firstOrNull { it.prefValue == value }
                    if (mode == null) false else { setStreamingMode(mode); true }
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

        override fun textParameters(): List<Pair<String, String>> = listOf(
            PARAM_DRONE_NAME to droneName,
            PARAM_VIDEO_SOURCE to getVideoSourceMode().prefValue,
            PARAM_MEDIAMTX to getMediamtxServer(),
            PARAM_DETECTION_SOURCE to getDetectionSource().prefValue,
            PARAM_RC_CONTROL_MODE to DroneController.getRcControlMode(),
            PARAM_RTC_RESOLUTION to getWebRTCResolutionPreset().prefValue,
            PARAM_STREAMING_MODE to getStreamingMode().prefValue
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
         */
        override fun captureImage(): CommandResult {
            val endpoint = mavlinkEndpoint ?: return CommandResult(MavlinkCommandOutcome.FAILED)
            endpoint.reportCaptureStarted()
            captureExecutor.execute {
                val file = runCatching { Payload.capturePhoto(mediaVM) }
                    .onFailure { Log.e(TAG, "Capture failed: ${it.message}", it) }
                    .getOrNull()
                endpoint.reportImageCaptured(file != null, file?.fileName.orEmpty())
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
        val succeeded = java.util.concurrent.atomic.AtomicBoolean(false)
        key.action(
            {
                succeeded.set(true)
                latch.countDown()
            },
            { error ->
                Log.w(TAG, "DJI action failed: ${error.description()}")
                latch.countDown()
            }
        )
        val answered = latch.await(ACTION_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        return when {
            !answered -> CommandResult(MavlinkCommandOutcome.FAILED)
            succeeded.get() -> CommandResult(MavlinkCommandOutcome.ACCEPTED)
            else -> CommandResult(MavlinkCommandOutcome.FAILED)
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
                    "(enable it from the settings menu)"
            )
            return CommandResult(MavlinkCommandOutcome.DENIED)
        }
        // A frame signed with the configured key is the Safety Computer; anything else is the
        // Pilot. Before signing every MAVLink command was the Pilot unconditionally, so an
        // installation that configures no key sees exactly the behaviour it saw before.
        val source = if (mavlinkEndpoint?.isTrustedOrigin == true) {
            ControlAuthority.Source.SAFETY
        } else {
            ControlAuthority.Source.PILOT
        }
        if (!ControlAuthority.authorizeControlCommand(source)) {
            ToastUtils.showToast(
                "MAVLink flight command blocked — the Safety Computer has control"
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

    private val mavlinkMotionSink = object : MavlinkMotionSink {

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
            groundSpeedMps: Double
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
            val speed = groundSpeedMps
                .takeIf { it.isFinite() && it > 0.0 }
                ?: DroneControlProfiles.activeProfile().defaultCruiseSpeedMps

            // param5/param6 NaN mean "hold the current position and change only the altitude",
            // which is how QGC expresses Change Altitude. A COMMAND_INT carries them as scaled
            // int32, where NaN converts to 0 -- a real coordinate in the Gulf of Guinea rather
            // than a marker -- so the zero case has to be caught alongside the non-finite one.
            val holdingPosition = !latitudeDeg.isFinite() || !longitudeDeg.isFinite() ||
                (abs(latitudeDeg) < REPOSITION_COORD_EPSILON &&
                    abs(longitudeDeg) < REPOSITION_COORD_EPSILON)
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
                        pending = PendingCommand(PendingKind.YAW, seq)
                    )
                } else {
                    val seq = DroneController.gotoAltitude(
                        // A Change altitude always names one. Defended anyway, because an
                        // altitude of NaN reaches the vertical controller as a setpoint and
                        // every comparison against it is false, so the aircraft would hold
                        // whatever throttle it had rather than refuse.
                        altitudeMeters.takeIf { it.isFinite() } ?: getLocation3D().altitude
                    )
                    CommandResult(
                        MavlinkCommandOutcome.ACCEPTED,
                        pending = PendingCommand(PendingKind.ALTITUDE, seq)
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
            val altitude = altitudeMeters.takeIf { it.isFinite() } ?: getLocation3D().altitude
            val seq = if (yawDeg.isNaN()) {
                DroneController.flyToWaypointNoseForward(
                    latitudeDeg, longitudeDeg, altitude, getHeading(), speed
                )
            } else {
                DroneController.flyToWaypointHoldHeading(
                    latitudeDeg, longitudeDeg, altitude, yawDeg, speed
                )
            }
            return CommandResult(
                MavlinkCommandOutcome.ACCEPTED,
                pending = PendingCommand(PendingKind.WAYPOINT, seq)
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
                pending = PendingCommand(PendingKind.YAW, seq)
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
            faceCentre: Boolean
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
                    "Orbit radius must be at least $MIN_ORBIT_RADIUS_M m"
                )
            }
            supersedeMission("orbit")
            DroneController.orbit(
                centreLatitude = latitudeDeg,
                centreLongitude = longitudeDeg,
                // As everywhere else on this surface, an unset altitude is the one being held.
                targetAltitude = altitudeMeters.takeIf { it.isFinite() }
                    ?: getLocation3D().altitude,
                radiusMeters = radiusMeters,
                tangentialSpeedMps = tangentialSpeedMps,
                clockwise = clockwise,
                arcDegrees = arcDegrees,
                faceCentre = faceCentre
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
            yaw: Float
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
                rightY = pitch
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
                pending = PendingCommand(PendingKind.ALTITUDE, seq)
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
            val source = if (mavlinkEndpoint?.isTrustedOrigin == true) {
                ControlAuthority.Source.SAFETY
            } else {
                ControlAuthority.Source.PILOT
            }
            return if (ControlAuthority.releaseSafetyControl(source)) {
                CommandResult(MavlinkCommandOutcome.ACCEPTED)
            } else {
                CommandResult(
                    MavlinkCommandOutcome.DENIED,
                    "Only the Safety Computer can release safety control"
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
            val (currentSeq, reached) = when (pending.kind) {
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
                val airborne = isFlyingKey.get(false) &&
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
    private val mavlinkMissionSink = object : MavlinkMissionSink {

        private var listener: MissionProgressListener? = null

        @Volatile
        private var missionThread: Thread? = null

        @Volatile
        private var running = false

        override val isRunning: Boolean get() = running

        override fun setProgressListener(listener: MissionProgressListener?) {
            this.listener = listener
        }

        override fun startMission(
            items: List<MissionItem>,
            startIndex: Int,
            executor: MissionExecutor
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
            var speed = items.firstNotNullOfOrNull { it.speedMps }
                ?: DroneControlProfiles.activeProfile().defaultCruiseSpeedMps
            var currentRoi: WaylineLocationCoordinate3D? = null
            val pendingActions = mutableListOf<WaylineActionInfo>()
            val waypointModels = mutableListOf<WaypointInfoModel>()
            for (item in items) {
                if (item.isWaypoint) {
                    val heading = if (item.noseForward) null else item.param4.toDouble()
                    waypointModels.add(
                        WaylineMissionHelper.createWaypointFromLatLon(
                            item.latitudeDeg, item.longitudeDeg, item.altitudeM,
                            waypointModels.size,
                            headingDeg = heading,
                            speedMps = speed,
                            roiTarget = currentRoi,
                            extraActions = pendingActions.toList()
                        )
                    )
                    pendingActions.clear()
                    continue
                }
                item.speedMps?.let { speed = it }
                when (item.command) {
                    Mav.CMD_DO_SET_ROI_LOCATION ->
                        currentRoi = WaylineLocationCoordinate3D(item.latitudeDeg, item.longitudeDeg, item.altitudeM)
                    Mav.CMD_DO_SET_ROI_NONE -> currentRoi = null
                    Mav.CMD_DO_SET_ROI -> currentRoi = if (item.param1.toInt() == Mav.ROI_MODE_LOCATION) {
                        WaylineLocationCoordinate3D(item.latitudeDeg, item.longitudeDeg, item.altitudeM)
                    } else {
                        null
                    }
                    else -> translatePlanActionToWaylineAction(item)?.let { pendingActions.add(it) }
                }
            }
            if (waypointModels.size < 2) {
                // DJI's wayline engine needs a path, not a point.
                return CommandResult(
                    MavlinkCommandOutcome.DENIED,
                    "DJI native missions need at least two waypoints"
                )
            }
            // Actions after the last leg (e.g. a final photo before landing) have no later
            // waypoint to attach to, so they ride along with the last one instead of being lost.
            if (pendingActions.isNotEmpty()) {
                val last = waypointModels.last()
                last.actionInfos = ArrayList(last.actionInfos + pendingActions)
            }

            val finishAction = when (items.lastOrNull {
                it.command == Mav.CMD_NAV_LAND || it.command == Mav.CMD_NAV_RETURN_TO_LAUNCH
            }?.command) {
                Mav.CMD_NAV_LAND -> WaylineFinishedAction.AUTO_LAND
                Mav.CMD_NAV_RETURN_TO_LAUNCH -> WaylineFinishedAction.GO_HOME
                else -> WaylineFinishedAction.NO_ACTION
            }
            val takeoffHeightM = items.firstOrNull { it.command == Mav.CMD_NAV_TAKEOFF }
                ?.altitudeM?.takeIf { it > 0.0 } ?: 20.0
            val missionConfig = WaylineMissionHelper.createMissionConfig(
                finishAction = finishAction,
                securityTakeOffHeightM = takeoffHeightM
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
                }
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
                Mav.CMD_IMAGE_START_CAPTURE -> WaylineActionInfo().apply {
                    actionType = WaylineActionType.TAKE_PHOTO
                    takePhotoParam = ActionTakePhotoParam().apply { payloadPositionIndex = 0 }
                }
                Mav.CMD_VIDEO_START_CAPTURE -> WaylineActionInfo().apply {
                    actionType = WaylineActionType.START_RECORD
                    startRecordParam = ActionStartRecordParam().apply { payloadPositionIndex = 0 }
                }
                Mav.CMD_VIDEO_STOP_CAPTURE -> WaylineActionInfo().apply {
                    actionType = WaylineActionType.STOP_RECORD
                    stopRecordParam = ActionStopRecordParam().apply { payloadPositionIndex = 0 }
                }
                Mav.CMD_DO_GIMBAL_MANAGER_PITCHYAW -> WaylineActionInfo().apply {
                    actionType = WaylineActionType.GIMBAL_ROTATE
                    gimbalRotateParam = ActionGimbalRotateParam().apply {
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
        private fun startOnboard(items: List<MissionItem>, startIndex: Int): CommandResult {
            val lastLegIndex = items.indexOfLast { it.isWaypoint }
            if (lastLegIndex < 0) {
                return CommandResult(MavlinkCommandOutcome.DENIED, "No waypoints in plan")
            }
            var speed = items.firstNotNullOfOrNull { it.speedMps }
                ?: DroneControlProfiles.activeProfile().defaultCruiseSpeedMps

            running = true
            missionThread = thread(name = "MavlinkMission", start = true) {
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
                Mav.CMD_DO_GIMBAL_MANAGER_PITCHYAW -> mavlinkCommandSink.setGimbal(
                    GimbalRotation(
                        mode = GimbalRotationMode.ABSOLUTE,
                        pitchDeg = item.param1.toDouble(),
                        rollDeg = 0.0,
                        yawDeg = item.param2.toDouble(),
                        pitchIgnored = !item.param1.isFinite(),
                        rollIgnored = true,
                        yawIgnored = !item.param2.isFinite()
                    )
                )
                // The legacy mount-control command: pitch is param1, yaw is param3 (param2 is
                // roll, which no DJI gimbal here supports).
                Mav.CMD_DO_MOUNT_CONTROL -> mavlinkCommandSink.setGimbal(
                    GimbalRotation(
                        mode = GimbalRotationMode.ABSOLUTE,
                        pitchDeg = item.param1.toDouble(),
                        rollDeg = 0.0,
                        yawDeg = item.param3.toDouble(),
                        pitchIgnored = !item.param1.isFinite(),
                        rollIgnored = true,
                        yawIgnored = !item.param3.isFinite()
                    )
                )
                // Distance-triggered capture has no executor yet — accepted at upload so the
                // transfer does not fail (see Mav.CMD_DO_SET_CAM_TRIGG_DIST), not actioned here.
                Mav.CMD_DO_SET_CAM_TRIGG_DIST -> Log.d(TAG, "Camera trigger distance not yet implemented, ignoring")
                Mav.CMD_DO_SET_ROI_LOCATION -> mavlinkCommandSink.setRegionOfInterest(
                    item.latitudeDeg, item.longitudeDeg, item.altitudeM
                )
                Mav.CMD_DO_SET_ROI_NONE -> mavlinkCommandSink.clearRegionOfInterest()
                Mav.CMD_DO_SET_ROI -> if (item.param1.toInt() == Mav.ROI_MODE_LOCATION) {
                    mavlinkCommandSink.setRegionOfInterest(
                        item.latitudeDeg, item.longitudeDeg, item.altitudeM
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
        private fun flyLeg(item: MissionItem, speed: Double, isLast: Boolean): Long {
            val yaw = if (item.noseForward) 0.0 else item.param4.toDouble()
            val arrival = DroneController.WaypointArrival(
                acceptanceRadiusM = item.acceptanceRadiusM,
                holdSeconds = item.holdSeconds,
                // The final leg is never a pass-through, whatever the plan says: there is nothing
                // after it to fly on to, so the aircraft settles there.
                passThrough = item.passThrough && !isLast
            )
            return if (item.noseForward) {
                DroneController.flyToWaypointNoseForward(
                    item.latitudeDeg, item.longitudeDeg, item.altitudeM, yaw, speed, arrival
                )
            } else {
                DroneController.flyToWaypointHoldHeading(
                    item.latitudeDeg, item.longitudeDeg, item.altitudeM, yaw, speed, arrival
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
                val airborne = isFlyingKey.get(false) &&
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
                if (DroneController.getWaypointSeq() == seq && DroneController.isWaypointReached()) {
                    return true
                }
                runCatching { Thread.sleep(MISSION_POLL_MS) }.onFailure {
                    Thread.currentThread().interrupt()
                    return false
                }
            }
            return false
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
            val ftpServer = MavlinkFtpServer(
                object : MavlinkFtpServer.FtpFileSource {
                    override fun listFiles(): List<Pair<String, Long>> =
                        Payload.listMediaFiles(mediaVM)

                    override fun readFileBytes(name: String): ByteArray? =
                        Payload.downloadMediaBytes(mediaVM, name)
                },
                ftpExecutor
            )
            val endpoint = MavlinkTelemetryEndpoint(
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
                        params = listOf(
                            command.param1, command.param2, command.param3, command.param4,
                            command.param5, command.param6, command.param7
                        ),
                        result = result.mavResult,
                        signed = mavlinkEndpoint?.isTrustedOrigin == true,
                        senderSystem = command.senderSystem
                    )
                }
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
            endpoint.start()
            mavlinkEndpoint = endpoint
            mavlinkFtpServer = ftpServer
        }.onFailure { error ->
            Log.e(TAG, "Error starting MAVLink endpoint: ${error.message}", error)
        }
    }

    private fun rebuildRealTelemetryCache() {
        val location = getLocation3D()
        val homeLocation = getHomeLocation()
        val goHomeInfo = goHomeAssessmentProcessor.value
        val timeNeededToGoHome = getTimeNeededToGoHome()
        val timeNeededToLand = getTimeNeededToLand()
        
        telemetryCoordinator.speed = getSpeed()
        telemetryCoordinator.heading = getHeading()
        telemetryCoordinator.attitude = getAttitude()
        telemetryCoordinator.location = location
        telemetryCoordinator.altitudeASL = location.altitude
        telemetryCoordinator.altitudeAGL = getAltitude()
        telemetryCoordinator.gimbalAttitude = getGimbalAttitude()
        telemetryCoordinator.gimbalJointAttitude = getGimbalJointAttitude()
        telemetryCoordinator.zoomFl = getCameraZoomFocalLength()
        telemetryCoordinator.hybridFl = getCameraHybridFocalLength()
        telemetryCoordinator.opticalFl = getCameraOpticalFocalLength()
        telemetryCoordinator.zoomRatio = zoomKey.get() ?: 1.0
        telemetryCoordinator.batteryLevel = getBatteryLevel()
        telemetryCoordinator.satelliteCount = getSatelliteCount()
        telemetryCoordinator.homeLocation = homeLocation
        // Zero until home is a real place. DJI reports (0, 0) before it has a home point, and
        // that is a real spot in the Atlantic: measuring to it produced a confident 2,559 km
        // from a stationary aircraft, which is worse than reporting nothing because it looks
        // like an answer.
        telemetryCoordinator.distanceToHome = if (
            hasRealHomeCoordinates(homeLocation.latitude, homeLocation.longitude)
        ) {
            DroneController.calculateDistance(
                location.latitude, location.longitude,
                homeLocation.latitude, homeLocation.longitude
            )
        } else {
            0.0
        }
        telemetryCoordinator.waypointReached = DroneController.isWaypointReached()
        telemetryCoordinator.intermediaryWaypointReached = DroneController.isIntermediaryWaypointReached()
        telemetryCoordinator.yawReached = DroneController.isYawReached()
        telemetryCoordinator.altitudeReached = DroneController.isAltitudeReached()
        telemetryCoordinator.isRecording = isRecordingKey.get() ?: false
        telemetryCoordinator.homeSet = isHomeSet()
        telemetryCoordinator.flightMode = getFlightMode().name
        telemetryCoordinator.waypointSeq = DroneController.getWaypointSeq()
        telemetryCoordinator.yawSeq = DroneController.getYawSeq()
        telemetryCoordinator.altitudeSeq = DroneController.getAltitudeSeq()
        telemetryCoordinator.readyToTakeoff = isReadyToTakeoff()
        telemetryCoordinator.takeoffBlockReason = getTakeoffBlockReason()
        telemetryCoordinator.lrfTarget = lrfTargetLocation
        telemetryCoordinator.isManualOverrideActive = DroneController.isManualOverrideActive
        telemetryCoordinator.isAutoSensingActive = isAutoSensingActive

        // Battery assessment
        telemetryCoordinator.remainingFlightTime = goHomeInfo.remainingFlightTime
        telemetryCoordinator.timeNeededToGoHome = timeNeededToGoHome
        telemetryCoordinator.timeNeededToLand = timeNeededToLand
        telemetryCoordinator.totalTime = timeNeededToGoHome + timeNeededToLand
        telemetryCoordinator.maxRadiusCanFlyAndGoHome = goHomeInfo.maxRadiusCanFlyAndGoHome.toInt()
        telemetryCoordinator.remainingCharge = chargeRemainingProcessor.value.toInt()
        telemetryCoordinator.batteryNeededToLand = goHomeInfo.batteryPercentNeededToLand
        telemetryCoordinator.batteryNeededToGoHome = goHomeInfo.batteryPercentNeededToGoHome
        telemetryCoordinator.seriousLowBatteryThreshold = seriousLowBatteryThresholdProcessor.value
        telemetryCoordinator.lowBatteryThreshold = lowBatteryThresholdProcessor.value
    }


    // ==================== HTTP Server ====================

}


