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
import kotlin.math.sin

/**
 * GPU-rendered Siri-inspired wave / translucent-glass surface.
 * API 33+ uses AGSL RuntimeShader; older Android versions use a Canvas fallback.
 * The containing VoiceInteractionSession window can enable system backdrop blur,
 * so the translucent lower region gets real background depth where the OS supports it.
 */
class SiriWaveGlassView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val bounds = RectF()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var shader: RuntimeShader? = null
    private var phase = 0f
    private var animator: ValueAnimator? = null

    private val fallbackWave = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fallbackPath = Path()
    private val glassPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(0.8f)
        color = 0x66FFFFFF
    }

    private val agsl = """
        uniform float2 resolution;
        uniform float time;

        float3 spectrum(float x) {
            float3 blue = float3(0.10, 0.35, 1.00);
            float3 cyan = float3(0.14, 0.92, 1.00);
            float3 green = float3(0.42, 1.00, 0.58);
            float3 yellow = float3(1.00, 0.88, 0.24);
            float3 orange = float3(1.00, 0.42, 0.12);
            float3 red = float3(1.00, 0.12, 0.20);
            float3 c = mix(blue, cyan, smoothstep(0.00, 0.20, x));
            c = mix(c, green, smoothstep(0.18, 0.39, x));
            c = mix(c, yellow, smoothstep(0.38, 0.59, x));
            c = mix(c, orange, smoothstep(0.58, 0.79, x));
            c = mix(c, red, smoothstep(0.78, 1.00, x));
            return c;
        }

        half4 main(float2 fragCoord) {
            float2 safeResolution = max(resolution, float2(1.0, 1.0));
            float2 uv = fragCoord / safeResolution;
            float x = clamp(uv.x, 0.0, 1.0);
            float t = time;

            // A layered travelling wave with small high-frequency detail, not a static gradient.
            float center = 0.455
                + 0.030 * sin(x * 10.5 - t * 1.85)
                + 0.012 * sin(x * 25.0 + t * 1.35)
                + 0.006 * sin(x * 43.0 - t * 2.60);
            float distance = uv.y - center;
            float halo = exp(-abs(distance) * 30.0);
            float core = exp(-abs(distance) * 190.0);
            float outerHalo = exp(-abs(distance) * 9.0);

            float lowerGlass = smoothstep(0.435, 0.59, uv.y);
            float upperBlack = 1.0 - smoothstep(0.425, 0.505, uv.y);
            float edgeLight = exp(-abs(uv.y - 0.515) * 30.0);

            float3 base = mix(float3(0.004, 0.006, 0.013),
                              float3(0.34, 0.42, 0.53), lowerGlass * 0.40);
            base += float3(0.018, 0.024, 0.040) * (1.0 - uv.y);
            base += spectrum(x) * (halo * 0.46 + outerHalo * 0.13);
            base += float3(0.86, 0.94, 1.0) * core * 0.95;
            base += float3(0.48, 0.64, 1.0) * edgeLight * 0.08;

            // Upper half stays near-black; lower half remains translucent for a glass surface.
            float alpha = mix(0.97, 0.23, lowerGlass);
            alpha = max(alpha, upperBlack * 0.97);
            alpha = max(alpha, halo * 0.28 + core * 0.58);
            return half4(base, clamp(alpha, 0.0, 1.0));
        }
    """.trimIndent()

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
        if (Build.VERSION.SDK_INT >= 33) {
            try { shader = RuntimeShader(agsl) } catch (_: Exception) { shader = null }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (animator == null) {
            animator = ValueAnimator.ofFloat(0f, (Math.PI * 2.0).toFloat()).apply {
                duration = 2800L
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener {
                    phase = it.animatedValue as Float
                    shader?.setFloatUniform("time", phase)
                    invalidate()
                }
                start()
            }
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
        bounds.set(0f, 0f, width.toFloat(), height.toFloat())
        val r = dp(34f)
        val activeShader = shader
        if (activeShader != null && Build.VERSION.SDK_INT >= 33) {
            activeShader.setFloatUniform("resolution", max(1, width).toFloat(), max(1, height).toFloat())
            activeShader.setFloatUniform("time", phase)
            paint.shader = activeShader
            canvas.drawRoundRect(bounds, r, r, paint)
            paint.shader = null
        } else {
            drawFallback(canvas, r)
        }
        drawSpecularDepth(canvas, r)
    }

    private fun drawFallback(canvas: Canvas, radius: Float) {
        glassPaint.shader = LinearGradient(
            0f, 0f, 0f, height.toFloat(),
            intArrayOf(0xF5000000.toInt(), 0xF8000000.toInt(), 0x554C6077, 0x334E657D),
            floatArrayOf(0f, 0.42f, 0.60f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(bounds, radius, radius, glassPaint)
        glassPaint.shader = null

        val colors = intArrayOf(
            Color.parseColor("#245DFF"), Color.parseColor("#22D9FF"),
            Color.parseColor("#5DFFB6"), Color.parseColor("#FFE05D"),
            Color.parseColor("#FF922D"), Color.parseColor("#FF3D5A")
        )
        val gradient = LinearGradient(0f, 0f, width.toFloat(), 0f, colors, null, Shader.TileMode.CLAMP)
        for (layer in 0..2) {
            fallbackPath.reset()
            val baseY = height * (0.455f + layer * 0.008f)
            fallbackPath.moveTo(0f, baseY)
            for (i in 0..80) {
                val x = width * i / 80f
                val u = i / 80f
                val y = baseY + dp(5f + layer * 1.6f) *
                    (sin(u * 10.5f - phase * 0.8f) + 0.38f * sin(u * 25f + phase * 0.6f))
                fallbackPath.lineTo(x, y)
            }
            fallbackWave.shader = gradient
            fallbackWave.strokeWidth = when (layer) { 0 -> dp(1.2f); 1 -> dp(3f); else -> dp(8f) }
            fallbackWave.alpha = when (layer) { 0 -> 235; 1 -> 105; else -> 34 }
            canvas.drawPath(fallbackPath, fallbackWave)
        }
        fallbackWave.shader = null
        fallbackWave.alpha = 255
    }

    private fun drawSpecularDepth(canvas: Canvas, radius: Float) {
        // Thin reflected rim and an oblique soft highlight make the glass read as a surface.
        linePaint.strokeWidth = dp(0.9f)
        linePaint.color = 0x70FFFFFF
        canvas.drawArc(bounds.left + dp(1f), bounds.top + dp(1f), bounds.right - dp(1f),
            bounds.bottom - dp(1f), 190f, 156f, false, linePaint)
        linePaint.color = 0x35B9D7FF
        linePaint.strokeWidth = dp(1.1f)
        canvas.drawArc(bounds.left + dp(2f), bounds.top + dp(2f), bounds.right - dp(2f),
            bounds.bottom - dp(2f), 12f, 150f, false, linePaint)
        linePaint.color = 0x22FFFFFF
        linePaint.strokeWidth = dp(0.6f)
        canvas.drawLine(bounds.left + dp(24f), bounds.bottom - dp(16f),
            bounds.right - dp(40f), bounds.bottom - dp(16f), linePaint)
    }
}
