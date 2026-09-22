package com.lyrebird.rc.fleet

/**
 * How long a device keeps announcing an identity it has left behind.
 *
 * A device starts out advertising its installation id, then switches to the aircraft serial once
 * the link comes up, and only this device can tell the fleet that the old row is gone. That
 * announcement used to go out exactly once, which is fine for every peer that was already
 * listening and useless for one that was restarting at that moment: the missed row then sits in
 * the roster for the whole forget timeout, showing one aircraft twice — the second row a lost
 * ghost — and clashing it against itself on name and MAVLink id, which the other receivers report
 * as a fleet conflict. Three receivers restarted together on the bench reproduced it every time.
 *
 * Repeating costs a few hundred bytes on a link that already carries a beacon every 500 ms, and it
 * stops on its own, so the count lives here where the arithmetic can be tested rather than in the
 * socket loop.
 */
internal class FleetRetireAnnouncer(
    private val repeatTicks: Int,
) {
    init {
        require(repeatTicks > 0) { "a retire announcement has to go out at least once" }
    }

    /** The identity currently being retired, or null when there is nothing left to announce. */
    var name: String? = null
        private set

    private var ticksLeft: Int = 0

    /** Start (or restart) announcing [deviceId]. Announcements for any previous id are dropped. */
    fun announce(deviceId: String) {
        if (deviceId.isEmpty()) return
        name = deviceId
        ticksLeft = repeatTicks
    }

    /**
     * One tick of announcements: the identity to send now, or null when there is nothing to send.
     *
     * The first tick after [announce] returns the id and the last tick before the repeat count runs
     * out returns it too, so the two calls together are exactly [repeatTicks] announcements.
     */
    fun next(): String? {
        val deviceId = name ?: return null
        if (ticksLeft <= 0) {
            name = null
            return null
        }
        ticksLeft--
        if (ticksLeft == 0) name = null
        return deviceId
    }
}
