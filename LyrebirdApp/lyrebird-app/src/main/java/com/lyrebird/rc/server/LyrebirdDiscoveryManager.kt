package com.lyrebird.rc.server

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import com.lyrebird.rc.util.NetworkUtils
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.MulticastSocket
import java.lang.ref.WeakReference
import kotlin.concurrent.thread

/**
 * Manages the UDP broadcast/multicast discovery servers and Zeroconf (mDNS) registration.
 * Keeps socket thread lifecycle separate from Android lifecycle.
 * Fully compliant with Detekt code quality and exception hygiene.
 */
class LyrebirdDiscoveryManager(
    private val context: Context,
    private val droneNameProvider: () -> String
) {
    companion object {
        private const val TAG = "LyrebirdDiscovery"
        private const val DISCOVERY_PORT = 30000
        private const val DISCOVERY_MSG = "DISCOVER_LYREBIRD"
        private const val DISCOVERY_RESPONSE_PREFIX = "LYREBIRD_HERE:"
        private const val MULTICAST_GROUP = "239.255.42.99"
        private const val MULTICAST_PORT = 30001
        private const val MDNS_SERVICE_TYPE = "_lyrebird._tcp."
    }

    // UDP Discovery state
    private var discoverySocket: DatagramSocket? = null
    private var multicastSocket: MulticastSocket? = null
    private var discoveryThread: Thread? = null
    private var multicastThread: Thread? = null
    
    @Volatile 
    var isDiscoveryRunning: Boolean = false
        private set

    // mDNS state
    private var nsdManager: NsdManager? = null
    private val registrationListener = MdnsRegistrationListener(this)
    
    @Volatile 
    var isMdnsRegistered: Boolean = false
        private set
        
    @Volatile 
    var isMdnsRegistrationRequested: Boolean = false
        private set
        
    @Volatile 
    var mdnsServiceName: String? = null
        private set

    /**
     * Spawns UDP broadcast and multicast listeners.
     */
    fun startDiscoveryServer() {
        if (isDiscoveryRunning) return
        isDiscoveryRunning = true

        // Thread 1: Handle broadcast/unicast UDP on port 30000
        discoveryThread = thread(name = "UDP-Discovery", start = true) {
            try {
                discoverySocket = DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    bind(java.net.InetSocketAddress("0.0.0.0", DISCOVERY_PORT))
                }

                val buffer = ByteArray(1024)
                Log.i(TAG, "✓ Discovery server started on 0.0.0.0:$DISCOVERY_PORT (broadcast enabled)")

                while (isDiscoveryRunning) {
                    val socket = discoverySocket ?: break
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    val message = String(packet.data, 0, packet.length).trim()

                    Log.d(TAG, "📡 UDP from ${packet.address.hostAddress}:${packet.port}: $message")

                    if (message == DISCOVERY_MSG) {
                        respondToDiscovery(packet.address, packet.port)
                    }
                }
            } catch (ignored: IOException) {
                // Ignore socket closed / read errors in expected close path
            } finally {
                discoverySocket?.close()
                discoverySocket = null
                Log.i(TAG, "Discovery server stopped")
            }
        }

        // Thread 2: Handle multicast on 239.255.42.99:30001
        multicastThread = thread(name = "UDP-Multicast", start = true) {
            try {
                multicastSocket = MulticastSocket(MULTICAST_PORT).apply {
                    reuseAddress = true
                }
                val group = InetAddress.getByName(MULTICAST_GROUP)
                multicastSocket?.joinGroup(group)

                val buffer = ByteArray(1024)
                Log.i(TAG, "✓ Multicast discovery started on $MULTICAST_GROUP:$MULTICAST_PORT")

                while (isDiscoveryRunning) {
                    val socket = multicastSocket ?: break
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    val message = String(packet.data, 0, packet.length).trim()

                    Log.d(TAG, "📡 Multicast from ${packet.address.hostAddress}: $message")

                    if (message == DISCOVERY_MSG) {
                        respondToDiscovery(packet.address, MULTICAST_PORT)
                    }
                }

                multicastSocket?.leaveGroup(group)
            } catch (ignored: IOException) {
                // Ignore socket closed / read errors in expected close path
            } finally {
                multicastSocket?.close()
                multicastSocket = null
                Log.i(TAG, "Multicast discovery stopped")
            }
        }
    }

    private fun respondToDiscovery(senderAddress: InetAddress, senderPort: Int) {
        val deviceIp = NetworkUtils.getDeviceIpAddress()
        val droneName = droneNameProvider()
        Log.i(TAG, "🔍 Discovery request from ${senderAddress.hostAddress}. My IP: $deviceIp")

        if (deviceIp != null) {
            val response = "$DISCOVERY_RESPONSE_PREFIX$deviceIp:$droneName"
            val responseData = response.toByteArray()

            try {
                val responsePacket = DatagramPacket(
                    responseData,
                    responseData.size,
                    senderAddress,
                    senderPort
                )
                val socketToUse = if (senderPort == MULTICAST_PORT) multicastSocket else discoverySocket
                socketToUse?.send(responsePacket)
                Log.i(TAG, "✓ Sent discovery response to ${senderAddress.hostAddress}:$senderPort → $response")
            } catch (e: IOException) {
                Log.e(TAG, "Failed to send discovery response: ${e.message}", e)
            }
        }
    }

    /**
     * Stop discovery servers and close underlying sockets.
     */
    fun stopDiscoveryServer() {
        isDiscoveryRunning = false
        try {
            discoverySocket?.close()
            multicastSocket?.close()
        } catch (ignored: IOException) {
            // Ignore close exceptions safely
        }
        try {
            discoveryThread?.join(1000)
            multicastThread?.join(1000)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            Log.w(TAG, "Interrupted while waiting for discovery threads to stop", e)
        }
        Log.i(TAG, "All discovery servers stopped")
    }

    // ==================== mDNS (Zeroconf) ====================

    /**
     * Registers Zeroconf/mDNS service for drone HTTP, telemetry, and WHIP publish.
     */
    @Suppress("TooGenericExceptionCaught")
    fun registerMdnsService(droneSerialNumber: String, httpPort: Int, telemetryPort: Int) {
        val droneName = droneNameProvider()
        try {
            nsdManager = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager

            val serviceInfo = NsdServiceInfo().apply {
                serviceName = droneName
                serviceType = MDNS_SERVICE_TYPE
                port = httpPort

                setAttribute("name", droneName)
                setAttribute("serial", droneSerialNumber)
                setAttribute("http", httpPort.toString())
                setAttribute("telemetry", telemetryPort.toString())
                setAttribute("video", "whip")
            }

            isMdnsRegistrationRequested = true
            nsdManager?.registerService(
                serviceInfo,
                NsdManager.PROTOCOL_DNS_SD,
                registrationListener
            )
            Log.i(TAG, "Registering mDNS service: $droneName.$MDNS_SERVICE_TYPE")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register mDNS service: ${e.message}", e)
            isMdnsRegistrationRequested = false
            isMdnsRegistered = false
            nsdManager = null
        }
    }

    /**
     * Unregisters Zeroconf/mDNS service.
     */
    @Suppress("TooGenericExceptionCaught")
    fun unregisterMdnsService() {
        if (isMdnsRegistered || isMdnsRegistrationRequested) {
            try {
                nsdManager?.unregisterService(registrationListener)
                Log.i(TAG, "Unregistering mDNS service")
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering mDNS: ${e.message}", e)
            } finally {
                isMdnsRegistrationRequested = false
                isMdnsRegistered = false
                mdnsServiceName = null
                nsdManager = null
            }
        }
    }

    private class MdnsRegistrationListener(manager: LyrebirdDiscoveryManager) : NsdManager.RegistrationListener {
        private val managerRef = WeakReference(manager)

        override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
            val m = managerRef.get() ?: return
            m.mdnsServiceName = serviceInfo.serviceName
            m.isMdnsRegistrationRequested = false
            m.isMdnsRegistered = true
            Log.i(TAG, "✓ mDNS service registered: ${serviceInfo.serviceName} (${MDNS_SERVICE_TYPE})")
        }

        override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            Log.e(TAG, "✗ mDNS registration failed: error $errorCode")
            val m = managerRef.get() ?: return
            m.isMdnsRegistrationRequested = false
            m.isMdnsRegistered = false
        }

        override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
            Log.i(TAG, "mDNS service unregistered: ${serviceInfo.serviceName}")
            val m = managerRef.get() ?: return
            m.isMdnsRegistrationRequested = false
            m.isMdnsRegistered = false
            m.mdnsServiceName = null
            m.nsdManager = null
        }

        override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            Log.e(TAG, "mDNS unregistration failed: error $errorCode")
            val m = managerRef.get() ?: return
            m.isMdnsRegistrationRequested = false
            m.isMdnsRegistered = false
            m.mdnsServiceName = null
            m.nsdManager = null
        }
    }
}
