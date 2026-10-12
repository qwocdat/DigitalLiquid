package com.digitalsmod.digitalliquid

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import android.view.animation.LinearInterpolator

class FaceUnlockAnimationView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val tile = RectF()
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
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1800L // 1.8 giây chuẩn khớp timeline video
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

        // Tính toán các mốc thời gian (stages) chuẩn video
        val morph = when {
            t < 0.15f -> ease(t / 0.15f)
            t > 0.85f -> ease((1f - t) / 0.15f)
            else -> 1f
        }
        
        val faceIconAlpha = when {
            t in 0.1f..0.2f -> (t - 0.1f) / 0.1f
            t in 0.2f..0.45f -> 1f
            t in 0.45f..0.55f -> 1f - (t - 0.45f) / 0.1f
            else -> 0f
        }
        
        val circleProgress = when {
            t in 0.5f..0.65f -> (t - 0.5f) / 0.15f
            t > 0.65f -> 1f
            else -> 0f
        }
        
        val checkmarkProgress = when {
            t in 0.65f..0.75f -> (t - 0.65f) / 0.1f
            t > 0.75f -> 1f
            else -> 0f
        }
        
        val successAlpha = when {
            t in 0.5f..0.8f -> 1f
            t in 0.8f..0.85f -> 1f - (t - 0.8f) / 0.05f
            else -> 0f
        }

        val cx = width * 0.5f
        val startTop = dp(11f)
        val startW = dp(120f)
        val startH = dp(38f)
        val endW = dp(150f)
        val endH = dp(150f)
        val endTop = dp(11f)

        val currentW = lerp(startW, endW, morph)
        val currentH = lerp(startH, endH, morph)
        val currentTop = lerp(startTop, endTop, morph)
        val radius = lerp(startH * 0.5f, dp(40f), morph)

        tile.set(cx - currentW * 0.5f, currentTop, cx + currentW * 0.5f, currentTop + currentH)
        val cy = currentTop + currentH * 0.5f

        // 1. Vẽ nền đen bo góc từ Dynamic Island
        paint.style = Paint.Style.FILL
        paint.color = Color.BLACK
        canvas.drawRoundRect(tile, radius, radius, paint)

        // 2. Vẽ Face ID Icon (Khung 4 góc, mắt, mũi, miệng)
        val fAlpha = (255 * faceIconAlpha).toInt()
        if (fAlpha > 0) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(4.5f)
            paint.color = Color.argb(fAlpha, 150, 150, 150) // Xám chuẩn
            
            val iconSize = dp(60f)
            val half = iconSize / 2f
            val cr = dp(14f)
            val len = dp(20f)

            // Góc trên-trái
            canvas.drawPath(Path().apply {
                moveTo(cx - half + len, cy - half)
                lineTo(cx - half + cr, cy - half)
                quadTo(cx - half, cy - half, cx - half, cy - half + cr)
                lineTo(cx - half, cy - half + len)
            }, paint)
            // Góc trên-phải
            canvas.drawPath(Path().apply {
                moveTo(cx + half - len, cy - half)
                lineTo(cx + half - cr, cy - half)
                quadTo(cx + half, cy - half, cx + half, cy - half + cr)
                lineTo(cx + half, cy - half + len)
            }, paint)
            // Góc dưới-trái
            canvas.drawPath(Path().apply {
                moveTo(cx - half + len, cy + half)
                lineTo(cx - half + cr, cy + half)
                quadTo(cx - half, cy + half, cx - half, cy + half - cr)
                lineTo(cx - half, cy + half - len)
            }, paint)
            // Góc dưới-phải
            canvas.drawPath(Path().apply {
                moveTo(cx + half - len, cy + half)
                lineTo(cx + half - cr, cy + half)
                quadTo(cx + half, cy + half, cx + half, cy + half - cr)
                lineTo(cx + half, cy + half - len)
            }, paint)

            // 2 Mắt
            paint.style = Paint.Style.FILL
            canvas.drawCircle(cx - dp(14f), cy - dp(6f), dp(3f), paint)
            canvas.drawCircle(cx + dp(14f), cy - dp(6f), dp(3f), paint)

            // Mũi chữ J
            paint.style = Paint.Style.STROKE
            canvas.drawPath(Path().apply {
                moveTo(cx, cy - dp(2f))
                lineTo(cx, cy + dp(6f))
                quadTo(cx, cy + dp(10f), cx - dp(5f), cy + dp(10f))
            }, paint)

            // Miệng cười
            canvas.drawPath(Path().apply {
                moveTo(cx - dp(14f), cy + dp(15f))
                quadTo(cx, cy + dp(23f), cx + dp(14f), cy + dp(15f))
            }, paint)
        }

        // 3. Vẽ Vòng tròn xanh & Dấu Tick hoàn thành
        val sAlpha = (255 * successAlpha).toInt()
        if (sAlpha > 0) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(5.5f)
            paint.color = Color.argb(sAlpha, 52, 199, 89) // Xanh lá chuẩn iOS

            // Vòng tròn vẽ dần
            if (circleProgress > 0f) {
                val rect = RectF(cx - dp(32f), cy - dp(32f), cx + dp(32f), cy + dp(32f))
                canvas.drawArc(rect, -90f, 360f * circleProgress, false, paint)
            }

            // Dấu tick xanh vẽ dần
            if (checkmarkProgress > 0f) {
                val checkPath = Path()
                val p1x = cx - dp(14f); val p1y = cy + dp(2f)
                val p2x = cx - dp(4f);  val p2y = cy + dp(12f)
                val p3x = cx + dp(16f); val p3y = cy - dp(8f)

                checkPath.moveTo(p1x, p1y)
                
                if (checkmarkProgress < 0.5f) {
                    val p = checkmarkProgress * 2f
                    checkPath.lineTo(p1x + (p2x - p1x) * p, p1y + (p2y - p1y) * p)
                } else {
                    checkPath.lineTo(p2x, p2y)
                    val p = (checkmarkProgress - 0.5f) * 2f
                    checkPath.lineTo(p2x + (p3x - p2x) * p, p2y + (p3y - p2y) * p)
                }
                canvas.drawPath(checkPath, paint)
            }
        }
    }
}
