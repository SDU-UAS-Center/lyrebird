package com.lyrebird.rc.fleet

import android.content.Context
import android.net.wifi.WifiManager
import android.os.SystemClock
import android.util.Log
import com.lyrebird.rc.util.NetworkUtils
import org.json.JSONObject
import java.io.IOException
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.util.Collections
import kotlin.concurrent.thread

/**
 * The fleet mesh: one multicast group carrying every Lyrebird device's state beacon.
 *
 * Multicast rather than unicast, and one shared group rather than a session per pair, because the
 * cost model here is the radio and not the CPU. Every aircraft on a field day publishes video
 * over the same access point, and the endpoint that streams MAVLink telemetry already refuses to
 * broadcast its full message set for exactly this reason. A beacon at [BEACON_INTERVAL_MS] is a
 * few hundred bytes per device per tick no matter how many devices are listening, which is the
 * only shape of traffic that stays honest as the fleet grows.
 *
 * The link is strictly advisory. It carries state outward and settings offers that a human has to
 * accept; nothing arriving on this socket can command an aircraft, change a flight parameter, or
 * alter authority. That is a deliberate boundary, not an omission: the group is unauthenticated
 * and writable by anything on the access point.
 */
internal class FleetLink(
    private val context: Context,
    private val roster: FleetRoster,
    /**
     * This device's own identity, cheaply.
     *
     * Separate from [beaconProvider] because it is consulted on every inbound datagram to reject
     * this device's own multicast echo, while building a beacon reads a dozen values out of the
     * DJI SDK. Deriving the id from a freshly built beacon would put those reads on the receive
     * thread at twice the beacon rate times the size of the fleet.
     */
    private val ownDeviceId: () -> String,
    /** Builds the local device's current beacon, or null while identity is not resolved yet. */
    private val beaconProvider: () -> FleetBeacon?
) {

    companion object {
        private const val TAG = "LyrebirdFleet"

        /**
         * Shared with Lyrebird's existing discovery multicast group, on its own port so the two
         * protocols never have to parse each other's datagrams.
         */
        const val MULTICAST_GROUP = "239.255.42.99"
        const val MULTICAST_PORT = 30002

        /** Twice a second: fast enough for a closing-rate readout, slow enough to be free. */
        const val BEACON_INTERVAL_MS = 500L

        private const val RECEIVE_BUFFER_BYTES = 8192
        private const val JOIN_TIMEOUT_MS = 1_500L

        /** Multicast time-to-live of 1: the fleet is one link, never routed off it. */
        private const val MULTICAST_TTL = 1
    }

    /** Fired on the receive thread whenever the roster changed. */
    var onRosterChanged: (() -> Unit)? = null

    /** Fired on the receive thread when a peer offers its settings profile. */
    var onSettingsOffered: ((FleetSettingsOffer) -> Unit)? = null

    @Volatile
    var isRunning: Boolean = false
        private set

    private var socket: MulticastSocket? = null
    private var receiveThread: Thread? = null
    private var sendThread: Thread? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var sequence: Long = 0
    private val startedAtMs = SystemClock.elapsedRealtime()

    /**
     * The last beacon this device actually published.
     *
     * Exposed so the UI can range peers against the same state the fleet was told about, without
     * reading the DJI SDK again on the main thread. Building a beacon costs about ten synchronous
     * key reads, and the Flight Deck is already sharing that thread with the video pipeline.
     */
    @Volatile
    var lastSentBeacon: FleetBeacon? = null
        private set

    fun start() {
        if (isRunning) return
        isRunning = true
        acquireMulticastLock()

        receiveThread = thread(name = "Fleet-rx", start = true) {
            runCatching { openSocketAndReceive() }.onFailure { error ->
                if (isRunning) Log.w(TAG, "Fleet receive ended: ${error.message}")
            }
        }
        sendThread = thread(name = "Fleet-tx", start = true) {
            runCatching { beaconLoop() }.onFailure { error ->
                if (isRunning) Log.w(TAG, "Fleet beacon loop ended: ${error.message}")
            }
        }
        Log.i(TAG, "Fleet mesh up on $MULTICAST_GROUP:$MULTICAST_PORT")
    }

    fun stop() {
        if (!isRunning) return
        isRunning = false
        runCatching {
            val group = InetAddress.getByName(MULTICAST_GROUP)
            socket?.leaveGroup(group)
        }
        socket?.close()
        socket = null
        runCatching {
            if (Thread.currentThread() != receiveThread) receiveThread?.join(JOIN_TIMEOUT_MS)
            if (Thread.currentThread() != sendThread) sendThread?.join(JOIN_TIMEOUT_MS)
        }
        receiveThread = null
        sendThread = null
        lastSentBeacon = null
        releaseMulticastLock()
        roster.clear()
        Log.i(TAG, "Fleet mesh stopped")
    }

    /**
     * Offer this device's shareable settings to the fleet.
     *
     * Fire and forget, like every other datagram here: peers decide for themselves whether to
     * prompt, and nothing waits for an answer.
     */
    fun offerSettings(payload: JSONObject): Boolean {
        val bound = socket ?: return false
        return runCatching {
            val bytes = payload.toString().toByteArray(Charsets.UTF_8)
            bound.send(
                DatagramPacket(
                    bytes, bytes.size,
                    InetAddress.getByName(MULTICAST_GROUP), MULTICAST_PORT
                )
            )
            Log.i(TAG, "Offered a settings profile to the fleet (${bytes.size} bytes)")
            true
        }.getOrElse { error ->
            Log.w(TAG, "Could not offer settings: ${error.message}")
            false
        }
    }

    private fun openSocketAndReceive() {
        val bound = MulticastSocket(MULTICAST_PORT).apply {
            reuseAddress = true
            timeToLive = MULTICAST_TTL
            // Without this the stack can join on a cellular or virtual interface and never see
            // the access point the fleet is actually on.
            preferredInterface()?.let { runCatching { networkInterface = it } }
        }
        socket = bound
        val group = InetAddress.getByName(MULTICAST_GROUP)
        bound.joinGroup(group)

        val buffer = ByteArray(RECEIVE_BUFFER_BYTES)
        while (isRunning && !bound.isClosed) {
            val packet = DatagramPacket(buffer, buffer.size)
            val received = runCatching { bound.receive(packet); true }.getOrElse { false }
            if (!received) break
            if (packet.length <= 0) continue
            handleDatagram(buffer, packet.length, packet.address?.hostAddress.orEmpty())
        }
    }

    private fun handleDatagram(buffer: ByteArray, length: Int, sourceAddress: String) {
        when (FleetBeacon.messageType(buffer, length)) {
            FleetBeacon.TYPE_BEACON -> {
                val beacon = FleetBeacon.parse(buffer, length) ?: return
                val accepted = roster.onBeacon(beacon, sourceAddress, System.currentTimeMillis())
                if (accepted) onRosterChanged?.invoke()
            }
            FleetSettingsShare.TYPE_SETTINGS_OFFER -> {
                val offer = FleetSettingsShare.parseOffer(buffer, length) ?: return
                // A device's own offer comes back to it through multicast loopback.
                if (offer.fromDeviceId == ownDeviceId()) return
                onSettingsOffered?.invoke(offer)
            }
            else -> Unit
        }
    }

    private fun beaconLoop() {
        while (isRunning) {
            val bound = socket
            if (bound != null && !bound.isClosed) {
                sendBeacon(bound)
            }
            runCatching { Thread.sleep(BEACON_INTERVAL_MS) }.onFailure { return }
        }
    }

    private fun sendBeacon(bound: MulticastSocket) {
        val beacon = beaconProvider()?.copy(
            appUptimeMs = SystemClock.elapsedRealtime() - startedAtMs,
            sequence = ++sequence
        ) ?: return
        lastSentBeacon = beacon
        runCatching {
            val bytes = beacon.toBytes()
            bound.send(
                DatagramPacket(
                    bytes, bytes.size,
                    InetAddress.getByName(MULTICAST_GROUP), MULTICAST_PORT
                )
            )
        }.onFailure { error ->
            if (error is IOException) Log.d(TAG, "Beacon send failed: ${error.message}")
        }
    }

    /** The interface carrying the device's Wi-Fi address, which is the one the fleet is on. */
    private fun preferredInterface(): NetworkInterface? = runCatching {
        val deviceIp = NetworkUtils.getDeviceIpAddress() ?: return null
        Collections.list(NetworkInterface.getNetworkInterfaces()).firstOrNull { candidate ->
            candidate.isUp && !candidate.isLoopback &&
                Collections.list(candidate.inetAddresses).any { it.hostAddress == deviceIp }
        }
    }.getOrNull()

    /**
     * Android drops inbound multicast before it reaches the socket unless something holds a
     * multicast lock. Without this the device would transmit beacons perfectly and hear nothing,
     * which presents as an empty roster on every RC at once.
     */
    private fun acquireMulticastLock() {
        runCatching {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            multicastLock = wifi?.createMulticastLock("lyrebird-fleet")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        }.onFailure { error ->
            Log.w(TAG, "Could not acquire multicast lock: ${error.message}")
        }
    }

    private fun releaseMulticastLock() {
        runCatching { multicastLock?.takeIf { it.isHeld }?.release() }
        multicastLock = null
    }
}
