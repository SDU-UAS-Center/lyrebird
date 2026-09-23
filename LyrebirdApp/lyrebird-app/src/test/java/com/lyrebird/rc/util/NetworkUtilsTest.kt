package com.lyrebird.rc.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket

class NetworkUtilsTest {

    @Test
    fun deviceIpCandidateIdentifiesPreferredWifiOrAccessPointInterfaces() {
        val wifi = DeviceIpCandidate("192.168.1.15", "wlan0")
        val ap = DeviceIpCandidate("192.168.43.1", "ap0")
        val ethernet = DeviceIpCandidate("10.0.2.15", "eth0")
        val cellular = DeviceIpCandidate("10.120.4.5", "rmnet_data0")

        assertTrue(wifi.isPreferred)
        assertTrue(ap.isPreferred)
        assertFalse(ethernet.isPreferred)
        assertFalse(cellular.isPreferred)
    }

    @Test
    fun isPortInUseCorrectlyReportsFreeAndBusyPorts() {
        // Find an ephemeral free port
        val socket = ServerSocket(0)
        val port = socket.localPort
        
        // When socket is open, the port is busy
        assertTrue(NetworkUtils.isPortInUse(port))
        
        // Close the socket
        socket.close()
        
        // When socket is closed, the port is free
        assertFalse(NetworkUtils.isPortInUse(port))
    }
}
