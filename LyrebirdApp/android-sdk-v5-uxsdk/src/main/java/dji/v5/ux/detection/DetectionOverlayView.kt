package dji.v5.ux.detection

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * Transparent overlay drawn on top of the FPV widget to render
 * AutoSensing detection bounding boxes in real-time.
 */
class DetectionOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class VideoScaleMode {
        CENTER_INSIDE,
        CENTER_CROP
    }

    @Volatile
    private var targets: List<DetectedTarget> = emptyList()

    @Volatile
    private var sourceFrameWidth: Int = 0

    @Volatile
    private var sourceFrameHeight: Int = 0

    @Volatile
    private var videoScaleMode: VideoScaleMode = VideoScaleMode.CENTER_INSIDE

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.parseColor("#00E676") // bright green
    }

    private val labelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#AA000000") // semi-transparent black
    }

    private val labelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 28f
        isFakeBoldText = true
    }

    /**
     * Update the list of detected targets (called from any thread).
     */
    fun setTargets(newTargets: List<DetectedTarget>) {
        targets = newTargets
        postInvalidate()
    }

    fun setSourceFrameSize(frameWidth: Int, frameHeight: Int) {
        if (frameWidth <= 0 || frameHeight <= 0) return
        if (sourceFrameWidth == frameWidth && sourceFrameHeight == frameHeight) return
        sourceFrameWidth = frameWidth
        sourceFrameHeight = frameHeight
        postInvalidate()
    }

    fun setVideoScaleMode(scaleMode: VideoScaleMode) {
        if (videoScaleMode == scaleMode) return
        videoScaleMode = scaleMode
        postInvalidate()
    }

    fun clearTargets() {
        targets = emptyList()
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w == 0f || h == 0f) return

        val snapshot = targets
        for (target in snapshot) {
            val rect = target.toViewRect(w, h)
            canvas.drawRect(rect, boxPaint)

            val confidence = target.confidence?.let { " ${(it * 100.0).coerceIn(0.0, 100.0).toInt()}%" } ?: ""
            val label = "#${target.index} ${target.type}$confidence"
            val textWidth = labelTextPaint.measureText(label)
            val textHeight = labelTextPaint.textSize
            val labelLeft = rect.left
            val labelTop = (rect.top - textHeight - 6f).coerceAtLeast(0f)
            canvas.drawRect(labelLeft, labelTop, labelLeft + textWidth + 12f, labelTop + textHeight + 6f, labelBgPaint)
            canvas.drawText(label, labelLeft + 6f, labelTop + textHeight, labelTextPaint)
        }
    }

    private fun DetectedTarget.toViewRect(viewWidth: Float, viewHeight: Float): RectF {
        val sourceWidth = sourceFrameWidth.takeIf { it > 0 }?.toFloat() ?: return RectF(
            (left * viewWidth).toFloat(),
            (top * viewHeight).toFloat(),
            (right * viewWidth).toFloat(),
            (bottom * viewHeight).toFloat()
        )
        val sourceHeight = sourceFrameHeight.takeIf { it > 0 }?.toFloat() ?: return RectF(
            (left * viewWidth).toFloat(),
            (top * viewHeight).toFloat(),
            (right * viewWidth).toFloat(),
            (bottom * viewHeight).toFloat()
        )
        val scale = when (videoScaleMode) {
            VideoScaleMode.CENTER_INSIDE -> minOf(viewWidth / sourceWidth, viewHeight / sourceHeight)
            VideoScaleMode.CENTER_CROP -> maxOf(viewWidth / sourceWidth, viewHeight / sourceHeight)
        }
        val dx = (viewWidth - sourceWidth * scale) / 2f
        val dy = (viewHeight - sourceHeight * scale) / 2f
        return RectF(
            (left * sourceWidth * scale + dx).toFloat(),
            (top * sourceHeight * scale + dy).toFloat(),
            (right * sourceWidth * scale + dx).toFloat(),
            (bottom * sourceHeight * scale + dy).toFloat()
        )
    }
}
