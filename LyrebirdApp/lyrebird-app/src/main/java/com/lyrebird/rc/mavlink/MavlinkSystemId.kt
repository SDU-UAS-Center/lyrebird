package com.lyrebird.rc.mavlink

import java.util.zip.CRC32

/**
 * A stable, unique MAVLink system id per aircraft.
 *
 * QGroundControl treats two vehicles with the same system id as the same vehicle, so every
 * Lyrebird device must advertise a distinct id. The automatic id is derived from the immutable
 * aircraft serial and can be overridden explicitly by the `lb_mav_0_sysid` preference.
 *
 * Values 1..99 are reserved for explicit fleet/user assignments. Automatic ids use 100..254, so
 * an operator can provision a small fleet without competing with automatic allocation. Zero is
 * the preference value meaning automatic; 255 is reserved by MAVLink.
 */
internal object MavlinkSystemId {

    /** Configured value meaning "derive the id from the aircraft identity". */
    const val AUTO = 0

    const val MIN = 1
    const val MAX = 254
    const val MANUAL_MIN = 1
    const val MANUAL_MAX = 99
    const val AUTO_MIN = 100
    const val AUTO_MAX = 254

    /**
     * Deterministic automatic id in [AUTO_MIN]..[AUTO_MAX] for [key].
     *
     * CRC-32 is explicit about its byte representation and is stable across runtimes. The full
     * serial is the input in production; keeping this parameter generic makes the helper easy to
     * test without allowing the editable drone name to become the aircraft identity.
     */
    fun fromKey(key: String): Int {
        val canonical = key.trim().uppercase().ifEmpty { "UNKNOWN" }
        val crc = CRC32().apply { update(canonical.toByteArray(Charsets.UTF_8)) }
        return AUTO_MIN + (crc.value % (AUTO_MAX - AUTO_MIN + 1)).toInt()
    }

    fun isManual(value: Int): Boolean = value in MANUAL_MIN..MANUAL_MAX

    /**
     * Resolve the configured preference: [AUTO] derives from the immutable aircraft key; values
     * outside the explicit manual range fall back to automatic allocation.
     */
    fun resolve(configured: Int, key: String): Int =
        if (configured == AUTO || !isManual(configured)) fromKey(key) else configured
}
