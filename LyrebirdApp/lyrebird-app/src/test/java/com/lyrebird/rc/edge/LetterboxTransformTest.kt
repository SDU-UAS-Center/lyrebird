package com.lyrebird.rc.edge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LetterboxTransformTest {
    @Test
    fun mapsWideFrameIntoPaddedModelInput() {
        val transform = LetterboxTransform(
            inputWidth = 320,
            inputHeight = 320,
            frameWidth = 640,
            frameHeight = 360
        )

        assertEquals(0.5f, transform.scale, 0.0001f)
        assertEquals(320, transform.resizedWidth)
        assertEquals(180, transform.resizedHeight)
        assertEquals(0, transform.padX)
        assertEquals(70, transform.padY)
        assertFalse(transform.containsModelPixel(100, 69))
        assertTrue(transform.containsModelPixel(100, 70))
        assertEquals(200, transform.sourceX(100))
        assertEquals(0, transform.sourceY(70))
    }

    @Test
    fun mapsTallFrameIntoPaddedModelInput() {
        val transform = LetterboxTransform(
            inputWidth = 320,
            inputHeight = 320,
            frameWidth = 360,
            frameHeight = 640
        )

        assertEquals(0.5f, transform.scale, 0.0001f)
        assertEquals(180, transform.resizedWidth)
        assertEquals(320, transform.resizedHeight)
        assertEquals(70, transform.padX)
        assertEquals(0, transform.padY)
        assertFalse(transform.containsModelPixel(69, 100))
        assertTrue(transform.containsModelPixel(70, 100))
        assertEquals(0, transform.sourceX(70))
        assertEquals(200, transform.sourceY(100))
    }

    @Test
    fun convertsModelBoxToNormalizedFrameBox() {
        val transform = LetterboxTransform(
            inputWidth = 320,
            inputHeight = 320,
            frameWidth = 640,
            frameHeight = 360
        )

        val box = transform.toFrameBox(80f, 110f, 240f, 210f)
        assertNotNull(box)

        assertEquals(0.25f, box!!.left, 0.0001f)
        assertEquals(0.2222f, box.top, 0.0001f)
        assertEquals(0.75f, box.right, 0.0001f)
        assertEquals(0.7777f, box.bottom, 0.0001f)
    }

    @Test
    fun rejectsEmptyBoxesAfterClamping() {
        val transform = LetterboxTransform(
            inputWidth = 320,
            inputHeight = 320,
            frameWidth = 640,
            frameHeight = 360
        )

        assertNull(transform.toFrameBox(240f, 210f, 80f, 110f))
    }
}