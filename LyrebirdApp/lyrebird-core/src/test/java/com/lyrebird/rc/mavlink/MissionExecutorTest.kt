package com.lyrebird.rc.mavlink

import org.junit.Assert.assertEquals
import org.junit.Test

class MissionExecutorTest {
    @Test
    fun `an unset or unknown preference means the onboard executor`() {
        assertEquals(MissionExecutor.ONBOARD, MissionExecutor.fromPref(null))
        assertEquals(MissionExecutor.ONBOARD, MissionExecutor.fromPref("something-else"))
    }

    @Test
    fun `each stored value selects its executor`() {
        assertEquals(MissionExecutor.ONBOARD, MissionExecutor.fromPref("onboard"))
        assertEquals(MissionExecutor.DJI_NATIVE, MissionExecutor.fromPref("dji_native"))
    }
}
