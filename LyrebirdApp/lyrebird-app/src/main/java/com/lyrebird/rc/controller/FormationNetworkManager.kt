package com.lyrebird.rc.controller

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.lyrebird.rc.util.ToastUtils
import dji.sdk.keyvalue.value.common.LocationCoordinate3D
import org.java_websocket.WebSocket
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.handshake.ServerHandshake
import org.java_websocket.server.WebSocketServer
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.URI

/**
 * Manages WebSocket networking (server/client) and message serialization/deserialization
 * for Drone Formation control.
 * Decouples networking logic entirely from FormationController.
 */
object FormationNetworkManager {

    private const val TAG = "FormationNetwork"
    private const val WEBSOCKET_PORT = 8765

    private var webSocketServer: WebSocketServer? = null
    private var webSocketClient: WebSocketClient? = null

    // Listeners
    var onLeaderStateReceived: ((DroneState) -> Unit)? = null
    var onFollowerStateReceived: ((DroneState) -> Unit)? = null
    var onFormationStartRequested: (() -> Unit)? = null
    var onFormationStopRequested: (() -> Unit)? = null
    var onEmergencyStopRequested: (() -> Unit)? = null
    var onConnectionClosed: (() -> Unit)? = null

    val isServerRunning: Boolean
        get() = webSocketServer != null

    val isClientConnected: Boolean
        get() = webSocketClient?.isOpen == true

    fun startServer() {
        runCatching {
            webSocketServer = FormationWebSocketServer(InetSocketAddress(WEBSOCKET_PORT))
            webSocketServer?.start()
            Log.i(TAG, "WebSocket server started on port $WEBSOCKET_PORT")
        }.onFailure { failure ->
            Log.e(TAG, "Failed to start WebSocket server", failure)
            ToastUtils.showToast("Failed to start server: ${failure.message}")
        }
    }

    fun connectToLeader(ipAddress: String) {
        runCatching {
            val uri = URI("ws://$ipAddress:$WEBSOCKET_PORT")
            webSocketClient = FormationWebSocketClient(uri)
            webSocketClient?.connect()
        }.onFailure { failure ->
            Log.e(TAG, "Failed to connect to leader", failure)
            ToastUtils.showToast("Failed to connect: ${failure.message}")
        }
    }

    fun broadcastCommand(type: String, data: Map<String, Any>) {
        webSocketServer?.broadcast(createCommandJson(type, data))
    }

    fun sendToLeader(type: String, data: Map<String, Any>) {
        webSocketClient?.send(createCommandJson(type, data))
    }

    fun stop() {
        runCatching {
            webSocketServer?.stop()
            webSocketClient?.close()
        }
        webSocketServer = null
        webSocketClient = null
    }

    fun getConnectionsCount(): Int {
        return webSocketServer?.connections?.size ?: 0
    }

    private fun createCommandJson(type: String, data: Map<String, Any>): String {
        val json = JSONObject()
        json.put("type", type)
        json.put("data", JSONObject(data))
        json.put("timestamp", System.currentTimeMillis())
        return json.toString()
    }

    fun configToMap(config: FormationConfig): Map<String, Any> {
        return mapOf(
            "distanceBehind" to config.distanceBehind,
            "altitudeOffset" to config.altitudeOffset,
            "maxFormationDistance" to config.maxFormationDistance,
            "formationType" to config.formationType.name
        )
    }

    fun positionToMap(position: LocationCoordinate3D): Map<String, Double> {
        return mapOf(
            "latitude" to position.latitude,
            "longitude" to position.longitude,
            "altitude" to position.altitude
        )
    }

    private fun handleIncomingMessage(message: String) {
        runCatching {
            val json = JSONObject(message)
            val type = json.getString("type")
            val data = json.getJSONObject("data")

            when (type) {
                "leader_state" -> {
                    val position = data.getJSONObject("position")
                    val state = DroneState(
                        position = LocationCoordinate3D(
                            position.getDouble("latitude"),
                            position.getDouble("longitude"),
                            position.getDouble("altitude")
                        ),
                        heading = data.getDouble("heading"),
                        battery = data.getInt("battery"),
                        isConnected = true,
                        timestamp = data.getLong("timestamp")
                    )
                    onLeaderStateReceived?.invoke(state)
                }
                "follower_state" -> {
                    val position = data.getJSONObject("position")
                    val state = DroneState(
                        position = LocationCoordinate3D(
                            position.getDouble("latitude"),
                            position.getDouble("longitude"),
                            position.getDouble("altitude")
                        ),
                        heading = data.getDouble("heading"),
                        battery = data.getInt("battery"),
                        isConnected = true
                    )
                    onFollowerStateReceived?.invoke(state)
                }
                "formation_start" -> {
                    onFormationStartRequested?.invoke()
                }
                "formation_stop", "emergency_stop" -> {
                    if (type == "emergency_stop") {
                        onEmergencyStopRequested?.invoke()
                    } else {
                        onFormationStopRequested?.invoke()
                    }
                }
            }
        }.onFailure { failure -> Log.e(TAG, "Error parsing message: $message", failure) }
    }

    private class FormationWebSocketServer(address: InetSocketAddress) : WebSocketServer(address) {
        override fun onOpen(conn: WebSocket?, handshake: ClientHandshake?) {
            Log.i(TAG, "Follower connected: ${conn?.remoteSocketAddress}")
            Handler(Looper.getMainLooper()).post {
                ToastUtils.showToast("Follower connected")
            }
        }

        override fun onClose(conn: WebSocket?, code: Int, reason: String?, remote: Boolean) {
            Log.i(TAG, "Follower disconnected: $reason")
            Handler(Looper.getMainLooper()).post {
                ToastUtils.showToast("Follower disconnected")
                onConnectionClosed?.invoke()
            }
        }

        override fun onMessage(conn: WebSocket?, message: String?) {
            message?.let { handleIncomingMessage(it) }
        }

        override fun onError(conn: WebSocket?, ex: Exception?) {
            Log.e(TAG, "WebSocket server error", ex)
        }

        override fun onStart() {
            Log.i(TAG, "WebSocket server started successfully")
        }
    }

    private class FormationWebSocketClient(uri: URI) : WebSocketClient(uri) {
        override fun onOpen(handshake: ServerHandshake?) {
            Log.i(TAG, "Connected to leader")
            Handler(Looper.getMainLooper()).post {
                ToastUtils.showToast("Connected to leader")
            }
        }

        override fun onMessage(message: String?) {
            message?.let { handleIncomingMessage(it) }
        }

        override fun onClose(code: Int, reason: String?, remote: Boolean) {
            Log.i(TAG, "Disconnected from leader: $reason")
            Handler(Looper.getMainLooper()).post {
                ToastUtils.showToast("Disconnected from leader")
                onConnectionClosed?.invoke()
            }
        }

        override fun onError(ex: Exception?) {
            Log.e(TAG, "WebSocket client error", ex)
            Handler(Looper.getMainLooper()).post {
                ToastUtils.showToast("Connection error: ${ex?.message}")
            }
        }
    }
}
