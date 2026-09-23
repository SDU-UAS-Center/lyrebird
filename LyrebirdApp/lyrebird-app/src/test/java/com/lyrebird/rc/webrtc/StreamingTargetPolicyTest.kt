package com.lyrebird.rc.webrtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingTargetPolicyTest {
    @Test
    fun healthyPublisherForSameClientIsKept() {
        val decision =
            StreamingTargetPolicy.decide(
                "10.0.0.2", "http://relay/drone/whip", PublisherState.PUBLISHING, "10.0.0.2",
            )

        assertFalse(decision.shouldStart)
        assertEquals("10.0.0.2", decision.targetIp)
    }

    @Test
    fun aPublishStillStartingIsNotRestartedByARepeatedConnect() {
        // WHIP spends seconds gathering ICE candidates before it can send anything. A ground
        // station that connects again in that window must not restart the attempt: the publisher
        // would be torn down and rebuilt, the gather would start over, and the stream would never
        // come up at all.
        val decision =
            StreamingTargetPolicy.decide(
                "10.0.0.2", "http://relay/drone/whip", PublisherState.STARTING, "10.0.0.2",
            )

        assertFalse(decision.shouldStart)
        assertEquals("10.0.0.2", decision.targetIp)
    }

    @Test
    fun anAbandonedPublisherIsRebuiltForTheClientThatAsksAgain() {
        val decision =
            StreamingTargetPolicy.decide(
                "10.0.0.2", "http://relay/drone/whip", PublisherState.NONE, "10.0.0.2",
            )

        assertTrue(decision.shouldStart)
        assertEquals("10.0.0.2", decision.targetIp)
    }

    @Test
    fun restartWithoutAClientOrAConfiguredServerHasNoTarget() {
        val target =
            StreamingTargetPolicy.restartTargetOrNull(
                previousClientIp = null,
                previousWhipHost = null,
                configuredServer = "",
                deviceIp = "10.0.0.9",
            )

        assertNull(target)
    }

    @Test
    fun restartPrefersTheRememberedClientThenTheLastUrlHost() {
        assertEquals(
            "10.0.0.2",
            StreamingTargetPolicy.restartTargetOrNull("10.0.0.2", "10.0.0.7", configuredServer = "", deviceIp = "10.0.0.9"),
        )
        assertEquals(
            "10.0.0.7",
            StreamingTargetPolicy.restartTargetOrNull(null, "10.0.0.7", configuredServer = "", deviceIp = "10.0.0.9"),
        )
    }

    @Test
    fun restartWithAConfiguredServerPublishesWithoutAClient() {
        assertEquals(
            "10.0.0.9",
            StreamingTargetPolicy.restartTargetOrNull(null, null, configuredServer = "mediamtx.local", deviceIp = "10.0.0.9"),
        )
    }

    @Test
    fun healthyPublisherIsNotHijackedByAnotherClient() {
        val decision =
            StreamingTargetPolicy.decide(
                "10.0.0.2", "http://relay/drone/whip", PublisherState.PUBLISHING, "10.0.0.3",
            )

        assertFalse(decision.shouldStart)
        assertEquals("10.0.0.2", decision.targetIp)
    }

    @Test
    fun aStartingPublisherRetargetsToTheClientThatAsksNow() {
        val decision =
            StreamingTargetPolicy.decide(
                "10.0.0.2", "http://relay/drone/whip", PublisherState.STARTING, "10.0.0.3",
            )

        assertTrue(decision.shouldStart)
        assertEquals("10.0.0.3", decision.targetIp)
    }

    @Test
    fun stalePublisherCanRetarget() {
        val decision =
            StreamingTargetPolicy.decide(
                "10.0.0.2", "http://relay/drone/whip", PublisherState.NONE, "10.0.0.3",
            )

        assertTrue(decision.shouldStart)
        assertEquals("10.0.0.3", decision.targetIp)
    }
}