package com.lyrebird.rc.server

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.lyrebird.rc.DroneControlProfile
import com.lyrebird.rc.DroneControlProfiles
import com.lyrebird.rc.LrfMeasurement
import com.lyrebird.rc.StreamingMode
import com.lyrebird.rc.controller.MavlinkPayloadHost
import com.lyrebird.rc.controller.MavlinkPayloadPolicy
import com.lyrebird.rc.controller.Payload
import com.lyrebird.rc.controller.ProcessRoiRuntimeRegistry
import com.lyrebird.rc.controller.V5PayloadCommandHost
import com.lyrebird.rc.controller.V5PayloadCommandPort
import com.lyrebird.rc.mavlink.CommandResult
import com.lyrebird.rc.mavlink.MavlinkCommandOutcome
import com.lyrebird.rc.mavlink.MavlinkCommandSink
import com.lyrebird.rc.models.MediaVM
import com.lyrebird.rc.models.PayloadWidgetVM
import com.lyrebird.rc.settings.LyrebirdSettings
import com.lyrebird.rc.telemetry.GeoPoint3D
import com.lyrebird.rc.telemetry.GeoPosition
import com.lyrebird.rc.telemetry.ProcessTelemetryRuntimeRegistry
import com.lyrebird.rc.util.AppContextHolder
import dji.sdk.keyvalue.key.CameraKey
import dji.sdk.keyvalue.key.DJIKey
import dji.sdk.keyvalue.key.GimbalKey
import dji.sdk.keyvalue.value.camera.ThermalTemperatureMeasureMode
import dji.sdk.keyvalue.value.common.CameraLensType
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.sdk.keyvalue.value.common.DoubleRect
import dji.sdk.keyvalue.value.common.EmptyMsg
import dji.sdk.keyvalue.value.gimbal.GimbalAngleRotation
import dji.v5.et.action
import dji.v5.et.create
import dji.v5.et.createCamera
import dji.v5.et.get
import dji.v5.et.set
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The payload command path, process-scoped.
 *
 * The policy, the V5 port and the DJI keys live here rather than on the Flight Deck screen, so
 * the HTTP and MAVLink surfaces keep reaching the camera after the screen is gone — previously
 * every payload command answered "runtime detached" once the activity died, including ones that
 * never needed a screen.
 *
 * The thermal and LRF *reads* are here for the same reason: a ground station asking for the
 * hottest point or the last laser fix was previously routed to the screen that displays them, so
 * the reading existed only while someone was looking at it. What still needs the screen is the
 * visible part — the auto-sensing switch, the thermal overlay, the settings refreshes.
 *
 * The DJI key instances are created on first use, not at object initialisation: touching a key at
 * class-init time makes this object unloadable in host JVM tests — see ProcessCommandSurface for
 * the same rule.
 */
internal object ProcessPayloadCommands {
    private const val TAG = "LyrebirdPayloadCommands"

    /** The SDK's own name for a locked laser reading; other states are still acquiring. */
    private const val LRF_STATE_LOCKED = "NORMAL"

    /** How long to wait for a DJI action callback before reporting the command failed. */
    private const val ACTION_TIMEOUT_MS = 2_000L

    /** The camera needs a moment after connect before its thermal settings can be accepted. */
    private const val THERMAL_ARM_DELAY_MS = 8_000L

    private val handler by lazy { Handler(Looper.getMainLooper()) }

    private val preferences: SharedPreferences
        get() =
            AppContextHolder.context
                ?.getSharedPreferences(LyrebirdSettings.PREFS_FILE, Context.MODE_PRIVATE)
                ?: error("ProcessPayloadCommands requires the application context")

    @Volatile private var gimbalKeyField: DJIKey.ActionKey<GimbalAngleRotation, EmptyMsg>? = null

    @Volatile private var zoomKeyField: DJIKey<Double>? = null

    @Volatile private var startRecordingField: DJIKey.ActionKey<EmptyMsg, EmptyMsg>? = null

    @Volatile private var stopRecordingField: DJIKey.ActionKey<EmptyMsg, EmptyMsg>? = null

    private val host =
        object : V5PayloadCommandHost {
            override val droneName: String get() = ProcessCommandSurface.droneName

            override val mediaVM: MediaVM get() = ProcessMediaRuntimeRegistry.mediaVM()

            override val payloadWidgetVM: PayloadWidgetVM get() = ProcessPayloadRuntimeRegistry.payloadWidgetVM()

            override var gimbalKey: DJIKey.ActionKey<GimbalAngleRotation, EmptyMsg>
                get() = gimbalKeyField ?: GimbalKey.KeyRotateByAngle.create().also { gimbalKeyField = it }
                set(value) {
                    gimbalKeyField = value
                }

            override val zoomKey: DJIKey<Double>
                get() = zoomKeyField ?: CameraKey.KeyCameraZoomRatios.create().also { zoomKeyField = it }

            override val startRecording: DJIKey.ActionKey<EmptyMsg, EmptyMsg>
                get() = startRecordingField ?: CameraKey.KeyStartRecord.create().also { startRecordingField = it }

