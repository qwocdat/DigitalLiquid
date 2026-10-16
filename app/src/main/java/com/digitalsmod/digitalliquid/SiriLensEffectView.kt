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
 * Thấu kính quang học Siri Liquid Glass chạy 100% bằng GPU AGSL RuntimeShader.
 * Đã loại bỏ hoàn toàn các hàm không tương thích khiến shader bị lỗi rơi về Canvas 2D.
 * Mô phỏng chính xác 2 giai đoạn trong video iOS:
 * 1. Quả cầu giọt nước (Orb Mode): Tia sáng cầu vồng sắc nét ở ranh giới, 2 thùy khúc xạ bẻ cong nền mạnh ở đáy.
 * 2. Thẻ mở rộng (Card Mode): Dải sáng trượt xuống đáy làm méo nội dung phía dưới theo thời gian thực.
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
    private var morphProgress = 0f
    private var cardExpandProgress = 0f
    private var energy = 1f
    private var animator: ValueAnimator? = null
    private var cardModeAnimator: ValueAnimator? = null

    private var globalScreenX = 0f
    private var globalScreenY = 0f
    private var screenWidth = resources.displayMetrics.widthPixels.toFloat()
    private var screenHeight = resources.displayMetrics.heightPixels.toFloat()

    // Chuẩn cú pháp AGSL 100% (không dùng atan2 hay toán tử 3 ngôi khác kiểu gây crash shader)
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
            float3 c0 = float3(0.10, 0.45, 1.00); // Deep Blue
            float3 c1 = float3(0.00, 0.88, 1.00); // Cyan
            float3 c2 = float3(0.25, 1.00, 0.55); // Mint Green
            float3 c3 = float3(1.00, 0.92, 0.25); // Warm Yellow
            float3 c4 = float3(1.00, 0.48, 0.12); // Orange
            float3 c5 = float3(1.00, 0.14, 0.28); // Magenta Red

            float x = clamp(t, 0.0, 1.0);
            float3 col = mix(c0, c1, smoothstep(0.00, 0.22, x));
            col = mix(col, c2, smoothstep(0.20, 0.42, x));
            col = mix(col, c3, smoothstep(0.40, 0.62, x));
            col = mix(col, c4, smoothstep(0.60, 0.82, x));
            col = mix(col, c5, smoothstep(0.80, 1.00, x));
            return col;
        }

        half4 main(float2 fragCoord) {
            float2 res = max(resolution, float2(1.0, 1.0));
            float2 uv = fragCoord / res;
            float2 screenCoord = fragCoord + windowOffset;

            // Hệ tọa độ chuẩn hóa [-1, 1] từ tâm
            float2 p = (uv - float2(0.5, 0.5)) * 2.0;
            float r2 = clamp(dot(p, p), 0.0, 1.0);
            float r = sqrt(r2);

            // Độ cao mặt cầu 3D z
            float z = sqrt(max(0.0, 1.0 - r2));

            // 1. VỊ TRÍ RANH GIỚI ĐEN & TIA QUANG HỌC (Optical Horizon)
            // Khi ở chế độ Orb (cardMode = 0): nằm ở y = 0.52
            // Khi ở chế độ Card (cardMode = 1): trượt xuống đáy y = 0.82 như video mẫu
            float baseHorizon = mix(0.52, 0.82, cardMode);

            // Độ vuốt nhọn 2 đầu (envelope = 0 ở sát mép, = 1 ở chính giữa)
            // Giúp tia sáng sắc lẻm như ánh sáng thấu kính thật, KHÔNG bị tròn đầu như con sâu
            float taper = sin(clamp(uv.x, 0.0, 1.0) * 3.14159265);
            float taperSharp = pow(max(0.0, taper), 1.35);

            // Chuyển động thở quang học mượt mà (không uốn éo kiểu rắn)
            float breath = 0.006 * sin(time * 2.2) + 0.004 * cos(uv.x * 4.5 - time * 1.6);
            float arch = -taper * mix(0.028, 0.010, cardMode);
            float horizonY = baseHorizon + arch + breath;

            float dy = uv.y - horizonY;
            float absDy = abs(dy);

            // Tia sáng gồm: lõi trắng kim cương siêu mảnh + tán xạ cầu vồng mỏng + quầng glow mềm
            float coreWidth = mix(160.0, 190.0, cardMode);
            float coreLine = exp(-absDy * coreWidth) * taperSharp;
            float midFlare = exp(-absDy * 38.0) * taperSharp;
            float softBloom = exp(-absDy * 10.5) * pow(max(0.0, taper), 0.8);

            // Dịch chuyển pha màu nhẹ nhàng dọc theo tia
            float colorShift = clamp((uv.x - 0.06) / 0.88 + 0.04 * sin(time * 1.2), 0.0, 1.0);
            float3 rainbow = appleSpectrum(colorShift);

            // Tâm sáng trắng rực ở giữa-trái giống hệt video iOS (giây 00:03)
            float centerHotspot = exp(-pow((uv.x - 0.45) / mix(0.22, 0.35, cardMode), 2.0)) * coreLine * 1.65;
            float3 flareLight = rainbow * (midFlare * 1.05 + softBloom * 0.42) * energy
                              + mix(rainbow, float3(1.0, 1.0, 1.0), 0.75) * coreLine * 1.25
                              + float3(1.0, 0.99, 0.96) * centerHotspot;

            // 2. VÙNG ĐEN PHÍA TRÊN (Hợp nhất hoàn toàn với Dynamic Island)
            float blackMask = 1.0 - smoothstep(horizonY - 0.08, horizonY + 0.015, uv.y);

            // 3. KHÚC XẠ THẤU KÍNH BẺ CONG MẠNH CÁC APP BÊN DƯỚI (Giống giây 00:01 - 00:04)
            float glassRegion = smoothstep(horizonY - 0.04, horizonY + 0.08, uv.y);

            // Độ cong thấu kính phóng đại và kéo giãn cực mạnh ở rìa dưới và 2 thùy trái/phải
            float edgeFactor = pow(r, 1.6);
            float bottomLobe = smoothstep(0.45, 0.98, uv.y) * (1.0 - 0.4 * cardMode);
            float refractPower = glassRegion * (0.18 * (1.0 - z) + 0.14 * bottomLobe * edgeFactor);

            // Vector dịch chuyển lấy mẫu thực tế trên màn hình
            float2 lensDir = float2(p.x * 1.25, (p.y - 0.15) * 1.35);
            float2 pixelOffset = lensDir * refractPower * res;

            // Tán sắc quang học RGB (Chromatic Aberration) tại viền khúc xạ
            float ca = 0.085 * edgeFactor * glassRegion;
            float2 sampleR = clamp(screenCoord - pixelOffset * (1.0 + ca), float2(0.0), screenResolution);
            float2 sampleG = clamp(screenCoord - pixelOffset, float2(0.0), screenResolution);
            float2 sampleB = clamp(screenCoord - pixelOffset * (1.0 - ca), float2(0.0), screenResolution);

            half4 texR = backgroundTexture.eval(sampleR);
            half4 texG = backgroundTexture.eval(sampleG);
            half4 texB = backgroundTexture.eval(sampleB);
            float3 refractedScene = float3(texR.r, texG.g, texB.b);

            // Tăng độ sâu tương phản thủy tinh (Specular Lobe Reflection như 2 vệt sáng đáy ở giây 00:02)
            float lobeReflect = exp(-pow((r - 0.84) * 16.0, 2.0)) * smoothstep(0.55, 0.92, uv.y) * abs(p.x) * (1.0 - cardMode * 0.7);
            float3 specularLobe = float3(0.92, 0.96, 1.0) * lobeReflect * 0.42;

            // Viền kính Fresnel siêu mảnh sắc nét ngoài cùng
            float fresnel = exp(-pow((r - 0.975) * 42.0, 2.0)) * smoothstep(0.25, 0.95, uv.y);
            float3 rimLight = float3(0.95, 0.98, 1.0) * fresnel * 0.85;

            // 4. KẾT HỢP VẬT LIỆU THỦY TINH & ĐEN
            float3 darkTopColor = float3(0.0, 0.0, 0.0) + rainbow * (softBloom * 0.06 * blackMask);
            float3 glassBottomColor = refractedScene * 0.96 + specularLobe;

            float3 baseColor = mix(glassBottomColor, darkTopColor, blackMask);
            if (hasBackground < 0.5) {
                baseColor = mix(float3(0.06, 0.08, 0.12), darkTopColor, blackMask);
            }

            float3 finalRGB = baseColor + flareLight + rimLight;

            // Khi có ảnh nền chụp, toàn bộ khối kính hiển thị đặc 100% ảnh đã bị bẻ cong
            float fallbackAlpha = max(blackMask, max(softBloom * 0.85, fresnel * 0.9));
            float outAlpha = mix(fallbackAlpha, 1.0, step(0.5, hasBackground)) * morph;

            return half4(half3(clamp(finalRGB, 0.0, 1.0) * outAlpha), half(outAlpha));
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

    /** Chuyển mượt từ Quả cầu giọt nước (0f) sang Thẻ Siri AI rộng (1f) như giây 00:05 trong video */
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
        // Bo tròn hoàn toàn thành khối giọt nước / viên nang mượt
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
        }

        canvas.restore()
    }
}
