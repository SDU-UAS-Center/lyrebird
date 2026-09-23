package com.lyrebird.rc.server

import android.util.Log
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket

/**
 * Device-local exclusive lease over the Lyrebird network session.
 *
 * Two Lyrebird APKs are meant to be installed side by side, one per SDK version. Both would
 * otherwise bind the same command and telemetry ports and answer the same discovery probes, and
 * the result is two half-working ground-station links instead of one working one. The lease turns
 * that into a decision: whichever app holds it serves, and the other one reports why it is not.
 *
 * It is taken by binding a loopback listening socket, not by asking whether a port is free and
 * then binding it. A check followed by a bind leaves a window in which both processes see a free
 * port, and the one that loses the race finds out only later, with an error it cannot attribute.
 * A bind either succeeds or fails, and the failure is the answer.
 *
 * Cooperative, and narrow by design: this is not an aircraft authority and cannot stop another app
 * from touching the SDK, the RC accessory, or the aircraft. It says which app owns the network
 * session, and nothing more.
 *
 * [SESSION_LEASE_PORT] must be identical in both APKs — the number is the contract between them.
 */
internal class SessionLease(
    private val port: Int = SESSION_LEASE_PORT,
) {
    private var socket: ServerSocket? = null

    /** True while this instance holds the lease. */
    val isHeld: Boolean
        get() = socket?.isClosed == false

    /**
     * Takes the lease, or reports that someone else has it.
     *
     * @return true when this process now owns the session; false when another one already does.
     */
    fun acquire(): Boolean {
        if (isHeld) return true
        return try {
            socket =
                ServerSocket().apply {
                    // Deliberately not SO_REUSEADDR: sharing the port is the one thing this socket
                    // exists to prevent.
                    reuseAddress = false
                    bind(InetSocketAddress(InetAddress.getLoopbackAddress(), port))
                }
            true
        } catch (error: IOException) {
            Log.i(TAG, "Session lease on port $port is held elsewhere: ${error.message}")
            socket = null
            false
        }
    }

    /** Gives the lease up. Safe to call when it is not held. */
    fun release() {
        runCatching { socket?.close() }
            .onFailure { error -> Log.w(TAG, "Error releasing session lease: ${error.message}") }
        socket = null
    }

    companion object {
        private const val TAG = "SessionLease"

        /**
         * Well clear of the service ports, and deliberately below the ephemeral range.
         *
         * Android hands out outbound source ports from roughly 32768 upwards, so a lease port up
         * there could be taken by an unrelated connection and read as a competing Lyrebird
         * session — which would stop this app from serving for no reason. Below 1024 would need
         * a privilege an app does not have, so the value sits between the two.
         *
         * Must be identical in both APKs; the number is the contract between them.
         */
        const val SESSION_LEASE_PORT = 3900
    }
}
