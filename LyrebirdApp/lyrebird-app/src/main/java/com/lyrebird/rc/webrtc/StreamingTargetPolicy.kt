package com.lyrebird.rc.webrtc

internal data class StreamingTargetDecision(
    val shouldStart: Boolean,
    val targetIp: String,
)

/**
 * How far along the publisher is, from the point of view of deciding whether to touch it.
 *
 * [STARTING] is not [PUBLISHING] with a delay: WHIP spends seconds gathering ICE candidates
 * before it can send a single packet, and tearing that attempt down to build an identical one
 * makes the wait start over. It is also not [NONE]: there is a live attempt worth keeping.
 */
internal enum class PublisherState { NONE, STARTING, PUBLISHING }

internal object StreamingTargetPolicy {
    /**
     * [desiredWhipUrl] is where a publish for [clientIp] would go now. When it differs from
     * [previousWhipUrl] the drone was renamed (the name is the URL path), and keeping the healthy
     * publisher would leave the video under the old name while everything else moved to the new
     * one. Null means "no opinion", for callers that do not build a WHIP URL.
     */
    fun decide(
        previousClientIp: String?,
        previousWhipUrl: String?,
        publisherState: PublisherState,
        clientIp: String,
        desiredWhipUrl: String? = null,
    ): StreamingTargetDecision =
        when {
            previousClientIp == clientIp &&
                previousWhipUrl != null &&
                publisherState != PublisherState.NONE &&
                (desiredWhipUrl == null || desiredWhipUrl == previousWhipUrl) ->
                StreamingTargetDecision(false, clientIp)
            previousClientIp != null && previousClientIp != clientIp && publisherState == PublisherState.PUBLISHING ->
                StreamingTargetDecision(false, previousClientIp)
            else -> StreamingTargetDecision(true, clientIp)
        }

    /**
     * Where an operator-requested restart should publish, or null when there is nothing to
     * publish to.
     *
     * The publish URL is derived from the ground station that connected; a configured MediaMTX
     * server overrides its host, so that case can publish without a client at all. With neither,
     * the restart used to fall back to the device's own address - a phone publishing a stream to
     * itself, which cannot succeed and hides the real state behind a connecting-but-empty
     * publisher. [deviceIp] is therefore only used as the client argument when a configured
     * server makes it irrelevant.
     */
    fun restartTargetOrNull(
        previousClientIp: String?,
        previousWhipHost: String?,
        configuredServer: String,
        deviceIp: String,
    ): String? =
        previousClientIp
            ?: previousWhipHost
            ?: deviceIp.takeIf { configuredServer.isNotBlank() }
}
