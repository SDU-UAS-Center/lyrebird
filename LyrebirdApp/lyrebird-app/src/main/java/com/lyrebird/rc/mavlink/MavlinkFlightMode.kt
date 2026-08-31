package com.lyrebird.rc.mavlink

/**
 * Lyrebird's own flight modes, reported in `HEARTBEAT.custom_mode`.
 *
 * The heartbeat claims `MAV_AUTOPILOT_PX4` — not because Lyrebird runs PX4, but because
 * QGroundControl only enables its Fly View action buttons (Takeoff / Land / RTL) for firmware
 * plugins that declare guided-mode capabilities, and its Generic plugin (selected for
 * `MAV_AUTOPILOT_INVALID` / `_GENERIC`) declares none. The cost of that claim is that QGC
 * renders `custom_mode` through PX4's mode enum, so the values here are PX4's packed mode
 * numbers (`(main_mode << 16) | (sub_mode << 24)`) and each Lyrebird mode is mapped to the
 * PX4 mode whose meaning matches — which PX4's list does remarkably well, down to Takeoff, Land
 * and Return existing as named modes (ArduCopter's list has no such modes, which is why PX4 is
 * the right claim rather than a smaller lie).
 *
 * The reverse direction matters too: QGC's PX4 plugin asks for Land / RTL by sending `SET_MODE`
 * with these numbers, and [fromPx4Mode] turns a request back into the mode it named, which the
 * endpoint then acts on through the motion sink.
 *
 * The names line up with `MAV_STANDARD_MODE` where a portable equivalent exists, so the
 * standard-modes protocol (`AVAILABLE_MODES` / `CURRENT_MODE` / `MAV_CMD_DO_SET_STANDARD_MODE`)
 * can advertise them by their portable identity as well. [MANUAL] and [INTELLIGENT] have no
 * standard equivalent and stay custom-only rather than being forced into a nearby mode that
 * would misdescribe what the aircraft is doing.
 *
 * @param guided the aircraft is being flown by something other than the pilot's sticks
 * @param manualInput the pilot on the sticks has authority
 */
internal enum class MavlinkFlightMode(
    val customMode: Int,
    val guided: Boolean,
    val manualInput: Boolean,
    /** MAV_STANDARD_MODE value, or [Mav.STANDARD_MODE_NON_STANDARD] when there is no portable one. */
    val standardMode: Int,
    /** Name a ground station shows. Only meaningful for modes with no standard identity. */
    val displayName: String
) {
    UNKNOWN(0, false, false, Mav.STANDARD_MODE_NON_STANDARD, "Unknown"),
    POSITION_HOLD(Mav.PX4_MODE_POSCTL, false, true, Mav.STANDARD_MODE_POSITION_HOLD, "Position"),
    ALTITUDE_HOLD(Mav.PX4_MODE_ALTCTL, false, true, Mav.STANDARD_MODE_ALTITUDE_HOLD, "Altitude"),
    OFFBOARD(Mav.PX4_MODE_OFFBOARD, true, false, Mav.STANDARD_MODE_NON_STANDARD, "Offboard"),
    MISSION(Mav.PX4_MODE_MISSION, true, false, Mav.STANDARD_MODE_MISSION, "Mission"),
    TAKEOFF(Mav.PX4_MODE_TAKEOFF, true, false, Mav.STANDARD_MODE_TAKEOFF, "Takeoff"),
    LAND(Mav.PX4_MODE_LAND, true, false, Mav.STANDARD_MODE_LAND, "Land"),
    SAFE_RECOVERY(Mav.PX4_MODE_RTL, true, false, Mav.STANDARD_MODE_SAFE_RECOVERY, "Return"),
    ORBIT(Mav.PX4_MODE_ORBIT, true, false, Mav.STANDARD_MODE_ORBIT, "Orbit"),

    /** DJI's fully manual mode: no position or altitude assistance. */
    MANUAL(Mav.PX4_MODE_MANUAL, false, true, Mav.STANDARD_MODE_NON_STANDARD, "Manual"),

    /** DJI intelligent-flight modes — follow, tap-fly, cinematic and friends. */
    INTELLIGENT(Mav.PX4_MODE_FOLLOW_TARGET, true, false, Mav.STANDARD_MODE_NON_STANDARD, "Intelligent");

    companion object {
        /**
         * The modes advertised to a ground station, in the order they are listed.
         *
         * [UNKNOWN] is excluded deliberately: it describes "we cannot tell", not a mode anyone
         * could select, and listing it would put a meaningless entry in the mode picker.
         */
        val ADVERTISED: List<MavlinkFlightMode> = entries.filter { it != UNKNOWN }

        /** Reverse lookup for inbound PX4 SET_MODE / DO_SET_MODE requests. */
        private val BY_CUSTOM_MODE: Map<Int, MavlinkFlightMode> =
            entries.associateBy { it.customMode }.filterKeys { it != 0 }

        /**
         * Map a ground station's requested PX4 custom_mode back onto a Lyrebird mode, or null
         * when the number is not one of ours. The endpoint acts on the modes with a DJI
         * equivalent (takeoff, land, return) and refuses the rest.
         */
        fun fromPx4Mode(customMode: Int): MavlinkFlightMode? = BY_CUSTOM_MODE[customMode]

        /**
         * Map a DJI flight-mode name onto a Lyrebird mode.
         *
         * The names below are the actual `dji.sdk.keyvalue.value.flightcontroller.FlightMode`
         * constants from MSDK 5.18, read out of the SDK jar — an earlier version of this mapping
         * was written from plausible guesses and silently reported UNKNOWN against a real
         * aircraft, which reports `GPS_NORMAL` rather than `GPS`.
         *
         * When the manual-override latch is set the pilot has taken the sticks, so the reported
         * mode is a position hold regardless of what the autonomous layer was doing — which is
         * the behaviour the mode model makes standard later, when the latch is replaced by this
         * transition outright.
         */
        fun fromDjiMode(djiMode: String, manualOverrideActive: Boolean): MavlinkFlightMode {
            if (manualOverrideActive) return POSITION_HOLD
            return when (djiMode.uppercase()) {
                "GPS_NORMAL", "GPS_SPORT", "GPS_TRIPOD", "GPS_NOVICE",
                "APAS", "AUTO_AVOIDANCE" -> POSITION_HOLD

                "ATTI", "ATTI_LANDING" -> ALTITUDE_HOLD
                "MANUAL" -> MANUAL
                "VIRTUAL_STICK" -> OFFBOARD
                "WAYPOINT" -> MISSION
                "GO_HOME" -> SAFE_RECOVERY
                "AUTO_LANDING", "FORCE_LANDING" -> LAND
                "TAKE_OFF_READY", "AUTO_TAKE_OFF", "MOTOR_START" -> TAKEOFF
                "POI" -> ORBIT

                "SMART_FLIGHT", "SMART_FLY", "CLICK_GO", "FOLLOW_ME", "TAP_FLY",
                "QUICK_MOVIE", "MASTER_SHOT", "CINEMATIC", "DRAW", "PANO",
                "TIME_LAPSE" -> INTELLIGENT

                else -> UNKNOWN
            }
        }
    }
}
