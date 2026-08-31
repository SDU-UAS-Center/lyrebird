package com.lyrebird.rc.mavlink

import java.security.MessageDigest

/**
 * MAVLink 2 packet-signature verification.
 *
 * Lyrebird has always had two kinds of caller — the Pilot and the Safety Computer — and on the
 * HTTP surface they are told apart by an `X-Safety-Token` header. MAVLink has no headers, and
 * until now signed frames were parsed with their signature *ignored*, which meant a signature
 * proved nothing: anyone could set the signed bit and be believed exactly as much as anyone else.
 *
 * This makes the signature mean something. A frame signed with the configured key is the Safety
 * Computer; anything unsigned is the Pilot, which is what every frame was treated as before, so
 * an installation that configures no key behaves exactly as it did.
 *
 * The one property worth stating plainly: an *invalid* signature is refused rather than downgraded
 * to Pilot. Accepting it as unsigned traffic would let an attacker who cannot forge a signature
 * simply strip it, and a security check that can be skipped by removing it is not a check.
 */
internal object MavlinkSigning {

    /** What a frame's signature says about who sent it. */
    enum class Origin {
        /** No signature. Treated as the Pilot, which is the pre-signing behaviour. */
        UNSIGNED,

        /** Signed with the configured key: the Safety Computer. */
        TRUSTED,

        /** Signed, but not with the configured key, or replayed. Refused outright. */
        REJECTED
    }

    /**
     * Highest signature timestamp seen per (system, component, link), for replay protection.
     *
     * MAVLink's timestamp is 48 bits of 10-microsecond ticks since 2015, and the rule is simply
     * that it must advance. Keyed by link as well as sender because the same Safety Computer may
     * legitimately be reachable over two links whose timestamps are not in lockstep.
     */
    private val lastTimestamps = HashMap<Int, Long>()

    /**
     * Who sent this frame.
     *
     * [key] is the 32-byte shared secret, or null when signing is not configured — in which case
     * even a signed frame is accepted as [Origin.UNSIGNED], because refusing it would make a
     * ground station with signing switched on unable to talk to an aircraft that does not care.
     */
    @Synchronized
    fun originOf(data: ByteArray, length: Int, key: ByteArray?): Origin {
        if (length < HEADER_BYTES + CHECKSUM_BYTES) return Origin.REJECTED
        val incompatFlags = data[2].toInt() and 0xFF
        if (incompatFlags and INCOMPAT_SIGNED == 0) return Origin.UNSIGNED
        if (key == null) return Origin.UNSIGNED

        val payloadLength = data[1].toInt() and 0xFF
        val signatureStart = HEADER_BYTES + payloadLength + CHECKSUM_BYTES
        if (length < signatureStart + SIGNATURE_BYTES) return Origin.REJECTED

        val linkId = data[signatureStart].toInt() and 0xFF
        val timestamp = readTimestamp(data, signatureStart + 1)

        // Everything up to and including the checksum is signed, plus the link id and timestamp.
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(key)
        digest.update(data, 0, signatureStart)
        digest.update(data, signatureStart, TIMESTAMP_BYTES + 1)
        val expected = digest.digest()

        var matches = true
        for (i in 0 until SIGNATURE_TAIL_BYTES) {
            // Constant work regardless of where the first difference is: comparing a signature
            // with an early return leaks, through timing, how much of a guess was correct.
            if (expected[i] != data[signatureStart + 1 + TIMESTAMP_BYTES + i]) matches = false
        }
        if (!matches) return Origin.REJECTED

        val senderKey = ((data[5].toInt() and 0xFF) shl 16) or
            ((data[6].toInt() and 0xFF) shl 8) or linkId
        val previous = lastTimestamps[senderKey]
        if (previous != null && timestamp <= previous) {
            // A replayed frame carries a valid signature by construction, so the timestamp is the
            // only thing standing between a recorded "land now" and it being obeyed twice.
            return Origin.REJECTED
        }
        lastTimestamps[senderKey] = timestamp
        return Origin.TRUSTED
    }

    /** Forget every remembered timestamp. Used when the key changes or the endpoint restarts. */
    @Synchronized
    fun reset() = lastTimestamps.clear()

    /**
     * Parse a signing key written as 64 hex characters, or null when it is absent or malformed.
     *
     * Malformed is deliberately the same as absent rather than an error: a typo in the key must
     * not leave the aircraft refusing the Safety Computer it was configured to trust, it must
     * leave it in the un-configured state it was in before anyone tried.
     */
    fun parseKey(hex: String?): ByteArray? {
        val trimmed = hex?.trim().orEmpty()
        if (trimmed.length != KEY_BYTES * 2) return null
        return runCatching {
            ByteArray(KEY_BYTES) { trimmed.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        }.getOrNull()
    }

    private fun readTimestamp(data: ByteArray, offset: Int): Long {
        var value = 0L
        for (i in 0 until TIMESTAMP_BYTES) {
            value = value or ((data[offset + i].toLong() and 0xFF) shl (8 * i))
        }
        return value
    }

    private const val HEADER_BYTES = 10
    private const val CHECKSUM_BYTES = 2
    private const val INCOMPAT_SIGNED = 0x01
    private const val SIGNATURE_BYTES = 13
    private const val TIMESTAMP_BYTES = 6

    /** The signature itself is the first six bytes of the sha256. */
    private const val SIGNATURE_TAIL_BYTES = 6

    const val KEY_BYTES = 32
}
