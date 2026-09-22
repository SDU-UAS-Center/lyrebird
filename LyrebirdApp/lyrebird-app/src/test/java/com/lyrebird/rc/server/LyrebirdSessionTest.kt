package com.lyrebird.rc.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * These tests are about ordering and honesty rather than about sockets: discovery must not go out
 * ahead of a bind, a half-bound session must advertise itself as half bound, and a session serving
 * nothing must not hold the lease or stand in the network's way.
 */
class LyrebirdSessionTest {

    /** Records the order in which lifecycle work happened, and can be made to fail binding. */
    private class FakeServer(
        override val label: String,
        var binds: Boolean,
        private val events: MutableList<String>,
    ) : ClientAwareServer {
        private var running = false
        var stopped = false
            private set

        override fun start(): Boolean {
            events += "start:$label"
            running = binds
            return binds
        }

        override fun stop() {
            events += "stop:$label"
            running = false
            stopped = true
        }

        override fun hasClients(): Boolean = running
    }

    private class FakeAdvertiser(private val events: MutableList<String>) : SessionAdvertiser {
        var advertisedHttp: Int? = null
        var advertisedTelemetry: Int? = null
        var advertisements = 0
            private set
        var withdrawn = false
            private set

        override fun advertise(httpPort: Int?, telemetryPort: Int?) {
            events += "advertise"
            advertisements++
            advertisedHttp = httpPort
            advertisedTelemetry = telemetryPort
        }

        override fun stopAdvertising() {
            events += "unadvertise"
            withdrawn = true
        }
    }

    private fun session(
        events: MutableList<String>,
        lease: SessionLease,
        httpBinds: Boolean = true,
        telemetryBinds: Boolean = true,
    ): Pair<LyrebirdSession, FakeAdvertiser> {
        val advertiser = FakeAdvertiser(events)
        val session =
            LyrebirdSession(
                lease = lease,
                http = FakeServer("commands", httpBinds, events),
                telemetry = FakeServer("telemetry", telemetryBinds, events),
                advertiser = advertiser,
                httpPort = 8080,
                telemetryPort = 8081,
            )
        return session to advertiser
    }

    private fun freePort(): Int = java.net.ServerSocket(0).use { it.localPort }

    @Test
    fun `servers bind before anything is advertised`() {
        val events = mutableListOf<String>()
        val lease = SessionLease(freePort())
        val (session, advertiser) = session(events, lease)

        val status = session.start()

        assertEquals(listOf("start:commands", "start:telemetry", "advertise"), events)
        assertTrue(status.isServing)
        assertEquals(8080, advertiser.advertisedHttp)
        assertEquals(8081, advertiser.advertisedTelemetry)
        session.stop()
    }

    @Test
    fun `only the ports that bound are advertised`() {
        val events = mutableListOf<String>()
        val lease = SessionLease(freePort())
        val (session, advertiser) = session(events, lease, telemetryBinds = false)

        val status = session.start()

        assertNull("the telemetry port must not be published when it is not answering", advertiser.advertisedTelemetry)
        assertEquals(8080, advertiser.advertisedHttp)
        assertEquals(8080, status.httpPort)
        assertNull(status.telemetryPort)
        assertEquals(listOf("telemetry"), status.failures)
        session.stop()
    }

    @Test
    fun `a later start retries a server that failed to bind`() {
        val events = mutableListOf<String>()
        val lease = SessionLease(freePort())
        val advertiser = FakeAdvertiser(events)
        val http = FakeServer("commands", binds = true, events)
        val telemetry = FakeServer("telemetry", binds = false, events)
        val session =
            LyrebirdSession(
                lease = lease,
                http = http,
                telemetry = telemetry,
                advertiser = advertiser,
                httpPort = 8080,
                telemetryPort = 8081,
            )

        val first = session.start()
        assertNull(first.telemetryPort)
        assertEquals(listOf("telemetry"), first.failures)

        telemetry.binds = true // the port is free again
        val second = session.start()

        assertEquals(8081, second.telemetryPort)
        assertTrue(second.failures.isEmpty())
        assertEquals(8081, advertiser.advertisedTelemetry)
        assertEquals("the healthy server is not rebound", 1, events.count { it == "start:commands" })
        assertTrue(second.summary().contains("telemetry on 8081"))
        session.stop()
    }

    @Test
    fun `a session that bound nothing can be retried later`() {
        val events = mutableListOf<String>()
        val lease = SessionLease(freePort())
        val advertiser = FakeAdvertiser(events)
        val http = FakeServer("commands", binds = false, events)
        val telemetry = FakeServer("telemetry", binds = false, events)
        val session =
            LyrebirdSession(
                lease = lease,
                http = http,
                telemetry = telemetry,
                advertiser = advertiser,
                httpPort = 8080,
                telemetryPort = 8081,
            )

        assertFalse(session.start().isServing)
        assertFalse("a session serving nothing must not hold the lease", lease.isHeld)

        http.binds = true
        telemetry.binds = true
        val retried = session.start()

        assertTrue(retried.isServing)
        assertTrue(lease.isHeld)
        assertEquals(8080, advertiser.advertisedHttp)
        assertEquals(8081, advertiser.advertisedTelemetry)
        session.stop()
    }

