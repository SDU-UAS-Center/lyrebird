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
        assertTrue(first in MavlinkSystemId.MIN..MavlinkSystemId.MAX)
    }

    @Test
    fun fromKeyNeverReturnsReservedIds() {
        // Exercise several keys; 0 and 255 are reserved and must never be produced.
        listOf("drone_1", "UNKNOWN", "", "alpha", "z").forEach { key ->
            val id = MavlinkSystemId.fromKey(key)
            assertTrue(id in MavlinkSystemId.MIN..MavlinkSystemId.MAX)
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
        assertEquals(MavlinkSystemId.MIN, MavlinkSystemId.resolve(-1, "anything"))
        assertEquals(MavlinkSystemId.MAX, MavlinkSystemId.resolve(300, "anything"))
    }

    @Test
    fun resolveDerivesWhenAuto() {
        assertEquals(
            MavlinkSystemId.fromKey("key"),
            MavlinkSystemId.resolve(MavlinkSystemId.AUTO, "key")
        )
    }
}
