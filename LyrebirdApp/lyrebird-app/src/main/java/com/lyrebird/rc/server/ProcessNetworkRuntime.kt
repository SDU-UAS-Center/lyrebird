package com.lyrebird.rc.server

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.lyrebird.rc.HTTP_PORT
import com.lyrebird.rc.LrfMeasurement
import com.lyrebird.rc.LyrebirdCommandHost
import com.lyrebird.rc.LyrebirdDetectionPort
import com.lyrebird.rc.LyrebirdFlightPort
import com.lyrebird.rc.LyrebirdMediaPort
import com.lyrebird.rc.SimpleHttpServer
import com.lyrebird.rc.StreamingMode
import com.lyrebird.rc.TELEMETRY_PORT
import com.lyrebird.rc.controller.ControlAuthority
import com.lyrebird.rc.mavlink.CommandResult
import com.lyrebird.rc.mavlink.GimbalRotation
import com.lyrebird.rc.mavlink.MavlinkCommandOutcome
import com.lyrebird.rc.mavlink.MavlinkCommandSink
import com.lyrebird.rc.telemetry.GeoPoint3D
import java.io.OutputStream
import java.lang.ref.WeakReference

internal interface NetworkRuntimeCallbacks {
    val runtimeDroneSerial: String

    fun telemetryJson(): String

    fun gapTelemetryJson(): String

    fun onTelemetryClient(clientIp: String)
}

private class RuntimeCommandHost : LyrebirdCommandHost {
    private val runtimeHandler = Handler(Looper.getMainLooper())
    private var hostRef: WeakReference<LyrebirdCommandHost> = WeakReference(null)

    private val mediaPort =
        object : LyrebirdMediaPort {
            override fun capturePhotoFileName(): String? = hostRef.get()?.media?.capturePhotoFileName()

            override fun captureThermalJson(): String? = hostRef.get()?.media?.captureThermalJson()

            override fun listMediaJson(): String = hostRef.get()?.media?.listMediaJson() ?: "{\"error\":\"runtime detached\"}"

            override fun sendMediaFile(
                fileName: String,
                outputStream: OutputStream,
            ) {
                hostRef.get()?.media?.sendMediaFile(fileName, outputStream) ?: sendErrorResponse("runtime detached", outputStream)
            }

            override fun sendErrorResponse(
                message: String,
                outputStream: OutputStream,
            ) {
                hostRef.get()?.media?.sendErrorResponse(message, outputStream) ?: outputStream.write(message.toByteArray())
            }
        }

    private val detectionPort =
        object : LyrebirdDetectionPort {
            override val isAutoSensingActive: Boolean get() = hostRef.get()?.detection?.isAutoSensingActive == true

            override fun currentTargets() =
                hostRef
                    .get()
                    ?.detection
                    ?.currentTargets()
                    .orEmpty()
        }

    private val flightPort =
        object : LyrebirdFlightPort {
            override fun takeoff() = hostRef.get()?.flight?.takeoff() ?: detachedResult()

            override fun land() = hostRef.get()?.flight?.land() ?: detachedResult()

            override fun returnToHome() = hostRef.get()?.flight?.returnToHome() ?: detachedResult()

            override fun stick(command: com.lyrebird.rc.StickCommand) = hostRef.get()?.flight?.stick(command) ?: detachedResult()

            override fun gotoYaw(yawDeg: Double) = hostRef.get()?.flight?.gotoYaw(yawDeg) ?: detachedResult()

            override fun gotoAltitude(altitudeM: Double) = hostRef.get()?.flight?.gotoAltitude(altitudeM) ?: detachedResult()

            override fun abortMission() = hostRef.get()?.flight?.abortMission() ?: detachedResult()

            override fun abortAll() = hostRef.get()?.flight?.abortAll() ?: detachedResult()

            override fun enableVirtualStick() = hostRef.get()?.flight?.enableVirtualStick() ?: detachedResult()

            override fun waypoint(
                latitudeDeg: Double,
                longitudeDeg: Double,
                altitudeM: Double,
                yawDeg: Double,
                maxSpeedMps: Double,
                noseForward: Boolean,
            ) = hostRef.get()?.flight?.waypoint(latitudeDeg, longitudeDeg, altitudeM, yawDeg, maxSpeedMps, noseForward) ?: detachedResult()

            override fun nativeTrajectory(
                waypoints: List<Triple<Double, Double, Double>>,
                speedMps: Double,
            ) = hostRef.get()?.flight?.nativeTrajectory(waypoints, speedMps) ?: detachedResult()

            override fun abortNativeMission() = hostRef.get()?.flight?.abortNativeMission() ?: detachedResult()

            override fun setRthAltitude(altitudeM: Int) = hostRef.get()?.flight?.setRthAltitude(altitudeM) ?: detachedResult()

