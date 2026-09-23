package com.lyrebird.rc.telemetry

/** Tracks aircraft connection generations so late callbacks cannot be treated as a new session. */
class ConnectionGeneration {
    data class State(
        val connected: Boolean,
        val generation: Long,
    )

    private var state = State(connected = false, generation = 0L)

    fun update(connected: Boolean): State {
        if (state.connected == connected) return state
        state =
            State(
                connected = connected,
                generation = if (connected) state.generation + 1 else state.generation,
            )
        return state
    }

    fun current(): State = state
}
