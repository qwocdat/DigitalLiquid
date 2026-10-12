package com.digitalsmod.digitalliquid

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.view.MotionEvent
import android.view.View
import android.view.animation.PathInterpolator
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

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
    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    // Easing mượt chuẩn iOS Apple Spring / Cubic-Bezier
    private val iosInterpolator = PathInterpolator(0.25f, 0.1f, 0.25f, 1f)

    private var touchStartX = 0f
    private var touchStartY = 0f
    private var isDismissing = false

    init {
        isClickable = true
        isFocusable = false
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startAnimationSequence()
    }

    private fun startAnimationSequence() {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 4500L // 0.35s mở rộng -> 0.85s xoay vòng kép 3D -> 0.35s tick xanh -> 3s giữ -> 0.35s thu nhỏ
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (!isDismissing) onDismissRequested()
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
                if (hypot(dx, dy) > dp(6f)) {
                    dismissWithMotion()
                }
            }
            MotionEvent.ACTION_UP -> {
                dismissWithMotion()
            }
        }
        return true
    }

    fun dismissWithMotion() {
        if (isDismissing) return
        isDismissing = true
        animator?.cancel()
        onDismissRequested()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        val t = progress.coerceIn(0f, 1f)

        // TIMELINE HOẠT ẢNH CHUẨN IOS:
        // 0.00 -> 0.08: Phóng to & Hiện khung mặt
        // 0.08 -> 0.28: Khung mặt cuộn thành 2 vòng tròn xoay 3D/4D đan chéo
        // 0.28 -> 0.36: Dung hợp thành vòng tròn xanh & Bật dấu tick
        // 0.36 -> 0.92: Giữ nguyên tick xanh trong 3s
        // 0.92 -> 1.00: Thu nhỏ kèm hiệu ứng Ghost Trail mờ đuôi

        val appearAlpha = when {
            t in 0.00f..0.08f -> t / 0.08f
            t in 0.08f..0.28f -> 1f
            else -> 0f
        }

        val dualRingSpinT = when {
            t in 0.08f..0.28f -> iosInterpolator.getInterpolation((t - 0.08f) / 0.20f)
            t > 0.28f -> 1f
            else -> 0f
        }

        val circleProgress = when {
            t in 0.28f..0.34f -> (t - 0.28f) / 0.06f
            t > 0.34f -> 1f
            else -> 0f
        }

        val checkmarkProgress = when {
            t in 0.34f..0.38f -> (t - 0.34f) / 0.04f
            t > 0.38f -> 1f
            else -> 0f
        }

        val successAlpha = when {
            t >= 0.28f && t <= 0.92f -> 1f
            t > 0.92f -> (1f - t) / 0.08f
            else -> 0f
        }

        val cx = width * 0.5f
        val cy = height * 0.5f

        // 1. VẼ HOẠT ẢNH XOAY VÒNG KÉP 3D/4D DẠNG IOS
        if (appearAlpha > 0f) {
            val baseAlpha = (255 * appearAlpha).toInt()

            // Nếu đang trong giai đoạn xoay 3D, tạo ra các bóng mờ bám đuổi (Ghost Trails)
            if (dualRingSpinT in 0.01f..0.99f) {
                val ghostCount = 4
                for (i in ghostCount downTo 1) {
                    val lagT = (dualRingSpinT - i * 0.04f).coerceIn(0f, 1f)
                    val gAlpha = (baseAlpha * (0.4f - i * 0.08f)).toInt().coerceAtLeast(0)
                    drawIOSDualRings(canvas, cx, cy, lagT, gAlpha)
                }
            }

            drawIOSDualRings(canvas, cx, cy, dualRingSpinT, baseAlpha)
        }

        // 2. VẼ VÒNG TRÒN & TICK XANH (SUCCESS STATE)
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

    // Thuật toán vẽ 2 vòng tròn đồng tâm xoay 3D/4D đan chéo chuẩn iOS Face ID
    private fun drawIOSDualRings(canvas: Canvas, cx: Float, cy: Float, spinProgress: Float, alpha: Int) {
        if (alpha <= 0) return
        canvas.save()

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(4.2f)
        paint.color = Color.argb(alpha, 210, 210, 215)

        val radiusOuter = dp(32f)
        val radiusInner = dp(22f)

        // Góc quay của 2 vòng đối ngược hướng nhau
        val angleOuter = spinProgress * 360f
        val angleInner = -spinProgress * 360f

        // Ma trận nghiêng tạo độ sâu 3D (Scale Y giả lập góc nghiêng không gian 4D)
        val tiltOuter = cos(spinProgress * Math.PI * 2.0).toFloat() * 0.35f + 0.65f
        val tiltInner = sin(spinProgress * Math.PI * 2.0).toFloat() * 0.35f + 0.65f

        // Vòng ngoài (Outer Orbit Ring)
        canvas.save()
        canvas.scale(1f, tiltOuter, cx, cy)
        val rectOuter = RectF(cx - radiusOuter, cy - radiusOuter, cx + radiusOuter, cy + radiusOuter)
        canvas.drawArc(rectOuter, angleOuter, 260f, false, paint)
        canvas.restore()

        // Vòng trong (Inner Orbit Ring)
        paint.strokeWidth = dp(3.5f)
        canvas.save()
        canvas.scale(tiltInner, 1f, cx, cy)
        val rectInner = RectF(cx - radiusInner, cy - radiusInner, cx + radiusInner, cy + radiusInner)
        canvas.drawArc(rectInner, angleInner, 220f, false, paint)
        canvas.restore()

        // Mắt & Miệng trung tâm sẽ mờ dần khi cuộn vào vòng tròn
        if (spinProgress < 0.3f) {
            val centerAlpha = (alpha * (1f - spinProgress / 0.3f)).toInt()
            paint.style = Paint.Style.FILL
            paint.color = Color.argb(centerAlpha, 180, 180, 185)
            canvas.drawCircle(cx - dp(12f), cy - dp(5f), dp(2.8f), paint)
            canvas.drawCircle(cx + dp(12f), cy - dp(5f), dp(2.8f), paint)

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(3f)
            canvas.drawPath(Path().apply {
                moveTo(cx - dp(10f), cy + dp(12f))
                quadTo(cx, cy + dp(18f), cx + dp(10f), cy + dp(12f))
            }, paint)
        }

        canvas.restore()
    }
}
