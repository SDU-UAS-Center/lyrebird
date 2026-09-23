package com.lyrebird.rc.controller

import com.lyrebird.rc.controller.ControlAuthority.Authority
import com.lyrebird.rc.controller.ControlAuthority.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule under test: a restart is not a release, a takeover belongs to one airframe, and only
 * the Safety Computer gives control back.
 */
class AuthorityLatchTest {

    /** Stands in for the preferences file: it outlives the latch, which is the whole point. */
    private class FakeStore : AuthorityLatch.LatchStore {
        val records = mutableMapOf<String, Authority>()
        var unreadable = false

        override fun read(aircraftSerial: String): Authority? =
            if (unreadable) Authority.SAFETY else records[aircraftSerial]

        override fun write(aircraftSerial: String, authority: Authority) {
            records[aircraftSerial] = authority
        }
    }

    private fun latch(store: FakeStore, serial: String = "1581F5FKD2376000ABCD") =
        AuthorityLatch(store) { serial }

    @Test
    fun `the pilot starts with control`() {
        val latch = latch(FakeStore())
        assertEquals(Authority.PILOT, latch.active)
        assertEquals(AuthorityLatch.Decision.ALLOWED, latch.authorize(Source.PILOT))
    }

    @Test
    fun `a safety command takes control and the pilot is refused from then on`() {
        val store = FakeStore()
        val latch = latch(store)

        assertEquals(AuthorityLatch.Decision.ALLOWED_TAKEOVER, latch.authorize(Source.SAFETY))
        assertEquals(Authority.SAFETY, latch.active)
        assertEquals(AuthorityLatch.Decision.REJECTED, latch.authorize(Source.PILOT))
        assertEquals(Authority.SAFETY, store.records["1581F5FKD2376000ABCD"])
    }

    @Test
    fun `the takeover is still in force after a restart`() {
        val store = FakeStore()
        latch(store).authorize(Source.SAFETY)

        // A new process: same storage, same airframe, nothing else carried over.
        val restarted = latch(store)
        assertEquals("a restart must not read as the Pilot holding control", Authority.PILOT, restarted.active)
        restarted.restore()

        assertEquals(Authority.SAFETY, restarted.active)
        assertEquals(AuthorityLatch.Decision.REJECTED, restarted.authorize(Source.PILOT))
    }

    @Test
    fun `a release is still in force after a restart`() {
        val store = FakeStore()
        val before = latch(store)
        before.authorize(Source.SAFETY)
        assertTrue(before.release(Source.SAFETY))

        val restarted = latch(store)
        restarted.restore()

        assertEquals(Authority.PILOT, restarted.active)
        assertEquals(AuthorityLatch.Decision.ALLOWED, restarted.authorize(Source.PILOT))
    }

    @Test
    fun `another airframe is not locked out by a takeover on a different one`() {
        val store = FakeStore()
        latch(store, serial = "AIRFRAME-A").authorize(Source.SAFETY)

        val other = latch(store, serial = "AIRFRAME-B")
        other.restore()

        assertEquals(Authority.PILOT, other.active)
        assertEquals(AuthorityLatch.Decision.ALLOWED, other.authorize(Source.PILOT))
        assertEquals(Authority.SAFETY, store.records["AIRFRAME-A"])
    }

    @Test
    fun `only the safety computer can release`() {
        val store = FakeStore()
        val latch = latch(store)
        latch.authorize(Source.SAFETY)

        assertFalse("the Pilot must not be able to release itself", latch.release(Source.PILOT))
        assertEquals(Authority.SAFETY, latch.active)

        assertTrue(latch.release(Source.SAFETY))
        assertEquals(Authority.PILOT, latch.active)
        assertEquals(Authority.PILOT, store.records["1581F5FKD2376000ABCD"])
    }

    @Test
    fun `there is no timeout`() {
        val latch = latch(FakeStore())
        latch.authorize(Source.SAFETY)

        // The Safety Computer going quiet is not a release: commands from the Pilot stay refused
        // however much time passes.
        repeat(1000) { latch.authorize(Source.PILOT) }

        assertEquals(Authority.SAFETY, latch.active)
        assertEquals(AuthorityLatch.Decision.REJECTED, latch.authorize(Source.PILOT))
    }

    @Test
    fun `restoring never lowers authority`() {
        val store = FakeStore()
        val latch = latch(store)
        latch.authorize(Source.SAFETY)

        // Stored state says the Pilot holds it — because the record predates a takeover that has
        // already happened in this process. That must not release it.
        store.records["1581F5FKD2376000ABCD"] = Authority.PILOT
        latch.restore()

        assertEquals(Authority.SAFETY, latch.active)
    }

    @Test
    fun `a takeover taken before the airframe was known is carried onto it`() {
        val store = FakeStore()
        var serial = AuthorityLatch.UNKNOWN_SERIAL
        val latch = AuthorityLatch(store) { serial }

        latch.authorize(Source.SAFETY)
        assertEquals(Authority.SAFETY, store.records[AuthorityLatch.UNKNOWN_SERIAL])

        // The aircraft connects and introduces itself: the takeover is already in force here, so
        // it now belongs to the airframe being flown rather than to the placeholder.
        serial = "1581F5FKD2376000ABCD"
        latch.restore()

        assertEquals(Authority.SAFETY, latch.active)
        assertEquals(
            "the airframe the Safety Computer took control of must carry the latch",
            Authority.SAFETY,
            store.records["1581F5FKD2376000ABCD"],
        )
    }

    @Test
    fun `an unreadable record fails towards safety`() {
        val store = FakeStore().apply { unreadable = true }
        val latch = latch(store)
        latch.restore()

        assertEquals(Authority.SAFETY, latch.active)
    }

    @Test
    fun `without storage the latch still works and holds nothing`() {
        val latch = AuthorityLatch()
        assertEquals(AuthorityLatch.Decision.ALLOWED_TAKEOVER, latch.authorize(Source.SAFETY))
        assertEquals(Authority.SAFETY, latch.active)
        latch.restore()
        assertEquals(Authority.SAFETY, latch.active)
    }

    @Test
    fun `nothing is recorded for an airframe that never took over`() {
        val store = FakeStore()
        latch(store).authorize(Source.PILOT)
        assertNull(store.records["1581F5FKD2376000ABCD"])
        assertTrue(store.records.isEmpty())
    }
}
