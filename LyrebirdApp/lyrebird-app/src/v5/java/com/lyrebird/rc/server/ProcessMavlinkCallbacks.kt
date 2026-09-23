package com.lyrebird.rc.server

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.lyrebird.rc.DroneControlProfiles
import com.lyrebird.rc.HTTP_PORT
import com.lyrebird.rc.TELEMETRY_PORT
import com.lyrebird.rc.controller.DroneController
import com.lyrebird.rc.edge.ProcessDetectionRuntimeRegistry
import com.lyrebird.rc.logger.LyrebirdFlightLogger
import com.lyrebird.rc.mavlink.CommandResult
import com.lyrebird.rc.mavlink.MavlinkEndpointConfig
import com.lyrebird.rc.mavlink.MavlinkSnapshot
import com.lyrebird.rc.mavlink.MavlinkSystemId
import com.lyrebird.rc.mavlink.MavlinkVideoStream
import com.lyrebird.rc.mavlink.MissionExecutor
import com.lyrebird.rc.settings.LyrebirdSettings
import com.lyrebird.rc.telemetry.ProcessTelemetryRuntimeRegistry
import com.lyrebird.rc.telemetry.toMavlinkSnapshot
import com.lyrebird.rc.util.AppContextHolder
import com.lyrebird.rc.util.NetworkUtils

/**
 * What the MAVLink endpoint reads, owned by the process.
 *
 * These callbacks used to be implemented by the Flight Deck screen, which meant the endpoint only
 * existed while a screen did: a phone that booted into the background never bound its MAVLink
 * port, and a ground station that pushed a new vehicle id with no screen attached was told the
 * change would apply "when the endpoint next starts" — which it never did.
 *
 * Everything here reads preferences, the telemetry projection, the process registries or DJI keys
 * inside method bodies, so the endpoint can attach and start at process boot. The screen keeps
 * only what a screen can do: draw the status, and edit the settings this object reads back.
 *
 * DJI types are touched inside method bodies only (the same rule as ProcessCommandSurface): an
 * object whose static initialiser resolved them would be unloadable in host JVM tests.
 */
internal object ProcessMavlinkCallbacks : MavlinkRuntimeCallbacks {
    private const val TAG = "LyrebirdMavlinkCallbacks"

    private val preferences: SharedPreferences
        get() =
            AppContextHolder.context
                ?.getSharedPreferences(LyrebirdSettings.PREFS_FILE, Context.MODE_PRIVATE)
                ?: error("ProcessMavlinkCallbacks requires the application context")

    private val settings: LyrebirdSettings get() = LyrebirdSettings(preferences)

    /**
     * Read the MAVLink endpoint settings, PX4-instance style.
     *
     * On by default: MAVLink and HTTP run side by side out of the box. Flight motion is the part
     * switched on deliberately, per aircraft, via `lb_mav_0_allow_flight`; the endpoint itself can
     * be turned off in the field with `adb shell` or the settings backup file by setting
     * `lb_mav_0_enabled` to false.
     */
    override fun runtimeMavlinkConfig(): MavlinkEndpointConfig {
        // These preferences are edited by hand in the field (adb, or the settings backup file), so
        // a value stored with the wrong type must not crash the app on startup.
        val port =
            prefIntOrDefault(
                MavlinkEndpointConfig.PREF_PORT,
                MavlinkEndpointConfig.DEFAULT_GCS_PORT,
            )
        // One system id per aircraft, so QGroundControl does not merge two drones into one vehicle.
        // 0 (the default) derives the id from the drone name once renamed, and the serial before that.
        return MavlinkEndpointConfig(
            enabled =
                runCatching {
                    preferences.getBoolean(MavlinkEndpointConfig.PREF_ENABLED, true)
                }.getOrDefault(true),
            targetHost =
                runCatching {
                    preferences.getString(MavlinkEndpointConfig.PREF_HOST, "")
                }.getOrNull().orEmpty(),
            targetPort = port,
            listenPort = port,
            mode =
                MavlinkEndpointConfig.Profile.fromPref(
                    runCatching {
                        preferences.getString(MavlinkEndpointConfig.PREF_MODE, null)
                    }.getOrNull(),
                ),
            systemId = currentMavlinkSystemId(),
            signingKeyHex =
                runCatching {
                    preferences.getString(MavlinkEndpointConfig.PREF_SIGNING_KEY, "")
                }.getOrNull().orEmpty(),
            missionExecutor =
                MissionExecutor.fromPref(
                    runCatching {
                        preferences.getString(MavlinkEndpointConfig.PREF_MISSION_EXECUTOR, null)
                    }.getOrNull(),
                ),
        )
    }

