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
import android.view.animation.PathInterpolator
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

class FaceUnlockAnimationView(
    context: Context,
    private val onDismissRequested: () -> Unit
) : View(context) {

    enum class State {
        SCANNING_3D,
        SUCCESS,
        FAILED
    }

    private val greenColor = Color.parseColor("#30D158")
    private val dimGreenColor = Color.parseColor("#1C8237")
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private var currentState = State.SCANNING_3D
    private var progress = 0f
    private var shakeOffset = 0f
    private var animator: ValueAnimator? = null
    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val iosEasing = PathInterpolator(0.25f, 0.1f, 0.25f, 1f)

    private var touchStartX = 0f
    private var touchStartY = 0f
    private var isDismissed = false

    init {
        isClickable = true
        isFocusable = false
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startFaceUnlockTimeline()
    }

    private fun startFaceUnlockTimeline() {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 4200L // Timeline: 0.8s Quét 3D -> 0.4s Tick xanh -> 2.6s Giữ -> 0.4s Thu nhỏ
            interpolator = LinearInterpolator()
            addUpdateListener {
                progress = it.animatedValue as Float
                if (progress >= 0.25f && currentState == State.SCANNING_3D) {
                    currentState = State.SUCCESS
                }
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (!isDismissed) {
                        isDismissed = true
                        onDismissRequested()
                    }
                }
            })
            start()
        }
    }

    fun triggerFailureState() {
        currentState = State.FAILED
        val shakeAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 450L
            addUpdateListener {
                val t = it.animatedValue as Float
                shakeOffset = sin(t * Math.PI * 6.0).toFloat() * dp(8f) * (1f - t)
                invalidate()
            }
        }
        shakeAnim.start()
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
                    requestImmediateDismiss()
                }
            }
            MotionEvent.ACTION_UP -> {
                requestImmediateDismiss()
            }
        }
        return true
    }

    private fun requestImmediateDismiss() {
        if (isDismissed) return
        isDismissed = true
        animator?.cancel()
        onDismissRequested()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return

        val cx = width * 0.5f + shakeOffset
        val cy = height * 0.5f
        val t = progress.coerceIn(0f, 1f)

        // Phân đoạn timeline
        val spinT = when {
            t in 0.00f..0.25f -> iosEasing.getInterpolation(t / 0.25f)
            t > 0.25f -> 1f
            else -> 0f
        }

        val circleProgress = when {
            t in 0.25f..0.33f -> (t - 0.25f) / 0.08f
            t > 0.33f -> 1f
            else -> 0f
        }

        val checkmarkProgress = when {
            t in 0.33f..0.40f -> (t - 0.33f) / 0.07f
            t > 0.40f -> 1f
            else -> 0f
        }

        val alphaFade = when {
            t in 0.00f..0.90f -> 1f
            t > 0.90f -> (1f - t) / 0.10f
            else -> 0f
        }

        val currentAlpha = (255 * alphaFade).toInt()
        if (currentAlpha <= 0) return

        // 1. GIAI ĐOẠN 3D ORBITAL ENERGY RINGS (QUÉT KHUÔN MẶT 3D CHUẨN IOS)
        if (spinT < 1f && currentState != State.FAILED) {
            draw3DOrbitalSphere(canvas, cx, cy, spinT, currentAlpha)
        }

        // 2. GIAI ĐOẠN VÒNG TRÒN VÀ TICK XANH TỰA CỤM IOS (SUCCESS)
        if (currentState == State.SUCCESS || spinT >= 1f) {
            strokePaint.style = Paint.Style.STROKE
            strokePaint.strokeWidth = dp(5f)
            strokePaint.color = Color.argb(currentAlpha, Color.red(greenColor), Color.green(greenColor), Color.blue(greenColor))

            val r = dp(32f)
            if (circleProgress > 0f) {
                val rect = RectF(cx - r, cy - r, cx + r, cy + r)
                canvas.drawArc(rect, -90f, 360f * circleProgress, false, strokePaint)
            }

            if (checkmarkProgress > 0f) {
                val path = Path()
                val p1x = cx - dp(13f); val p1y = cy + dp(1f)
                val p2x = cx - dp(4f);  val p2y = cy + dp(11f)
                val p3x = cx + dp(15f); val p3y = cy - dp(8f)

                path.moveTo(p1x, p1y)
                if (checkmarkProgress < 0.5f) {
                    val p = checkmarkProgress * 2f
                    path.lineTo(p1x + (p2x - p1x) * p, p1y + (p2y - p1y) * p)
                } else {
                    path.lineTo(p2x, p2y)
                    val p = (checkmarkProgress - 0.5f) * 2f
                    path.lineTo(p2x + (p3x - p2x) * p, p2y + (p3y - p2y) * p)
                }
                canvas.drawPath(path, strokePaint)
            }
        }

        // 3. TRẠNG THÁI KHÔNG TÌM THẤY / LẮC MẶT CỜ
        if (currentState == State.FAILED) {
            strokePaint.style = Paint.Style.STROKE
            strokePaint.strokeWidth = dp(4f)
            strokePaint.color = Color.argb(currentAlpha, 255, 69, 58) // Màu đỏ warning Apple
            drawSimpleFaceIcon(canvas, cx, cy, strokePaint)
        }
    }

    // Mô phỏng 3 quả cầu hạt năng lượng xoay không gian 3D đan chéo (Orbital Wireframe Sphere)
    private fun draw3DOrbitalSphere(canvas: Canvas, cx: Float, cy: Float, progress: Float, alpha: Int) {
        strokePaint.style = Paint.Style.STROKE
        strokePaint.strokeWidth = dp(3.8f)

        val baseRadius = dp(30f)
        val angle = progress * Math.PI * 2.0

        // Vẽ 3 quỹ đạo xoay 3D đan chéo lệch góc
        for (i in 0..2) {
            canvas.save()
            val phaseOffset = i * (Math.PI / 3.0)
            val currentAngle = angle + phaseOffset

            val scaleX = cos(currentAngle).toFloat()
            val scaleY = sin(currentAngle).toFloat() * 0.4f + 0.6f

            // Tạo hiệu ứng bóng mờ đuôi (Motion Trail)
            val ringAlpha = (alpha * (0.85f - i * 0.15f)).toInt().coerceIn(0, 255)
            strokePaint.color = Color.argb(ringAlpha, Color.red(greenColor), Color.green(greenColor), Color.blue(greenColor))

            canvas.translate(cx, cy)
            canvas.rotate(i * 60f + (progress * 180f))
            canvas.scale(scaleX, scaleY)

            val rect = RectF(-baseRadius, -baseRadius, baseRadius, baseRadius)
            canvas.drawArc(rect, 0f, 360f, false, strokePaint)

            canvas.restore()
        }
    }

    private fun drawSimpleFaceIcon(canvas: Canvas, cx: Float, cy: Float, paint: Paint) {
        val half = dp(28f)
        val cr = dp(12f)
        val len = dp(18f)

        // 4 góc khung
        canvas.drawPath(Path().apply {
            moveTo(cx - half + len, cy - half); lineTo(cx - half + cr, cy - half)
            quadTo(cx - half, cy - half, cx - half, cy - half + cr); lineTo(cx - half, cy - half + len)
        }, paint)
        canvas.drawPath(Path().apply {
            moveTo(cx + half - len, cy - half); lineTo(cx + half - cr, cy - half)
            quadTo(cx + half, cy - half, cx + half, cy - half + cr); lineTo(cx + half, cy - half + len)
        }, paint)
        canvas.drawPath(Path().apply {
            moveTo(cx - half + len, cy + half); lineTo(cx - half + cr, cy + half)
            quadTo(cx - half, cy + half, cx - half, cy + half - cr); lineTo(cx - half, cy + half - len)
        }, paint)
        canvas.drawPath(Path().apply {
            moveTo(cx + half - len, cy + half); lineTo(cx + half - cr, cy + half)
            quadTo(cx + half, cy + half, cx + half, cy + half - cr); lineTo(cx + half, cy + half - len)
        }, paint)

        // Mắt & Miệng
        val fillPaint = Paint(paint).apply { style = Paint.Style.FILL }
        canvas.drawCircle(cx - dp(10f), cy - dp(4f), dp(2.5f), fillPaint)
        canvas.drawCircle(cx + dp(10f), cy - dp(4f), dp(2.5f), fillPaint)

        canvas.drawPath(Path().apply {
            moveTo(cx - dp(10f), cy + dp(10f))
            quadTo(cx, cy + dp(16f), cx + dp(10f), cy + dp(10f))
        }, paint)
    }
}