            override fun setMaxFlightHeight(heightM: Int) = hostRef.get()?.flight?.setMaxFlightHeight(heightM) ?: detachedResult()

            override fun setMaxFlightDistance(distanceM: Int) = hostRef.get()?.flight?.setMaxFlightDistance(distanceM) ?: detachedResult()

            override fun setDistanceLimitEnabled(enabled: Boolean) =
                hostRef.get()?.flight?.setDistanceLimitEnabled(enabled) ?: detachedResult()

            override fun setRcControlMode(mode: String) = hostRef.get()?.flight?.setRcControlMode(mode) ?: detachedResult()

            override fun requestRcPairing() = hostRef.get()?.flight?.requestRcPairing() ?: detachedResult()

            override fun stopRcPairing() = hostRef.get()?.flight?.stopRcPairing() ?: detachedResult()

            override fun deactivateManualOverride() = hostRef.get()?.flight?.deactivateManualOverride() ?: detachedResult()

            override fun isManualOverrideActive() = hostRef.get()?.flight?.isManualOverrideActive() == true
        }

    override val mainHandler: Handler get() = runtimeHandler
    override val droneName: String get() = hostRef.get()?.droneName ?: "lb_unavailable"
    override val media: LyrebirdMediaPort get() = mediaPort
    override val detection: LyrebirdDetectionPort get() = detectionPort
    override val flight: LyrebirdFlightPort get() = flightPort

    override fun readSettingsJson() = hostRef.get()?.readSettingsJson() ?: "{}"

    override fun setDroneName(name: String) = hostRef.get()?.setDroneName(name) == true

    override fun setMavlinkSystemId(value: Int) = hostRef.get()?.setMavlinkSystemId(value) == true

    override fun setVideoSource(value: String) = hostRef.get()?.setVideoSource(value) == true

    override fun setWebRtcResolution(value: String) = hostRef.get()?.setWebRtcResolution(value) == true

    override fun setWebRtcFps(value: Int) = hostRef.get()?.setWebRtcFps(value) == true

    override fun setDetectionsEnabled(enabled: Boolean) {
        hostRef.get()?.setDetectionsEnabled(enabled)
    }

    override fun setDetectionSource(value: String) = hostRef.get()?.setDetectionSource(value) == true

    override fun setEdgeConfidence(threshold: Float) = hostRef.get()?.setEdgeConfidence(threshold) == true

    override fun setMediamtxServer(value: String) = hostRef.get()?.setMediamtxServer(value) == true

    override fun setDjiSurfaceH264Encoder(enabled: Boolean) {
        hostRef.get()?.setDjiSurfaceH264Encoder(enabled)
    }

    override fun restartActiveStreaming() {
        hostRef.get()?.restartActiveStreaming()
    }

    override fun setStreamingMode(mode: StreamingMode) {
        hostRef.get()?.setStreamingMode(mode)
    }

    override fun startAutoSensing() {
        hostRef.get()?.startAutoSensing()
    }

    override fun stopAutoSensing() {
        hostRef.get()?.stopAutoSensing()
    }

    override fun updateManualOverrideUI() {
        hostRef.get()?.updateManualOverrideUI()
    }

    override fun classifyCommandSource(presentedToken: String?) =
        hostRef.get()?.classifyCommandSource(presentedToken) ?: ControlAuthority.Source.PILOT

    override fun readThermalMaxTempNow() = hostRef.get()?.readThermalMaxTempNow()

    override fun readLrfMeasurement() = hostRef.get()?.readLrfMeasurement() ?: LrfMeasurement(null, null, null)

    override fun setLrfTarget(target: GeoPoint3D?) {
        hostRef.get()?.setLrfTarget(target)
    }

    override fun hasThermalCamera() = hostRef.get()?.hasThermalCamera() == true

    override fun setAutoSensingSwitchChecked(checked: Boolean) {
        hostRef.get()?.setAutoSensingSwitchChecked(checked)
    }

    fun attach(host: LyrebirdCommandHost) {
        hostRef = WeakReference(host)
    }

    fun detach(host: LyrebirdCommandHost) {
        if (hostRef.get() === host) hostRef.clear()
    }

    private fun detachedResult() = CommandResult(MavlinkCommandOutcome.FAILED, "runtime detached")
}

private class RuntimeMavlinkCommandSink : MavlinkCommandSink {
    private var sinkRef: WeakReference<MavlinkCommandSink> = WeakReference(null)

    fun attach(sink: MavlinkCommandSink) {
        sinkRef = WeakReference(sink)
    }

    fun detach(sink: MavlinkCommandSink) {
        if (sinkRef.get() === sink) sinkRef.clear()
    }

