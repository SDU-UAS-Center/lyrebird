package com.lyrebird.rc.server

import android.util.Log

/**
 * The session's advertisement: mDNS for the service, UDP broadcast/multicast for the fallback
 * probe.
 *
 * [advertise] is given only the ports that bound, so a session that is half up advertises itself
 * as half up instead of publishing a port nothing is listening on.
 */
internal class DiscoveryAdvertiser(
    private val discovery: LyrebirdDiscoveryManager,
    private val droneSerialNumber: () -> String,
) : SessionAdvertiser {
    override fun advertise(
        httpPort: Int?,
        telemetryPort: Int?,
    ) {
        // mDNS carries the command port as its service port, so a session whose command server did
        // not bind has nothing to register. The UDP responder still answers either way: its reply
        // names the device, not a port.
        if (httpPort != null) {
            discovery.registerMdnsService(droneSerialNumber(), httpPort, telemetryPort)
        } else {
            Log.w(TAG, "Command server is not answering; not registering mDNS")
        }
        discovery.startDiscoveryServer()
    }

    override fun stopAdvertising() {
        discovery.unregisterMdnsService()
        discovery.stopDiscoveryServer()
    }

    private companion object {
        private const val TAG = "DiscoveryAdvertiser"
    }
}
