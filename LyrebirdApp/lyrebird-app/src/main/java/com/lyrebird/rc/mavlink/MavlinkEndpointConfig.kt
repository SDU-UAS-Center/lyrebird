package com.lyrebird.rc.mavlink

/**
 * Configuration for one MAVLink endpoint, shaped after PX4's MAVLink instance parameters.
 *
 * PX4 does not have "a MAVLink connection" — it has numbered instances, each switched entirely by
 * parameter: `MAV_n_CONFIG` chooses the port or disables the instance, `MAV_n_MODE` chooses a
 * stream profile, `MAV_n_RATE` caps bandwidth. Lyrebird copies that shape so the endpoint can be
 * enabled per aircraft rather than per build, and so a second instance can later serve the Safety
 * Computer with its own profile and rate.
 *
 * [enabled] defaults to true so a freshly installed device is reachable without hand-editing
 * prefs. This is safe only because the surface is payload-only — no flight motion is wired through
 * it. The operator-control / signing gate arrives with the authority phase and re-hardens this.
 */
internal data class MavlinkEndpointConfig(
    val enabled: Boolean = true,
    /** Where to send. Empty means broadcast on the local subnet. */
    val targetHost: String = "",
    val targetPort: Int = DEFAULT_GCS_PORT,
    /** Local port to bind, so the endpoint also learns peers that talk to it first. */
    val listenPort: Int = DEFAULT_GCS_PORT,
    val mode: Profile = Profile.NORMAL,
    /** Resolved MAVLink system id (1..254). One per aircraft, the way a GCS distinguishes vehicles. */
    val systemId: Int = DEFAULT_SYSTEM_ID,
    /**
     * Which of Lyrebird's two path followers flies an uploaded plan. A setting rather than a
     * second mission protocol: MAVLink has one, and a ground station has no way to pick an
     * executor, so the choice belongs in a parameter.
     *
     * Defaults to [MissionExecutor.DJI_NATIVE]: the mission runs on DJI's own flight controller
     * rather than the app's PID loop, so it survives the phone losing focus or being backgrounded
     * mid-flight. `onboard` remains selectable for a plan that leans on behaviour DJI's wayline
     * engine cannot represent, such as a continuously-tracking region of interest.
     */
    /** Signing key as hex, or empty when signing is not configured. */
    val signingKeyHex: String = "",
    val missionExecutor: MissionExecutor = MissionExecutor.DJI_NATIVE
) {
    /**
     * Stream profile, the equivalent of `MAV_n_MODE`. Each profile picks a different set of
     * messages and rates; [MINIMAL] exists for links where bandwidth matters more than smoothness.
     */
    enum class Profile(val prefValue: String) {
        NORMAL("normal"),
        MINIMAL("minimal");

        companion object {
            fun fromPref(value: String?): Profile =
                entries.firstOrNull { it.prefValue == value } ?: NORMAL
        }
    }

    companion object {
        /** The port QGroundControl listens on by default. */
        const val DEFAULT_GCS_PORT = 14550
        /** 0 = auto-derive the id from the aircraft identity; resolved by [MavlinkSystemId]. */
        const val DEFAULT_SYSTEM_ID = MavlinkSystemId.AUTO

        const val PREF_ENABLED = "lb_mav_0_enabled"
        const val PREF_HOST = "lb_mav_0_host"
        const val PREF_PORT = "lb_mav_0_port"
        const val PREF_MODE = "lb_mav_0_mode"
        const val PREF_SYSTEM_ID = "lb_mav_0_sysid"
        const val PREF_MISSION_EXECUTOR = "lb_mission_exec"

        /** Master switch for flight motion; false means every motion command is refused. */
        const val PREF_ALLOW_FLIGHT = "lb_mav_0_allow_flight"

        /**
         * 64 hex characters of MAVLink 2 signing key, shared with the Safety Computer.
         *
         * Absent means signing is not configured and every caller is the Pilot, which is how the
         * endpoint behaved before signing existed.
         */
        const val PREF_SIGNING_KEY = "lb_mav_0_signing_key"
    }
}
