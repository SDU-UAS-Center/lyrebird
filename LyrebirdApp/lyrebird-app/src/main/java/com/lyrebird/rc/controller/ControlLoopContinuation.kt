package com.lyrebird.rc.controller

internal object ControlLoopContinuation {
    enum class Decision(
        val shouldContinue: Boolean,
        val shouldDisableControlLoop: Boolean
    ) {
        Continue(shouldContinue = true, shouldDisableControlLoop = false),
        Stop(shouldContinue = false, shouldDisableControlLoop = false),
        StopAndDisable(shouldContinue = false, shouldDisableControlLoop = true)
    }

    data class State(
        val controlLoopEnabled: Boolean,
        val loopId: Long,
        val currentControlLoopId: Long,
        val manualOverrideActive: Boolean,
        val timeSinceStartMs: Long,
        val virtualStickEnableGracePeriodMs: Long,
        val virtualStickEnabled: Boolean
    )

    fun decide(state: State): Decision {
        return when {
            !state.controlLoopEnabled || state.loopId != state.currentControlLoopId -> Decision.Stop
            state.manualOverrideActive -> Decision.StopAndDisable
            // enableVirtualStick() is async, so allow the control loop to start before checking SDK state.
            state.timeSinceStartMs < state.virtualStickEnableGracePeriodMs -> Decision.Continue
            // A later virtual-stick disable can be SDK/system-driven; stop the loop without latching manual override.
            !state.virtualStickEnabled -> Decision.StopAndDisable
            else -> Decision.Continue
        }
    }
}
