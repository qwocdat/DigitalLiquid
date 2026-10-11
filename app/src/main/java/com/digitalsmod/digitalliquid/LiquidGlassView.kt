package com.digitalsmod.digitalliquid

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.LinearGradient
import android.animation.ValueAnimator
import android.view.animation.LinearInterpolator
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.View
import kotlin.math.min

/**
 * Black iOS-style Dynamic Island surface with per-pixel AGSL highlights on Android 13+
 * and a native gradient fallback on older Android versions. This view never blurs the
 * application behind the island; all highlights are drawn inside the pill itself.
 */
class LiquidGlassView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val rect = RectF()
    private val path = Path()
    private var time = 0f
    private var strength = 0.72f
    private var tintColor = Color.rgb(17, 20, 29)
    private var expanded = false
    private var shaderRenderer: Api33Shader? = null
    private var shaderFailed = false
    private var shimmerAnimator: ValueAnimator? = null

    init {
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                shaderRenderer = Api33Shader(AGSL)
            } catch (_: Throwable) {
                shaderFailed = true
            }
        }
    }

    fun configure(glassStrength: Int, tint: String, expandedNow: Boolean) {
        strength = (glassStrength.coerceIn(15, 100) / 100f)
        // The pill stays near-black like iOS. Presets only tint its subtle optical edge.
        tintColor = when (tint) {
            "violet" -> Color.rgb(7, 4, 13)
            "ice" -> Color.rgb(3, 8, 12)
            "graphite" -> Color.rgb(8, 8, 10)
            else -> Color.rgb(2, 3, 5)
        }
        expanded = expandedNow
        refreshMotionPreference()
        invalidate()
    }

    fun setPhase(value: Float) {
        time = value
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        refreshMotionPreference()
    }

    private fun refreshMotionPreference() {
        val shouldAnimate = isAttachedToWindow && !AppPrefs.get(context).getBoolean(AppPrefs.REDUCE_MOTION, false)
        if (shouldAnimate && shimmerAnimator == null) {
            shimmerAnimator = ValueAnimator.ofFloat(0f, 12f).apply {
                duration = 16_000L
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener {
                    time = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        } else if (!shouldAnimate) {
            shimmerAnimator?.cancel()
            shimmerAnimator = null
        }
    }

    override fun onDetachedFromWindow() {
        shimmerAnimator?.cancel()
        shimmerAnimator = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        val w = width.toFloat()
        val h = height.toFloat()
        val radius = if (expanded) min(h * 0.24f, dp(34f)) else min(h * 0.5f, w * 0.5f)
        rect.set(0.5f, 0.5f, w - 0.5f, h - 0.5f)
        path.reset()
        path.addRoundRect(rect, radius, radius, Path.Direction.CW)

        val renderedWithShader = if (Build.VERSION.SDK_INT >= 33 && !shaderFailed) {
            try {
                shaderRenderer?.draw(canvas, paint, w, h, time, strength, expanded, tintColor) == true
            } catch (_: Throwable) {
                shaderFailed = true
                false
            }
        } else false
        if (!renderedWithShader) {
            paint.shader = null
            drawFallback(canvas, radius)
        }

        // Thin, restrained optical rim. No thick dark inset border or plastic-looking stroke.
        strokePaint.shader = LinearGradient(
            0f, 0f, w, h,
            intArrayOf(0x22FFFFFF, 0x08E8F0FF, 0x14FFFFFF, 0x04FFFFFF),
            floatArrayOf(0f, 0.42f, 0.76f, 1f), Shader.TileMode.CLAMP
        )
        strokePaint.strokeWidth = dp(0.55f)
        canvas.drawRoundRect(rect, radius, radius, strokePaint)
        strokePaint.shader = null
    }

    private fun drawFallback(canvas: Canvas, radius: Float) {
        val w = width.toFloat()
        val h = height.toFloat()
        val base = tintColor
        val sweepX = (time / 12f % 1f) * w * 0.24f

        // Start with a fully opaque black surface. Highlights are composited over it,
        // never used as a translucent base that lets the app behind bleed through.
        paint.shader = null
        paint.color = base
        paint.alpha = 255
        canvas.drawRoundRect(rect, radius, radius, paint)

        paint.color = Color.WHITE
        paint.alpha = 255
        paint.shader = LinearGradient(
            sweepX, 0f, sweepX + w * 0.28f, h * 0.55f,
            intArrayOf(
                withAlpha(Color.rgb(238, 244, 255), (0.035f + strength * 0.075f) * 255f),
                0x06FFFFFF,
                0x00FFFFFF
            ),
            floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(rect, radius, radius, paint)
        paint.shader = null
        paint.alpha = 255

        val glint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, w, h * 0.55f,
                intArrayOf(0x14FFFFFF, 0x04FFFFFF, 0x00FFFFFF),
                floatArrayOf(0f, 0.48f, 1f), Shader.TileMode.CLAMP
            )
        }
        canvas.drawRoundRect(rect, radius, radius, glint)
    }

    private fun withAlpha(color: Int, alpha: Float): Int =
        (color and 0x00FFFFFF) or (alpha.toInt().coerceIn(0, 255) shl 24)

    private fun dp(value: Float) = value * resources.displayMetrics.density

    /** Keeps the API-33-only RuntimeShader class out of the main View's field/signature types. */
    @android.annotation.TargetApi(33)
    private class Api33Shader(source: String) {
        private val runtimeShader = android.graphics.RuntimeShader(source)

        fun draw(
            canvas: Canvas,
            paint: Paint,
            width: Float,
            height: Float,
            time: Float,
            strength: Float,
            expanded: Boolean,
            tintColor: Int
        ): Boolean {
            runtimeShader.setFloatUniform("uSize", width, height)
            runtimeShader.setFloatUniform("uTime", time)
            runtimeShader.setFloatUniform("uIntensity", strength)
            runtimeShader.setFloatUniform("uExpanded", if (expanded) 1f else 0f)
            runtimeShader.setFloatUniform(
                "uTint",
                Color.red(tintColor) / 255f,
                Color.green(tintColor) / 255f,
                Color.blue(tintColor) / 255f,
                1f
            )
            paint.shader = runtimeShader
            canvas.drawRect(0f, 0f, width, height, paint)
            return true
        }
    }

    companion object {
        private const val AGSL = """
            uniform float2 uSize;
            uniform float uTime;
            uniform float uIntensity;
            uniform float uExpanded;
            uniform float4 uTint;

            float roundedBox(float2 p, float2 b, float r) {
                float2 q = abs(p) - b + float2(r, r);
                return length(max(q, float2(0.0, 0.0))) + min(max(q.x, q.y), 0.0) - r;
            }

            half4 main(float2 p) {
                float2 size = max(uSize, float2(1.0, 1.0));
                float2 uv = p / size;
                float2 center = p - size * 0.5;
                float corner = min(size.y * (0.50 - uExpanded * 0.25), size.x * 0.5);
                float d = roundedBox(center, size * 0.5 - float2(0.8, 0.8), corner);
                float mask = 1.0 - smoothstep(-1.1, 1.1, d);
                float rim = 1.0 - smoothstep(0.0, 3.6, abs(d));
                float topSweep = exp(-pow((uv.y - (0.14 + 0.035 * sin(uTime * 2.1))) * 15.0, 2.0));
                float lowerGlow = exp(-pow((uv.y - 0.91) * 18.0, 2.0));
                float travelling = exp(-pow((uv.x - fract(uTime * 0.095)) * 25.0, 2.0));
                float specular = (topSweep * 0.17 + lowerGlow * 0.045 + travelling * 0.06) * uIntensity;
                float3 tint = uTint.rgb;
                // Opaque black body; only a restrained moving specular highlight is bright.
                float3 color = tint * (0.82 + uv.y * 0.12);
                color += float3(0.19, 0.22, 0.29) * specular;
                color += float3(0.32, 0.36, 0.44) * rim * (0.07 + uIntensity * 0.13);
                color += float3(1.0, 1.0, 1.0) * topSweep * uIntensity * 0.026;
                // Opaque within the pill; only antialiased outside edges are transparent.
                return half4(half3(color), half(mask));
            }
        """
    }
}
