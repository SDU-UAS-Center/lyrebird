package com.lyrebird.rc.webrtc

internal data class StreamingTargetDecision(
    val shouldStart: Boolean,
    val targetIp: String,
)

internal object StreamingTargetPolicy {
    fun decide(
        previousClientIp: String?,
        previousWhipUrl: String?,
        publisherHealthy: Boolean,
        clientIp: String,
    ): StreamingTargetDecision =
        when {
            previousClientIp == clientIp && previousWhipUrl != null && publisherHealthy ->
                StreamingTargetDecision(false, clientIp)
            previousClientIp != null && previousClientIp != clientIp && publisherHealthy ->
                StreamingTargetDecision(false, previousClientIp)
            else -> StreamingTargetDecision(true, clientIp)
        }
}
