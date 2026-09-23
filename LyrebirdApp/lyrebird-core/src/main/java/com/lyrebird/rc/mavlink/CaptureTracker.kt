package com.lyrebird.rc.mavlink

import java.util.concurrent.atomic.AtomicLong

/**
 * Which shutter captures one endpoint has in flight.
 *
 * Capture is asynchronous: the command is acknowledged immediately and the result arrives seconds
 * later, on a worker. The tracker is what makes "arrives later" safe — a result whose id this
 * endpoint never issued belongs to a previous session, and announcing it would read as this
 * session's photo. Ids come from a process-wide counter, so a replacement endpoint instance can
 * never collide with the ids of the one it replaced.
 *
 * A set rather than a single slot: two shutters can overlap (a mission's distance trigger and a
 * ground-station command), and each result must still be delivered exactly once.
 */
class CaptureTracker {
    private companion object {
        /** Process-wide so ids stay unique across endpoint restarts. */
        val nextId = AtomicLong(0)
    }

    private val active = mutableSetOf<Long>()

    /** Register a shutter and return the id its result will be matched against. */
    @Synchronized
    fun started(): Long {
        val id = nextId.incrementAndGet()
        active.add(id)
        return id
    }

    /** True while at least one shutter is in flight, for honest capture-status reporting. */
    val isCapturing: Boolean
        @Synchronized get() = active.isNotEmpty()

    /** True when [id] was in flight and is now delivered; false for an unknown or repeated id. */
    @Synchronized
    fun finished(id: Long): Boolean = active.remove(id)
}
