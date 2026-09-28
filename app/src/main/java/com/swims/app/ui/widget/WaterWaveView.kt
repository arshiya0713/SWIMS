package com.swims.app.ui.widget

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.sin

/**
 * A playful teardrop that fills bottom-up with animated water as [progress] rises.
 *
 * Two offset sine waves give a lively surface; a few bubbles drift upward.
 * Colours are driven from attrs so it adapts to light/dark theme.
 *
 * Exposes `var progress: Int` (0..100) so existing view-binding code that did
 * `binding.progressRing.progress = x` keeps working unchanged.
 */
class WaterWaveView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    private val dropPath = Path()
    private val wavePath = Path()

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val waterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
        alpha = 90
    }

    private var trackColor = Color.parseColor("#D2F1F8")
    private var waterTopColor = Color.parseColor("#40C4E8")
    private var waterBottomColor = Color.parseColor("#0091C7")
    private var outlineColor = Color.parseColor("#00B8D4")

    /** Displayed fill fraction, eased toward [progress]. */
    private var animatedProgress = 0f
    private var wavePhase = 0f

    var progress: Int = 0
        set(value) {
            val clamped = value.coerceIn(0, 100)
            field = clamped
            animateTo(clamped / 100f)
        }

    private var progressAnimator: ValueAnimator? = null

    private val wavePhaseAnimator = ValueAnimator.ofFloat(0f, (2 * PI).toFloat()).apply {
        duration = 1600
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            wavePhase = it.animatedValue as Float
            if (animatedProgress > 0.001f) invalidate()
        }
    }

    fun setColors(track: Int, waterTop: Int, waterBottom: Int, outline: Int) {
        trackColor = track
        waterTopColor = waterTop
        waterBottomColor = waterBottom
        outlineColor = outline
        trackPaint.color = track
        outlinePaint.color = outline
        invalidate()
    }

    private fun animateTo(target: Float) {
        progressAnimator?.cancel()
        progressAnimator = ValueAnimator.ofFloat(animatedProgress, target).apply {
            duration = 900
            addUpdateListener {
                animatedProgress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        buildDrop(w, h)
        outlinePaint.strokeWidth = w * 0.02f
        waterPaint.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            waterTopColor, waterBottomColor, Shader.TileMode.CLAMP
        )
    }

    /** Builds a teardrop = round bulb ∪ pointed top. */
    private fun buildDrop(w: Int, h: Int) {
        dropPath.reset()
        val cx = w / 2f
        val r = w * 0.34f
        val cy = h * 0.60f
        val topY = h * 0.08f

        val bulb = Path().apply { addCircle(cx, cy, r, Path.Direction.CW) }
        val tip = Path().apply {
            moveTo(cx, topY)
            lineTo(cx - r * 0.80f, cy - r * 0.60f)
            lineTo(cx + r * 0.80f, cy - r * 0.60f)
            close()
        }
        dropPath.op(bulb, tip, Path.Op.UNION)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return

        val save = canvas.save()
        canvas.clipPath(dropPath)

        // Track (empty drop)
        canvas.drawColor(trackColor)

        // Water
        if (animatedProgress > 0.001f) {
            val top = height * 0.06f
            val bottom = height * 0.96f
            val span = bottom - top
            val level = bottom - animatedProgress * span
            val amplitude = width * 0.035f * (if (animatedProgress > 0.98f) 0.4f else 1f)
            val len = width.toFloat()

            wavePath.reset()
            wavePath.moveTo(0f, level)
            var x = 0f
            while (x <= len) {
                val y = level + amplitude * sin(2 * PI * (x / len) + wavePhase).toFloat()
                wavePath.lineTo(x, y)
                x += 6f
            }
            wavePath.lineTo(len, height.toFloat())
            wavePath.lineTo(0f, height.toFloat())
            wavePath.close()
            canvas.drawPath(wavePath, waterPaint)

            // second, offset wave for depth
            wavePath.reset()
            wavePath.moveTo(0f, level)
            x = 0f
            while (x <= len) {
                val y = level + amplitude * 0.7f *
                    sin(2 * PI * (x / len) + wavePhase + PI).toFloat()
                wavePath.lineTo(x, y)
                x += 6f
            }
            wavePath.lineTo(len, height.toFloat())
            wavePath.lineTo(0f, height.toFloat())
            wavePath.close()
            val a = waterPaint.alpha
            waterPaint.alpha = 120
            canvas.drawPath(wavePath, waterPaint)
            waterPaint.alpha = a

            // drifting bubbles
            val bubbleBase = level + span * 0.18f
            drawBubble(canvas, width * 0.40f, bubbleBase, width * 0.018f, 0f)
            drawBubble(canvas, width * 0.58f, bubbleBase, width * 0.012f, 0.5f)
            drawBubble(canvas, width * 0.50f, bubbleBase, width * 0.010f, 0.8f)
        }

        canvas.restoreToCount(save)

        // Outline
        canvas.drawPath(dropPath, outlinePaint)
    }

    private fun drawBubble(canvas: Canvas, x: Float, base: Float, radius: Float, offset: Float) {
        val t = ((wavePhase / (2 * PI).toFloat()) + offset) % 1f
        val y = base - t * height * 0.16f
        bubblePaint.alpha = (90 * (1f - t)).toInt().coerceIn(0, 255)
        canvas.drawCircle(x, y, radius, bubblePaint)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!wavePhaseAnimator.isStarted) wavePhaseAnimator.start()
    }

    override fun onDetachedFromWindow() {
        wavePhaseAnimator.cancel()
        progressAnimator?.cancel()
        super.onDetachedFromWindow()
    }
}
