package com.lyrebird.rc.controller

import android.util.Log
import com.lyrebird.rc.mavlink.CommandResult
import com.lyrebird.rc.mavlink.DistanceTrigger
import com.lyrebird.rc.mavlink.GimbalRotation
import com.lyrebird.rc.mavlink.GimbalRotationMode
import com.lyrebird.rc.mavlink.Mav
import com.lyrebird.rc.mavlink.MavlinkCommandOutcome
import com.lyrebird.rc.mavlink.MavlinkCommandSink
import com.lyrebird.rc.mavlink.MavlinkMissionSink
import com.lyrebird.rc.mavlink.MavlinkMotionSink
import com.lyrebird.rc.mavlink.MissionExecutor
import com.lyrebird.rc.mavlink.MissionItem
import com.lyrebird.rc.mavlink.MissionProgressListener
import com.lyrebird.rc.mavlink.MotionCommandPort
import com.lyrebird.rc.mavlink.PendingKind
import com.lyrebird.rc.mavlink.WaypointArrival
import com.lyrebird.rc.mavlink.WaypointRejection
import com.lyrebird.rc.telemetry.AircraftTelemetrySource
import com.lyrebird.rc.telemetry.GeoPosition

/**
 * What the shared mission policy needs from its host besides the ports.
 *
 * The host owns the settings/authority gate and the application-side actions (camera mode, the
 * default cruise speed, the main-looper post the abort needs), plus the one controller fact the
 * airborne wait cares about. Deliberately no SDK value type and no controller statics.
 */
internal interface MavlinkMissionHost {
    /** The settings/authority gate shared with the motion policy; null when flying is allowed. */
    fun mavlinkFlightGate(): CommandResult?

    fun setCameraMode(mode: Int)

    /** The active aircraft profile's default cruise speed, for a plan that sets no speed. */
    fun defaultCruiseSpeedMps(): Double

    /** Post a block to the thread the controller expects (the main looper in the app). */
    fun postToMain(block: () -> Unit)

    /** True while the app's own command tracking says a take-off is still climbing. */
    fun isTakeoffStillClimbing(): Boolean

    /**
     * Hand a plan to the SDK's own mission engine — the DJI wayline executor in V5, an explicit
     * refusal anywhere that has none. Compilation and execution stay in the adapter; a plan it
     * cannot represent must refuse rather than drop items.
     *
     * [onProgress] receives each waypoint index the engine reaches and [onFinished] its terminal
     * outcome. The wrapper here owns the sequencer's own bookkeeping — the running latch and the
     * listener — around whichever engine the adapter starts.
     */
    fun startNativeMission(
        items: List<MissionItem>,
        onProgress: (Int) -> Unit,
        onFinished: (Boolean) -> Unit,
    ): CommandResult
}

/**
 * Mission commands over MAVLink, behind the safety gate.
 *
 * The onboard executor is the interesting half. Until now the sequencing lived on the ground
 * station: it sent one waypoint, watched the reach latch, and sent the next — which is why the
 * seq-tracked reach flags exist at all. MAVLink expects the vehicle to own that state, because
 * MISSION_CURRENT and MISSION_ITEM_REACHED come from the aircraft, so this moves the loop into
 * the app.
 *
 * Each item picks its own controller through param4: NaN means fly nose-forward, a value means
 * hold that heading. One plan can mix them, which the two separate HTTP endpoints cannot express.
 *
 * This is the policy every backend shares: it decides the order, and [MotionCommandPort],
 * [MavlinkMotionSink] and [MavlinkCommandSink] do the flying. It used to live beside the V5
 * controller, reading its statics — which would have made a second SDK a second copy of every
 * rule in here. The one half that stays SDK-local is native-plan compilation, behind
 * [MavlinkMissionHost.startNativeMission].
 */
