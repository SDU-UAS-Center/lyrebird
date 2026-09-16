package com.lyrebird.rc.edge

import android.content.Context
import android.net.Uri
import com.lyrebird.rc.webrtc.WebRTCStreamer
import dji.v5.ux.detection.DetectedTarget

internal class LocalDetectionProvider(
    context: Context,
    private val onTargets: (List<DetectedTarget>) -> Unit,
    private val onMetrics: (EdgeDetectionController.EdgeDetectionMetrics) -> Unit,
) {
    private val appContext = context.applicationContext
    private var controller: EdgeDetectionController? = null

    val isActive: Boolean
        get() = controller != null

    fun start(
        modelUri: Uri,
        labels: List<String>,
        confidenceThreshold: Float,
        streamer: WebRTCStreamer,
    ) {
        if (controller != null) return
        val created =
            EdgeDetectionController(
                context = appContext,
                config =
                    EdgeDetectionConfig(
                        modelUri = modelUri,
                        labels = labels,
                        sourceLabel = "drone",
                        confidenceThreshold = confidenceThreshold,
                    ),
                onTargets = onTargets,
                onMetrics = onMetrics,
            )
        controller = created
        created.start()
        streamer.setEdgeDetectionFrameListener(created)
    }

    fun stop(streamer: WebRTCStreamer?) {
        val current = controller ?: return
        streamer?.setEdgeDetectionFrameListener(null)
        current.dispose()
        controller = null
    }
}
