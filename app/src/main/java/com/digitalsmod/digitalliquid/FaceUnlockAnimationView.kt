package com.digitalsmod.digitalliquid

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.max
import kotlin.math.min

/**
 * Cosmetic wake/face-scan animation. Android's public APIs do not reveal whether the
 * eventual unlock was face, fingerprint or PIN; this view never reads biometric data.
 * It starts at the Dynamic Island and morphs down into a rounded-square scanner.
 */
class FaceUnlockAnimationView(context: android.content.Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val tile = RectF()
    private val face = Path()
    private val scanLine = Path()
    private var progress = 0f
    private var animator: ValueAnimator? = null
    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density
    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
    private fun ease(t0: Float): Float {
        val t = t0.coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    init {
        isClickable = false
        isFocusable = false
        setLayerType(View.LAYER_TYPE_HARDWARE, null)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1680L
            interpolator = LinearInterpolator()
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
        val t = progress.coerceIn(0f, 1f)
        val fade = when {
            t < 0.78f -> 1f
            else -> (1f - (t - 0.78f) / 0.22f).coerceIn(0f, 1f)
        }
        val morph = ease((t / 0.30f).coerceIn(0f, 1f))
        val cx = width * 0.5f
        // Starts directly below the camera cutout, then travels down only about 95dp.
        // It intentionally never jumps to the centre of the screen.
        val cy = dp(34f) + dp(96f) * morph
        val w = lerp(dp(84f), dp(90f), morph)
        val h = lerp(dp(23f), dp(90f), morph)
        val radius = lerp(h * 0.5f, dp(23f), morph)
        tile.set(cx - w * 0.5f, cy - h * 0.5f, cx + w * 0.5f, cy + h * 0.5f)

        // Solid black material, a subdued edge, no permanent outer glow.
        paint.style = Paint.Style.FILL
        paint.color = Color.argb((246 * fade).toInt(), 2, 3, 6)
        canvas.drawRoundRect(tile, radius, radius, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(0.8f)
        paint.color = Color.argb((30 * fade).toInt(), 220, 232, 246)
        canvas.drawRoundRect(tile, radius, radius, paint)

        val reveal = ease(((t - 0.22f) / 0.22f).coerceIn(0f, 1f))
        if (reveal <= 0f || morph < 0.4f) return
        val shapeAlpha = (255f * reveal * fade).toInt().coerceIn(0, 255)
        val green = Color.rgb(88, 255, 158)
        val fw = dp(38f)
        val fh = dp(50f)
        val top = cy - fh * 0.48f
        val bottom = cy + fh * 0.50f
        val left = cx - fw * 0.5f
        val right = cx + fw * 0.5f

        // Human face silhouette with cheek/jaw contours, ears, hairline and facial features.
        // This is a drawn scanning model, not a captured face or device biometric template.
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1.5f)
        paint.color = Color.argb(shapeAlpha, 204, 255, 224)
        face.reset()
        face.moveTo(cx - fw * 0.42f, cy - fh * 0.18f)
        face.cubicTo(cx - fw * 0.47f, cy - fh * 0.55f, cx + fw * 0.42f, cy - fh * 0.58f, cx + fw * 0.42f, cy - fh * 0.16f)
        face.cubicTo(cx + fw * 0.42f, cy + fh * 0.18f, cx + fw * 0.25f, cy + fh * 0.40f, cx, bottom)
        face.cubicTo(cx - fw * 0.25f, cy + fh * 0.40f, cx - fw * 0.42f, cy + fh * 0.18f, cx - fw * 0.42f, cy - fh * 0.18f)
        canvas.drawPath(face, paint)
        // Ears
        canvas.drawArc(RectF(left - dp(3f), cy - dp(8f), left + dp(4f), cy + dp(7f)), 260f, 205f, false, paint)
        canvas.drawArc(RectF(right - dp(4f), cy - dp(8f), right + dp(3f), cy + dp(7f)), 75f, 205f, false, paint)
        // Hairline / brow arches
        face.reset()
        face.moveTo(cx - fw * 0.39f, cy - fh * 0.24f)
        face.cubicTo(cx - fw * 0.25f, cy - fh * 0.57f, cx + fw * 0.22f, cy - fh * 0.59f, cx + fw * 0.40f, cy - fh * 0.23f)
        canvas.drawPath(face, paint)
        paint.strokeWidth = dp(1.7f)
        face.reset()
        face.moveTo(cx - dp(13f), cy - dp(5f)); face.quadTo(cx - dp(8f), cy - dp(8f), cx - dp(4f), cy - dp(5f))
        face.moveTo(cx + dp(4f), cy - dp(5f)); face.quadTo(cx + dp(8f), cy - dp(8f), cx + dp(13f), cy - dp(5f))
        canvas.drawPath(face, paint)
        // Eye shapes and pupils
        face.reset()
        face.moveTo(cx - dp(13f), cy - dp(1f)); face.quadTo(cx - dp(8f), cy + dp(3f), cx - dp(4f), cy - dp(1f))
        face.moveTo(cx + dp(4f), cy - dp(1f)); face.quadTo(cx + dp(8f), cy + dp(3f), cx + dp(13f), cy - dp(1f))
        canvas.drawPath(face, paint)
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(shapeAlpha, 210, 255, 230)
        canvas.drawCircle(cx - dp(8.5f), cy - dp(0.4f), dp(1.2f), paint)
        canvas.drawCircle(cx + dp(8.5f), cy - dp(0.4f), dp(1.2f), paint)
        // Nose bridge and nostrils
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1.25f)
        face.reset()
        face.moveTo(cx, cy + dp(1f)); face.quadTo(cx - dp(1f), cy + dp(7f), cx - dp(2f), cy + dp(8f))
        face.quadTo(cx, cy + dp(10f), cx + dp(3f), cy + dp(8f))
        canvas.drawPath(face, paint)
        // Mouth and subtle jaw contour
        face.reset()
        face.moveTo(cx - dp(6f), cy + dp(15f)); face.quadTo(cx, cy + dp(12f), cx + dp(6f), cy + dp(15f))
        face.moveTo(cx - dp(5f), cy + dp(16f)); face.quadTo(cx, cy + dp(19f), cx + dp(5f), cy + dp(16f))
        canvas.drawPath(face, paint)

        // A restrained mesh runs over the face like a Face ID scan, not a floating icon.
        paint.color = Color.argb((84 * reveal * fade).toInt(), 88, 255, 158)
        paint.strokeWidth = dp(0.7f)
        canvas.drawLine(cx - dp(13f), cy - dp(9f), cx + dp(12f), cy + dp(13f), paint)
        canvas.drawLine(cx + dp(13f), cy - dp(9f), cx - dp(11f), cy + dp(13f), paint)
        canvas.drawLine(cx - dp(14f), cy + dp(6f), cx + dp(14f), cy + dp(6f), paint)
        canvas.drawLine(cx - dp(12f), cy - dp(12f), cx + dp(12f), cy - dp(12f), paint)
        for (yy in -12..14 step 6) {
            for (xx in -12..12 step 6) {
                paint.style = Paint.Style.FILL
                paint.color = Color.argb((70 * reveal * fade).toInt(), 88, 255, 158)
                canvas.drawCircle(cx + dp(xx.toFloat()), cy + dp(yy.toFloat()), dp(0.8f), paint)
            }
        }

        // Scan sweep begins only after the pill has finished most of its morph.
        if (t in 0.34f..0.76f) {
            val scan = ((t - 0.34f) / 0.42f).coerceIn(0f, 1f)
            val y = top + dp(4f) + (fh - dp(8f)) * scan
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(1.4f)
            paint.color = Color.argb((220 * fade).toInt(), Color.red(green), Color.green(green), Color.blue(green))
            scanLine.reset(); scanLine.moveTo(left - dp(2f), y); scanLine.lineTo(right + dp(2f), y)
            canvas.drawPath(scanLine, paint)
        }

        if (t >= 0.65f && t < 0.83f) {
            val ringProgress = ((t - 0.65f) / 0.18f).coerceIn(0f, 1f)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(1.7f)
            paint.color = Color.argb((220 * fade).toInt(), Color.red(green), Color.green(green), Color.blue(green))
            canvas.drawCircle(cx, cy, dp(25f + 8f * ringProgress), paint)
        }
        if (t >= 0.80f) {
            val checkProgress = ((t - 0.80f) / 0.12f).coerceIn(0f, 1f)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(2.4f)
            paint.color = Color.argb((225 * fade).toInt(), Color.red(green), Color.green(green), Color.blue(green))
            val check = Path().apply {
                moveTo(cx - dp(9f), cy + dp(1f))
                lineTo(cx - dp(2f), cy + dp(8f))
                lineTo(cx + dp(11f), cy - dp(8f))
            }
            canvas.save()
            canvas.clipRect(cx - dp(16f), cy - dp(13f), cx - dp(16f) + dp(35f) * checkProgress, cy + dp(13f))
            canvas.drawPath(check, paint)
            canvas.restore()
        }
    }
}
