package com.lyrebird.rc.mavlink

/**
 * One stored mission item, in the shape MAVLink uploaded it.
 *
 * Deliberately close to the wire format rather than to DJI's. The uploaded items are the stored
 * plan and the source of truth for a download, so keeping them verbatim is what lets a ground
 * station read back exactly what it sent. Translating to whatever the executor wants happens on a
 * copy, at execution time — which is why DJI's wayline format not round-tripping limits what can
 * be flown, not what can be reported.
 */
internal data class MissionItem(
    val seq: Int,
    val command: Int,
    val param1: Float,
    val param2: Float,
    val param3: Float,
    /**
     * Yaw at the waypoint, in degrees, or NaN for "use the vehicle's own heading mode".
     *
     * This one field is the whole nose-forward / hold-heading distinction. Lyrebird has two
     * waypoint controllers and today they are two separate HTTP endpoints; in a mission they are
     * NaN versus a value, per item, so one plan can mix them.
     */
    val param4: Float,
    val latitudeDeg: Double,
    val longitudeDeg: Double,
    val altitudeM: Double,
    val autocontinue: Boolean
) {
    /** True when this item should be flown nose-forward rather than holding a fixed heading. */
    val noseForward: Boolean get() = param4.isNaN()

    /** A leg to fly. Kept here so an executor never has to know MAV_CMD numbers. */
    val isWaypoint: Boolean get() = command == Mav.CMD_NAV_WAYPOINT

    /** A speed change, applying to the legs that follow it. */
    val isSpeedChange: Boolean get() = command == Mav.CMD_DO_CHANGE_SPEED

    /** The ground speed this item asks for, or null when it is not a usable speed change. */
    val speedMps: Double? get() = param2.toDouble().takeIf { isSpeedChange && it > 0 }

    /**
     * Seconds to hold at this waypoint before it counts as reached — MAV_CMD_NAV_WAYPOINT param1.
     *
     * This and the two below are how a plan says which legs to pass through and which to settle
     * at. Only the plan knows which waypoint is the last one, which is exactly why MAVLink puts
     * these on the item rather than leaving the vehicle to guess.
     */
    val holdSeconds: Double get() = if (isWaypoint) param1.toDouble().coerceAtLeast(0.0) else 0.0

    /** Acceptance radius in metres — param2 — or null when the plan did not specify one. */
    val acceptanceRadiusM: Double? get() =
        param2.toDouble().takeIf { isWaypoint && it > 0 }

    /**
     * True when the plan asked to fly through rather than stop — param3 of zero.
     *
     * MAVLink defines param3 as the pass radius: zero means pass through the waypoint, and a
     * positive value means pass by it at that distance. Either way the aircraft is not meant to
     * stop, so both read as pass-through here; the orbit direction a positive value also encodes
     * is not something Lyrebird's controllers can fly.
     */
    val passThrough: Boolean get() = isWaypoint && holdSeconds == 0.0
}

/** MAV_MISSION_RESULT values used when acknowledging an upload. */
internal object MissionResult {
    const val ACCEPTED = 0
    const val ERROR = 1
    const val UNSUPPORTED = 3
    const val NO_SPACE = 4
    const val INVALID_SEQUENCE = 12
}

/** MISSION_STATE, reported in MISSION_CURRENT so a ground station can show plan progress. */
internal object MissionState {
    const val NO_MISSION = 1
    const val NOT_STARTED = 2
    const val ACTIVE = 3
    const val PAUSED = 4
    const val COMPLETE = 5
}

/**
 * Holds the uploaded plan and drives the upload handshake.
 *
 * The MAVLink upload is a conversation, not a single message: the ground station announces a
 * count, then the vehicle requests each item by index and the station answers, until the vehicle
 * acknowledges. This class owns that exchange's state so the endpoint stays a transport.
 *
 * Only `MAV_MISSION_TYPE_MISSION` is accepted. Geofences and rally points are refused because DJI
 * owns them through FlySafe and Lyrebird cannot write them — answering an upload we would then
 * ignore is the silent-drop failure that makes a plan upload dangerous.
 *
 * Thread-safety: the endpoint's receive thread drives the upload while the streaming thread reads
 * the plan for MISSION_CURRENT, so every access is synchronised.
 */
internal class MavlinkMissionStore(private val maxItems: Int = MAX_ITEMS) {

