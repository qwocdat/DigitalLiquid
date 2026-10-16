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
import android.view.animation.PathInterpolator
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Hiệu ứng kính quang học Siri 1:1 nguyên bản cho DigitalLiquid.
 * Sử dụng AGSL RuntimeShader trên GPU để tính toán bề mặt cầu thủy tinh 3D thật,
 * tán sắc quang phổ chân thực và đáy kính trong suốt quang học xuyên thấu nền máy.
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
    private var energy = 1f
    private var listening = true
    private var animator: ValueAnimator? = null
    private var intensityAnimator: ValueAnimator? = null

    // Fallback cho Android < 13
    private val fallbackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val flarePath = Path()

    private val agsl = """
        uniform float2 resolution;
        uniform float time;
        uniform float morph;
        uniform float energy;

        float3 siriSpectrum(float t) {
            float3 c0 = float3(0.04, 0.38, 1.00); // Blue
            float3 c1 = float3(0.00, 0.88, 0.98); // Cyan
            float3 c2 = float3(0.12, 0.98, 0.44); // Green
            float3 c3 = float3(1.00, 0.92, 0.16); // Yellow
            float3 c4 = float3(1.00, 0.46, 0.08); // Orange
            float3 c5 = float3(1.00, 0.10, 0.22); // Red

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

            // Chuyển về tọa độ hình cầu trung tâm [-1, 1]
            float2 p = (uv - float2(0.5, 0.5)) * 2.0;
            float distSq = dot(p, p);
            float dist = sqrt(max(0.0, distSq));

            // 1. Tính toán chiều sâu và vector pháp tuyến bề mặt cầu 3D (PBR Normal)
            float z = sqrt(max(0.0, 1.0 - distSq));
            float3 N = float3(p.x, p.y, z);

            // Phản xạ góc nghiêng Fresnel ở viền ngoài thấu kính (rực sáng siêu mỏng tinh tế, không viền vẽ thô)
            float fresnel = pow(clamp(1.0 - N.z, 0.0, 1.0), 3.6);
            float rimHighlight = fresnel * smoothstep(0.85, 0.985, dist);

            // 2. Nửa đỉnh quả cầu giữ màu đen sâu nguyên bản để hòa làm một với cụm camera / Dynamic Island
            float blackDome = 1.0 - smoothstep(0.36, 0.49, uv.y);

            // 3. Dải sóng quang phổ nằm chính xác ở trục ngang trung tâm (y = 0.50)
            float arch = -sin(uv.x * 3.14159) * 0.048;
            float wave = 0.008 * sin(uv.x * 6.28 - time * 1.8) + 0.004 * sin(uv.x * 12.56 + time * 1.1);
            float beamCenterY = 0.50 + arch + wave;
            float dy = abs(uv.y - beamCenterY);

            float beamCore = exp(-dy * 125.0);
            float beamBloom = exp(-dy * 26.0);
            float beamWide = exp(-dy * 7.5);

            float specPos = clamp((uv.x - 0.08) / 0.84, 0.0, 1.0);
            float3 spectrumColor = siriSpectrum(specPos);

            // Điểm hội tụ quang học trắng rực ở lệch tâm bên trái như trong ảnh mẫu
            float focalSpot = exp(-pow((uv.x - 0.38) / 0.16, 2.0)) * beamCore * 1.85;
            float3 hotWhite = float3(1.0, 0.99, 0.96) * focalSpot;

            // 4. Khúc xạ thủy tinh thật: Nửa dưới có độ trong suốt cao (alpha chỉ 0.05 - 0.18) để app đằng sau nhìn rõ
            float glassBodyAlpha = mix(0.05, 0.18, smoothstep(0.40, 0.98, dist)) * smoothstep(0.46, 0.58, uv.y);

            // Tụ quang đáy kính (Caustic Focus): ánh sáng uốn cong hội tụ bên trong đáy giọt thủy tinh
            float2 causticCenter = float2(0.50, 0.42);
            float causticR = length(float2((uv.x - causticCenter.x) * 1.15, uv.y - causticCenter.y));
            float internalCaustic = exp(-pow((causticR - 0.44) * 36.0, 2.0)) * smoothstep(0.50, 0.86, uv.y);
            float3 causticColor = float3(0.85, 0.94, 1.0) * internalCaustic * 0.85;

            // Phản xạ đáy kính ngoài cùng
            float bottomLip = exp(-pow((uv.y - 0.975) / 0.025, 2.0)) * exp(-pow((uv.x - 0.50) / 0.35, 2.0));

            // Tổng hợp màu sắc
            float3 color = float3(0.0);
            color += spectrumColor * (beamBloom * 0.90 + beamWide * 0.35) * energy;
            color += (spectrumColor * 0.45 + float3(0.80, 0.90, 1.0) * 0.55) * beamCore * 1.35;
            color += hotWhite;
            color += causticColor;
            color += float3(0.95, 0.98, 1.0) * rimHighlight * smoothstep(0.20, 0.85, uv.y) * 1.6;
            color += float3(1.0) * bottomLip * 1.2;

            // Alpha quang học: đỉnh đen tuyệt đối, đáy trong suốt cho nền và app xuyên qua
            float finalAlpha = max(blackDome, glassBodyAlpha);
            finalAlpha = max(finalAlpha, (beamBloom * 0.72 + beamCore * 0.98) * energy);
            finalAlpha = max(finalAlpha, internalCaustic * 0.80);
            finalAlpha = max(finalAlpha, rimHighlight * 0.90);
            finalAlpha = max(finalAlpha, bottomLip * 0.85);
            finalAlpha = clamp(finalAlpha * morph, 0.0, 1.0);

            return half4(half3(clamp(color, 0.0, 1.0) * finalAlpha), half(finalAlpha));
        }
    """.trimIndent()

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
        contentDescription = "Hiệu ứng kính Siri nhiều màu của DigitalLiquid"
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                shader = RuntimeShader(agsl)
            } catch (_: Exception) {
                shader = null
            }
        }
    }

    fun setMorphProgress(progress: Float) {
        morphProgress = progress.coerceIn(0f, 1f)
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
            0f, 0f, 0f, h * 0.48f,
            intArrayOf(0xFF000000.toInt(), 0xEE000000.toInt(), 0x00000000),
            floatArrayOf(0f, 0.75f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h * 0.48f, fallbackPaint)

        val horizonY = h * 0.50f
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

/**
 * Hiệu ứng dải viền màn hình phát sáng chạy từ trên đỉnh xuống ôm sát mép viền cong của điện thoại (Apple Intelligence Screen Edge Glow).
 */
class SiriEdgeGlowView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var shader: RuntimeShader? = null
    private var phase = 0f
    private var streamProgress = 0f
    private var enterAnimator: ValueAnimator? = null
    private var loopAnimator: ValueAnimator? = null

    private val agsl = """
        uniform float2 resolution;
        uniform float time;
        uniform float streamProgress;
        uniform float cornerRadius;

        float3 siriEdgeSpectrum(float t) {
            float3 c0 = float3(0.04, 0.45, 1.00); // Blue
            float3 c1 = float3(0.00, 0.92, 1.00); // Cyan
            float3 c2 = float3(0.68, 0.22, 1.00); // Magenta/Purple
            float3 c3 = float3(1.00, 0.38, 0.16); // Coral
            float3 c4 = float3(1.00, 0.88, 0.12); // Amber
            float x = fract(t);
            float3 col = mix(c0, c1, smoothstep(0.00, 0.25, x));
            col = mix(col, c2, smoothstep(0.25, 0.50, x));
            col = mix(col, c3, smoothstep(0.50, 0.75, x));
            col = mix(col, c4, smoothstep(0.75, 1.00, x));
            return col;
        }

        // Tính khoảng cách chính xác đến viền màn hình bo cong (SDF Rounded Rectangle)
        float sdRoundedBox(float2 p, float2 b, float r) {
            float2 q = abs(p) - b + r;
            return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - r;
        }

        half4 main(float2 fragCoord) {
            float2 res = max(resolution, float2(1.0, 1.0));
            float2 halfRes = res * 0.5;
            float2 p = fragCoord - halfRes;

            float R = max(1.0, cornerRadius);
            float2 b = halfRes - float2(R, R);

            // Khoảng cách từ pixel tới chu vi viền cong của điện thoại
            float distToEdge = abs(sdRoundedBox(p, b, R));

            // Quầng sáng mềm mại tự nhiên (lõi neon mảnh + bloom tỏa vào trong)
            float coreGlow = exp(-distToEdge / 3.8);
            float bloomGlow = exp(-distToEdge / 18.0);
            float edgeIntensity = coreGlow * 1.5 + bloomGlow * 0.75;

            // Tính quãng đường chạy từ đỉnh giữa (Dynamic Island) tỏa sang 2 bên rồi chạy dọc xuống đáy
            float normX = abs(p.x) / halfRes.x;
            float normY = (p.y + halfRes.y) / res.y;

            float pathCoord = 0.0;
            if (normY < 0.07) {
                pathCoord = normX * 0.25;
            } else {
                pathCoord = 0.25 + normY * 0.75;
            }

            // Đầu tia sáng dẫn đường chạy từ trên xuống
            float cutoff = smoothstep(streamProgress, max(0.0, streamProgress - 0.16), pathCoord);
            float headPulse = exp(-pow((pathCoord - streamProgress) / 0.06, 2.0)) * 1.6;

            float colorCycle = pathCoord * 1.6 - time * 0.45;
            float3 rgb = siriEdgeSpectrum(colorCycle) * (1.0 + headPulse * 0.5);

            float alpha = edgeIntensity * cutoff * clamp(streamProgress * 1.8, 0.0, 1.0);
            alpha = clamp(alpha, 0.0, 0.95);

            return half4(half3(rgb * alpha), half(alpha));
        }
    """.trimIndent()

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
        isClickable = false
        isFocusable = false
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                shader = RuntimeShader(agsl)
            } catch (_: Exception) {
                shader = null
            }
        }
    }

    fun startEnterAnimation() {
        alpha = 1f
        enterAnimator?.cancel()
        enterAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 540L
            interpolator = PathInterpolator(0.18f, 1f, 0.28f, 1f)
            addUpdateListener {
                streamProgress = it.animatedValue as Float
                invalidate()
            }
            start()
        }

        if (loopAnimator == null) {
            loopAnimator = ValueAnimator.ofFloat(0f, (Math.PI * 2.0).toFloat()).apply {
                duration = 3800L
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

    fun startExitAnimation(onEnd: () -> Unit) {
        enterAnimator?.cancel()
        animate().alpha(0f).setDuration(280L).withEndAction {
            loopAnimator?.cancel()
            loopAnimator = null
            onEnd()
        }.start()
    }

    override fun onDetachedFromWindow() {
        enterAnimator?.cancel()
        enterAnimator = null
        loopAnimator?.cancel()
        loopAnimator = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val activeShader = shader
        if (activeShader != null && Build.VERSION.SDK_INT >= 33) {
            val cornerDp = 38f * resources.displayMetrics.density
            activeShader.setFloatUniform("resolution", w, h)
            activeShader.setFloatUniform("time", phase)
            activeShader.setFloatUniform("streamProgress", streamProgress)
            activeShader.setFloatUniform("cornerRadius", cornerDp)
            paint.shader = activeShader
            canvas.drawRect(0f, 0f, w, h, paint)
            paint.shader = null
        } else {
            val density = resources.displayMetrics.density
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 6f * density
            paint.shader = LinearGradient(
                0f, 0f, w, h,
                intArrayOf(0xFF00D8FF.toInt(), 0xFF9B51E0.toInt(), 0xFFFF385C.toInt(), 0xFF00D8FF.toInt()),
                null, Shader.TileMode.CLAMP
            )
            paint.alpha = (180 * streamProgress).toInt()
            canvas.drawRoundRect(0f, 0f, w, h, 36f * density, 36f * density, paint)
            paint.shader = null
            paint.style = Paint.Style.FILL
            paint.alpha = 255
        }
    }
}
