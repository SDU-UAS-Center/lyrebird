package com.lyrebird.rc.edge

import com.lyrebird.rc.settings.DetectionSource
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The decision the process-wide detection registry acts on, without any of the machinery that
 * carries it out.
 */
class DetectionSelectionTest {
    @Test
    fun `detections switched off run nothing whatever the stored source says`() {
        for (source in DetectionSource.entries) {
            assertEquals(
                "stored source $source must not run while detections are off",
                DetectionSelection.Provider.NONE,
                DetectionSelection.providerFor(detectionsEnabled = false, source = source),
            )
        }
    }

    @Test
    fun `an enabled selection runs exactly the provider it names`() {
        assertEquals(
            DetectionSelection.Provider.NONE,
            DetectionSelection.providerFor(detectionsEnabled = true, source = DetectionSource.NONE),
        )
        assertEquals(
            DetectionSelection.Provider.DJI_ONBOARD,
            DetectionSelection.providerFor(detectionsEnabled = true, source = DetectionSource.DJI_ONBOARD),
        )
        assertEquals(
            DetectionSelection.Provider.YOLO_ON_PHONE,
            DetectionSelection.providerFor(detectionsEnabled = true, source = DetectionSource.YOLO_ON_PHONE),
        )
    }
}
