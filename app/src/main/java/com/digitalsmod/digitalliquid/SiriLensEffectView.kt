package com.digitalsmod.digitalliquid

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Hiệu ứng kính quang học Siri 1:1 nguyên bản cho DigitalLiquid.
 * Sử dụng AGSL RuntimeShader trên GPU để tính toán khúc xạ thủy tinh 3D,
 * dải tán sắc quang phổ chân thực và độ trong suốt sâu nhìn xuyên thấu nền màn hình.
 */
class SiriLensEffectView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private fun dp(value: Float) = value * density

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clipPath = Path()
    private val viewBounds = RectF()
    private var shader: RuntimeShader? = null
    private var phase = 0f
    private var morphProgress = 1f
    private var energy = 1f
    private var listening = true
    private var animator: ValueAnimator? = null
    private var intensityAnimator: ValueAnimator? = null

    // Fallback cho thiết bị Android < 13
    private val fallbackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val flarePath = Path()

    private val agsl = """
        uniform float2 resolution;
        uniform float time;
        uniform float morph;
        uniform float energy;

        float3 siriSpectrum(float t) {
            float3 c0 = float3(0.05, 0.35, 1.00);
            float3 c1 = float3(0.00, 0.85, 0.98);
            float3 c2 = float3(0.15, 0.98, 0.42);
            float3 c3 = float3(1.00, 0.90, 0.18);
            float3 c4 = float3(1.00, 0.45, 0.08);
            float3 c5 = float3(1.00, 0.12, 0.24);

            float x = clamp(t, 0.0, 1.0);
            float3 col = mix(c0, c1, smoothstep(0.00, 0.20, x));
            col = mix(col, c2, smoothstep(0.18, 0.40, x));
            col = mix(col, c3, smoothstep(0.38, 0.60, x));
            col = mix(col, c4, smoothstep(0.58, 0.80, x));
            col = mix(col, c5, smoothstep(0.78, 1.00, x));
            return col;
        }

        half4 main(float2 fragCoord) {
            float2 res = max(resolution, float2(1.0, 1.0));
            float2 uv = fragCoord / res;
            float x = uv.x;
            float y = uv.y;

            float2 p = (uv - float2(0.5, 0.5)) * 2.0;
            float distSq = p.x * p.x + p.y * p.y;
            float dist = sqrt(max(0.0, distSq));

            // 1. Đỉnh quả cầu giữ màu đen sâu tuyệt đối để hòa hợp làm 1 với camera / Dynamic Island
            float blackTop = 1.0 - smoothstep(0.28, 0.44, y);

            // 2. Dải quang phổ tán sắc uốn cong chuẩn Apple Siri 1:1
            float arch = -sin(x * 3.14159) * 0.065;
            float wave = 0.012 * sin(x * 6.28 - time * 1.5) + 0.006 * sin(x * 12.56 + time * 0.9);
            float flareLine = 0.415 + arch + wave;
            float dy = y - flareLine;

            float flareCore = exp(-abs(dy) * 120.0);
            float flareMid = exp(-abs(dy) * 28.0);
            float flareBloom = exp(-abs(dy) * 7.5);

            float specT = clamp((x - 0.12) / 0.76, 0.0, 1.0);
            float3 specCol = siriSpectrum(specT);

            // Điểm hội tụ ánh sáng trắng rực ở lệch tâm trái giống hệt ảnh mẫu
            float hotSpot = exp(-pow((x - 0.38) / 0.18, 2.0)) * flareCore * 1.7;
            float3 hotLight = float3(1.0, 0.99, 0.96) * hotSpot;

            // 3. Khúc xạ thủy tinh trong suốt nửa dưới cho phép nhìn thấy chiều sâu nền phía sau
            float3 glassTint = float3(0.05, 0.09, 0.16);
            float glassAlpha = mix(0.12, 0.32, smoothstep(0.35, 0.95, dist));

            // Vệt khúc xạ cong hình lưỡi liềm ở lòng kính dưới
            float causticDist = length(float2((x - 0.5) * 1.25, y - 0.46));
            float causticArc = exp(-pow(causticDist - 0.46, 2.0) * 110.0) * smoothstep(0.40, 0.82, y);
            float3 causticCol = float3(0.80, 0.90, 1.0) * causticArc * 0.65;

            // Viền khúc xạ bạc sắc nét bao quanh đáy quả cầu
            float rimShape = smoothstep(0.88, 0.98, dist) * (1.0 - smoothstep(0.985, 1.01, dist));
            float rimWeight = smoothstep(0.15, 0.85, y);
            float3 rimCol = float3(0.92, 0.96, 1.0) * rimShape * rimWeight * 1.8;

            // Ánh phản chiếu nhẹ ở đỉnh
            float topGlance = exp(-pow((x - 0.5) / 0.35, 2.0)) * exp(-pow((y - 0.06) / 0.04, 2.0)) * 0.22;
            float3 topCol = float3(1.0) * topGlance;

            // Tổng hợp các lớp quang học
            float3 rgb = mix(glassTint, float3(0.0, 0.0, 0.0), blackTop);
            rgb += specCol * (flareMid * 0.92 + flareBloom * 0.38) * energy;
            rgb += (specCol * 0.4 + float3(0.7, 0.85, 1.0) * 0.6) * flareCore * 1.2;
            rgb += hotLight;
            rgb += causticCol;
            rgb += rimCol;
            rgb += topCol;

            // Đỉnh đen hoàn toàn (alpha 1.0), đáy thủy tinh trong suốt cho nền xuyên thấu
            float finalAlpha = max(blackTop, glassAlpha);
            finalAlpha = max(finalAlpha, (flareMid * 0.75 + flareCore * 0.95) * energy);
            finalAlpha = max(finalAlpha, rimShape * rimWeight * 0.85);
            finalAlpha = clamp(finalAlpha * morph, 0.0, 1.0);

            return half4(half3(clamp(rgb, 0.0, 1.0) * finalAlpha), half(finalAlpha));
        }
    """.trimIndent()

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
        contentDescription = "Hiệu ứng kính Siri nhiều màu của DigitalLiquid"
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                shader = RuntimeShader(agsl)
            } catch (_: Exception) {
                shader = null
            }
        }
    }

    fun setMorphProgress(progress: Float) {
        morphProgress = progress.coerceIn(0f, 1f)
        invalidate()
    }

    fun setListening(active: Boolean) {
        if (listening == active) return
        listening = active
        val target = if (active) 1f else 0.52f
        intensityAnimator?.cancel()
        intensityAnimator = ValueAnimator.ofFloat(energy, target).apply {
            duration = 260L
            addUpdateListener { energy = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (animator == null) {
            animator = ValueAnimator.ofFloat(0f, (Math.PI * 2.0).toFloat()).apply {
                duration = 3600L
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener {
                    phase = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        intensityAnimator?.cancel()
        intensityAnimator = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        viewBounds.set(0f, 0f, w, h)
        val cornerRadius = min(w, h) * 0.5f
        clipPath.reset()
        clipPath.addRoundRect(viewBounds, cornerRadius, cornerRadius, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clipPath)

        val activeShader = shader
        if (activeShader != null && Build.VERSION.SDK_INT >= 33) {
            activeShader.setFloatUniform("resolution", w, h)
            activeShader.setFloatUniform("time", phase)
            activeShader.setFloatUniform("morph", morphProgress)
            activeShader.setFloatUniform("energy", energy)
            paint.shader = activeShader
            canvas.drawRect(viewBounds, paint)
            paint.shader = null
        } else {
            drawFallback(canvas, w, h)
        }

        canvas.restore()
    }

    private fun drawFallback(canvas: Canvas, w: Float, h: Float) {
        // Nền đen trên đỉnh hòa vào Camera Cutout
        fallbackPaint.shader = LinearGradient(
            0f, 0f, 0f, h * 0.45f,
            intArrayOf(0xFF000000.toInt(), 0xCC000000.toInt(), 0x00000000),
            floatArrayOf(0f, 0.7f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h * 0.45f, fallbackPaint)

        // Độ sâu thủy tinh trong suốt nửa dưới
        fallbackPaint.shader = RadialGradient(
            w * 0.5f, h * 0.65f, w * 0.5f,
            intArrayOf(0x153A6596, 0x40182E47, 0x00000000),
            floatArrayOf(0f, 0.7f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, h * 0.35f, w, h, fallbackPaint)

        // Cầu vồng uốn cong
        val horizonY = h * 0.42f
        flarePath.reset()
        val steps = 60
        for (i in 0..steps) {
            val u = i.toFloat() / steps
            val x = w * (0.1f + 0.8f * u)
            val arch = -sin(u * Math.PI).toFloat() * dp(6f)
            val wave = sin(u * 6.28f + phase) * dp(1.2f)
            val y = horizonY + arch + wave
            if (i == 0) flarePath.moveTo(x, y) else flarePath.lineTo(x, y)
        }

        fallbackPaint.style = Paint.Style.STROKE
        fallbackPaint.strokeCap = Paint.Cap.ROUND
        fallbackPaint.strokeWidth = dp(14f)
        fallbackPaint.shader = LinearGradient(
            w * 0.1f, 0f, w * 0.9f, 0f,
            intArrayOf(0xFF1E5BFF.toInt(), 0xFF00D8FF.toInt(), 0xFF5CFF95.toInt(), 0xFFFFF36B.toInt(), 0xFFFF8826.toInt(), 0xFFFF2A3C.toInt()),
            null, Shader.TileMode.CLAMP
        )
        fallbackPaint.alpha = (180 * morphProgress).toInt()
        canvas.drawPath(flarePath, fallbackPaint)

        fallbackPaint.strokeWidth = dp(3.5f)
        fallbackPaint.alpha = (255 * morphProgress).toInt()
        canvas.drawPath(flarePath, fallbackPaint)

        // Viền bạc khúc xạ dưới đáy
        fallbackPaint.shader = LinearGradient(
            0f, h * 0.4f, 0f, h,
            intArrayOf(0x00FFFFFF, 0x55B5D6FF, 0xE8FFFFFF.toInt()),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        fallbackPaint.strokeWidth = dp(1.6f)
        val inset = dp(1f)
        val r = min(w, h) * 0.5f
        canvas.drawRoundRect(inset, inset, w - inset, h - inset, r - inset, r - inset, fallbackPaint)

        fallbackPaint.shader = null
        fallbackPaint.style = Paint.Style.FILL
        fallbackPaint.alpha = 255
    }
}
