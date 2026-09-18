package com.lyrebird.rc.server

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.lyrebird.rc.DroneControlProfiles
import com.lyrebird.rc.controller.DroneController
import com.lyrebird.rc.controller.MavlinkFlightPolicy
import com.lyrebird.rc.controller.MavlinkMissionHost
import com.lyrebird.rc.controller.MavlinkMissionPolicy
import com.lyrebird.rc.controller.MavlinkMotionHost
import com.lyrebird.rc.controller.MavlinkMotionPolicy
import com.lyrebird.rc.controller.V5MotionCommandPort
import com.lyrebird.rc.controller.V5NativeMissionAdapter
import com.lyrebird.rc.mavlink.CommandResult
import com.lyrebird.rc.mavlink.Mav
import com.lyrebird.rc.mavlink.MavlinkEndpointConfig
import com.lyrebird.rc.mavlink.MavlinkMissionSink
import com.lyrebird.rc.mavlink.MavlinkMotionSink
import com.lyrebird.rc.mavlink.MissionItem
import com.lyrebird.rc.settings.LyrebirdSettings
import com.lyrebird.rc.telemetry.ProcessTelemetryRuntimeRegistry
import com.lyrebird.rc.util.AppContextHolder
import com.lyrebird.rc.util.ToastUtils
import dji.sdk.keyvalue.key.CameraKey
import dji.sdk.keyvalue.key.DJIKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.value.camera.CameraMode
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.KeyManager

/**
 * The MAVLink flight command path, process-scoped.
 *
 * The motion and mission policies and their hosts live here rather than on the Flight Deck
 * screen, so a ground station can keep commanding takeoff, landing, RTH and missions after the
 * screen is gone — previously the whole path died with the activity and answered "runtime
 * detached". The policies themselves are the same ones the HTTP surface drives; nothing about
 * their decision-making changes, only who owns them.
 *
 * The DJI camera-mode key is created on first use, not at object initialisation: touching a key
 * at class-init time makes this object unloadable in host JVM tests.
 */
internal object ProcessFlightCommands {
    private const val TAG = "LyrebirdFlightCommands"

    /** How long to wait for a take-off to finish before abandoning a requested climb. */
    private const val TAKEOFF_CLIMB_TIMEOUT_MS = 30_000L
    private const val TAKEOFF_POLL_MS = 500L

    /**
     * Set when a ground station's ARM command was accepted. DJI has no arming state — motors
     * spin only when a takeoff actually runs — so the heartbeat otherwise never reports armed and
     * QGroundControl's arm wait times out with "vehicle rejected arming" while the aircraft is
     * already taking off. Cleared by a DISARM, and the armed flag also stands on real motor
     * activity regardless of this.
     */
    @Volatile var armedCommanded = false

    private val handler by lazy { Handler(Looper.getMainLooper()) }

    private val preferences: SharedPreferences
        get() =
            AppContextHolder.context
                ?.getSharedPreferences(LyrebirdSettings.PREFS_FILE, Context.MODE_PRIVATE)
                ?: error("ProcessFlightCommands requires the application context")

    private val aircraftTelemetry get() = ProcessTelemetryRuntimeRegistry.aircraftTelemetry()

    private val motionPolicy by lazy {
        MavlinkMotionPolicy(
            object : MavlinkMotionHost {
                override var armedCommanded
                    get() = this@ProcessFlightCommands.armedCommanded
                    set(value) {
                        this@ProcessFlightCommands.armedCommanded = value
                    }

                override fun isMavlinkOriginTrusted() = ProcessMavlinkRuntimeRegistry.isTrustedOrigin()

                override fun climbAfterTakeoff(altitudeMeters: Double) = this@ProcessFlightCommands.climbAfterTakeoff(altitudeMeters)

                override fun mavlinkFlightGate() = this@ProcessFlightCommands.mavlinkFlightGate()

                override fun supersedeMission(reason: String) = this@ProcessFlightCommands.supersedeMission(reason)

                override fun currentAltitudeM() = aircraftTelemetry.getLocation3D().altitude

                override fun currentHeadingDeg() = aircraftTelemetry.getHeading()

                override fun defaultCruiseSpeedMps() = DroneControlProfiles.activeProfile().defaultCruiseSpeedMps
            },
            V5MotionCommandPort,
        )
    }

