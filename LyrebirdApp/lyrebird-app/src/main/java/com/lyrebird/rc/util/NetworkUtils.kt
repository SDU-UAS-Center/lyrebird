package com.lyrebird.rc.util

import android.util.Log
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.SocketException
import java.util.Collections

/**
 * A structure representing an IPv4 candidate found on a network interface.
 */
data class DeviceIpCandidate(
    val ip: String,
    val interfaceName: String
) {
    val isPreferred: Boolean
        get() = interfaceName.contains("wlan") || interfaceName.contains("ap")
}

/**
 * Clean utilities for scanning network interfaces and checking port availability.
 * Follows strict exception hygiene (naming ignored catches 'ignored').
 */
object NetworkUtils {
    private const val TAG = "NetworkUtils"

    // getDeviceIpAddress() is called from rebuildTelemetryCache(), which DJI's own high-rate
    // attitude/heading/speed/battery KeyManager listeners trigger on every update -- tens of
    // times per second in practice, not the occasional lookup this looked like in isolation.
    // NetworkInterface.getNetworkInterfaces() is a real syscall-backed enumeration, not a cheap
    // getter, so doing it uncached at that call frequency competes for CPU with the video
    // capture/encode pipeline running at the same time. A short TTL cache keeps the address
    // fresh enough to notice a real Wi-Fi change while cutting the re-scan rate by orders of
    // magnitude; deviceIpCandidates() itself stays uncached since nothing else calls it.
    private const val CACHE_TTL_MS = 2_000L
    @Volatile private var cachedIp: String? = null
    @Volatile private var cachedAtMs: Long = 0L

    /**
     * Resolves the primary device IP address, preferring Wi-Fi/Access Point interfaces.
     * Cached for [CACHE_TTL_MS] -- see the field comment above for why.
     */
    fun getDeviceIpAddress(): String? {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - cachedAtMs < CACHE_TTL_MS) return cachedIp

        val resolved = try {
            val candidates = deviceIpCandidates()
            candidates.forEach {
                Log.d(TAG, "Found IP: ${it.ip} on interface: ${it.interfaceName}")
            }
            (candidates.firstOrNull { it.isPreferred } ?: candidates.firstOrNull())?.ip
        } catch (ignored: SocketException) {
            null
        }
        cachedIp = resolved
        cachedAtMs = now
        return resolved
    }

    /**
     * Scans and returns all active IPv4 candidates across network interfaces.
     */
    fun deviceIpCandidates(): List<DeviceIpCandidate> {
        return try {
            Collections.list(NetworkInterface.getNetworkInterfaces())
                .filter { it.isUsableNetworkInterface() }
                .flatMap { it.ipv4Candidates() }
        } catch (ignored: SocketException) {
            emptyList()
        }
    }

    /**
     * Helper to verify if a local port is already in use by another socket.
     */
    fun isPortInUse(port: Int): Boolean {
        return try {
            ServerSocket(port).close()
            false
        } catch (ignored: IOException) {
            true
        }
    }

    private fun NetworkInterface.isUsableNetworkInterface(): Boolean {
        return isUp && !isLoopback
    }

    private fun NetworkInterface.ipv4Candidates(): List<DeviceIpCandidate> {
        val interfaceName = name.lowercase()
        return Collections.list(inetAddresses)
            .filterIsInstance<Inet4Address>()
            .filterNot { it.isLoopbackAddress }
            .mapNotNull { address ->
                val ip = address.hostAddress ?: return@mapNotNull null
                DeviceIpCandidate(ip = ip, interfaceName = interfaceName)
            }
    }
}
