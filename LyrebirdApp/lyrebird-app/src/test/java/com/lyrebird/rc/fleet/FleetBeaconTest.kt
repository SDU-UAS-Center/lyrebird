package com.lyrebird.rc.fleet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The beacon is the entire wire format of the fleet mesh, and every field on it is read by a
 * device that did not send it. These pin the round trip and the rejection rules.
 */
class FleetBeaconTest {

    @Test
    fun `round trips every field`() {
        val original = sampleBeacon()
        val parsed = FleetBeacon.parse(original.toJson().toString())
        assertEquals(original, parsed)
    }

    @Test
    fun `rejects a datagram without the magic`() {
        assertNull(FleetBeacon.parse("""{"v":1,"t":"beacon","id":"abc"}"""))
    }

    @Test
    fun `rejects another protocol version`() {
        val json = sampleBeacon().toJson().put("v", FleetBeacon.PROTOCOL_VERSION + 1)
        assertNull(FleetBeacon.parse(json.toString()))
    }

    @Test
    fun `rejects a beacon with no device id`() {
        val json = sampleBeacon().toJson().put("id", "   ")
        assertNull(FleetBeacon.parse(json.toString()))
    }

    @Test
    fun `rejects malformed json rather than throwing`() {
        assertNull(FleetBeacon.parse("not json at all"))
        assertNull(FleetBeacon.parse(""))
    }

    @Test
    fun `reports the message type of a settings offer without parsing it as a beacon`() {
        val offerBytes = """{"lb":"lyrebird-fleet","v":1,"t":"settings"}""".toByteArray()
        assertEquals(
            FleetSettingsShare.TYPE_SETTINGS_OFFER,
            FleetBeacon.messageType(offerBytes, offerBytes.size)
        )
        assertNull(FleetBeacon.parse(offerBytes, offerBytes.size))
    }

    @Test
    fun `foreign multicast traffic reports no message type`() {
        val foreign = """{"some":"other protocol"}""".toByteArray()
        assertEquals("", FleetBeacon.messageType(foreign, foreign.size))
    }

    @Test
    fun `null island is not a real position`() {
        assertFalse(sampleBeacon().copy(latitudeDeg = 0.0, longitudeDeg = 0.0).hasRealPosition())
        assertTrue(sampleBeacon().hasRealPosition())
    }

    @Test
    fun `home is not real until the latch is set and the coordinates are a place`() {
        assertFalse(sampleBeacon().copy(homeSet = false).hasRealHome())
        assertFalse(
            sampleBeacon().copy(homeLatitudeDeg = 0.0, homeLongitudeDeg = 0.0).hasRealHome()
        )
        assertTrue(sampleBeacon().hasRealHome())
    }

    @Test
    fun `stays inside a single datagram`() {
        // Names and paths are operator-supplied, so the size check uses long ones.
        val wide = sampleBeacon().copy(
            droneName = "a".repeat(64),
            videoPath = "b".repeat(64),
            videoServer = "c".repeat(64),
            flightMode = "d".repeat(32)
        )
        assertTrue("beacon must fit one MTU", wide.toBytes().size < 1_200)
    }

    @Test
    fun `parses a beacon that a newer sender padded with unknown keys`() {
        val json = sampleBeacon().toJson().put("future_field", "ignored")
        assertNotNull(FleetBeacon.parse(json.toString()))
    }

    /**
     * A device that started before its aircraft linked advertises its installation id first and the
     * aircraft serial afterwards; the retire datagram is how peers hear that the old row is gone
     * rather than a second aircraft.
     */
    @Test
    fun `a retire datagram names the identity being withdrawn`() {
        val bytes = FleetBeacon.retireBytes("install-42")
        assertEquals("install-42", FleetBeacon.parseRetire(bytes, bytes.size))
        assertEquals(FleetBeacon.TYPE_RETIRE, FleetBeacon.messageType(bytes, bytes.size))
    }

    @Test
    fun `a beacon is not read as a retire, and junk is neither`() {
        val beacon = sampleBeacon().toBytes()
        assertNull(FleetBeacon.parseRetire(beacon, beacon.size))
        val junk = "{\"lb\":\"something-else\",\"t\":\"retire\",\"id\":\"x\"}".toByteArray()
        assertNull(FleetBeacon.parseRetire(junk, junk.size))
        val noId = "{\"lb\":\"lyrebird-fleet\",\"v\":1,\"t\":\"retire\",\"id\":\"  \"}".toByteArray()
        assertNull(FleetBeacon.parseRetire(noId, noId.size))
    }

    companion object {
        internal fun sampleBeacon(): FleetBeacon = FleetBeacon(
            deviceId = "SERIAL123",
            droneName = "alpha",
            systemId = 142,
            latitudeDeg = 55.4719,
            longitudeDeg = 10.3255,
            altitudeAslM = 74.5,
            altitudeAglM = 50.0,
            velocityNorthMps = 3.0,
            velocityEastMps = -1.5,
            velocityDownMps = 0.25,
            headingDeg = 217.0,
            batteryPercent = 82,
            satelliteCount = 17,
            flying = true,
            flightMode = "GPS_ATTI",
            homeLatitudeDeg = 55.4718,
            homeLongitudeDeg = 10.3250,
            homeSet = true,
            videoPath = "alpha",
            videoServer = "10.42.0.5:8889",
            appUptimeMs = 123_456L,
            sequence = 42L
        )
    }
}
