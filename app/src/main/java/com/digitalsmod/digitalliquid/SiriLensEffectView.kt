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
 * Sử dụng AGSL RuntimeShader trên GPU để tính toán bề mặt cầu thủy tinh 3D thật,
 * tán sắc quang phổ chân thực và đáy kính khúc xạ quang học méo hình trên bề mặt tiếp xúc.
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

    // Fallback cho Android < 13
    private val fallbackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val flarePath = Path()

    private val agsl = """
        uniform float2 resolution;
        uniform float time;
        uniform float morph;
        uniform float energy;

        float3 siriSpectrum(float t) {
            float3 c0 = float3(0.04, 0.38, 1.00); // Blue
            float3 c1 = float3(0.00, 0.88, 0.98); // Cyan
            float3 c2 = float3(0.12, 0.98, 0.44); // Green
            float3 c3 = float3(1.00, 0.92, 0.16); // Yellow
            float3 c4 = float3(1.00, 0.46, 0.08); // Orange
            float3 c5 = float3(1.00, 0.10, 0.22); // Red

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

            // Chuyển về tọa độ hình cầu trung tâm [-1, 1]
            float2 p = (uv - float2(0.5, 0.5)) * 2.0;
            float distSq = dot(p, p);
            float dist = sqrt(max(0.0, distSq));

            // Tính toán vector pháp tuyến bề mặt cầu 3D
            float z = sqrt(max(0.0, 1.0 - distSq));
            float3 N = float3(p.x, p.y, z);

            // 1. Dải sóng quang phổ nằm chính xác ở trục ngang trung tâm (y = 0.50)
            float arch = -sin(uv.x * 3.14159) * 0.046;
            float wave = 0.008 * sin(uv.x * 6.28 - time * 1.8) + 0.004 * sin(uv.x * 12.56 + time * 1.1);
            float beamCenterY = 0.50 + arch + wave;
            float dy = abs(uv.y - beamCenterY);

            float beamCore = exp(-dy * 125.0);
            float beamBloom = exp(-dy * 24.0);
            float beamWide = exp(-dy * 7.0);

            float specPos = clamp((uv.x - 0.08) / 0.84, 0.0, 1.0);
            float3 spectrumColor = siriSpectrum(specPos);

            // Điểm hội tụ quang học trắng rực ở lệch tâm bên trái
            float focalSpot = exp(-pow((uv.x - 0.38) / 0.16, 2.0)) * beamCore * 1.85;
            float3 hotWhite = float3(1.0, 0.99, 0.96) * focalSpot;

            // 2. Phần màu đen ở đỉnh: Có ám một chút ánh màu huyền bí nhẹ từ dải phổ phía trên
            float blackDome = 1.0 - smoothstep(0.36, 0.49, uv.y);
            float3 darkTint = mix(float3(0.002, 0.003, 0.008), spectrumColor * 0.038, (1.0 - uv.y) * 0.45);

            // 3. Hiệu ứng khúc xạ quang học méo hình trên phần tiếp xúc bên dưới:
            // Tính toán vector khúc xạ thấu kính cong (Refractive Lens Distortion Offset)
            float2 refractDistort = N.xy * (0.12 * (1.0 - N.z));
            float2 distortedCoord = uv + refractDistort;

            // Vệt méo quang học cong theo thấu kính (Refraction Caustic Distortion Fringe)
            float causticRadius = length(float2((distortedCoord.x - 0.50) * 1.18, (distortedCoord.y - 0.42) * 1.0));
            float causticDistort = exp(-pow((causticRadius - 0.44) * 28.0, 2.0)) * smoothstep(0.48, 0.88, uv.y);
            
            // Tán sắc viền quang sai (Chromatic aberration on refraction)
            float3 causticColor = mix(float3(0.15, 0.65, 1.0), float3(0.95, 0.98, 1.0), causticDistort * 0.8) * causticDistort * 0.75;

            // Viền khúc xạ thủy tinh ngoài cùng siêu mảnh (Fresnel Rim)
            float fresnel = pow(clamp(1.0 - z, 0.0, 1.0), 3.8);
            float rimHighlight = fresnel * smoothstep(0.92, 0.99, dist);
            float3 rimColor = float3(0.92, 0.96, 1.0) * rimHighlight * smoothstep(0.20, 0.85, uv.y) * 1.4;

            // Tổng hợp màu sắc (không vẽ bất kỳ vệt bóng đục nào)
            float3 color = darkTint * blackDome;
            color += spectrumColor * (beamBloom * 0.90 + beamWide * 0.35) * energy;
            color += (spectrumColor * 0.45 + float3(0.80, 0.90, 1.0) * 0.55) * beamCore * 1.35;
            color += hotWhite;
            color += causticColor;
            color += rimColor;

            // 4. Độ trong suốt: Nửa dưới trong suốt tự nhiên, cho phép nhìn xuyên qua app bên dưới kèm hiệu ứng khúc xạ
            float glassBodyAlpha = mix(0.03, 0.10, smoothstep(0.40, 0.98, dist)) * smoothstep(0.46, 0.58, uv.y);
            float finalAlpha = max(blackDome, glassBodyAlpha);
            finalAlpha = max(finalAlpha, (beamBloom * 0.72 + beamCore * 0.98) * energy);
            finalAlpha = max(finalAlpha, causticDistort * 0.70);
            finalAlpha = max(finalAlpha, rimHighlight * 0.90);
            finalAlpha = clamp(finalAlpha * morph, 0.0, 1.0);

            return half4(half3(clamp(color, 0.0, 1.0) * finalAlpha), half(finalAlpha));
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
        fallbackPaint.shader = LinearGradient(
            0f, 0f, 0f, h * 0.48f,
            intArrayOf(0xFF03040B.toInt(), 0xEE020306.toInt(), 0x00000000),
            floatArrayOf(0f, 0.75f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h * 0.48f, fallbackPaint)

        val horizonY = h * 0.50f
        flarePath.reset()
        val steps = 60
        for (i in 0..steps) {
            val u = i.toFloat() / steps
            val x = w * (0.08f + 0.84f * u)
            val arch = -sin(u * Math.PI).toFloat() * dp(6.5f)
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

        fallbackPaint.shader = LinearGradient(
            0f, h * 0.4f, 0f, h,
            intArrayOf(0x00FFFFFF, 0x44B5D6FF, 0xE8FFFFFF.toInt()),
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
