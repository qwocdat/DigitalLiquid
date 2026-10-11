package com.digitalsmod.digitalliquid

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import kotlin.math.min

/** Opaque black pill. The only visible smear is a brief response while the pill moves. */
class LiquidGlassView(context: Context) : View(context) {
    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val blurPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        maskFilter = try {
            BlurMaskFilter(dp(5.5f), BlurMaskFilter.Blur.NORMAL)
        } catch (_: Throwable) {
            null
        }
    }
    private val bodyRect = RectF()
    private val leftSmearRect = RectF()
    private val rightSmearRect = RectF()
    private var motionBlurFactor = 0f

    fun configure(glassStrength: Int, tint: String, expandedNow: Boolean) {
        // Intentionally stay solid black when idle: no permanent outline or glass halo.
        invalidate()
    }

    fun setPhase(value: Float) = Unit

    fun setMotionBlur(value: Float) {
        motionBlurFactor = value.coerceIn(0f, 1f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val radius = min(h * 0.5f, w * 0.5f)

        if (motionBlurFactor > 0.015f) {
            val travel = dp(12f) * motionBlurFactor
            blurPaint.alpha = (motionBlurFactor * 34f).toInt().coerceIn(0, 34)
            leftSmearRect.set(-travel, dp(2f), w - travel * 0.32f, h - dp(2f))
            canvas.drawRoundRect(leftSmearRect, radius, radius, blurPaint)
            blurPaint.alpha = (motionBlurFactor * 21f).toInt().coerceIn(0, 21)
            rightSmearRect.set(travel * 0.32f, dp(1f), w + travel, h - dp(1f))
            canvas.drawRoundRect(rightSmearRect, radius, radius, blurPaint)
        }

        bodyPaint.color = Color.BLACK
        bodyPaint.alpha = 255
        bodyRect.set(0f, 0f, w, h)
        canvas.drawRoundRect(bodyRect, radius, radius, bodyPaint)
        blurPaint.alpha = 0
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