    /**
     * One consistent read of aircraft state for the MAVLink endpoint.
     *
     * DJI reports no arming state, so `motorsRunning` comes from KeyIsFlying — the only honest
     * source. Deriving it from the flight mode would report armed while the aircraft sits on the
     * ground.
     */
    override fun runtimeMavlinkSnapshot(): MavlinkSnapshot {
        val readings = ProcessTelemetryRuntimeRegistry.aircraftTelemetry().readState().readings
        val projection = ProcessTelemetryRuntimeRegistry.projection()

        return readings.toMavlinkSnapshot(
            MavlinkSnapshot(
                droneName = ProcessCommandSurface.droneName,
                homeSet = ProcessTelemetryRuntimeRegistry.telemetryCoordinator().homeSet,
                manualOverrideActive = DroneController.isManualOverrideActive,
                armedCommanded = ProcessFlightCommands.armedCommanded,
                // The sequencer flies through virtual stick, so the mode DJI reports (OFFBOARD) would
                // hide a mission that is actually under way; the heartbeat prefers MISSION instead.
                missionActive = ProcessFlightCommands.missionSink.isRunning,
                lrfDistanceM = projection.lrfDistanceM,
                lrfTargetLatitudeDeg = projection.lrfTarget?.latitudeDeg,
                lrfTargetLongitudeDeg = projection.lrfTarget?.longitudeDeg,
                lrfTargetAltitudeM = projection.lrfTarget?.altitudeM,
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
                // The outbound name, not the stored preference: this field is read by ground
                // stations alongside GET /config, and the two must not disagree.
                videoMode = settings.getStreamingMode().wireName,
                hasThermal = ProcessPayloadCommands.hasThermalCamera(),
                autoSensingActive = ProcessDetectionRuntimeRegistry.isAutoSensingActive(),
                detectionSource = settings.getDetectionSource().prefValue,
                detectionConfidenceThreshold = settings.getEdgeConfidenceThreshold(),
                detectedTargets = ProcessDetectionRuntimeRegistry.currentTargets(),
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
    override fun runtimeMavlinkVideoStream(): MavlinkVideoStream? =
        MavlinkVideoStream.fromWhipUrl(ProcessStreamingRuntimeRegistry.currentWhipUrl(), ProcessCommandSurface.droneName)

    /**
     * The active control profile, published as read-only MAVLink parameters.
     *
     * It is what a ground station needs to finish connecting — QGroundControl's camera manager
     * discards every message, including the camera heartbeat, until its initial-connect state
     * machine completes, and that machine blocks on the parameter download. It also makes the
     * per-airframe tuning visible in a standard parameter editor instead of being a constant
     * nobody outside the source can see.
     *
     * Read-only for now: these are published, not settable. Making them writable is a change with
     * its own safety review, since they are the gains an autonomous control loop flies on.
     */
    override fun runtimeMavlinkParameters(): List<Pair<String, Float>> {
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
            // The writable ones. Published so a ground station can read them back after a write
            // and see what actually took, which is what makes PARAM_SET meaningful.
            ProcessMavlinkParameters.PARAM_RTH_ALTITUDE to DroneController.getRTHAltitude().toFloat(),
            ProcessMavlinkParameters.PARAM_MAX_HEIGHT to DroneController.getMaxFlightHeight().toFloat(),
            ProcessMavlinkParameters.PARAM_MAX_DISTANCE to DroneController.getMaxFlightDistance().toFloat(),
            ProcessMavlinkParameters.PARAM_DISTANCE_LIMIT to if (DroneController.getDistanceLimitEnabled()) 1f else 0f,
            ProcessMavlinkParameters.PARAM_WEBRTC_FPS to settings.getWebRTCFps().toFloat(),
            ProcessMavlinkParameters.PARAM_DETECTIONS to if (settings.isDetectionsEnabled()) 1f else 0f,
            ProcessMavlinkParameters.PARAM_EDGE_CONFIDENCE to settings.getEdgeConfidenceThreshold(),
            ProcessMavlinkParameters.PARAM_SURFACE_H264_ENCODER to if (settings.isDjiSurfaceH264EncoderEnabled()) 1f else 0f,
            ProcessMavlinkParameters.PARAM_MAVLINK_SYSTEM_ID to currentMavlinkSystemId().toFloat(),
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

    override fun runtimeLogMavlinkCommand(
        command: com.lyrebird.rc.mavlink.MavlinkCommand,
        result: CommandResult,
        signed: Boolean,
    ) {
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
            signed = signed,
            senderSystem = command.senderSystem,
        )
    }

    /**
     * A ground station announced itself: publish this aircraft's video at it.
     *
     * Reached with no screen attached, which is the point — the target policy and the streamer
     * are process-owned, and an unattached screen is remembered as a pending client rather than
     * losing the request.
     */
    override fun runtimeMavlinkPeerDiscovered(peer: String) {
        Log.i(TAG, "MAVLink ground station at $peer")
        val peerIp = peer.substringBefore(':')
        if (peerIp.isNotBlank()) {
            ProcessStreamingRuntimeRegistry.startForClient(peerIp)
        }
    }

    /**
     * The identity the endpoint publishes, resolved here so there is exactly one answer.
     *
     * The Flight Deck shows and edits the vehicle id; it resolves it through these three rather
     * than keeping its own copy of the rule, so what the screen displays is what the endpoint
     * publishes.
     */
    internal fun configuredMavlinkSystemId(): Int =
        prefIntOrDefault(
            MavlinkEndpointConfig.PREF_SYSTEM_ID,
            MavlinkEndpointConfig.DEFAULT_SYSTEM_ID,
        )

    /** The current vehicle id: the configured one, or the id derived from the serial. */
    internal fun currentMavlinkSystemId(): Int = MavlinkSystemId.resolve(configuredMavlinkSystemId(), sysIdKey())

    /** The full DJI serial is immutable; the editable drone name is never an ID input. */
    private fun sysIdKey(): String = ProcessTelemetryRuntimeRegistry.droneSerial.trim().ifEmpty { "UNKNOWN" }

    /** Read an int preference that may have been stored as a string by a hand edit. */
    internal fun prefIntOrDefault(
        key: String,
        fallback: Int,
    ): Int =
        runCatching { preferences.getInt(key, fallback) }
            .recoverCatching { preferences.getString(key, null)?.toInt() ?: fallback }
            .getOrDefault(fallback)
}
