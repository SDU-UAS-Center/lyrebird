package com.lyrebird.rc.edge

import com.lyrebird.rc.LyrebirdDetectionPort
import com.lyrebird.rc.mavlink.DetectedTargetSnapshot

internal class V5DetectionPort(
    private val activeProvider: () -> Boolean,
    private val targetsProvider: () -> List<DetectedTargetSnapshot>,
) : LyrebirdDetectionPort {
    override val isAutoSensingActive: Boolean
        get() = activeProvider()

    override fun currentTargets(): List<DetectedTargetSnapshot> = targetsProvider()
}
