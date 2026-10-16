package com.digitalsmod.digitalliquid

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
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
 * Sử dụng AGSL RuntimeShader trên GPU để tính toán khúc xạ thủy tinh 3D,
 * dải tán sắc quang phổ chân thực và độ trong suốt sâu nhìn xuyên thấu nền màn hình.
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

    // Fallback cho thiết bị Android < 13
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
            float x = uv.x;
            float y = uv.y;

            float2 p = (uv - float2(0.5, 0.5)) * 2.0;
            float dist = length(p);

            // 1. Đỉnh quả cầu giữ màu đen sâu tuyệt đối để hòa hợp làm một với cụm Dynamic Island và camera
            float blackTop = 1.0 - smoothstep(0.38, 0.49, y);

            // 2. Dải sóng quang phổ nằm chính xác ở trục ngang trung tâm (y = 0.50)
            float waveCenter = 0.50;
            float arch = -sin(x * 3.14159) * 0.052; // Vòm cong nhẹ lên trên ở giữa
            float wave = 0.009 * sin(x * 6.28 - time * 1.8) + 0.004 * sin(x * 12.56 + time * 1.1);
            float flareLine = waveCenter + arch + wave;
            float dy = y - flareLine;

            float core = exp(-abs(dy) * 110.0);
            float bloom = exp(-abs(dy) * 22.0);
            float wideGlow = exp(-abs(dy) * 6.5);

            float specT = clamp((x - 0.10) / 0.80, 0.0, 1.0);
            float3 specCol = siriSpectrum(specT);

            // Điểm hội tụ ánh sáng trắng rực ở lệch tâm trái giống ảnh mẫu
            float hotSpot = exp(-pow((x - 0.40) / 0.16, 2.0)) * core * 1.8;
            float3 hotLight = float3(1.0, 0.99, 0.96) * hotSpot;

            // 3. Khúc xạ thủy tinh thật: Thân kính trong suốt cho phép nhìn xuyên qua các app đằng sau
            // Không dùng màu xám đục để tránh cảm giác nhựa
            float glassAlpha = mix(0.04, 0.16, smoothstep(0.40, 0.95, dist)) * smoothstep(0.45, 0.55, y);

            // Tia tụ quang (Caustic focal rays) lan tỏa xuống đáy kính
            float causticBeam = exp(-pow((x - 0.50) / (0.16 + (y - 0.48) * 0.42), 2.0)) * smoothstep(0.48, 0.75, y) * (1.0 - smoothstep(0.75, 0.96, y));
            float3 causticBeamCol = float3(0.75, 0.90, 1.0) * causticBeam * 0.45;

            // Vệt phản chiếu ngược lưỡi liềm (Inverted caustic reflection) đặc trưng của giọt kính Apple
            float2 arcCenter = float2(0.50, 0.48);
            float arcDist = length(float2((x - arcCenter.x) * 1.18, y - arcCenter.y));
            float crescent = exp(-pow((arcDist - 0.44) * 32.0, 2.0)) * smoothstep(0.52, 0.86, y);
            float3 crescentCol = float3(0.88, 0.95, 1.0) * crescent * 0.95;

            // Viền khúc xạ bạc sắc nét bao quanh đáy quả cầu (Fresnel rim reflection)
            float rim = smoothstep(0.92, 0.985, dist) * (1.0 - smoothstep(0.99, 1.01, dist));
            float rimFadeTop = smoothstep(0.25, 0.80, y);
            float3 rimCol = float3(0.95, 0.98, 1.0) * rim * rimFadeTop * 1.9;

            // Phản chiếu ánh sáng trắng ở đáy dưới cùng
            float bottomLip = exp(-pow((y - 0.97) / 0.03, 2.0)) * exp(-pow((x - 0.50) / 0.32, 2.0));
            float3 bottomLipCol = float3(1.0, 1.0, 1.0) * bottomLip * 1.4;

            // Ánh phản chiếu nhẹ ở đỉnh
            float topGlance = exp(-pow((x - 0.5) / 0.35, 2.0)) * exp(-pow((y - 0.05) / 0.04, 2.0)) * 0.20;
            float3 topCol = float3(1.0) * topGlance;

            // Tổng hợp các lớp quang học
            float3 rgb = float3(0.0);
            rgb += specCol * (bloom * 0.92 + wideGlow * 0.35) * energy;
            rgb += (specCol * 0.45 + float3(0.75, 0.88, 1.0) * 0.55) * core * 1.35;
            rgb += hotLight;
            rgb += causticBeamCol;
            rgb += crescentCol;
            rgb += rimCol;
            rgb += bottomLipCol;
            rgb += topCol;

            // Nửa trên màu đen sâu tuyệt đối, nửa dưới trong suốt khúc xạ ánh sáng
            float finalAlpha = max(blackTop, glassAlpha);
            finalAlpha = max(finalAlpha, (bloom * 0.75 + core * 0.98) * energy);
            finalAlpha = max(finalAlpha, crescent * 0.85);
            finalAlpha = max(finalAlpha, rim * rimFadeTop * 0.95);
            finalAlpha = max(finalAlpha, bottomLip * 0.90);
            finalAlpha = clamp(finalAlpha * morph, 0.0, 1.0);

            return half4(half3(clamp(rgb, 0.0, 1.0) * finalAlpha), half(finalAlpha));
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
        // Nền đen trên đỉnh hòa vào Camera Cutout
        fallbackPaint.shader = LinearGradient(
            0f, 0f, 0f, h * 0.48f,
            intArrayOf(0xFF000000.toInt(), 0xEE000000.toInt(), 0x00000000),
            floatArrayOf(0f, 0.75f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h * 0.48f, fallbackPaint)

        // Cầu vồng uốn cong chính giữa
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

        // Vệt lưỡi liềm phản quang dưới đáy kính
        fallbackPaint.shader = LinearGradient(
            w * 0.15f, 0f, w * 0.85f, 0f,
            intArrayOf(0x00FFFFFF, 0x88DCEBFF.toInt(), 0x00FFFFFF),
            null, Shader.TileMode.CLAMP
        )
        fallbackPaint.strokeWidth = dp(2.2f)
        val crescentRect = RectF(w * 0.10f, h * 0.22f, w * 0.90f, h * 0.92f)
        canvas.drawArc(crescentRect, 45f, 90f, false, fallbackPaint)

        // Viền bạc khúc xạ ngoài mép kính
        fallbackPaint.shader = LinearGradient(
            0f, h * 0.4f, 0f, h,
            intArrayOf(0x00FFFFFF, 0x55B5D6FF, 0xF0FFFFFF.toInt()),
            floatArrayOf(0f, 0.45f, 1f),
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
 * Hiệu ứng dải viền màn hình phát sáng chạy từ trên xuống quanh mép màn hình (Apple Intelligence Edge Glow)
 */
class SiriEdgeGlowView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var shader: RuntimeShader? = null
    private var phase = 0f
    private var progress = 0f
    private var enterAnimator: ValueAnimator? = null
    private var loopAnimator: ValueAnimator? = null

    private val agsl = """
        uniform float2 resolution;
        uniform float time;
        uniform float progress;

        float3 siriEdgeSpectrum(float t) {
            float3 c0 = float3(0.05, 0.42, 1.00); // Blue
            float3 c1 = float3(0.00, 0.92, 1.00); // Cyan
            float3 c2 = float3(0.68, 0.22, 1.00); // Purple / Magenta
            float3 c3 = float3(1.00, 0.38, 0.16); // Coral / Orange
            float3 c4 = float3(1.00, 0.88, 0.12); // Amber
            float x = fract(t);
            float3 col = mix(c0, c1, smoothstep(0.00, 0.25, x));
            col = mix(col, c2, smoothstep(0.25, 0.50, x));
            col = mix(col, c3, smoothstep(0.50, 0.75, x));
            col = mix(col, c4, smoothstep(0.75, 1.00, x));
            return col;
        }

        half4 main(float2 fragCoord) {
            float2 res = max(resolution, float2(1.0, 1.0));
            float2 uv = fragCoord / res;

            // Khoảng cách tới 4 mép viền màn hình
            float dX = min(fragCoord.x, res.x - fragCoord.x);
            float dY = min(fragCoord.y, res.y - fragCoord.y);
            float dist = min(dX, dY);

            // Độ dày vệt sáng viền và hào quang tỏa vào trong
            float edgeCore = exp(-dist / 5.5);
            float edgeBloom = exp(-dist / 22.0);
            float edgeGlow = edgeCore * 1.35 + edgeBloom * 0.70;

            // Dây ánh sáng chạy từ vị trí Dynamic Island (giữa đỉnh) tỏa ra 2 bên mép và chạy dọc xuống đáy
            float topDist = abs(uv.x - 0.5) * 2.0;
            float streamProg = 0.0;
            if (uv.y < 0.08) {
                streamProg = topDist * 0.25;
            } else {
                streamProg = 0.25 + uv.y * 0.75;
            }

            float streamCutoff = smoothstep(progress, max(0.0, progress - 0.14), streamProg);
            float colorPos = streamProg * 1.4 - time * 0.40;
            float3 rgb = siriEdgeSpectrum(colorPos);

            float alpha = edgeGlow * streamCutoff * clamp(progress * 1.5, 0.0, 1.0);
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
            duration = 460L
            interpolator = PathInterpolator(0.16f, 1f, 0.30f, 1f)
            addUpdateListener {
                progress = it.animatedValue as Float
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
        animate().alpha(0f).setDuration(300L).withEndAction {
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
            activeShader.setFloatUniform("resolution", w, h)
            activeShader.setFloatUniform("time", phase)
            activeShader.setFloatUniform("progress", progress)
            paint.shader = activeShader
            canvas.drawRect(0f, 0f, w, h, paint)
            paint.shader = null
        } else {
            // Fallback viền phát sáng đa sắc
            val density = resources.displayMetrics.density
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 6f * density
            paint.shader = LinearGradient(
                0f, 0f, w, h,
                intArrayOf(0xFF00D8FF.toInt(), 0xFF9B51E0.toInt(), 0xFFFF385C.toInt(), 0xFF00D8FF.toInt()),
                null, Shader.TileMode.CLAMP
            )
            paint.alpha = (180 * progress).toInt()
            canvas.drawRoundRect(0f, 0f, w, h, 34f * density, 34f * density, paint)
            paint.shader = null
            paint.style = Paint.Style.FILL
            paint.alpha = 255
        }
    }
}
