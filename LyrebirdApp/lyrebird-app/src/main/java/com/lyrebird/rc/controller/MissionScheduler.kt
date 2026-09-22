package com.lyrebird.rc.controller

import kotlin.concurrent.thread

/** A handle on the mission sequencer's worker, so [MavlinkMissionPolicy] can cancel it. */
internal interface MissionTask {
    fun interrupt()
}

/**
 * How the mission sequencer runs and waits.
 *
 * The sequencing rules are time-shaped — wait for a take-off climb to finish, poll a leg until it
 * is reached or its timeout expires, sleep between polls — and none of that is testable against
 * the wall clock. Injecting the scheduler is what lets a test fly a whole plan in-call on a fake
 * clock: sleeps advance [nowMs], and the test decides which poll a refusal, an override or a
 * cancellation fires on.
 */
internal interface MissionScheduler {
    /** A monotonic clock in milliseconds; the basis of every deadline in the sequencer. */
    fun nowMs(): Long

    /** Run [block] off the calling thread. The returned handle cancels by interrupting it. */
    fun runAsync(
        name: String,
        block: () -> Unit,
    ): MissionTask

    /** Sleep for [ms], throwing like [Thread.sleep] when the task is cancelled while sleeping. */
    fun sleepMs(ms: Long)
}

/** The scheduler the app runs on: a real thread and the wall clock. */
internal object SystemMissionScheduler : MissionScheduler {
    override fun nowMs(): Long = System.currentTimeMillis()

    override fun runAsync(
        name: String,
        block: () -> Unit,
    ): MissionTask {
        val worker = thread(name = name, start = true) { block() }
        return object : MissionTask {
            override fun interrupt() = worker.interrupt()
        }
    }

    override fun sleepMs(ms: Long) {
        Thread.sleep(ms)
    }
}
