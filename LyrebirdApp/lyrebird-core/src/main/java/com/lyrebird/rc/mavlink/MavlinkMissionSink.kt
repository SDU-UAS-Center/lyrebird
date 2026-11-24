package com.lyrebird.rc.mavlink

/**
 * Flies a stored mission. Implemented in the host activity, where the DJI SDK is.
 *
 * Lyrebird has two ways to fly a path and they are genuinely different, so the executor is a
 * choice rather than an implementation detail:
 *
 *  - **Onboard** sequences the items itself through the PID waypoint controllers, one at a time,
 *    advancing when each is reached. It honours [MissionItem.noseForward] per item, which the DJI
 *    path cannot, and it is the default.
 *  - **DJI native** hands the whole list to the aircraft's own wayline engine. Smoother, and it
 *    keeps flying if the phone's attention lapses, but the heading behaviour is DJI's.
 *
 * This interface can move the aircraft. Implementations must pass the same gates as
 * [MavlinkMotionSink] — the allow-flight preference, control authority, and the RC override latch.
 */
interface MavlinkMissionSink {
    /**
     * Whether a plan is being flown right now.
     *
     * Read before any command that takes the aircraft elsewhere, so a guided command can stop the
     * sequencer rather than racing it.
     */
    val isRunning: Boolean

    /** Begin flying [items] from index [startIndex]. */
    fun startMission(
        items: List<MissionItem>,
        startIndex: Int,
        executor: MissionExecutor,
    ): CommandResult

    /** Stop flying and hold. Does not clear the stored plan. */
    fun stopMission(): CommandResult

    /** Called by the endpoint so the sink can report progress back as it flies. */
    fun setProgressListener(listener: MissionProgressListener?)
}

/** Which of Lyrebird's two path-following implementations flies the plan. */
enum class MissionExecutor(
    val prefValue: String,
    /** How the app's settings show it. */
    val label: String,
) {
    /** Lyrebird sequences items through the PID waypoint controllers. */
    ONBOARD("onboard", "Onboard (Lyrebird)"),

    /** DJI's own wayline engine flies the whole list; no camera or gimbal items. */
    DJI_NATIVE("dji_native", "DJI native (waypoints only)"),
    ;

    companion object {
        /**
         * Onboard by default, for now: in field tests (October 2026) it flew every item of a
         * QGroundControl plan -- take-off, waypoints, photo, video, return -- while DJI's wayline
         * generator could not carry camera or gimbal items on a Mini 4 Pro, and was once slow
         * enough to start a mission ~50 s late. DJI native keeps the stronger property, a mission
         * that keeps flying if the app is backgrounded or killed, and should become the default
         * again once it carries a plan as completely. Chosen in the app's settings (FLEET ->
         * Mission executor).
         */
        fun fromPref(value: String?): MissionExecutor = entries.firstOrNull { it.prefValue == value } ?: ONBOARD
    }
}

/** How the sink tells the endpoint where the mission has got to. */
interface MissionProgressListener {
    /** The executor has started flying item [seq]. */
    fun onItemStarted(seq: Int)

    /** Item [seq] has been reached. */
    fun onItemReached(seq: Int)

    /** The plan finished, or stopped early. */
    fun onMissionFinished(completed: Boolean)
}
