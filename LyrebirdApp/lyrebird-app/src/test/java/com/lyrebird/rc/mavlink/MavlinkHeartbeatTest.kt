package com.lyrebird.rc.mavlink

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HEARTBEAT mode reporting.
 *
 * The heartbeat's custom_mode is what a ground station shows in its mode widget, and this pins
 * the two ways it can be wrong: mapping DJI's reported mode through PX4 numbers, and — the one
 * that bit in the field — reporting the sequencer's transport (virtual stick = OFFBOARD) instead
 * of the mission that is actually flying. A ground station that started a mission but never saw
 * the vehicle leave its pre-mission mode is the failure this guards against.
 */
class MavlinkHeartbeatTest {

    private fun customMode(snapshot: MavlinkSnapshot): Long =
        ByteBuffer.wrap(MavlinkMessages.heartbeat(snapshot)).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL

    private fun baseMode(snapshot: MavlinkSnapshot): Int =
        ByteBuffer.wrap(MavlinkMessages.heartbeat(snapshot)).get(6).toInt() and 0xFF

    @Test
    fun djiPositionModeIsReportedAsPosition() {
        val mode = customMode(MavlinkSnapshot(flightMode = "GPS_NORMAL"))
        assertEquals(Mav.PX4_MODE_POSCTL.toLong(), mode)
        // Position hold is pilot-assisted, not autonomous: no guided flag.
        assertTrue((baseMode(MavlinkSnapshot(flightMode = "GPS_NORMAL")) and Mav.MODE_FLAG_GUIDED_ENABLED) == 0)
    }

    @Test
    fun virtualStickFlightIsReportedAsOffboard() {
        val mode = customMode(MavlinkSnapshot(flightMode = "VIRTUAL_STICK"))
        assertEquals(Mav.PX4_MODE_OFFBOARD.toLong(), mode)
        assertTrue((baseMode(MavlinkSnapshot(flightMode = "VIRTUAL_STICK")) and Mav.MODE_FLAG_GUIDED_ENABLED) != 0)
    }

    @Test
    fun aRunningMissionIsReportedAsMissionEvenUnderVirtualStick() {
        // The onboard sequencer flies through virtual stick, so DJI reports VIRTUAL_STICK —
        // read as OFFBOARD. While a plan is running the reported mode must be MISSION.
        val snapshot = MavlinkSnapshot(flightMode = "VIRTUAL_STICK", missionActive = true)
        assertEquals(Mav.PX4_MODE_MISSION.toLong(), customMode(snapshot))
        assertTrue((baseMode(snapshot) and Mav.MODE_FLAG_GUIDED_ENABLED) != 0)
    }

    @Test
    fun djiWaypointModeIsReportedAsMission() {
        assertEquals(Mav.PX4_MODE_MISSION.toLong(), customMode(MavlinkSnapshot(flightMode = "WAYPOINT")))
    }

    @Test
    fun customModeFlagIsAlwaysSet() {
        assertTrue((baseMode(MavlinkSnapshot(flightMode = "GPS_NORMAL")) and Mav.MODE_FLAG_CUSTOM_MODE_ENABLED) != 0)
    }

    @Test
    fun realMotorActivityReportsArmed() {
        assertTrue((baseMode(MavlinkSnapshot(motorsRunning = true)) and Mav.MODE_FLAG_SAFETY_ARMED) != 0)
    }

    @Test
    fun anAcceptedArmCommandReportsArmedEvenBeforeMotorsSpin() {
        // DJI has no arming state, so the heartbeat only ever reports armed because an ARM was
        // accepted (or the motors are actually running). Without this QGC's arm wait times out
        // with "vehicle rejected arming" while the aircraft is already taking off.
        val snapshot = MavlinkSnapshot(armedCommanded = true)
        assertTrue((baseMode(snapshot) and Mav.MODE_FLAG_SAFETY_ARMED) != 0)
    }

    @Test
    fun aGroundStationDoesNotReportArmedOutOfTheBlue() {
        val snapshot = MavlinkSnapshot(flightMode = "GPS_NORMAL")
        assertTrue((baseMode(snapshot) and Mav.MODE_FLAG_SAFETY_ARMED) == 0)
    }

    @Test
    fun anRthFlightIsReportedAsSafeRecoveryEvenWhenOverridden() {
        // The app activates the override latch when the pilot presses RTH on the controller, so
        // RTH and the latch arrive together. RTH must win: a ground station reacting to a drone
        // going home has to see SAFE_RECOVERY, not a position hold.
        val snapshot = MavlinkSnapshot(flightMode = "GO_HOME", manualOverrideActive = true)
        assertEquals(Mav.PX4_MODE_RTL.toLong(), customMode(snapshot))
        assertTrue((baseMode(snapshot) and Mav.MODE_FLAG_GUIDED_ENABLED) != 0)
    }

    @Test
    fun anRthFlightIsReportedAsSafeRecoveryEvenWhileAMissionIsActive() {
        // An RTH that interrupted a mission must read as Return, not as the mission that is no
        // longer flying.
        val snapshot = MavlinkSnapshot(flightMode = "GO_HOME", missionActive = true)
        assertEquals(Mav.PX4_MODE_RTL.toLong(), customMode(snapshot))
    }

    @Test
    fun anOverrideLatchIsReportedAsManualNotPositionHold() {
        // With the latch set the pilot has the sticks and autonomous commands are refused; a
        // heartbeat-only ground station must be able to tell that from ordinary GPS flight.
        val snapshot = MavlinkSnapshot(flightMode = "GPS_NORMAL", manualOverrideActive = true)
        assertEquals(Mav.PX4_MODE_MANUAL.toLong(), customMode(snapshot))
        assertTrue((baseMode(snapshot) and Mav.MODE_FLAG_GUIDED_ENABLED) == 0)
        assertTrue((baseMode(snapshot) and Mav.MODE_FLAG_MANUAL_INPUT_ENABLED) != 0)
    }

    @Test
    fun aDjiManualFlightReportsManual() {
        assertEquals(Mav.PX4_MODE_MANUAL.toLong(), customMode(MavlinkSnapshot(flightMode = "MANUAL")))
    }
}