    @Test
    fun `a session serving nothing releases the lease and stays quiet`() {
        val events = mutableListOf<String>()
        val lease = SessionLease(freePort())
        val (session, advertiser) = session(events, lease, httpBinds = false, telemetryBinds = false)

        val status = session.start()

        assertEquals(0, advertiser.advertisements)
        assertFalse(status.isServing)
        assertFalse(status.leaseHeld)
        assertFalse("a session serving nothing must not keep another app from serving", lease.isHeld)
        assertEquals(listOf("commands", "telemetry"), status.failures)
        session.stop()
    }

    @Test
    fun `a held lease stops the session before anything is bound`() {
        val events = mutableListOf<String>()
        val port = freePort()
        val other = SessionLease(port)
        assertTrue(other.acquire())

        val (session, advertiser) = session(events, SessionLease(port))
        val status = session.start()

        assertTrue(status.blockedByAnotherSession)
        assertFalse(status.isServing)
        assertEquals(0, advertiser.advertisements)
        assertEquals("no server may be bound while another app owns the session", emptyList<String>(), events)

        other.release()
        session.stop()
    }

    @Test
    fun `stopping withdraws the advertisement before the servers go`() {
        val events = mutableListOf<String>()
        val lease = SessionLease(freePort())
        val (session, advertiser) = session(events, lease)
        session.start()
        events.clear()

        session.stop()

        assertEquals(listOf("unadvertise", "stop:commands", "stop:telemetry"), events)
        assertTrue(advertiser.withdrawn)
        assertFalse(lease.isHeld)
        assertFalse(session.status.isServing)
    }

    @Test
    fun `starting twice does not bind or advertise twice`() {
        val events = mutableListOf<String>()
        val lease = SessionLease(freePort())
        val (session, advertiser) = session(events, lease)

        session.start()
        session.start()

        assertEquals(1, advertiser.advertisements)
        assertEquals(listOf("start:commands", "start:telemetry", "advertise"), events)
        session.stop()
    }

    @Test
    fun `a second session is refused while the first holds the lease`() {
        val events = mutableListOf<String>()
        val port = freePort()
        val first = session(events, SessionLease(port)).first
        val (second, secondAdvertiser) = session(events, SessionLease(port))

        assertTrue(first.start().isServing)
        val blocked = second.start()

        assertTrue(blocked.blockedByAnotherSession)
        assertEquals(0, secondAdvertiser.advertisements)

        first.stop()
        // With the first session down, the second can take over on the next attempt.
        assertTrue(second.start().isServing)
        second.stop()
    }

    @Test
    fun `the telemetry client count comes from the telemetry server`() {
        val events = mutableListOf<String>()
        val (session, _) = session(events, SessionLease(freePort()))

        assertFalse(session.hasTelemetryClients())
        session.start()
        assertTrue(session.hasTelemetryClients())
        session.stop()
        assertFalse(session.hasTelemetryClients())
    }

    @Test
    fun `the summary names what is answering and what is not`() {
        val events = mutableListOf<String>()
        val (session, _) = session(events, SessionLease(freePort()), telemetryBinds = false)

        assertEquals(
            "Serving commands on 8080 (telemetry down)",
            session.start().summary(),
        )
        session.stop()
    }

    @Test
    fun `a healthy summary does not end in null`() {
        val events = mutableListOf<String>()
        val (session, _) = session(events, SessionLease(freePort()))

        val summary = session.start().summary()

        assertFalse("a serving session reads as serving: $summary", summary.contains("null"))
        session.stop()
    }

    @Test
    fun `the summary reports a session owned elsewhere`() {
        val port = freePort()
        val other = SessionLease(port)
        assertTrue(other.acquire())

        val (session, _) = session(mutableListOf(), SessionLease(port))
        val summary = session.start().summary()

        assertTrue("the operator has to be told why the app is not serving", summary.contains("Another Lyrebird app"))

        other.release()
        session.stop()
    }

    @Test
    fun `stopping without a session is safe and repeatable`() {
        val events = mutableListOf<String>()
        val port = freePort()
        val lease = SessionLease(port)
        val (session, _) = session(events, lease)

        // A teardown path must not need to know whether the start ever ran: stopping a session
        // that never bound, twice, must leave the lease cleanly free for the next process.
        session.stop()
        session.stop()

        assertFalse(lease.isHeld)
        assertFalse(session.status.isServing)
        assertTrue("the lease port is free again", SessionLease(port).acquire())
    }
}
