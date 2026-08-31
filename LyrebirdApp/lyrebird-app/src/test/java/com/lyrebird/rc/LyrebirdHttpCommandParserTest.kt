package com.lyrebird.rc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyrebirdHttpCommandParserTest {
    @Test
    fun parseStickReadsFourFloatChannels() {
        assertEquals(
            LyrebirdHttpCommandParser.StickCommand(1.0f, -1.0f, 0.25f, -0.5f),
            LyrebirdHttpCommandParser.parseStick("1.0,-1.0,0.25,-0.5")
        )
    }

    @Test
    fun parseGimbalReadsRollPitchAndYaw() {
        assertEquals(
            LyrebirdHttpCommandParser.GimbalCommand(roll = 1.0, pitch = -10.0, yaw = 90.0),
            LyrebirdHttpCommandParser.parseGimbal("1.0,-10.0,90.0")
        )
    }

    @Test
    fun parseWaypointRejectsMissingAltitude() {
        val result = LyrebirdHttpCommandParser.parseWaypoint("55.0,12.0")

        assertEquals(
            LyrebirdHttpCommandParser.ParseResult.Invalid(
                "Invalid input. Expected format: lat,lon,alt"
            ),
            result
        )
    }

    @Test
    fun parseWaypointPidReadsAllFields() {
        val result = LyrebirdHttpCommandParser.parseWaypointPid("55.1,12.2,30.0,180.0,3.5")

        assertEquals(
            LyrebirdHttpCommandParser.ParseResult.Valid(
                LyrebirdHttpCommandParser.WaypointPidCommand(
                    latitude = 55.1,
                    longitude = 12.2,
                    altitude = 30.0,
                    yaw = 180.0,
                    maxSpeed = 3.5
                )
            ),
            result
        )
    }

    @Test
    fun parseTrajectoryReadsSemicolonSeparatedWaypoints() {
        val result = LyrebirdHttpCommandParser.parseTrajectory(
            "55.0,12.0,10.0; 55.1,12.1,11.0"
        )

        assertEquals(
            LyrebirdHttpCommandParser.ParseResult.Valid(
                listOf(Triple(55.0, 12.0, 10.0), Triple(55.1, 12.1, 11.0))
            ),
            result
        )
    }

    @Test
    fun parseTrajectoryReportsSegmentIndex() {
        val result = LyrebirdHttpCommandParser.parseTrajectory("55.0,12.0,10.0;55.1,12.1")

        assertEquals(
            LyrebirdHttpCommandParser.ParseResult.Invalid(
                "Invalid input at segment 1: expected lat,lon,alt"
            ),
            result
        )
    }

    @Test
    fun parseNativeTrajectoryReadsSpeedAndWaypoints() {
        val result = LyrebirdHttpCommandParser.parseNativeTrajectory(
            "2.5;55.0,12.0,10.0;55.1,12.1,11.0"
        )

        assertEquals(
            LyrebirdHttpCommandParser.ParseResult.Valid(
                LyrebirdHttpCommandParser.NativeTrajectoryCommand(
                    speed = 2.5,
                    waypoints = listOf(Triple(55.0, 12.0, 10.0), Triple(55.1, 12.1, 11.0))
                )
            ),
            result
        )
    }

    @Test
    fun parseNativeTrajectoryRejectsInvalidSpeed() {
        val result = LyrebirdHttpCommandParser.parseNativeTrajectory(
            "fast;55.0,12.0,10.0;55.1,12.1,11.0"
        )

        assertTrue(result is LyrebirdHttpCommandParser.ParseResult.Invalid)
        assertEquals(
            "Invalid input. Speed must be a number.",
            (result as LyrebirdHttpCommandParser.ParseResult.Invalid).message
        )
    }
}
