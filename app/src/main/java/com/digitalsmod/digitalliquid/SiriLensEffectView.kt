package com.digitalsmod.digitalliquid

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
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
 * Thấu kính quang học Siri Liquid Glass chuẩn 100% video Apple Intelligence:
 * - Có thể tích khối cầu 3D thủy tinh khói (Smoked Liquid Glass) đặc quánh, không bị trong suốt rỗng ruột.
 * - 2 vệt phản quang cong Specular Lobes ở 2 bên đáy tạo khối cầu tròn bóng lộn như video iOS.
 * - Khi mở sang Thẻ Card, thân kính chuyển thành kính mờ đen siêu sang trọng, dải sáng trượt xuống mép đáy.
 * - Dải sóng cầu vồng laser sắc nét, chuyển động sóng âm hữu cơ.
 */
class SiriLensEffectView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private fun dp(value: Float) = value * density

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clipPath = Path()
    private val viewBounds = RectF()
    private var shader: RuntimeShader? = null
    private var bgShader: BitmapShader? = null
    private var bgBitmap: Bitmap? = null
    private val dummyShader = LinearGradient(0f, 0f, 1f, 1f, Color.TRANSPARENT, Color.TRANSPARENT, Shader.TileMode.CLAMP)

    private var phase = 0f
    private var morphProgress = 1f
    private var cardExpandProgress = 0f
    private var energy = 1f
    private var listening = true
    private var animator: ValueAnimator? = null
    private var intensityAnimator: ValueAnimator? = null
    private var cardModeAnimator: ValueAnimator? = null

    private var globalScreenX = 0f
    private var globalScreenY = 0f
    private var screenWidth = resources.displayMetrics.widthPixels.toFloat()
    private var screenHeight = resources.displayMetrics.heightPixels.toFloat()

    // Fallback cho Android < 13
    private val fallbackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val flarePath = Path()

    private val agsl = """
        uniform shader backgroundTexture;
        uniform float hasBackground;
        uniform float2 resolution;
        uniform float2 screenResolution;
        uniform float2 windowOffset;
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
            float2 screenCoord = fragCoord + windowOffset;

            // Hệ tọa độ chuẩn hóa thấu kính [-1, 1]
            float2 p = (uv - float2(0.5, 0.5)) * 2.0;
            float r2 = clamp(dot(p, p), 0.0, 1.0);
            float r = sqrt(r2);
            float z = sqrt(max(0.0, 1.0 - r2));

            // 1. VỊ TRÍ RANH GIỚI & DẢI SÓNG CẦU VỒNG (Optical Horizon)
            // Quả cầu giọt nước: nằm ở y = 0.53 | Thẻ mở rộng: trượt xuống đáy y = 0.84 như video mẫu
            float baseHorizon = mix(0.53, 0.84, cardMode);
            float taper = sin(clamp(uv.x, 0.0, 1.0) * 3.14159265);
            float taperSharp = pow(max(0.0, taper), 1.35);

            // Chuyển động sóng chất lỏng Apple nhấp nhô hữu cơ
            float waveFluid = -taper * mix(0.032, 0.010, cardMode)
                            + 0.006 * sin(uv.x * 6.28 - time * 2.2)
                            + 0.003 * sin(uv.x * 14.0 + time * 1.5) * energy;
            float horizonY = baseHorizon + waveFluid;

            float dy = uv.y - horizonY;
            float absDy = abs(dy);

            // Dải tia laser cầu vồng siêu mỏng và sắc nét
            float coreLine = exp(-absDy * mix(150.0, 180.0, cardMode)) * taperSharp;
            float midFlare = exp(-absDy * 34.0) * taperSharp;
            float softBloom = exp(-absDy * 9.5) * pow(max(0.0, taper), 0.7);

            float colorShift = clamp((uv.x - 0.06) / 0.88 + 0.03 * sin(time * 1.2), 0.0, 1.0);
            float3 rainbow = appleSpectrum(colorShift);

            // Điểm hội tụ kim cương trắng rực ở giữa-trái
            float centerHotspot = exp(-pow((uv.x - 0.42) / mix(0.20, 0.32, cardMode), 2.0)) * coreLine * 1.85;
            float3 flareLight = rainbow * (midFlare * 1.1 + softBloom * 0.45) * energy
                              + mix(rainbow, float3(1.0, 1.0, 1.0), 0.75) * coreLine * 1.35
                              + float3(1.0, 0.99, 0.96) * centerHotspot;

            // 2. PHẦN ĐEN PHÍA TRÊN (Hòa làm một khối với Dynamic Island)
            float blackMask = 1.0 - smoothstep(horizonY - 0.06, horizonY + 0.012, uv.y);
            float3 topColor = float3(0.0, 0.0, 0.0) + rainbow * (softBloom * 0.06 * blackMask);

            // 3. THỂ TÍCH KHỐI CẦU PHA LÊ KHÓI (Smoked 3D Glass Volume)
            // Không để trong suốt rỗng ruột: có màu nền kính đen khói sang trọng
            float glassDepth = mix(0.68, 0.88, cardMode);
            float3 glassBody = mix(float3(0.015, 0.02, 0.032), float3(0.035, 0.045, 0.065), (1.0 - z) * 0.5);

            // KHÚC XẠ NỀN THẬT: bẻ cong và tán sắc RGB nội dung launcher phía dưới
            float glassRegion = smoothstep(horizonY - 0.04, horizonY + 0.08, uv.y);
            float edgeDistort = pow(r, 1.5);
            float refractPower = glassRegion * (0.16 * (1.0 - z) + 0.12 * edgeDistort) * (1.0 - cardMode * 0.5);

            float2 lensDir = float2(p.x * 1.15, (p.y - 0.2) * 1.25);
            float2 pixelOffset = lensDir * refractPower * res;

            float ca = 0.075 * edgeDistort * glassRegion;
            float2 sampleR = clamp(screenCoord - pixelOffset * (1.0 + ca), float2(0.0), screenResolution);
            float2 sampleG = clamp(screenCoord - pixelOffset, float2(0.0), screenResolution);
            float2 sampleB = clamp(screenCoord - pixelOffset * (1.0 - ca), float2(0.0), screenResolution);

            half4 texR = backgroundTexture.eval(sampleR);
            half4 texG = backgroundTexture.eval(sampleG);
            half4 texB = backgroundTexture.eval(sampleB);
            float3 refractedScene = float3(texR.r, texG.g, texB.b);

            // 4. HAI VỆT PHẢN QUANG ĐÁY ĐẶC TRƯNG CỦA APPLE (Specular Lobe Reflections - Giây 00:01 - 00:03)
            // Đây chính là 2 vệt sáng trắng cong ở 2 bên đáy tạo nên khối cầu tròn lẳn như pha lê
            float lobeLeft = exp(-pow(length(p - float2(-0.55, 0.42)) - 0.46, 2.0) * 18.0);
            float lobeRight = exp(-pow(length(p - float2(0.55, 0.42)) - 0.46, 2.0) * 18.0);
            float dualLobes = (lobeLeft + lobeRight) * smoothstep(0.48, 0.88, uv.y) * (1.0 - cardMode * 0.85);
            float3 specularHighlights = float3(0.92, 0.96, 1.0) * dualLobes * 0.65;

            // Viền khúc xạ Fresnel siêu mảnh bao quanh thấu kính
            float fresnel = exp(-pow((r - 0.975) * 44.0, 2.0)) * smoothstep(0.20, 0.95, uv.y);
            float3 rimLight = float3(0.94, 0.97, 1.0) * fresnel * 0.85;

            // 5. TỔNG HỢP VẬT LIỆU
            float3 bottomGlassColor = mix(glassBody, refractedScene, step(0.5, hasBackground) * (1.0 - cardMode * 0.65))
                                    + specularHighlights;

            float3 finalRGB = mix(bottomGlassColor, topColor, blackMask) + flareLight + rimLight;

            // Độ đục tổng thể: đảm bảo khối kính luôn có thể tích thực sự, KHÔNG rỗng ruột
            float bodyAlpha = mix(glassDepth, 1.0, blackMask);
            float finalAlpha = clamp(max(bodyAlpha, max(softBloom * 0.9, fresnel * 0.95)) * morph, 0.0, 1.0);

            return half4(half3(clamp(finalRGB, 0.0, 1.0) * finalAlpha), half(finalAlpha));
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

    fun setBackgroundSource(bitmap: Bitmap?, screenX: Float, screenY: Float, sWidth: Float, sHeight: Float) {
        val old = bgBitmap
        bgBitmap = bitmap
        globalScreenX = screenX
        globalScreenY = screenY
        screenWidth = sWidth
        screenHeight = sHeight
        bgShader = if (bitmap != null && !bitmap.isRecycled) {
            BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        } else null
        if (old != null && old !== bitmap) {
            old.recycle()
        }
        invalidate()
    }

    fun updateWindowPosition(screenX: Float, screenY: Float) {
        globalScreenX = screenX
        globalScreenY = screenY
        invalidate()
    }

    fun releaseBackground() {
        bgBitmap?.recycle()
        bgBitmap = null
        bgShader = null
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
        releaseBackground()
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
            val currentBg = bgShader
            if (currentBg != null) {
                activeShader.setInputShader("backgroundTexture", currentBg)
                activeShader.setFloatUniform("hasBackground", 1f)
            } else {
                activeShader.setInputShader("backgroundTexture", dummyShader)
                activeShader.setFloatUniform("hasBackground", 0f)
            }
            activeShader.setFloatUniform("resolution", w, h)
            activeShader.setFloatUniform("screenResolution", screenWidth, screenHeight)
            activeShader.setFloatUniform("windowOffset", globalScreenX, globalScreenY)
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
        fallbackPaint.shader = LinearGradient(
            0f, 0f, 0f, h * 0.50f,
            intArrayOf(0xFF03040B.toInt(), 0xEE020306.toInt(), 0x00000000),
            floatArrayOf(0f, 0.75f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h * 0.50f, fallbackPaint)

        val horizonY = h * 0.53f
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
