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
import kotlin.math.cos

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
            // Tăng thời gian lên 2.2 giây để đủ mượt cho hoạt ảnh xoay 3D và bóng mờ
            duration = 2200L 
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

        // --- CÁC GIAI ĐOẠN (PHASES) ---
        // 1. Morph: Kéo dài từ viên thuốc ra hình vuông (0.0 -> 0.12) và thu lại (0.88 -> 1.0)
        val morph = when {
            t < 0.12f -> ease(t / 0.12f)
            t > 0.88f -> ease((1f - t) / 0.12f)
            else -> 1f
        }
        
        // 2. Alpha khuôn mặt: Hiện ra (0.12 -> 0.20)
        val faceAppearAlpha = when {
            t in 0.12f..0.20f -> (t - 0.12f) / 0.08f
            t > 0.20f -> 1f
            else -> 0f
        }

        // 3. Xoay 3D: (0.40 -> 0.65)
        val spinT = when {
            t in 0.40f..0.65f -> ease((t - 0.40f) / 0.25f)
            t > 0.65f -> 1f
            else -> 0f
        }

        // 4. Tick xanh xuất hiện: Vẽ vòng (0.68 -> 0.78), Vẽ tick (0.78 -> 0.88)
        val circleProgress = when {
            t in 0.68f..0.78f -> (t - 0.68f) / 0.10f
            t > 0.78f -> 1f
            else -> 0f
        }
        
        val checkmarkProgress = when {
            t in 0.78f..0.88f -> (t - 0.78f) / 0.10f
            t > 0.88f -> 1f
            else -> 0f
        }

        val successAlpha = when {
            t in 0.65f..0.88f -> 1f
            t > 0.88f -> 1f - ease((t - 0.88f) / 0.12f)
            else -> 0f
        }

        // --- BƯỚC 1: VẼ NỀN ĐEN TỪ VIÊN THUỐC ---
        val cx = width * 0.5f
        // Thông số của viên thuốc gốc (Island)
        val startW = dp(148f) 
        val startH = dp(38f)
        val startTop = dp(6f)
        
        // Thông số của ô vuông Face ID
        val endW = dp(150f)
        val endH = dp(150f)
        val endTop = dp(16f)

        val currentW = lerp(startW, endW, morph)
        val currentH = lerp(startH, endH, morph)
        val currentTop = lerp(startTop, endTop, morph)
        val currentRadius = lerp(startH * 0.5f, dp(40f), morph)

        tile.set(cx - currentW * 0.5f, currentTop, cx + currentW * 0.5f, currentTop + currentH)
        val cy = currentTop + currentH * 0.5f

        paint.style = Paint.Style.FILL
        paint.color = Color.BLACK
        canvas.drawRoundRect(tile, currentRadius, currentRadius, paint)

        // --- BƯỚC 2 & 3: VẼ FACE ID CÓ HIỆU ỨNG XOAY 3D VÀ BÓNG MỜ ---
        // Chỉ vẽ khuôn mặt khi chưa đến giai đoạn tick xanh
        if (t < 0.65f && faceAppearAlpha > 0f) {
            val baseAlpha = (255 * faceAppearAlpha).toInt()
            
            // Số lượng bóng mờ (Ghost trails)
            val ghostCount = 5 
            val ghostDelay = 0.04f // Khoảng cách thời gian trễ của bóng

            // Vẽ các bóng mờ (từ cũ nhất đến mới nhất)
            if (spinT > 0f && spinT < 1f) {
                for (i in ghostCount downTo 1) {
                    val gT = (spinT - (i * ghostDelay)).coerceIn(0f, 1f)
                    if (gT > 0f) {
                        val gAngle = gT * Math.PI * 2.0
                        val gScaleX = cos(gAngle).toFloat()
                        // Bóng càng xa càng mờ
                        val gAlpha = (baseAlpha * (0.6f - (i * 0.1f))).toInt().coerceAtLeast(0) 
                        drawFaceIcon(canvas, cx, cy, gScaleX, gAlpha)
                    }
                }
            }

            // Vẽ khuôn mặt chính ở hiện tại
            val currentAngle = spinT * Math.PI * 2.0
            val mainScaleX = cos(currentAngle).toFloat()
            // Nếu đang xoay ở nửa vòng sau (quay lưng), ta giảm nhẹ alpha để tạo chiều sâu 3D
            val isBackFacing = cos(currentAngle) < 0
            val mainFaceAlpha = if (isBackFacing && spinT > 0) (baseAlpha * 0.4f).toInt() else baseAlpha
            
            drawFaceIcon(canvas, cx, cy, mainScaleX, mainFaceAlpha)
        }

        // --- BƯỚC 4: VẼ TICK XANH HOÀN THÀNH ---
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

    // Hàm vẽ chi tiết khuôn mặt Face ID
    private fun drawFaceIcon(canvas: Canvas, cx: Float, cy: Float, scaleX: Float, alpha: Int) {
        if (alpha <= 0) return
        canvas.save()
        // Tạo hiệu ứng lật 3D bằng cách nén trục X
        canvas.scale(scaleX, 1f, cx, cy) 

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(4.5f)
        paint.color = Color.argb(alpha, 150, 150, 150)
        
        val half = dp(30f)
        val cr = dp(14f)
        val len = dp(20f)

        // 4 góc
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

        // Mắt
        paint.style = Paint.Style.FILL
        canvas.drawCircle(cx - dp(14f), cy - dp(6f), dp(3f), paint)
        canvas.drawCircle(cx + dp(14f), cy - dp(6f), dp(3f), paint)

        // Mũi
        paint.style = Paint.Style.STROKE
        canvas.drawPath(Path().apply {
            moveTo(cx, cy - dp(2f))
            lineTo(cx, cy + dp(6f))
            quadTo(cx, cy + dp(10f), cx - dp(5f), cy + dp(10f))
        }, paint)

        // Miệng
        canvas.drawPath(Path().apply {
            moveTo(cx - dp(14f), cy + dp(15f))
            quadTo(cx, cy + dp(23f), cx + dp(14f), cy + dp(15f))
        }, paint)

        canvas.restore()
    }
}
