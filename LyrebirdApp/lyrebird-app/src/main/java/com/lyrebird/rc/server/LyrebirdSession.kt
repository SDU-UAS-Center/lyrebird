package com.lyrebird.rc.server

/**
 * One server the session owns.
 */
internal interface SessionServer {
    /** Human-readable name, for status and logs. */
    val label: String

    /**
     * Binds and starts serving.
     *
     * @return true when the server is answering, false when its port could not be taken. It must
     *   report the bind result rather than returning early and failing afterwards on a background
     *   thread — the caller decides what to advertise from this answer.
     */
    fun start(): Boolean

    /** Stops serving. Safe to call whether or not [start] was reached. */
    fun stop()
}

/**
 * A server that knows whether anyone is attached to it.
 *
 * Only the telemetry server qualifies today; the command server answers requests and has no
 * standing connection to count.
 */
internal interface ClientAwareServer : SessionServer {
    fun hasClients(): Boolean
}

/**
 * Publishes the session to the network, and takes the publication down again.
 */
internal interface SessionAdvertiser {
    /**
     * Advertises the session.
     *
     * @param httpPort the command port, or null when it is not answering.
     * @param telemetryPort the telemetry port, or null when it is not answering.
     */
    fun advertise(httpPort: Int?, telemetryPort: Int?)

    /** Withdraws everything advertised by [advertise]. */
    fun stopAdvertising()
}

/**
 * What the session is actually doing, rather than what it was asked to do.
 */
internal data class LyrebirdSessionStatus(
    val leaseHeld: Boolean = false,
    val blockedByAnotherSession: Boolean = false,
    val httpPort: Int? = null,
    val telemetryPort: Int? = null,
    val advertising: Boolean = false,
    /** Names of the servers that could not bind, so the reason survives to the screen. */
    val failures: List<String> = emptyList(),
) {
    val isServing: Boolean
        get() = httpPort != null || telemetryPort != null

    /** One line for the operator, naming what is answering and what is not. */
    fun summary(): String =
        when {
            blockedByAnotherSession -> {
                "Another Lyrebird app owns the network session; this one is not serving"
            }
            !isServing -> "Not serving: ${failures.joinToString(", ").ifEmpty { "no servers started" }}"
            else -> {
                val serving =
                    listOfNotNull(
                        httpPort?.let { "commands on $it" },
                        telemetryPort?.let { "telemetry on $it" },
                    )
                val down = failures.takeIf { it.isNotEmpty() }?.let { " (${it.joinToString(", ")} down)" }
                "Serving ${serving.joinToString(" and ")}$down"
            }
        }
}

/**
 * Owns the device's network session: the exclusive lease, the command and telemetry servers, and
 * the discovery advertisement that points ground stations at them.
 *
 * The ordering is the point of this class. Discovery used to be started before the servers were
 * bound, so the aircraft announced itself and only then found out whether it could serve; a bind
 * failure left the announcement standing, and a ground station would spend the flight talking to
 * a port nobody was listening on. Here the lease is taken first, the servers bind second, and only
 * what bound is advertised. The status returned from [start] says which is which, and a session
 * that ends up serving nothing gives the lease back rather than holding the device's name with
 * nothing behind it.
 */
internal class LyrebirdSession(
    private val lease: SessionLease,
    private val http: SessionServer,
    private val telemetry: SessionServer,
    private val advertiser: SessionAdvertiser,
    private val httpPort: Int,
    private val telemetryPort: Int,
) {
    @Volatile
    var status: LyrebirdSessionStatus = LyrebirdSessionStatus()
        private set

    /**
     * Takes the lease, binds the servers, and advertises what came up.
     *
     * @return what the session is actually doing; see [LyrebirdSessionStatus].
     */
    fun start(): LyrebirdSessionStatus {
        if (status.isServing) return status

        if (!lease.acquire()) {
            status = LyrebirdSessionStatus(blockedByAnotherSession = true)
            return status
        }

        val httpUp = http.start()
        val telemetryUp = telemetry.start()

        if (!httpUp && !telemetryUp) {
            // Nothing is answering, so nothing may be advertised, and the lease goes back: holding
            // it would keep the other APK from serving while this one serves nothing either.
            lease.release()
            status =
                LyrebirdSessionStatus(
                    leaseHeld = false,
                    failures = listOf(http.label, telemetry.label),
                )
            return status
        }

        advertiser.advertise(
            httpPort = httpPort.takeIf { httpUp },
            telemetryPort = telemetryPort.takeIf { telemetryUp },
        )

        status =
            LyrebirdSessionStatus(
                leaseHeld = true,
                httpPort = httpPort.takeIf { httpUp },
                telemetryPort = telemetryPort.takeIf { telemetryUp },
                advertising = true,
                failures =
                    listOfNotNull(
                        http.label.takeUnless { httpUp },
                        telemetry.label.takeUnless { telemetryUp },
                    ),
            )
        return status
    }

    /** True while at least one ground station is attached to the telemetry stream. */
    fun hasTelemetryClients(): Boolean = (telemetry as? ClientAwareServer)?.hasClients() == true

    /** Stops serving, in the reverse order of [start]. Safe to call more than once. */
    fun stop() {        // Point clients elsewhere before the sockets go: a client still being told we are here,
        // about a session that has already left, would reconnect to nothing.
        advertiser.stopAdvertising()
        http.stop()
        telemetry.stop()
        lease.release()
        status = LyrebirdSessionStatus()
    }
}
