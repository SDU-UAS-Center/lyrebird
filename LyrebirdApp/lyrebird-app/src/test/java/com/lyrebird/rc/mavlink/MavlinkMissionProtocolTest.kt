package com.lyrebird.rc.mavlink

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mission upload handshake, exercised against frames built the way a ground station builds
 * them.
 *
 * These tests were written after checking the same frames against pymavlink, which is why they
 * assert on decoded field values rather than on byte blobs: a golden-bytes test would pass just as
 * happily with a field in the wrong place, and a field in the wrong place is exactly the failure
 * this package has already produced once (EXTENDED_SYS_STATE shipped at POWER_STATUS's id — valid
 * MAVLink, correct CRC, silently ignored by every ground station).
 */
class MavlinkMissionProtocolTest {

    private val store = MavlinkMissionStore()

    // -- upload state machine ----------------------------------------------------------------

    @Test
    fun uploadRequestsEveryItemInOrderThenCommits() {
        assertNull(store.beginUpload(3, MavlinkMissionStore.MISSION_TYPE_MISSION))
        for (seq in 0 until 3) {
            assertEquals("item $seq requested", seq, store.nextRequestIndex())
            assertNull(store.acceptItem(waypoint(seq)))
        }
        assertNull("nothing left to request", store.nextRequestIndex())
        assertTrue(store.uploadComplete())

        store.commitUpload()
        assertEquals(3, store.count())
        assertEquals(MissionState.NOT_STARTED, store.missionState())
    }

    @Test
    fun anItemOutOfOrderIsRefusedRatherThanReordered() {
        store.beginUpload(2, MavlinkMissionStore.MISSION_TYPE_MISSION)
        assertEquals(MissionResult.INVALID_SEQUENCE, store.acceptItem(waypoint(1)))
    }

    @Test
    fun commandsOutsideTheNavigationSubsetAreRefused() {
        store.beginUpload(1, MavlinkMissionStore.MISSION_TYPE_MISSION)
        // MAV_CMD_NAV_LOITER_TIME: a real mission command, deliberately not implemented.
        assertEquals(MissionResult.UNSUPPORTED, store.acceptItem(waypoint(0).copy(command = 19)))
    }

    @Test
    fun geofenceAndRallyUploadsAreRefusedOutright() {
        // DJI owns these through FlySafe. Accepting an upload we would then ignore is the silent
        // drop that makes a plan upload dangerous, so it is refused at the count.
        assertEquals(MissionResult.UNSUPPORTED, store.beginUpload(2, missionType = 1))
        assertEquals(MissionResult.UNSUPPORTED, store.beginUpload(2, missionType = 2))
    }

    @Test
    fun anImplausibleCountIsRefusedWithNoSpace() {
        assertEquals(
            MissionResult.NO_SPACE,
            store.beginUpload(MavlinkMissionStore.MAX_ITEMS + 1, MavlinkMissionStore.MISSION_TYPE_MISSION)
        )
    }

    @Test
    fun aZeroItemUploadClearsThePlan() {
        uploadPlan(2)
        assertEquals(2, store.count())

        assertNull(store.beginUpload(0, MavlinkMissionStore.MISSION_TYPE_MISSION))
        assertEquals(0, store.count())
        assertEquals(MissionState.NO_MISSION, store.missionState())
        assertNull("a zero-item upload has nothing to request", store.nextRequestIndex())
    }

    @Test
    fun aFailedUploadLeavesThePreviousPlanIntact() {
        uploadPlan(2)
        val planId = store.currentPlanId()

        store.beginUpload(3, MavlinkMissionStore.MISSION_TYPE_MISSION)
        store.acceptItem(waypoint(0))
        store.abortUpload()

        assertEquals("previous plan still stored", 2, store.count())
        assertEquals("and still the same plan", planId, store.currentPlanId())
    }

    @Test
    fun thePlanIdChangesWheneverThePlanDoes() {
        val first = store.currentPlanId()
        uploadPlan(1)
        val second = store.currentPlanId()
        uploadPlan(1)

        assertTrue("upload changed the id", second != first)
        assertTrue("second upload changed it again", store.currentPlanId() != second)
    }

