package com.digitalsmod.digitalliquid

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.LinearGradient
import android.graphics.Shader
import android.os.Build
import android.animation.ValueAnimator
import android.view.animation.LinearInterpolator
import android.view.View
import kotlin.math.min

/** Solid black Dynamic Island surface with a restrained, motion-driven AGSL smear. */
class LiquidGlassView(context: Context) : View(context) {
    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        maskFilter = try { BlurMaskFilter(dp(3.2f), BlurMaskFilter.Blur.NORMAL) } catch (_: Throwable) { null }
    }
    private val bodyRect = RectF()
    private val trailRect = RectF()
    private val glintPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(0.75f)
    }
    private var shimmerAnimator: ValueAnimator? = null
    private var strength = 0.82f
    private var motionBlurFactor = 0f
    private var motionDirection = 1f
    private var tint = Color.BLACK
    private var expanded = false
    private var phase = 0f
    private var shaderFailed = false
    private var shaderRenderer: Api33Shader? = null

    init {
        setLayerType(View.LAYER_TYPE_HARDWARE, null)
        if (Build.VERSION.SDK_INT >= 33) {
            try { shaderRenderer = Api33Shader(AGSL) } catch (_: Throwable) { shaderFailed = true }
        }
    }

    fun configure(glassStrength: Int, tintName: String, expandedNow: Boolean) {
        strength = (glassStrength.coerceIn(15, 100) / 100f).coerceIn(0.15f, 1f)
        tint = when (tintName) {
            "violet" -> Color.rgb(3, 2, 6)
            "ice" -> Color.rgb(2, 4, 6)
            "graphite" -> Color.rgb(4, 4, 5)
            else -> Color.BLACK
        }
        expanded = expandedNow
        invalidate()
    }

    fun setPhase(value: Float) {
        phase = value.coerceIn(0f, 1f)
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startShimmerIfAllowed()
    }

    override fun onDetachedFromWindow() {
        shimmerAnimator?.cancel()
        shimmerAnimator = null
        super.onDetachedFromWindow()
    }

    private fun startShimmerIfAllowed() {
        if (AppPrefs.get(context).getBoolean(AppPrefs.REDUCE_MOTION, false)) return
        if (shimmerAnimator != null) return
        shimmerAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 6_800L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                phase = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    fun setMotionBlur(value: Float, direction: Float = motionDirection) {
        motionBlurFactor = value.coerceIn(0f, 1f)
        motionDirection = if (direction < 0f) -1f else 1f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        if (Build.VERSION.SDK_INT >= 33 && !shaderFailed) {
            try {
                val shader = shaderRenderer ?: Api33Shader(AGSL).also { shaderRenderer = it }
                shader.draw(canvas, bodyPaint, w, h, motionBlurFactor, motionDirection, tint, expanded, phase, strength)
                return
            } catch (_: Throwable) {
                shaderFailed = true
                bodyPaint.shader = null
            }
        }
        drawFallback(canvas, w, h)
    }

    private fun drawFallback(canvas: Canvas, w: Float, h: Float) {
        val radius = min(h * 0.5f, w * 0.5f)
        bodyRect.set(0.5f, 0.5f, w - 0.5f, h - 0.5f)
        val highlight = if (tint == Color.BLACK) 0xFF171B25.toInt() else tint
        bodyPaint.shader = LinearGradient(
            0f, 0f, 0f, h,
            intArrayOf(highlight, tint, 0xFF000000.toInt()),
            floatArrayOf(0f, 0.42f, 1f), Shader.TileMode.CLAMP
        )
        bodyPaint.alpha = 255
        canvas.drawRoundRect(bodyRect, radius, radius, bodyPaint)
        bodyPaint.shader = null

        val sheenX = ((phase * 1.34f) % 1.0f) * (w + dp(44f)) - dp(22f)
        glintPaint.shader = LinearGradient(
            sheenX, 0f, sheenX + dp(28f), h,
            intArrayOf(0x00FFFFFF, (0x20 * strength).toInt().shl(24) or 0x00FFFFFF,
                (0x08 * strength).toInt().shl(24) or 0x00FFFFFF, 0x00FFFFFF),
            floatArrayOf(0f, 0.44f, 0.65f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(bodyRect, radius, radius, glintPaint)
        glintPaint.shader = LinearGradient(
            0f, 0f, w, h * 0.8f,
            intArrayOf(0x35FFFFFF, 0x0EFFFFFF, 0x01FFFFFF),
            floatArrayOf(0f, 0.42f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(bodyRect, radius, radius, glintPaint)
        glintPaint.shader = null

        // Layered direction-sensitive after-smear remains active only while moving.
        if (motionBlurFactor > 0.01f) {
            val travel = dp(20f) * motionBlurFactor * motionDirection
            trailPaint.color = 0xFFD4DFF3.toInt()
            trailPaint.alpha = (motionBlurFactor * 22f).toInt().coerceIn(0, 22)
            trailRect.set(-travel * 0.46f, dp(0.5f), w - travel * 0.46f, h - dp(0.5f))
            canvas.drawRoundRect(trailRect, radius, radius, trailPaint)
            trailPaint.alpha = (motionBlurFactor * 13f).toInt().coerceIn(0, 13)
            trailRect.set(-travel * 0.82f, dp(2f), w - travel * 0.82f, h - dp(2f))
            canvas.drawRoundRect(trailRect, radius, radius, trailPaint)
        }

        rimPaint.shader = LinearGradient(
            0f, 0f, w, h,
            intArrayOf(0x75FFFFFF, 0x18D9E7FF, 0x4AFFFFFF, 0x12FFFFFF),
            floatArrayOf(0f, 0.38f, 0.74f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(bodyRect, radius, radius, rimPaint)
        rimPaint.shader = null
        trailPaint.alpha = 0
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    @android.annotation.TargetApi(33)
    private class Api33Shader(source: String) {
        private val runtimeShader = android.graphics.RuntimeShader(source)

        fun draw(
            canvas: Canvas,
            paint: Paint,
            width: Float,
            height: Float,
            motion: Float,
            direction: Float,
            tint: Int,
            expanded: Boolean,
            phase: Float,
            intensity: Float
        ) {
            runtimeShader.setFloatUniform("uSize", width, height)
            runtimeShader.setFloatUniform("uMotion", motion)
            runtimeShader.setFloatUniform("uDirection", direction)
            runtimeShader.setFloatUniform("uExpanded", if (expanded) 1f else 0f)
            runtimeShader.setFloatUniform("uPhase", phase)
            runtimeShader.setFloatUniform("uIntensity", intensity)
            runtimeShader.setFloatUniform(
                "uTint",
                Color.red(tint) / 255f,
                Color.green(tint) / 255f,
                Color.blue(tint) / 255f,
                1f
            )
            paint.shader = runtimeShader
            canvas.drawRect(0f, 0f, width, height, paint)
            paint.shader = null
        }
    }

    companion object {
        private const val AGSL = """
            uniform float2 uSize;
            uniform float uMotion;
            uniform float uDirection;
            uniform float uExpanded;
            uniform float uPhase;
            uniform float uIntensity;
            uniform float4 uTint;

            float roundedBox(float2 p, float2 b, float r) {
                float2 q = abs(p) - b + float2(r, r);
                return length(max(q, float2(0.0, 0.0))) + min(max(q.x, q.y), 0.0) - r;
            }

            half4 main(float2 p) {
                float2 size = max(uSize, float2(1.0, 1.0));
                float2 uv = p / size;
                float2 halfSize = size * 0.5 - float2(0.7, 0.7);
                float radius = min(size.y * (0.5 - uExpanded * 0.25), size.x * 0.5);
                float2 centered = p - size * 0.5;
                float d = roundedBox(centered, halfSize, radius);
                float mask = 1.0 - smoothstep(-0.8, 0.8, d);
                float rim = 1.0 - smoothstep(0.0, 2.8, abs(d));

                float sweepY = 0.14 + 0.025 * sin(uPhase * 6.28318);
                float topSheen = exp(-pow((uv.y - sweepY) * 13.0, 2.0));
                float lowerSheen = exp(-pow((uv.y - 0.91) * 17.0, 2.0));
                float sheenX = fract(uPhase * 0.72);
                float movingSheen = exp(-pow((uv.x - sheenX) * 28.0, 2.0));
                float light = (topSheen * 0.12 + lowerSheen * 0.032 + movingSheen * 0.105) * uIntensity;

                float motion = clamp(uMotion, 0.0, 1.0);
                float displacement = uDirection * motion * 21.0;
                float d1 = roundedBox(centered - float2(-displacement, 0.0), halfSize, radius);
                float d2 = roundedBox(centered - float2(-displacement * 0.62, 0.0), halfSize, radius);
                float m1 = 1.0 - smoothstep(-2.4, 2.4, d1);
                float m2 = 1.0 - smoothstep(-1.5, 1.5, d2);
                float trail = max(max(m1 - mask, 0.0) * 0.25, max(m2 - mask, 0.0) * 0.16) * motion;
                float leadingX = uDirection > 0.0 ? size.x * 0.91 : size.x * 0.09;
                float leading = exp(-pow((p.x - leadingX) / 4.5, 2.0)) * motion * 0.065;

                float3 body = uTint.rgb * (0.83 + uv.y * 0.09);
                float3 color = body;
                color += float3(0.54, 0.61, 0.76) * light;
                color += float3(0.48, 0.55, 0.70) * rim * (0.12 + uIntensity * 0.23);
                color += float3(0.20, 0.24, 0.34) * trail;
                color += float3(0.72, 0.80, 0.96) * leading;
                float alpha = max(mask * 0.985, trail * 0.30);
                return half4(half3(color), half(alpha));
            }
        """
    }
}
