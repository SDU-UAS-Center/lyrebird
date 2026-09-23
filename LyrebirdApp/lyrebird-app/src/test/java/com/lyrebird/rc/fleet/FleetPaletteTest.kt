package com.lyrebird.rc.fleet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Identity colours: one per aircraft, the same one everywhere, and never two alike on one screen.
 *
 * The colour is what a pilot matches between a row on the strip and a chevron on the map, so the
 * two properties that matter are that it does not move under them and that no two aircraft share
 * it. Both are checkable without a device, which is why the assignment is not in the view.
 */
class FleetPaletteTest {
    private fun member(
        name: String,
        deviceId: String = name.uppercase(),
    ) = FleetPalette.Member(deviceId = deviceId, name = name)

    @Test
    fun `every member of a fleet gets its own colour`() {
        val members = (0 until FleetPalette.capacity).map { member("device-$it") }
        val assigned = FleetPalette.assign(members.first(), members.drop(1))
        assertEquals(members.size, assigned.size)
        assertEquals(
            "two aircraft on the same screen are never drawn alike",
            members.size,
            assigned.values.toSet().size,
        )
    }

    @Test
    fun `this aircraft keeps its colour whatever else is on the list`() {
        val alone = FleetPalette.assign(member("mini1"), listOf(member("mini4")))
        val crowded =
            FleetPalette.assign(
                member("mini1"),
                listOf(member("mini4"), member("mini5"), member("mini6"), member("mini7")),
            )
        assertEquals(alone["MINI1"], crowded["MINI1"])
    }

    /**
     * The complaint this exists for: a peer advertises an installation id until its aircraft links
     * and its serial afterwards, so a colour keyed on the device id changed under the operator mid
     * session, on a strip where nothing else had moved.
     */
    @Test
    fun `an aircraft keeps its colour when it changes device id mid-session`() {
        val before = FleetPalette.assign(member("mini1", "rc-1"), listOf(member("mini4", "rc-9f3a")))
        val after =
            FleetPalette.assign(
                member("mini1", "rc-1"),
                listOf(member("mini4", "1581F6Z9C238Q0033XR2")),
            )
        assertEquals(before["rc-9f3a"], after["1581F6Z9C238Q0033XR2"])
        assertEquals(before["rc-1"], after["rc-1"])
    }

    /**
     * The case the hashing exists for: two RCs looking at the same pair of aircraft, neither of
     * them knowing what the other's roster looks like, agreeing on what colour the third one is.
     */
    @Test
    fun `two RCs with different rosters agree on a third aircraft's colour`() {
        val names = namesInDistinctBuckets(3)
        val onFirst = FleetPalette.assign(member(names[0]), listOf(member(names[1]), member(names[2])))
        val onSecond = FleetPalette.assign(member(names[1]), listOf(member(names[0]), member(names[2])))
        assertEquals(onFirst[names[0].uppercase()], onSecond[names[0].uppercase()])
        assertEquals(onFirst[names[1].uppercase()], onSecond[names[1].uppercase()])
        assertEquals(onFirst[names[2].uppercase()], onSecond[names[2].uppercase()])
    }

    @Test
    fun `a nameless device still gets a colour of its own`() {
        val assigned = FleetPalette.assign(member("", "rc-1"), listOf(member("", "rc-2")))
        assertEquals(2, assigned.values.toSet().size)
    }

    @Test
    fun `a fleet larger than the palette repeats colours rather than blanking them`() {
        val members = (0 until FleetPalette.capacity * 2).map { member("device-$it") }
        val assigned = FleetPalette.assign(members.first(), members.drop(1))
        assertEquals(members.size, assigned.size)
        assertEquals(
            "every colour in the palette is in use before any of them is shared",
            FleetPalette.capacity,
            assigned.values.toSet().size,
        )
        assertFalse(assigned.values.any { it == FleetPalette.UNKNOWN })
    }

    @Test
    fun `dimming keeps the hue and changes only the opacity`() {
        val identity = 0xFF4FC3F7.toInt()
        val dim = FleetPalette.dimmed(identity, 110)
        assertEquals(110, (dim ushr 24) and 0xFF)
        assertEquals(identity and 0xFFFFFF, dim and 0xFFFFFF)
    }

    @Test
    fun `the unknown fallback is not one of the identity colours`() {
        val members = (0 until FleetPalette.capacity).map { member("device-$it") }
        val assigned = FleetPalette.assign(members.first(), members.drop(1))
        assertTrue(assigned.values.none { it == FleetPalette.UNKNOWN })
        assertNotEquals(FleetPalette.UNKNOWN, assigned.values.first())
    }

    /** Names that hash into different palette slots, so each is certain to keep its own colour. */
    private fun namesInDistinctBuckets(count: Int): List<String> {
        val byBucket = mutableMapOf<Int, String>()
        var n = 0
        while (byBucket.size < count) {
            val name = "device-$n"
            byBucket.putIfAbsent(Math.floorMod(name.hashCode(), FleetPalette.capacity), name)
            n++
        }
        return byBucket.values.toList()
    }
}
