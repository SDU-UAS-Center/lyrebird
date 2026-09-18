package com.lyrebird.rc.server

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
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
import dji.sdk.keyvalue.value.common.EmptyMsg
import dji.sdk.keyvalue.value.gimbal.GimbalAngleRotation
import dji.v5.et.action
import dji.v5.et.create
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
 * What still needs the screen is routed through [ProcessCommandSurface] (thermal reads, the
 * auto-sensing switch visual, the settings actions a visible control reflects); with no screen
 * those fail honestly rather than pretending to have acted.
 *
 * The DJI key instances are created on first use, not at object initialisation: touching a key at
 * class-init time makes this object unloadable in host JVM tests — see ProcessCommandSurface for
 * the same rule.
 */
internal object ProcessPayloadCommands {
    private const val TAG = "LyrebirdPayloadCommands"

    /** How long to wait for a DJI action callback before reporting the command failed. */
    private const val ACTION_TIMEOUT_MS = 2_000L

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

            override fun readThermalMaxTempNow(): Double? = ProcessCommandSurface.readThermalMaxTempNow()

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
}
