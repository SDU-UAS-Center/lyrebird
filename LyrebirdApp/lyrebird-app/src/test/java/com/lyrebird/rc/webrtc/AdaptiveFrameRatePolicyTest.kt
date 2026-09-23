package com.lyrebird.rc.webrtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AdaptiveFrameRatePolicyTest {
    @Test
    fun resetsWhenThereAreNoObservers() {
        val policy = AdaptiveFrameRatePolicy(initialDesiredFps = 30)

        policy.evaluate(hotMetrics())
        policy.evaluate(hotMetrics())
        val decision = policy.evaluate(hotMetrics(observerCount = 0))

        assertNull(decision.frameRateToApply)
        assertEquals(25, policy.effectiveFps)
        assertEquals("ok", policy.saturationState)
    }

    @Test
    fun lowersFrameRateAfterConsecutiveSaturatedWindows() {
        val policy = AdaptiveFrameRatePolicy(initialDesiredFps = 30)

        assertNull(policy.evaluate(hotMetrics()).frameRateToApply)
        val decision = policy.evaluate(hotMetrics())

        assertEquals(25, decision.frameRateToApply)
        assertEquals("processing saturation", decision.reason)
        assertEquals("adaptive_fps_lowered", decision.lifecycleEvent)
        assertEquals(25, policy.effectiveFps)
        assertEquals("hot", policy.saturationState)
    }

    @Test
    fun reportsSourceLimitedWithoutLoweringWhenProcessingIsHealthy() {
        val policy = AdaptiveFrameRatePolicy(initialDesiredFps = 30)
        val decision = policy.evaluate(
            WebRTCStreamMetrics(
                targetFps = 30,
                inputFps = 30.0,
                outputFps = 10.0,
                averageFrameProcessingMs = 5.0,
                processingErrors = 0,
                observerCount = 1
            )
        )

        assertNull(decision.frameRateToApply)
        assertEquals(30, policy.effectiveFps)
        assertEquals("source-limited", policy.saturationState)
    }

    @Test
    fun raisesFrameRateAfterStableRecoveryWindows() {
        val policy = AdaptiveFrameRatePolicy(initialDesiredFps = 30)

        policy.evaluate(hotMetrics())
        policy.evaluate(hotMetrics())
        repeat(7) {
            assertNull(policy.evaluate(healthyMetrics()).frameRateToApply)
        }
        val decision = policy.evaluate(healthyMetrics())

        assertEquals(30, policy.desiredFps)
        assertEquals(30, decision.frameRateToApply)
        assertEquals("saturation recovered", decision.reason)
        assertEquals("adaptive_fps_raised", decision.lifecycleEvent)
        assertEquals(30, policy.effectiveFps)
    }

    @Test
    fun resetAppliesNewDesiredFrameRate() {
        val policy = AdaptiveFrameRatePolicy(initialDesiredFps = 30)

        policy.evaluate(hotMetrics())
        policy.evaluate(hotMetrics())
        policy.reset(newDesiredFps = 12)

        assertEquals(12, policy.desiredFps)
        assertEquals(12, policy.effectiveFps)
        assertEquals("ok", policy.saturationState)
    }

    private fun hotMetrics(observerCount: Int = 1): WebRTCStreamMetrics =
        WebRTCStreamMetrics(
            targetFps = 30,
            inputFps = 30.0,
            outputFps = 28.0,
            averageFrameProcessingMs = 30.0,
            observerCount = observerCount
        )

    private fun healthyMetrics(): WebRTCStreamMetrics =
        WebRTCStreamMetrics(
            targetFps = 25,
            inputFps = 25.0,
            outputFps = 24.0,
            averageFrameProcessingMs = 8.0,
            observerCount = 1
        )
}