            override val stopRecording: DJIKey.ActionKey<EmptyMsg, EmptyMsg>
                get() = stopRecordingField ?: CameraKey.KeyStopRecord.create().also { stopRecordingField = it }

            override val settings: LyrebirdSettings get() = LyrebirdSettings(preferences)

            override fun applyMavlinkParameter(
                name: String,
                value: Float,
            ): CommandResult = ProcessMavlinkParameters.apply(name, value)

            override fun awaitAction(key: DJIKey.ActionKey<EmptyMsg, EmptyMsg>): CommandResult {
                val latch = CountDownLatch(1)
                val succeeded = AtomicBoolean(false)
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
                val answered = latch.await(ACTION_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                return when {
                    !answered -> CommandResult(MavlinkCommandOutcome.FAILED)
                    succeeded.get() -> CommandResult(MavlinkCommandOutcome.ACCEPTED)
                    else -> CommandResult(MavlinkCommandOutcome.FAILED)
                }
            }

            override fun readThermalMaxTempNow(): Double? = ProcessPayloadCommands.readThermalMaxTempNow()

            override fun setAutoSensingSwitchChecked(checked: Boolean) = ProcessCommandSurface.setAutoSensingSwitchChecked(checked)

            override fun setDetectionSource(value: String): Boolean = ProcessCommandSurface.setDetectionSource(value)

            override fun setDroneName(value: String): Boolean = ProcessCommandSurface.setDroneName(value)

            override fun setMediamtxServer(value: String): Boolean = ProcessCommandSurface.setMediamtxServer(value)

            override fun setStreamingMode(mode: StreamingMode) = ProcessCommandSurface.setStreamingMode(mode)

            override fun setVideoSource(value: String): Boolean = ProcessCommandSurface.setVideoSource(value)

            override fun setWebRtcResolution(value: String): Boolean = ProcessCommandSurface.setWebRtcResolution(value)

            override fun startAutoSensing() = ProcessCommandSurface.startAutoSensing()

            override fun stopAutoSensing() = ProcessCommandSurface.stopAutoSensing()

            override fun startRoiTracking(
                latitudeDeg: Double,
                longitudeDeg: Double,
                altitudeM: Double,
            ) = ProcessRoiRuntimeRegistry.start(latitudeDeg, longitudeDeg, altitudeM)

            override fun stopRoiTracking() = ProcessRoiRuntimeRegistry.stop()
        }

    private val port = V5PayloadCommandPort(host)

    private val policy =
        MavlinkPayloadPolicy(
            object : MavlinkPayloadHost {
                override fun postToMain(block: () -> Unit) {
                    handler.post(block)
                }

                override fun runCapture(block: () -> Unit) {
                    ProcessCaptureExecutorRegistry.executor().execute(block)
                }

                override fun reportCaptureStarted(): Long = ProcessMavlinkRuntimeRegistry.reportCaptureStarted()

                override fun reportImageCaptured(
                    captureId: Long,
                    success: Boolean,
                    fileName: String,
                ) = ProcessMavlinkRuntimeRegistry.reportImageCaptured(captureId, success, fileName)

                override fun publishLrfReading(
                    distanceM: Double?,
                    target: GeoPosition?,
                ) {
                    // The projection is the process's copy of the reading: the MAVLink snapshot and
                    // the telemetry frame both read it while no screen is attached.
                    val projection = ProcessTelemetryRuntimeRegistry.projection()
                    projection.lrfDistanceM = distanceM
                    projection.lrfTarget = target?.let { GeoPoint3D(it.latitudeDeg, it.longitudeDeg, it.altitudeAslM) }
                }
            },
            port,
        )

    /** The one payload sink both command surfaces command through. */
    val sink: MavlinkCommandSink get() = policy.sink

    /** SD-card reads for the MAVLink FTP server, process-owned so they outlive the screen. */
    val mediaSource: MavlinkMediaSource =
        object : MavlinkMediaSource {
            override fun listFiles(): List<Pair<String, Long>> = Payload.listMediaFiles(ProcessMediaRuntimeRegistry.mediaVM())

            override fun readFileBytes(name: String): ByteArray? = Payload.downloadMediaBytes(ProcessMediaRuntimeRegistry.mediaVM(), name)
        }

    /**
     * Rebind the gimbal keys for the M400's PORT_3 payload. Every later rotation goes through the
     * rebound key, which is why the key lives here rather than on the screen that notices the
     * frame.
     */
    fun rebindGimbalKey(key: DJIKey.ActionKey<GimbalAngleRotation, EmptyMsg>) {
        host.gimbalKey = key
    }

    // ── Thermal and laser reads ───────────────────────────────────────────────

    /**
     * The M400 carries its thermal payload in the PORT_3 bay; every other airframe on the main
     * gimbal. Derived from the control profile rather than from a second product check, so the
     * airframe table stays the one place products are mapped.
     */
    private fun thermalCameraIndex(): ComponentIndexType =
        if (DroneControlProfiles.activeProfile() == DroneControlProfile.MATRICE_400) {
            ComponentIndexType.PORT_3
        } else {
            ComponentIndexType.LEFT_OR_MAIN
        }

