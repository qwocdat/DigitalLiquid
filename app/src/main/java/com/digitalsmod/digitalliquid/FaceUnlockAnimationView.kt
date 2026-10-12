package com.digitalsmod.digitalliquid

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import kotlin.math.min

/** Cosmetic unlock animation. Android broadcasts USER_PRESENT, not which unlock method was used. */
class FaceUnlockAnimationView(context: android.content.Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val tile = RectF()
    private var progress = 0f
    private var animator: ValueAnimator? = null
    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    init {
        isClickable = false
        isFocusable = false
        setLayerType(View.LAYER_TYPE_HARDWARE, null)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1280L
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { progress = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        val size = dp(80f)
        val cx = width / 2f
        val cy = height / 2f
        val t = progress.coerceIn(0f, 1f)
        val fade = when { t < 0.76f -> 1f; else -> (1f - (t - 0.76f) / 0.24f).coerceIn(0f, 1f) }
        tile.set(cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2)
        paint.style = Paint.Style.FILL
        paint.color = Color.argb((236 * fade).toInt(), 7, 9, 13)
        canvas.drawRoundRect(tile, dp(22f), dp(22f), paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1f)
        paint.color = Color.argb((44 * fade).toInt(), 255, 255, 255)
        canvas.drawRoundRect(tile, dp(22f), dp(22f), paint)

        val green = Color.rgb(66, 255, 142)
        val centerX = cx
        val centerY = cy
        val faceSize = dp(27f)
        if (t < 0.52f) {
            val scanT = (t / 0.52f).coerceIn(0f, 1f)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(1.8f)
            paint.color = Color.argb((220 * fade).toInt(), 205, 255, 220)
            val face = RectF(centerX - faceSize * 0.43f, centerY - faceSize * 0.46f, centerX + faceSize * 0.43f, centerY + faceSize * 0.46f)
            canvas.drawRoundRect(face, dp(9f), dp(9f), paint)
            paint.style = Paint.Style.FILL
            paint.color = Color.argb((215 * fade).toInt(), 205, 255, 220)
            canvas.drawCircle(centerX - dp(5f), centerY - dp(3f), dp(1.5f), paint)
            canvas.drawCircle(centerX + dp(5f), centerY - dp(3f), dp(1.5f), paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(1.5f)
            canvas.drawArc(RectF(centerX - dp(6f), centerY + dp(1f), centerX + dp(6f), centerY + dp(9f)), 10f, 160f, false, paint)
            val y = face.top + dp(3f) + (face.height() - dp(6f)) * scanT
            paint.color = Color.argb((210 * fade).toInt(), 66, 255, 142)
            paint.strokeWidth = dp(1.4f)
            canvas.drawLine(face.left - dp(3f), y, face.right + dp(3f), y, paint)
        }

        val ringT = ((t - 0.27f) / 0.38f).coerceIn(0f, 1f)
        if (t >= 0.27f && t < 0.72f) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(2f)
            paint.color = Color.argb((235 * fade).toInt(), Color.red(green), Color.green(green), Color.blue(green))
            val radius = dp(11f + ringT * 20f)
            canvas.drawCircle(centerX, centerY, radius, paint)
            paint.color = Color.argb((55 * fade).toInt(), Color.red(green), Color.green(green), Color.blue(green))
            paint.strokeWidth = dp(3f)
            canvas.drawCircle(centerX, centerY, radius + dp(3f), paint)
        }
        if (t >= 0.62f) {
            val checkT = ((t - 0.62f) / 0.2f).coerceIn(0f, 1f)
            val radius = dp(27f)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(2f)
            paint.color = Color.argb((245 * fade).toInt(), Color.red(green), Color.green(green), Color.blue(green))
            canvas.drawCircle(centerX, centerY, radius, paint)
            val check = Path().apply {
                moveTo(centerX - dp(9f), centerY + dp(0f))
                lineTo(centerX - dp(2f), centerY + dp(7f))
                lineTo(centerX + dp(11f), centerY - dp(8f))
            }
            canvas.save()
            canvas.clipRect(centerX - dp(16f), centerY - dp(13f), centerX - dp(16f) + dp(35f) * checkT, centerY + dp(13f))
            paint.strokeWidth = dp(2.6f)
            canvas.drawPath(check, paint)
            canvas.restore()
        }
    }
}
