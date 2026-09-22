package com.lyrebird.rc.perception

import android.content.SharedPreferences
import android.os.SystemClock
import android.util.Log

/**
 * Stops Lyrebird's autonomous motion when the aircraft's own sensors see something in the way.
 *
 * ## Why this exists
 *
 * DJI aircraft brake for obstacles on their own, and nothing here tries to replace that. The gap
 * is narrower and specific: when Lyrebird flies its PID sequencer through virtual stick, the
 * aircraft is being flown by stick input from an app, and what the vendor's avoidance does around
 * that input is neither documented nor guaranteed across the airframes this project supports. An
 * autonomous waypoint run is also the moment nobody has their hands on the controls.
 *
 * So the guard watches the same sensors the aircraft uses and, when the stopping distance is no
 * longer there, cancels the control loop Lyrebird is running. The aircraft holds position. That is
 * the entire behaviour.
 *
 * ## What it will not do
 *
 * It does not steer around anything, does not choose a new path, does not descend or climb away,
 * and does not touch the aircraft when a human is on the sticks. It has no authority it did not
 * already have: cancelling a loop this app started is the same thing the abort endpoint does.
 *
 * It also does not react to other drones. Peer positions arrive over an unauthenticated Wi-Fi
 * beacon that is one to two beacons stale and carries GPS error at both ends, which is fine for
 * telling a pilot where to look and nowhere near good enough to stop an aircraft with. That
 * remains deliberately out of scope; the fleet mesh stays advisory.
 *
 * ## What it cannot see
 *
 * Every airframe has blind arcs, and a sector inside one reports exactly what clear air reports.
 * Thin obstacles — wires, branches, guy lines, netting — are the classic case these sensors miss
 * entirely. This guard reduces the chance of flying into something detectable. It is not a reason
 * to fly somewhere you would not have flown without it.
 */
internal object ObstacleGuard {
    private const val TAG = "LyrebirdObstacle"

    /** Master switch. Default off: it changes flight behaviour, so it is opted into. */
    const val PREF_ENABLED = "lb_obstacle_guard_enabled"

    /** Standoff kept after stopping, in metres. */
    const val PREF_MARGIN_M = "lb_obstacle_guard_margin_m"

    /**
     * How long after a brake the guard stays latched.
     *
     * Once stopped, the aircraft is hovering in front of something it can still see, so an
     * unlatched guard would re-fire on every sweep and drown the log. The latch clears on its own
     * so a genuine second approach is still caught.
     */
    const val BRAKE_LATCH_MS = 5_000L

    /** What happened, for the status readout and the flight log. */
    data class BrakeEvent(
        val reason: BrakeReason,
        val clearanceM: Double,
        val requiredM: Double,
        val bearingFromNoseDeg: Double,
        val atElapsedMs: Long,
    )

    /** Fired on the SDK's callback thread after the guard has stopped the aircraft. */
    var onBrake: ((BrakeEvent) -> Unit)? = null

    /**
     * Supplies the aircraft's current velocity and heading, or null when the runtime cannot say.
     *
     * Null is a real answer, not a value: zeroed motion reads as "hovering" to the brake
     * arithmetic, and a missing observation must not become a stationary aircraft it can brake
     * on. Set by the host before [start].
     */
    var motionProvider: (() -> Motion?)? = null

    /**
     * Whether Lyrebird is flying on its own authority right now, asked freshly on every sweep.
     *
     * The providers are asked per sweep rather than sampled at [start] so the guard follows a
     * session through a safety takeover or an override change. Null is a real answer — "cannot
     * say" — and is treated as a no: a guard that is not told who is flying must not stop a
     * flight.
     */
    var autonomousMotionProvider: (() -> Boolean?)? = null

    /** Whether the physical RC pilot has taken the sticks, asked freshly on every sweep. */
    var manualOverrideProvider: (() -> Boolean?)? = null

    /**
     * Stops the autonomous motion this guard is allowed to stop (cancels the app's control loop).
     *
     * Set by the host alongside [motionProvider]. Absent means the guard has no way to stop
     * anything, so it will not latch or report a brake it cannot perform.
     */
    var stopMotion: (() -> Unit)? = null

