package com.lyrebird.rc.server

import android.util.Log
import com.lyrebird.rc.mavlink.CommandProgress
import com.lyrebird.rc.mavlink.CommandResult
import com.lyrebird.rc.mavlink.GimbalRotation
import com.lyrebird.rc.mavlink.MavlinkCommand
import com.lyrebird.rc.mavlink.MavlinkCommandSink
import com.lyrebird.rc.mavlink.MavlinkEndpointConfig
import com.lyrebird.rc.mavlink.MavlinkFtpServer
import com.lyrebird.rc.mavlink.MavlinkMissionSink
import com.lyrebird.rc.mavlink.MavlinkMotionSink
import com.lyrebird.rc.mavlink.MavlinkSnapshot
import com.lyrebird.rc.mavlink.MavlinkTelemetryEndpoint
import com.lyrebird.rc.mavlink.MavlinkVideoStream
import com.lyrebird.rc.mavlink.MissionExecutor
import com.lyrebird.rc.mavlink.MissionItem
import com.lyrebird.rc.mavlink.MissionProgressListener
import com.lyrebird.rc.mavlink.PendingCommand
import java.lang.ref.WeakReference
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

internal interface MavlinkRuntimeCallbacks {
    fun runtimeMavlinkConfig(): MavlinkEndpointConfig

    fun runtimeMavlinkSnapshot(): MavlinkSnapshot

    fun runtimeMavlinkVideoStream(): MavlinkVideoStream?

    fun runtimeMavlinkParameters(): List<Pair<String, Float>>

    fun runtimeLogMavlinkCommand(
        command: com.lyrebird.rc.mavlink.MavlinkCommand,
        result: CommandResult,
        signed: Boolean,
    )

    fun runtimeMavlinkPeerDiscovered(peer: String)
}

internal interface MavlinkMediaSource : MavlinkFtpServer.FtpFileSource

private class CommandBridge : MavlinkCommandSink {
    @Volatile
    private var ref: WeakReference<MavlinkCommandSink> = WeakReference(null)

    fun attach(value: MavlinkCommandSink) {
        ref = WeakReference(value)
    }

    fun detach(value: MavlinkCommandSink) {
        if (ref.get() === value) ref.clear()
    }

    private fun value() = ref.get()

    private fun failed() = CommandResult(com.lyrebird.rc.mavlink.MavlinkCommandOutcome.FAILED, "runtime detached")

    override fun setGimbal(rotation: GimbalRotation) = value()?.setGimbal(rotation) ?: failed()

    override fun setCameraZoom(zoomRatio: Float) = value()?.setCameraZoom(zoomRatio) ?: failed()

    override fun startVideoRecording() = value()?.startVideoRecording() ?: failed()

    override fun stopVideoRecording() = value()?.stopVideoRecording() ?: failed()

    override fun captureImage() = value()?.captureImage() ?: failed()

    override fun setGimbalRelative(
        pitchDeg: Double,
        yawDeg: Double,
    ) = value()?.setGimbalRelative(pitchDeg, yawDeg) ?: failed()

    override fun measureLrf() = value()?.measureLrf() ?: failed()

    override fun captureTemperature() = value()?.captureTemperature() ?: failed()

    override fun captureThermalImage() = value()?.captureThermalImage() ?: failed()

    override fun dropPayload() = value()?.dropPayload() ?: failed()

    override fun setAutoSensing(enabled: Boolean) = value()?.setAutoSensing(enabled) ?: failed()

    override fun setParameter(
        name: String,
        value: Float,
    ) = value()?.setParameter(name, value) ?: failed()

    override fun setTextParameter(
        name: String,
        value: String,
    ) = value()?.setTextParameter(name, value) ?: failed()

    override fun textParameters() = value()?.textParameters().orEmpty()

    override fun setRegionOfInterest(
        latitudeDeg: Double,
        longitudeDeg: Double,
        altitudeM: Double,
    ) = value()?.setRegionOfInterest(latitudeDeg, longitudeDeg, altitudeM) ?: failed()

    override fun clearRegionOfInterest() = value()?.clearRegionOfInterest() ?: failed()
}

private class MediaBridge : MavlinkMediaSource {
    @Volatile
    private var ref: WeakReference<MavlinkMediaSource> = WeakReference(null)

