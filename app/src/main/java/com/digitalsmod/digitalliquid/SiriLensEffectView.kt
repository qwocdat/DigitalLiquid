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
import android.graphics.Shader
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.min
import kotlin.math.sin

/**
 * Siri-inspired optical lens for DigitalLiquid. It is deliberately a visual layer first:
 * the camera pill stays black while a moving glass lens and spectral flare animate around it.
 */
class SiriLensEffectView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private fun dp(value: Float) = value * density

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val flarePath = Path()
    private val pillBounds = RectF()
    private var centerX = 0f
    private var centerY = 0f
    private var radius = 0f
    private var ambientShader: Shader? = null
    private var sphereShader: Shader? = null
    private var lowerGlassShader: Shader? = null
    private var spectrumShader: Shader? = null
    private var phase = 0f
    private var energy = 1f
    private var listening = true
    private var intensityAnimator: ValueAnimator? = null
    private var animator: ValueAnimator? = null

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
        contentDescription = "Hiệu ứng kính Siri nhiều màu của DigitalLiquid"
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

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        centerX = w * 0.5f
        centerY = h * 0.455f
        radius = min(min(w * 0.285f, dp(78f)), h * 0.42f)
        if (w <= 0 || h <= 0 || radius <= 0f) return
        ambientShader = RadialGradient(
            centerX, centerY, radius * 1.9f,
            intArrayOf(0x5B8DAFFF, 0x344B72CE, 0x182A3A65, 0x00060910),
            floatArrayOf(0f, 0.45f, 0.72f, 1f), Shader.TileMode.CLAMP
        )
        sphereShader = RadialGradient(
            centerX - radius * 0.34f, centerY - radius * 0.50f, radius * 1.72f,
            intArrayOf(0x83F4FAFF.toInt(), 0x4A9FB9D9, 0x394D6382, 0x2A303A4D, 0x1839445E, 0x0A1B263B),
            floatArrayOf(0f, 0.22f, 0.47f, 0.67f, 0.86f, 1f), Shader.TileMode.CLAMP
        )
        lowerGlassShader = LinearGradient(
            0f, centerY - radius * 0.04f, 0f, centerY + radius,
            intArrayOf(0x00D8F1FF, 0x238EB9F0, 0x455B7197, 0x223B435C),
            floatArrayOf(0f, 0.30f, 0.73f, 1f), Shader.TileMode.CLAMP
        )
        val spectrum = intArrayOf(
            0xFF62B7FF.toInt(), 0xFF8D86FF.toInt(), 0xFFE7A8FF.toInt(),
            0xFFFFDFA1.toInt(), 0xFFFFFFFF.toInt(), 0xFF8DEFFF.toInt(), 0xFF72A4FF.toInt()
        )
        spectrumShader = LinearGradient(
            centerX - radius * 0.93f, 0f, centerX + radius * 0.93f, 0f,
            spectrum, null, Shader.TileMode.CLAMP
        )
        val pillWidth = dp(138f)
        val pillHeight = dp(39f)
        val pillTop = dp(6f)
        pillBounds.set(centerX - pillWidth / 2f, pillTop, centerX + pillWidth / 2f, pillTop + pillHeight)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (animator == null) {
            animator = ValueAnimator.ofFloat(0f, (Math.PI * 2.0).toFloat()).apply {
                duration = 4200L
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
        if (width <= 0 || height <= 0) return

        val cx = centerX
        val cy = centerY
        val radius = this.radius
        if (radius <= 0f) return

        // Cached shaders keep the animated lens inexpensive even on high-refresh displays.
        glowPaint.shader = ambientShader
        canvas.drawCircle(cx, cy, radius * 1.9f, glowPaint)
        paint.style = Paint.Style.FILL
        paint.shader = sphereShader
        canvas.drawCircle(cx, cy, radius, paint)
        paint.shader = lowerGlassShader
        canvas.drawCircle(cx, cy, radius * 0.985f, paint)
        paint.shader = null

        // Fine optical rim and a softer inner reflection.
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1.1f)
        paint.color = 0x83D9ECFF.toInt()
        canvas.drawCircle(cx, cy, radius - dp(0.7f), paint)
        paint.strokeWidth = dp(0.75f)
        paint.color = 0x30FFFFFF
        canvas.drawArc(
            cx - radius * 0.88f, cy - radius * 0.90f,
            cx + radius * 0.88f, cy + radius * 0.90f,
            198f, 142f, false, paint
        )
        paint.style = Paint.Style.FILL

        // Travelling rainbow horizon with a soft bloom and a thin white-hot centre.
        flarePath.reset()
        val lineY = cy + dp(2f)
        val left = cx - radius * 0.93f
        val right = cx + radius * 0.93f
        flarePath.moveTo(left, lineY + dp(4f))
        for (step in 1..80) {
            val u = step / 80f
            val x = left + (right - left) * u
            val wave = sin(u * 6.2f + phase * 0.9f) * dp(3.6f) + sin(u * 14f - phase * 0.55f) * dp(1.2f)
            flarePath.lineTo(x, lineY + wave)
        }
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        paint.shader = spectrumShader
        paint.alpha = (105 * energy).toInt().coerceIn(0, 255)
        paint.strokeWidth = dp(9f)
        paint.setShadowLayer(dp(11f), 0f, 0f, 0xFF89C9FF.toInt())
        canvas.drawPath(flarePath, paint)
        paint.clearShadowLayer()
        paint.alpha = (240 * energy).toInt().coerceIn(0, 255)
        paint.strokeWidth = dp(1.55f)
        canvas.drawPath(flarePath, paint)
        paint.shader = null
        paint.alpha = 255
        paint.style = Paint.Style.FILL

        // Tiny specular streaks make the lens read as a material, not a flat gradient.
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1f)
        paint.color = 0x59FFFFFF
        canvas.drawArc(
            cx - radius * 0.68f, cy - radius * 0.75f,
            cx + radius * 0.60f, cy + radius * 0.42f,
            207f, 68f, false, paint
        )
        paint.style = Paint.Style.FILL

        // The physical island remains black and crisp above the luminous glass effect.
        val pillWidth = dp(138f)
        val pillHeight = dp(39f)
        paint.setShadowLayer(dp(8f), 0f, dp(3f), 0xAA000000.toInt())
        paint.color = 0xFF010103.toInt()
        canvas.drawRoundRect(pillBounds, pillHeight / 2f, pillHeight / 2f, paint)
        paint.clearShadowLayer()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(0.7f)
        paint.color = 0x24FFFFFF
        canvas.drawRoundRect(pillBounds, pillHeight / 2f, pillHeight / 2f, paint)
        paint.style = Paint.Style.FILL
        paint.color = 0xFFFFD77A.toInt()
        canvas.drawCircle(cx + pillWidth * 0.30f, pillBounds.centerY(), dp(1.7f), paint)
    }
}
