package com.lyrebird.rc.controller

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The safety-takeover hook, without an SDK.
 *
 * [ControlAuthority] used to call the V5 controller directly, which is why the policy object
 * compiled only against the SDK flavor at all. The host now installs what "stop and hold" means;
 * this proves the hook fires exactly on the latched takeover, that later Safety commands do not
 * re-fire it, and that the latch still only returns through a Safety release. Everything is
 * restored in `finally`, because the latch is process-wide and other suites depend on the
 * Pilot-held default.
 */
class ControlAuthorityTakeoverTest {
    @Test
    fun `the first safety command latches, fires the handler once, and blocks the pilot`() {
        var takeovers = 0
        ControlAuthority.takeoverHandler = { takeovers++ }
        try {
            assertTrue(ControlAuthority.authorizeControlCommand(ControlAuthority.Source.SAFETY))
            assertEquals(1, takeovers)

            // Still latched: the Pilot is refused and the handler does not run again.
            assertFalse(ControlAuthority.authorizeControlCommand(ControlAuthority.Source.PILOT))
            assertTrue(ControlAuthority.authorizeControlCommand(ControlAuthority.Source.SAFETY))
            assertEquals(1, takeovers)

            assertTrue(ControlAuthority.releaseSafetyControl(ControlAuthority.Source.SAFETY))
            assertTrue(ControlAuthority.authorizeControlCommand(ControlAuthority.Source.PILOT))
        } finally {
            ControlAuthority.releaseSafetyControl(ControlAuthority.Source.SAFETY)
            ControlAuthority.takeoverHandler = null
        }
    }

    @Test
    fun `a takeover without an installed handler still latches and releases`() {
        ControlAuthority.takeoverHandler = null
        try {
            assertTrue(ControlAuthority.authorizeControlCommand(ControlAuthority.Source.SAFETY))
            assertFalse(ControlAuthority.authorizeControlCommand(ControlAuthority.Source.PILOT))
        } finally {
            assertTrue(ControlAuthority.releaseSafetyControl(ControlAuthority.Source.SAFETY))
        }
    }
}
