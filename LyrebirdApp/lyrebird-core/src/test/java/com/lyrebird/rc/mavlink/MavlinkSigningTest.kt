package com.lyrebird.rc.mavlink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Signature verification, against frames produced by pymavlink's own signer.
 *
 * The frames below were signed by `pymavlink.dialects.v20.common` with the key `00 01 02 … 1f`,
 * so these assertions check this implementation against the reference one rather than against my
 * reading of the specification.
 */
class MavlinkSigningTest {

    private val key = ByteArray(32) { it.toByte() }

    @Before
    fun forgetReplayHistory() = MavlinkSigning.reset()

    @Test
    fun aFrameSignedWithTheConfiguredKeyIsTrusted() {
        assertEquals(MavlinkSigning.Origin.TRUSTED, originOf(VALID_FIRST))
    }

    @Test
    fun aLaterFrameFromTheSameSenderIsStillTrusted() {
        assertEquals(MavlinkSigning.Origin.TRUSTED, originOf(VALID_FIRST))
        assertEquals(MavlinkSigning.Origin.TRUSTED, originOf(VALID_SECOND))
    }

    @Test
    fun areplayedFrameIsRefused() {
        assertEquals(MavlinkSigning.Origin.TRUSTED, originOf(VALID_SECOND))
        // Its signature is valid by construction — the timestamp is the only thing standing
        // between a recorded "land now" and it being obeyed a second time.
        assertEquals(MavlinkSigning.Origin.REJECTED, originOf(VALID_FIRST))
    }

    @Test
    fun atamperedSignatureIsRefusedRatherThanTreatedAsUnsigned() {
        // Downgrading it would let an attacker who cannot forge a signature simply strip it.
        assertEquals(MavlinkSigning.Origin.REJECTED, originOf(TAMPERED))
    }

    @Test
    fun anUnsignedFrameIsThePilot() {
        assertEquals(MavlinkSigning.Origin.UNSIGNED, originOf(UNSIGNED))
    }

    @Test
    fun withNoKeyConfiguredEvenASignedFrameIsUnsigned() {
        // Refusing it would make a ground station with signing switched on unable to talk to an
        // aircraft that does not care about signing.
        val data = bytes(VALID_FIRST)
        assertEquals(
            MavlinkSigning.Origin.UNSIGNED,
            MavlinkSigning.originOf(data, data.size, key = null)
        )
    }

    @Test
    fun aMalformedKeyReadsAsNoKeyRatherThanAsAnError() {
        // A typo must leave the aircraft where it was, not refusing the computer it was meant to
        // trust.
        assertNull(MavlinkSigning.parseKey("not-hex"))
        assertNull(MavlinkSigning.parseKey("00112233"))
        assertNull(MavlinkSigning.parseKey(null))
        assertEquals(32, MavlinkSigning.parseKey("00".repeat(32))!!.size)
    }

    private fun originOf(hex: String): MavlinkSigning.Origin {
        val data = bytes(hex)
        return MavlinkSigning.originOf(data, data.size, key)
    }

    private fun bytes(hex: String) =
        ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private companion object {
        const val VALID_FIRST = "fd20010000ffbe4c00000000803f0000000000000000000000000000000000000000000000009001010149d00040420f00000083b453f4492b"
        const val VALID_SECOND = "fd20010001ffbe4c00000000803f00000000000000000000000000000000000000000000000090010101a79a0041420f000000638d21c1617a"
        const val TAMPERED = "fd20010001ffbe4c00000000803f00000000000000000000000000000000000000000000000090010101a79a0041420f000000638d21c16185"
        const val UNSIGNED = "fd20000000ffbe4c00000000803f000000000000000000000000000000000000000000000000900101019e4e"
    }
}