    /**
     * The aircraft's obstacle sensors, wired by the host before [start].
     *
     * The guard never touches the SDK for this; the port delivers converted sweeps, and the
     * V5 implementation owns the listener registration and the millimetre payload conversion.
     */
    var sensorPort: ObstacleSensorPort? = null

    /**
     * The clock the latch and the blocked arc are measured against.
     *
     * Injectable so both lifetimes can be tested without waiting for them; production reads the
     * elapsed-realtime clock, the same one the brake arithmetic timestamps with.
     */
    internal var nowMs: () -> Long = { SystemClock.elapsedRealtime() }

    /**
     * Half-angle searched around the direction of travel, mirroring [ObstacleBrake]'s
     * [ObstacleBrake.TRAVEL_ARC_HALF_ANGLE_DEG] so the blocked arc is derived from the same arc
     * the brake decision used rather than from a second constant that can drift.
     */
    private val TRAVEL_ARC_FOR_BLOCKED_DEG = ObstacleBrake.TRAVEL_ARC_HALF_ANGLE_DEG

    /** Velocity in the NED frame and heading in degrees, as the rest of the app reports them. */
    data class Motion(
        val velocityNorthMps: Double,
        val velocityEastMps: Double,
        val velocityDownMps: Double,
        val headingDeg: Double,
    )

    @Volatile
    var isRunning: Boolean = false
        private set

    /** The most recent sweep, for telemetry and diagnostics. */
    @Volatile
    var lastReading: ObstacleReading = ObstacleReading.EMPTY
        private set

    /** The most recent brake, or null if the guard has not fired this session. */
    @Volatile
    var lastBrake: BrakeEvent? = null
        private set

    /**
     * The blocked bearing arc from the most recent horizontal brake, or null when none is active.
     *
     * Recorded when the guard brakes so consumers can ask whether a later leg points into the
     * direction that was just measured as a hazard. Discriminated from [isLatched] deliberately:
     * the latch answers "is the aircraft stopped by us right now" and lasts [BRAKE_LATCH_MS]; the
     * arc answers "which direction is known bad" and lasts [BlockedArc.LOCKOUT_MS], which is
     * longer because its purpose is to outrun a ground station's retry cadence.
     */
    val blockedArc: BlockedArc?
        get() = _blockedArc?.takeIf { it.isActive(nowMs()) }

    @Volatile
    private var _blockedArc: BlockedArc? = null

    @Volatile
    private var latchedUntilElapsedMs: Long = 0L

    @Volatile
    private var marginM: Double = ObstacleBrake.DEFAULT_MARGIN_M

    /** True while the aircraft is stopped by this guard rather than by anything else. */
    val isLatched: Boolean
        get() = nowMs() < latchedUntilElapsedMs

    fun isEnabled(prefs: SharedPreferences): Boolean = prefs.getBoolean(PREF_ENABLED, false)

    /**
     * Subscribe to the aircraft's obstacle sensors.
     *
     * Safe to call repeatedly. Does nothing when the preference is off, so a device that has not
     * opted in never even registers the listener.
     */
    fun start(prefs: SharedPreferences) {
        if (isRunning) return
        if (!isEnabled(prefs)) {
            Log.i(TAG, "Obstacle guard is off")
            return
        }
        marginM = prefs.getFloat(PREF_MARGIN_M, ObstacleBrake.DEFAULT_MARGIN_M.toFloat()).toDouble()
        val sensor =
            sensorPort
                ?: run {
                    Log.w(TAG, "No obstacle sensor port is wired; guard cannot arm")
                    return
                }
        runCatching {
            sensor.start { reading -> onReading(reading) }
            isRunning = true
            Log.i(TAG, "Obstacle guard armed with a ${marginM}m standoff")
        }.onFailure { error ->
            Log.w(TAG, "Could not subscribe to obstacle data: ${error.message}")
        }
    }

