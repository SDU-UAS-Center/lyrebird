package com.lyrebird.rc.server

/** Process-scoped lease shared by application bootstrap and the activity runtime. */
internal object DeviceSessionLeaseRegistry {
    private val lease = SessionLease()

    /**
     * True when the lease was found held by another Lyrebird app when this process started.
     *
     * Recorded rather than re-checked: the screens ask whether *this* app owns the session, and
     * re-acquiring from a screen would answer a different question ("could I take it now?").
     * The DJI SDK was not initialised in that case (see DJIApplication), so every SDK-facing
     * screen has to know not to build SDK objects at all.
     */
    @Volatile
    var blockedByAnotherSession = false
        private set

    fun acquire(): Boolean {
        if (lease.acquire()) return true
        blockedByAnotherSession = true
        return false
    }

    fun current(): SessionLease = lease

    fun release() {
        lease.release()
        blockedByAnotherSession = false
    }
}
