package com.lyrebird.rc.edge

import android.content.Context
import com.lyrebird.rc.StreamingMode
import com.lyrebird.rc.server.ProcessStreamingRuntimeRegistry
import com.lyrebird.rc.settings.LyrebirdSettings
import java.lang.ref.WeakReference

/**
 * What a visible screen wants to hear about the detection pipeline.
 *
 * Notifications only. The pipeline's own inputs — which source to run, whether custom YOLO is
 * supported in the current streaming mode, which model and labels it was given, how confident a
 * detection has to be — are read from settings by the runtime itself, so a selection change means
 * the same thing whether or not anybody is looking at it.
 */
internal interface DetectionRuntimeCallbacks {
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

    /**
     * Attach the process itself: settings are read from [context] and the pipeline runs without
     * any screen. Called once from the application runtime, so detections survive the screen that
     * happened to ask for them.
     */
    fun attachProcess(context: Context) = runtime.attach(context, null)

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
        callbacks: DetectionRuntimeCallbacks?,
    ) {
        this.context = context.applicationContext
        // A process attach (no screen) must not unplug a screen that is already listening.
        if (callbacks != null) callbacksRef = WeakReference(callbacks)
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
        val settings = settings() ?: return
        val provider = DetectionSelection.providerFor(settings.isDetectionsEnabled(), settings.getDetectionSource())
        when (provider) {
            DetectionSelection.Provider.NONE -> stopSelected()
            DetectionSelection.Provider.DJI_ONBOARD -> startAutoSensing()
            DetectionSelection.Provider.YOLO_ON_PHONE -> startLocal(settings)
        }
    }

    private fun settings(): LyrebirdSettings? {
        val attachedContext = context ?: return null
        return LyrebirdSettings(
            attachedContext.getSharedPreferences(LyrebirdSettings.PREFS_FILE, Context.MODE_PRIVATE),
        )
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

    private fun startAutoSensing() {
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

    private fun startLocal(settings: LyrebirdSettings) {
        if (settings.getStreamingMode() != StreamingMode.WEBRTC) {
            callbacksRef.get()?.runtimeDetectionUnsupported(settings.getStreamingMode())
            return
        }
        val modelUri = settings.getEdgeModelUri()
        if (modelUri == null) {
            callbacksRef.get()?.runtimeDetectionNeedsModel()
            return
        }
        val streamer = ProcessStreamingRuntimeRegistry.streamer()
        if (streamer == null) {
            callbacksRef.get()?.runtimeDetectionNeedsModel()
            return
        }
        val attachedContext = context ?: return
        localProvider?.start(
            modelUri = modelUri,
            labels = settings.getEdgeLabels(attachedContext.contentResolver),
            confidenceThreshold = settings.getEdgeConfidenceThreshold(),
            streamer = streamer,
        )
    }
}
