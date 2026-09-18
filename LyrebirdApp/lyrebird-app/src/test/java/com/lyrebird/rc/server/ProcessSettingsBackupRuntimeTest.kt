package com.lyrebird.rc.server

import android.content.SharedPreferences
import com.lyrebird.rc.settings.DroneSettingsProfilesTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The settings-backup runtime: changes debounce into a single write, and the write goes to the
 * screen that is actually attached — a stale screen's detach must neither redirect nor silence
 * the backup a newer screen asked for.
 */
class ProcessSettingsBackupRuntimeTest {
    private class FakeScheduler : BackupScheduler {
        val pending = mutableListOf<Pair<Long, Runnable>>()
        var cancels = 0
            private set

        override fun postDelayed(
            delayMs: Long,
            action: Runnable,
        ) {
            pending += delayMs to action
        }

        override fun cancel(action: Runnable) {
            cancels++
            pending.removeAll { it.second === action }
        }

        fun pump() {
            val due = pending.toList()
            pending.clear()
            due.forEach { it.second.run() }
        }
    }

    private class FakeCallbacks(
        private val name: String,
    ) : SettingsBackupRuntimeCallbacks {
        override val runtimeSettingsBackupDroneName: String get() = name
    }

    private fun awaitWrites(writes: List<Pair<SharedPreferences, String>>) {
        val deadline = System.currentTimeMillis() + 5_000
        while (writes.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
    }

    @Test
    fun `a preference change schedules one debounced write`() {
        val prefs = DroneSettingsProfilesTest.FakePrefs(mapOf())
        val writes = mutableListOf<Pair<SharedPreferences, String>>()
        val scheduler = FakeScheduler()
        val runtime = ProcessSettingsBackupRuntime(scheduler) { p, name -> writes += p to name }
        runtime.attach(prefs, FakeCallbacks("scout"))

        prefs.edit().putBoolean("lb_obstacle_enabled", true).apply()

        assertEquals(1, scheduler.pending.size)
        assertEquals(1_500L, scheduler.pending.single().first)
        scheduler.pump()
        awaitWrites(writes)
        assertEquals(listOf("scout"), writes.map { it.second })
    }

    @Test
    fun `changes inside the debounce window re-arm instead of stacking`() {
        val prefs = DroneSettingsProfilesTest.FakePrefs(mapOf())
        val scheduler = FakeScheduler()
        val runtime = ProcessSettingsBackupRuntime(scheduler) { _, _ -> }
        runtime.attach(prefs, FakeCallbacks("scout"))

        prefs.edit().putBoolean("a", true).apply()
        prefs.edit().putBoolean("b", true).apply()

        assertEquals("the second change must cancel the first schedule", 2, scheduler.cancels)
        assertEquals(1, scheduler.pending.size)
    }

    @Test
    fun `a stale screen cannot redirect the backup to itself`() {
        val prefs = DroneSettingsProfilesTest.FakePrefs(mapOf())
        val writes = mutableListOf<Pair<SharedPreferences, String>>()
        val scheduler = FakeScheduler()
        val runtime = ProcessSettingsBackupRuntime(scheduler) { p, name -> writes += p to name }
        val old = FakeCallbacks("old")
        val newer = FakeCallbacks("new")
        runtime.attach(prefs, old)
        runtime.attach(prefs, newer)

        runtime.detach(old)
        runtime.scheduleInitialBackup()
        scheduler.pump()
        awaitWrites(writes)
        assertEquals("the attached screen's name must be used, not the stale one's", listOf("new"), writes.map { it.second })

        runtime.detach(newer)
        runtime.scheduleInitialBackup()
        scheduler.pump()
        Thread.sleep(100)
        assertEquals("no screen, no write", 1, writes.size)
    }

    @Test
    fun `re-attaching a different store moves the listener with it`() {
        val storeA = DroneSettingsProfilesTest.FakePrefs(mapOf())
        val storeB = DroneSettingsProfilesTest.FakePrefs(mapOf())
        val scheduler = FakeScheduler()
        val runtime = ProcessSettingsBackupRuntime(scheduler) { _, _ -> }
        runtime.attach(storeA, FakeCallbacks("a"))
        runtime.attach(storeB, FakeCallbacks("b"))

        storeA.edit().putBoolean("x", true).apply()
        assertEquals("the old store must be unlistened", 0, scheduler.pending.size)

        storeB.edit().putBoolean("x", true).apply()
        assertEquals(1, scheduler.pending.size)
    }
}
