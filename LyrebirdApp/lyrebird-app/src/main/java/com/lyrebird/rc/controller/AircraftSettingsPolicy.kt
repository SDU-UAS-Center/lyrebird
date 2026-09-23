package com.lyrebird.rc.controller

import com.lyrebird.rc.mavlink.CommandResult
import com.lyrebird.rc.mavlink.MavlinkCommandOutcome

/**
 * Validation and prose for the aircraft settings a ground station can write.
 *
 * Extracted from the HTTP route table so the parts that are pure — which control-mode values the
 * wire accepts, and what a refusal says — are decided in one place and unit-tested, instead of
 * living in the route that happens to serve them. The route used to answer every failed write
 * with "Invalid control mode (jp|usa|ch|custom)", which told a ground station its request was
 * malformed even when the real reason was that no screen was attached to reach the aircraft.
 *
 * Whether the aircraft is in standby (and therefore cannot accept settings at all) is asked by
 * the caller and passed in: that answer needs DJI keys, and this policy must stay loadable in a
 * host JVM test.
 */
object AircraftSettingsPolicy {
    /** DJI's control-mode values, lowercase, exactly as they cross the wire. */
    val CONTROL_MODES: List<String> = listOf("jp", "usa", "ch", "custom")

    private const val INVALID_CONTROL_MODE = "Invalid control mode (jp|usa|ch|custom)"

    /** The wire value of a control mode, or null when the request names no mode DJI has. */
    fun normalizeControlMode(raw: String): String? = raw.trim().lowercase().takeIf { it in CONTROL_MODES }

    /** A control mode that is not one of [CONTROL_MODES]: the one case the client is at fault. */
    fun rejectedControlMode(): CommandResult = refused(INVALID_CONTROL_MODE)

    /**
     * A control mode the client asked for correctly but the aircraft did not take.
     *
     * @param mode the normalized mode value.
     * @param inStandby whether the aircraft's flight controller is unreachable right now, which
     *   is a reason the operator can act on rather than a malformed request.
     */
    fun controlModeRefused(
        mode: String,
        inStandby: Boolean,
    ): CommandResult =
        if (inStandby) {
            refused("Aircraft in standby: RC control mode not changed (wanted $mode)")
        } else {
            refused("RC control mode not applied ($mode)")
        }

    private fun refused(detail: String): CommandResult = CommandResult(MavlinkCommandOutcome.DENIED, detail = detail)
}
