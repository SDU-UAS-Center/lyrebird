package com.lyrebird.rc.fleet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The retire announcement's schedule: it repeats, it stops, and a second change takes over.
 *
 * This is the sender half of "a retired identity leaves at once instead of ageing out" (see
 * [FleetRosterTest]). Sending it once is what let a restarted peer keep the old row for the whole
 * forget timeout — showing one aircraft twice, one of the two a lost ghost, and reporting a
 * conflict about a device that was not in conflict with anything.
 */
class FleetRetireAnnouncerTest {

    @Test
    fun `nothing to announce before an identity change`() {
        val announcer = FleetRetireAnnouncer(repeatTicks = 3)
        assertNull(announcer.next())
        assertNull(announcer.name)
    }

    @Test
    fun `an announced identity repeats for the configured number of ticks and then stops`() {
        val announcer = FleetRetireAnnouncer(repeatTicks = 3)
        announcer.announce("install-42")

        assertEquals("install-42", announcer.next())
        assertEquals("install-42", announcer.next())
        assertEquals("install-42", announcer.next())
        assertNull("the announcements stop on their own", announcer.next())
        assertNull(announcer.next())
        assertNull(announcer.name)
    }

    @Test
    fun `a second change replaces the first, with a full count of its own`() {
        val announcer = FleetRetireAnnouncer(repeatTicks = 2)
        announcer.announce("install-42")
        assertEquals("install-42", announcer.next())

        announcer.announce("install-77")
        assertEquals("install-77", announcer.next())
        assertEquals("install-77", announcer.next())
        assertNull(announcer.next())
    }

    @Test
    fun `an empty identity is not an announcement`() {
        val announcer = FleetRetireAnnouncer(repeatTicks = 2)
        announcer.announce("")
        assertNull(announcer.next())
        assertNull(announcer.name)
    }
}
