package com.lyrebird.rc.fleet

import android.content.SharedPreferences
import org.json.JSONObject

/**
 * A settings profile one device published to the rest of the fleet.
 *
 * Carries only the keys in [FleetSettingsShare.SHAREABLE_KEYS] — see that list for why the set is
 * as small as it is. An offer is inert: receiving one produces a file, never a change.
 */
internal data class FleetSettingsOffer(
    val fromDeviceId: String,
    val fromDroneName: String,
    val values: Map<String, Any>
) {
    val size: Int get() = values.size
}

/**
 * Fleet-wide settings propagation.
 *
 * Setting up a field day means typing the same MediaMTX address, the same detection model, and
 * the same stream resolution into every RC by hand, on a touchscreen, outdoors. This lets one
 * configured device offer its profile to the others.
 *
 * Two rules shape the design, both inherited from decisions already made elsewhere in the app:
 *
 * The shared set is an allowlist, never "all preferences". Anything that identifies an aircraft
 * is excluded, because copying it is precisely the misconfiguration the conflict detector exists
 * to catch: pushing one device's drone name or MAVLink system id across the fleet would manufacture
 * a duplicate on every receiver. Anything governing flight is excluded too — return-to-home
 * altitude, height and distance limits, the flight-permission gate — because those are set per
 * aircraft against the site it is flying, and a value that is right for one is not automatically
 * right for another.
 *
 * **Receiving a profile never changes the receiving app.** It writes a file and stops. Nothing is
 * applied, and the operator is not asked to apply it either, because being asked mid-flight is
 * itself an interruption. What arrives is a profile sitting on disk beside the device's own
 * settings backup, available to be loaded later through the existing restore path, on the ground,
 * deliberately. This extends the reasoning already in `LyrebirdSettingsBackup`, which refuses to
 * restore automatically on the grounds that a recovered profile may be from another deployment: a
 * profile that arrived unauthenticated over multicast has strictly less provenance than that one.
 */
internal object FleetSettingsShare {

    const val TYPE_SETTINGS_OFFER = "settings"

    private const val KEY_MAGIC = "lb"
    private const val KEY_VERSION = "v"
    private const val KEY_TYPE = "t"
    private const val KEY_FROM_ID = "id"
    private const val KEY_FROM_NAME = "n"
    private const val KEY_VALUES = "vals"

    /**
     * Preferences that describe how the fleet talks to its ground infrastructure, and nothing else.
     *
     * Mirrors the preference key strings in `FlightDeckActivity`; they are literals here because
     * the policy about what may travel between aircraft belongs with the mesh rather than with the
     * screen that happens to own the constants.
     */
    val SHAREABLE_KEYS: Set<String> = setOf(
        "mediamtx_server",
        "webrtc_fps",
        "webrtc_resolution",
        "streaming_mode",
        "video_source",
        "detections_enabled",
        "detection_source",
        "edge_detection_enabled",
        "edge_confidence_threshold",
        "edge_model_uri",
        "edge_model_name",
        "edge_labels_uri",
        "edge_labels_name"
    )

    /**
     * Build the datagram this device would offer.
     *
     * Sets are skipped for the same reason the settings backup skips them: `SharedPreferences`
     * cannot round-trip the element type, and guessing it on the way back is worse than dropping
     * the key.
     */
    fun buildOffer(
        prefs: SharedPreferences,
        fromDeviceId: String,
        fromDroneName: String
    ): JSONObject {
        val values = JSONObject()
        SHAREABLE_KEYS.forEach { key ->
            when (val value = prefs.all[key]) {
                null -> Unit
                is Set<*> -> Unit
                else -> values.put(key, value)
            }
        }
        return JSONObject().apply {
            put(KEY_MAGIC, FleetBeacon.MAGIC)
            put(KEY_VERSION, FleetBeacon.PROTOCOL_VERSION)
            put(KEY_TYPE, TYPE_SETTINGS_OFFER)
            put(KEY_FROM_ID, fromDeviceId)
            put(KEY_FROM_NAME, fromDroneName)
            put(KEY_VALUES, values)
        }
    }

