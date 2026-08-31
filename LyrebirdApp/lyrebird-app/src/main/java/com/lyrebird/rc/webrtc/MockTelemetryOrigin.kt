package com.lyrebird.rc.webrtc

data class MockTelemetryOrigin(
    val latitude: Double,
    val longitude: Double
) {
    companion object {
        fun fromNullable(latitude: Double?, longitude: Double?): MockTelemetryOrigin? {
            if (latitude == null || longitude == null) return null

            val hasMeaningfulCoordinates = latitude != 0.0 || longitude != 0.0
            return if (hasMeaningfulCoordinates) {
                MockTelemetryOrigin(latitude, longitude)
            } else {
                null
            }
        }
    }
}
