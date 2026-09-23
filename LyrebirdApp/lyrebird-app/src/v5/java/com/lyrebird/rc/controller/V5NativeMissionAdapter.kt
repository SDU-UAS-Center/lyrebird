package com.lyrebird.rc.controller

import android.util.Log
import com.lyrebird.rc.DroneControlProfiles
import com.lyrebird.rc.mavlink.CommandResult
import com.lyrebird.rc.mavlink.Mav
import com.lyrebird.rc.mavlink.MavlinkCommandOutcome
import com.lyrebird.rc.mavlink.MissionItem
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

/**
 * Compiles a stored plan into DJI's wayline format and hands it to the aircraft's own engine.
 *
 * This is the half of the mission surface that stays SDK-local: the shared
 * [MavlinkMissionPolicy] owns the running latch, the listener and the dispatch, and calls in
 * here only for [DroneController.navigateWaylineMissionNative]. Every wayline type — models,
 * actions, the mission config — lives behind this seam, so the policy above it never imports
 * the SDK.
 *
 * DJI's own take-off (to [WaylineMissionHelper]'s `securityTakeOffHeight`) and its wayline action
 * framework mean this carries much more of a plan than a bare waypoint path: a leading
 * NAV_TAKEOFF's altitude becomes the take-off height, a trailing LAND/RTL becomes the mission's
 * finish action, DO_CHANGE_SPEED becomes a per-leg [WaypointInfoModel.speedMps], param4 becomes a
 * fixed heading, and camera/gimbal items become wayline actions (translated by
 * [translatePlanActionToWaylineAction]) triggered at the waypoint they sit after — the same
 * "takes effect where it sits" semantics the onboard executor uses.
 *
 * `DO_SET_ROI`/`DO_SET_ROI_LOCATION` are compiled rather than dropped: MAVLink's ROI is modal (it
 * stays in force until `DO_SET_ROI_NONE` or a non-location `DO_SET_ROI`), so `currentRoi` below
 * is carried across items the same way and stamped onto every waypoint model built while it is
 * active, driving DJI's own `TOWARD_POI` yaw and gimbal modes — see
 * [WaylineMissionHelper.createWaypointFromLatLon]. `CMD_SET_CAMERA_MODE` remains skipped; DJI's
 * wayline engine has no camera-mode concept.
 */
internal object V5NativeMissionAdapter {
    private const val TAG = "LyrebirdDefaultLayout"

    fun start(
        items: List<MissionItem>,
        onProgress: (Int) -> Unit,
        onFinished: (Boolean) -> Unit,
    ): CommandResult {
        var speed =
            items.firstNotNullOfOrNull { it.speedMps }
                ?: DroneControlProfiles.activeProfile().defaultCruiseSpeedMps
        var currentRoi: WaylineLocationCoordinate3D? = null
        // Modal, exactly like the ROI above: a trigger distance set by one item stays in force for
        // every waypoint built after it, until another item changes or clears it.
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
                // A zero or negative distance is MAVLink's way of turning the trigger off; WPML
                // expresses the same thing by the waypoint carrying no interval.
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
        // Actions after the last leg (e.g. a final photo before landing) have no later waypoint to
        // attach to, so they ride along with the last one instead of being lost.
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

        // Distinct from NAVIGATING: DJI's own wayline engine is flying this, not the app's
        // virtual-stick loop, and the status badge showing MANUAL for a mission that is flying
        // perfectly fine was ambient RC stick noise being read as a takeover — see the MISSION
        // exclusion in VirtualStickVM.tryUpdateVirtualStickByRc().
        DroneController.markMissionActive()
        DroneController.navigateWaylineMissionNative(
            waypointModels,
            missionConfig,
            speed,
            onProgress = { waypointIndex -> onProgress(waypointIndex) },
            onFinished = { success ->
                DroneController.clearMissionActiveIfStillSet()
                onFinished(success)
            },
        )
        return CommandResult(MavlinkCommandOutcome.ACCEPTED)
    }

    /**
     * Translate one non-waypoint, non-ROI plan item into the DJI wayline action it maps to, or
     * null when there is none. ROI items are handled separately in [start], since they set
     * waypoint-level yaw/gimbal state rather than a one-shot triggered action;
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
            else -> {
                Log.d(TAG, "Native plan item ${item.command} has no wayline action")
                null
            }
        }
}