    fun attach(value: MavlinkMediaSource) {
        ref = WeakReference(value)
    }

    fun detach(value: MavlinkMediaSource) {
        if (ref.get() === value) ref.clear()
    }

    override fun listFiles() = ref.get()?.listFiles().orEmpty()

    override fun readFileBytes(name: String) = ref.get()?.readFileBytes(name)
}

private class MotionBridge : MavlinkMotionSink {
    @Volatile
    private var ref: WeakReference<MavlinkMotionSink> = WeakReference(null)

    fun attach(value: MavlinkMotionSink) {
        ref = WeakReference(value)
    }

    fun detach(value: MavlinkMotionSink) {
        if (ref.get() === value) ref.clear()
    }

    private fun value() = ref.get()

    private fun failed() = CommandResult(com.lyrebird.rc.mavlink.MavlinkCommandOutcome.FAILED, "runtime detached")

    override fun takeoff(altitudeM: Float?) = value()?.takeoff(altitudeM) ?: failed()

    override fun land() = value()?.land() ?: failed()

    override fun returnToHome() = value()?.returnToHome() ?: failed()

    override fun reposition(
        latitudeDeg: Double,
        longitudeDeg: Double,
        altitudeMeters: Double,
        yawDeg: Double,
        groundSpeedMps: Double,
    ) = value()?.reposition(latitudeDeg, longitudeDeg, altitudeMeters, yawDeg, groundSpeedMps) ?: failed()

    override fun setYaw(yawDeg: Double) = value()?.setYaw(yawDeg) ?: failed()

    override fun orbit(
        latitudeDeg: Double,
        longitudeDeg: Double,
        altitudeMeters: Double,
        radiusMeters: Double,
        tangentialSpeedMps: Double,
        clockwise: Boolean,
        arcDegrees: Double,
        faceCentre: Boolean,
    ) = value()?.orbit(latitudeDeg, longitudeDeg, altitudeMeters, radiusMeters, tangentialSpeedMps, clockwise, arcDegrees, faceCentre)
        ?: failed()

    override fun abortToPositionHold() = value()?.abortToPositionHold() ?: failed()

    override fun enableOffboard() = value()?.enableOffboard() ?: failed()

    override fun manualControl(
        roll: Float,
        pitch: Float,
        throttle: Float,
        yaw: Float,
    ) = value()?.manualControl(roll, pitch, throttle, yaw) ?: failed()

    override fun setAltitude(altitudeMeters: Double) = value()?.setAltitude(altitudeMeters) ?: failed()

    override fun releaseManualOverride() = value()?.releaseManualOverride() ?: failed()

    override fun releaseSafetyControl() = value()?.releaseSafetyControl() ?: failed()

    override fun pollCompletion(pending: PendingCommand) = value()?.pollCompletion(pending) ?: CommandProgress.ABANDONED

    override fun arm() = value()?.arm() ?: failed()

    override fun disarm() = value()?.disarm() ?: failed()
}

private class MissionBridge : MavlinkMissionSink {
    @Volatile
    private var ref: WeakReference<MavlinkMissionSink> = WeakReference(null)

    fun attach(value: MavlinkMissionSink) {
        ref = WeakReference(value)
    }

    fun detach(value: MavlinkMissionSink) {
        if (ref.get() === value) ref.clear()
    }

    private fun value() = ref.get()

    private fun failed() = CommandResult(com.lyrebird.rc.mavlink.MavlinkCommandOutcome.FAILED, "runtime detached")

    override val isRunning get() = value()?.isRunning == true

    override fun setProgressListener(listener: MissionProgressListener?) {
        value()?.setProgressListener(listener)
    }

    override fun startMission(
        items: List<MissionItem>,
        startIndex: Int,
        executor: MissionExecutor,
    ) = value()?.startMission(items, startIndex, executor) ?: failed()

    override fun stopMission() = value()?.stopMission() ?: failed()
}

internal object ProcessMavlinkRuntimeRegistry {
    private val runtime = ProcessMavlinkRuntime()

    fun attach(
        callbacks: MavlinkRuntimeCallbacks,
        media: MavlinkMediaSource,
        command: MavlinkCommandSink,
        motion: MavlinkMotionSink,
        mission: MavlinkMissionSink,
    ) = runtime.attach(callbacks, media, command, motion, mission)

