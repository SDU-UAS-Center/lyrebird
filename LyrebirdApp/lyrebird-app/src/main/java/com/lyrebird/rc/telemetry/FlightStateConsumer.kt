package com.lyrebird.rc.telemetry

/** A pending scheduled action; cancelling more than once is harmless. */
internal fun interface ScheduledAction {
    fun cancel()
}

/** The delayed work the consumer needs; the wiring supplies the app's main handler. */
internal fun interface FlightStateScheduler {
    fun postDelayed(
        delayMs: Long,
        action: () -> Unit,
    ): ScheduledAction
}

/**
 * What the runtime does about a flight-state change, implemented per SDK generation.
 *
 * Everything here used to run inside the FlightDeck screen's observer, which meant the flight log
 * opened only while a screen existed to receive the event. The consumer calls these from the
 * process runtime's subscription instead; `onAirborneChanged` keeps the controller's airborne
 * latch in step so takeoff gating and the idle detector do not depend on this screen either.
 */
internal interface FlightStateEffects {
    /** Whether this generation's controller is already returning home. */
    fun isReturningHome(): Boolean

    fun onAirborneChanged(flying: Boolean)

    fun onTakeoff()

    fun onLanded()

    /** The aircraft started returning home without this app asking it to (RC switch). */
    fun onRemoteReturnToHome()
}

/**
 * Flight-state policy, shared by every backend.
 *
 * Two transitions have real work behind them: takeoff opens the flight-log session and starts
 * onboard detection when that source is selected; landing closes the session after a grace
 * period, because a brief mid-air telemetry glitch must not end the log of a flying aircraft.
 * Return-to-home is detected from the mode alone: our own RTH command already knows it is
 * returning, so only a mode that arrived without that command is the RC's doing.
 *
 * Safe from SDK callback threads: transitions are synchronized, and the grace action re-checks
 * the airborne latch rather than trusting that nothing happened in between.
 */
internal class FlightStateConsumer(
    private val effects: FlightStateEffects,
    private val scheduler: FlightStateScheduler,
    private val landingGraceMs: Long = 10_000L,
) : AircraftTelemetryListener {
    private var airborne = false
    private var landingAction: ScheduledAction? = null

    @Synchronized
    override fun onFlyingChanged(flying: Boolean) {
        val wasAirborne = airborne
        airborne = flying
        effects.onAirborneChanged(flying)
        when {
            !wasAirborne && flying -> {
                // A takeoff during a pending landing grace is a telemetry glitch, not a new
                // flight: the session never closed, so it must not be opened a second time.
                val stillInLandingGrace = landingAction != null
                landingAction?.cancel()
                landingAction = null
                if (!stillInLandingGrace) effects.onTakeoff()
            }

            wasAirborne && !flying -> {
                landingAction?.cancel()
                landingAction =
                    scheduler.postDelayed(landingGraceMs) {
                        val stillLanded =
                            synchronized(this) {
                                landingAction = null
                                !airborne
                            }
                        if (stillLanded) effects.onLanded()
                    }
            }
        }
    }

    @Synchronized
    override fun onFlightModeChanged(mode: AircraftFlightMode) {
        if (mode == AircraftFlightMode.GO_HOME && !effects.isReturningHome()) {
            effects.onRemoteReturnToHome()
        }
    }
}
