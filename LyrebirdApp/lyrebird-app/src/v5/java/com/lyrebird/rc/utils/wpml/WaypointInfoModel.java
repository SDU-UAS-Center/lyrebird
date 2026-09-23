package com.lyrebird.rc.utils.wpml;

import java.util.List;

import dji.sdk.wpmz.value.mission.WaylineActionInfo;
import dji.sdk.wpmz.value.mission.WaylineWaypoint;

/**
 * @author feel.feng
 * @time 2023/07/05 5:31 下午
 * @description:
 */
public class WaypointInfoModel {

    WaylineWaypoint waylineWaypoint;
    List<WaylineActionInfo> actionInfos;

    /**
     * When set, one photo is taken every this many metres over the span this waypoint is part of.
     *
     * Carries the MAVLink DO_SET_CAM_TRIGG_DIST distance: the template builder turns a contiguous
     * run of waypoints that share a value into one MULTIPLE_DISTANCE action group, which is
     * WPML's own form of distance-triggered capture. Null (or zero/negative) on a waypoint means
     * no distance trigger covers it.
     */
    private Double distanceIntervalMeters;

    public WaylineWaypoint getWaylineWaypoint() {
        return waylineWaypoint;
    }

    public void setWaylineWaypoint(WaylineWaypoint waylineWaypoint) {
        this.waylineWaypoint = waylineWaypoint;
    }

    public List<WaylineActionInfo> getActionInfos() {
        return actionInfos;
    }

    public void setActionInfos(List<WaylineActionInfo> actionInfos) {
        this.actionInfos = actionInfos;
    }

    public Double getDistanceIntervalMeters() {
        return distanceIntervalMeters;
    }

    public void setDistanceIntervalMeters(Double distanceIntervalMeters) {
        this.distanceIntervalMeters = distanceIntervalMeters;
    }

}
