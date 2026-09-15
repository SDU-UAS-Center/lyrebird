package com.lyrebird.rc.webrtc

import org.junit.Assert.assertEquals
import org.junit.Test

class WhipEndpointTest {
    @Test
    fun blankServerUsesClientAddress() {
        assertEquals(
            "http://10.0.0.4:8889/drone-1/whip",
            WhipEndpoint.url("10.0.0.4", "drone-1", ""),
        )
    }

    @Test
    fun configuredServerPreservesItsExplicitPortAndScheme() {
        assertEquals(
            "http://relay.example:9999/drone-1/whip",
            WhipEndpoint.url("10.0.0.4", "drone-1", "https://relay.example:9999/"),
        )
    }

    @Test
    fun configuredHostGetsTheDefaultWhipPort() {
        assertEquals(
            "http://relay.example:8889/lb_unknown/whip",
            WhipEndpoint.url("10.0.0.4", "", "relay.example"),
        )
    }
}