    fun stop() {
        if (!isRunning) return
        runCatching { sensorPort?.stop() }
        isRunning = false
        latchedUntilElapsedMs = 0L
        lastReading = ObstacleReading.EMPTY
        Log.i(TAG, "Obstacle guard disarmed")
    }

    /** Re-read the preferences, for when the operator changes them mid-session. */
    fun refresh(prefs: SharedPreferences) {
        val wanted = isEnabled(prefs)
        if (wanted && !isRunning) {
            start(prefs)
        } else if (!wanted && isRunning) {
            stop()
        } else if (wanted) {
            marginM = prefs.getFloat(PREF_MARGIN_M, ObstacleBrake.DEFAULT_MARGIN_M.toFloat()).toDouble()
        }
    }

    /**
     * One converted sweep from the sensor port. May run on any thread; kept short and
     * allocation-light, because it fires several times a second on a device that is also encoding
     * and publishing video.
     */
    internal fun onReading(reading: ObstacleReading) {
        lastReading = reading

        if (isLatched) return
        val motion = motionProvider?.invoke() ?: return

        // The gate that matters. Only motion this app is commanding is motion this app may stop:
        // a pilot on the sticks is flying with the vendor's own avoidance and must never be
        // countermanded by a phone, and a native wayline is the flight controller's to brake.
        val decision =
            ObstacleBrake.evaluate(
                reading = reading,
                velocityNorthMps = motion.velocityNorthMps,
                velocityEastMps = motion.velocityEastMps,
                velocityDownMps = motion.velocityDownMps,
                headingDeg = motion.headingDeg,
                // Asked per sweep: the app is flying on its own authority only when it says so and
                // the pilot has not taken over. Either question unanswered means no.
                autonomousMotionActive =
                    autonomousMotionProvider?.invoke() == true &&
                        manualOverrideProvider?.invoke() == false,
                marginM = marginM,
            )
        if (!decision.shouldBrake) return

        applyBrake(decision, reading)
    }

    private fun applyBrake(
        decision: BrakeDecision,
        reading: ObstacleReading,
    ) {
        // No stopper means no brake: latching or reporting one would claim the aircraft was
        // stopped when it is still moving, and every reader of this state trusts that claim.
        val stop =
            stopMotion ?: run {
                Log.w(TAG, "Obstacle brake due, but no motion stopper is wired; not braking")
                return
            }
        latchedUntilElapsedMs = nowMs() + BRAKE_LATCH_MS
        val event =
            BrakeEvent(
                reason = decision.reason,
                clearanceM = decision.clearanceM,
                requiredM = decision.requiredM,
                bearingFromNoseDeg = decision.bearingFromNoseDeg,
                atElapsedMs = nowMs(),
            )
        lastBrake = event
        Log.w(
            TAG,
            "Stopping: ${decision.reason} clearance=${"%.1f".format(decision.clearanceM)}m " +
                "required=${"%.1f".format(decision.requiredM)}m",
        )
        // Record which direction was measured as the hazard, centred on the closest sector in the
        // arc that produced the brake rather than on the requested travel bearing: the ring is
        // sector-quantised, and the sector bearing is what a later leg is actually compared
        // against. Vertical brakes record nothing — a blocked climb says nothing about a leg.
        _blockedArc = reading
            .closestKnownBearingInArc(decision.bearingFromNoseDeg, TRAVEL_ARC_FOR_BLOCKED_DEG)
            .takeIf { it.isFinite() }
            ?.let { closest ->
                BlockedArc.fromBrake(
                    decision.copy(bearingFromNoseDeg = closest),
                    nowMs(),
                )
            } ?: BlockedArc.fromBrake(decision, nowMs())
        // Cancels the PID loop and zeroes the sticks, so the aircraft holds position. Virtual
        // stick is deliberately left enabled: dropping it would hand control back mid-air, and the
        // next command should be able to fly without re-arming anything.
        runCatching { stop() }
            .onFailure { error -> Log.e(TAG, "Could not stop the control loop: ${error.message}") }
        onBrake?.invoke(event)
    }
}