    /**
     * Arm the thermal pipeline, once per process.
     *
     * Armed here rather than by the screen that asks for a reading: with no screen attached the
     * first HTTP or MAVLink thermal read has to arm it itself, and arming on every read would
     * re-send three camera settings per command. The pipeline therefore stays armed until the
     * link drops ([resetThermalPipeline]); the reads are key lookups, not a subscription.
     */
    @Volatile private var thermalArmed = false

    /** The SDK's own warm-up delay, kept from the screen that used to schedule it. */
    private val armThermalRunnable = Runnable { armThermalMeasurement() }

    /**
     * Arm the thermal pipeline once the product type and the thermal payload have had time to
     * come up, so the first on-demand read is warm instead of racing the camera.
     */
    fun warmThermalPipeline() {
        handler.postDelayed(armThermalRunnable, THERMAL_ARM_DELAY_MS)
    }

    /**
     * Forget the arming after a link drop: the camera comes back with its settings cleared, so
     * the next connection must send them again.
     */
    fun resetThermalPipeline() {
        handler.removeCallbacks(armThermalRunnable)
        thermalArmed = false
    }

    private fun armThermalMeasurement() {
        if (thermalArmed) return
        thermalArmed = true
        val idx = thermalCameraIndex()
        val lens = CameraLensType.CAMERA_LENS_THERMAL
        Log.i(TAG, "Arming thermal measurement on camera index=$idx lens=THERMAL")

        CameraKey.KeyThermalTemperatureDataEnabled.createCamera(idx, lens).set(
            true,
            onSuccess = { Log.i(TAG, "ThermalTemperatureDataEnabled=true OK") },
            onFailure = { e -> Log.e(TAG, "set TemperatureDataEnabled failed: ${e.description()}") },
        )

        CameraKey.KeyThermalTemperatureMeasureMode.createCamera(idx, lens).set(
            ThermalTemperatureMeasureMode.REGION,
            onSuccess = {
                Log.i(TAG, "MeasureMode=REGION OK; setting full-frame area")
                CameraKey.KeyThermalRegionMetersureArea.createCamera(idx, lens).set(
                    DoubleRect(0.0, 0.0, 1.0, 1.0),
                    onSuccess = { Log.i(TAG, "Region area=full-frame OK") },
                    onFailure = { e -> Log.e(TAG, "set Region area failed: ${e.description()}") },
                )
            },
            onFailure = { e -> Log.e(TAG, "set MeasureMode failed: ${e.description()}") },
        )
    }

    /** The thermal lens's hottest point, or null when there is no thermal camera to read. */
    fun readThermalMaxTempNow(): Double? {
        // Make sure the pipeline is armed even if the read is the very first thermal action.
        armThermalMeasurement()
        return runCatching {
            val idx = thermalCameraIndex()
            val lens = CameraLensType.CAMERA_LENS_THERMAL
            val globalMax = CameraKey.KeyThermalGlobalMaxTemperature.createCamera(idx, lens).get()
            val regionMax =
                CameraKey.KeyThermalRegionMetersureTemperature
                    .createCamera(idx, lens)
                    .get()
                    ?.maxAreaTemperature
            val maxTemp = globalMax ?: regionMax
            Log.i(TAG, "[capture read] idx=$idx globalMax=$globalMax regionMax=$regionMax -> $maxTemp")
            maxTemp
        }.onFailure { Log.e(TAG, "[capture read] error: ${it.message}", it) }.getOrNull()
    }

    fun hasThermalCamera(): Boolean =
        runCatching {
            // The same key the temperature read uses: it resolves only when a thermal lens is
            // actually present, so this is the honest "would Temp/Thermal do anything" probe.
            CameraKey.KeyThermalGlobalMaxTemperature
                .createCamera(thermalCameraIndex(), CameraLensType.CAMERA_LENS_THERMAL)
                .get() != null
        }.getOrDefault(false)

    /**
     * The last laser reading, in the shape the HTTP route and the payload policy both consume.
     *
     * Distance and target are reported only while the laser is locked ([LRF_STATE_LOCKED]): a
     * reading that is still acquiring has a state worth reporting and nothing else, and handing
     * out the previous fix as if it were the current one is how a ground station records a
     * measurement of the wrong thing.
     */
    fun readLrfMeasurement(): LrfMeasurement {
        val reading = port.takeLrfReading()
        val locked = reading?.stateName == LRF_STATE_LOCKED
        return LrfMeasurement(
            distanceMeters = reading?.distanceM.takeIf { locked },
            state = reading?.stateName,
            target = reading?.target?.takeIf { locked }?.let { GeoPoint3D(it.latitudeDeg, it.longitudeDeg, it.altitudeAslM) },
        )
    }

    /**
     * Record the point the laser measured, in the projection the MAVLink snapshot and the
     * telemetry frame read — with or without a screen.
     */
    fun setLrfTarget(target: GeoPoint3D?) {
        ProcessTelemetryRuntimeRegistry.projection().lrfTarget = target
    }
}
