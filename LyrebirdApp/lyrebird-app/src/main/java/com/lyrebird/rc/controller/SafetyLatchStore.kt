package com.lyrebird.rc.controller

import android.content.SharedPreferences

/**
 * The Safety latch in the app's private preferences, keyed by aircraft serial.
 *
 * Private preferences are the whole of the durability policy: they survive a crash, a kill, and an
 * Android restart, and clearing app data or reinstalling the app removes them. That is the bypass
 * an operator reaches for when an aircraft has to be flown and the Safety Computer is not there to
 * release it — deliberate, and not something the app can do for them while it is running.
 *
 * Writes use `commit()`. `apply()` returns before the value reaches disk, and this is exactly the
 * write where the difference matters: the process that took control is allowed to die at any
 * moment, including immediately after a takeover, and the whole point is that the next start finds
 * it. The cost is a few milliseconds on the request thread that performed the takeover, which is
 * once per flight.
 */
internal class SafetyLatchStore(
    private val preferences: SharedPreferences,
) : AuthorityLatch.LatchStore {
    override fun read(aircraftSerial: String): ControlAuthority.Authority? {
        val stored = preferences.getString(keyFor(aircraftSerial), null) ?: return null
        return runCatching { ControlAuthority.Authority.valueOf(stored) }.getOrElse {
            // A record this build cannot read is not evidence that the Pilot holds control.
            // Failing towards SAFETY means the worst case is a release from the Safety Computer;
            // failing the other way would hand control back on the strength of a parse error.
            ControlAuthority.Authority.SAFETY
        }
    }

    override fun write(
        aircraftSerial: String,
        authority: ControlAuthority.Authority,
    ) {
        preferences.edit().putString(keyFor(aircraftSerial), authority.name).commit()
    }

    private fun keyFor(aircraftSerial: String): String = "$PREF_PREFIX$aircraftSerial"

    companion object {
        /**
         * One key per aircraft, deliberately not [DroneSettingsProfiles] profile keys.
         *
         * Profile keys move between aircraft when the settings profile is swapped, and the latch
         * has to survive the window before a profile is applied — when the aircraft is connected
         * but its settings have not been restored yet.
         */
        const val PREF_PREFIX = "safety_authority_latch."
    }
}
