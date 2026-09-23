package com.lyrebird.rc.edge

import com.lyrebird.rc.settings.DetectionSource

/**
 * Which detection provider a settings state asks for.
 *
 * Pure on purpose. The registry that applies this runs process-wide — including with no screen
 * attached — so the decision it acts on has to be reproducible without an Activity, a preference
 * file or the SDK. That is what this expresses, rather than the registry asking whichever screen
 * happens to be open.
 *
 * The stored source is what the operator picked; the provider is what should actually run. They
 * differ when detections are switched off, and a source nobody selected runs nothing.
 */
internal object DetectionSelection {
    enum class Provider { NONE, DJI_ONBOARD, YOLO_ON_PHONE }

    fun providerFor(
        detectionsEnabled: Boolean,
        source: DetectionSource,
    ): Provider {
        if (!detectionsEnabled) return Provider.NONE
        return when (source) {
            DetectionSource.NONE -> Provider.NONE
            DetectionSource.DJI_ONBOARD -> Provider.DJI_ONBOARD
            DetectionSource.YOLO_ON_PHONE -> Provider.YOLO_ON_PHONE
        }
    }
}
