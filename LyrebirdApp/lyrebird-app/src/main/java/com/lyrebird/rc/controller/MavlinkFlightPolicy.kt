package com.lyrebird.rc.controller

import com.lyrebird.rc.mavlink.CommandResult
import com.lyrebird.rc.mavlink.MavlinkCommandOutcome

/** Shared authorization decision used by every MAVLink flight-motion entry point. */
internal class MavlinkFlightPolicy(
    private val authorize: (ControlAuthority.Source) -> Boolean = ControlAuthority::authorizeControlCommand,
) {
    fun check(
        flightAllowed: Boolean,
        trustedOrigin: Boolean,
    ): CommandResult? {
        if (!flightAllowed) {
            return CommandResult(
                MavlinkCommandOutcome.DENIED,
                "MAVLink flight command blocked - flight control not allowed",
            )
        }
        val source = if (trustedOrigin) ControlAuthority.Source.SAFETY else ControlAuthority.Source.PILOT
        if (!authorize(source)) {
            return CommandResult(
                MavlinkCommandOutcome.DENIED,
                "MAVLink flight command blocked - the Safety Computer has control",
            )
        }
        return null
    }
}
