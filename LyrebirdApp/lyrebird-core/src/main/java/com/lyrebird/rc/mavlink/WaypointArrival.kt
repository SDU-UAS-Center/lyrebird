package com.lyrebird.rc.mavlink

/**
 * What "arrived" means for one waypoint, as the plan defined it.
 *
 * MAVLink puts these on the mission item because only the plan knows which leg is the last one:
 * param2 is the acceptance radius, param1 the hold time, and param3 zero means fly through. A
 * single goto carries none of them and takes [DEFAULT], because a lone reposition is a
 * destination rather than a leg.
 *
 * Lives with the ports rather than on any one controller because both the shared mission policy
 * and every SDK adapter pass it through: the controller decides what to do with the criteria,
 * not what they are.
 */
data class WaypointArrival(
    val acceptanceRadiusM: Double? = null,
    val holdSeconds: Double = 0.0,
    val passThrough: Boolean = false,
) {
    companion object {
        val DEFAULT = WaypointArrival()
    }
}
