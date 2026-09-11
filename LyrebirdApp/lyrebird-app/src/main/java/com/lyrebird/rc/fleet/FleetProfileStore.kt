package com.lyrebird.rc.fleet

import android.util.Log
import com.lyrebird.rc.logger.FlightLogStorage
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Where settings profiles received from other aircraft are filed.
 *
 * Receiving a profile is a filesystem operation and nothing more. The mesh's job is to get one
 * device's configuration onto the other phones so it is there when someone wants it; deciding to
 * use it is a separate, deliberate, on-the-ground act that goes through the app's existing
 * settings restore. Nothing in this file touches a live preference.
 *
 * Profiles land in `Lyrebird/Config/FleetProfiles/`, beside the device's own settings backup and
 * per-drone profiles, so they survive an uninstall the way those do and so an operator with the
 * card mounted finds all three kinds of configuration in one place.
 */
internal object FleetProfileStore {

    private const val TAG = "LyrebirdFleet"
    private const val DIRECTORY = "FleetProfiles"

    /** One record of a profile this device has filed, for the fleet page to list. */
    data class StoredProfile(
        val fromDroneName: String,
        val fromDeviceId: String,
        val keyCount: Int,
        val receivedAtMs: Long,
        val file: File
    )

    private val received = LinkedHashMap<String, StoredProfile>()

    /** The directory profiles are written to, or null when no durable storage is available. */
    fun directory(): File? = FlightLogStorage.resolveConfigDir()?.let { config ->
        File(config, DIRECTORY).also { dir -> if (!dir.isDirectory) dir.mkdirs() }
    }

    /**
     * File an offer. Returns the record, or null when there is nowhere durable to write.
     *
     * A resend from the same device replaces its previous file rather than accumulating copies:
     * the useful thing is that aircraft's current configuration, not its history.
     */
    @Synchronized
    fun store(offer: FleetSettingsOffer, nowMs: Long = System.currentTimeMillis()): StoredProfile? {
        val dir = directory() ?: run {
            Log.w(TAG, "No durable storage for the profile offered by ${offer.fromDroneName}")
            return null
        }
        return runCatching {
            val savedAt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date(nowMs))
            val target = File(dir, FleetSettingsShare.profileFileName(offer))
            target.writeText(FleetSettingsShare.profileJson(offer, savedAt).toString(2))
            val record = StoredProfile(
                fromDroneName = offer.fromDroneName,
                fromDeviceId = offer.fromDeviceId,
                keyCount = offer.size,
                receivedAtMs = nowMs,
                file = target
            )
            received[offer.fromDeviceId] = record
            Log.i(TAG, "Filed a settings profile from ${offer.fromDroneName} at ${target.absolutePath}")
            record
        }.onFailure { error ->
            Log.w(TAG, "Could not file a settings profile: ${error.message}")
        }.getOrNull()
    }

    /** Profiles filed during this session, newest last. */
    @Synchronized
    fun storedThisSession(): List<StoredProfile> = received.values.toList()

    @Synchronized
    fun clearSessionRecords() {
        received.clear()
    }
}
