package com.lyrebird.rc

import android.os.Handler
import com.lyrebird.rc.models.MediaVM
import com.lyrebird.rc.models.PayloadWidgetVM
import dji.sdk.keyvalue.key.DJIKey
import dji.sdk.keyvalue.value.common.EmptyMsg
import dji.sdk.keyvalue.value.common.LocationCoordinate3D
import android.util.Log
import android.widget.Switch
import com.lyrebird.rc.controller.ControlAuthority
import com.lyrebird.rc.controller.DroneController
import com.lyrebird.rc.controller.Payload
import com.lyrebird.rc.logger.LyrebirdFlightLogger
import com.lyrebird.rc.mavlink.GimbalRotation
import com.lyrebird.rc.mavlink.GimbalRotationMode
import com.lyrebird.rc.mavlink.MavlinkCommandOutcome
import com.lyrebird.rc.mavlink.MavlinkCommandSink
import com.lyrebird.rc.util.NetworkUtils
import dji.sdk.keyvalue.value.camera.LaserMeasureState
import dji.sdk.keyvalue.value.gimbal.GimbalAngleRotation
import dji.v5.et.action
import dji.v5.et.get
import dji.v5.et.set
import dji.v5.ux.detection.DetectedTarget
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import kotlin.concurrent.thread

internal const val HTTP_PORT = 8080
internal const val TELEMETRY_PORT = 8081
internal const val SAFETY_TOKEN_HEADER = "X-Safety-Token:"
internal const val AUTONOMOUS_COMMAND_REJECTED =
    "REJECTED: Manual override active. Deactivate manual override first."

/**
 * What the HTTP command surface needs from the activity hosting it.
 *
 * Declaring the dependency explicitly is the point: the command handler and socket server used to
 * be inner classes reaching into the activity's ~195 members at will. Everything they actually
 * need is listed here, so the surface can be reasoned about — and tested — on its own.
 */
internal interface LyrebirdCommandHost {
    val mainHandler: Handler
    val droneName: String
    val mediaVM: MediaVM
    val payloadWidgetVM: PayloadWidgetVM
    val gimbalKey: DJIKey.ActionKey<GimbalAngleRotation, EmptyMsg>
    val zoomKey: DJIKey<Double>
    val startRecording: DJIKey.ActionKey<EmptyMsg, EmptyMsg>
    val stopRecording: DJIKey.ActionKey<EmptyMsg, EmptyMsg>
    val isAutoSensingActive: Boolean
    val currentDetectedTargets: List<DetectedTarget>
    var lrfTargetLocation: LocationCoordinate3D?

    /** Full settings snapshot (app prefs + DJI flight limits) as JSON, for GET /config/settings. */
    fun readSettingsJson(): String

    /** Set the drone name pref; returns false when the name is rejected. */
    fun setDroneName(name: String): Boolean

    /** Set the video source (drone/phone/mock); false when rejected. */
    fun setVideoSource(value: String): Boolean
    /** Set the WebRTC resolution preset (auto/1080p/720p/480p); false when rejected. */
    fun setWebRtcResolution(value: String): Boolean
    /** Set the WebRTC frame rate (5/10/15/20/25/30); false when rejected. */
    fun setWebRtcFps(value: Int): Boolean
    /** Toggle detections. */
    fun setDetectionsEnabled(enabled: Boolean)
    /** Set the detection source (none/dji_onboard/yolo_on_phone); false when rejected. */
    fun setDetectionSource(value: String): Boolean
    /** Set the edge confidence threshold; false when rejected. */
    fun setEdgeConfidence(threshold: Float): Boolean
    /** Set the MediaMTX WHIP server (blank = auto/client IP); false when rejected. */
    fun setMediamtxServer(value: String): Boolean

