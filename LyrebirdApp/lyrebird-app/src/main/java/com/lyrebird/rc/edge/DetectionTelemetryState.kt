package com.lyrebird.rc.edge

internal data class DetectionTelemetryState(
    val source: String,
    val selectedSource: String,
    val active: Boolean,
    val modelName: String?,
    val threshold: Float?,
)

internal object DetectionTelemetryProjection {
    fun project(
        selectedSource: String,
        enabled: Boolean,
        onboardActive: Boolean,
        localActive: Boolean,
        modelName: String?,
        threshold: Float?,
    ): DetectionTelemetryState {
        val source = if (enabled) selectedSource else "none"
        val active =
            when (source) {
                "dji_onboard" -> onboardActive
                "yolo_on_phone" -> localActive
                else -> false
            }
        return DetectionTelemetryState(
            source = source,
            selectedSource = selectedSource,
            active = active,
            modelName = modelName.takeIf { source == "yolo_on_phone" },
            threshold = threshold.takeIf { source == "yolo_on_phone" },
        )
    }
}
