package com.lyrebird.rc.server

import android.util.Log

/**
 * The explicit, ordered teardown of a serving session.
 *
 * Order matters in one direction only: everything that serves *through* the session has to be
 * down before the session's lease goes back. The lease is what tells a second Lyrebird app on the
 * same phone "the device is yours"; releasing it while this app's video publisher, MAVLink
 * endpoint, detection runtimes or fleet mesh are still running would hand the aircraft over with
 * the camera, the radio and the ports still busy.
 *
 * So the lease release is deliberately not one more step in the list: it is what [run] does last,
 * and it happens even when a step before it failed. A teardown that threw halfway must not strand
 * the device lease — that would leave both APKs convinced the other owns the session, which is
 * the one state neither can recover from without a process restart.
 */
internal class RuntimeTeardown(
    private val steps: List<Step>,
    private val releaseLease: () -> Unit,
) {
    /** One named teardown action, so a log line and a test can both name what ran. */
    internal data class Step(
        val label: String,
        val action: () -> Unit,
    )

    /**
     * Runs every step in order, then releases the lease.
     *
     * @return the labels in execution order, for the caller's log and for tests that pin the
     *   order.
     */
    fun run(): List<String> {
        val ran = mutableListOf<String>()
        for (step in steps) {
            runCatching { step.action() }
                .onFailure { Log.w(TAG, "Teardown step \"${step.label}\" failed: ${it.message}", it) }
            ran += step.label
        }
        runCatching { releaseLease() }
            .onFailure { Log.e(TAG, "Could not release the session lease: ${it.message}", it) }
        ran += LEASE_STEP
        Log.i(TAG, "Teardown complete: ${ran.joinToString(" -> ")}")
        return ran
    }

    companion object {
        private const val TAG = "LyrebirdTeardown"

        /** The label [run] appends for the lease release, last by construction. */
        const val LEASE_STEP = "release lease"
    }
}