    fun detach(
        callbacks: MavlinkRuntimeCallbacks,
        media: MavlinkMediaSource,
        command: MavlinkCommandSink,
        motion: MavlinkMotionSink,
        mission: MavlinkMissionSink,
    ) = runtime.detach(callbacks, media, command, motion, mission)

    fun start() = runtime.start()

    fun stop() = runtime.stop()

    fun isUp() = runtime.isUp()

    fun isTrustedOrigin() = runtime.isTrustedOrigin()

    fun reportCaptureStarted() = runtime.reportCaptureStarted()

    fun reportImageCaptured(
        success: Boolean,
        fileName: String,
    ) = runtime.reportImageCaptured(success, fileName)
}

private class ProcessMavlinkRuntime {
    private val command = CommandBridge()
    private val motion = MotionBridge()
    private val mission = MissionBridge()
    private val mediaBridge = MediaBridge()

    @Volatile
    private var callbacksRef: WeakReference<MavlinkRuntimeCallbacks> = WeakReference(null)

    @Volatile
    private var endpoint: MavlinkTelemetryEndpoint? = null
    private var ftp: com.lyrebird.rc.mavlink.MavlinkFtpServer? = null
    private val ftpExecutor: ExecutorService = Executors.newFixedThreadPool(2)

    @Synchronized
    fun attach(
        callbacks: MavlinkRuntimeCallbacks,
        mediaSource: MavlinkMediaSource,
        commandSink: MavlinkCommandSink,
        motionSink: MavlinkMotionSink,
        missionSink: MavlinkMissionSink,
    ) {
        callbacksRef = WeakReference(callbacks)
        mediaBridge.attach(mediaSource)
        command.attach(commandSink)
        motion.attach(motionSink)
        mission.attach(missionSink)
        if (endpoint == null) {
            ftp = MavlinkFtpServer(mediaBridge, ftpExecutor)
        }
    }

    @Synchronized
    fun detach(
        callbacks: MavlinkRuntimeCallbacks,
        mediaSource: MavlinkMediaSource,
        commandSink: MavlinkCommandSink,
        motionSink: MavlinkMotionSink,
        missionSink: MavlinkMissionSink,
    ) {
        if (callbacksRef.get() === callbacks) callbacksRef.clear()
        mediaBridge.detach(mediaSource)
        command.detach(commandSink)
        motion.detach(motionSink)
        mission.detach(missionSink)
    }

    @Synchronized
    fun start() {
        if (endpoint != null) return
        val source = callbacksRef.get() ?: return
        val config = source.runtimeMavlinkConfig()
        if (!config.enabled) return
        val fileServer = ftp ?: return
        val created =
            MavlinkTelemetryEndpoint(
                config,
                { callbacksRef.get()?.runtimeMavlinkSnapshot() ?: MavlinkSnapshot() },
                { callbacksRef.get()?.runtimeMavlinkVideoStream() },
                { callbacksRef.get()?.runtimeMavlinkParameters().orEmpty() },
                command,
                motion,
                mission,
                fileServer,
                commandLog = { mavlinkCommand, result ->
                    callbacksRef.get()?.runtimeLogMavlinkCommand(mavlinkCommand, result, endpoint?.isTrustedOrigin == true)
                },
            ).apply {
                onPeerDiscovered = { peer -> callbacksRef.get()?.runtimeMavlinkPeerDiscovered(peer) }
            }
        if (created.start()) endpoint = created else Log.w(TAG, "MAVLink endpoint did not start")
    }

    @Synchronized
    fun stop() {
        endpoint?.stop()
        endpoint = null
        ftp?.shutdown()
        ftp = null
    }

    fun isUp() = endpoint != null

    fun isTrustedOrigin() = endpoint?.isTrustedOrigin == true

    fun reportCaptureStarted() {
        endpoint?.reportCaptureStarted()
    }

    fun reportImageCaptured(
        success: Boolean,
        fileName: String,
    ) {
        endpoint?.reportImageCaptured(success, fileName)
    }

    companion object {
        private const val TAG = "LyrebirdMavlinkRuntime"
    }
}
