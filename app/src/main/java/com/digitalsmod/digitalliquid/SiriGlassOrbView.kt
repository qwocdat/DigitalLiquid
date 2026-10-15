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
import android.graphics.SweepGradient
import android.graphics.BlurMaskFilter
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.min
import kotlin.math.sin

/**
 * Animated, Siri-inspired refractive glass orb. It is deliberately self-contained so the
 * visual effect works before any assistant provider, microphone, or AI endpoint is configured.
 */
class SiriGlassOrbView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private fun dp(value: Float) = value * density

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.2f)
    }
    private val band = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val edge = RectF()
    private val inner = RectF()
    private val path = Path()
    private var phase = 0f
    private var animator: ValueAnimator? = null

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
        isClickable = false
        contentDescription = "Hiệu ứng quả cầu Siri bằng kính khúc xạ"
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (AppPrefs.get(context).getBoolean(AppPrefs.REDUCE_MOTION, false)) return
        if (animator != null) return
        animator = ValueAnimator.ofFloat(0f, (Math.PI * 2.0).toFloat()).apply {
            duration = 5200L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                phase = it.animatedValue as Float
                invalidate()
            }
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
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val cx = w * 0.5f
        val cy = h * 0.51f + dp(2f)
        val rx = min(w * 0.425f, h * 0.60f)
        val ry = min(h * 0.445f, rx * 0.88f)
        edge.set(cx - rx, cy - ry, cx + rx, cy + ry)
        inner.set(cx - rx * 0.94f, cy - ry * 0.94f, cx + rx * 0.94f, cy + ry * 0.94f)

        // Wide ambient bloom, followed by the curved tinted body of the glass lens.
        glow.shader = RadialGradient(
            cx, cy + ry * 0.28f, rx * 1.44f,
            intArrayOf(0x42F4C47B, 0x2392A9D2, 0x006B7192),
            floatArrayOf(0f, 0.56f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawOval(edge, glow)
        glow.shader = null

        fill.shader = RadialGradient(
            cx - rx * 0.26f, cy - ry * 0.54f, rx * 1.72f,
            intArrayOf(0xB7F5F1E9.toInt(), 0x726B7683, 0x48313843, 0xB90D1018.toInt()),
            floatArrayOf(0f, 0.30f, 0.70f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawOval(edge, fill)
        fill.shader = null

        // A warm/cool caustic underneath gives the orb a physical, refractive edge.
        fill.shader = RadialGradient(
            cx + rx * 0.03f, cy + ry * 0.72f, rx * 1.02f,
            intArrayOf(0x70FFD69A, 0x45C18A63, 0x0B7699D7, 0x006B7192),
            floatArrayOf(0f, 0.30f, 0.69f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawOval(edge, fill)
        fill.shader = null

        val save = canvas.save()
        path.reset()
        path.addOval(edge, Path.Direction.CW)
        canvas.clipPath(path)

        // Rainbow horizon: broad soft bloom, tight spectral core and a thin white refraction.
        val rainbow = intArrayOf(
            0x0066BFFF, 0x9D60C8FF.toInt(), 0xE36FE6FF.toInt(), 0xDDA6FFD0.toInt(),
            0xE9FFF0B7.toInt(), 0xF6FFCD80.toInt(), 0xDFFF8D70.toInt(), 0xB8F578C0.toInt(), 0x00666DFF
        )
        val bandY = cy + ry * (0.08f + 0.016f * sin(phase))
        band.shader = LinearGradient(cx - rx, bandY, cx + rx, bandY, rainbow, null, Shader.TileMode.CLAMP)
        band.alpha = 88
        band.maskFilter = try { BlurMaskFilter(dp(9f), BlurMaskFilter.Blur.NORMAL) } catch (_: Throwable) { null }
        canvas.drawRoundRect(cx - rx * 1.1f, bandY - dp(6f), cx + rx * 1.1f, bandY + dp(6f), dp(8f), dp(8f), band)
        band.maskFilter = null
        band.alpha = 245
        canvas.drawRoundRect(cx - rx * 0.96f, bandY - dp(1.2f), cx + rx * 0.96f, bandY + dp(1.2f), dp(2f), dp(2f), band)
        band.alpha = 140
        band.shader = LinearGradient(cx, bandY - dp(12f), cx, bandY + dp(7f), 0x00FFFFFF, 0x9DFFFFFF.toInt(), Shader.TileMode.CLAMP)
        canvas.drawRect(cx - rx * 0.78f, bandY - dp(1.1f), cx + rx * 0.78f, bandY + dp(0.8f), band)
        band.shader = null
        band.alpha = 255

        // Fine moving light strands suggest the background being bent through the sphere.
        for (layer in 0..2) {
            path.reset()
            val strandY = bandY + dp(5f + layer * 4f)
            path.moveTo(cx - rx * 0.97f, strandY)
            for (i in 0..64) {
                val u = i / 64f
                val x = cx - rx * 0.97f + rx * 1.94f * u
                val y = strandY + dp((2.4f + layer * 0.7f) * sin(u * 8.6f + phase * (0.62f + layer * 0.12f)))
                path.lineTo(x, y)
            }
            line.shader = LinearGradient(cx - rx, strandY, cx + rx, strandY,
                intArrayOf(0x00D9ECFF, 0x7CE8F1FF, 0x35FFCB92, 0x00FFFFFF), null, Shader.TileMode.CLAMP)
            line.strokeWidth = dp(if (layer == 0) 0.9f else 0.55f)
            line.alpha = if (layer == 0) 165 else 78
            canvas.drawPath(path, line)
        }
        line.shader = null
        line.alpha = 255
        canvas.restoreToCount(save)

        // Fine iridescent rim, bright upper-left reflection and a darker lower lens edge.
        rim.shader = SweepGradient(cx, cy, intArrayOf(
            0xDFFFFFFF.toInt(), 0x6675BFFF, 0x18FFFFFF, 0x55FFD6A0, 0xB5FFFFFF.toInt(), 0xDFFFFFFF.toInt()
        ), null)
        canvas.drawOval(edge, rim)
        rim.shader = null
        rim.strokeWidth = dp(2.1f)
        rim.shader = LinearGradient(0f, cy - ry, 0f, cy + ry,
            intArrayOf(0xC8FFFFFF.toInt(), 0x167BBDEB, 0x11FFFFFF, 0x79FFD09A),
            floatArrayOf(0f, 0.36f, 0.65f, 1f), Shader.TileMode.CLAMP)
        canvas.drawArc(edge, 205f, 116f, false, rim)
        rim.strokeWidth = dp(1.2f)
        rim.shader = null

        // Camera capsule and lens reflection make it feel anchored to the phone's top cut-out.
        val capsuleW = rx * 0.70f
        val capsuleH = dp(24f)
        val capsule = RectF(cx - capsuleW * 0.76f, cy - ry * 0.70f, cx + capsuleW * 0.24f, cy - ry * 0.70f + capsuleH)
        fill.shader = LinearGradient(capsule.left, capsule.top, capsule.left, capsule.bottom,
            intArrayOf(0xFF030406.toInt(), 0xF60B0D12.toInt(), 0xFF000000.toInt()), null, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(capsule, capsuleH / 2f, capsuleH / 2f, fill)
        fill.shader = null
        rim.strokeWidth = dp(0.65f)
        rim.shader = LinearGradient(capsule.left, capsule.top, capsule.right, capsule.bottom,
            intArrayOf(0x557A8496, 0x117FFFFF, 0x226C7280), null, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(capsule, capsuleH / 2f, capsuleH / 2f, rim)
        rim.shader = null
        val lensR = dp(10f)
        val lensX = cx + rx * 0.43f
        val lensY = capsule.centerY()
        fill.shader = RadialGradient(lensX - lensR * 0.28f, lensY - lensR * 0.28f, lensR * 1.35f,
            intArrayOf(0xFF344767.toInt(), 0xFF111827.toInt(), 0xFF020305.toInt()), null, Shader.TileMode.CLAMP)
        canvas.drawCircle(lensX, lensY, lensR, fill)
        fill.shader = null
        rim.strokeWidth = dp(0.8f)
        rim.color = 0x55AFC4E9
        canvas.drawCircle(lensX, lensY, lensR, rim)
        rim.color = Color.WHITE
        rim.style = Paint.Style.FILL
        rim.alpha = 180
        canvas.drawCircle(lensX - lensR * 0.24f, lensY - lensR * 0.27f, dp(1.45f), rim)
        rim.style = Paint.Style.STROKE
        rim.alpha = 255

        // A tiny moving glint is the only strong motion; the glass stays elegant, not noisy.
        val glintX = cx - rx * 0.44f + ((phase / (Math.PI * 2.0).toFloat()) % 1f) * rx * 0.64f
        glow.shader = RadialGradient(glintX, cy - ry * 0.44f, dp(17f),
            intArrayOf(0x5BFFFFFF, 0x1BFFFFFF, 0x00FFFFFF), null, Shader.TileMode.CLAMP)
        canvas.drawCircle(glintX, cy - ry * 0.44f, dp(17f), glow)
        glow.shader = null
    }
}
