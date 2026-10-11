package com.digitalsmod.digitalliquid

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
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
                shader.draw(canvas, bodyPaint, w, h, motionBlurFactor, motionDirection, tint, expanded, phase)
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
        bodyRect.set(0f, 0f, w, h)
        bodyPaint.shader = null
        bodyPaint.color = tint
        bodyPaint.alpha = 255
        canvas.drawRoundRect(bodyRect, radius, radius, bodyPaint)
        if (motionBlurFactor > 0.01f) {
            val travel = dp(8f) * motionBlurFactor * motionDirection
            trailPaint.color = 0xFF8C9AB3.toInt()
            trailPaint.alpha = (motionBlurFactor * 13f).toInt().coerceIn(0, 13)
            trailRect.set(-travel * 0.45f, dp(1.5f), w - travel * 0.45f, h - dp(1.5f))
            canvas.drawRoundRect(trailRect, radius, radius, trailPaint)
            trailPaint.alpha = (motionBlurFactor * 7f).toInt().coerceIn(0, 7)
            trailRect.set(-travel, dp(2.5f), w - travel, h - dp(2.5f))
            canvas.drawRoundRect(trailRect, radius, radius, trailPaint)
        }
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
            phase: Float
        ) {
            runtimeShader.setFloatUniform("uSize", width, height)
            runtimeShader.setFloatUniform("uMotion", motion)
            runtimeShader.setFloatUniform("uDirection", direction)
            runtimeShader.setFloatUniform("uExpanded", if (expanded) 1f else 0f)
            runtimeShader.setFloatUniform("uPhase", phase)
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
            uniform float4 uTint;

            float roundedBox(float2 p, float2 b, float r) {
                float2 q = abs(p) - b + float2(r, r);
                return length(max(q, float2(0.0, 0.0))) + min(max(q.x, q.y), 0.0) - r;
            }

            half4 main(float2 p) {
                float2 size = max(uSize, float2(1.0, 1.0));
                float2 halfSize = size * 0.5 - float2(0.6, 0.6);
                float radius = min(size.y * (0.5 - uExpanded * 0.25), size.x * 0.5);
                float2 centered = p - size * 0.5;
                float d = roundedBox(centered, halfSize, radius);
                float mask = 1.0 - smoothstep(-0.65, 0.65, d);

                // Two shifted masks approximate a directional temporal blur. The smear is
                // intentionally faint, only active while moving, so the idle pill stays clean.
                float motion = clamp(uMotion, 0.0, 1.0);
                float displacement = uDirection * motion * 8.0;
                float d1 = roundedBox(centered - float2(-displacement, 0.0), halfSize, radius);
                float d2 = roundedBox(centered - float2(-displacement * 0.52, 0.0), halfSize, radius);
                float m1 = 1.0 - smoothstep(-1.8, 1.8, d1);
                float m2 = 1.0 - smoothstep(-1.2, 1.2, d2);
                float trail = max(max(m1 - mask, 0.0) * 0.14, max(m2 - mask, 0.0) * 0.09) * motion;

                float edge = 1.0 - smoothstep(0.0, 2.4, abs(d));
                float leadingX = uDirection > 0.0 ? size.x * 0.90 : size.x * 0.10;
                float leading = exp(-pow((p.x - leadingX) / 5.0, 2.0)) * motion * 0.045;
                float3 color = uTint.rgb;
                color += float3(0.18, 0.22, 0.31) * trail;
                color += float3(0.12, 0.15, 0.20) * leading;
                color += float3(0.10, 0.12, 0.16) * edge * motion * 0.10;
                float alpha = max(mask, trail * 0.18);
                return half4(half3(color), half(alpha));
            }
        """
    }
}
