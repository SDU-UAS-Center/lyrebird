package com.lyrebird.rc.controller

/**
 * Pilot / Safety authority arbitration for the HTTP command server.
 *
 * Two computers drive the drone over HTTP:
 *  - the Pilot Computer flies the mission normally;
 *  - the Safety Computer supervises and can seize control at any time.
 *
 * Authority is identified purely by an `X-Safety-Token` HTTP header (configured app-side):
 * a request carrying the valid token is [Source.SAFETY], everything else is [Source.PILOT].
 *
 * Behaviour:
 *  - Initial state: [Authority.PILOT] holds control; Pilot commands execute normally.
 *  - The FIRST control command from the Safety Computer latches control to [Authority.SAFETY].
 *    From then on every Pilot command is rejected.
 *  - The takeover is PERSISTENT: no timeout ever returns control to the Pilot. Even if the
 *    Safety Computer goes silent, the Pilot does not regain control.
 *  - The only way back is an explicit POST /releaseSafetyControl, callable solely by the
 *    Safety Computer, which returns authority to the Pilot.
 *
 * The latch survives a restart. It used to be in-memory, which made an app restart the same thing
 * as a release — and a crash, an OOM kill, or an Android-initiated restart are not the Safety
 * Computer deciding to hand control back. It is now read back on start and keyed by aircraft
 * serial, so a takeover only ever holds the airframe it happened on. See [AuthorityLatch] for the
 * rule and [SafetyLatchStore] for where it is kept; clearing app data is the deliberate bypass.
 * This object is orthogonal to DroneController's RC manual-override latch — that tracks the
 * physical RC pilot, this tracks which computer commands the server, and a restart restores
 * neither of them into the other.
 *
 * Thread-safety: the command server handles requests on a 10-thread pool, so the
 * check-and-latch in [authorizeControlCommand]/[releaseSafetyControl] is @Synchronized.
 */
object ControlAuthority {
    /** Which computer currently holds command authority. */
    enum class Authority { PILOT, SAFETY }

    /** Origin of an individual HTTP request, derived from the X-Safety-Token header. */
    enum class Source { PILOT, SAFETY }

    private val latch = AuthorityLatch()

    /** The latch is the state; this is a view of it, not a second copy to drift out of step. */
    val active: Authority
        get() = latch.active

    /** UI hook — fires only when [active] actually changes. */
    interface Listener {
        fun onAuthorityChanged(authority: Authority)
    }

    var listener: Listener? = null

    /**
     * Gives the latch somewhere to live between runs, and the aircraft to key it on.
     *
     * Until this is called the latch is in-memory, which is what the tests and any host without
     * storage get. The app calls it during startup, before the servers accept anything.
     */
    internal fun attachPersistence(
        store: AuthorityLatch.LatchStore,
        aircraftSerial: () -> String,
    ) {
        latch.attach(store, aircraftSerial)
        restoreLatch()
    }

    /**
     * Re-reads the latch for the aircraft now in play, and tells the UI if that changed anything.
     *
     * Call this when the airframe identity becomes known: a takeover recorded against one aircraft
     * must not be carried onto another, and a takeover recorded for the aircraft that just
     * connected must be in force before its first command.
     */
    @Synchronized
    internal fun restoreLatch() {
        val before = latch.active
        latch.restore()
        if (latch.active != before) listener?.onAuthorityChanged(latch.active)
    }

    /**
     * Gate for every drone-control command (the /send/ family).
     * Returns true if the command is allowed to execute.
     *
     * A Safety command always passes and, on first arrival, latches authority to SAFETY
     * (cancelling any autonomous loop the Pilot left running). A Pilot command passes only
     * while the Pilot still holds authority.
     */
    @Synchronized
    fun authorizeControlCommand(source: Source): Boolean {
        val before = latch.active
        val decision = latch.authorize(source)
        if (latch.active != before) listener?.onAuthorityChanged(latch.active)
        if (decision == AuthorityLatch.Decision.ALLOWED_TAKEOVER) {
            // Safety has seized control: stop whatever the Pilot was flying so the aircraft
            // holds position until the Safety Computer issues its own commands.
            DroneController.onSafetyTakeover()
        }
        return decision != AuthorityLatch.Decision.REJECTED
    }

    /**
     * Explicit return of control to the Pilot. Reserved for the Safety Computer.
     * Returns false (and changes nothing) if a non-Safety caller invokes it.
     */
    @Synchronized
    fun releaseSafetyControl(source: Source): Boolean {
        val before = latch.active
        if (!latch.release(source)) return false
        if (latch.active != before) listener?.onAuthorityChanged(latch.active)
        return true
    }
}