    fun restartActiveStreaming()
    fun setStreamingMode(mode: StreamingMode)
    fun startAutoSensing()
    fun stopAutoSensing()
    fun updateManualOverrideUI()
    fun classifyCommandSource(presentedToken: String?): ControlAuthority.Source
    fun readThermalMaxTempNow(): Double?

    /** Whether the connected payload has a thermal camera (spot temp + thermal shutter work). */
    fun hasThermalCamera(): Boolean

    /** Keep the AutoSensing toggle in the UI in step with a command-driven change. */
    fun setAutoSensingSwitchChecked(checked: Boolean)
}

internal class LyrebirdHttpCommandHandler(
    private val host: LyrebirdCommandHost,
    private val commandSink: MavlinkCommandSink
) {
        private val postRoutes: Map<String, (String) -> String> = mapOf(
            "/send/takeoff" to {
                DroneController.startTakeOff()
                "Takeoff command sent."
            },
            "/send/land" to {
                DroneController.startLanding()
                "Landing command sent."
            },
            "/send/RTH" to {
                DroneController.startReturnToHome()
                "Return to home command sent."
            },
            "/send/stick" to { postData ->
                if (DroneController.shouldRejectAutonomousCommand("stick")) {
                    AUTONOMOUS_COMMAND_REJECTED
                } else {
                    val command = LyrebirdHttpCommandParser.parseStick(postData)
                    DroneController.setStick(command.leftX, command.leftY, command.rightX, command.rightY)
                    "Received: leftX: ${command.leftX}, leftY: ${command.leftY}, " +
                        "rightX: ${command.rightX}, rightY: ${command.rightY}"
                }
            },
            "/send/gimbal/pitch" to { postData ->
                val command = LyrebirdHttpCommandParser.parseGimbal(postData)
                commandSink.setGimbal(
                    GimbalRotation(
                        mode = GimbalRotationMode.ABSOLUTE,
                        pitchDeg = command.pitch,
                        rollDeg = command.roll,
                        yawDeg = command.yaw,
                        pitchIgnored = false,
                        rollIgnored = true,
                        yawIgnored = true
                    )
                )
                "Received: roll: ${command.roll}, pitch: ${command.pitch}, yaw: ${command.yaw}"
            },
            "/send/gimbal/yaw" to { postData ->
                val command = LyrebirdHttpCommandParser.parseGimbal(postData)
                commandSink.setGimbal(
                    GimbalRotation(
                        mode = GimbalRotationMode.ABSOLUTE,
                        pitchDeg = command.pitch,
                        rollDeg = command.roll,
                        yawDeg = command.yaw,
                        pitchIgnored = true,
                        rollIgnored = true,
                        yawIgnored = false
                    )
                )
                "Received: roll: ${command.roll}, pitch: ${command.pitch}, yaw: ${command.yaw}"
            },
            "/send/gotoWaypointHoldHeading" to { postData ->
                if (DroneController.shouldRejectAutonomousCommand("gotoWaypointHoldHeading")) {
                    AUTONOMOUS_COMMAND_REJECTED
                } else {
                    when (val command = LyrebirdHttpCommandParser.parseWaypointPid(postData)) {
                        is LyrebirdHttpCommandParser.ParseResult.Invalid -> command.message
                        is LyrebirdHttpCommandParser.ParseResult.Valid -> {
                            val wp = command.value
                            val seq = DroneController.flyToWaypointHoldHeading(
                                wp.latitude, wp.longitude, wp.altitude, wp.yaw, wp.maxSpeed
                            )
                            "WAYPOINT_ACCEPTED seq=$seq Latitude=${wp.latitude}, " +
                                "Longitude=${wp.longitude}, Altitude=${wp.altitude}, " +
                                "Yaw=${wp.yaw}, MaxSpeed=${wp.maxSpeed}"
                        }
                    }
                }
            },
            // Nose-follows-path. During travel the heading is forced to bearing(current -> waypoint);
            // the yaw field is the FINAL arrival heading the drone rotates to in place once it
            // arrives. Use /send/gotoWaypointHoldHeading to keep the nose on yaw while translating.
            "/send/gotoWaypointNoseForward" to { postData ->
                if (DroneController.shouldRejectAutonomousCommand("gotoWaypointNoseForward")) {
                    AUTONOMOUS_COMMAND_REJECTED
                } else {
                    when (val command = LyrebirdHttpCommandParser.parseWaypointPid(postData)) {
                        is LyrebirdHttpCommandParser.ParseResult.Invalid -> command.message
                        is LyrebirdHttpCommandParser.ParseResult.Valid -> {
                            val wp = command.value
                            val seq = DroneController.flyToWaypointNoseForward(
                                wp.latitude, wp.longitude, wp.altitude, wp.yaw, wp.maxSpeed
                            )
                            "WAYPOINT_ACCEPTED seq=$seq Latitude=${wp.latitude}, " +
                                "Longitude=${wp.longitude}, Altitude=${wp.altitude}, " +
                                "FinalYaw=${wp.yaw}, MaxSpeed=${wp.maxSpeed}"
                        }
                    }
                }
            },
            "/send/gimbal/rel_pitch" to { postData ->
                val gimbal = LyrebirdHttpCommandParser.parseGimbal(postData)
                commandSink.setGimbal(
                    GimbalRotation(
                        mode = GimbalRotationMode.RELATIVE,
                        pitchDeg = gimbal.pitch,
                        rollDeg = 0.0,
                        yawDeg = 0.0,
                        pitchIgnored = false,
                        rollIgnored = true,
                        yawIgnored = false
                    )
                )
                "Received: relative pitch: ${gimbal.pitch}"
            },
            "/send/gimbal/rel_yaw" to { postData ->
                val gimbal = LyrebirdHttpCommandParser.parseGimbal(postData)
                commandSink.setGimbal(
                    GimbalRotation(
                        mode = GimbalRotationMode.RELATIVE,
                        pitchDeg = 0.0,
                        rollDeg = 0.0,
                        yawDeg = gimbal.yaw,
                        pitchIgnored = true,
                        rollIgnored = true,
                        yawIgnored = false
                    )
                )
                "Received: relative yaw: ${gimbal.yaw}"
            },
            // Fire the laser and return distance + geo-reference + state as JSON. Distance and the
            // target point are populated only when the laser locks (state == NORMAL, which needs a
            // GPS fix); other states return null alongside the raw state.
            "/send/lrf/measure" to { _ ->
                val info = Payload.takeFreshLrfReading()
                val state = info?.laserMeasureState
                val locked = state == LaserMeasureState.NORMAL
                val distance = if (locked) info?.distance else null
                val target = if (locked) {
                    info?.location3D?.takeIf {
                        it.latitude != 0.0 || it.longitude != 0.0 || it.altitude != 0.0
                    }
                } else {
                    null
                }
                if (locked && target != null) {
                    // Surfaced on the telemetry stream as lrfTarget.
                    host.lrfTargetLocation = target
                }
                val targetJson = if (target == null) {
                    "null"
                } else {
                    "[${target.latitude}, ${target.longitude}, ${target.altitude}]"
                }
                val stateJson = if (state == null) "null" else "\"$state\""
                "{\"distance\": ${distance ?: "null"}, \"target\": $targetJson, \"state\": $stateJson}"
            },
            // The detected control profile carries the payload index and the drop widget indices
            // (RIGHT + Unlock 3 / All_Down 5 on the M300 SkyPort payload, PORT_4 on the M400, null
            // where no droppable payload exists). dropPayload pulses unlock then release.
            "/send/drop" to { _ ->
                val profile = DroneControlProfiles.activeProfile()
                val indexType = profile.payloadIndexType
                when {
                    indexType == null ->
                        "REJECTED: ${profile.displayName} has no payload drop port configured."
                    Payload.dropPayload(
                        host.payloadWidgetVM, indexType,
                        profile.dropArmSwitchIndex, profile.dropReleaseButtonIndex
                    ) -> "Payload dropped on $indexType"
                    else -> "Payload drop failed"
                }
            },
            "/send/gotoYaw" to { postData ->
                if (DroneController.shouldRejectAutonomousCommand("gotoYaw")) {
                    AUTONOMOUS_COMMAND_REJECTED
                } else {
                    val yaw = postData.split(",")[0].toDouble()
                    val seq = DroneController.gotoYaw(yaw)
                    "YAW_ACCEPTED seq=$seq Yaw=$yaw"
                }
            },
            "/send/gotoAltitude" to { postData ->
                if (DroneController.shouldRejectAutonomousCommand("gotoAltitude")) {
                    AUTONOMOUS_COMMAND_REJECTED
                } else {
                    val targetAltitude = postData.split(",")[0].toDouble()
                    val seq = DroneController.gotoAltitude(targetAltitude)
                    "ALTITUDE_ACCEPTED seq=$seq Altitude: $targetAltitude"
                }
            },
            "/send/camera/zoom" to { postData ->
                val targetZoom = postData.toDouble()
                when (commandSink.setCameraZoom(targetZoom.toFloat()).outcome) {
                    MavlinkCommandOutcome.ACCEPTED -> "Received: zoom: $targetZoom"
                    else -> "Invalid zoom value"
                }
            },
            "/send/abortMission" to {
                DroneController.setStick(0.0f, 0.0f, 0.0f, 0.0f)
                DroneController.disableVirtualStick()
                "Received: abortMission"
            },
            "/send/abortAll" to {
                DroneController.abortAllMissions()
                "Received: abortAll"
            },
            "/send/enableVirtualStick" to {
                if (DroneController.shouldRejectAutonomousCommand("enableVirtualStick")) {
                    AUTONOMOUS_COMMAND_REJECTED
                } else {
                    DroneController.enableVirtualStick()
                    "Received: enableVirtualStick"
                }
            },
            "/send/camera/startRecording" to {
                when (commandSink.startVideoRecording().outcome) {
                    MavlinkCommandOutcome.ACCEPTED -> "Received: camera start recording"
                    else -> "FAILED: camera start recording"
                }
            },
            "/send/camera/stopRecording" to {
                when (commandSink.stopVideoRecording().outcome) {
                    MavlinkCommandOutcome.ACCEPTED -> "Received: camera stop recording"
                    else -> "FAILED: camera stop recording"
                }
            },
            "/send/navigateTrajectoryDJINative" to { postData ->
                if (DroneController.shouldRejectAutonomousCommand("navigateTrajectoryDJINative")) {
                    AUTONOMOUS_COMMAND_REJECTED
                } else {
                    when (val command = LyrebirdHttpCommandParser.parseNativeTrajectory(postData)) {
                        is LyrebirdHttpCommandParser.ParseResult.Invalid -> command.message
                        is LyrebirdHttpCommandParser.ParseResult.Valid -> {
                            val trajectory = command.value
                            DroneController.navigateTrajectoryNative(
                                trajectory.waypoints,
                                trajectory.speed
                            )
                            "DJI native mission requested with ${trajectory.waypoints.size} waypoints " +
                                "at ${trajectory.speed}m/s"
                        }
                    }
                }
            },
            "/send/abort/DJIMission" to {
                DroneController.endMission()
                "Mission stop requested"
            },
            "/send/setRTHAltitude" to { postData ->
                val altitude = postData.toIntOrNull()
                if (altitude != null) {
                    DroneController.setRTHAltitude(altitude)
                    "RTH altitude set to $altitude m"
                } else {
                    "Invalid altitude value"
                }
            },
            "/send/setMaxFlightHeight" to { postData ->
                val height = postData.toIntOrNull()
                if (height != null) {
                    DroneController.setMaxFlightHeight(height)
                    "Max flight height set to $height m"
                } else {
                    "Invalid height value"
                }
            },
            "/send/setMaxFlightDistance" to { postData ->
                val distance = postData.toIntOrNull()
                if (distance != null) {
                    DroneController.setMaxFlightDistance(distance)
                    "Max flight distance set to $distance m"
                } else {
                    "Invalid distance value"
                }
            },
            "/send/setDistanceLimitEnabled" to { postData ->
                when (postData.trim().lowercase()) {
                    "true", "1", "on", "enable" -> {
                        DroneController.setDistanceLimitEnabled(true)
                        "Distance limit enabled"
                    }
                    "false", "0", "off", "disable" -> {
                        DroneController.setDistanceLimitEnabled(false)
                        "Distance limit disabled"
                    }
                    else -> "Invalid value (use true/false)"
                }
            },
            "/send/setRcControlMode" to { postData ->
                val mode = postData.trim().lowercase()
                if (DroneController.setRcControlMode(mode)) {
                    "RC control mode set to $mode"
                } else {
                    "Invalid control mode (jp|usa|ch|custom)"
                }
            },
            "/send/rcPairing/start" to {
                DroneController.requestRcPairing()
                "RC pairing requested"
            },
            "/send/rcPairing/stop" to {
                DroneController.stopRcPairing()
                "RC pairing stopped"
            },
            "/send/setDroneName" to { postData ->
                if (host.setDroneName(postData)) {
                    "Drone name set to ${host.droneName}"
                } else {
                    "Invalid name (must be 1-32 non-empty characters)"
                }
            },
            "/send/setVideoSource" to { postData ->
                val value = postData.trim()
                if (host.setVideoSource(value)) {
                    "Video source set to $value"
                } else {
                    "Invalid video source (drone|phone|mock)"
                }
            },
            "/send/setWebRtcResolution" to { postData ->
                val value = postData.trim()
                if (host.setWebRtcResolution(value)) {
                    "WebRTC resolution set to $value"
                } else {
                    "Invalid resolution (auto|1080p|720p|480p)"
                }
            },
            "/send/setWebRtcFps" to { postData ->
                val fps = postData.toIntOrNull()
                if (fps != null && host.setWebRtcFps(fps)) {
                    "WebRTC FPS set to $fps"
                } else {
                    "Invalid FPS (5|10|15|20|25|30)"
                }
            },
            "/send/setDetectionsEnabled" to { postData ->
                when (postData.trim().lowercase()) {
                    "true", "1", "on", "enable" -> {
                        host.setDetectionsEnabled(true)
                        "Detections enabled"
                    }
                    "false", "0", "off", "disable" -> {
                        host.setDetectionsEnabled(false)
                        "Detections disabled"
                    }
                    else -> "Invalid value (use true/false)"
                }
            },
            "/send/setDetectionSource" to { postData ->
                val value = postData.trim()
                if (host.setDetectionSource(value)) {
                    "Detection source set to $value"
                } else {
                    "Invalid source (none|dji_onboard|yolo_on_phone)"
                }
            },
            "/send/setEdgeConfidence" to { postData ->
                val threshold = postData.toFloatOrNull()
                if (threshold != null && host.setEdgeConfidence(threshold)) {
                    "Edge confidence set to $threshold"
                } else {
                    "Invalid threshold (0.10-0.70 in 0.05 steps)"
                }
            },
            "/send/setMediamtxServer" to { postData ->
                if (host.setMediamtxServer(postData)) {
                    "MediaMTX server set to ${postData.trim()}"
                } else {
                    "Invalid server value"
                }
            },
            "/send/deactivateManualOverride" to {
                DroneController.deactivateManualOverride()
                host.mainHandler.post { host.updateManualOverrideUI() }
                "Manual override deactivated. Autonomous commands are now allowed."
            },
            "/get/isManualOverrideActive" to {
                if (DroneController.isManualOverrideActive) "true" else "false"
            },
            "/send/autoSensing/start" to {
                host.mainHandler.post {
                    host.startAutoSensing()
                    host.setAutoSensingSwitchChecked(true)
                }
                "AutoSensing start requested"
            },
            "/send/autoSensing/stop" to {
                host.mainHandler.post {
                    host.stopAutoSensing()
                    host.setAutoSensingSwitchChecked(false)
                }
                "AutoSensing stop requested"
            },
            "/get/autoSensing/status" to {
                """{"active":${host.isAutoSensingActive},"targetCount":${host.currentDetectedTargets.size}}"""
            },
            "/get/autoSensing/targets" to {
                DetectedTarget.listToJsonArray(host.currentDetectedTargets).toString()
            },
            "/send/streaming/mode" to { postData ->
                val modeStr = postData.trim().lowercase()
                val selectedMode = StreamingMode.entries.find { it.prefValue == modeStr }
                if (selectedMode != null) {
                    host.mainHandler.post {
                        host.setStreamingMode(selectedMode)
                        host.restartActiveStreaming()
                    }
                    "Streaming mode successfully changed to $modeStr"
                } else {
                    "Invalid streaming mode: $postData"
                }
            }
        )

        /**
         * Pilot / Safety authority gate. Returns a rejection message when the request must not
         * reach a route, or null to let it through.
         *
         * /releaseSafetyControl is the explicit hand-back and is reserved for the Safety
         * Computer. Every drone-control command (the /send/ family) goes through the authority latch: the
         * first Safety command seizes persistent control, and Pilot commands are rejected while
         * the Safety Computer holds it.
         */
        private fun authorityRejection(uri: String, source: ControlAuthority.Source): String? {
            if (uri == "/releaseSafetyControl") {
                return if (ControlAuthority.releaseSafetyControl(source)) {
                    "Safety control released. Pilot Computer is back in control."
                } else {
                    "REJECTED: only the Safety Computer can release safety control."
                }
            }
            if (uri.startsWith("/send/") && !ControlAuthority.authorizeControlCommand(source)) {
                return "REJECTED: Safety Computer is in control. Pilot commands are blocked."
            }
            return null
        }

        fun handlePostRequest(
            uri: String,
            postData: String,
            source: ControlAuthority.Source
        ): String {
            return runCatching {
                Log.i("DroneServer", "POST $uri with data: $postData")
                authorityRejection(uri, source) ?: postRoutes[uri]?.invoke(postData)
                    ?: "Not Found: $uri"
            }.getOrElse { error ->
                Log.e("DroneServer", "Error processing POST request: ${error.message}", error)
                "Error processing request: ${error.message}"
            }
        }
    }