    // -- item semantics ----------------------------------------------------------------------

    @Test
    fun param4NaNIsTheNoseForwardHeadingMode() {
        assertTrue(waypoint(0, yaw = Float.NaN).noseForward)
        assertFalse(waypoint(0, yaw = 90f).noseForward)
    }

    @Test
    fun speedIsReadOnlyFromASpeedChangeAndOnlyWhenPositive() {
        val speedChange = waypoint(0).copy(command = Mav.CMD_DO_CHANGE_SPEED, param2 = 4.5f)
        assertEquals(4.5, speedChange.speedMps!!, 1e-6)
        assertFalse(speedChange.isWaypoint)

        // A waypoint's param2 is acceptance radius, not a speed, and must never be read as one.
        assertNull(waypoint(0).copy(param2 = 4.5f).speedMps)
        // -1 is how DO_CHANGE_SPEED says "no change".
        assertNull(speedChange.copy(param2 = -1f).speedMps)
    }

    // -- wire format -------------------------------------------------------------------------

    @Test
    fun anUploadedItemComesBackUnchangedOnTheWire() {
        val item = waypoint(2, lat = 46.519, lon = 6.567, alt = 35.0, yaw = 90f)
        val payload = MavlinkMessages.missionItemInt(item, targetSystem = 255, targetComponent = 190, isCurrent = false)
        val fields = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)

        assertEquals("param4 holds the heading", 90f, fields.getFloat(12), 1e-6f)
        assertEquals("lat as degE7", 465190000, fields.getInt(16))
        assertEquals("lon as degE7", 65670000, fields.getInt(20))
        assertEquals("alt in metres", 35f, fields.getFloat(24), 1e-6f)
        assertEquals("seq", 2, fields.getShort(28).toInt())
        assertEquals("command", Mav.CMD_NAV_WAYPOINT, fields.getShort(30).toInt())
        assertEquals(
            "frame is GLOBAL_RELATIVE_ALT_INT, so altitudes are above home",
            6,
            payload[34].toInt()
        )
    }

    @Test
    fun noseForwardSurvivesEncodingAsNaNRatherThanZero() {
        val payload = MavlinkMessages.missionItemInt(
            waypoint(0, yaw = Float.NaN), targetSystem = 255, targetComponent = 190, isCurrent = true
        )
        val param4 = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN).getFloat(12)
        assertTrue("a zero here would silently turn nose-forward into hold-north", param4.isNaN())
    }

    @Test
    fun missionCurrentReportsThePlanAndWhereWeAreInIt() {
        uploadPlan(4)
        store.setCurrent(1)
        val payload = MavlinkMessages.missionCurrent(
            store.currentIndex(), store.count(), store.missionState(), store.currentPlanId()
        )
        val fields = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)

        assertEquals("seq", 1, fields.getShort(0).toInt())
        assertEquals("total", 4, fields.getShort(2).toInt())
        assertEquals("state", MissionState.ACTIVE, payload[4].toInt())
        assertEquals("plan id", store.currentPlanId(), fields.getInt(6))
    }

    @Test
    fun theCurrentIndexStaysInsideThePlan() {
        uploadPlan(2)
        store.setCurrent(99)
        assertEquals("clamped to the last item", 1, store.currentIndex())
    }

    // -- helpers -----------------------------------------------------------------------------

    private fun uploadPlan(items: Int) {
        store.beginUpload(items, MavlinkMissionStore.MISSION_TYPE_MISSION)
        for (seq in 0 until items) store.acceptItem(waypoint(seq))
        store.commitUpload()
    }

    private fun waypoint(
        seq: Int,
        lat: Double = 46.518,
        lon: Double = 6.566,
        alt: Double = 30.0,
        yaw: Float = Float.NaN
    ) = MissionItem(
        seq = seq,
        command = Mav.CMD_NAV_WAYPOINT,
        param1 = 0f,
        param2 = 0f,
        param3 = 0f,
        param4 = yaw,
        latitudeDeg = lat,
        longitudeDeg = lon,
        altitudeM = alt,
        autocontinue = true
    )
}
