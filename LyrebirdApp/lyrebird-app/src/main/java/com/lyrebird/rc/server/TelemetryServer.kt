package com.lyrebird.rc.server

import android.util.Log
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.concurrent.thread

class TelemetryServer(
    private val port: Int,
    private val telemetryProvider: () -> String,
    /**
     * Trimmed telemetry for a client that already has MAVLink — only fields with no MAVLink form
     * (phone GPS, WebRTC stats, streaming config, and the handful of numbers MAVLink never
     * carries). Requested per connection; see [MODE_GAP_REQUEST]. Defaults to [telemetryProvider]
     * so a caller that never sends gap requests is unaffected.
     */
    private val gapTelemetryProvider: () -> String = telemetryProvider,
    /**
     * Interval between telemetry pushes to connected clients, in milliseconds.
     *
     * Defaults to [DEFAULT_SEND_INTERVAL_MS] (~2Hz), the rate flown on the XPRIZE
     * airframes. The cache is rebuilt by SDK listeners rather than by this loop, so a
     * slower interval costs freshness rather than data. Raise it for ground stations
     * that consume high-rate telemetry over this socket; note that per-frame metadata
     * on the WebRTC path comes from TelemetryProvider and is unaffected by this value.
     */
    private val sendIntervalMs: Long = DEFAULT_SEND_INTERVAL_MS
) {
    private var serverSocket: ServerSocket? = null
    private val executor = Executors.newCachedThreadPool()
    @Volatile
    private var isRunning = false
    private var serverThread: Thread? = null
    private val clients = ConcurrentHashMap<Socket, ClientConnection>()

    /** Callback invoked when a bridge client connects. Receives the client's IP address. */
    var onFirstClientConnected: ((clientIp: String) -> Unit)? = null

    fun hasClients(): Boolean = clients.isNotEmpty()

    /** One connected socket and whether it asked for the trimmed, MAVLink-gap-only stream. */
    private class ClientConnection(val writer: PrintWriter) {
        @Volatile
        var gapOnly: Boolean = false
    }

    companion object {
        /** ~2Hz. */
        const val DEFAULT_SEND_INTERVAL_MS = 500L

        /**
         * Sent by a ground station that already reads MAVLink telemetry, as the first line on the
         * socket right after connecting, to ask for the trimmed stream instead of the full one.
         * Anything else the client sends (or nothing at all, within [MODE_DETECT_TIMEOUT_MS]) is
         * the full stream — every reader written before gap mode existed only ever read this
         * socket and never wrote to it, so silence must keep meaning "full stream" for them to
         * keep working unchanged.
         */
        const val MODE_GAP_REQUEST = "MODE=GAP"

        /** How long to wait, once, for [MODE_GAP_REQUEST] before assuming a full-stream client. */
        const val MODE_DETECT_TIMEOUT_MS = 200
    }

    fun start() {
        if (isRunning) return

        serverThread = thread(name = "TelemetryServer-$port", start = true) {
            runCatching {
                serverSocket = ServerSocket(port)
                isRunning = true
                Log.i("TelemetryServer", "Server started on port $port")

                // Start a thread to periodically send telemetry data to all connected clients
                executor.submit { sendTelemetryData() }

                while (isRunning && !serverSocket!!.isClosed) {
                    acceptNextClient()
                }
            }.onFailure { error ->
                Log.e("TelemetryServer", "Server error: ${error.message}")
            }
        }
    }

    private fun acceptNextClient() {
        runCatching {
            val clientSocket = serverSocket!!.accept()
            val clientIp = clientSocket.inetAddress.hostAddress ?: "unknown"
            Log.i("TelemetryServer", "Client connected: $clientIp")
            val connection = ClientConnection(PrintWriter(clientSocket.getOutputStream(), true))
            clients[clientSocket] = connection
            onFirstClientConnected?.invoke(clientIp)
            // Off the accept thread: a client that never speaks (every reader written before gap
            // mode existed) must not delay accepting the next connection.
            executor.submit { detectGapOnlyMode(clientSocket, connection) }
        }.onFailure { error ->
            if (isRunning) {
                Log.e("TelemetryServer", "Error accepting connection: ${error.message}")
            }
        }
    }

    private fun detectGapOnlyMode(socket: Socket, connection: ClientConnection) {
        runCatching {
            socket.soTimeout = MODE_DETECT_TIMEOUT_MS
            if (socket.getInputStream().bufferedReader().readLine()?.trim() == MODE_GAP_REQUEST) {
                connection.gapOnly = true
                Log.i("TelemetryServer", "Client requested gap-only telemetry (already on MAVLink)")
            }
        }
        runCatching { socket.soTimeout = 0 }
    }

    private fun sendTelemetryData() {
        while (isRunning) {
            runCatching {
                val fullJson = telemetryProvider()
                val gapJson = if (clients.values.any { it.gapOnly }) gapTelemetryProvider() else null
                removeDisconnectedClients(sendTelemetryToClients(fullJson, gapJson))
                Thread.sleep(sendIntervalMs)
            }.onFailure { error ->
                handleTelemetryLoopError(error)
            }
        }
    }

    private fun sendTelemetryToClients(fullJson: String, gapJson: String?): List<Socket> {
        return clients.mapNotNull { (socket, connection) ->
            if (socket.isClosed || !socket.isConnected) {
                socket
            } else {
                connection.writer.println(if (connection.gapOnly) gapJson ?: fullJson else fullJson)
                socket.takeIf { connection.writer.checkError() }
            }
        }
    }

    private fun removeDisconnectedClients(clientsToRemove: List<Socket>) {
        clientsToRemove.forEach { socket ->
            runCatching { socket.close() }
                .onFailure { error -> Log.d("TelemetryServer", "Socket close ignored: ${error.message}") }
            clients.remove(socket)
            Log.i("TelemetryServer", "Client disconnected and removed.")
        }
    }

    private fun handleTelemetryLoopError(error: Throwable) {
        if (error is InterruptedException) {
            Thread.currentThread().interrupt()
            isRunning = false
        } else {
            Log.e("TelemetryServer", "Error in telemetry sending loop: ${error.message}")
        }
    }

    fun stop() {
        isRunning = false
        onFirstClientConnected = null
        runCatching {
            clients.values.forEach { it.writer.close() }
            clients.keys.forEach { it.close() }
            clients.clear()
            serverSocket?.close()
            serverSocket = null
            executor.shutdownNow()
            if (Thread.currentThread() != serverThread) {
                serverThread?.join(1000)
            }
            serverThread = null
        }.onFailure { error -> Log.e("TelemetryServer", "Error stopping server: ${error.message}") }
    }
}