internal class SimpleHttpServer(
    private val port: Int,
    private val host: LyrebirdCommandHost,
    private val commandSink: MavlinkCommandSink
) {
        private var serverSocket: ServerSocket? = null
        private val executor = Executors.newFixedThreadPool(10)
        private val commandHandler = LyrebirdHttpCommandHandler(host, commandSink)
        @Volatile
        private var isRunning = false

        fun start() {
            if (isRunning) return
            thread {
                try {
                    serverSocket = ServerSocket(port)
                    isRunning = true
                    Log.i("SimpleHttpServer", "Server started on port $port")
                    while (isRunning && !serverSocket!!.isClosed) {
                        try {
                            val clientSocket = serverSocket!!.accept()
                            executor.submit { handleRequest(clientSocket) }
                        } catch (e: IOException) {
                            if (isRunning) {
                                Log.e("SimpleHttpServer", "Error accepting connection: ${e.message}", e)
                            }
                        }
                    }
                } catch (e: IOException) {
                    Log.e("SimpleHttpServer", "Server error: ${e.message}", e)
                }
            }
        }

        fun stop() {
            isRunning = false
            runCatching {
                serverSocket?.close()
                serverSocket = null
                // Interrupt, don't just drain: a worker blocked in readLine() on a stale client
                // would otherwise keep the server (and its host activity) alive past destroy.
                executor.shutdownNow()
            }.onFailure { error ->
                Log.e("SimpleHttpServer", "Error stopping server: ${error.message}", error)
            }
        }

        /** Parsed request line, headers and body of one HTTP request. */
        private class HttpRequest(
            val method: String,
            val uri: String,
            val postData: String,
            val source: ControlAuthority.Source
        )

        private fun readRequest(reader: BufferedReader): HttpRequest? {
            val requestLine = reader.readLine() ?: return null
            val parts = requestLine.split(" ")
            if (parts.size < 3) return null

            var contentLength = 0
            var safetyToken: String? = null
            var line: String?
            while (reader.readLine().also { line = it } != null && line!!.isNotEmpty()) {
                if (line!!.startsWith("Content-Length:", ignoreCase = true)) {
                    contentLength = line!!.substring(15).trim().toIntOrNull() ?: 0
                } else if (line!!.startsWith(SAFETY_TOKEN_HEADER, ignoreCase = true)) {
                    safetyToken = line!!.substring(SAFETY_TOKEN_HEADER.length).trim()
                }
            }

            val method = parts[0]
            var postData = ""
            if (method == "POST" && contentLength > 0) {
                val buffer = CharArray(contentLength)
                reader.read(buffer, 0, contentLength)
                postData = String(buffer)
            }

            // A request is from the Safety Computer iff it carries the configured token.
            // Everything else (including a token mismatch) is treated as the Pilot Computer.
            return HttpRequest(method, parts[1], postData, host.classifyCommandSource(safetyToken))
        }

        private val jsonEndpoints = setOf(
            "/send/capture",
            "/send/captureTemperature",
            "/send/captureThermalImage",
            "/send/listMedia"
        )

        private fun writeJsonResponse(writer: PrintWriter, body: String) {
            writer.println("HTTP/1.1 200 OK")
            writer.println("Content-Type: application/json")
            writer.println("Content-Length: ${body.toByteArray().size}")
            writer.println("Access-Control-Allow-Origin: *")
            writer.println()
            writer.print(body)
            writer.flush()
        }

        /**
         * Endpoints that answer with their own JSON rather than the plain-text command response.
         *
         * Photo capture trips one shutter and reports the file it produced. Thermal capture is
         * two-step: it returns a descriptor naming the per-lens filenames the payload stored; the
         * files stay on the card and are fetched by name via /send/downloadMediaByName. The
         * temperature read takes no shutter and downloads nothing — it reads the hottest point on
         * the thermal feed synchronously.
         *
         * Returns null when the request is not one of these. Like every /send/ command these are
         * behind the authority latch, so the Pilot cannot drive the payload while Safety holds control.
         */
        private fun handleJsonEndpoint(request: HttpRequest): String? {
            if (request.method != "POST") return null
            if (request.uri !in jsonEndpoints) return null

            LyrebirdFlightLogger.logCommand(request.uri, request.postData)
            // Authorise BEFORE acting: these endpoints trip a shutter or drive the payload, so a
            // rejected request must not reach the camera.
            if (!ControlAuthority.authorizeControlCommand(request.source)) {
                return "{\"error\":\"REJECTED: Safety Computer is in control.\"}"
            }
            return when (request.uri) {
                "/send/capture" -> {
                    // Airframe-agnostic photo path: one shutter, whichever lens it came from.
                    val file = Payload.capturePhoto(host.mediaVM)
                    if (file != null) {
                        "{\"captured\":true,\"file\":\"${file.fileName}\"}"
                    } else {
                        "{\"error\":\"Failed to capture photo\"}"
                    }
                }
                "/send/captureTemperature" -> {
                    val maxTemp = host.readThermalMaxTempNow()
                    "{\"thermalMaxTemp\":${maxTemp ?: "null"}}"
                }
                "/send/captureThermalImage" ->
                    Payload.captureThermal(host.mediaVM) ?: "{\"error\":\"Failed to capture thermal image\"}"
                else -> Payload.listAllMedia(host.mediaVM)
            }
        }

        private fun handleRequest(clientSocket: Socket) {
            runCatching {
                val reader = BufferedReader(InputStreamReader(clientSocket.getInputStream()))
                val writer = PrintWriter(OutputStreamWriter(clientSocket.getOutputStream()), true)

                val request = readRequest(reader) ?: return

                handleJsonEndpoint(request)?.let { body ->
                    writeJsonResponse(writer, body)
                    clientSocket.close()
                    return
                }

                // Download ANY file on the SD card by name: body is the filename. Resolves against
                // the live media list (the card's own index). Returns binary image/jpeg written
                // straight to the socket, bypassing the text-response path below.
                if (request.method == "POST" && request.uri == "/send/downloadMediaByName") {
                    LyrebirdFlightLogger.logCommand(request.uri, request.postData)
                    val outputStream = clientSocket.getOutputStream()
                    val fileName = request.postData.trim()
                    when {
                        !ControlAuthority.authorizeControlCommand(request.source) ->
                            Payload.sendErrorResponse(
                                outputStream, "REJECTED: Safety Computer is in control."
                            )
                        fileName.isEmpty() ->
                            Payload.sendErrorResponse(outputStream, "Expected body '<fileName>'")
                        else -> Payload.sendMediaFileByName(host.mediaVM, fileName, outputStream)
                    }
                    clientSocket.close()
                    return
                }

                val response = handleHttpRequest(
                    request.method, request.uri, request.postData, request.source
                )

                writer.println("HTTP/1.1 200 OK")
                writer.println("Content-Type: text/plain")
                writer.println("Content-Length: ${response.length}")
                writer.println("Access-Control-Allow-Origin: *")
                writer.println("Access-Control-Allow-Methods: GET, POST, OPTIONS")
                writer.println("Access-Control-Allow-Headers: Content-Type")
                writer.println()
                writer.print(response)
                writer.flush()
                clientSocket.close()
            }.onFailure { error ->
                Log.e("SimpleHttpServer", "Error handling request: ${error.message}", error)
                try {
                    clientSocket.close()
                } catch (closeError: IOException) {
                    Log.w(
                        "SimpleHttpServer",
                        "Error closing client socket: ${closeError.message}",
                        closeError
                    )
                }
            }
        }

        private fun handleHttpRequest(
            method: String,
            uri: String,
            postData: String,
            source: ControlAuthority.Source
        ): String {
            return when (method) {
                "POST" -> handlePostRequest(uri, postData, source)
                "GET" -> handleGetRequest(uri)
                "OPTIONS" -> "OK"
                else -> "Method Not Allowed"
            }
        }

        private fun handleGetRequest(uri: String): String {
            return when (uri) {
                "/config" -> {
                    val deviceIp = NetworkUtils.getDeviceIpAddress() ?: "unknown"
                    """{"droneName":"${host.droneName}","ipAddress":"$deviceIp","httpPort":$HTTP_PORT,""" +
                        """"telemetryPort":$TELEMETRY_PORT,"videoMode":"whip",""" +
                        """"hasThermal":${host.hasThermalCamera()}}"""
                }
                "/config/settings" -> host.readSettingsJson()
                else -> "Use POST for commands. Telemetry available on port $TELEMETRY_PORT. " +
                    "Config available at GET /config; settings at GET /config/settings"
            }
        }

        private fun handlePostRequest(
            uri: String,
            postData: String,
            source: ControlAuthority.Source
        ): String {
            return commandHandler.handlePostRequest(uri, postData, source)
        }
    }