internal class MavlinkMissionPolicy(
    private val host: MavlinkMissionHost,
    private val commands: MotionCommandPort,
    private val motionSink: MavlinkMotionSink,
    private val commandSink: MavlinkCommandSink,
    private val telemetry: AircraftTelemetrySource,
    private val scheduler: MissionScheduler = SystemMissionScheduler,
) {
    companion object {
        private const val TAG = "LyrebirdDefaultLayout"
        private const val TAKEOFF_CLIMB_TIMEOUT_MS = 30_000L
        private const val TAKEOFF_POLL_MS = 500L
        private const val MISSION_LEG_TIMEOUT_MS = 300_000L
        private const val MISSION_POLL_MS = 200L
    }

    val sink: MavlinkMissionSink
        get() = mavlinkMissionSink

    internal val mavlinkMissionSink =
        object : MavlinkMissionSink {
            private var listener: MissionProgressListener? = null

            @Volatile
            private var missionTask: MissionTask? = null

            @Volatile
            private var running = false

            /**
             * Distance-triggered capture state for the plan being flown, or null when the plan set
             * no trigger distance. [CMD_DO_SET_CAM_TRIGG_DIST] arms it; the accumulate-and-compare
             * arithmetic lives in [DistanceTrigger], and [triggerAnchor] is the fix it last
             * advanced from, so consecutive fixes make a path.
             */
            private var distanceTrigger: DistanceTrigger? = null
            private var triggerAnchor: GeoPosition? = null

            override val isRunning: Boolean get() = running

            override fun setProgressListener(listener: MissionProgressListener?) {
                this.listener = listener
            }

            override fun startMission(
                items: List<MissionItem>,
                startIndex: Int,
                executor: MissionExecutor,
            ): CommandResult {
                host.mavlinkFlightGate()?.let { return it }
                // A plan is an autonomous command like any other. The sequencer aborts on the first
                // leg if the latch is set, but refusing it here says so plainly rather than
                // accepting a mission that is going to stop immediately.
                if (commands.shouldRejectAutonomousCommand("mission")) {
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
             * Hand the plan to the SDK engine and wrap its progress in the sequencer's own state.
             *
             * The compilation — wayline models, actions, the finish action — is the adapter's;
             * this owns the running latch and the listener around it, so a native plan reports
             * progress the same way an onboard one does.
             */
            private fun startNative(items: List<MissionItem>): CommandResult {
                running = true
                val result =
                    host.startNativeMission(
                        items,
                        onProgress = { index -> listener?.onItemStarted(index) },
                        onFinished = { success ->
                            running = false
                            listener?.onMissionFinished(success)
                        },
                    )
                if (result.outcome != MavlinkCommandOutcome.ACCEPTED) {
                    // Refused before anything started: no mission to report as running.
                    running = false
                    return result
                }
                // Guarded so a start that failed and reported finished on the way in cannot be
                // followed by a start announcement after its finish.
                if (running) listener?.onItemStarted(0)
                return result
            }

            /**
             * Sequence the items ourselves, one waypoint at a time.
             *
             * Runs on a worker because it waits: each leg is issued, then the reach latch is
             * polled until the matching seq reports arrival. Comparing the seq rather than just
             * the boolean is what stops a stale latch from a previous leg being read as this
             * one's arrival — the same reason the seq mechanism exists on the HTTP surface.
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
                        ?: host.defaultCruiseSpeedMps()

                // A fresh plan means a fresh trigger state: the armed distance and the accumulated
                // ground both belong to whichever plan set them, not to a previously flown one.
                distanceTrigger = null
                triggerAnchor = null
                running = true
                missionTask =
                    scheduler.runAsync("MavlinkMission") {
                        // Every item in order, not only the waypoints. Walking the waypoints alone
                        // meant a plan's camera and gimbal actions were carried through the upload
                        // and then silently dropped, so a survey flew the right path and
                        // photographed nothing.
                        for ((index, item) in items.withIndex()) {
                            if (!running) break
                            if (index < startIndex) continue

                            if (!item.isWaypoint) {
                                // Actions take effect where they sit in the plan and do not block:
                                // their whole purpose is to be in force for the legs that follow.
                                item.speedMps?.let { speed = it }
                                listener?.onItemStarted(index)
                                if (!executePlanAction(item)) {
                                    listener?.onMissionFinished(false)
                                    running = false
                                    return@runAsync
                                }
                                listener?.onItemReached(index)
                                continue
                            }

                            listener?.onItemStarted(index)
                            val seq = flyLeg(item, speed, isLast = index == lastLegIndex)
                            if (!awaitLeg(seq)) {
                                // Interrupted, overridden, or timed out — stop rather than
                                // skipping on.
                                listener?.onMissionFinished(false)
                                running = false
                                return@runAsync
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
             * under way. A payload action that fails is logged and the plan carries on: a camera
             * that will not switch mode is a worse photograph, not a reason to abandon a survey
             * mid-air.
             */
            private fun executePlanAction(item: MissionItem): Boolean {
                when (item.command) {
                    Mav.CMD_SET_CAMERA_MODE -> host.setCameraMode(item.param2.toInt())
                    Mav.CMD_IMAGE_START_CAPTURE -> commandSink.captureImage()
                    Mav.CMD_VIDEO_START_CAPTURE -> commandSink.startVideoRecording()
                    Mav.CMD_VIDEO_STOP_CAPTURE -> commandSink.stopVideoRecording()
                    Mav.CMD_DO_GIMBAL_MANAGER_PITCHYAW ->
                        commandSink.setGimbal(
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
                        commandSink.setGimbal(
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
                        commandSink.setRegionOfInterest(
                            item.latitudeDeg,
                            item.longitudeDeg,
                            item.altitudeM,
                        )
                    Mav.CMD_DO_SET_ROI_NONE -> commandSink.clearRegionOfInterest()
                    Mav.CMD_DO_SET_ROI ->
                        if (item.param1.toInt() == Mav.ROI_MODE_LOCATION) {
                            commandSink.setRegionOfInterest(
                                item.latitudeDeg,
                                item.longitudeDeg,
                                item.altitudeM,
                            )
                        } else {
                            commandSink.clearRegionOfInterest()
                        }
                    Mav.CMD_NAV_TAKEOFF -> {
                        // Through the gated motion sink, not the raw port: a plan's take-off obeys
                        // the same authority gate and post-takeoff climb as a commanded one.
                        motionSink.takeoff(item.altitudeM.toFloat().takeIf { it > 0f })
                        if (!awaitAirborne()) {
                            Log.w(TAG, "Take-off did not complete in time; aborting mission")
                            return false
                        }
                    }
                    Mav.CMD_NAV_LAND -> {
                        motionSink.land()
                        return false
                    }
                    Mav.CMD_NAV_RETURN_TO_LAUNCH -> {
                        motionSink.returnToHome()
                        return false
                    }
                    // A speed change has already been folded into the running speed above; there
                    // is nothing else to do with it.
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
                    WaypointArrival(
                        acceptanceRadiusM = item.acceptanceRadiusM,
                        holdSeconds = item.holdSeconds,
                        // The final leg is never a pass-through, whatever the plan says: there is
                        // nothing after it to fly on to, so the aircraft settles there.
                        passThrough = item.passThrough && !isLast,
                    )
                return commands.waypoint(
                    latitudeDeg = item.latitudeDeg,
                    longitudeDeg = item.longitudeDeg,
                    altitudeMeters = item.altitudeM,
                    yawDeg = yaw,
                    speedMps = speed,
                    noseForward = item.noseForward,
                    arrival = arrival,
                )
            }

            /**
             * Block until a take-off just commanded has actually left the ground.
             *
             * [executePlanAction] returns as soon as [MavlinkMotionSink.takeoff] is issued,
             * because DJI's own take-off climb is asynchronous. Without this wait the sequencer
             * moved straight on to the first waypoint while the aircraft was still in its
             * take-off sequence, which had the waypoint controller fight DJI for the sticks and
             * left [awaitLeg] polling a seq the aircraft was never going to report — the mission
             * looked stalled rather than flown.
             */
            private fun awaitAirborne(): Boolean {
                val deadline = scheduler.nowMs() + TAKEOFF_CLIMB_TIMEOUT_MS
                while (running && scheduler.nowMs() < deadline) {
                    val airborne =
                        telemetry.readState().readings.flying &&
                            !host.isTakeoffStillClimbing()
                    if (airborne) return true
                    runCatching { scheduler.sleepMs(TAKEOFF_POLL_MS) }.onFailure {
                        Thread.currentThread().interrupt()
                        return false
                    }
                }
                return false
            }

            /** Wait for the leg with this seq to report reached. False if it did not. */
            private fun awaitLeg(seq: Long): Boolean {
                val deadline = scheduler.nowMs() + MISSION_LEG_TIMEOUT_MS
                while (running && scheduler.nowMs() < deadline) {
                    if (commands.isManualOverrideActive) return false
                    // A refused leg was never going to be flown, so waiting can only end at the
                    // timeout. The obstacle guard publishes the refusal with the same seq the leg
                    // was issued under, which is exactly what makes the two matchable here.
                    val refusal = commands.lastWaypointRefusal()
                    if (refusal?.seq == seq && refusal.reason != WaypointRejection.NONE) {
                        Log.w(TAG, "Mission leg seq=$seq refused (${refusal.reason}); stopping plan")
                        return false
                    }
                    val latch = commands.reachLatch(PendingKind.WAYPOINT)
                    if (latch.seq == seq && latch.reached) {
                        return true
                    }
                    maybeCaptureByDistance()
                    runCatching { scheduler.sleepMs(MISSION_POLL_MS) }.onFailure {
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
                val location = telemetry.read().location
                val last = triggerAnchor
                if (last == null ||
                    (location.latitudeDeg == 0.0 && location.longitudeDeg == 0.0)
                ) {
                    triggerAnchor = location
                    return
                }
                val travelled = last.distanceTo(location)
                triggerAnchor = location
                if (trigger.addTravelled(travelled)) {
                    Log.i(TAG, "Distance trigger: capturing (${"%.1f".format(travelled)}m since last fix)")
                    commandSink.captureImage()
                }
            }

            override fun stopMission(): CommandResult {
                running = false
                missionTask?.interrupt()
                missionTask = null
                host.postToMain { commands.abortAllMissions() }
                return CommandResult(MavlinkCommandOutcome.ACCEPTED)
            }
        }
}