    /**
     * Parse an inbound offer, or null when it is not one this build understands.
     *
     * Keys outside [SHAREABLE_KEYS] are discarded rather than rejected wholesale, so a newer
     * sender offering a key this build does not know still delivers the rest. The allowlist is
     * enforced on receipt as well as on send: a device must not be able to write arbitrary
     * preferences into a peer by hand-crafting a datagram.
     */
    fun parseOffer(json: String): FleetSettingsOffer? = runCatching {
        val obj = JSONObject(json)
        if (obj.optString(KEY_MAGIC) != FleetBeacon.MAGIC) return null
        if (obj.optInt(KEY_VERSION) != FleetBeacon.PROTOCOL_VERSION) return null
        if (obj.optString(KEY_TYPE) != TYPE_SETTINGS_OFFER) return null
        val fromDeviceId = obj.optString(KEY_FROM_ID).trim()
        if (fromDeviceId.isEmpty()) return null
        val rawValues = obj.optJSONObject(KEY_VALUES) ?: return null

        val values = mutableMapOf<String, Any>()
        rawValues.keys().forEach { key ->
            if (key !in SHAREABLE_KEYS) return@forEach
            when (val value = rawValues.get(key)) {
                is Boolean, is String, is Int, is Long -> values[key] = value
                is Number -> values[key] = value.toFloat()
                else -> Unit
            }
        }
        if (values.isEmpty()) return null
        FleetSettingsOffer(
            fromDeviceId = fromDeviceId,
            fromDroneName = obj.optString(KEY_FROM_NAME).trim(),
            values = values
        )
    }.getOrNull()

    fun parseOffer(data: ByteArray, length: Int): FleetSettingsOffer? =
        parseOffer(String(data, 0, length, Charsets.UTF_8))

    /**
     * The JSON a received offer is filed as.
     *
     * Deliberately the same shape `LyrebirdSettingsBackup` writes — a timestamp, a drone name and
     * a `values` object — so a profile that arrived over the mesh is the same kind of artefact as
     * one this device saved itself, and whatever loads the one can load the other.
     */
    fun profileJson(offer: FleetSettingsOffer, savedAt: String): JSONObject {
        val values = JSONObject()
        // Filtered again on the way to disk. The parse already dropped anything outside the
        // allowlist, so neither step is load-bearing alone, which is the point.
        offer.values.forEach { (key, value) -> if (key in SHAREABLE_KEYS) values.put(key, value) }
        return JSONObject()
            .put(KEY_SAVED_AT, savedAt)
            .put(KEY_DRONE_NAME, offer.fromDroneName)
            .put(KEY_SOURCE_DEVICE, offer.fromDeviceId)
            // The file's own key, spelled out. The wire abbreviates to keep the datagram small;
            // the file matches LyrebirdSettingsBackup so both are the same kind of artefact.
            .put(FILE_KEY_VALUES, values)
    }

    /** Stable, filesystem-safe name for one sender's profile, so a resend replaces its predecessor. */
    fun profileFileName(offer: FleetSettingsOffer): String {
        val name = sanitize(offer.fromDroneName.ifBlank { "unnamed" })
        val device = sanitize(offer.fromDeviceId)
        return "$FILE_PREFIX$name-$device$FILE_SUFFIX"
    }

    /**
     * Reduce operator-supplied text to something safe to use as a file name.
     *
     * Drone names and device ids arrive over an unauthenticated multicast group, so they are
     * attacker-controlled text being used to build a path. Separators are replaced, and any run of
     * dots collapses to one so no `..` survives to be interpreted as a parent directory.
     */
    private fun sanitize(value: String): String =
        value.trim().lowercase()
            .replace(UNSAFE_FILENAME_CHARS, "_")
            .replace(DOT_RUN, ".")
            .trim('.')
            .ifEmpty { "unnamed" }
            .take(MAX_NAME_SEGMENT)

    private const val KEY_SAVED_AT = "savedAt"
    private const val KEY_DRONE_NAME = "droneName"
    private const val KEY_SOURCE_DEVICE = "sourceDeviceId"
    private const val FILE_KEY_VALUES = "values"
    private const val FILE_PREFIX = "fleet-"
    private const val FILE_SUFFIX = ".json"
    private const val MAX_NAME_SEGMENT = 40
    private val UNSAFE_FILENAME_CHARS = Regex("[^a-z0-9_.-]")
    private val DOT_RUN = Regex("\\.{2,}")
}
