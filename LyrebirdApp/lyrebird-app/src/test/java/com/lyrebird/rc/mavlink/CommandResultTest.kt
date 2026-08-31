package com.lyrebird.rc.mavlink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CommandResultTest {

    @Test
    fun mavResultMirrorsTheOutcome() {
        assertEquals(Mav.RESULT_ACCEPTED, CommandResult(MavlinkCommandOutcome.ACCEPTED).mavResult)
        assertEquals(Mav.RESULT_DENIED, CommandResult(MavlinkCommandOutcome.DENIED).mavResult)
        assertEquals(Mav.RESULT_UNSUPPORTED, CommandResult(MavlinkCommandOutcome.UNSUPPORTED).mavResult)
        assertEquals(Mav.RESULT_FAILED, CommandResult(MavlinkCommandOutcome.FAILED).mavResult)
    }

    @Test
    fun detailIsOptionalAndPreserved() {
        assertNull(CommandResult(MavlinkCommandOutcome.ACCEPTED).detail)
        assertEquals(
            "Received: zoom: 3.0",
            CommandResult(MavlinkCommandOutcome.ACCEPTED, "Received: zoom: 3.0").detail
        )
    }
}
