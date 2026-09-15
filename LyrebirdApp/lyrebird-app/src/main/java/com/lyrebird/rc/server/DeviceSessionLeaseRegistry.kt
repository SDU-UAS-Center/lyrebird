package com.lyrebird.rc.server

/** Process-scoped lease shared by application bootstrap and the activity runtime. */
internal object DeviceSessionLeaseRegistry {
    private val lease = SessionLease()

    fun acquire(): Boolean = lease.acquire()

    fun current(): SessionLease = lease

    fun release() = lease.release()
}
