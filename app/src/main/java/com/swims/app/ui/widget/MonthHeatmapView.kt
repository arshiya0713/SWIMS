package com.swims.app.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.swims.app.R
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.min

/**
 * GitHub-style month heatmap: one rounded cell per day, tinted by how close
 * that day's intake got to the goal. Today gets an outline ring.
 * Feed it data via [setMonth].
 */
class MonthHeatmapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var month: YearMonth = YearMonth.now()
    private var ratios: Map<Int, Float> = emptyMap() // dayOfMonth -> 0..1

    private val cellPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }

    private val trackColor = ContextCompat.getColor(context, R.color.water_track)
    private val fillColor = ContextCompat.getColor(context, R.color.blue_dark)
    private val accentColor = ContextCompat.getColor(context, R.color.blue_primary)
    private val labelColor = ContextCompat.getColor(context, R.color.text_secondary)

    private val headers = listOf("M", "T", "W", "T", "F", "S", "S")

    /** [dayRatios]: dayOfMonth → fraction of goal reached (0..1+, clamped). */
    fun setMonth(month: YearMonth, dayRatios: Map<Int, Float>) {
        this.month = month
        this.ratios = dayRatios
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val cell = w / 7f
        val firstDow = month.atDay(1).dayOfWeek.value // Mon=1..Sun=7
        val rows = ((firstDow - 1 + month.lengthOfMonth()) + 6) / 7
        val h = (cell * 0.62f) + rows * cell // header row + day rows
        setMeasuredDimension(w, h.toInt())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cell = width / 7f
        val pad = cell * 0.09f
        val corner = cell * 0.24f

        headerPaint.color = labelColor
        headerPaint.textSize = cell * 0.34f
        textPaint.textSize = cell * 0.34f

        // Weekday header
        for (i in 0 until 7) {
            canvas.drawText(headers[i], (i + 0.5f) * cell, cell * 0.42f, headerPaint)
        }

        val today = LocalDate.now()
        val firstDow = month.atDay(1).dayOfWeek.value
        val yOffset = cell * 0.62f

        for (day in 1..month.lengthOfMonth()) {
            val index = firstDow - 1 + (day - 1)
            val col = index % 7
            val row = index / 7
            val left = col * cell + pad
            val top = yOffset + row * cell + pad
            val rect = RectF(left, top, left + cell - 2 * pad, top + cell - 2 * pad)

            val ratio = ratios[day]?.coerceIn(0f, 1f)
            val isFuture = month.atDay(day).isAfter(today)

            cellPaint.color = when {
                isFuture -> withAlpha(trackColor, 70)
                ratio == null || ratio <= 0f -> trackColor
                else -> blend(trackColor, fillColor, 0.25f + 0.75f * ratio)
            }
            canvas.drawRoundRect(rect, corner, corner, cellPaint)

            // Day number — readable on both light and dark cells
            textPaint.color = when {
                isFuture -> withAlpha(labelColor, 120)
                (ratio ?: 0f) > 0.45f -> Color.WHITE
                else -> labelColor
            }
            val ty = rect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2
            canvas.drawText(day.toString(), rect.centerX(), ty, textPaint)

            // Today ring
            if (month.atDay(day) == today) {
                ringPaint.color = accentColor
                ringPaint.strokeWidth = min(4f, cell * 0.06f)
                canvas.drawRoundRect(rect, corner, corner, ringPaint)
            }
        }
    }

    private fun blend(from: Int, to: Int, t: Float): Int {
        val r = (Color.red(from) + (Color.red(to) - Color.red(from)) * t).toInt()
        val g = (Color.green(from) + (Color.green(to) - Color.green(from)) * t).toInt()
        val b = (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * t).toInt()
        return Color.rgb(r, g, b)
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
}
