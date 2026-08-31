package com.lyrebird.rc.mavlink

/**
 * A stable, unique MAVLink system id per aircraft.
 *
 * QGroundControl treats two vehicles with the same system id as the same vehicle (its
 * "Multi-Vehicle Gotchas" note — the symptom is the Plan view jerking between positions), so every
 * Lyrebird device must advertise a distinct id. The id is derived from a stable per-aircraft key
 * and can be overridden explicitly by the `lb_mav_0_sysid` preference.
 *
 * The valid unique range is 1..254; 0 and 255 are reserved in MAVLink (broadcast/all), so this
 * helper never produces them.
 */
internal object MavlinkSystemId {

    /** Configured value meaning "derive the id from the aircraft identity". */
    const val AUTO = 0

    const val MIN = 1
    const val MAX = 254

    /**
     * Deterministic id in [MIN]..[MAX] for [key].
     *
     * `String.hashCode()` is specified by the JLS, so the same key maps to the same id across
     * restarts and JVM versions — the id survives a reboot.
     */
    fun fromKey(key: String): Int =
        MIN + (key.hashCode() and Int.MAX_VALUE) % (MAX - MIN + 1)

    /**
     * Resolve the configured preference: [AUTO] derives from [key]; anything else is an explicit id
     * clamped to [MIN]..[MAX].
     */
    fun resolve(configured: Int, key: String): Int =
        if (configured == AUTO) fromKey(key) else configured.coerceIn(MIN, MAX)
}
