package com.lyrebird.rc.fleet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The advisory is the one part of the mesh a pilot may act on in the air, so its arithmetic and
 * especially its silences are pinned here: when it must not fire matters as much as when it must.
 */
class TrafficAdvisoryTest {

    private val baseLatitude = 55.4719
    private val baseLongitude = 10.3255
    private val baseAltitude = 100.0

    @Test
    fun `ranges a stationary peer due east`() {
        val solution = solve(peerAt(metresEast = 100.0, metresNorth = 0.0))
        assertNotNull(solution)
        assertEquals(100.0, solution!!.horizontalDistanceM, 1.0)
        assertEquals(90.0, solution.bearingDeg, 1.0)
        assertEquals("E", FleetGeo.compassPoint(solution.bearingDeg))
    }

    @Test
    fun `reports vertical separation signed with the peer above as positive`() {
        val above = solve(peerAt(metresEast = 500.0, altitudeAslM = baseAltitude + 40.0))
        assertEquals(40.0, above!!.verticalSeparationM, 0.1)
        val below = solve(peerAt(metresEast = 500.0, altitudeAslM = baseAltitude - 25.0))
        assertEquals(-25.0, below!!.verticalSeparationM, 0.1)
    }

    @Test
    fun `two aircraft on the ground raise nothing however close they are`() {
        val solution = solve(
            peer = peerAt(metresEast = 3.0, flying = false),
            ownFlying = false
        )
        assertEquals(AdvisoryLevel.NONE, solution!!.level)
    }

    @Test
    fun `an airborne peer inside the warning radius is a warning`() {
        val solution = solve(peerAt(metresEast = 20.0, flying = true))
        assertEquals(AdvisoryLevel.WARNING, solution!!.level)
    }

    @Test
    fun `a distant peer holding station is only an advisory`() {
        val solution = solve(peerAt(metresEast = 300.0, flying = true))
        assertEquals(AdvisoryLevel.ADVISORY, solution!!.level)
    }

    @Test
    fun `a far peer holding station beyond the advisory radius is silent`() {
        val solution = solve(peerAt(metresEast = 1_500.0, flying = true))
        assertEquals(AdvisoryLevel.NONE, solution!!.level)
    }

    @Test
    fun `a distant peer closing head-on is a warning long before it is close`() {
        // 400 m east, tracking west at 15 m/s while this aircraft tracks east at 10 m/s:
        // closing at 25 m/s, so closest approach is about 16 seconds out and near zero.
        val peer = peerAt(metresEast = 400.0, flying = true, velocityEastMps = -15.0)
        val solution = solve(peer, ownVelocityEastMps = 10.0, ownFlying = true)
        assertEquals(AdvisoryLevel.WARNING, solution!!.level)
        assertEquals(16.0, solution.timeToClosestApproachS!!, 1.0)
        assertTrue(solution.closingSpeedMps > 24.0)
    }

    @Test
    fun `a peer at the same range opening is not a hazard`() {
        val peer = peerAt(metresEast = 400.0, flying = true, velocityEastMps = 15.0)
        val solution = solve(peer, ownVelocityEastMps = -10.0, ownFlying = true)
        assertNull("diverging tracks have no closest approach ahead", solution!!.timeToClosestApproachS)
        assertEquals(AdvisoryLevel.ADVISORY, solution.level)
    }

    @Test
    fun `tracks that converge horizontally but stay vertically apart do not warn`() {
        // Same convergence as the head-on case, but 200 m of height between them throughout.
        val peer = peerAt(
            metresEast = 400.0,
            altitudeAslM = baseAltitude + 200.0,
            flying = true,
            velocityEastMps = -15.0
        )
        val solution = solve(peer, ownVelocityEastMps = 10.0, ownFlying = true)
        assertTrue(
            "vertical separation must keep this below a warning",
            solution!!.level.ordinal < AdvisoryLevel.WARNING.ordinal
        )
    }

    @Test
    fun `a peer with no gps fix yields no solution rather than a zero range`() {
        val peer = FleetBeaconTest.sampleBeacon().copy(latitudeDeg = 0.0, longitudeDeg = 0.0)
        assertNull(solve(peer))
    }

    @Test
    fun `no solution while this aircraft itself has no fix`() {
        val solution = TrafficAdvisory.solve(
            ownLatitudeDeg = 0.0,
            ownLongitudeDeg = 0.0,
            ownAltitudeAslM = 0.0,
            ownVelocityNorthMps = 0.0,
            ownVelocityEastMps = 0.0,
            ownVelocityDownMps = 0.0,
            ownFlying = true,
            peer = peerAt(metresEast = 50.0, flying = true)
        )
        assertNull(solution)
    }

    @Test
    fun `climbing and descending aircraft converge vertically`() {
        // Level in plan, 60 m apart vertically, closing at 6 m/s: ten seconds to co-altitude.
        val peer = peerAt(
            metresEast = 15.0,
            altitudeAslM = baseAltitude + 60.0,
            flying = true,
            velocityDownMps = 3.0
        )
        val solution = solve(peer, ownVelocityDownMps = -3.0, ownFlying = true)
        assertEquals(10.0, solution!!.timeToClosestApproachS!!, 0.5)
        assertEquals(AdvisoryLevel.WARNING, solution.level)
    }

    private fun solve(
        peer: FleetBeacon,
        ownVelocityNorthMps: Double = 0.0,
        ownVelocityEastMps: Double = 0.0,
        ownVelocityDownMps: Double = 0.0,
        ownFlying: Boolean = true
    ): TrafficSolution? = TrafficAdvisory.solve(
        ownLatitudeDeg = baseLatitude,
        ownLongitudeDeg = baseLongitude,
        ownAltitudeAslM = baseAltitude,
        ownVelocityNorthMps = ownVelocityNorthMps,
        ownVelocityEastMps = ownVelocityEastMps,
        ownVelocityDownMps = ownVelocityDownMps,
        ownFlying = ownFlying,
        peer = peer
    )

    /** Places a peer a given number of metres from the reference point. */
    private fun peerAt(
        metresEast: Double = 0.0,
        metresNorth: Double = 0.0,
        altitudeAslM: Double = baseAltitude,
        flying: Boolean = false,
        velocityNorthMps: Double = 0.0,
        velocityEastMps: Double = 0.0,
        velocityDownMps: Double = 0.0
    ): FleetBeacon {
        val latitude = baseLatitude + Math.toDegrees(metresNorth / FleetGeo.EARTH_RADIUS_M)
        val longitude = baseLongitude + Math.toDegrees(
            metresEast / (FleetGeo.EARTH_RADIUS_M * Math.cos(Math.toRadians(baseLatitude)))
        )
        return FleetBeaconTest.sampleBeacon().copy(
            deviceId = "PEER",
            droneName = "bravo",
            latitudeDeg = latitude,
            longitudeDeg = longitude,
            altitudeAslM = altitudeAslM,
            flying = flying,
            velocityNorthMps = velocityNorthMps,
            velocityEastMps = velocityEastMps,
            velocityDownMps = velocityDownMps
        )
    }
}
