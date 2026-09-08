package com.lyrebird.rc.mavlink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MavlinkSystemIdTest {

    @Test
    fun fromKeyIsStableAndInRange() {
        val first = MavlinkSystemId.fromKey("biomass-1")
        val second = MavlinkSystemId.fromKey("biomass-1")

        assertEquals(first, second)
        assertTrue(first in MavlinkSystemId.AUTO_MIN..MavlinkSystemId.AUTO_MAX)
    }

    @Test
    fun fromKeyNeverReturnsReservedIds() {
        // Exercise several keys; automatic IDs must stay outside the explicit fleet range.
        listOf("drone_1", "UNKNOWN", "", "alpha", "z").forEach { key ->
            val id = MavlinkSystemId.fromKey(key)
            assertTrue(id in MavlinkSystemId.AUTO_MIN..MavlinkSystemId.AUTO_MAX)
        }
    }

    @Test
    fun fromKeyDistinguishesTypicalNames() {
        // Not a rigorous uniqueness proof (hashing is probabilistic), but a guard against a
        // regression that would collapse distinct names onto the same id.
        assertNotEquals(
            MavlinkSystemId.fromKey("drone_alpha").toLong(),
            MavlinkSystemId.fromKey("drone_beta").toLong()
        )
    }

    @Test
    fun resolveHonoursExplicitOverride() {
        assertEquals(7, MavlinkSystemId.resolve(7, "anything"))
        assertEquals(
            MavlinkSystemId.fromKey("anything"),
            MavlinkSystemId.resolve(MavlinkSystemId.AUTO_MIN, "anything")
        )
        assertEquals(
            MavlinkSystemId.fromKey("anything"),
            MavlinkSystemId.resolve(300, "anything")
        )
    }

    @Test
    fun resolveDerivesWhenAuto() {
        assertEquals(
            MavlinkSystemId.fromKey("key"),
            MavlinkSystemId.resolve(MavlinkSystemId.AUTO, "key")
        )
    }
}
