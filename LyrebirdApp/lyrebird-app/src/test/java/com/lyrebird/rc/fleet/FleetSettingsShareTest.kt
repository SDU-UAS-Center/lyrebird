package com.lyrebird.rc.fleet

import android.content.SharedPreferences
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The allowlist is the security boundary of the whole mesh, and the fact that receiving a profile
 * changes nothing is the property that makes the boundary cheap to trust.
 *
 * These pin both: identity and flight settings never travel between aircraft, a hand-crafted
 * datagram cannot smuggle a key past the filter on the way in, and what a receiver ends up with is
 * a file rather than a mutation.
 */
class FleetSettingsShareTest {

    @Test
    fun `an offer carries only allowlisted keys`() {
        val prefs = FakePrefs(
            mapOf(
                "mediamtx_server" to "10.0.0.5:8889",
                "webrtc_fps" to 30,
                "drone_name" to "alpha",
                "lb_mav_0_sysid" to 7,
                "lb_mav_0_allow_flight" to true
            )
        )
        val offer = FleetSettingsShare.buildOffer(prefs, "OWN", "alpha")
        val values = offer.getJSONObject("vals")
        assertTrue(values.has("mediamtx_server"))
        assertTrue(values.has("webrtc_fps"))
        assertFalse("drone name must never travel", values.has("drone_name"))
        assertFalse("MAVLink id must never travel", values.has("lb_mav_0_sysid"))
        assertFalse("flight gate must never travel", values.has("lb_mav_0_allow_flight"))
    }

    @Test
    fun `an offer round trips`() {
        val prefs = FakePrefs(
            mapOf(
                "mediamtx_server" to "10.0.0.5:8889",
                "webrtc_fps" to 30,
                "detections_enabled" to true,
                "edge_confidence_threshold" to 0.4f
            )
        )
        val parsed = FleetSettingsShare.parseOffer(
            FleetSettingsShare.buildOffer(prefs, "OWN", "alpha").toString()
        )
        assertNotNull(parsed)
        assertEquals("OWN", parsed!!.fromDeviceId)
        assertEquals("alpha", parsed.fromDroneName)
        assertEquals("10.0.0.5:8889", parsed.values["mediamtx_server"])
        assertEquals(30, parsed.values["webrtc_fps"])
        assertEquals(true, parsed.values["detections_enabled"])
    }

    @Test
    fun `a hand crafted offer cannot smuggle a key outside the allowlist`() {
        val hostile = JSONObject()
            .put("lb", FleetBeacon.MAGIC)
            .put("v", FleetBeacon.PROTOCOL_VERSION)
            .put("t", FleetSettingsShare.TYPE_SETTINGS_OFFER)
            .put("id", "ATTACKER")
            .put("n", "friendly")
            .put(
                "vals",
                JSONObject()
                    .put("mediamtx_server", "10.0.0.9:8889")
                    .put("lb_mav_0_allow_flight", true)
                    .put("drone_name", "hijacked")
            )
        val parsed = FleetSettingsShare.parseOffer(hostile.toString())
        assertNotNull(parsed)
        assertEquals(setOf("mediamtx_server"), parsed!!.values.keys)

        // The filter holds a second time on the way to disk, so neither the parse nor the write is
        // load-bearing on its own.
        val filed = FleetSettingsShare.profileJson(parsed, "2026-09-11T12:00:00")
            .getJSONObject("values")
        assertFalse(filed.has("drone_name"))
        assertFalse(filed.has("lb_mav_0_allow_flight"))
        assertTrue(filed.has("mediamtx_server"))
    }

    @Test
    fun `a received profile becomes a file shaped like the settings backup`() {
        val offer = FleetSettingsOffer(
            fromDeviceId = "PEER123",
            fromDroneName = "bravo",
            values = mapOf("mediamtx_server" to "10.0.0.5:8889", "webrtc_fps" to 30)
        )
        val profile = FleetSettingsShare.profileJson(offer, "2026-09-11T12:00:00")
        assertEquals("2026-09-11T12:00:00", profile.getString("savedAt"))
        assertEquals("bravo", profile.getString("droneName"))
        assertEquals("PEER123", profile.getString("sourceDeviceId"))
        assertEquals(2, profile.getJSONObject("values").length())
    }

    @Test
    fun `a resent profile overwrites its predecessor rather than accumulating`() {
        val first = FleetSettingsOffer("PEER123", "bravo", mapOf("webrtc_fps" to 30))
        val second = FleetSettingsOffer("PEER123", "bravo", mapOf("webrtc_fps" to 15))
        assertEquals(
            FleetSettingsShare.profileFileName(first),
            FleetSettingsShare.profileFileName(second)
        )
    }

    @Test
    fun `a profile file name cannot escape its directory`() {
        val hostile = FleetSettingsOffer("../../etc", "../../../passwd", mapOf("webrtc_fps" to 30))
        val name = FleetSettingsShare.profileFileName(hostile)
        assertFalse("must not contain a path separator", name.contains('/'))
        assertFalse("must not contain a parent traversal", name.contains(".."))
    }

    @Test
    fun `two aircraft sharing a name still get separate profile files`() {
        val a = FleetSettingsOffer("SERIAL_A", "scout", mapOf("webrtc_fps" to 30))
        val b = FleetSettingsOffer("SERIAL_B", "scout", mapOf("webrtc_fps" to 30))
        assertFalse(
            FleetSettingsShare.profileFileName(a) == FleetSettingsShare.profileFileName(b)
        )
    }

    @Test
    fun `a beacon is not mistaken for a settings offer`() {
        assertNull(FleetSettingsShare.parseOffer(FleetBeaconTest.sampleBeacon().toJson().toString()))
    }

    @Test
    fun `an offer with no usable values is rejected`() {
        val empty = JSONObject()
            .put("lb", FleetBeacon.MAGIC)
            .put("v", FleetBeacon.PROTOCOL_VERSION)
            .put("t", FleetSettingsShare.TYPE_SETTINGS_OFFER)
            .put("id", "PEER")
            .put("vals", JSONObject().put("not_shareable", 1))
        assertNull(FleetSettingsShare.parseOffer(empty.toString()))
    }

    @Test
    fun `the allowlist excludes every identity and flight key`() {
        val forbidden = listOf(
            "drone_name", "drone_name_user_set", "lb_mav_0_sysid", "lb_mav_0_allow_flight",
            "lb_mav_0_enabled", "lb_mav_0_host", "lb_mav_0_port", "lb_fleet_install_id"
        )
        forbidden.forEach { key ->
            assertFalse("$key must not be shareable", key in FleetSettingsShare.SHAREABLE_KEYS)
        }
    }

    /** Minimal in-memory SharedPreferences so these run without Robolectric. */
    private class FakePrefs(initial: Map<String, Any>) : SharedPreferences {
        private val store = initial.toMutableMap()

        override fun getAll(): MutableMap<String, *> = store
        override fun getString(key: String, defValue: String?): String? =
            store[key] as? String ?: defValue

        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
            defValues

        override fun getInt(key: String, defValue: Int): Int = store[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long): Long = store[key] as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = store[key] as? Float ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean =
            store[key] as? Boolean ?: defValue

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
                value?.let { pending[key] = it }
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
                store.remove(key)
                return this
            }

            override fun clear(): SharedPreferences.Editor {
                store.clear()
                return this
            }

            override fun commit(): Boolean {
                apply()
                return true
            }

            override fun apply() {
                store.putAll(pending)
                pending.clear()
            }
        }
    }
}
