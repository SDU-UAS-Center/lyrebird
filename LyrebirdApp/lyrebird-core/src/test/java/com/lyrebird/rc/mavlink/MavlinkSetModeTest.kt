package com.lyrebird.rc.mavlink

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * MAV_CMD_DO_SET_MODE as MAVSDK sends it, and as Lyrebird's own Python client does.
 *
 * Field regression: MAVSDK's return_to_launch() and goto_location() both start with a mode change
 * sent PX4's way, main mode in param2 and sub mode in param3. Reading param2 alone as the packed
 * number made Return (4, 5) and Hold (4, 3) unknown modes, so both were refused as UNSUPPORTED.
 */
class MavlinkSetModeTest {
    @Test
    fun `main and sub mode in separate params are packed the way PX4 packs them`() {
        assertEquals(Mav.PX4_MODE_RTL, MavlinkFlightMode.px4ModeFromDoSetMode(4f, 5f))
        assertEquals(Mav.PX4_MODE_LOITER, MavlinkFlightMode.px4ModeFromDoSetMode(4f, 3f))
        assertEquals(Mav.PX4_MODE_POSCTL, MavlinkFlightMode.px4ModeFromDoSetMode(3f, 0f))
        assertEquals(Mav.PX4_MODE_OFFBOARD, MavlinkFlightMode.px4ModeFromDoSetMode(6f, Float.NaN))
    }

    @Test
    fun `an already packed mode in param2 is taken as it is`() {
        assertEquals(
            Mav.PX4_MODE_POSCTL,
            MavlinkFlightMode.px4ModeFromDoSetMode(Mav.PX4_MODE_POSCTL.toFloat(), 0f),
        )
        assertEquals(
            Mav.PX4_MODE_OFFBOARD,
            MavlinkFlightMode.px4ModeFromDoSetMode(Mav.PX4_MODE_OFFBOARD.toFloat(), 0f),
        )
    }

    @Test
    fun `return and hold resolve to modes the aircraft acts on`() {
        assertEquals(MavlinkFlightMode.SAFE_RECOVERY, MavlinkFlightMode.fromPx4Mode(Mav.PX4_MODE_RTL))
        assertEquals(MavlinkFlightMode.POSITION_HOLD, MavlinkFlightMode.fromPx4Mode(Mav.PX4_MODE_LOITER))
    }
}
