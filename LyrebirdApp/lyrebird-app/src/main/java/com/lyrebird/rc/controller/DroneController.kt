package com.lyrebird.rc.controller

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.lyrebird.rc.DroneControlProfiles
import com.lyrebird.rc.models.BasicAircraftControlVM
import com.lyrebird.rc.models.VirtualStickVM
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.aircraft.virtualstick.Stick
import dji.sdk.keyvalue.value.common.EmptyMsg
import dji.sdk.keyvalue.value.flightcontroller.FlightCoordinateSystem
import dji.sdk.keyvalue.value.flightcontroller.RollPitchControlMode
import dji.sdk.keyvalue.value.flightcontroller.VerticalControlMode
import dji.sdk.keyvalue.value.flightcontroller.VirtualStickFlightControlParam
import dji.sdk.keyvalue.value.flightcontroller.YawControlMode
import com.lyrebird.rc.util.ToastUtils
import dji.sdk.keyvalue.key.AirLinkKey
import dji.sdk.keyvalue.key.DJIKey
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.RemoteControllerKey
import dji.sdk.keyvalue.value.airlink.FrequencyBand
import dji.sdk.keyvalue.value.remotecontroller.ControlMode
import dji.sdk.keyvalue.value.remotecontroller.PairingState
import dji.v5.et.action
import dji.sdk.keyvalue.value.common.LocationCoordinate3D
import dji.v5.et.create
import dji.v5.et.listen
import dji.v5.et.get
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import com.dji.wpmzsdk.common.data.Template
import com.dji.wpmzsdk.manager.WPMZManager
import com.lyrebird.rc.utils.wpml.WaypointInfoModel
import dji.v5.manager.aircraft.waypoint3.WaypointMissionManager
import dji.v5.utils.common.ContextUtil
import dji.sdk.wpmz.value.mission.WaylineActionGroup
import dji.sdk.wpmz.value.mission.WaylineActionInfo
import dji.sdk.wpmz.value.mission.WaylineActionNodeList
import dji.sdk.wpmz.value.mission.WaylineActionTreeNode
import dji.sdk.wpmz.value.mission.WaylineActionTrigger
import dji.sdk.wpmz.value.mission.WaylineActionTriggerType
import dji.sdk.wpmz.value.mission.WaylineActionType
import dji.sdk.wpmz.value.mission.WaylineActionsRelationType
import dji.sdk.wpmz.value.mission.WaylineExitOnRCLostAction
import dji.sdk.wpmz.value.mission.WaylineFinishedAction
import dji.sdk.wpmz.value.mission.WaylineGimbalActuatorRotateMode
import dji.sdk.wpmz.value.mission.WaylineMission
import dji.sdk.wpmz.value.mission.WaylineMissionConfig
import dji.sdk.wpmz.value.mission.ActionGimbalRotateParam
import dji.v5.et.set
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile


object DroneController {

    private var basicAircraftControlVM: BasicAircraftControlVM? = null
    var virtualStickVM: VirtualStickVM? = null

    // ==================== Manual Override System ====================
    // When true, the pilot has taken manual control via RC sticks.
    // This flag latches ON automatically when RC stick input exceeds the deadzone,
    // and only clears when the user explicitly deactivates it (via checkbox or HTTP).
    // While active, all autonomous HTTP commands (waypoints, trajectories, etc.) are rejected.
    @Volatile
    var isManualOverrideActive = false
        private set

    // RC stick deadzone threshold [0..660]. DJI RC sticks report ±660.
    // 200 ≈ 30 % deflection — requires a clear, deliberate push to activate
    // override, making accidental triggering from calibration drift or small
    // incidental touches virtually impossible.
    const val RC_STICK_DEADZONE = 200

    // Waypoint acceptance thresholds — shared across all control loops
    const val WP_ACCEPT_DISTANCE_M = 1.5
    const val WP_ACCEPT_DISTANCE_M_HOLD_HEADING = 0.5// horizontal distance in meters
    const val WP_ACCEPT_ALTITUDE_M = 0.5    // vertical error in meters
    const val WP_ACCEPT_YAW_DEG = 4.0       // yaw error in degrees

    /**
     * Consecutive time inside the acceptance box before an arrival is believed, when the plan
     * does not say. Three control ticks: enough to reject a single noisy fix, short enough that
     * it is not a loiter.
     */
    const val DEFAULT_ARRIVAL_DWELL_MS = 300L

    // Arrival box for flyToWaypointHoldHeading: tighter horizontally than the nose-forward
    // controller, which brakes into WP_ACCEPT_DISTANCE_M and then rotates to the final heading.
    private val HOLD_HEADING_ACCEPTANCE = WaypointControl.Acceptance(
        distanceMeters = WP_ACCEPT_DISTANCE_M_HOLD_HEADING,
        yawDegrees = WP_ACCEPT_YAW_DEG,
        altitudeMeters = WP_ACCEPT_ALTITUDE_M
    )

    /** Arrival box for flyToWaypointNoseForward, which brakes into the wider radius first. */
    private val NOSE_FORWARD_ACCEPTANCE = WaypointControl.Acceptance(
        distanceMeters = WP_ACCEPT_DISTANCE_M,
        yawDegrees = WP_ACCEPT_YAW_DEG,
        altitudeMeters = WP_ACCEPT_ALTITUDE_M
    )

    /**
     * The acceptance box for this waypoint: the plan's radius when it gave one, else the
     * controller's own.
     *
     * Only the radius is taken from the plan. Altitude and yaw tolerances stay the controller's,
     * because MAVLink's acceptance radius is a sphere around the position and says nothing about
     * how precisely the aircraft should be pointing when it gets there.
     */
    private fun acceptanceFor(
        target: WaypointTarget,
        fallback: WaypointControl.Acceptance
    ): WaypointControl.Acceptance {
        val radius = target.acceptanceRadiusM
        return if (radius != null && radius > 0.0) fallback.copy(distanceMeters = radius) else fallback
    }

    /**
     * How long the aircraft must hold inside the box before this waypoint counts as reached.
     *
     * A pass-through leg wants none: the plan asked to fly through it, and waiting there would
     * turn a trajectory into a series of stops. Anywhere else gets the plan's hold time, or a
     * short default that exists to filter a single noisy GPS sample rather than to loiter.
     */
    private fun dwellMsFor(target: WaypointTarget): Long = when {
        target.passThrough -> 0L
        target.holdSeconds > 0.0 -> (target.holdSeconds * 1000).toLong()
        else -> DEFAULT_ARRIVAL_DWELL_MS
    }

    private fun distancePidKp(): Double = DroneControlProfiles.activeProfile().distanceKp
    private fun distancePidKi(): Double = DroneControlProfiles.activeProfile().distanceKi
    private fun distancePidKd(): Double = DroneControlProfiles.activeProfile().distanceKd
    private fun yawPidKp(): Double = DroneControlProfiles.activeProfile().yawKp
    private fun maxYawRateDegS(): Double = DroneControlProfiles.activeProfile().maxYawRateDegS
    private fun waypointPidOutputLimit(): Double = DroneControlProfiles.activeProfile().maxHorizontalSpeedMps
    private fun maxHorizontalAccelMps2(): Double = DroneControlProfiles.activeProfile().maxHorizontalAccelMps2

    /** Metres per second of radius correction per metre of radius error, while orbiting. */
    private const val ORBIT_RADIAL_GAIN = 0.5

    /**
     * Cap on the radius correction, so joining an orbit from outside it is a curve.
     *
     * Without a cap the correction is proportional to how far away the aircraft is, and an orbit
     * commanded from a few hundred metres away would begin with a full-speed run at the centre.
     */
    private const val ORBIT_MAX_RADIAL_MPS = 3.0

    // Listener interface so the UI can react to automatic activation
    interface ManualOverrideListener {
        fun onManualOverrideActivated()
    }
    var manualOverrideListener: ManualOverrideListener? = null

