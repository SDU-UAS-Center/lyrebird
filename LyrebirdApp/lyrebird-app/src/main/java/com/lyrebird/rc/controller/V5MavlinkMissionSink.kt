package com.lyrebird.rc.controller

import android.os.Handler
import android.util.Log
import com.lyrebird.rc.DroneControlProfiles
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
import com.lyrebird.rc.telemetry.V5AircraftTelemetrySource
import com.lyrebird.rc.utils.wpml.WaypointInfoModel
import dji.sdk.keyvalue.value.common.LocationCoordinate3D
import dji.sdk.wpmz.value.mission.ActionGimbalRotateParam
import dji.sdk.wpmz.value.mission.ActionStartRecordParam
import dji.sdk.wpmz.value.mission.ActionStopRecordParam
import dji.sdk.wpmz.value.mission.ActionTakePhotoParam
import dji.sdk.wpmz.value.mission.WaylineActionInfo
import dji.sdk.wpmz.value.mission.WaylineActionType
import dji.sdk.wpmz.value.mission.WaylineFinishedAction
import dji.sdk.wpmz.value.mission.WaylineGimbalActuatorRotateMode
import dji.sdk.wpmz.value.mission.WaylineLocationCoordinate3D
import kotlin.concurrent.thread

internal interface V5MavlinkMissionHost {
    val aircraftTelemetry: V5AircraftTelemetrySource
    val mainHandler: Handler
    val mavlinkCommandSink: MavlinkCommandSink
    val mavlinkMotionSink: MavlinkMotionSink
    var roiTarget: LocationCoordinate3D?

    fun mavlinkFlightGate(): CommandResult?

    fun setCameraMode(mode: Int)
}

internal class V5MavlinkMissionSink(
    private val host: V5MavlinkMissionHost,
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
    internal val mavlinkMissionSink =
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
                host.mavlinkFlightGate()?.let { return it }
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
                    Mav.CMD_SET_CAMERA_MODE -> host.setCameraMode(item.param2.toInt())
                    Mav.CMD_IMAGE_START_CAPTURE -> host.mavlinkCommandSink.captureImage()
                    Mav.CMD_VIDEO_START_CAPTURE -> host.mavlinkCommandSink.startVideoRecording()
                    Mav.CMD_VIDEO_STOP_CAPTURE -> host.mavlinkCommandSink.stopVideoRecording()
                    Mav.CMD_DO_GIMBAL_MANAGER_PITCHYAW ->
                        host.mavlinkCommandSink.setGimbal(
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
                        host.mavlinkCommandSink.setGimbal(
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
                        host.mavlinkCommandSink.setRegionOfInterest(
                            item.latitudeDeg,
                            item.longitudeDeg,
                            item.altitudeM,
                        )
                    Mav.CMD_DO_SET_ROI_NONE -> host.mavlinkCommandSink.clearRegionOfInterest()
                    Mav.CMD_DO_SET_ROI ->
                        if (item.param1.toInt() == Mav.ROI_MODE_LOCATION) {
                            host.mavlinkCommandSink.setRegionOfInterest(
                                item.latitudeDeg,
                                item.longitudeDeg,
                                item.altitudeM,
                            )
                        } else {
                            host.mavlinkCommandSink.clearRegionOfInterest()
                        }
                    Mav.CMD_NAV_TAKEOFF -> {
                        host.mavlinkMotionSink.takeoff(item.altitudeM.toFloat().takeIf { it > 0f })
                        if (!awaitAirborne()) {
                            Log.w(TAG, "Take-off did not complete in time; aborting mission")
                            return false
                        }
                    }
                    Mav.CMD_NAV_LAND -> {
                        host.mavlinkMotionSink.land()
                        return false
                    }
                    Mav.CMD_NAV_RETURN_TO_LAUNCH -> {
                        host.mavlinkMotionSink.returnToHome()
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
                        host.aircraftTelemetry
                            .readState()
                            .readings.flying &&
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
                val location = host.aircraftTelemetry.getLocation3D()
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
                    host.mavlinkCommandSink.captureImage()
                }
            }

            override fun stopMission(): CommandResult {
                running = false
                missionThread?.interrupt()
                missionThread = null
                host.mainHandler.post { DroneController.abortAllMissions() }
                return CommandResult(MavlinkCommandOutcome.ACCEPTED)
            }
        }
}
