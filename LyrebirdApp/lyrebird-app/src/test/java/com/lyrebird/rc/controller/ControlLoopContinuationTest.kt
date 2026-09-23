package com.lyrebird.rc.controller

import org.junit.Assert.assertEquals
import org.junit.Test

class ControlLoopContinuationTest {
    @Test
    fun stopsWithoutDisablingWhenLoopWasAlreadyCancelled() {
        val decision = decide(
            controlLoopEnabled = false,
            loopId = 3L,
            currentControlLoopId = 3L,
            manualOverrideActive = false,
            timeSinceStartMs = 5_000L,
            virtualStickEnableGracePeriodMs = 1_000L,
            virtualStickEnabled = true
        )

        assertEquals(ControlLoopContinuation.Decision.Stop, decision)
    }

    @Test
    fun stopsWithoutDisablingWhenLoopIdIsStale() {
        val decision = decide(
            controlLoopEnabled = true,
            loopId = 2L,
            currentControlLoopId = 3L,
            manualOverrideActive = false,
            timeSinceStartMs = 5_000L,
            virtualStickEnableGracePeriodMs = 1_000L,
            virtualStickEnabled = true
        )

        assertEquals(ControlLoopContinuation.Decision.Stop, decision)
    }

    @Test
    fun stopsAndDisablesWhenManualOverrideIsActive() {
        val decision = decide(
            controlLoopEnabled = true,
            loopId = 3L,
            currentControlLoopId = 3L,
            manualOverrideActive = true,
            timeSinceStartMs = 5_000L,
            virtualStickEnableGracePeriodMs = 1_000L,
            virtualStickEnabled = true
        )

        assertEquals(ControlLoopContinuation.Decision.StopAndDisable, decision)
    }

    @Test
    fun continuesDuringVirtualStickEnableGracePeriod() {
        val decision = decide(
            controlLoopEnabled = true,
            loopId = 3L,
            currentControlLoopId = 3L,
            manualOverrideActive = false,
            timeSinceStartMs = 500L,
            virtualStickEnableGracePeriodMs = 1_000L,
            virtualStickEnabled = false
        )

        assertEquals(ControlLoopContinuation.Decision.Continue, decision)
    }

    @Test
    fun stopsAndDisablesWhenVirtualStickIsOffAfterGracePeriod() {
        val decision = decide(
            controlLoopEnabled = true,
            loopId = 3L,
            currentControlLoopId = 3L,
            manualOverrideActive = false,
            timeSinceStartMs = 1_500L,
            virtualStickEnableGracePeriodMs = 1_000L,
            virtualStickEnabled = false
        )

        assertEquals(ControlLoopContinuation.Decision.StopAndDisable, decision)
    }

    @Test
    fun continuesWhenLoopAndVirtualStickAreValid() {
        val decision = decide(
            controlLoopEnabled = true,
            loopId = 3L,
            currentControlLoopId = 3L,
            manualOverrideActive = false,
            timeSinceStartMs = 1_500L,
            virtualStickEnableGracePeriodMs = 1_000L,
            virtualStickEnabled = true
        )

        assertEquals(ControlLoopContinuation.Decision.Continue, decision)
    }

    private fun decide(
        controlLoopEnabled: Boolean,
        loopId: Long,
        currentControlLoopId: Long,
        manualOverrideActive: Boolean,
        timeSinceStartMs: Long,
        virtualStickEnableGracePeriodMs: Long,
        virtualStickEnabled: Boolean
    ): ControlLoopContinuation.Decision {
        return ControlLoopContinuation.decide(
            ControlLoopContinuation.State(
                controlLoopEnabled = controlLoopEnabled,
                loopId = loopId,
                currentControlLoopId = currentControlLoopId,
                manualOverrideActive = manualOverrideActive,
                timeSinceStartMs = timeSinceStartMs,
                virtualStickEnableGracePeriodMs = virtualStickEnableGracePeriodMs,
                virtualStickEnabled = virtualStickEnabled
            )
        )
    }
}