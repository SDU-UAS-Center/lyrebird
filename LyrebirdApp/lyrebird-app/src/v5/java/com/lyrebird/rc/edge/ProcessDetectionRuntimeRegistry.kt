package com.lyrebird.rc.edge

import android.content.Context
import android.net.Uri
import com.lyrebird.rc.StreamingMode
import com.lyrebird.rc.server.ProcessStreamingRuntimeRegistry
import java.lang.ref.WeakReference

internal interface DetectionRuntimeCallbacks {
    fun runtimeDetectionSource(): String

    fun runtimeDetectionStreamingMode(): StreamingMode

    fun runtimeDetectionsEnabled(): Boolean

    fun runtimeEdgeModelUri(): Uri?

    fun runtimeEdgeLabels(): List<String>

    fun runtimeEdgeConfidenceThreshold(): Float

    fun runtimeDetectionTargetsChanged(targets: List<com.lyrebird.rc.mavlink.DetectedTargetSnapshot>)

    fun runtimeDetectionMetricsChanged(metrics: EdgeDetectionController.EdgeDetectionMetrics)

    fun runtimeDetectionActiveChanged(active: Boolean)

    fun runtimeDetectionUnsupported(mode: StreamingMode)

    fun runtimeDetectionNeedsModel()
}

internal object ProcessDetectionRuntimeRegistry {
    private val runtime = ProcessDetectionRuntime()

    fun attach(
        context: Context,
        callbacks: DetectionRuntimeCallbacks,
    ) = runtime.attach(context, callbacks)

    fun detach(callbacks: DetectionRuntimeCallbacks) = runtime.detach(callbacks)

    fun startSelected() = runtime.startSelected()

    fun stopSelected() = runtime.stopSelected()

    fun isAutoSensingActive() = runtime.isAutoSensingActive()

    fun isLocalActive() = runtime.isLocalActive()

    fun currentTargets() = runtime.currentTargets()
}

private class ProcessDetectionRuntime {
    private var context: Context? = null
    private var callbacksRef: WeakReference<DetectionRuntimeCallbacks> = WeakReference(null)
    private var autoSensingActive = false
    private var autoSensingProvider: V5AutoSensingProvider? = null
    private var localProvider: LocalDetectionProvider? = null
    private var currentTargets = emptyList<com.lyrebird.rc.mavlink.DetectedTargetSnapshot>()

    fun attach(
        context: Context,
        callbacks: DetectionRuntimeCallbacks,
    ) {
        this.context = context.applicationContext
        callbacksRef = WeakReference(callbacks)
        if (localProvider == null) {
            localProvider =
                LocalDetectionProvider(
                    context = context,
                    onTargets = { targets ->
                        currentTargets = targets
                        callbacksRef.get()?.runtimeDetectionTargetsChanged(targets)
                    },
                    onMetrics = { metrics -> callbacksRef.get()?.runtimeDetectionMetricsChanged(metrics) },
                )
        }
    }

    fun detach(callbacks: DetectionRuntimeCallbacks) {
        if (callbacksRef.get() === callbacks) callbacksRef.clear()
    }

    fun startSelected() {
        val callbacks = callbacksRef.get() ?: return
        if (!callbacks.runtimeDetectionsEnabled()) {
            stopSelected()
            return
        }
        when (callbacks.runtimeDetectionSource()) {
            "none" -> stopSelected()
            "dji_onboard" -> startAutoSensing(callbacks)
            "yolo_on_phone" -> startLocal(callbacks)
            else -> stopSelected()
        }
    }

    fun stopSelected() {
        val callbacks = callbacksRef.get()
        if (autoSensingActive) {
            autoSensingProvider?.stop(
                object : V5AutoSensingProvider.Observer {
                    override fun onActiveChanged(active: Boolean) = Unit

                    override fun onTargets(targets: List<com.lyrebird.rc.mavlink.DetectedTargetSnapshot>) = Unit
                },
            )
            callbacks?.runtimeDetectionActiveChanged(false)
            autoSensingActive = false
        }
        localProvider?.stop(ProcessStreamingRuntimeRegistry.streamer())
        currentTargets = emptyList()
        callbacks?.runtimeDetectionTargetsChanged(emptyList())
    }

    fun isAutoSensingActive() = autoSensingActive

    fun isLocalActive() = localProvider?.isActive == true

    fun currentTargets() = currentTargets

    private fun startAutoSensing(callbacks: DetectionRuntimeCallbacks) {
        val provider = autoSensingProvider ?: V5AutoSensingProvider().also { autoSensingProvider = it }
        provider.start(
            object : V5AutoSensingProvider.Observer {
                override fun onActiveChanged(active: Boolean) {
                    autoSensingActive = active
                    callbacksRef.get()?.runtimeDetectionActiveChanged(active)
                }

                override fun onTargets(targets: List<com.lyrebird.rc.mavlink.DetectedTargetSnapshot>) {
                    currentTargets = targets
                    callbacksRef.get()?.runtimeDetectionTargetsChanged(targets)
                }
            },
        )
    }

    private fun startLocal(callbacks: DetectionRuntimeCallbacks) {
        if (callbacks.runtimeDetectionStreamingMode() != StreamingMode.WEBRTC) {
            callbacks.runtimeDetectionUnsupported(callbacks.runtimeDetectionStreamingMode())
            return
        }
        val modelUri = callbacks.runtimeEdgeModelUri()
        if (modelUri == null) {
            callbacks.runtimeDetectionNeedsModel()
            return
        }
        val streamer = ProcessStreamingRuntimeRegistry.streamer()
        if (streamer == null) {
            callbacks.runtimeDetectionNeedsModel()
            return
        }
        localProvider?.start(
            modelUri = modelUri,
            labels = callbacks.runtimeEdgeLabels(),
            confidenceThreshold = callbacks.runtimeEdgeConfidenceThreshold(),
            streamer = streamer,
        )
    }
}
