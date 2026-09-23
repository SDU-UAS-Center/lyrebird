package com.lyrebird.rc.fleet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Each conflict here is a field-day failure that no single device can see. The tests pin both the
 * detection and the cases that must stay quiet, since a pre-flight check that cries wolf is worse
 * than none at all.
 */
class FleetConflictsTest {

    private val own = FleetBeaconTest.sampleBeacon().copy(
        deviceId = "OWN",
        droneName = "alpha",
        systemId = 142,
        videoPath = "alpha"
    )

    @Test
    fun `a lone aircraft has no conflicts`() {
        assertTrue(FleetConflicts.detect(own, emptyList()).isEmpty())
    }

    /** A distinct peer in every respect the detector looks at, including its launch point. */
    private fun cleanPeer() = own.copy(
        deviceId = "PEER",
        droneName = "bravo",
        systemId = 143,
        videoPath = "bravo",
        homeLatitudeDeg = own.homeLatitudeDeg + 0.001
    )

    @Test
    fun `a correctly configured fleet has no conflicts`() {
        assertTrue(FleetConflicts.detect(own, listOf(cleanPeer())).isEmpty())
    }

    @Test
    fun `aircraft launched from one mat report only an informational overlap`() {
        // The ordinary way a field day starts: same launch point, everything else distinct. It is
        // worth listing on the pre-flight page and must never reach the deck as a warning.
        val peer = own.copy(deviceId = "PEER", droneName = "bravo", systemId = 143, videoPath = "bravo")
        val conflicts = FleetConflicts.detect(own, listOf(peer))
        assertEquals(listOf(ConflictKind.HOME_POINT_OVERLAP), conflicts.map { it.kind })
        assertEquals(ConflictSeverity.INFO, conflicts.single().severity)
    }

    @Test
    fun `finds two aircraft claiming one mavlink id`() {
        val peer = own.copy(deviceId = "PEER", droneName = "bravo", systemId = 142, videoPath = "bravo")
        val conflicts = FleetConflicts.detect(own, listOf(peer))
        val clash = conflicts.single { it.kind == ConflictKind.DUPLICATE_SYSTEM_ID }
        assertEquals(ConflictSeverity.CRITICAL, clash.severity)
        assertTrue(clash.summary.contains("142"))
        assertEquals(listOf("alpha", "bravo"), clash.involved)
    }

    @Test
    fun `finds a duplicate drone name regardless of case`() {
        val peer = own.copy(deviceId = "PEER", droneName = "ALPHA", systemId = 143, videoPath = "x")
        val conflicts = FleetConflicts.detect(own, listOf(peer))
        assertTrue(conflicts.any { it.kind == ConflictKind.DUPLICATE_DRONE_NAME })
    }

    @Test
    fun `finds a video path clash that would hide one aircraft from the dashboard`() {
        val peer = own.copy(deviceId = "PEER", droneName = "bravo", systemId = 143, videoPath = "alpha")
        val conflicts = FleetConflicts.detect(own, listOf(peer))
        val clash = conflicts.single { it.kind == ConflictKind.DUPLICATE_VIDEO_PATH }
        assertTrue(clash.summary.contains("alpha"))
    }

    @Test
    fun `finds a fleet split across two video servers`() {
        val peer = own.copy(
            deviceId = "PEER",
            droneName = "bravo",
            systemId = 143,
            videoPath = "bravo",
            videoServer = "192.168.1.9:8889"
        )
        val conflicts = FleetConflicts.detect(own, listOf(peer))
        assertTrue(conflicts.any { it.kind == ConflictKind.MISMATCHED_VIDEO_SERVER })
    }

    @Test
    fun `devices resolving the server automatically never disagree`() {
        val blankOwn = own.copy(videoServer = "")
        val peer = blankOwn.copy(deviceId = "PEER", droneName = "bravo", systemId = 143, videoPath = "bravo")
        val conflicts = FleetConflicts.detect(blankOwn, listOf(peer))
        assertTrue(conflicts.none { it.kind == ConflictKind.MISMATCHED_VIDEO_SERVER })
    }

    @Test
    fun `one configured server and one automatic is not a disagreement`() {
        val peer = own.copy(
            deviceId = "PEER",
            droneName = "bravo",
            systemId = 143,
            videoPath = "bravo",
            videoServer = ""
        )
        val conflicts = FleetConflicts.detect(own, listOf(peer))
        assertTrue(conflicts.none { it.kind == ConflictKind.MISMATCHED_VIDEO_SERVER })
    }

    @Test
    fun `finds home points close enough for return paths to overlap`() {
        val peer = own.copy(
            deviceId = "PEER",
            droneName = "bravo",
            systemId = 143,
            videoPath = "bravo",
            // About eleven metres north of the shared home point.
            homeLatitudeDeg = own.homeLatitudeDeg + 0.0001
        )
        val conflicts = FleetConflicts.detect(own, listOf(peer))
        assertTrue(conflicts.any { it.kind == ConflictKind.HOME_POINT_OVERLAP })
    }

    @Test
    fun `home points a sensible distance apart are not reported`() {
        val peer = own.copy(
            deviceId = "PEER",
            droneName = "bravo",
            systemId = 143,
            videoPath = "bravo",
            homeLatitudeDeg = own.homeLatitudeDeg + 0.001
        )
        val conflicts = FleetConflicts.detect(own, listOf(peer))
        assertTrue(conflicts.none { it.kind == ConflictKind.HOME_POINT_OVERLAP })
    }

    @Test
    fun `a peer echoing this device's own id is not compared against itself`() {
        val echo = own.copy()
        assertTrue(FleetConflicts.detect(own, listOf(echo)).isEmpty())
    }

    @Test
    fun `the most severe conflict is reported first`() {
        val peer = own.copy(
            deviceId = "PEER",
            droneName = "alpha",
            systemId = 142,
            videoPath = "alpha",
            videoServer = "192.168.1.9:8889"
        )
        val conflicts = FleetConflicts.detect(own, listOf(peer))
        assertEquals(ConflictSeverity.CRITICAL, conflicts.first().severity)
    }

    @Test
    fun `an unset mavlink id is not treated as a shared one`() {
        val zeroOwn = own.copy(systemId = 0)
        val peer = zeroOwn.copy(deviceId = "PEER", droneName = "bravo", videoPath = "bravo")
        val conflicts = FleetConflicts.detect(zeroOwn, listOf(peer))
        assertTrue(conflicts.none { it.kind == ConflictKind.DUPLICATE_SYSTEM_ID })
    }
}
