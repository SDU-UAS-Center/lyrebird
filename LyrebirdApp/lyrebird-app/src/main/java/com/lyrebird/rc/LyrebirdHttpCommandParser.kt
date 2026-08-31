package com.lyrebird.rc

internal object LyrebirdHttpCommandParser {
    data class StickCommand(
        val leftX: Float,
        val leftY: Float,
        val rightX: Float,
        val rightY: Float
    )

    data class GimbalCommand(
        val roll: Double,
        val pitch: Double,
        val yaw: Double
    )

    data class WaypointCommand(
        val latitude: Double,
        val longitude: Double,
        val altitude: Double
    )

    data class WaypointPidCommand(
        val latitude: Double,
        val longitude: Double,
        val altitude: Double,
        val yaw: Double,
        val maxSpeed: Double
    )

    data class NativeTrajectoryCommand(
        val speed: Double,
        val waypoints: List<Triple<Double, Double, Double>>
    )

    sealed interface ParseResult<out T> {
        data class Valid<T>(val value: T) : ParseResult<T>
        data class Invalid(val message: String) : ParseResult<Nothing>
    }

    fun parseStick(postData: String): StickCommand {
        val parts = csvParts(postData)
        return StickCommand(
            leftX = parts[0].toFloat(),
            leftY = parts[1].toFloat(),
            rightX = parts[2].toFloat(),
            rightY = parts[3].toFloat()
        )
    }

    fun parseGimbal(postData: String): GimbalCommand {
        val parts = csvParts(postData)
        return GimbalCommand(
            roll = parts[0].toDouble(),
            pitch = parts[1].toDouble(),
            yaw = parts[2].toDouble()
        )
    }

    fun parseWaypoint(postData: String): ParseResult<WaypointCommand> {
        val parts = csvParts(postData)
        if (parts.size < 3) {
            return ParseResult.Invalid("Invalid input. Expected format: lat,lon,alt")
        }
        return ParseResult.Valid(
            WaypointCommand(
                latitude = parts[0].toDouble(),
                longitude = parts[1].toDouble(),
                altitude = parts[2].toDouble()
            )
        )
    }

    fun parseWaypointPid(postData: String): ParseResult<WaypointPidCommand> {
        val parts = csvParts(postData)
        if (parts.size < 5) {
            return ParseResult.Invalid("Invalid input. Expected format: lat,lon,alt,yaw,maxSpeed")
        }
        return ParseResult.Valid(
            WaypointPidCommand(
                latitude = parts[0].toDouble(),
                longitude = parts[1].toDouble(),
                altitude = parts[2].toDouble(),
                yaw = parts[3].toDouble(),
                maxSpeed = parts[4].toDouble()
            )
        )
    }

    fun parseTrajectory(postData: String): ParseResult<List<Triple<Double, Double, Double>>> {
        val segments = trajectorySegments(postData)
        if (segments.isEmpty()) {
            return ParseResult.Invalid("Invalid input. Expected at least one waypoint.")
        }
        return parseWaypointSegments(segments, firstWaypointIndex = 0)
    }

    fun parseNativeTrajectory(postData: String): ParseResult<NativeTrajectoryCommand> {
        val segments = trajectorySegments(postData)
        if (segments.size < 3) {
            return ParseResult.Invalid(
                "Invalid input. Need speed and at least 2 waypoints: speed;lat,lon,alt;..."
            )
        }
        val speed = segments[0].toDoubleOrNull()
            ?: return ParseResult.Invalid("Invalid input. Speed must be a number.")
        val waypoints = parseWaypointSegments(segments.drop(1), firstWaypointIndex = 0)
        if (waypoints is ParseResult.Invalid) return waypoints
        val parsedWaypoints = (waypoints as ParseResult.Valid).value
        if (parsedWaypoints.size < 2) {
            return ParseResult.Invalid("Invalid input. Need at least 2 waypoints.")
        }
        return ParseResult.Valid(NativeTrajectoryCommand(speed, parsedWaypoints))
    }

    private fun parseWaypointSegments(
        segments: List<String>,
        firstWaypointIndex: Int
    ): ParseResult<List<Triple<Double, Double, Double>>> {
        val waypoints = mutableListOf<Triple<Double, Double, Double>>()
        for (index in segments.indices) {
            val parts = csvParts(segments[index])
            if (parts.size < 3) {
                return ParseResult.Invalid(
                    "Invalid input at segment ${index + firstWaypointIndex}: expected lat,lon,alt"
                )
            }
            waypoints.add(Triple(parts[0].toDouble(), parts[1].toDouble(), parts[2].toDouble()))
        }
        return ParseResult.Valid(waypoints)
    }

    private fun csvParts(postData: String): List<String> {
        return postData.split(",").map { it.trim() }
    }

    private fun trajectorySegments(postData: String): List<String> {
        return postData.split(";").map { it.trim() }.filter { it.isNotEmpty() }
    }
}
