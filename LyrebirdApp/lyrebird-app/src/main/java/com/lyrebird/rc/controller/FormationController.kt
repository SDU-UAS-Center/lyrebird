package com.lyrebird.rc.controller

import android.os.Handler
import android.os.Looper
import android.util.Log
import dji.sdk.keyvalue.key.BatteryKey
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.value.common.LocationCoordinate3D
import com.lyrebird.rc.util.ToastUtils
import dji.v5.et.create
import dji.v5.et.get

data class FormationConfig(
    val distanceBehind: Double = 1.0, // meters behind leader
    val altitudeOffset: Double = 2.0, // meters above leader
    val maxFormationDistance: Double = 10.0, // break formation if too far
    val formationType: FormationType = FormationType.BEHIND
)

enum class FormationType {
    BEHIND, BESIDE, DIAGONAL
}

enum class DroneRole {
    LEADER, FOLLOWER, NONE
}

data class DroneState(
    val position: LocationCoordinate3D,
    val heading: Double,
    val battery: Int,
    val isConnected: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)

data class FormationCommand(
    val type: String,
    val data: Map<String, Any>
)

@Suppress("TooManyFunctions")
object FormationController {

    private const val TAG = "FormationController"
    private const val UPDATE_INTERVAL = 100L // ms

    // DJI SDK Keys for telemetry
    private val location3DKey = FlightControllerKey.KeyAircraftLocation3D.create()
    private val compassHeadKey = FlightControllerKey.KeyCompassHeading.create()
    private val batteryPercentKey = BatteryKey.KeyChargeRemainingInPercent.create()

    var role: DroneRole = DroneRole.NONE
        private set
    var isFormationActive = false
        private set
    var config = FormationConfig()
        private set

    private var leaderState: DroneState? = null
    private var followerState: DroneState? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var formationUpdateRunnable: Runnable? = null

    // Safety flags
    private var emergencyStopRequested = false
    private var collisionAvoidanceActive = false

    init {
        FormationNetworkManager.onLeaderStateReceived = { state ->
            if (role == DroneRole.FOLLOWER) {
                leaderState = state
            }
        }
        FormationNetworkManager.onFollowerStateReceived = { state ->
            if (role == DroneRole.LEADER) {
                followerState = state
            }
        }
        FormationNetworkManager.onFormationStartRequested = {
            if (role == DroneRole.FOLLOWER && !isFormationActive) {
                startFormation()
            }
        }
        FormationNetworkManager.onFormationStopRequested = {
            if (isFormationActive) {
                stopFormation()
            }
        }
        FormationNetworkManager.onEmergencyStopRequested = {
            if (isFormationActive) {
                stopFormation()
            }
            emergencyStopRequested = true
            DroneController.setStick(0F, 0F, 0F, 0F)
        }
        FormationNetworkManager.onConnectionClosed = {
            if (isFormationActive) {
                stopFormation()
            }
        }
    }

    fun initAsLeader() {
        role = DroneRole.LEADER
        FormationNetworkManager.startServer()
        ToastUtils.showToast("Initialized as Formation Leader")
    }

    fun initAsFollower(leaderIpAddress: String) {
        role = DroneRole.FOLLOWER
        FormationNetworkManager.connectToLeader(leaderIpAddress)
        ToastUtils.showToast("Connecting to Leader at $leaderIpAddress")
    }

    fun startFormation() {
        if (role == DroneRole.NONE) {
            ToastUtils.showToast("Please select Leader or Follower role first")
            return
        }

        if (role == DroneRole.FOLLOWER && leaderState == null) {
            ToastUtils.showToast("Not connected to leader")
            return
        }

        isFormationActive = true
        emergencyStopRequested = false
        startFormationControl()

        // Notify other drone
        if (role == DroneRole.LEADER) {
            val cfgMap = FormationNetworkManager.configToMap(config)
            FormationNetworkManager.broadcastCommand("formation_start", mapOf("config" to cfgMap))
        } else {
            FormationNetworkManager.sendToLeader("formation_ready", mapOf("follower_ready" to true))
        }

        ToastUtils.showToast("Formation Started")
    }

