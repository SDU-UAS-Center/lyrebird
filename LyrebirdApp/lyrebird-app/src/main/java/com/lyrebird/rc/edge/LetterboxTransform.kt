package com.lyrebird.rc.edge

import kotlin.math.min

class LetterboxTransform(
    private val inputWidth: Int,
    private val inputHeight: Int,
    private val frameWidth: Int,
    private val frameHeight: Int
) {
    val scale: Float = min(inputWidth.toFloat() / frameWidth.toFloat(), inputHeight.toFloat() / frameHeight.toFloat())
    val resizedWidth: Int = (frameWidth * scale).toInt().coerceAtLeast(1)
    val resizedHeight: Int = (frameHeight * scale).toInt().coerceAtLeast(1)
    val padX: Int = (inputWidth - resizedWidth) / 2
    val padY: Int = (inputHeight - resizedHeight) / 2

    fun containsModelPixel(modelX: Int, modelY: Int): Boolean {
        return modelX in padX until padX + resizedWidth && modelY in padY until padY + resizedHeight
    }

    fun sourceX(modelX: Int): Int {
        return ((modelX - padX) / scale).toInt().coerceIn(0, frameWidth - 1)
    }

    fun sourceY(modelY: Int): Int {
        return ((modelY - padY) / scale).toInt().coerceIn(0, frameHeight - 1)
    }

    fun toFrameBox(x1: Float, y1: Float, x2: Float, y2: Float): NormalizedBox? {
        val left = toNormalizedFrameX(toInputCoordinate(x1))
        val top = toNormalizedFrameY(toInputCoordinate(y1))
        val right = toNormalizedFrameX(toInputCoordinate(x2))
        val bottom = toNormalizedFrameY(toInputCoordinate(y2))

        return if (right > left && bottom > top) {
            NormalizedBox(left, top, right, bottom)
        } else {
            null
        }
    }

    private fun toNormalizedFrameX(inputX: Float): Float {
        return ((inputX - padX) / scale).coerceIn(0f, frameWidth.toFloat()) / frameWidth.toFloat()
    }

    private fun toNormalizedFrameY(inputY: Float): Float {
        return ((inputY - padY) / scale).coerceIn(0f, frameHeight.toFloat()) / frameHeight.toFloat()
    }

    private fun toInputCoordinate(value: Float): Float {
        return if (value <= NORMALIZED_COORDINATE_MAX) value * inputWidth else value
    }

    data class NormalizedBox(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float
    )

    companion object {
        private const val NORMALIZED_COORDINATE_MAX = 1.5f
    }
}
