package com.lyrebird.rc.server

import java.net.InetSocketAddress
import java.net.ServerSocket
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The lease exists to make a race into a decision, so these tests are about exactly that: a second
 * claimant is refused, and the refusal ends when the holder lets go.
 */
class SessionLeaseTest {

    /** A port nothing is listening on, without using the reserved constant. */
    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    @Test
    fun `acquire takes the lease`() {
        val lease = SessionLease(freePort())
        assertTrue(lease.acquire())
        assertTrue(lease.isHeld)
        lease.release()
    }

    @Test
    fun `a second claimant is refused while the lease is held`() {
        val port = freePort()
        val holder = SessionLease(port)
        val rival = SessionLease(port)

        assertTrue(holder.acquire())
        assertFalse(rival.acquire())
        assertFalse(rival.isHeld)
        assertTrue(holder.isHeld)

        holder.release()
        rival.release()
    }

    @Test
    fun `the lease can be taken again once released`() {
        val port = freePort()
        val first = SessionLease(port)
        val second = SessionLease(port)

        assertTrue(first.acquire())
        assertFalse(second.acquire())

        first.release()
        assertTrue(second.acquire())
        second.release()
    }

    @Test
    fun `acquiring again while held is not a second claim`() {
        val lease = SessionLease(freePort())
        assertTrue(lease.acquire())
        assertTrue(lease.acquire())
        lease.release()
        assertFalse(lease.isHeld)
    }

    @Test
    fun `releasing without holding is harmless`() {
        val lease = SessionLease(freePort())
        lease.release()
        assertFalse(lease.isHeld)
        assertTrue(lease.acquire())
        lease.release()
    }

    @Test
    fun `the lease port is refused to a plain listener`() {
        // The lease is only meaningful if it really holds the port: a test that passed while a
        // second binder could still take it would be asserting nothing.
        val port = freePort()
        val lease = SessionLease(port)
        assertTrue(lease.acquire())

        val blocked =
            try {
                ServerSocket().apply { bind(InetSocketAddress("127.0.0.1", port)) }
                false
            } catch (expected: java.io.IOException) {
                true
            }
        assertTrue("a bound lease must keep the port to itself", blocked)
        lease.release()
    }
}