    fun stopFormation() {
        isFormationActive = false
        stopFormationControl()

        // Notify other drone
        if (role == DroneRole.LEADER) {
            FormationNetworkManager.broadcastCommand("formation_stop", mapOf("reason" to "manual_stop"))
        } else {
            FormationNetworkManager.sendToLeader("formation_stop", mapOf("reason" to "manual_stop"))
        }

        // Stop drone movement
        DroneController.setStick(0F, 0F, 0F, 0F)
        ToastUtils.showToast("Formation Stopped")
    }

    fun emergencyStop() {
        emergencyStopRequested = true
        isFormationActive = false
        stopFormationControl()

        // Stop both drones immediately
        DroneController.setStick(0F, 0F, 0F, 0F)

        // Notify other drone
        if (role == DroneRole.LEADER) {
            FormationNetworkManager.broadcastCommand("emergency_stop", mapOf("reason" to "emergency"))
        } else {
            FormationNetworkManager.sendToLeader("emergency_stop", mapOf("reason" to "emergency"))
        }

        ToastUtils.showToast("EMERGENCY STOP ACTIVATED")
    }

    private fun startFormationControl() {
        formationUpdateRunnable = object : Runnable {
            override fun run() {
                if (isFormationActive && !emergencyStopRequested) {
                    updateFormation()
                    mainHandler.postDelayed(this, UPDATE_INTERVAL)
                }
            }
        }
        mainHandler.post(formationUpdateRunnable!!)
    }

    private fun stopFormationControl() {
        formationUpdateRunnable?.let { mainHandler.removeCallbacks(it) }
        formationUpdateRunnable = null
    }

    private fun updateFormation() {
        when (role) {
            DroneRole.LEADER -> updateAsLeader()
            DroneRole.NONE -> return
            DroneRole.FOLLOWER -> TODO()
        }
    }

    private fun updateAsLeader() {
        val currentState = getCurrentDroneState()

        // Broadcast leader state to followers
        FormationNetworkManager.broadcastCommand("leader_state", mapOf(
            "position" to FormationNetworkManager.positionToMap(currentState.position),
            "heading" to currentState.heading,
            "battery" to currentState.battery,
            "timestamp" to currentState.timestamp
        ))

        // Check if follower is too far away
        followerState?.let { follower ->
            val distance = DroneController.calculateDistance(
                currentState.position.latitude, currentState.position.longitude,
                follower.position.latitude, follower.position.longitude
            )

            if (distance > config.maxFormationDistance) {
                ToastUtils.showToast("Follower too far away - breaking formation")
                stopFormation()
            }
        }
    }

    private fun getCurrentDroneState(): DroneState {
        return runCatching {
            val position = location3DKey.get(LocationCoordinate3D(0.0, 0.0, 0.0))
            val heading = compassHeadKey.get(0.0)
            val battery = batteryPercentKey.get(0)

            DroneState(
                position = position,
                heading = heading,
                battery = battery,
                isConnected = true,
                timestamp = System.currentTimeMillis()
            )
        }.getOrElse { failure ->
            Log.e(TAG, "Error getting drone state", failure)
            DroneState(
                position = LocationCoordinate3D(0.0, 0.0, 0.0),
                heading = 0.0,
                battery = 0,
                isConnected = false
            )
        }
    }

    fun getConnectionStatus(): String {
        return when (role) {
            DroneRole.LEADER -> {
                if (FormationNetworkManager.getConnectionsCount() > 0) {
                    "Follower Connected"
                } else {
                    "Waiting for Follower"
                }
            }
            DroneRole.FOLLOWER -> {
                if (FormationNetworkManager.isClientConnected) {
                    "Connected to Leader"
                } else {
                    "Disconnected"
                }
            }
            DroneRole.NONE -> "No Role Selected"
        }
    }

    fun getFormationStatus(): String {
        return when {
            emergencyStopRequested -> "EMERGENCY STOP"
            collisionAvoidanceActive -> "COLLISION AVOIDANCE"
            isFormationActive -> "Formation Active"
            else -> "Formation Inactive"
        }
    }

    fun getOtherDroneState(): DroneState? {
        return when (role) {
            DroneRole.LEADER -> followerState
            DroneRole.FOLLOWER -> leaderState
            DroneRole.NONE -> null
        }
    }

    fun cleanup() {
        stopFormation()
        mainHandler.removeCallbacksAndMessages(null)
        FormationNetworkManager.stop()
        leaderState = null
        followerState = null
        role = DroneRole.NONE
    }
}