    /**
     * Called when RC stick input exceeds the deadzone during autonomous flight.
     * Activates the manual override latch and kills any running control loops.
     */
    fun activateManualOverride() {
        if (!isManualOverrideActive) {
            isManualOverrideActive = true
            cancelActiveControlLoop()
            virtualStickVM?.disableVirtualStick(object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() { /* no-op */ }
                override fun onFailure(error: IDJIError) { /* no-op */ }
            })
            setDroneStatus(DroneStatus.MANUAL_OVERRIDE)
            ToastUtils.showToast("⚠ MANUAL OVERRIDE ACTIVE — autonomous commands blocked")
            manualOverrideListener?.onManualOverrideActivated()
        }
    }

    /**
     * Called ONLY by the user pressing the deactivate button/checkbox.
     * Clears the manual override latch so autonomous commands work again.
     */
    fun deactivateManualOverride() {
        isManualOverrideActive = false
        setDroneStatus(DroneStatus.IDLE)
        ToastUtils.showToast("Manual override cleared — autonomous commands enabled")
    }

    /**
     * Check if an autonomous command should be allowed to execute.
     * Returns true if the command should be REJECTED (manual override is active).
     */
    fun shouldRejectAutonomousCommand(commandName: String = ""): Boolean {
        if (isManualOverrideActive) {
            val msg = if (commandName.isNotEmpty())
                "Command '$commandName' rejected — manual override active"
            else
                "Autonomous command rejected — manual override active"
            ToastUtils.showToast(msg)
            return true
        }
        return false
    }
    // ==================== End Manual Override ====================

    /**
     * Called by [ControlAuthority] when the Safety Computer seizes control.
     * Kills any autonomous control loop the Pilot Computer started and drops virtual stick so
     * the aircraft holds position until the Safety Computer issues its own commands.
     *
     * This does NOT latch manual override: Pilot/Safety authority is a separate, software axis
     * from the physical RC manual-override latch.
     */
    fun onSafetyTakeover() {
        cancelActiveControlLoop()
        virtualStickVM?.disableVirtualStick(object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() { /* no-op */ }
            override fun onFailure(error: IDJIError) { /* no-op */ }
        })
    }

    // ==================== Drone Status ====================
    /**
     * High-level operational state of the drone, derived from app-side command tracking.
     * The UI layer can also upgrade IDLE → HOVERING using FC telemetry (isFlying key).
     */
    enum class DroneStatus {
        IDLE, TAKING_OFF, HOVERING, NAVIGATING, LANDING, RETURNING_HOME, MANUAL_OVERRIDE, ABORTING,
        /** Flying an uploaded plan on DJI's own wayline engine — see MavlinkMissionSequencer.startNative. */
        MISSION
    }

    interface DroneStatusListener {
        fun onDroneStatusChanged(status: DroneStatus)
    }
    var droneStatusListener: DroneStatusListener? = null

    @Volatile
    var droneStatus: DroneStatus = DroneStatus.IDLE
        private set

    private val statusResetHandler = Handler(Looper.getMainLooper())

    private fun setDroneStatus(status: DroneStatus) {
        droneStatus = status
        droneStatusListener?.onDroneStatusChanged(status)
    }

    /** Marks a DJI-native wayline mission as flying — see [DroneStatus.MISSION]. */
    fun markMissionActive() = setDroneStatus(DroneStatus.MISSION)

    /**
     * Clears [DroneStatus.MISSION] back to idle once the wayline mission ends, but only if
     * nothing else moved the status on since — a real manual override mid-mission must stick
     * until the operator clears it, not be overwritten by the mission's own completion.
     */
    fun clearMissionActiveIfStillSet() {
        if (droneStatus == DroneStatus.MISSION) setDroneStatus(DroneStatus.IDLE)
    }
    // ==================== End Drone Status ====================

    fun init(basicVM: BasicAircraftControlVM, stickVM: VirtualStickVM ) {
        basicAircraftControlVM = basicVM
        virtualStickVM = stickVM
    }

    fun destroy() {
        statusResetHandler.removeCallbacksAndMessages(null)
        manualOverrideListener = null
        droneStatusListener = null
        basicAircraftControlVM = null
        virtualStickVM = null
    }

    //WAYPOINT MISSION
    private val location3DKey: DJIKey<LocationCoordinate3D> =
            FlightControllerKey.KeyAircraftLocation3D.create()

    private fun getLocation3D(): LocationCoordinate3D {
        return location3DKey.get(LocationCoordinate3D(0.0, 0.0, 0.0))
    }

    private val compassHeadKey: DJIKey<Double> = FlightControllerKey.KeyCompassHeading.create()
    private fun getHeading(): Double {
        return (compassHeadKey.get(0.0)).toDouble()
    }

    @Volatile private var _isWaypointReached = false
    // Monotonic id assigned to every waypoint navigation request (fresh start OR hot-swap).
    // Echoed in telemetry as "waypointSeq" so a ground station can tell whether a streamed
    // "waypointReached" refers to the target it just commanded or a stale latched value from
    // the previous one. Incremented from the HTTP server thread, read from the telemetry thread.
    private val _waypointSeq = java.util.concurrent.atomic.AtomicLong(0)
    @Volatile private var _isOrbitComplete = false
    private val _orbitSeq = java.util.concurrent.atomic.AtomicLong(0)

    @Volatile private var _isYawReached = false
    // Same role as _waypointSeq, for gotoYaw — echoed in telemetry as "yawSeq".
    private val _yawSeq = java.util.concurrent.atomic.AtomicLong(0)
    @Volatile private var _isAltitudeReached = false
    // Same role as _waypointSeq, for gotoAltitude — echoed in telemetry as "altitudeSeq".
    private val _altitudeSeq = java.util.concurrent.atomic.AtomicLong(0)
    @Volatile private var _isIntermediaryWaypointReached = false

    // Hot-swappable waypoint target for smooth PID transitions.
    // When a new waypoint arrives mid-flight, the target is swapped without restarting the loop.
    /**
     * What "arrived" means for one waypoint, as the plan defined it.
     *
     * MAVLink puts these on the mission item because only the plan knows which leg is the last
     * one: param2 is the acceptance radius, param1 the hold time, and param3 zero means fly
     * through. A single goto carries none of them and takes [DEFAULT], because a lone reposition
     * is a destination rather than a leg.
     */
    data class WaypointArrival(
        val acceptanceRadiusM: Double? = null,
        val holdSeconds: Double = 0.0,
        val passThrough: Boolean = false
    ) {
        companion object {
            val DEFAULT = WaypointArrival()
        }
    }

    data class WaypointTarget(
        val latitude: Double,
        val longitude: Double,
        val altitude: Double,
        val yaw: Double,        // TRACK yaw: heading held while translating (bearing to the WP)
        val maxSpeed: Double,
        // FINAL yaw: heading the drone rotates to in place once it has arrived (Phase 3). Defaults
        // to the track yaw so callers that don't care about arrival heading keep the old behaviour.
        val finalYaw: Double = yaw,
        /**
         * Arrival criteria for this waypoint, straight from the mission item that asked for it.
         *
         * MAVLink carries these per waypoint, which is the right place for them: only the plan
         * knows which leg is the last one. A generous radius with no hold is a leg to pass
         * through; a tight radius with a hold is somewhere to settle. Null means "use the
         * controller's default", which is what a single goto gets — a lone reposition is a
         * destination by definition.
         */
        val acceptanceRadiusM: Double? = null,
        /** Seconds to stay inside the radius before the waypoint counts as reached. */
        val holdSeconds: Double = 0.0,
        /** True when the plan asked to fly through rather than stop here. */
        val passThrough: Boolean = false
    )

    @Volatile
    private var activeWaypointTarget: WaypointTarget? = null

    // True only while the *waypoint* PID loop is the active control loop. controlLoopEnabled
    // is shared by every loop type (gotoYaw / gotoAltitude), but only the
    // waypoint loop consumes activeWaypointTarget — so a hot-swap must check this flag, otherwise
    // a waypoint command issued while another loop runs would be stashed and silently dropped.
    @Volatile
    private var activeLoopIsWaypoint = false

    // Which waypoint controller owns the running loop. flyToWaypointNoseForward and
    // flyToWaypointHoldHeading share activeWaypointTarget/activeLoopIsWaypoint but run
    // DIFFERENT motion laws and interpret WaypointTarget.yaw/finalYaw differently. A hot-swap is
    // only safe between calls of the SAME controller; swapping a target into the other controller's
    // runnable applies the wrong motion law. So the hot-swap gate must also match this mode,
    // otherwise it falls through to a cold restart with the correct runnable.
    private enum class WaypointMode { NOSE_FORWARD, HOLD_HEADING }
    @Volatile
    private var activeWaypointMode: WaypointMode? = null

    // Set when a target is hot-swapped into a running waypoint loop. The loop clears it on the
    // next tick by reset()-ing its PIDs, so the discontinuous jump in error doesn't kick.
    @Volatile
    private var waypointPidResetRequested = false

    // Control loop management - to prevent ghost waypoint navigation
    private var activeControlLoopHandler: Handler? = null
    private var activeControlLoopRunnable: Runnable? = null
    
    // Kill switch - when false, ALL control loops must stop immediately
    @Volatile
    private var controlLoopEnabled = false

    /**
     * True while a PID/virtual-stick control loop is actively running.
     * Used externally (e.g. VirtualStickVM) to gate the manual-override check so that
     * RC stick noise or drift doesn't spuriously activate override when the drone is idle.
     */
    val isAutonomousFlightActive: Boolean
        get() = controlLoopEnabled

    /**
     * Mirrors the FC isFlying telemetry key. Updated by the Activity via the
     * isFlyingKey listener so VirtualStickVM can gate manual-override detection:
     * sticks only latch override when the drone is actually airborne (prevents
     * ground-level calibration drift false-positives) or when an autonomous loop
     * is running (catches pilot intervention mid-mission).
     */
    @Volatile
    var isAirborne: Boolean = false

    // Unique ID for each control loop session - loops check this to ensure they're still valid
    @Volatile
    private var currentControlLoopId: Long = 0
    
    // Timestamp when control loop started - used to give virtual stick time to enable
    @Volatile
    private var controlLoopStartTime: Long = 0
    
    // Grace period (ms) to allow virtual stick to enable before checking its state
    private const val VIRTUAL_STICK_ENABLE_GRACE_PERIOD_MS = 1000L

    /**
     * Cancel any active control loop (waypoint, gotoYaw, gotoAltitude, etc.)
     * This MUST be called before starting a new control loop or when disabling virtual sticks
     */
    fun cancelActiveControlLoop() {
        // Disable control loop and increment ID to invalidate any running loops
        controlLoopEnabled = false
        currentControlLoopId++
        activeWaypointTarget = null
        activeLoopIsWaypoint = false
        activeWaypointMode = null
        waypointPidResetRequested = false

        activeControlLoopRunnable?.let { runnable ->
            activeControlLoopHandler?.removeCallbacks(runnable)
        }
        activeControlLoopHandler?.removeCallbacksAndMessages(null)
        activeControlLoopHandler = null
        activeControlLoopRunnable = null
        // Reset stick to neutral position
        setStick(0F, 0F, 0F, 0F)
        // Reset navigation status — but don't overwrite TAKING_OFF, LANDING, RTH, MANUAL, ABORTING
        if (droneStatus == DroneStatus.NAVIGATING) setDroneStatus(DroneStatus.IDLE)
    }
    
    /**
     * Start a new control loop session - returns the loop ID that must be checked each iteration
     */
    private fun startNewControlLoopSession(): Long {
        cancelActiveControlLoop()  // Cancel any previous loop first
        controlLoopEnabled = true
        currentControlLoopId++
        controlLoopStartTime = System.currentTimeMillis()
        setDroneStatus(DroneStatus.NAVIGATING)
        return currentControlLoopId
    }
    
    /**
     * Check if the control loop with given ID should continue running.
     * Returns false if:
     * - The control loop was explicitly cancelled (controlLoopEnabled = false)
     * - A new control loop was started (loopId doesn't match)
     * - Manual override is active (pilot took control via RC sticks)
     * - The drone is no longer in virtual stick mode (user took manual control)
     */
    private fun shouldControlLoopContinue(loopId: Long): Boolean {
        // NOTE on the virtual-stick check below: do NOT call activateManualOverride() when it
        // goes false. Virtual stick can be disabled by the system itself (startTakeOff(), signal
        // loss recovery, FC safety checks), which would spuriously latch manual override and block
        // subsequent autonomous commands. Real pilot RC-stick intervention is detected in
        // VirtualStickVM.tryUpdateVirtualStickByRc() while isAutonomousFlightActive is true.
        val decision = ControlLoopContinuation.decide(
            ControlLoopContinuation.State(
                controlLoopEnabled = controlLoopEnabled,
                loopId = loopId,
                currentControlLoopId = currentControlLoopId,
                manualOverrideActive = isManualOverrideActive,
                timeSinceStartMs = System.currentTimeMillis() - controlLoopStartTime,
                virtualStickEnableGracePeriodMs = VIRTUAL_STICK_ENABLE_GRACE_PERIOD_MS,
                virtualStickEnabled =
                    virtualStickVM?.currentVirtualStickStateInfo?.value?.state?.isVirtualStickEnable ?: false
            )
        )
        if (decision.shouldDisableControlLoop) {
            controlLoopEnabled = false
        }
        return decision.shouldContinue
    }

    // Keep track of last KMZ pushed/started

    // App-owned external files directory for KMZ output

    // STREAM STABILITY
    fun enableVirtualStick() {
        // Cancel any active control loop first to prevent ghost navigation
        cancelActiveControlLoop()
        virtualStickVM?.enableVirtualStick(object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() { /* no-op */ }
            override fun onFailure(error: IDJIError) { /* SDK may report "already enabled" — not a real error */ }
        })
    }

    fun disableVirtualStick() {
        // Cancel any active control loop first
        cancelActiveControlLoop()
        virtualStickVM?.disableVirtualStick(object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() { /* no-op */ }
            override fun onFailure(error: IDJIError) { /* SDK may report "already disabled" — not a real error */ }
        })
    }

    /**
     * Comprehensive abort function that stops ALL types of missions/navigation:
     * 1. Cancels any active control loops (PID navigation)
     * 2. Resets virtual sticks to neutral
     * 3. Attempts to disable virtual stick (may fail if control authority was lost - that's OK)
     * 4. Stops any DJI native waypoint missions
     * 
     * This function is designed to be resilient - it will attempt all abort actions
     * regardless of individual failures, ensuring the drone stops moving.
     */
    fun abortAllMissions() {
        // 1. Cancel any active PID control loops immediately
        setDroneStatus(DroneStatus.ABORTING)
        cancelActiveControlLoop()
        // ABORTING is a transient display state — return to IDLE after 2 s
        statusResetHandler.postDelayed({
            if (droneStatus == DroneStatus.ABORTING) setDroneStatus(DroneStatus.IDLE)
        }, 2_000L)
        
        // 2. Reset sticks to neutral
        setStick(0F, 0F, 0F, 0F)
        
        // 3. Try to disable virtual stick (may fail if we don't have control authority - that's OK)
        virtualStickVM?.disableVirtualStick(object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() {
                // Virtual stick disabled successfully
            }
            override fun onFailure(error: IDJIError) {
                // Ignore - we may not have had control authority, which is fine
                // The important thing is we've cancelled the control loops
            }
        })
        
        // 4. Also try to stop any DJI native waypoint mission
        try {
            if (WaylineMissionHelper.lastMissionNameNoExt.isNotEmpty()) {
                WaypointMissionManager.getInstance().stopMission(
                    WaylineMissionHelper.lastMissionNameNoExt,
                    object : CommonCallbacks.CompletionCallback {
                        override fun onSuccess() { /* no-op */ }
                        override fun onFailure(error: IDJIError) { /* no-op */ }
                    }
                )
            }
            // Also try pause in case there's an unnamed mission running
            WaypointMissionManager.getInstance().pauseMission(object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() { /* no-op */ }
                // Fault barrier: the DJI SDK does not document an exception hierarchy for these calls, so a
                // narrower catch would let an unanticipated type escape. This boundary must degrade, not throw.
                @Suppress("TooGenericExceptionCaught")
                override fun onFailure(error: IDJIError) { /* no-op */ }
            })
        } catch (e: Exception) {
            // Best-effort: abort must not throw. Log rather than discard, so a genuine SDK
            // failure during an abort is still visible in the flight log.
            Log.w("DroneController", "Ignoring error while stopping DJI native mission: ${e.message}", e)
        }
    }

    fun calculateDistance(
            latA: Double,
            lngA: Double,
            latB: Double,
            lngB: Double,
    ): Double {
        val earthR = 6371000.0
        val x =
                cos(latA * PI / 180) * cos(
                        latB * PI / 180
                ) * cos((lngA - lngB) * PI / 180)
        val y =
                sin(latA * PI / 180) * sin(
                        latB * PI / 180
                )
        var s = x + y
        if (s > 1) {
            s = 1.0
        }
        if (s < -1) {
            s = -1.0
        }
        val alpha = acos(s)
        return alpha * earthR
    }

    // Helper function to normalize an angle to the range [-180, 180]
    fun normalizeAngle(angle: Double): Double {
        var adjustedAngle = angle % 360
        if (adjustedAngle > 180) adjustedAngle -= 360
        if (adjustedAngle < -180) adjustedAngle += 360
        return adjustedAngle
    }

    fun calculateBearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val lat1Rad = Math.toRadians(lat1)
        val lon1Rad = Math.toRadians(lon1)
        val lat2Rad = Math.toRadians(lat2)
        val lon2Rad = Math.toRadians(lon2)
        val deltaLon = lon2Rad - lon1Rad
        val y = sin(deltaLon) * cos(lat2Rad)
        val x = cos(lat1Rad) * sin(lat2Rad) -
                sin(lat1Rad) * cos(lat2Rad) * cos(deltaLon)
        val initialBearing = atan2(y, x)
        val initialBearingDeg = Math.toDegrees(initialBearing)
        val compassBearing = (initialBearingDeg + 360) % 360
        return compassBearing.toFloat()
    }

    fun setStick(
            leftX: Float = 0F,
            leftY: Float = 0F,
            rightX: Float = 0F,
            rightY: Float = 0F
    ) {
        virtualStickVM?.setLeftPosition(
                (leftX * Stick.MAX_STICK_POSITION_ABS).toInt(),
                (leftY * Stick.MAX_STICK_POSITION_ABS).toInt()
        )
        virtualStickVM?.setRightPosition(
                (rightX * Stick.MAX_STICK_POSITION_ABS).toInt(),
                (rightY * Stick.MAX_STICK_POSITION_ABS).toInt()
        )
    }

    fun startTakeOff() {
        // Disable virtual sticks first to ensure no control loops are running before takeoff
        disableVirtualStick()
        setDroneStatus(DroneStatus.TAKING_OFF)
        basicAircraftControlVM?.startTakeOff(object : CommonCallbacks.CompletionCallbackWithParam<EmptyMsg> {
            override fun onSuccess(t: EmptyMsg?) {
                ToastUtils.showToast("start takeOff onSuccess.")
                // Auto-reset after ~12 s; telemetry will upgrade IDLE → HOVERING if airborne
                statusResetHandler.postDelayed({
                    if (droneStatus == DroneStatus.TAKING_OFF) setDroneStatus(DroneStatus.IDLE)
                }, 12_000L)
            }
            override fun onFailure(error: IDJIError) {
                setDroneStatus(DroneStatus.IDLE)
                ToastUtils.showToast("start takeOff onFailure, $error")
            }
        })
    }

    fun startLanding() {
        setDroneStatus(DroneStatus.LANDING)
        statusResetHandler.postDelayed({
            if (droneStatus == DroneStatus.LANDING) setDroneStatus(DroneStatus.IDLE)
        }, 40_000L)
        basicAircraftControlVM?.startLanding(object : CommonCallbacks.CompletionCallbackWithParam<EmptyMsg> {
            override fun onSuccess(t: EmptyMsg?) {
                ToastUtils.showToast("start landing onSuccess.")
            }
            override fun onFailure(error: IDJIError) {
                setDroneStatus(DroneStatus.IDLE)
                ToastUtils.showToast("start landing onFailure, $error")
            }
        })
    }

    fun startReturnToHome() {
        // CRITICAL: Disable virtual stick before RTH to prevent conflicts
        // Virtual stick mode can interfere with RTH causing erratic behaviorW
        setDroneStatus(DroneStatus.RETURNING_HOME)
        statusResetHandler.postDelayed({
            if (droneStatus == DroneStatus.RETURNING_HOME) setDroneStatus(DroneStatus.IDLE)
        }, 120_000L)
        cancelActiveControlLoop()
        
        virtualStickVM?.disableVirtualStick(object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() {
                // Virtual stick disabled, now safe to start RTH
                executeRTH()
            }

            override fun onFailure(error: IDJIError) {
                // Virtual stick may already be disabled or we don't have control authority
                // Still try RTH - the DJI SDK may handle it
                executeRTH()
            }
        })
    }
    
    private fun executeRTH() {
        basicAircraftControlVM?.startReturnToHome(object :
                CommonCallbacks.CompletionCallbackWithParam<EmptyMsg> {
            override fun onSuccess(t: EmptyMsg?) {
                ToastUtils.showToast("start RTH onSuccess.")
            }

            override fun onFailure(error: IDJIError) {
                ToastUtils.showToast("start RTH onFailure,$error")
            }
        })
    }


    private fun stopCurrentMission() {
        if (WaylineMissionHelper.lastMissionNameNoExt.isNotEmpty()) {
            WaypointMissionManager.getInstance()
                .stopMission(WaylineMissionHelper.lastMissionNameNoExt, object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() { /* no-op */ }
                override fun onFailure(error: IDJIError) { /* ignore */ }
            })
        } else {
            // Try to pause/stop any active mission even if we don't track the name
             WaypointMissionManager.getInstance().pauseMission(object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() { /* no-op */ }
                override fun onFailure(error: IDJIError) { /* ignore */ }
            })
        }
    }

    /** Rotate to [targetYaw]. Returns the seq id published in telemetry as "yawSeq" so a caller
     *  can match a streamed "yawReached" to this command rather than a latched previous one. */
    fun gotoYaw(targetYaw: Double): Long {
        stopCurrentMission()
        // Start new control loop session
        val loopId = startNewControlLoopSession()

        val seq = _yawSeq.incrementAndGet()
        _isYawReached = false
        val controlLoopYaw = Handler(Looper.getMainLooper())
        val updateInterval = 100.0 // Update every 100 ms
        val maxYawRate = 30.0 // degrees per second
        val yawPID = PID(3.0, 0.0, 0.0, updateInterval/1000, -maxYawRate to maxYawRate)

        virtualStickVM?.enableVirtualStickAdvancedMode()
        // Enable Virtual Stick and advanced mode
        // NOTE: Use VM directly, not enableVirtualStick() which would cancel the loop we just started
        virtualStickVM?.enableVirtualStick(object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() { /* no-op */ }
            override fun onFailure(error: IDJIError) {
                /* SDK may report "already enabled" — not a real error */
            }
        })
        virtualStickVM?.enableVirtualStickAdvancedMode()

        val runnable = object : Runnable {
            override fun run() {
                // CHECK IF WE SHOULD STILL BE RUNNING
                if (!shouldControlLoopContinue(loopId)) {
                    setStick(0F, 0F, 0F, 0F)
                    return
                }
                
                val currentPosition = getLocation3D()
                val currentYaw = getHeading()
                val yawError = normalizeAngle(targetYaw - currentYaw)
                val angularVelocity = yawPID.update(yawError)

                // Stop if the error is within a threshold
                if (abs(yawError) < 0.5) {
                    setStick(0F, 0F, 0F, 0F)
                    _isYawReached = true
                    controlLoopEnabled = false
                    return
                }

                val flightControlParam = VirtualStickFlightControlParam().apply {
                    this.pitch = 0.0
                    this.roll = 0.0
                    this.yaw = angularVelocity
                    this.verticalThrottle = currentPosition.altitude
                    this.verticalControlMode = VerticalControlMode.POSITION
                    this.rollPitchControlMode = RollPitchControlMode.VELOCITY
                    this.yawControlMode = YawControlMode.ANGULAR_VELOCITY
                    this.rollPitchCoordinateSystem = FlightCoordinateSystem.BODY
                }

                virtualStickVM?.sendVirtualStickAdvancedParam(flightControlParam)
                controlLoopYaw.postDelayed(this, updateInterval.toLong())
            }
        }
        
        // Store references to allow cancellation
        activeControlLoopHandler = controlLoopYaw
        activeControlLoopRunnable = runnable
        controlLoopYaw.post(runnable)
        return seq
    }

    /** Climb/descend to [targetAltitude]. Returns the seq id published in telemetry as
     *  "altitudeSeq" so a caller can match a streamed "altitudeReached" to this command. */
    fun gotoAltitude(targetAltitude: Double): Long {
        stopCurrentMission()
        // Start new control loop session
        val loopId = startNewControlLoopSession()

        val seq = _altitudeSeq.incrementAndGet()

        // Enable Virtual Stick and advanced mode
        // NOTE: Use VM directly, not enableVirtualStick() which would cancel the loop we just started
        virtualStickVM?.enableVirtualStick(object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() { /* no-op */ }
            override fun onFailure(error: IDJIError) { /* SDK may report "already enabled" — not a real error */ }
        })
        virtualStickVM?.enableVirtualStickAdvancedMode()

        _isAltitudeReached = false
        val controlLoopHandler = Handler(Looper.getMainLooper())
        val updateInterval = 100L // Update every 100 ms
        
        // Capture initial yaw ONCE to prevent oscillation from compass noise
        val initialYaw = getHeading()

        // Enable advanced Virtual Stick mode
        virtualStickVM?.enableVirtualStickAdvancedMode()

        val runnable = object : Runnable {
            override fun run() {
                // CHECK IF WE SHOULD STILL BE RUNNING
                if (!shouldControlLoopContinue(loopId)) {
                    setStick(0F, 0F, 0F, 0F)
                    return
                }

                val currentPosition = getLocation3D()
                val altitudeError = targetAltitude - currentPosition.altitude
                val distanceToAltitude = abs(altitudeError)

                if (distanceToAltitude < 0.4) { // Stop if close enough to the target altitude
                    setStick(0F, 0F, 0F, 0F)
                    _isAltitudeReached = true
                    controlLoopEnabled = false
                    return
                }

                // Proportional gain
                val Kp = 0.4 // Adjust this gain as needed

                // Calculate the vertical speed command
                var verticalSpeed = Kp * altitudeError

                // Limit the vertical speed to the maximum allowed by the drone
                val maxVerticalSpeed = 4.0 // Maximum vertical speed in m/s
                verticalSpeed = verticalSpeed.coerceIn(-maxVerticalSpeed, maxVerticalSpeed)

                // Use initial yaw captured at start to prevent oscillation from compass noise
                val flightControlParam = VirtualStickFlightControlParam().apply {
                    this.pitch = 0.0
                    this.roll = 0.0
                    this.yaw = initialYaw
                    this.verticalThrottle = verticalSpeed
                    this.verticalControlMode = VerticalControlMode.VELOCITY
                    this.rollPitchControlMode = RollPitchControlMode.VELOCITY
                    this.yawControlMode = YawControlMode.ANGLE
                    this.rollPitchCoordinateSystem = FlightCoordinateSystem.BODY
                }

                virtualStickVM?.sendVirtualStickAdvancedParam(flightControlParam)

                // Schedule the next update
                controlLoopHandler.postDelayed(this, updateInterval)
            }
        }
        
        // Store references to allow cancellation
        activeControlLoopHandler = controlLoopHandler
        activeControlLoopRunnable = runnable
        controlLoopHandler.post(runnable)
        return seq
    }

    /**
     * CONTRACT: hold-heading controller. The nose stays on the caller-supplied [targetYaw] for the
     * WHOLE flight — the to-waypoint vector is projected into the body frame, so the drone crabs
     * sideways/diagonally rather than turning to face where it is going. Use this when the payload
     * must keep looking at one bearing while repositioning (animal tracking, standoff observation).
     * Tighter arrival tolerance than [flyToWaypointNoseForward]: WP_ACCEPT_DISTANCE_M_HOLD_HEADING.
     * If you want the drone to fly nose-first along the leg instead, use [flyToWaypointNoseForward].
     *
     * Returns the monotonic sequence id assigned to this request; the same value is published in
     * telemetry as "waypointSeq" so a caller can confirm a streamed "waypointReached" belongs to
     * this target and not a latched previous one.
     */
    fun flyToWaypointHoldHeading(
        targetLatitude: Double,
        targetLongitude: Double,
        targetAlt: Double,
        targetYaw: Double,
        maxSpeed: Double,
        /** Arrival criteria from the plan, when this waypoint came from one. */
        arrival: WaypointArrival = WaypointArrival.DEFAULT
    ): Long {
        val newTarget = WaypointTarget(
            targetLatitude, targetLongitude, targetAlt, targetYaw, maxSpeed,
            acceptanceRadiusM = arrival.acceptanceRadiusM,
            holdSeconds = arrival.holdSeconds,
            passThrough = arrival.passThrough
        )
        // New target → new id, and the reached latch drops to false until this target is reached.
        val seq = _waypointSeq.incrementAndGet()
        _isWaypointReached = false

        // If a *waypoint* PID loop is already running, just hot-swap the target.
        // The running loop reads activeWaypointTarget each tick and will smoothly steer to the new
        // waypoint without a cold restart. We must check activeLoopIsWaypoint, not just
        // controlLoopEnabled: that flag is shared by gotoYaw/gotoAltitude, none
        // of which consume activeWaypointTarget — so swapping into one of those would lose the
        // command. The reset request clears the PID derivative/integral kick from the error jump.
        // Also require the SAME controller mode: if the running loop is flyToWaypointNoseForward's
        // runnable, its motion law/yaw interpretation differ from this precise one, so we must NOT
        // hot-swap into it — fall through to a cold restart with the precise runnable instead.
        if (controlLoopEnabled && activeLoopIsWaypoint && activeWaypointMode == WaypointMode.HOLD_HEADING) {
            activeWaypointTarget = newTarget
            waypointPidResetRequested = true
            return seq
        }

        // No active waypoint loop (or a different controller is running) — start a fresh one
        // (this also cancels any other active loop). Set target AFTER startNewControlLoopSession()
        // because it calls cancelActiveControlLoop() which clears activeWaypointTarget.
        stopCurrentMission()
        val loopId = startNewControlLoopSession()
        activeWaypointTarget = newTarget
        activeLoopIsWaypoint = true
        activeWaypointMode = WaypointMode.HOLD_HEADING

        val updateInterval = 100.0  // Nominal update period (ms); real dt is measured each tick.
        val maxYawRate = maxYawRateDegS() // degrees per second, from the active drone profile
        var lastCommandedSpeed = 0.0
        var lastTickMs = 0L  // SystemClock.elapsedRealtime() of the previous tick, 0 = first tick

        virtualStickVM?.enableVirtualStickAdvancedMode()
        // NOTE: Use VM directly, not enableVirtualStick() which would cancel the loop we just started
        virtualStickVM?.enableVirtualStick(object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() { /* no-op */ }
            override fun onFailure(error: IDJIError) {
                /* SDK may report "already enabled" — not a real error */
            }
        })
        virtualStickVM?.enableVirtualStickAdvancedMode()

        // PID gains are all selected from the connected aircraft profile at runtime.
        val distancePID = PID(
            distancePidKp(),
            distancePidKi(),
            distancePidKd(),
            updateInterval/1000,
            0.0 to waypointPidOutputLimit()
        )
        val yawPID = PID(yawPidKp(), 0.0000, 0.00, updateInterval/1000, -maxYawRate to maxYawRate)

        val controlLoop = Handler(Looper.getMainLooper())
        virtualStickVM?.enableVirtualStickAdvancedMode()

        // Cooldown: after reaching a waypoint, keep PID loop alive for this long
        // to allow the bridge to hot-swap the next target without a cold restart.
        val holdCooldownMs = 200L
        var reachedAtMs = 0L  // SystemClock.elapsedRealtime() when waypoint was first reached, 0 = not reached

        val runnable = object : Runnable {
            override fun run() {
                // CHECK IF WE SHOULD STILL BE RUNNING
                if (!shouldControlLoopContinue(loopId)) {
                    setStick(0F, 0F, 0F, 0F)
                    return
                }

                // Read the current target (may have been hot-swapped by a new call)
                val target = activeWaypointTarget
                if (target == null) {
                    setStick(0F, 0F, 0F, 0F)
                    controlLoopEnabled = false
                    disableVirtualStick()
                    return
                }

                // Measure the real timestep instead of assuming updateInterval. The loop runs on
                // the main Looper, whose cadence drifts under load; clamp so a stalled thread can't
                // inject a huge dt spike into the PID derivative/integral or the accel limiter.
                val nowMs = android.os.SystemClock.elapsedRealtime()
                val dtSec = if (lastTickMs == 0L) updateInterval / 1000.0
                            else ((nowMs - lastTickMs) / 1000.0).coerceIn(0.02, 0.5)
                lastTickMs = nowMs

                // A hot-swapped target is a discontinuous setpoint — clear PID history once so the
                // jump in distance/yaw error doesn't produce an integral/derivative kick.
                if (waypointPidResetRequested) {
                    waypointPidResetRequested = false
                    distancePID.reset()
                    yawPID.reset()
                }

                val currentPosition = getLocation3D()
                val currentYaw = getHeading()

                val distance = calculateDistance(
                    target.latitude,
                    target.longitude,
                    currentPosition.latitude,
                    currentPosition.longitude
                )
                val pidSpeed = distancePID.update(distance, dtSec)
                val maxSpeedStep = maxHorizontalAccelMps2() * dtSec
                val targetSpeed = WaypointControl.limitedSpeed(
                    pidSpeed = pidSpeed,
                    targetMaxSpeed = target.maxSpeed,
                    lastCommandedSpeed = lastCommandedSpeed,
                    maxSpeedStep = maxSpeedStep
                )
                lastCommandedSpeed = targetSpeed
                val movementDirection = calculateBearing(
                    currentPosition.latitude,
                    currentPosition.longitude,
                    target.latitude,
                    target.longitude
                ).toDouble()

                val yawError = normalizeAngle(target.yaw - currentYaw)
                val angularVelocity = yawPID.update(yawError, dtSec)

                // Project the to-waypoint vector into the drone's body frame; the nose stays on
                // target.yaw, so travel is a mix of forward and lateral velocity.
                val body = WaypointControl.bodyVelocity(targetSpeed, movementDirection, currentYaw)

                val altError = target.altitude - currentPosition.altitude

                val plan = WaypointControl.cooldownPlan(
                    targetReached = WaypointControl.reachedTarget(
                        distance = distance,
                        yawError = yawError,
                        altitudeError = altError,
                        acceptance = acceptanceFor(target, HOLD_HEADING_ACCEPTANCE)
                    ),
                    wasWaypointReached = _isWaypointReached,
                    reachedAtMs = reachedAtMs,
                    nowMs = nowMs,
                    holdCooldownMs = holdCooldownMs,
                    dwellMs = dwellMsFor(target)
                )
                _isWaypointReached = plan.waypointReached
                // 0 when outside acceptance, so GPS drift or a new target restarts the cooldown.
                reachedAtMs = plan.reachedAtMs
                if (plan.stopAtWaypoint) {
                    // Cooldown expired — no new waypoint arrived, stop cleanly
                    setStick(0F, 0F, 0F, 0F)
                    activeWaypointTarget = null
                    controlLoopEnabled = false
                    disableVirtualStick()
                    return
                }

                // DJI SDK V5 quirk: in BODY frame, the SDK's "pitch" field actually controls
                // lateral (left/right) movement and "roll" controls forward/backward. This is
                // the inverse of what the field names suggest. Confirmed empirically.
                val flightControlParam = VirtualStickFlightControlParam().apply {
                    this.pitch = body.lateralSpeed
                    this.roll = body.forwardSpeed
                    this.yaw = angularVelocity
                    this.verticalThrottle = target.altitude
                    this.verticalControlMode = VerticalControlMode.POSITION
                    this.rollPitchControlMode = RollPitchControlMode.VELOCITY
                    this.yawControlMode = YawControlMode.ANGULAR_VELOCITY
                    this.rollPitchCoordinateSystem = FlightCoordinateSystem.BODY
                }

                virtualStickVM?.sendVirtualStickAdvancedParam(flightControlParam)
                controlLoop.postDelayed(this, updateInterval.toLong())
            }
        }
        
        // Store references to allow cancellation
        activeControlLoopHandler = controlLoop
        activeControlLoopRunnable = runnable
        controlLoop.post(runnable)
        return seq
    }

    /**
     * CONTRACT: nose-follows-path controller with a final-heading phase. Three phases:
     *   Phase 1 ALIGN  — rotate in place to the TRACK heading = bearing(current -> WP).
     *   Phase 2 NAV    — translate to the WP, nose held on the track heading (cross-track owns lateral).
     *   Phase 3 FINAL  — once arrived (position + altitude), rotate in place to the user-requested
     *                    [targetYaw], holding position. Only then is the waypoint latched as reached.
     * So during travel the drone faces its direction of motion (required by the Phase-2 motion law,
     * which dead-reckons forwardSpeed along body-X), and [targetYaw] sets the heading the drone ends
     * up facing AT the waypoint. If you need the nose pointed at [targetYaw] while translating, use
     * [flyToWaypointHoldHeading] instead, which projects the to-waypoint vector into body frame.
     */
    fun flyToWaypointNoseForward(
        targetLatitude: Double,
        targetLongitude: Double,
        targetAlt: Double,
        targetYaw: Double,
        maxSpeed: Double,
        /** Arrival criteria from the plan, when this waypoint came from one. */
        arrival: WaypointArrival = WaypointArrival.DEFAULT
    ): Long {
        // Track yaw = bearing(current position -> waypoint): the heading held during Phase 1/2.
        // The caller-supplied targetYaw becomes the Phase-3 final heading (finalYaw). Recomputed here
        // so both the cold-start and hot-swap paths below anchor the nose on the new leg.
        val startPos = getLocation3D()
        val trackYaw = calculateBearing(
            startPos.latitude,
            startPos.longitude,
            targetLatitude,
            targetLongitude
        ).toDouble()
        val newTarget = WaypointTarget(
            targetLatitude,
            targetLongitude,
            targetAlt,
            trackYaw,
            maxSpeed,
            finalYaw = targetYaw,
            acceptanceRadiusM = arrival.acceptanceRadiusM,
            holdSeconds = arrival.holdSeconds,
            passThrough = arrival.passThrough
        )
        // New target → new id, and the reached latch drops to false until this target is reached.
        val seq = _waypointSeq.incrementAndGet()
        _isWaypointReached = false

        // If a *waypoint* PID loop is already running, just hot-swap the target.
        // The running loop reads activeWaypointTarget each tick and will smoothly steer to the new
        // waypoint without a cold restart. We must check activeLoopIsWaypoint, not just
        // controlLoopEnabled: that flag is shared by gotoYaw/gotoAltitude, none
        // of which consume activeWaypointTarget — so swapping into one of those would lose the
        // command. The reset request clears the PID derivative/integral kick from the error jump.
        // Also require the SAME controller mode: if the running loop is the precise runnable, its
        // motion law/yaw interpretation differ from this one, so we must NOT hot-swap into it —
        // fall through to a cold restart with this runnable instead.
        if (controlLoopEnabled && activeLoopIsWaypoint && activeWaypointMode == WaypointMode.NOSE_FORWARD) {
            activeWaypointTarget = newTarget
            waypointPidResetRequested = true
            return seq
        }

        // No active waypoint loop (or a different controller is running) — start a fresh one
        // (this also cancels any other active loop). Set target AFTER startNewControlLoopSession()
        // because it calls cancelActiveControlLoop() which clears activeWaypointTarget.
        stopCurrentMission()
        val loopId = startNewControlLoopSession()
        activeWaypointTarget = newTarget
        activeLoopIsWaypoint = true
        activeWaypointMode = WaypointMode.NOSE_FORWARD

        val updateInterval = 100.0  // PID/setpoint recompute period (ms); real dt measured each control tick.
        val sendIntervalMs = 100L    // Virtual-stick resend period (ms) = 10 Hz, decoupled from the 1 Hz
                                     // control update. The SDK watchdog zeros the sticks if no fresh
                                     // command arrives within a few hundred ms, so we re-send the latest
                                     // computed param at 10 Hz to keep pitch/roll/yaw velocity continuous
                                     // between PID updates — this kills the once-per-second stutter.
        val maxYawRate = maxYawRateDegS() // degrees per second, from the active drone profile
        var lastCommandedSpeed = 0.0
        var lastControlMs = 0L  // elapsedRealtime() of the last PID/setpoint update, 0 = first tick
        var lastParam: VirtualStickFlightControlParam? = null  // latest computed command, resent at 10 Hz

        virtualStickVM?.enableVirtualStickAdvancedMode()
        // NOTE: Use VM directly, not enableVirtualStick() which would cancel the loop we just started
        virtualStickVM?.enableVirtualStick(object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() { /* no-op */ }
            override fun onFailure(error: IDJIError) {
                /* SDK may report "already enabled" — not a real error */
            }
        })
        virtualStickVM?.enableVirtualStickAdvancedMode()

        // --- Cross-track lateral control state (replaces speed-scaled bearing steering) ---
        // Roll-oscillation root cause: the old law lateralSpeed = targetSpeed*sin(bearing-yaw)
        // scaled the cross-track correction by full forward speed AND by ~1/distance near the WP,
        // so it blew up into a roll limit cycle at speed. We instead steer on the signed
        // perpendicular distance to the straight A->B line: well-conditioned near the endpoint and
        // with a gain independent of speed and distance.
        var lineLat0 = 0.0            // A: track origin, captured at cold start / after a hot-swap
        var lineLon0 = 0.0
        var lineValid = false
        var crossTrackFilt = 0.0      // low-pass of signed cross-track offset (m); kills stepped-GPS jitter
        var crossTrackInit = false
        var lastLateralSpeed = 0.0    // for the lateral slew limit
        var yawAligned = false        // Phase 1->2: false => rotate to track heading; true => translate
        var positionReached = false   // Phase 2->3: true once within WP distance+altitude; Phase 3 then
                                      // rotates in place to target.finalYaw before latching reached.
        val crossTrackKp = 0.5        // m/s of lateral correction per m of offset
        val maxLateralSpeed = 3.0     // hard cap on lateral correction (m/s)
        val crossTrackLpfAlpha = 0.3  // 0..1 low-pass weight; lower = smoother

        // All distance/yaw gains and the max horizontal accel come from the active aircraft profile,
        // same as flyToWaypointHoldHeading. (This endpoint exists for the align-then-translate
        // behaviour, not for live gain sweeping.)
        val distancePID = PID(
            distancePidKp(),
            distancePidKi(),
            distancePidKd(),
            updateInterval/1000,
            0.0 to waypointPidOutputLimit()
        )
        val yawPID = PID(yawPidKp(), 0.0000, 0.00, updateInterval/1000, -maxYawRate to maxYawRate)

        val controlLoop = Handler(Looper.getMainLooper())
        virtualStickVM?.enableVirtualStickAdvancedMode()

        // Cooldown: after reaching a waypoint, keep PID loop alive for this long
        // to allow the bridge to hot-swap the next target without a cold restart.
        val holdCooldownMs = 200L
        var reachedAtMs = 0L  // SystemClock.elapsedRealtime() when waypoint was first reached, 0 = not reached

        val runnable = object : Runnable {
            
            override fun run() {
                // CHECK IF WE SHOULD STILL BE RUNNING
                if (!shouldControlLoopContinue(loopId)) {
                    setStick(0F, 0F, 0F, 0F)
                    return
                }

                // Read the current target (may have been hot-swapped by a new call)
                val target = activeWaypointTarget
                if (target == null) {
                    setStick(0F, 0F, 0F, 0F)
                    controlLoopEnabled = false
                    disableVirtualStick()
                    return
                }

                val nowMs = android.os.SystemClock.elapsedRealtime()
                // Between PID updates: just re-send the latest command so the SDK watchdog never
                // zeros the sticks. This is what makes the motion continuous instead of stuttering
                // once per second — the setpoint only changes at 1 Hz but the drone keeps the
                // commanded velocity the whole time.
                if (lastControlMs != 0L && (nowMs - lastControlMs) < updateInterval.toLong()) {
                    lastParam?.let { virtualStickVM?.sendVirtualStickAdvancedParam(it) }
                    controlLoop.postDelayed(this, sendIntervalMs)
                    return
                }

                // Control tick: measure real elapsed time since the last PID update. Clamp so a
                // stalled main Looper can't inject a huge dt spike into the PID or accel limiter.
                val dtSec = if (lastControlMs == 0L) updateInterval / 1000.0
                else ((nowMs - lastControlMs) / 1000.0).coerceIn(0.02, 2.0)
                lastControlMs = nowMs

                // A hot-swapped target is a discontinuous setpoint — clear PID history once so the
                // jump in distance/yaw error doesn't produce an integral/derivative kick. Also drop
                // the cross-track line + lateral state so they re-anchor to the new leg.
                if (waypointPidResetRequested) {
                    waypointPidResetRequested = false
                    distancePID.reset()
                    yawPID.reset()
                    lineValid = false
                    crossTrackInit = false
                    lastLateralSpeed = 0.0
                    yawAligned = false
                    positionReached = false
                }

                val currentPosition = getLocation3D()
                val currentYaw = getHeading()

                // Phase 1 (ALIGN): rotate yaw in place toward target.yaw, no translation, holding
                // altitude. Only when the heading is within tolerance do we switch to Phase 2 (NAV).
                // We early-return here so the distance/cross-track loops don't tick during rotation:
                // distancePID stays un-wound and the A->B line anchors where translation begins.
                val yawError = normalizeAngle(target.yaw - currentYaw)
                val angularVelocity = yawPID.update(yawError, dtSec)
                if (!yawAligned) {
                    if (abs(yawError) < WP_ACCEPT_YAW_DEG) {
                        yawAligned = true
                    } else {
                        lastParam = VirtualStickFlightControlParam().apply {
                            this.pitch = 0.0
                            this.roll = 0.0
                            this.yaw = angularVelocity
                            this.verticalThrottle = target.altitude
                            this.verticalControlMode = VerticalControlMode.POSITION
                            this.rollPitchControlMode = RollPitchControlMode.VELOCITY
                            this.yawControlMode = YawControlMode.ANGULAR_VELOCITY
                            this.rollPitchCoordinateSystem = FlightCoordinateSystem.BODY
                        }
                        lastParam?.let { virtualStickVM?.sendVirtualStickAdvancedParam(it) }
                        controlLoop.postDelayed(this, sendIntervalMs)
                        return
                    }
                }

                // Anchor the straight-line track origin A on the first tick of this leg.
                if (!lineValid) {
                    lineLat0 = currentPosition.latitude
                    lineLon0 = currentPosition.longitude
                    lineValid = true
                }

                val distance = calculateDistance(
                    target.latitude,
                    target.longitude,
                    currentPosition.latitude,
                    currentPosition.longitude
                )
                val altErrorNav = target.altitude - currentPosition.altitude

                // Phase 2 -> 3 transition: inside the WP position + altitude tolerance we stop
                // translating and run Phase 3 (FINAL ALIGN).
                //
                // Re-evaluated every tick rather than latched once. Latching it meant the
                // aircraft could touch the radius, drift out of it in wind, rotate to the final
                // heading and report arrival from well outside the box — position was never
                // checked again. Dropping back to Phase 2 when it drifts out is what makes the
                // arrival claim mean what it says.
                val acceptance = acceptanceFor(target, NOSE_FORWARD_ACCEPTANCE)
                positionReached = distance < acceptance.distanceMeters &&
                    abs(altErrorNav) < acceptance.altitudeMeters

                // Phase 3 (FINAL ALIGN): rotate in place to the user-requested arrival heading
                // (target.finalYaw), holding position and altitude. The waypoint is only latched as
                // reached once that heading is within tolerance; until then the cooldown stays unarmed.
                if (positionReached) {
                    val finalYawError = normalizeAngle(target.finalYaw - currentYaw)
                    val finalAngularVelocity = yawPID.update(finalYawError, dtSec)
                    // Arrival here is final-heading only: position and altitude were already
                    // latched by positionReached, so the shared three-axis predicate does not apply.
                    val plan = WaypointControl.cooldownPlan(
                        // Position and altitude are re-checked above and gate entry to this
                        // branch, so arrival here is the final heading closing the last axis.
                        targetReached = abs(finalYawError) < acceptance.yawDegrees,
                        wasWaypointReached = _isWaypointReached,
                        reachedAtMs = reachedAtMs,
                        nowMs = nowMs,
                        holdCooldownMs = holdCooldownMs,
                        dwellMs = dwellMsFor(target)
                    )
                    _isWaypointReached = plan.waypointReached
                    // 0 while still rotating to the final heading — keeps the cooldown unarmed.
                    reachedAtMs = plan.reachedAtMs
                    if (plan.stopAtWaypoint) {
                        // Cooldown expired — no new waypoint hot-swapped in, stop cleanly.
                        setStick(0F, 0F, 0F, 0F)
                        activeWaypointTarget = null
                        controlLoopEnabled = false
                        disableVirtualStick()
                        return
                    }
                    lastParam = VirtualStickFlightControlParam().apply {
                        this.pitch = 0.0
                        this.roll = 0.0
                        this.yaw = finalAngularVelocity
                        this.verticalThrottle = target.altitude
                        this.verticalControlMode = VerticalControlMode.POSITION
                        this.rollPitchControlMode = RollPitchControlMode.VELOCITY
                        this.yawControlMode = YawControlMode.ANGULAR_VELOCITY
                        this.rollPitchCoordinateSystem = FlightCoordinateSystem.BODY
                    }
                    lastParam?.let { virtualStickVM?.sendVirtualStickAdvancedParam(it) }
                    controlLoop.postDelayed(this, sendIntervalMs)
                    return
                }

                // Forward speed: distance-PID, clamped to maxSpeed, a kinematic decel cap so the
                // drone can always brake within the remaining distance (v <= sqrt(2*a*d)), then the
                // existing accel slew limit. The decel cap replaces the implicit, Kp-defined braking
                // zone that used to slam the brakes / overshoot near the WP.
                val brakeDist = max(0.0, distance - WP_ACCEPT_DISTANCE_M)
                val decelCap = sqrt(2.0 * maxHorizontalAccelMps2() * brakeDist)
                val pidSpeed = distancePID.update(distance, dtSec)
                val maxSpeedStep = maxHorizontalAccelMps2() * dtSec
                val targetSpeed = WaypointControl.limitedSpeed(
                    pidSpeed = pidSpeed,
                    // The kinematic decel cap is folded into the ceiling so the drone can always
                    // brake within the remaining distance; the slew limit then applies on top.
                    targetMaxSpeed = min(target.maxSpeed, decelCap),
                    lastCommandedSpeed = lastCommandedSpeed,
                    maxSpeedStep = maxSpeedStep
                )
                lastCommandedSpeed = targetSpeed

                // --- Cross-track lateral correction (decoupled from forward speed) ---
                // Signed perpendicular distance from the drone to the A->B line, in meters.
                // x = north, y = east. cross = ((B-A) x (P-A)).z / |B-A|; positive => right of track.
                val mPerDegLat = 111320.0
                val mPerDegLon = 111320.0 * cos(Math.toRadians(currentPosition.latitude))
                val bn = (target.latitude - lineLat0) * mPerDegLat
                val be = (target.longitude - lineLon0) * mPerDegLon
                val pn = (currentPosition.latitude - lineLat0) * mPerDegLat
                val pe = (currentPosition.longitude - lineLon0) * mPerDegLon
                val segLen = hypot(bn, be)
                val crossTrackRaw = if (segLen > 0.1) (bn * pe - be * pn) / segLen else 0.0
                crossTrackFilt = if (!crossTrackInit) crossTrackRaw
                                 else crossTrackFilt + crossTrackLpfAlpha * (crossTrackRaw - crossTrackFilt)
                crossTrackInit = true

                // Push back toward the line (positive cross = right of track -> steer left, i.e.
                // negative lateral). Constant gain (independent of speed and 1/distance) => no roll
                // limit cycle, and the line offset stays well-conditioned right up to the endpoint.
                val lateralDesired = (-crossTrackKp * crossTrackFilt).coerceIn(-maxLateralSpeed, maxLateralSpeed)
                // Slew-limit lateral like forward so it can't jump full range on one stepped GPS tick.
                val maxLatStep = maxHorizontalAccelMps2() * dtSec
                val lateralSpeed = lateralDesired.coerceIn(lastLateralSpeed - maxLatStep, lastLateralSpeed + maxLatStep)
                lastLateralSpeed = lateralSpeed

                // Forward is along body-X; the yaw loop holds the nose on the track heading so
                // body-forward stays aligned with A->B and the cross-track loop owns lateral. Sign
                // by along-track remaining so an overshoot past B reverses instead of running away.
                val alongRemaining = if (segLen > 0.1) segLen - (pn * bn + pe * be) / segLen else 0.0
                val forwardSpeed = if (alongRemaining >= 0.0) targetSpeed else -targetSpeed

                // Arrival (position + final-yaw) is handled by the Phase 3 block above, which
                // early-returns. Reaching here means we are still translating (Phase 2).

                // DJI SDK V5 quirk: in BODY frame, the SDK's "pitch" field actually controls
                // lateral (left/right) movement and "roll" controls forward/backward. This is
                // the inverse of what the field names suggest. Confirmed empirically.
                lastParam = VirtualStickFlightControlParam().apply {
                    this.pitch = lateralSpeed
                    this.roll = forwardSpeed
                    this.yaw = 0.0   // no yaw command during translation; heading set once in Phase 1
                    this.verticalThrottle = target.altitude
                    this.verticalControlMode = VerticalControlMode.POSITION
                    this.rollPitchControlMode = RollPitchControlMode.VELOCITY
                    this.yawControlMode = YawControlMode.ANGULAR_VELOCITY
                    this.rollPitchCoordinateSystem = FlightCoordinateSystem.BODY
                }

                lastParam?.let { virtualStickVM?.sendVirtualStickAdvancedParam(it) }
                controlLoop.postDelayed(this, sendIntervalMs)
            }
        }

        // Store references to allow cancellation
        activeControlLoopHandler = controlLoop
        activeControlLoopRunnable = runnable
        controlLoop.post(runnable)
        return seq
    }

    /**
     * CONTRACT: fly a circle around a fixed point, at a fixed radius and altitude.
     *
     * Two velocities, added: around the circle at [tangentialSpeedMps], and in or out to hold the
     * radius. The radial term is what lets the orbit be commanded from anywhere — the aircraft
     * curves onto the circle rather than flying to a point on it first — and it is capped, so
     * joining from far outside is a sweep and not a dash at the centre.
     *
     * The arc travelled is accumulated tick by tick rather than compared against the starting
     * bearing, because the aircraft passes its start angle once per lap: an absolute comparison
     * cannot tell a finished lap from a fresh one, and would end a two-lap orbit at the first
     * crossing. [arcDegrees] of zero means orbit until something else takes over, which is what a
     * ground station means by an orbit with no limit.
     *
     * @param clockwise seen from above. MAVLink carries this as the sign of the radius.
     * @param faceCentre keep the nose (and therefore a fixed camera) pointed at the centre. False
     *   holds the heading the aircraft started with.
     * @return the monotonic sequence id for this request, so an arrival can be told from a stale
     *   latch belonging to the orbit before it.
     */
    @Suppress("LongParameterList")
    fun orbit(
        centreLatitude: Double,
        centreLongitude: Double,
        targetAltitude: Double,
        radiusMeters: Double,
        tangentialSpeedMps: Double,
        clockwise: Boolean,
        arcDegrees: Double,
        faceCentre: Boolean
    ): Long {
        stopCurrentMission()
        val loopId = startNewControlLoopSession()
        val seq = _orbitSeq.incrementAndGet()
        _isOrbitComplete = false

        virtualStickVM?.enableVirtualStickAdvancedMode()
        // The VM directly rather than enableVirtualStick(), which would cancel the session just
        // started — the same reason the waypoint controllers do it this way.
        virtualStickVM?.enableVirtualStick(object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() { /* no-op */ }
            override fun onFailure(error: IDJIError) { /* "already enabled" is not an error */ }
        })
        virtualStickVM?.enableVirtualStickAdvancedMode()

        val controlLoop = Handler(Looper.getMainLooper())
        val updateInterval = 100L
        val maxYawRate = maxYawRateDegS()
        val yawPID = PID(yawPidKp(), 0.0, 0.0, updateInterval / 1000.0, -maxYawRate to maxYawRate)
        // Captured once. Read every tick it would chase its own compass noise, and an orbit that
        // is not facing the centre is meant to hold the heading it began with.
        val initialHeading = getHeading()

        var lastCommandedSpeed = 0.0
        var lastTickMs = 0L
        var travelledDeg = 0.0
        var lastBearingDeg = Double.NaN

        val runnable = object : Runnable {
            override fun run() {
                if (!shouldControlLoopContinue(loopId)) {
                    setStick(0F, 0F, 0F, 0F)
                    return
                }

                val nowMs = android.os.SystemClock.elapsedRealtime()
                val dtSec = if (lastTickMs == 0L) updateInterval / 1000.0
                else ((nowMs - lastTickMs) / 1000.0).coerceIn(0.02, 0.5)
                lastTickMs = nowMs

                val position = getLocation3D()
                val heading = getHeading()

                // Offset from the centre in metres. Flat-earth is right to well under a metre at
                // any radius an orbit is flown at, and keeps the geometry readable.
                val metresPerDegreeLat = 111320.0
                val metresPerDegreeLon = 111320.0 * cos(Math.toRadians(position.latitude))
                val northM = (position.latitude - centreLatitude) * metresPerDegreeLat
                val eastM = (position.longitude - centreLongitude) * metresPerDegreeLon

                val bearingFromCentre = OrbitControl.bearingFromCentreDeg(northM, eastM)
                if (!lastBearingDeg.isNaN()) {
                    travelledDeg += OrbitControl.angleStepDeg(lastBearingDeg, bearingFromCentre)
                }
                lastBearingDeg = bearingFromCentre

                if (OrbitControl.isComplete(travelledDeg, arcDegrees)) {
                    setStick(0F, 0F, 0F, 0F)
                    _isOrbitComplete = true
                    controlLoopEnabled = false
                    disableVirtualStick()
                    return
                }

                val velocity = OrbitControl.velocity(
                    northM = northM,
                    eastM = eastM,
                    radiusM = radiusMeters,
                    tangentialMps = tangentialSpeedMps,
                    clockwise = clockwise,
                    radialGain = ORBIT_RADIAL_GAIN,
                    maxRadialMps = ORBIT_MAX_RADIAL_MPS
                )
                // Slew-limited like the waypoint controllers, so the first tick of an orbit
                // commanded from a hover is a push rather than a step to full speed.
                val speed = velocity.speedMps
                    .coerceAtMost(lastCommandedSpeed + maxHorizontalAccelMps2() * dtSec)
                    .coerceAtLeast(0.0)
                lastCommandedSpeed = speed

                // Negating the offset turns "bearing from the centre out to us" into "bearing
                // from us in to the centre", which is where the nose goes.
                val desiredHeading = if (faceCentre) {
                    OrbitControl.bearingFromCentreDeg(-northM, -eastM)
                } else {
                    initialHeading
                }
                val angularVelocity = yawPID.update(
                    normalizeAngle(desiredHeading - heading), dtSec
                )

                val body = WaypointControl.bodyVelocity(speed, velocity.directionDeg, heading)

                // DJI SDK V5 quirk: in BODY frame the SDK's "pitch" field controls lateral
                // movement and "roll" controls forward/backward, the inverse of what the names
                // suggest. Confirmed empirically, and shared with both waypoint controllers.
                val flightControlParam = VirtualStickFlightControlParam().apply {
                    this.pitch = body.lateralSpeed
                    this.roll = body.forwardSpeed
                    this.yaw = angularVelocity
                    this.verticalThrottle = targetAltitude
                    this.verticalControlMode = VerticalControlMode.POSITION
                    this.rollPitchControlMode = RollPitchControlMode.VELOCITY
                    this.yawControlMode = YawControlMode.ANGULAR_VELOCITY
                    this.rollPitchCoordinateSystem = FlightCoordinateSystem.BODY
                }
                virtualStickVM?.sendVirtualStickAdvancedParam(flightControlParam)
                controlLoop.postDelayed(this, updateInterval)
            }
        }

        activeControlLoopHandler = controlLoop
        activeControlLoopRunnable = runnable
        controlLoop.post(runnable)
        return seq
    }

    fun getOrbitSeq(): Long = _orbitSeq.get()

    fun isOrbitComplete(): Boolean = _isOrbitComplete

    // === DJI Native Wayline (KMZ) flow ===
    // ---- DJI native KMZ wayline missions -------------------------------------
    // Implementation lives in WaylineMissionHelper; these delegate so callers keep
    // using DroneController as the single entry point.

    fun generateTrajectoryName(): String = WaylineMissionHelper.generateTrajectoryName()

    fun getKmzDirectory(): String = WaylineMissionHelper.kmzDir

    fun getLastMissionNameNoExt(): String = WaylineMissionHelper.lastMissionNameNoExt

    fun getLastMissionKmzPath(): String = WaylineMissionHelper.lastMissionKmzPath

    fun createWaypointFromLatLon(lat: Double, lon: Double, heightMeters: Double, index: Int): WaypointInfoModel =
        WaylineMissionHelper.createWaypointFromLatLon(lat, lon, heightMeters, index)

    fun createWaylineMission(): WaylineMission = WaylineMissionHelper.createWaylineMission()

    fun createMissionConfig(
        finishAction: WaylineFinishedAction = WaylineFinishedAction.NO_ACTION,
        lostAction: WaylineExitOnRCLostAction = WaylineExitOnRCLostAction.GO_BACK
    ): WaylineMissionConfig = WaylineMissionHelper.createMissionConfig(finishAction, lostAction)

    fun extractWaylineIdsFromKmz(kmzPath: String): ArrayList<Int> =
        WaylineMissionHelper.extractWaylineIdsFromKmz(kmzPath)

    fun generateAndSaveKmz(
        waypointInfoModels: List<WaypointInfoModel>,
        missionName: String = WaylineMissionHelper.generateTrajectoryName(),
        trajectorySpeed: Double = 5.0,
        finishAction: WaylineFinishedAction = WaylineFinishedAction.GO_HOME,
        lostAction: WaylineExitOnRCLostAction = WaylineExitOnRCLostAction.GO_BACK
    ): String = WaylineMissionHelper.generateAndSaveKmz(
        waypointInfoModels, missionName, trajectorySpeed, finishAction, lostAction
    )

    fun pushKmzToAircraft(
        kmzPath: String,
        onProgress: ((Double) -> Unit)? = null,
        onSuccess: () -> Unit,
        onFailure: (IDJIError) -> Unit
    ) = WaylineMissionHelper.pushKmzToAircraft(kmzPath, onProgress, onSuccess, onFailure)

    fun startMission(
        missionNameNoExt: String = WaylineMissionHelper.lastMissionNameNoExt,
        kmzPath: String = WaylineMissionHelper.lastMissionKmzPath,
        onSuccess: () -> Unit,
        onFailure: (IDJIError) -> Unit
    ) = WaylineMissionHelper.startMission(missionNameNoExt, kmzPath, onSuccess, onFailure)

    fun pauseMission(onSuccess: () -> Unit, onFailure: (IDJIError) -> Unit) =
        WaylineMissionHelper.pauseMission(onSuccess, onFailure)

    fun stopMission(
        missionNameNoExt: String = WaylineMissionHelper.lastMissionNameNoExt,
        onSuccess: () -> Unit,
        onFailure: (IDJIError) -> Unit
    ) = WaylineMissionHelper.stopMission(missionNameNoExt, onSuccess, onFailure)

    fun navigateTrajectoryNative(
        userWaypoints: List<Triple<Double, Double, Double>>,
        trajectorySpeed: Double,
        onProgress: (Int) -> Unit = {},
        onFinished: (Boolean) -> Unit = {}
    ) = WaylineMissionHelper.navigateTrajectoryNative(userWaypoints, trajectorySpeed, onProgress, onFinished)

    fun navigateWaylineMissionNative(
        waypointInfoModels: List<WaypointInfoModel>,
        missionConfig: WaylineMissionConfig,
        autoFlightSpeed: Double,
        onProgress: (Int) -> Unit = {},
        onFinished: (Boolean) -> Unit = {}
    ) = WaylineMissionHelper.navigateWaylineMissionNative(
        waypointInfoModels, missionConfig, autoFlightSpeed, onProgress, onFinished
    )

    fun endMission() = WaylineMissionHelper.endMission()



    

    /**
     * Create a waypoint model from lat/lon/height with gimbal pitch set to -90 (looking down)
     */




    // Transform waypoint actions into proper action groups for KMZ



    /**
     * Generate and save a KMZ file from waypoint models
     * Returns the path to the saved KMZ file
     */

    /**
     * Push a KMZ file to the aircraft
     */

    /**
     * Start a mission that has been pushed to the aircraft
     */

    /**
     * Pause the current mission
     */

    /**
     * Stop the current mission
     */



    // Getter pour isWaypointReached
    fun isWaypointReached(): Boolean {
        return _isWaypointReached
    }

    // Id of the most recently accepted waypoint request. Pair this with isWaypointReached()
    // in telemetry so a client can match "reached" to a specific commanded target.
    fun getWaypointSeq(): Long {
        return _waypointSeq.get()
    }

    // Id of the most recently accepted gotoYaw request — pair with isYawReached().
    fun getYawSeq(): Long {
        return _yawSeq.get()
    }

    // Id of the most recently accepted gotoAltitude request — pair with isAltitudeReached().
    fun getAltitudeSeq(): Long {
        return _altitudeSeq.get()
    }

    // Getter pour isYawReached
    fun isYawReached(): Boolean {
        return _isYawReached
    }

    // Idem pour isAltitudeReached, etc.
    fun isAltitudeReached(): Boolean {
        return _isAltitudeReached
    }

    fun isIntermediaryWaypointReached(): Boolean {
        return _isIntermediaryWaypointReached
    }

    private val goHomeHeightKey: DJIKey<Int> = FlightControllerKey.KeyGoHomeHeight.create()
    private val maxFlightHeightKey: DJIKey<Int> = FlightControllerKey.KeyHeightLimit.create()
    private val maxFlightDistanceKey: DJIKey<Int> = FlightControllerKey.KeyDistanceLimit.create()
    private val distanceLimitEnabledKey: DJIKey<Boolean> = FlightControllerKey.KeyDistanceLimitEnabled.create()

    @Volatile private var cachedRTHAltitude: Int = -1
    @Volatile private var cachedMaxFlightHeight: Int = -1
    @Volatile private var cachedMaxFlightDistance: Int = -1
    @Volatile private var cachedDistanceLimitEnabled: Boolean = false
    @Volatile private var flightLimitListenersRegistered: Boolean = false

    // .get(default) only reads KeyManager's cache and never triggers a live fetch, so these
    // stayed at their -1/false fallback forever even with the aircraft connected. Register
    // persistent listeners (idempotent, same pattern as Payload.kt's setupLaserMeasureListener)
    // so they reflect the aircraft's real configuration.
    //
    // A listener alone is not enough: it fires on *change*, and these are settings that rarely
    // change, so an aircraft that has held the same RTH altitude since power-on never publishes
    // one. That left the values reading -1 forever — visible over HTTP as
    // `GET /config/settings` reporting rthAltitude -1, and over MAVLink as an unreadable
    // LB_RTH_ALT. Each listener is therefore paired with one live fetch to seed the cache; the
    // listener then keeps it current.
    private fun setupFlightLimitListeners() {
        if (flightLimitListenersRegistered) return
        goHomeHeightKey.listen(this) { newValue: Int? -> cachedRTHAltitude = newValue ?: -1 }
        maxFlightHeightKey.listen(this) { newValue: Int? -> cachedMaxFlightHeight = newValue ?: -1 }
        maxFlightDistanceKey.listen(this) { newValue: Int? -> cachedMaxFlightDistance = newValue ?: -1 }
        distanceLimitEnabledKey.listen(this) { newValue: Boolean? -> cachedDistanceLimitEnabled = newValue ?: false }
        flightLimitListenersRegistered = true
        seedFlightLimits()
    }

    /**
     * Fetch each flight limit once, so the caches hold a real value before anything changes.
     *
     * Failures are logged and left alone rather than written as a default: -1 already means "not
     * known", and overwriting it on a failed read would turn "we have not been told" into a
     * confident wrong answer.
     */
    private fun seedFlightLimits() {
        goHomeHeightKey.get(
            { value -> value?.let { cachedRTHAltitude = it } },
            { error -> Log.w("DroneController", "Could not read RTH altitude: ${error.description()}") }
        )
        maxFlightHeightKey.get(
            { value -> value?.let { cachedMaxFlightHeight = it } },
            { error -> Log.w("DroneController", "Could not read max flight height: ${error.description()}") }
        )
        maxFlightDistanceKey.get(
            { value -> value?.let { cachedMaxFlightDistance = it } },
            { error -> Log.w("DroneController", "Could not read max flight distance: ${error.description()}") }
        )
        distanceLimitEnabledKey.get(
            { value -> value?.let { cachedDistanceLimitEnabled = it } },
            { error -> Log.w("DroneController", "Could not read distance limit: ${error.description()}") }
        )
    }

    fun getRTHAltitude(): Int {
        setupFlightLimitListeners()
        return cachedRTHAltitude
    }

    /**
     * Set the return-to-home altitude.
     *
     * The cache is updated from the aircraft's confirmation rather than from the requested value,
     * so a write DJI clamps or refuses does not leave us reporting a number the aircraft is not
     * holding — which is the difference between a setting a ground station can verify and one it
     * can only hope about.
     */
    fun setRTHAltitude(altitude: Int, onResult: ((Boolean) -> Unit)? = null) {
        goHomeHeightKey.set(
            altitude,
            {
                cachedRTHAltitude = altitude
                ToastUtils.showToast("RTH altitude set to $altitude m")
                onResult?.invoke(true)
            },
            { error ->
                Log.w("DroneController", "RTH altitude set failed: ${error.description()}")
                ToastUtils.showToast("RTH altitude change refused: ${error.description()}")
                onResult?.invoke(false)
            }
        )
    }

    fun getMaxFlightHeight(): Int {
        setupFlightLimitListeners()
        return cachedMaxFlightHeight
    }

    fun setMaxFlightHeight(height: Int) {
        maxFlightHeightKey.set(height)
        ToastUtils.showToast("Max flight height set to $height m")
    }

    fun getMaxFlightDistance(): Int {
        setupFlightLimitListeners()
        return cachedMaxFlightDistance
    }

    fun setMaxFlightDistance(distance: Int) {
        maxFlightDistanceKey.set(distance)
        ToastUtils.showToast("Max flight distance set to $distance m")
    }

    fun getDistanceLimitEnabled(): Boolean {
        setupFlightLimitListeners()
        return cachedDistanceLimitEnabled
    }

    fun setDistanceLimitEnabled(enabled: Boolean) {
        distanceLimitEnabledKey.set(enabled)
        ToastUtils.showToast("Distance limit ${if (enabled) "enabled" else "disabled"}")
    }

    // --- RC stick mode (ControlMode: JP / USA / CH / CUSTOM) ---
    private val controlModeKey: DJIKey<ControlMode> = RemoteControllerKey.KeyControlMode.create()

    fun getRcControlMode(): String = controlModeKey.get(ControlMode.UNKNOWN).name.lowercase()

    fun setRcControlMode(value: String): Boolean {
        val mode = runCatching { ControlMode.valueOf(value.uppercase()) }.getOrNull()
            ?: return false
        if (mode == ControlMode.UNKNOWN) return false
        controlModeKey.set(mode)
        ToastUtils.showToast("RC control mode set to ${mode.name}")
        return true
    }

    // --- RC pairing state + start/stop pairing actions ---
    private val pairingStatusKey: DJIKey<PairingState> = RemoteControllerKey.KeyPairingStatus.create()
    private val remoteControllerConnectionKey: DJIKey<Boolean> = RemoteControllerKey.KeyConnection.create()

    fun getRcPairingStatus(): String {
        val pairingState = pairingStatusKey.get(PairingState.UNKNOWN)
        return when {
            pairingState == PairingState.PAIRED -> "paired"
            pairingState == PairingState.PAIRING -> "pairing"
            pairingState == PairingState.UNPAIRED -> "unpaired"
            pairingState == PairingState.UNKNOWN && remoteControllerConnectionKey.get(false) -> "paired"
            else -> pairingState.name.lowercase()
        }
    }

    fun requestRcPairing() {
        RemoteControllerKey.KeyRequestPairing.create().action()
        ToastUtils.showToast("RC pairing requested")
    }

    fun stopRcPairing() {
        RemoteControllerKey.KeyStopPairing.create().action()
        ToastUtils.showToast("RC pairing stopped")
    }

    // --- HD transmission frequency band (read-only info) ---
    private val frequencyBandKey: DJIKey<FrequencyBand> = AirLinkKey.KeyFrequencyBand.create()

    fun getHdFrequencyBand(): String = when (frequencyBandKey.get(FrequencyBand.UNKNOWN)) {
        FrequencyBand.BAND_2_DOT_4G, FrequencyBand.BAND_1_DOT_4G -> "2.4g"
        FrequencyBand.BAND_MULTI -> "5g"
        else -> "unknown"
    }
}
