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
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val flarePath = Path()
    private val clipPath = Path()
    private val sphereRect = RectF()
    private var centerX = 0f
    private var centerY = 0f
    private var radius = 0f
    private var expandProgress = 1f

    private var topDarkShader: Shader? = null
    private var lowerGlassShader: Shader? = null
    private var refractionRimShader: Shader? = null
    private var leftCausticShader: Shader? = null
    private var rightCausticShader: Shader? = null
    private var spectrumCoreShader: Shader? = null
    private var spectrumGlowShader: Shader? = null
    private var bottomLipShader: Shader? = null

    private var phase = 0f
    private var energy = 1f
    private var listening = true
    private var intensityAnimator: ValueAnimator? = null
    private var animator: ValueAnimator? = null

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
        contentDescription = "Hiệu ứng kính Siri nhiều màu của DigitalLiquid"
    }

    fun setMorphProgress(progress: Float) {
        expandProgress = progress.coerceIn(0f, 1f)
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

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        centerX = w * 0.5f
        centerY = h * 0.5f
        radius = min(w, h) * 0.5f
        sphereRect.set(0f, 0f, w.toFloat(), h.toFloat())

        // Phần đỉnh quả cầu giữ màu đen sâu để hòa làm một với cụm Dynamic Island thật
        topDarkShader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            intArrayOf(
                0xFF000000.toInt(),
                0xFF000000.toInt(),
                0xEC040508.toInt(),
                0x58121722.toInt(),
                0x14242E42.toInt(),
                0x2840506B.toInt()
            ),
            floatArrayOf(0f, 0.34f, 0.44f, 0.54f, 0.76f, 1f),
            Shader.TileMode.CLAMP
        )

        // Lớp kính trong suốt nửa dưới quả cầu tạo độ sâu khúc xạ
        lowerGlassShader = RadialGradient(
            centerX, h * 0.62f, radius * 0.96f,
            intArrayOf(
                0x05FFFFFF,
                0x188CA8CC,
                0x364E6482,
                0x64192130
            ),
            floatArrayOf(0f, 0.52f, 0.82f, 1f),
            Shader.TileMode.CLAMP
        )

        // Viền khúc xạ ánh sáng trắng bạc bao quanh mép dưới quả cầu như trong ảnh
        refractionRimShader = LinearGradient(
            0f, h * 0.35f, 0f, h.toFloat(),
            intArrayOf(
                0x00FFFFFF,
                0x44A8C6EA,
                0xB8DCEBFF.toInt(),
                0xF2FFFFFF.toInt()
            ),
            floatArrayOf(0f, 0.35f, 0.78f, 1f),
            Shader.TileMode.CLAMP
        )

        leftCausticShader = RadialGradient(
            w * 0.16f, h * 0.74f, radius * 0.55f,
            intArrayOf(0x88FFFFFF.toInt(), 0x38C7E0FF, 0x00FFFFFF),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP
        )

        rightCausticShader = RadialGradient(
            w * 0.84f, h * 0.74f, radius * 0.55f,
            intArrayOf(0x88FFFFFF.toInt(), 0x38C7E0FF, 0x00FFFFFF),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP
        )

        // Dải quang phổ cầu vồng rực rỡ ở giữa quả cầu (Xanh lam -> Lục -> Vàng -> Cam -> Đỏ)
        val rainbowColors = intArrayOf(
            0x001E5BFF,
            0xFF2B68FF.toInt(),
            0xFF00D8FF.toInt(),
            0xFF5CFF95.toInt(),
            0xFFFFF36B.toInt(),
            0xFFFF8826.toInt(),
            0xFFFF2A3C.toInt(),
            0x00FF2A3C
        )
        val rainbowPositions = floatArrayOf(0f, 0.14f, 0.30f, 0.45f, 0.58f, 0.73f, 0.86f, 1f)

        spectrumCoreShader = LinearGradient(
            w * 0.08f, 0f, w * 0.92f, 0f,
            rainbowColors, rainbowPositions, Shader.TileMode.CLAMP
        )

        spectrumGlowShader = LinearGradient(
            w * 0.04f, 0f, w * 0.96f, 0f,
            rainbowColors, rainbowPositions, Shader.TileMode.CLAMP
        )

        bottomLipShader = LinearGradient(
            w * 0.18f, 0f, w * 0.82f, 0f,
            intArrayOf(0x00FFFFFF, 0xCCFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0xCCFFFFFF.toInt(), 0x00FFFFFF),
            floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f),
            Shader.TileMode.CLAMP
        )
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

        val cornerR = min(w, h) * 0.5f
        clipPath.reset()
        clipPath.addRoundRect(sphereRect, cornerR, cornerR, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clipPath)

        // 1. Nền chuyển mượt từ phần đen nguyên bản của Dynamic Island ở đỉnh xuống lớp kính trong suốt bên dưới
        paint.style = Paint.Style.FILL
        paint.shader = topDarkShader
        canvas.drawRect(sphereRect, paint)

        // 2. Độ sâu thủy tinh nửa dưới quả cầu
        paint.shader = lowerGlassShader
        canvas.drawRect(sphereRect, paint)

        // 3. Hiệu ứng khúc xạ ánh sáng trắng uốn cong ở 2 bên mép dưới (giống hệt phần chữ bị bẻ cong ở viền kính trong ảnh)
        paint.shader = leftCausticShader
        canvas.drawCircle(w * 0.18f, h * 0.74f, radius * 0.52f, paint)
        paint.shader = rightCausticShader
        canvas.drawCircle(w * 0.82f, h * 0.74f, radius * 0.52f, paint)
        paint.shader = null

        // Vệt phản quang cong đặc trưng bên trái và bên phải lòng kính
        rimPaint.strokeCap = Paint.Cap.ROUND
        rimPaint.strokeWidth = dp(2.2f)
        rimPaint.color = 0x78DCEBFF
        val innerArcRect = RectF(w * 0.07f, h * 0.20f, w * 0.93f, h * 0.92f)
        canvas.drawArc(innerArcRect, 118f, 36f, false, rimPaint)
        canvas.drawArc(innerArcRect, 26f, 36f, false, rimPaint)

        // 4. Dải quang phổ cầu vồng uốn cong nhẹ nằm ngay đường chân trời giữa phần đen và phần kính
        val horizonY = h * 0.465f
        val leftX = w * 0.08f
        val rightX = w * 0.92f
        val span = rightX - leftX
        val archHeight = dp(7.5f)

        flarePath.reset()
        for (step in 0..80) {
            val u = step / 80f
            val x = leftX + span * u
            // Đường cong vòm nhẹ lên ở giữa giống hệt ảnh mẫu + chuyển động thở mượt
            val arch = -sin(u * Math.PI).toFloat() * archHeight
            val subtleWave = sin(u * 5.5f + phase) * dp(0.9f)
            val y = horizonY + arch + subtleWave
            if (step == 0) flarePath.moveTo(x, y) else flarePath.lineTo(x, y)
        }

        // Lớp hào quang cầu vồng tán sắc rộng
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        paint.shader = spectrumGlowShader
        paint.alpha = (175 * energy * expandProgress).toInt().coerceIn(0, 255)
        paint.strokeWidth = dp(18f)
        paint.setShadowLayer(dp(14f), 0f, dp(2f), 0xAAFFFFFF.toInt())
        canvas.drawPath(flarePath, paint)
        paint.clearShadowLayer()

        // Lớp cầu vồng trung tâm sắc nét
        paint.shader = spectrumCoreShader
        paint.alpha = (255 * energy * expandProgress).toInt().coerceIn(0, 255)
        paint.strokeWidth = dp(6.5f)
        canvas.drawPath(flarePath, paint)

        // Lõi sáng trắng rực ở tâm dải cầu vồng (bên trái-giữa như trong ảnh)
        val hotSpotPath = Path()
        val hotLeft = w * 0.16f
        val hotRight = w * 0.68f
        val hotSpan = hotRight - hotLeft
        for (step in 0..50) {
            val u = step / 50f
            val globalU = ((hotLeft + hotSpan * u) - leftX) / span
            val x = hotLeft + hotSpan * u
            val arch = -sin(globalU * Math.PI).toFloat() * archHeight
            val subtleWave = sin(globalU * 5.5f + phase) * dp(0.9f)
            val y = horizonY + arch + subtleWave + dp(0.5f)
            if (step == 0) hotSpotPath.moveTo(x, y) else hotSpotPath.lineTo(x, y)
        }
        paint.shader = LinearGradient(
            hotLeft, 0f, hotRight, 0f,
            intArrayOf(0x00FFFFFF, 0xEEFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0x00FFFFFF),
            floatArrayOf(0f, 0.35f, 0.65f, 1f),
            Shader.TileMode.CLAMP
        )
        paint.strokeWidth = dp(2.6f)
        canvas.drawPath(hotSpotPath, paint)
        paint.shader = null

        // 5. Viền thủy tinh sắc nét bao quanh toàn bộ khối cầu Dynamic Island
        rimPaint.shader = refractionRimShader
        rimPaint.strokeWidth = dp(1.5f)
        val inset = dp(0.8f)
        canvas.drawRoundRect(
            inset, inset, w - inset, h - inset,
            cornerR - inset, cornerR - inset, rimPaint
        )
        rimPaint.shader = null

        // Ánh sáng phản chiếu đậm ở đáy dưới cùng của quả cầu kính
        rimPaint.shader = bottomLipShader
        rimPaint.strokeWidth = dp(2.4f)
        val bottomOval = RectF(w * 0.04f, h * 0.04f, w * 0.96f, h - dp(1.2f))
        canvas.drawArc(bottomOval, 48f, 84f, false, rimPaint)
        rimPaint.shader = null

        canvas.restore()
    }
}
