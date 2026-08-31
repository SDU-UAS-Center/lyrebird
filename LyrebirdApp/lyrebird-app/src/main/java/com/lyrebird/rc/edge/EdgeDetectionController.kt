package com.lyrebird.rc.edge

import android.content.Context
import android.media.Image
import android.net.Uri
import android.util.Log
import com.lyrebird.rc.webrtc.SharedDJIFrameSource
import dji.v5.ux.detection.DetectedTarget
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

data class EdgeDetectionConfig(
    val modelUri: Uri?,
    val labels: List<String>,
    val sourceLabel: String,
    val confidenceThreshold: Float = 0.25f
)

class EdgeDetectionController(
    context: Context,
    private val config: EdgeDetectionConfig,
    private val onTargets: (List<DetectedTarget>) -> Unit,
    private val onMetrics: (EdgeDetectionMetrics) -> Unit = {}
) : SharedDJIFrameSource.EdgeDetectionFrameListener {

    companion object {
        private const val TAG = "EdgeDetectionController"
        private const val TARGET_FPS = 5
        private const val FRAME_INTERVAL_NS = 1_000_000_000L / TARGET_FPS
    }

    private val appContext = context.applicationContext
    private val executor = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    @Volatile private var detector: YoloTfliteDetector? = null
    @Volatile private var running = false
    @Volatile private var lastInferenceNs = 0L
    @Volatile private var status = "loading"
    private var receivedInWindow = 0L
    private var inferredInWindow = 0L
    private var droppedInWindow = 0L
    private var inferenceTimeNsInWindow = 0L
    private var lastMetricsAtNs = System.nanoTime()

    data class EdgeDetectionMetrics(
        val status: String = "off",
        val source: String = "dji",
        val targetFps: Int = TARGET_FPS,
        val inputFps: Double = 0.0,
        val inferenceFps: Double = 0.0,
        val droppedFps: Double = 0.0,
        val averageInferenceMs: Double = 0.0,
        val targetCount: Int = 0,
        val confidenceThreshold: Float = 0.25f,
        val lastError: String? = null
    ) {
        fun compactLabel(): String {
            val errorLabel = lastError?.takeIf { it.isNotBlank() }?.let { " err" } ?: ""
            return buildString {
                append("EDGE $status src $source th ${(confidenceThreshold * 100).toInt()}")
                append(" fps ${inferenceFps.format1()}/$targetFps")
                append(" in ${inputFps.format1()} drop ${droppedFps.format1()}")
                append(" infer ${averageInferenceMs.format1()}ms")
                append(" targets $targetCount$errorLabel")
            }
        }

        private fun Double.format1(): String = String.format(java.util.Locale.US, "%.1f", this)
    }

    fun start() {
        if (running) return
        val selectedModelUri = config.modelUri
        if (selectedModelUri == null) {
            status = "no-model"
            emitImmediateMetrics(targetCount = 0, lastError = "No model selected")
            onTargets(emptyList())
            return
        }
        running = true
        status = "loading"
        emitImmediateMetrics(targetCount = 0)
        executor.execute {
            runCatching {
                detector = YoloTfliteDetector.fromUri(
                    context = appContext,
                    modelUri = selectedModelUri,
                    labels = config.labels,
                    confidenceThreshold = config.confidenceThreshold
                )
                status = "ready"
                emitImmediateMetrics(targetCount = 0)
                Log.i(TAG, "Edge detector loaded: $selectedModelUri")
            }.onFailure {
                running = false
                status = "error"
                Log.e(TAG, "Failed to load edge detector: ${it.message}", it)
                emitImmediateMetrics(targetCount = 0, lastError = it.message)
                onTargets(emptyList())
            }
        }
    }

    fun stop() {
        running = false
        busy.set(false)
        executor.execute {
            detector?.close()
            detector = null
            status = "off"
            emitImmediateMetrics(targetCount = 0)
            onTargets(emptyList())
        }
    }

    fun dispose() {
        stop()
        executor.shutdownNow()
    }

    override fun onNv21Frame(frame: SharedDJIFrameSource.Nv21Frame) {
        if (!startInferenceWindow(frame.timestampNs)) return

        val frameCopy = frame.data.copyOfRange(frame.offset, frame.offset + frame.length)
        executor.execute {
            runNv21Inference(frameCopy, frame.width, frame.height)
        }
    }

    private fun startInferenceWindow(timestampNs: Long): Boolean {
        var accepted = false
        if (running && detector != null) {
            accepted = acceptFrameForInference(timestampNs)
        }
        return accepted
    }

    private fun acceptFrameForInference(timestampNs: Long): Boolean {
        receivedInWindow++
        if (timestampNs - lastInferenceNs < FRAME_INTERVAL_NS) {
            droppedInWindow++
            maybeEmitWindowMetrics(timestampNs, targetCount = 0)
        } else if (!busy.compareAndSet(false, true)) {
            droppedInWindow++
            maybeEmitWindowMetrics(timestampNs, targetCount = 0)
        } else {
            lastInferenceNs = timestampNs
            return true
        }
        return false
    }

    private fun runNv21Inference(frameCopy: ByteArray, width: Int, height: Int) {
        val inferenceStartNs = System.nanoTime()
        var targetCount = 0
        try {
            runCatching { detector?.detectNv21(frameCopy, 0, frameCopy.size, width, height).orEmpty() }
                .onSuccess { targets ->
                    targetCount = targets.size
                    inferredInWindow++
                    inferenceTimeNsInWindow += System.nanoTime() - inferenceStartNs
                    status = "running"
                    if (running) onTargets(targets)
                }.onFailure { error ->
                    status = "error"
                    Log.e(TAG, "Edge inference failed: ${error.message}", error)
                    emitImmediateMetrics(targetCount = targetCount, lastError = error.message)
                }
        } finally {
            maybeEmitWindowMetrics(System.nanoTime(), targetCount)
            busy.set(false)
        }
    }

    fun onYuv420Image(image: Image, timestampNs: Long, onComplete: () -> Unit = {}) {
        if (!startInferenceWindow(timestampNs)) {
            image.close()
            onComplete()
            return
        }

        executor.execute {
            val inferenceStartNs = System.nanoTime()
            var targetCount = 0
            try {
                runCatching { detector?.detectYuv420(image).orEmpty() }
                    .onSuccess { targets ->
                        targetCount = targets.size
                        inferredInWindow++
                        inferenceTimeNsInWindow += System.nanoTime() - inferenceStartNs
                        status = "running"
                        if (running) onTargets(targets)
                    }.onFailure { error ->
                        status = "error"
                        Log.e(TAG, "Edge YUV inference failed: ${error.message}", error)
                        emitImmediateMetrics(targetCount = targetCount, lastError = error.message)
                    }
            } finally {
                image.close()
                maybeEmitWindowMetrics(System.nanoTime(), targetCount)
                busy.set(false)
                onComplete()
            }
        }
    }

    private fun maybeEmitWindowMetrics(nowNs: Long, targetCount: Int) {
        val elapsedNs = nowNs - lastMetricsAtNs
        if (elapsedNs < 1_000_000_000L) return
        val elapsedSeconds = elapsedNs / 1_000_000_000.0
        val inferred = inferredInWindow
        val averageInferenceMs = if (inferred > 0) inferenceTimeNsInWindow / inferred / 1_000_000.0 else 0.0
        onMetrics(
            EdgeDetectionMetrics(
                status = status,
                source = config.sourceLabel,
                inputFps = receivedInWindow / elapsedSeconds,
                inferenceFps = inferred / elapsedSeconds,
                droppedFps = droppedInWindow / elapsedSeconds,
                averageInferenceMs = averageInferenceMs,
                targetCount = targetCount,
                confidenceThreshold = config.confidenceThreshold,
            )
        )
        receivedInWindow = 0L
        inferredInWindow = 0L
        droppedInWindow = 0L
        inferenceTimeNsInWindow = 0L
        lastMetricsAtNs = nowNs
    }

    private fun emitImmediateMetrics(targetCount: Int, lastError: String? = null) {
        onMetrics(
            EdgeDetectionMetrics(
                status = status,
                source = config.sourceLabel,
                targetCount = targetCount,
                confidenceThreshold = config.confidenceThreshold,
                lastError = lastError
            )
        )
    }
}
