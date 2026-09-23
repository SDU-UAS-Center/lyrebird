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
    private val beaconProvider: () -> FleetBeacon?,
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

        /** How long a thread that failed waits before its loop is restarted. */
        private const val RESTART_DELAY_MS = 1_000L

        /**
         * Ticks between repeated "nothing to send" warnings: about half a minute at
         * [BEACON_INTERVAL_MS], which is long enough to be a real fault and short enough to
         * notice on a bench.
         */
        private const val MISSING_BEACON_REPORT_TICKS = 60

        /**
         * Ticks an identity change keeps being announced: longer than the roster's forget
         * timeout, so a peer that was restarting when the change happened still hears it before
         * the superseded row would have aged out on its own.
         */
        private const val RETIRE_REPEAT_TICKS = 270

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

    /** The identity the last beacon carried, so a change can be announced instead of appearing as
     * a second aircraft. Null until the first beacon goes out. */
    private var lastAdvertisedDeviceId: String? = null

    /** Consecutive ticks that had no beacon to send, for the throttled warning in [sendBeacon]. */
    private var missingBeaconTicks: Int = 0

    /** Repeats the "this identity is gone" announcement (see [FleetRetireAnnouncer]). */
    private val retireAnnouncer = FleetRetireAnnouncer(RETIRE_REPEAT_TICKS)

    /**
     * When this *process* started, which is not the same thing as when this link started.
     *
     * This uptime is how a peer detects that another Lyrebird app restarted, and that matters
     * because command authority over an aircraft lives in that app's process and dies with it -
     * the one failure another RC cannot see any other way. Taken from the link instead of from the
     * process, a mesh restart under a running app (a network session coming back up) read as an
     * app restart on every other RC at once, and the fleet spent the next minute announcing a
     * restart that never happened.
     */
    private val processStartedAtMs = android.os.Process.getStartElapsedRealtime()

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

        receiveThread =
            thread(name = "Fleet-rx", start = true) {
                supervise("Fleet receive") { openSocketAndReceive() }
            }
        sendThread =
            thread(name = "Fleet-tx", start = true) {
                supervise("Fleet beacon loop") { beaconLoop() }
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
                    bytes,
                    bytes.size,
                    InetAddress.getByName(MULTICAST_GROUP),
                    MULTICAST_PORT,
                ),
            )
            Log.i(TAG, "Offered a settings profile to the fleet (${bytes.size} bytes)")
            true
        }.getOrElse { error ->
            Log.w(TAG, "Could not offer settings: ${error.message}")
            false
        }
    }

    private fun openSocketAndReceive() {
        val bound =
            MulticastSocket(MULTICAST_PORT).apply {
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
            val received =
                runCatching {
                    bound.receive(packet)
                    true
                }.getOrElse { false }
            if (!received) break
            if (packet.length <= 0) continue
            handleDatagram(buffer, packet.length, packet.address?.hostAddress.orEmpty())
        }
    }

    private fun handleDatagram(
        buffer: ByteArray,
        length: Int,
        sourceAddress: String,
    ) {
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
            else -> {
                val retiredId = FleetBeacon.parseRetire(buffer, length) ?: return
                if (retiredId == ownDeviceId()) return
                if (roster.forget(retiredId)) {
                    Log.i(TAG, "Peer ${retiredId.takeLast(8)} retired the identity it had before its aircraft connected")
                    onRosterChanged?.invoke()
                }
            }
        }
    }

    /**
     * Keep one of the mesh's loops running for as long as the mesh is supposed to be up.
     *
     * A loop that threw used to end for the lifetime of the process, which left a bound socket, a
     * roster full of peers, and this device absent from every other RC's fleet -- the same shape of
     * silent half-failure as a beacon provider that stops answering, and the reason both are now
     * loud and self-healing instead of invisible.
     */
    private fun supervise(
        what: String,
        loop: () -> Unit,
    ) {
        while (isRunning) {
            runCatching(loop).onFailure { error ->
                Log.w(TAG, "$what ended; restarting it in ${RESTART_DELAY_MS}ms", error)
            }
            if (!isRunning) return
            runCatching { Thread.sleep(RESTART_DELAY_MS) }
        }
    }

    private fun beaconLoop() {
        while (isRunning) {
            val bound = socket
            if (bound != null && !bound.isClosed) {
                runCatching { sendBeacon(bound) }.onFailure { error ->
                    Log.w(TAG, "Beacon tick failed; the mesh keeps running", error)
                }
            }
            runCatching { Thread.sleep(BEACON_INTERVAL_MS) }.onFailure { return }
        }
    }

    private fun sendBeacon(bound: MulticastSocket) {
        val beacon =
            beaconProvider()?.copy(
                appUptimeMs = SystemClock.elapsedRealtime() - processStartedAtMs,
                sequence = ++sequence,
            )
        if (beacon == null) {
            // The one failure with no other symptom: the mesh is bound and the roster is being
            // filled, while this device tells nobody anything. Say so instead of returning.
            missingBeaconTicks++
            if (missingBeaconTicks == 1 || missingBeaconTicks % MISSING_BEACON_REPORT_TICKS == 0) {
                Log.w(
                    TAG,
                    "No beacon to send: the mesh is up but this device is invisible to the fleet " +
                        "($missingBeaconTicks ticks)",
                )
            }
            return
        }
        missingBeaconTicks = 0
        retirePreviousIdentityIfChanged(beacon.deviceId)
        lastSentBeacon = beacon
        runCatching {
            val bytes = beacon.toBytes()
            bound.send(
                DatagramPacket(
                    bytes,
                    bytes.size,
                    InetAddress.getByName(MULTICAST_GROUP),
                    MULTICAST_PORT,
                ),
            )
        }.onFailure { error ->
            if (error is IOException) Log.d(TAG, "Beacon send failed: ${error.message}")
        }
        announceRetiredIdentity(bound)
    }

    /**
     * Note the identity this device is leaving behind, if it changed.
     *
     * The announcement itself is repeated by [announceRetiredIdentity] rather than sent from here:
     * a peer that is restarting when the identity changes is not listening yet, and the single
     * datagram it missed left it showing this aircraft twice — the second row a lost ghost — for
     * the whole forget timeout, which every other receiver reported as a fleet conflict.
     */
    private fun retirePreviousIdentityIfChanged(currentDeviceId: String) {
        val previous = lastAdvertisedDeviceId
        lastAdvertisedDeviceId = currentDeviceId
        if (previous == null || previous == currentDeviceId) return
        retireAnnouncer.announce(previous)
    }

    /** Send the next queued retire announcement, while the schedule still has one to send. */
    private fun announceRetiredIdentity(bound: MulticastSocket) {
        val retiredId = retireAnnouncer.next() ?: return
        sendDatagram(bound, FleetBeacon.retireBytes(retiredId)) { error ->
            Log.w(TAG, "Could not retire the previous identity: ${error.message}")
        }
    }

    private fun sendDatagram(
        bound: MulticastSocket,
        bytes: ByteArray,
        onError: (Throwable) -> Unit,
    ) {
        runCatching {
            bound.send(
                DatagramPacket(
                    bytes,
                    bytes.size,
                    InetAddress.getByName(MULTICAST_GROUP),
                    MULTICAST_PORT,
                ),
            )
        }.onFailure(onError)
    }

    /** The interface carrying the device's Wi-Fi address, which is the one the fleet is on. */
    private fun preferredInterface(): NetworkInterface? =
        runCatching {
            val deviceIp = NetworkUtils.getDeviceIpAddress() ?: return null
            Collections.list(NetworkInterface.getNetworkInterfaces()).firstOrNull { candidate ->
                candidate.isUp &&
                    !candidate.isLoopback &&
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
            multicastLock =
                wifi?.createMulticastLock("lyrebird-fleet")?.apply {
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