    /**
     * Commands accepted in an uploaded plan. Anything else is refused per item.
     *
     * Wider than the set the sequencer flies, and deliberately so. A mission upload is
     * all-or-nothing: one refused item fails the whole transfer, and QGroundControl reports it as
     * "Mission transfer failed" with the item number, not as a plan that mostly worked. So the
     * set has to cover what a ground station actually puts in a plan rather than only what an
     * aircraft strictly needs — a takeoff at the start, a return at the end, and the camera
     * actions QGC writes into item #0 whenever Mission Settings names one.
     */
    private val supportedCommands = setOf(
        Mav.CMD_NAV_WAYPOINT,
        Mav.CMD_NAV_TAKEOFF,
        Mav.CMD_NAV_LAND,
        Mav.CMD_NAV_RETURN_TO_LAUNCH,
        Mav.CMD_DO_CHANGE_SPEED,
        Mav.CMD_SET_CAMERA_MODE,
        Mav.CMD_IMAGE_START_CAPTURE,
        Mav.CMD_VIDEO_START_CAPTURE,
        Mav.CMD_VIDEO_STOP_CAPTURE,
        Mav.CMD_DO_GIMBAL_MANAGER_PITCHYAW,
        Mav.CMD_DO_MOUNT_CONTROL,
        Mav.CMD_DO_SET_CAM_TRIGG_DIST,
        Mav.CMD_DO_SET_ROI_LOCATION,
        Mav.CMD_DO_SET_ROI_NONE,
        Mav.CMD_DO_SET_ROI
    )

    private val items = mutableListOf<MissionItem>()

    /** Items arriving during an upload, kept apart so a failed upload cannot corrupt the plan. */
    private val incoming = mutableListOf<MissionItem>()

    @Volatile
    private var uploadExpected = 0

    @Volatile
    private var uploading = false

    /** Index the executor is flying, or -1 when nothing is running. */
    @Volatile
    private var currentSeq = -1

    @Volatile
    private var state = MissionState.NO_MISSION

    /** Changes whenever the stored plan changes, so a ground station can spot a stale cache. */
    @Volatile
    private var planId = 0

    @Synchronized
    fun count(): Int = items.size

    @Synchronized
    fun snapshot(): List<MissionItem> = items.toList()

    @Synchronized
    fun itemAt(seq: Int): MissionItem? = items.getOrNull(seq)

    @Synchronized
    fun missionState(): Int = state

    @Synchronized
    fun currentIndex(): Int = currentSeq.coerceAtLeast(0)

    @Synchronized
    fun currentPlanId(): Int = planId

    /**
     * Begin an upload of [count] items. Returns null to proceed, or a MAV_MISSION_RESULT to refuse.
     *
     * A count of zero is a valid way to clear the plan, and is completed immediately — there are
     * no items to request, so waiting for one would hang the handshake.
     */
    @Synchronized
    fun beginUpload(count: Int, missionType: Int): Int? {
        if (missionType != MISSION_TYPE_MISSION) return MissionResult.UNSUPPORTED
        if (count > maxItems) return MissionResult.NO_SPACE
        incoming.clear()
        uploadExpected = count
        uploading = count > 0
        if (count == 0) {
            items.clear()
            planId++
            state = MissionState.NO_MISSION
            currentSeq = -1
        }
        return null
    }

    /** The next item index to request, or null when the upload is complete. */
    @Synchronized
    fun nextRequestIndex(): Int? = if (uploading && incoming.size < uploadExpected) {
        incoming.size
    } else {
        null
    }

    /**
     * Accept one uploaded item. Returns null to continue, or a MAV_MISSION_RESULT to refuse.
     *
     * Out-of-order items are refused rather than reordered: the protocol requests them by index,
     * so an unexpected index means the two ends disagree about where they are, and guessing would
     * store a plan neither side intended.
     */
    @Synchronized
    fun acceptItem(item: MissionItem): Int? {
        if (!uploading) return MissionResult.ERROR
        if (item.seq != incoming.size) return MissionResult.INVALID_SEQUENCE
        if (item.command !in supportedCommands) return MissionResult.UNSUPPORTED
        incoming.add(item)
        return null
    }

    /** True once every announced item has arrived. */
    @Synchronized
    fun uploadComplete(): Boolean = uploading && incoming.size >= uploadExpected

    /** Promote the uploaded items to the stored plan. */
    @Synchronized
    fun commitUpload() {
        items.clear()
        items.addAll(incoming)
        incoming.clear()
        uploading = false
        planId++
        currentSeq = -1
        state = if (items.isEmpty()) MissionState.NO_MISSION else MissionState.NOT_STARTED
    }

    /** Abandon an upload in progress, leaving the previous plan untouched. */
    @Synchronized
    fun abortUpload() {
        incoming.clear()
        uploading = false
    }

    @Synchronized
    fun clear() {
        items.clear()
        incoming.clear()
        uploading = false
        planId++
        currentSeq = -1
        state = MissionState.NO_MISSION
    }

    /** Record that the executor has moved on to [seq]. */
    @Synchronized
    fun setCurrent(seq: Int) {
        currentSeq = seq.coerceIn(0, (items.size - 1).coerceAtLeast(0))
        state = MissionState.ACTIVE
    }

    @Synchronized
    fun setState(newState: Int) {
        state = newState
    }

    companion object {
        const val MISSION_TYPE_MISSION = 0

        /**
         * Upper bound on a stored plan. Generous for a DJI battery's endurance, and finite so a
         * ground station announcing an implausible count is refused with NO_SPACE rather than
         * being allowed to exhaust memory.
         */
        const val MAX_ITEMS = 1000
    }
}
