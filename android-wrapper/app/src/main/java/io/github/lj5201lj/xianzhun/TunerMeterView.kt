package io.github.lj5201lj.xianzhun

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.cos
import kotlin.math.sin

class TunerMeterView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val density = resources.displayMetrics.density
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(70, 75, 66)
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val accuratePaint = Paint(trackPaint).apply {
        color = Color.rgb(190, 224, 112)
        strokeWidth = 6f * density
    }
    private val tickPaint = Paint(trackPaint).apply {
        strokeWidth = 1.5f * density
    }
    private val centerTickPaint = Paint(tickPaint).apply {
        color = Color.rgb(239, 255, 157)
        strokeWidth = 3f * density
    }
    private val needlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(212, 181, 111)
        style = Paint.Style.STROKE
        strokeWidth = 4f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(155, 160, 148)
        textSize = 12f * resources.displayMetrics.scaledDensity
        textAlign = Paint.Align.CENTER
    }
    private var displayedCents = 0f
    private var readingActive = false
    private var accurate = false
    private var animator: ValueAnimator? = null

    fun setReading(cents: Double, active: Boolean, isAccurate: Boolean) {
        readingActive = active
        accurate = isAccurate
        val target = cents.toFloat().coerceIn(-50f, 50f)
        animator?.cancel()
        animator = ValueAnimator.ofFloat(displayedCents, target).apply {
            duration = 150
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                displayedCents = it.animatedValue as Float
                invalidate()
            }
            start()
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val centerX = width / 2f
        val centerY = height * 0.83f
        val radius = minOf(width * 0.39f, height * 0.72f)
        val arc = RectF(centerX - radius, centerY - radius, centerX + radius, centerY + radius)
        canvas.drawArc(arc, 200f, 140f, false, trackPaint)
        canvas.drawArc(arc, 263f, 14f, false, accuratePaint)

        for (value in -50..50 step 5) {
            val angle = 200f + (value + 50) / 100f * 140f
            val radians = Math.toRadians(angle.toDouble())
            val outerX = centerX + radius * cos(radians).toFloat()
            val outerY = centerY + radius * sin(radians).toFloat()
            val length = if (value % 25 == 0) 14f * density else 8f * density
            val innerX = centerX + (radius - length) * cos(radians).toFloat()
            val innerY = centerY + (radius - length) * sin(radians).toFloat()
            canvas.drawLine(innerX, innerY, outerX, outerY, if (value == 0) centerTickPaint else tickPaint)
        }

        val angle = 270f + displayedCents / 50f * 70f
        val radians = Math.toRadians(angle.toDouble())
        val needleLength = radius * 0.77f
        val endX = centerX + needleLength * cos(radians).toFloat()
        val endY = centerY + needleLength * sin(radians).toFloat()
        needlePaint.color = when {
            !readingActive -> Color.rgb(93, 96, 89)
            accurate -> Color.rgb(239, 255, 157)
            else -> Color.rgb(212, 181, 111)
        }
        canvas.drawLine(centerX, centerY, endX, endY, needlePaint)
        canvas.drawCircle(centerX, centerY, 7f * density, needlePaint)

        canvas.drawText("−50", centerX - radius, centerY + 24f * density, labelPaint)
        canvas.drawText("0", centerX, centerY - radius - 10f * density, labelPaint)
        canvas.drawText("+50", centerX + radius, centerY + 24f * density, labelPaint)
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }
}
