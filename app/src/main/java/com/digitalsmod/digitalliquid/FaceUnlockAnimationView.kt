package com.digitalsmod.digitalliquid

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.cos
import kotlin.math.hypot

class FaceUnlockAnimationView(
    context: Context,
    private val onDismissRequested: () -> Unit
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private var progress = 0f
    private var animator: ValueAnimator? = null
    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private var touchStartX = 0f
    private var touchStartY = 0f

    init {
        isClickable = true
        isFocusable = false
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 4400L // 0.3s nở/hiện + 0.8s quét xoay 3D + 0.3s tick + 3.0s giữ nguyên
            interpolator = LinearInterpolator()
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    onDismissRequested()
                }
            })
            start()
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchStartX = event.x
                touchStartY = event.y
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - touchStartX
                val dy = event.y - touchStartY
                if (hypot(dx, dy) > dp(8f)) {
                    animator?.cancel()
                    onDismissRequested()
                }
            }
            MotionEvent.ACTION_UP -> {
                animator?.cancel()
                onDismissRequested()
            }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        val t = progress.coerceIn(0f, 1f)

        val faceAppearAlpha = when {
            t in 0.00f..0.07f -> t / 0.07f
            t in 0.07f..0.25f -> 1f
            else -> 0f
        }

        val spinT = when {
            t in 0.07f..0.25f -> (t - 0.07f) / 0.18f
            t > 0.25f -> 1f
            else -> 0f
        }

        val circleProgress = when {
            t in 0.25f..0.32f -> (t - 0.25f) / 0.07f
            t > 0.32f -> 1f
            else -> 0f
        }

        val checkmarkProgress = when {
            t in 0.32f..0.38f -> (t - 0.32f) / 0.06f
            t > 0.38f -> 1f
            else -> 0f
        }

        val successAlpha = when {
            t >= 0.25f -> 1f
            else -> 0f
        }

        val cx = width * 0.5f
        val cy = height * 0.5f

        // 1. Vẽ Icon Face ID (Xoay 3D + Bóng mờ Motion Blur)
        if (faceAppearAlpha > 0f) {
            val baseAlpha = (255 * faceAppearAlpha).toInt()
            val ghostCount = 5
            val ghostDelay = 0.03f

            if (spinT > 0f && spinT < 1f) {
                for (i in ghostCount downTo 1) {
                    val gT = (spinT - (i * ghostDelay)).coerceIn(0f, 1f)
                    if (gT > 0f) {
                        val gAngle = gT * Math.PI * 2.0
                        val gScaleX = cos(gAngle).toFloat()
                        val gAlpha = (baseAlpha * (0.55f - (i * 0.1f))).toInt().coerceAtLeast(0)
                        drawFaceIcon(canvas, cx, cy, gScaleX, gAlpha)
                    }
                }
            }

            val currentAngle = spinT * Math.PI * 2.0
            val mainScaleX = cos(currentAngle).toFloat()
            val isBackFacing = cos(currentAngle) < 0
            val mainFaceAlpha = if (isBackFacing && spinT > 0) (baseAlpha * 0.35f).toInt() else baseAlpha

            drawFaceIcon(canvas, cx, cy, mainScaleX, mainFaceAlpha)
        }

        // 2. Vẽ Vòng tròn & Tick Xanh (Giữ nguyên 3s)
        val sAlpha = (255 * successAlpha).toInt()
        if (sAlpha > 0) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(5.5f)
            paint.color = Color.argb(sAlpha, 52, 199, 89)

            if (circleProgress > 0f) {
                val rect = RectF(cx - dp(32f), cy - dp(32f), cx + dp(32f), cy + dp(32f))
                canvas.drawArc(rect, -90f, 360f * circleProgress, false, paint)
            }

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

    private fun drawFaceIcon(canvas: Canvas, cx: Float, cy: Float, scaleX: Float, alpha: Int) {
        if (alpha <= 0) return
        canvas.save()
        canvas.scale(scaleX, 1f, cx, cy)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(4.5f)
        paint.color = Color.argb(alpha, 150, 150, 150)

        val half = dp(30f)
        val cr = dp(14f)
        val len = dp(20f)

        canvas.drawPath(Path().apply {
            moveTo(cx - half + len, cy - half)
            lineTo(cx - half + cr, cy - half)
            quadTo(cx - half, cy - half, cx - half, cy - half + cr)
            lineTo(cx - half, cy - half + len)
        }, paint)
        canvas.drawPath(Path().apply {
            moveTo(cx + half - len, cy - half)
            lineTo(cx + half - cr, cy - half)
            quadTo(cx + half, cy - half, cx + half, cy - half + cr)
            lineTo(cx + half, cy - half + len)
        }, paint)
        canvas.drawPath(Path().apply {
            moveTo(cx - half + len, cy + half)
            lineTo(cx - half + cr, cy + half)
            quadTo(cx - half, cy + half, cx - half, cy + half - cr)
            lineTo(cx - half, cy + half - len)
        }, paint)
        canvas.drawPath(Path().apply {
            moveTo(cx + half - len, cy + half)
            lineTo(cx + half - cr, cy + half)
            quadTo(cx + half, cy + half, cx + half, cy + half - cr)
            lineTo(cx + half, cy + half - len)
        }, paint)

        paint.style = Paint.Style.FILL
        canvas.drawCircle(cx - dp(14f), cy - dp(6f), dp(3f), paint)
        canvas.drawCircle(cx + dp(14f), cy - dp(6f), dp(3f), paint)

        paint.style = Paint.Style.STROKE
        canvas.drawPath(Path().apply {
            moveTo(cx, cy - dp(2f))
            lineTo(cx, cy + dp(6f))
            quadTo(cx, cy + dp(10f), cx - dp(5f), cy + dp(10f))
        }, paint)

        canvas.drawPath(Path().apply {
            moveTo(cx - dp(14f), cy + dp(15f))
            quadTo(cx, cy + dp(23f), cx + dp(14f), cy + dp(15f))
        }, paint)

        canvas.restore()
    }
}
