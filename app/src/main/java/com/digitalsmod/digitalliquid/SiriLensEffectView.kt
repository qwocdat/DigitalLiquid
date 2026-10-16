package com.digitalsmod.digitalliquid

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.view.View
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Thấu kính quang học Siri Liquid Glass tinh khiết:
 * - Nửa dưới trong suốt hoàn toàn (nhìn trực tiếp các icon app bên dưới, không dùng nền giả).
 * - Viền mép thấu kính có quầng sáng phản quang mờ nhẹ (Frosted Rim Glow).
 * - Khi phóng to (cardMode = 1f): phần đen thu nhỏ lại lên đỉnh để nhường chỗ cho văn bản và khung chat.
 * - Dải sóng laser Apple nhấp nhô hữu cơ theo năng lượng.
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
    private var cardExpandProgress = 0f
    private var energy = 1f
    private var listening = true
    private var animator: ValueAnimator? = null
    private var intensityAnimator: ValueAnimator? = null
    private var cardModeAnimator: ValueAnimator? = null

    // Fallback cho Android < 13
    private val fallbackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val flarePath = Path()

    private val agsl = """
        uniform float2 resolution;
        uniform float time;
        uniform float morph;
        uniform float cardMode;
        uniform float energy;

        float3 appleSpectrum(float t) {
            float3 c0 = float3(0.08, 0.44, 1.00); // Deep Blue
            float3 c1 = float3(0.00, 0.90, 0.98); // Cyan
            float3 c2 = float3(0.20, 1.00, 0.50); // Mint Green
            float3 c3 = float3(1.00, 0.94, 0.20); // Warm Yellow
            float3 c4 = float3(1.00, 0.48, 0.10); // Coral Orange
            float3 c5 = float3(1.00, 0.12, 0.26); // Magenta Red

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

            // Hệ tọa độ thấu kính chuẩn hóa [-1, 1]
            float2 p = (uv - float2(0.5, 0.5)) * 2.0;
            float r2 = clamp(dot(p, p), 0.0, 1.0);
            float r = sqrt(r2);

            // 1. VỊ TRÍ RANH GIỚI:
            // Khi ở dạng giọt nước nhỏ: ranh giới ở y = 0.52
            // Khi phóng to ra (cardMode = 1): PHẦN ĐEN THU NHỎ LẠI ở đỉnh (chỉ chiếm đến y = 0.28)
            float baseHorizon = mix(0.52, 0.28, cardMode);
            float taper = sin(clamp(uv.x, 0.0, 1.0) * 3.14159265);
            float taperSharp = pow(max(0.0, taper), 1.35);

            // Chuyển động sóng Siri hữu cơ
            float waveFluid = -taper * mix(0.032, 0.010, cardMode)
                            + 0.006 * sin(uv.x * 6.28 - time * 2.2)
                            + 0.003 * sin(uv.x * 14.0 + time * 1.5) * energy;
            float horizonY = baseHorizon + waveFluid;

            float dy = uv.y - horizonY;
            float absDy = abs(dy);

            // Dải tia sáng laser mảnh sắc ở ranh giới
            float coreLine = exp(-absDy * mix(145.0, 175.0, cardMode)) * taperSharp;
            float midFlare = exp(-absDy * 32.0) * taperSharp;
            float softBloom = exp(-absDy * 9.0) * pow(max(0.0, taper), 0.7);

            float colorShift = clamp((uv.x - 0.06) / 0.88 + 0.03 * sin(time * 1.2), 0.0, 1.0);
            float3 rainbow = appleSpectrum(colorShift);

            float centerHotspot = exp(-pow((uv.x - 0.42) / mix(0.20, 0.35, cardMode), 2.0)) * coreLine * 1.8;
            float3 flareLight = rainbow * (midFlare * 1.1 + softBloom * 0.45) * energy
                              + mix(rainbow, float3(1.0, 1.0, 1.0), 0.75) * coreLine * 1.35
                              + float3(1.0, 0.99, 0.96) * centerHotspot;

            // 2. PHẦN ĐEN PHÍA TRÊN
            float blackMask = 1.0 - smoothstep(horizonY - 0.05, horizonY + 0.015, uv.y);
            float3 topBlack = float3(0.0, 0.0, 0.0) + rainbow * (softBloom * 0.05 * blackMask);

            // 3. VIỀN SÁNG MỜ Ở MÉP TRONG SUỐT (Frosted Rim Glow)
            // Lõi viền sắc nét ở ngoài rìa thấu kính + quầng mờ lan nhẹ
            float rimCore = exp(-pow((r - 0.98) * 36.0, 2.0));
            float rimSoft = exp(-pow((r - 0.95) * 12.0, 2.0));
            float rimIntensity = (rimCore * 1.2 + rimSoft * 0.35) * smoothstep(horizonY - 0.05, horizonY + 0.30, uv.y);
            float3 rimColor = mix(float3(0.85, 0.92, 1.0), rainbow, 0.35) * rimIntensity;

            // 4. MÀU SẮC TỔNG HỢP (NỬA DƯỚI HOÀN TOÀN TRONG SUỐT, KHÔNG VẼ BÓNG NHỰA)
            float3 rgb = topBlack * blackMask + flareLight + rimColor;

            // 5. ĐỘ TRONG SUỐT (ALPHA)
            // Phần đen trên đỉnh: alpha = 1.0
            // Nửa dưới: chỉ có dải sáng và viền sáng mờ là có alpha, còn bên trong hoàn toàn trong suốt (alpha = 0)
            float glassAlpha = clamp(rimIntensity * 0.85 + (midFlare * 0.75 + coreLine * 0.95) * energy, 0.0, 1.0);
            float finalAlpha = max(blackMask, glassAlpha) * morph;

            return half4(half3(clamp(rgb, 0.0, 1.0) * finalAlpha), half(finalAlpha));
        }
    """.trimIndent()

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                shader = RuntimeShader(agsl)
            } catch (_: Exception) {
                shader = null
            }
        }
    }

    fun setListening(active: Boolean) {
        if (listening == active) return
        listening = active
        val target = if (active) 1f else 0.52f
        intensityAnimator?.cancel()
        intensityAnimator = ValueAnimator.ofFloat(energy, target).apply {
            duration = 260L
            addUpdateListener {
                energy = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    fun setMorphProgress(progress: Float) {
        morphProgress = progress.coerceIn(0f, 1f)
        invalidate()
    }

    fun animateCardMode(expanded: Boolean) {
        val target = if (expanded) 1f else 0f
        cardModeAnimator?.cancel()
        cardModeAnimator = ValueAnimator.ofFloat(cardExpandProgress, target).apply {
            duration = 420L
            interpolator = PathInterpolator(0.22f, 1f, 0.36f, 1f)
            addUpdateListener {
                cardExpandProgress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (animator == null) {
            animator = ValueAnimator.ofFloat(0f, (Math.PI * 20.0).toFloat()).apply {
                duration = 24000L
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
        cardModeAnimator?.cancel()
        cardModeAnimator = null
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
            activeShader.setFloatUniform("cardMode", cardExpandProgress)
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
        val horizonY = if (cardExpandProgress > 0.5f) h * 0.28f else h * 0.52f
        fallbackPaint.shader = LinearGradient(
            0f, 0f, 0f, horizonY,
            intArrayOf(0xFF000000.toInt(), 0xFF000000.toInt(), 0x00000000),
            floatArrayOf(0f, 0.85f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, horizonY, fallbackPaint)

        flarePath.reset()
        val steps = 60
        for (i in 0..steps) {
            val u = i.toFloat() / steps
            val x = w * (0.08f + 0.84f * u)
            val arch = -sin(u * Math.PI).toFloat() * dp(6f)
            val wave = sin(u * 6.28f + phase) * dp(1.2f)
            val y = horizonY + arch + wave
            if (i == 0) flarePath.moveTo(x, y) else flarePath.lineTo(x, y)
        }

        fallbackPaint.style = Paint.Style.STROKE
        fallbackPaint.strokeCap = Paint.Cap.ROUND
        fallbackPaint.strokeWidth = dp(3.5f)
        fallbackPaint.shader = LinearGradient(
            w * 0.1f, 0f, w * 0.9f, 0f,
            intArrayOf(0xFF1E5BFF.toInt(), 0xFF00D8FF.toInt(), 0xFF5CFF95.toInt(), 0xFFFFF36B.toInt(), 0xFFFF8826.toInt(), 0xFFFF2A3C.toInt()),
            null, Shader.TileMode.CLAMP
        )
        fallbackPaint.alpha = (255 * morphProgress).toInt()
        canvas.drawPath(flarePath, fallbackPaint)

        // Viền sáng mờ ở nửa dưới
        fallbackPaint.shader = LinearGradient(
            0f, horizonY, 0f, h,
            intArrayOf(0x00FFFFFF, 0x44B5D6FF, 0xA0FFFFFF.toInt()),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        fallbackPaint.strokeWidth = dp(1.8f)
        val inset = dp(1f)
        val r = min(w, h) * 0.5f
        canvas.drawRoundRect(inset, inset, w - inset, h - inset, r - inset, r - inset, fallbackPaint)

        fallbackPaint.shader = null
        fallbackPaint.style = Paint.Style.FILL
        fallbackPaint.alpha = 255
    }
}
