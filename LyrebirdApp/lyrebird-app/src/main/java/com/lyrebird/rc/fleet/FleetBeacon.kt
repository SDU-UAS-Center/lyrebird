package com.lyrebird.rc.fleet

import org.json.JSONObject

/**
 * What one Lyrebird device tells the rest of the fleet about itself, twice a second.
 *
 * This is the whole wire format of the mesh, and it is deliberately a state beacon rather than a
 * telemetry stream. The aircraft on a field day share one access point and one slice of spectrum
 * with the video publishers, so the mesh gets a fixed, tiny budget: one multicast datagram of a
 * few hundred bytes per device per tick, never a unicast stream per pair. Everything a peer needs
 * to be drawn on a map, ranged, checked for a configuration clash, and watched for silence fits
 * in here, so nothing downstream ever has to ask a question over the network.
 *
 * JSON keys are abbreviated for the same reason. The format is self-describing enough to read in
 * a packet capture, and [PROTOCOL_VERSION] lets a mixed-version fleet reject what it cannot parse
 * rather than misread it.
 */
internal data class FleetBeacon(
    /** Stable identity of the sending device: the aircraft serial where one is known. */
    val deviceId: String,
    val droneName: String,
    val systemId: Int,
    val latitudeDeg: Double,
    val longitudeDeg: Double,
    /** Above mean sea level, the only altitude two aircraft can meaningfully compare. */
    val altitudeAslM: Double,
    /** Above this aircraft's own take-off point, which is what its pilot reads on the deck. */
    val altitudeAglM: Double,
    val velocityNorthMps: Double,
    val velocityEastMps: Double,
    /** Down is positive, as DJI and MAVLink both report it. */
    val velocityDownMps: Double,
    val headingDeg: Double,
    val batteryPercent: Int,
    val satelliteCount: Int,
    val flying: Boolean,
    val flightMode: String,
    val homeLatitudeDeg: Double,
    val homeLongitudeDeg: Double,
    val homeSet: Boolean,
    /** MediaMTX path this aircraft publishes video to. Two aircraft sharing one silently collide. */
    val videoPath: String,
    /** Configured MediaMTX server, blank when the device auto-resolves it from the client address. */
    val videoServer: String,
    /** Milliseconds since this device's app started, so a restart is visible as a counter reset. */
    val appUptimeMs: Long,
    val sequence: Long
) {

    fun hasRealPosition(): Boolean = FleetGeo.isRealPosition(latitudeDeg, longitudeDeg)

    fun hasRealHome(): Boolean = homeSet && FleetGeo.isRealPosition(homeLatitudeDeg, homeLongitudeDeg)

    fun toJson(): JSONObject = JSONObject().apply {
        put(KEY_MAGIC, MAGIC)
        put(KEY_VERSION, PROTOCOL_VERSION)
        put(KEY_TYPE, TYPE_BEACON)
        put(KEY_DEVICE_ID, deviceId)
        put(KEY_NAME, droneName)
        put(KEY_SYSTEM_ID, systemId)
        put(KEY_LATITUDE, latitudeDeg)
        put(KEY_LONGITUDE, longitudeDeg)
        put(KEY_ALTITUDE_ASL, altitudeAslM)
        put(KEY_ALTITUDE_AGL, altitudeAglM)
        put(KEY_VELOCITY_NORTH, velocityNorthMps)
        put(KEY_VELOCITY_EAST, velocityEastMps)
        put(KEY_VELOCITY_DOWN, velocityDownMps)
        put(KEY_HEADING, headingDeg)
        put(KEY_BATTERY, batteryPercent)
        put(KEY_SATELLITES, satelliteCount)
        put(KEY_FLYING, flying)
        put(KEY_FLIGHT_MODE, flightMode)
        put(KEY_HOME_LATITUDE, homeLatitudeDeg)
        put(KEY_HOME_LONGITUDE, homeLongitudeDeg)
        put(KEY_HOME_SET, homeSet)
        put(KEY_VIDEO_PATH, videoPath)
        put(KEY_VIDEO_SERVER, videoServer)
        put(KEY_UPTIME, appUptimeMs)
        put(KEY_SEQUENCE, sequence)
    }

    fun toBytes(): ByteArray = toJson().toString().toByteArray(Charsets.UTF_8)

    companion object {

        /** Rejects anything else that happens to be multicasting on this group and port. */
        const val MAGIC = "lyrebird-fleet"
        const val PROTOCOL_VERSION = 1
        const val TYPE_BEACON = "beacon"

        private const val KEY_MAGIC = "lb"
        private const val KEY_VERSION = "v"
        const val KEY_TYPE = "t"
        private const val KEY_DEVICE_ID = "id"
        private const val KEY_NAME = "n"
        private const val KEY_SYSTEM_ID = "sid"
        private const val KEY_LATITUDE = "lat"
        private const val KEY_LONGITUDE = "lon"
        private const val KEY_ALTITUDE_ASL = "asl"
        private const val KEY_ALTITUDE_AGL = "agl"
        private const val KEY_VELOCITY_NORTH = "vn"
        private const val KEY_VELOCITY_EAST = "ve"
        private const val KEY_VELOCITY_DOWN = "vd"
        private const val KEY_HEADING = "hdg"
        private const val KEY_BATTERY = "bat"
        private const val KEY_SATELLITES = "sat"
        private const val KEY_FLYING = "fly"
        private const val KEY_FLIGHT_MODE = "fm"
        private const val KEY_HOME_LATITUDE = "hla"
        private const val KEY_HOME_LONGITUDE = "hlo"
        private const val KEY_HOME_SET = "hs"
        private const val KEY_VIDEO_PATH = "vp"
        private const val KEY_VIDEO_SERVER = "vs"
        private const val KEY_UPTIME = "up"
        private const val KEY_SEQUENCE = "seq"

        /**
         * Read a datagram, or null when it is not a beacon this build understands.
         *
         * Everything about an inbound beacon is untrusted: it arrives unauthenticated on a
         * multicast group anyone on the access point can write to. Nothing here can do more than
         * populate a read-only roster entry, and a device id that does not parse simply yields
         * null rather than a half-built peer.
         */
        fun parse(json: String): FleetBeacon? = runCatching {
            val obj = JSONObject(json)
            if (obj.optString(KEY_MAGIC) != MAGIC) return null
            if (obj.optInt(KEY_VERSION) != PROTOCOL_VERSION) return null
            if (obj.optString(KEY_TYPE) != TYPE_BEACON) return null
            val deviceId = obj.optString(KEY_DEVICE_ID).trim()
            if (deviceId.isEmpty()) return null
            FleetBeacon(
                deviceId = deviceId,
                droneName = obj.optString(KEY_NAME).trim(),
                systemId = obj.optInt(KEY_SYSTEM_ID, 0),
                latitudeDeg = obj.optDouble(KEY_LATITUDE, 0.0),
                longitudeDeg = obj.optDouble(KEY_LONGITUDE, 0.0),
                altitudeAslM = obj.optDouble(KEY_ALTITUDE_ASL, 0.0),
                altitudeAglM = obj.optDouble(KEY_ALTITUDE_AGL, 0.0),
                velocityNorthMps = obj.optDouble(KEY_VELOCITY_NORTH, 0.0),
                velocityEastMps = obj.optDouble(KEY_VELOCITY_EAST, 0.0),
                velocityDownMps = obj.optDouble(KEY_VELOCITY_DOWN, 0.0),
                headingDeg = obj.optDouble(KEY_HEADING, 0.0),
                batteryPercent = obj.optInt(KEY_BATTERY, -1),
                satelliteCount = obj.optInt(KEY_SATELLITES, -1),
                flying = obj.optBoolean(KEY_FLYING, false),
                flightMode = obj.optString(KEY_FLIGHT_MODE, "UNKNOWN"),
                homeLatitudeDeg = obj.optDouble(KEY_HOME_LATITUDE, 0.0),
                homeLongitudeDeg = obj.optDouble(KEY_HOME_LONGITUDE, 0.0),
                homeSet = obj.optBoolean(KEY_HOME_SET, false),
                videoPath = obj.optString(KEY_VIDEO_PATH).trim(),
                videoServer = obj.optString(KEY_VIDEO_SERVER).trim(),
                appUptimeMs = obj.optLong(KEY_UPTIME, 0L),
                sequence = obj.optLong(KEY_SEQUENCE, 0L)
            )
        }.getOrNull()

        fun parse(data: ByteArray, length: Int): FleetBeacon? =
            parse(String(data, 0, length, Charsets.UTF_8))

        /** The message type of a datagram on the fleet group, or blank when it is not ours. */
        fun messageType(data: ByteArray, length: Int): String = runCatching {
            val obj = JSONObject(String(data, 0, length, Charsets.UTF_8))
            if (obj.optString(KEY_MAGIC) != MAGIC) "" else obj.optString(KEY_TYPE)
        }.getOrDefault("")
    }
}
