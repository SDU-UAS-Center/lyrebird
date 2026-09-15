package com.lyrebird.rc.controller

/**
 * The Pilot/Safety latch, with the one decision that makes it outlive the process.
 *
 * The rule this encodes: a restart is not a release. Control belongs to the Pilot until the Safety
 * Computer takes it, and from that moment only an explicit release from the Safety Computer gives
 * it back — so a crash, a kill, or an Android restart must not do it by accident. The latch is
 * therefore read back on start and written the instant it changes.
 *
 * It is keyed by aircraft serial. One RC can be flown against several airframes, and a takeover
 * belongs to the airframe it happened on: a different aircraft must not be locked out by a latch
 * that was never about it. Storage is the app's private preferences, so clearing app data or
 * reinstalling the app is the deliberate bypass, as it should be — nothing else clears it.
 *
 * Deliberately separate from the RC's physical manual-override latch in [DroneController]: that
 * tracks the human holding the sticks, this tracks which computer is commanding the server, and
 * neither implies the other.
 */
internal class AuthorityLatch(
    private var store: LatchStore? = null,
    private var aircraftSerial: () -> String = { UNKNOWN_SERIAL },
) {
    /**
     * Where the latch is kept between runs.
     *
     * Keyed by aircraft serial because the latch is about an airframe, not about the device.
     */
    interface LatchStore {
        /**
         * The latched authority for this aircraft, or null when nothing is stored for it.
         *
         * An unreadable record must be reported as [ControlAuthority.Authority.SAFETY] rather than
         * as "nothing stored": the latch is the thing that keeps a crash from returning control to
         * the Pilot, and a value it cannot read is not evidence that the Pilot holds it.
         */
        fun read(aircraftSerial: String): ControlAuthority.Authority?

        /** Records the latched authority for this aircraft. */
        fun write(aircraftSerial: String, authority: ControlAuthority.Authority)
    }

    /** What the latch decided about one request. */
    enum class Decision { ALLOWED, ALLOWED_TAKEOVER, REJECTED }

    @Volatile
    var active: ControlAuthority.Authority = ControlAuthority.Authority.PILOT
        private set

    /**
     * Gives the latch somewhere to live between runs.
     *
     * The serial is a provider rather than a value because the aircraft identity arrives
     * asynchronously from the SDK; every read and write asks for it as it is at that moment.
     */
    fun attach(store: LatchStore, aircraftSerial: () -> String) {
        this.store = store
        this.aircraftSerial = aircraftSerial
    }

    fun authorize(source: ControlAuthority.Source): Decision =
        when (source) {
            ControlAuthority.Source.SAFETY ->
                if (active != ControlAuthority.Authority.SAFETY) {
                    latch(ControlAuthority.Authority.SAFETY)
                    Decision.ALLOWED_TAKEOVER
                } else {
                    Decision.ALLOWED
                }
            ControlAuthority.Source.PILOT ->
                if (active == ControlAuthority.Authority.PILOT) {
                    Decision.ALLOWED
                } else {
                    Decision.REJECTED
                }
        }

    /** Explicit return of control. Only the Safety Computer may ask for it. */
    fun release(source: ControlAuthority.Source): Boolean {
        if (source != ControlAuthority.Source.SAFETY) return false
        if (active != ControlAuthority.Authority.PILOT) latch(ControlAuthority.Authority.PILOT)
        return true
    }

    /**
     * Reads the stored latch for the aircraft now in play.
     *
     * Only ever raises authority. A stale-or-missing record says nothing about a takeover this
     * process has already performed, and the serial only becomes known partway through startup —
     * so a live takeover is carried onto the airframe rather than dropped on the floor.
     */
    fun restore() {
        val store = store ?: return
        val serial = aircraftSerial()
        val stored = store.read(serial)
        if (stored == ControlAuthority.Authority.SAFETY) {
            active = ControlAuthority.Authority.SAFETY
        } else if (active == ControlAuthority.Authority.SAFETY) {
            store.write(serial, ControlAuthority.Authority.SAFETY)
        }
    }

    private fun latch(authority: ControlAuthority.Authority) {
        active = authority
        store?.write(aircraftSerial(), authority)
    }

    companion object {
        /** Stands in for an airframe that has not introduced itself yet. */
        const val UNKNOWN_SERIAL = "UNKNOWN"
    }
}
