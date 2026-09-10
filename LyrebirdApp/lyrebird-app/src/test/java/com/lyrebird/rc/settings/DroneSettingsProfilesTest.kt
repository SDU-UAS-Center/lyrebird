package com.lyrebird.rc.settings

import android.content.SharedPreferences
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Per-drone settings profile round-trip.
 *
 * The profile is a typed subset of the preferences: only per-drone keys are snapshotted, and the
 * restore must put each value back with its original type or the readers downstream break.
 */
class DroneSettingsProfilesTest {

    private val perDroneKeys = setOf("drone_name", "webrtc_fps", "detections_enabled")

    @Test
    fun snapshotCapturesOnlyPerDroneKeysWithTypes() {
        val prefs = FakePrefs(
            mapOf(
                "drone_name" to "mini3",
                "webrtc_fps" to 20,
                "detections_enabled" to true,
                "lb_mav_0_allow_flight" to false // device-wide, must not travel
            )
        )
        val snapshot = DroneSettingsProfiles.snapshotSettings(prefs, perDroneKeys)

        assertTrue(snapshot.has("drone_name"))
        assertTrue(snapshot.has("webrtc_fps"))
        assertTrue(snapshot.has("detections_enabled"))
        assertFalse(snapshot.has("lb_mav_0_allow_flight"))
        assertEquals("mini3", snapshot.getString("drone_name"))
        assertEquals(20, snapshot.getInt("webrtc_fps"))
        assertEquals(true, snapshot.getBoolean("detections_enabled"))
    }

    @Test
    fun applyRestoresTypesAndLeavesOtherPrefsAlone() {
        val prefs = FakePrefs(mapOf("unrelated" to "keep"))
        val values = JSONObject()
            .put("drone_name", "mini4")
            .put("webrtc_fps", 15)
            .put("detections_enabled", false)

        DroneSettingsProfiles.applySettings(prefs, values)

        assertEquals("mini4", prefs.getString("drone_name", null))
        assertEquals(15, prefs.getInt("webrtc_fps", -1))
        assertEquals(false, prefs.getBoolean("detections_enabled", true))
        assertEquals("keep", prefs.getString("unrelated", null))
    }

    @Test
    fun profileJsonRoundTripsTheValuesBlock() {
        val values = JSONObject().put("drone_name", "mini5").put("webrtc_fps", 30)
        val encoded = DroneSettingsProfiles.encodeProfile("ABC123", values)

        val parsed = DroneSettingsProfiles.parseProfile(encoded.toString())
        assertEquals("mini5", parsed?.getString("drone_name"))
        assertEquals(30, parsed?.getInt("webrtc_fps"))
    }

    @Test
    fun unreadableProfileParsesToNull() {
        assertNull(DroneSettingsProfiles.parseProfile("not json at all"))
    }

    @Test
    fun unusableSerialsAreRejected() {
        assertFalse(DroneSettingsProfiles.isUsableSerial(""))
        assertFalse(DroneSettingsProfiles.isUsableSerial("  "))
        assertFalse(DroneSettingsProfiles.isUsableSerial("UNKNOWN"))
        assertTrue(DroneSettingsProfiles.isUsableSerial("ABC123"))
    }

    /** Minimal in-memory SharedPreferences so the round-trip runs without Robolectric. */
    private class FakePrefs(initial: Map<String, Any>) : SharedPreferences {
        private val store = initial.toMutableMap()
        private val removed = Any()

        override fun getAll(): MutableMap<String, *> = store
        override fun getString(key: String, defValue: String?): String? = store[key] as? String ?: defValue
        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
            store[key] as? MutableSet<String> ?: defValues

        override fun getInt(key: String, defValue: Int): Int = store[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long): Long = store[key] as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = store[key] as? Float ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean = store[key] as? Boolean ?: defValue
        override fun contains(key: String): Boolean = store.containsKey(key)
        override fun edit(): SharedPreferences.Editor = Editor()
        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener
        ) = Unit

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener
        ) = Unit

        private inner class Editor : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any>()

            override fun putString(key: String, value: String?): SharedPreferences.Editor {
                pending[key] = value ?: removed
                return this
            }

            override fun putStringSet(
                key: String,
                values: MutableSet<String>?
            ): SharedPreferences.Editor = this

            override fun putInt(key: String, value: Int): SharedPreferences.Editor {
                pending[key] = value
                return this
            }

            override fun putLong(key: String, value: Long): SharedPreferences.Editor {
                pending[key] = value
                return this
            }

            override fun putFloat(key: String, value: Float): SharedPreferences.Editor {
                pending[key] = value
                return this
            }

            override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor {
                pending[key] = value
                return this
            }

            override fun remove(key: String): SharedPreferences.Editor {
                pending[key] = removed
                return this
            }

            override fun clear(): SharedPreferences.Editor {
                store.clear()
                pending.clear()
                return this
            }

            override fun commit(): Boolean {
                apply()
                return true
            }

            override fun apply() {
                pending.forEach { (key, value) ->
                    if (value === removed) store.remove(key) else store[key] = value
                }
                pending.clear()
            }
        }
    }
}