    val motionSink: MavlinkMotionSink get() = motionPolicy.sink

    private val missionPolicy by lazy {
        MavlinkMissionPolicy(
            object : MavlinkMissionHost {
                override fun mavlinkFlightGate() = this@ProcessFlightCommands.mavlinkFlightGate()

                override fun setCameraMode(mode: Int) = this@ProcessFlightCommands.setCameraMode(mode)

                override fun defaultCruiseSpeedMps() = DroneControlProfiles.activeProfile().defaultCruiseSpeedMps

                override fun postToMain(block: () -> Unit) {
                    handler.post(block)
                }

                override fun isTakeoffStillClimbing() = DroneController.droneStatus == DroneController.DroneStatus.TAKING_OFF

                override fun startNativeMission(
                    items: List<MissionItem>,
                    onProgress: (Int) -> Unit,
                    onFinished: (Boolean) -> Unit,
                ) = V5NativeMissionAdapter.start(items, onProgress, onFinished)
            },
            V5MotionCommandPort,
            motionPolicy.sink,
            ProcessPayloadCommands.sink,
            aircraftTelemetry,
        )
    }

    val missionSink: MavlinkMissionSink get() = missionPolicy.sink

    @Volatile private var cameraModeKeyField: DJIKey<CameraMode>? = null

    private val cameraModeKey: DJIKey<CameraMode>
        get() =
            cameraModeKeyField
                ?: KeyTools
                    .createKey(CameraKey.KeyCameraMode, ComponentIndexType.LEFT_OR_MAIN)
                    .also { cameraModeKeyField = it }

    /**
     * Returns a refusal when MAVLink-commanded motion is blocked, or null when it may proceed.
     *
     * Lives on the process command path rather than inside one sink because both the motion sink
     * and the mission sink fly the aircraft, and a gate that only one of them consulted would be
     * a hole rather than a gate.
     */
    private fun mavlinkFlightGate(): CommandResult? {
        val result =
            MavlinkFlightPolicy().check(
                flightAllowed = preferences.getBoolean(MavlinkEndpointConfig.PREF_ALLOW_FLIGHT, true),
                trustedOrigin = ProcessMavlinkRuntimeRegistry.isTrustedOrigin(),
            )
        if (result != null) {
            // Silent otherwise: the sender gets MAV_RESULT_DENIED over the wire and it lands in
            // the flight log, but nobody standing at the aircraft would ever see either of those
            // in the moment — a ground station could sit there commanding takeoff on a fresh
            // install after the setting has explicitly been blocked and the pilot would have no
            // idea why the command was refused.
            val message = result.detail.orEmpty()
            ToastUtils.showToast(
                if (message.contains("not allowed")) {
                    "$message (enable it from the settings menu)"
                } else {
                    message
                },
            )
        }
        return result
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
        if (missionSink.isRunning) {
            Log.i(TAG, "Stopping the running mission: superseded by $reason")
            missionSink.stopMission()
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
        ProcessCaptureExecutorRegistry.executor().execute {
            val deadline = System.currentTimeMillis() + TAKEOFF_CLIMB_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                val airborne =
                    aircraftTelemetry.readState().readings.flying &&
                        DroneController.droneStatus != DroneController.DroneStatus.TAKING_OFF
                if (airborne) {
                    Log.i(TAG, "Take-off complete; climbing to ${altitudeMeters}m")
                    handler.post { DroneController.gotoAltitude(altitudeMeters) }
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
}