    private fun sink() = sinkRef.get()

    private fun detached() = CommandResult(MavlinkCommandOutcome.FAILED, "runtime detached")

    override fun setGimbal(rotation: GimbalRotation) = sink()?.setGimbal(rotation) ?: detached()

    override fun setCameraZoom(zoomRatio: Float) = sink()?.setCameraZoom(zoomRatio) ?: detached()

    override fun startVideoRecording() = sink()?.startVideoRecording() ?: detached()

    override fun stopVideoRecording() = sink()?.stopVideoRecording() ?: detached()

    override fun captureImage() = sink()?.captureImage() ?: detached()

    override fun setGimbalRelative(
        pitchDeg: Double,
        yawDeg: Double,
    ) = sink()?.setGimbalRelative(pitchDeg, yawDeg) ?: detached()

    override fun measureLrf() = sink()?.measureLrf() ?: detached()

    override fun captureTemperature() = sink()?.captureTemperature() ?: detached()

    override fun captureThermalImage() = sink()?.captureThermalImage() ?: detached()

    override fun dropPayload() = sink()?.dropPayload() ?: detached()

    override fun setAutoSensing(enabled: Boolean) = sink()?.setAutoSensing(enabled) ?: detached()

    override fun setParameter(
        name: String,
        value: Float,
    ) = sink()?.setParameter(name, value) ?: detached()

    override fun setTextParameter(
        name: String,
        value: String,
    ) = sink()?.setTextParameter(name, value) ?: detached()

    override fun textParameters() = sink()?.textParameters().orEmpty()

    override fun setRegionOfInterest(
        latitudeDeg: Double,
        longitudeDeg: Double,
        altitudeM: Double,
    ) = sink()?.setRegionOfInterest(latitudeDeg, longitudeDeg, altitudeM) ?: detached()

    override fun clearRegionOfInterest() = sink()?.clearRegionOfInterest() ?: detached()
}

internal object ProcessNetworkRuntimeRegistry {
    private val runtime = ProcessNetworkRuntime()

    fun attach(
        context: Context,
        host: LyrebirdCommandHost,
        sink: MavlinkCommandSink,
        callbacks: NetworkRuntimeCallbacks,
    ) = runtime.attach(context, host, sink, callbacks)

    fun start() = runtime.start()

    fun status() = runtime.status()

    fun hasTelemetryClients() = runtime.hasTelemetryClients()

    fun detach(host: LyrebirdCommandHost) = runtime.detach(host)
}

private class ProcessNetworkRuntime {
    private val hostBridge = RuntimeCommandHost()
    private val sinkBridge = RuntimeMavlinkCommandSink()
    private var callbacksRef: WeakReference<NetworkRuntimeCallbacks> = WeakReference(null)
    private var discovery: LyrebirdDiscoveryManager? = null
    private var session: LyrebirdSession? = null

    @Synchronized
    fun attach(
        context: Context,
        host: LyrebirdCommandHost,
        sink: MavlinkCommandSink,
        callbacks: NetworkRuntimeCallbacks,
    ) {
        hostBridge.attach(host)
        sinkBridge.attach(sink)
        callbacksRef = WeakReference(callbacks)
        if (discovery == null) {
            discovery = LyrebirdDiscoveryManager(context.applicationContext) { callbacksRef.get()?.runtimeDroneSerial ?: host.droneName }
        }
    }

    @Synchronized
    fun start(): LyrebirdSessionStatus {
        val existing = session
        if (existing != null && existing.status.isServing) return existing.status
        val telemetry =
            TelemetryServer(TELEMETRY_PORT, { callbacksRef.get()?.telemetryJson() ?: "{}" }, {
                callbacksRef.get()?.gapTelemetryJson()
                    ?: "{}"
            }).apply {
                onFirstClientConnected = { clientIp -> callbacksRef.get()?.onTelemetryClient(clientIp) }
            }
        session =
            LyrebirdSession(
                lease = DeviceSessionLeaseRegistry.current(),
                http = SimpleHttpServer(HTTP_PORT, hostBridge, sinkBridge),
                telemetry = telemetry,
                advertiser =
                    DiscoveryAdvertiser(discovery ?: error("runtime not attached")) {
                        callbacksRef.get()?.runtimeDroneSerial
                            ?: hostBridge.droneName
                    },
                httpPort = HTTP_PORT,
                telemetryPort = TELEMETRY_PORT,
            )
        return session!!.start()
    }

    fun status() = session?.status ?: LyrebirdSessionStatus()

    fun hasTelemetryClients() = session?.hasTelemetryClients() == true

    @Synchronized
    fun detach(host: LyrebirdCommandHost) {
        hostBridge.detach(host)
        callbacksRef.clear()
    }
}
