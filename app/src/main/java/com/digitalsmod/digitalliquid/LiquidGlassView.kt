package com.digitalsmod.digitalliquid

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import kotlin.math.min

/**
 * Hardware-friendly black pill surface. Idle state is genuinely opaque black: no rim,
 * no moving shine, and no transparent halo. Only the short tap reaction enables a smear.
 */
class LiquidGlassView(context: Context) : View(context) {
    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private var motionBlurFactor = 0f

    fun configure(glassStrength: Int, tint: String, expandedNow: Boolean) {
        // Parameters are retained for source compatibility with the existing preview and settings.
        // The always-present pill deliberately ignores tint/highlight options to stay solid black.
        invalidate()
    }

    fun setPhase(value: Float) {
        // Kept for source compatibility; the idle pill has no continuous shimmer loop.
    }

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
        // Always draw the solid-black pill first. There is no rim or permanent highlight.
        bodyPaint.color = Color.BLACK
        bodyPaint.alpha = 255
        canvas.drawRoundRect(RectF(0f, 0f, w, h), radius, radius, bodyPaint)

        if (motionBlurFactor > 0.015f) {
            val travel = dp(9f) * motionBlurFactor
            val blurPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                alpha = (motionBlurFactor * 28f).toInt().coerceIn(0, 28)
                try { maskFilter = BlurMaskFilter(dp(5.5f), BlurMaskFilter.Blur.NORMAL) } catch (_: Throwable) { }
            }
            canvas.drawRoundRect(RectF(-travel, dp(2f), w - travel * 0.38f, h - dp(2f)), radius, radius, blurPaint)
            blurPaint.alpha = (motionBlurFactor * 16f).toInt().coerceIn(0, 16)
            canvas.drawRoundRect(RectF(travel * 0.35f, dp(1f), w + travel, h - dp(1f)), radius, radius, blurPaint)
            blurPaint.maskFilter = null
        }
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
