package com.lyrebird.rc.webrtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingTargetPolicyTest {
    @Test
    fun healthyPublisherForSameClientIsKept() {
        val decision = StreamingTargetPolicy.decide("10.0.0.2", "http://relay/drone/whip", true, "10.0.0.2")

        assertFalse(decision.shouldStart)
        assertEquals("10.0.0.2", decision.targetIp)
    }

    @Test
    fun healthyPublisherIsNotHijackedByAnotherClient() {
        val decision = StreamingTargetPolicy.decide("10.0.0.2", "http://relay/drone/whip", true, "10.0.0.3")

        assertFalse(decision.shouldStart)
        assertEquals("10.0.0.2", decision.targetIp)
    }

    @Test
    fun stalePublisherCanRetarget() {
        val decision = StreamingTargetPolicy.decide("10.0.0.2", "http://relay/drone/whip", false, "10.0.0.3")

        assertTrue(decision.shouldStart)
        assertEquals("10.0.0.3", decision.targetIp)
    }
}