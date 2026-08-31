package com.lyrebird.rc.webrtc

data class WebRTCStreamMetrics(
    val sourceWidth: Int = 0,
    val sourceHeight: Int = 0,
    val outputWidth: Int = 0,
    val outputHeight: Int = 0,
    val requestedWidth: Int = 0,
    val requestedHeight: Int = 0,
    val targetFps: Int = 0,
    val inputFps: Double = 0.0,
    val outputFps: Double = 0.0,
    val droppedFps: Double = 0.0,
    val averageFrameProcessingMs: Double = 0.0,
    val totalFrames: Long = 0,
    val totalDroppedFrames: Long = 0,
    val processingErrors: Long = 0,
    val observerCount: Int = 0,
    val activeCamera: String = "unknown",
    val status: String = "idle",
    val configuredFps: Int = 0,
    val saturationState: String = "ok",
    val scaleMode: String = "fixed",
    val recoveryCount: Int = 0,
    val lastError: String? = null,
    // Send-side network stats from PeerConnection.getStats() (WhipPublisher), absent until the
    // first stats poll completes and always absent outside an active WHIP publish -- distinguishes
    // a network-congestion-driven drop from the on-device processing-time saturation
    // AdaptiveFrameRatePolicy already tracks above.
    val qualityLimitationReason: String? = null,
    val framesEncodedNotSent: Long? = null,
    val sendBitrateBps: Long? = null
) {
    val resolutionLabel: String
        get() = if (outputWidth > 0 && outputHeight > 0) {
            "${outputWidth}x${outputHeight}"
        } else {
            "waiting"
        }

    fun compactLabel(): String {
        val saturationLabel = if (saturationState != "ok") " sat $saturationState" else ""
        val errorLabel = if (processingErrors > 0) " err $processingErrors" else ""
        val recoveryLabel = if (recoveryCount > 0) " fix $recoveryCount" else ""
        val networkLabel = networkLabel()
            return buildString {
                append("WHIP $status$saturationLabel out $resolutionLabel")
                append(" req ${requestedLabel()} src ${sourceLabel()}")
                append(" fps ${fpsLabel()} drop ${droppedFps.format1()}")
                append(" resize ${averageFrameProcessingMs.format1()}ms")
                append(" scale $scaleMode clients $observerCount")
                append(errorLabel)
                append(recoveryLabel)
                append(networkLabel)
            }
    }

    /**
     * Absent by default (no active WHIP publish, or stats not polled yet), so this appends
     * nothing and existing callers/tests built before send-side stats existed see no change.
     */
    private fun networkLabel(): String {
        val parts = mutableListOf<String>()
        qualityLimitationReason?.let { if (it != "none") parts += "qlimit $it" }
        if ((framesEncodedNotSent ?: 0L) > 0L) parts += "unsent $framesEncodedNotSent"
        sendBitrateBps?.let { parts += "send ${it / 1000}kbps" }
        return if (parts.isEmpty()) "" else " " + parts.joinToString(" ")
    }

        private fun requestedLabel(): String = if (requestedWidth > 0 && requestedHeight > 0) {
            "${requestedWidth}x${requestedHeight}"
        } else {
            "native"
        }

        private fun sourceLabel(): String = if (sourceWidth > 0 && sourceHeight > 0) {
            "${sourceWidth}x${sourceHeight}"
        } else {
            "waiting"
        }

        private fun fpsLabel(): String = if (configuredFps > 0 && configuredFps != targetFps) {
            "${outputFps.format1()}/${targetFps} cfg $configuredFps"
        } else {
            "${outputFps.format1()}/${targetFps}"
        }

    private fun Double.format1(): String = String.format(java.util.Locale.US, "%.1f", this)
}
