package com.lyrebird.rc.webrtc

class AdaptiveFrameRatePolicy(initialDesiredFps: Int) {
    companion object {
        private const val SATURATION_PROCESSING_RATIO = 0.75
        private const val SATURATION_WINDOWS_TO_THROTTLE = 2
        private const val STABLE_WINDOWS_TO_RELAX = 8
        private val ADAPTIVE_FPS_STEPS = intArrayOf(30, 25, 20, 15, 12, 10, 8, 6, 5)
    }

    var desiredFps: Int = initialDesiredFps.coerceIn(1, 60)
        private set
    var effectiveFps: Int = desiredFps
        private set
    var saturationState: String = "ok"
        private set

    private var saturationWindows = 0
    private var stableWindows = 0

    data class Decision(
        val frameRateToApply: Int? = null,
        val reason: String? = null,
        val lifecycleEvent: String? = null,
        val lifecycleDetail: String? = null
    )

    fun reset(newDesiredFps: Int = desiredFps) {
        desiredFps = newDesiredFps.coerceIn(1, 60)
        effectiveFps = desiredFps
        saturationWindows = 0
        stableWindows = 0
        saturationState = "ok"
    }

    fun evaluate(metrics: WebRTCStreamMetrics): Decision {
        if (metrics.observerCount == 0 || metrics.targetFps <= 0) {
            saturationWindows = 0
            stableWindows = 0
            saturationState = "ok"
            return Decision()
        }

        val frameBudgetMs = 1000.0 / metrics.targetFps.toDouble()
        val processingSaturated = metrics.averageFrameProcessingMs >= frameBudgetMs * SATURATION_PROCESSING_RATIO
        val sourceFlowing = metrics.inputFps >= maxOf(2.0, metrics.targetFps * 0.6)
        val outputLagging = sourceFlowing && metrics.outputFps < metrics.targetFps * 0.5
        val saturated = processingSaturated || (outputLagging && metrics.processingErrors > 0)

        return when {
            saturated -> handleSaturatedWindow(metrics, frameBudgetMs, processingSaturated)
            outputLagging && effectiveFps == desiredFps -> handleSourceLimitedWindow()
            effectiveFps < desiredFps -> handleRecoveryWindow(metrics)
            else -> handleStableWindow()
        }
    }

    private fun handleSaturatedWindow(
        metrics: WebRTCStreamMetrics,
        frameBudgetMs: Double,
        processingSaturated: Boolean
    ): Decision {
        saturationWindows += 1
        stableWindows = 0
        saturationState = if (processingSaturated) "hot" else "error"
        var decision = Decision()

        if (saturationWindows >= SATURATION_WINDOWS_TO_THROTTLE) {
            val nextFps = nextLowerAdaptiveFps(effectiveFps)
            if (nextFps < effectiveFps) {
                effectiveFps = nextFps
                saturationWindows = 0
                decision = Decision(
                    frameRateToApply = effectiveFps,
                    reason = "processing saturation",
                    lifecycleEvent = "adaptive_fps_lowered",
                    lifecycleDetail = buildString {
                        append("fps ${metrics.targetFps} -> $effectiveFps")
                        append(" proc=${metrics.averageFrameProcessingMs.format1()}ms")
                        append(" budget=${frameBudgetMs.format1()}ms")
                        append(" out=${metrics.outputFps.format1()}")
                        append(" in=${metrics.inputFps.format1()}")
                        append(" err=${metrics.processingErrors}")
                    }
                )
            }
        }

        return decision
    }

    private fun handleSourceLimitedWindow(): Decision {
        saturationWindows = 0
        stableWindows = 0
        saturationState = "source-limited"
        return Decision()
    }

    private fun handleRecoveryWindow(metrics: WebRTCStreamMetrics): Decision {
        stableWindows += 1
        saturationState = "recovering"
        var decision = Decision()

        if (stableWindows >= STABLE_WINDOWS_TO_RELAX) {
            val nextFps = nextHigherAdaptiveFps(effectiveFps, desiredFps)
            if (nextFps > effectiveFps) {
                effectiveFps = nextFps
                stableWindows = 0
                decision = Decision(
                    frameRateToApply = effectiveFps,
                    reason = "saturation recovered",
                    lifecycleEvent = "adaptive_fps_raised",
                    lifecycleDetail = buildString {
                        append("fps ${metrics.targetFps} -> $effectiveFps")
                        append(" proc=${metrics.averageFrameProcessingMs.format1()}ms")
                        append(" out=${metrics.outputFps.format1()}")
                        append(" in=${metrics.inputFps.format1()}")
                    }
                )
            }
        }

        return decision
    }

    private fun handleStableWindow(): Decision {
        stableWindows = 0
        saturationState = "ok"
        return Decision()
    }

    private fun nextLowerAdaptiveFps(current: Int): Int {
        val currentIndex = ADAPTIVE_FPS_STEPS.indexOfFirst { it == current }
        return if (currentIndex >= 0 && currentIndex < ADAPTIVE_FPS_STEPS.lastIndex) {
            ADAPTIVE_FPS_STEPS[currentIndex + 1]
        } else {
            ADAPTIVE_FPS_STEPS.firstOrNull { it < current } ?: current
        }
    }

    private fun nextHigherAdaptiveFps(current: Int, desired: Int): Int {
        val currentIndex = ADAPTIVE_FPS_STEPS.indexOfFirst { it == current }
        return if (currentIndex > 0) {
            ADAPTIVE_FPS_STEPS[currentIndex - 1].coerceAtMost(desired)
        } else {
            desired
        }
    }

    private fun Double.format1(): String = String.format(java.util.Locale.US, "%.1f", this)
}
