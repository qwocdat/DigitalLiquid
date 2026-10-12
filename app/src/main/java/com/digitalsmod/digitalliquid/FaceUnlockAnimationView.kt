package com.digitalsmod.digitalliquid

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Face ID animation drawn inside the expanded Dynamic Island container.
 *
 * Success timeline (ms):
 *   0    - 350   container expands, green Face ID icon pops & fades in
 *   350  - 1100  icon transitions into 3 orbital rings spinning in 3D
 *   1100 - 1400  rings merge into one flat circle, tick is drawn left -> right
 *   1400 - 3500  hold success state
 *   3500 - 3900  content fades/shrinks while the SERVICE shrinks the container
 *
 * Failure timeline (ms): 0-350 expand, 350-900 shake red face icon, 900-1800 hold, then shrink.
 */
class FaceUnlockAnimationView(
    context: Context,
    private val failed: Boolean = false,
    private val onShrinkRequested: () -> Unit
) : View(context) {

    enum class State { EXPANDING, SCANNING_3D, MERGING, SUCCESS, FAILED, SHRINKING }

    var state: State = State.EXPANDING
        private set

    private val green = Color.parseColor("#30D158")
    private val red = Color.parseColor("#FF453A")

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density
    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = dp(5f)
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = dp(3.5f)
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val rect = RectF()
    private val tmpPath = Path()
    private val tickPath = Path()
    private val tickSegment = Path()
    private val tickMeasure = PathMeasure()
    private var tickLength = 0f

    private val emphasisEase = PathInterpolator(0.22f, 1f, 0.36f, 1f)
    private val spinEase = PathInterpolator(0.30f, 0f, 0.20f, 1f)
    private val settleEase = PathInterpolator(0.25f, 0.1f, 0.25f, 1f)

    private val totalMs = if (failed) FAIL_HOLD_END else T_HOLD_END

    private var timeMs = 0f
    private var shrinkT = 0f
    private var shrinkStarted = false
    private var shrinkNotified = false
    private var timeline: ValueAnimator? = null
    private var fadeAnim: ValueAnimator? = null
    private var timelineCanceled = false

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private var touchStartX = 0f
    private var touchStartY = 0f

    init {
        isClickable = true
        isFocusable = false
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (timeline == null && !shrinkStarted) startTimeline()
    }

    override fun onDetachedFromWindow() {
        release()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val cx = w / 2f
        val cy = h / 2f
        tickPath.reset()
        tickPath.moveTo(cx - dp(13f), cy + dp(1f))
        tickPath.lineTo(cx - dp(4f), cy + dp(11f))
        tickPath.lineTo(cx + dp(15f), cy - dp(8f))
        tickMeasure.setPath(tickPath, false)
        tickLength = tickMeasure.length
    }

    fun release() {
        timeline?.let { it.removeAllUpdateListeners(); it.removeAllListeners(); it.cancel() }
        timeline = null
        fadeAnim?.let { it.removeAllUpdateListeners(); it.removeAllListeners(); it.cancel() }
        fadeAnim = null
    }

    private fun startTimeline() {
        timelineCanceled = false
        timeline = ValueAnimator.ofFloat(0f, totalMs).apply {
            duration = totalMs.toLong()
            interpolator = LinearInterpolator()
            addUpdateListener {
                timeMs = it.animatedValue as Float
                updateState()
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationCancel(animation: Animator) { timelineCanceled = true }
                override fun onAnimationEnd(animation: Animator) {
                    if (!timelineCanceled) requestShrink()
                }
            })
            start()
        }
    }

    private fun updateState() {
        state = when {
            shrinkStarted -> State.SHRINKING
            timeMs < T_EXPAND_END -> State.EXPANDING
            failed -> State.FAILED
            timeMs < T_SPIN_END -> State.SCANNING_3D
            timeMs < T_MERGE_END -> State.MERGING
            else -> State.SUCCESS
        }
    }

    fun startShrinkFade() {
        if (shrinkStarted) return
        shrinkStarted = true
        timelineCanceled = true
        timeline?.let { it.removeAllUpdateListeners(); it.removeAllListeners(); it.cancel() }
        timeline = null
        state = State.SHRINKING
        fadeAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = SHRINK_MS
            interpolator = LinearInterpolator()
            addUpdateListener {
                shrinkT = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun requestShrink() {
        startShrinkFade()
        if (!shrinkNotified) {
            shrinkNotified = true
            onShrinkRequested()
        }
    }

    fun triggerFailureState() { requestShrink() }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchStartX = event.x
                touchStartY = event.y
            }
            MotionEvent.ACTION_MOVE -> {
                if (hypot(event.x - touchStartX, event.y - touchStartY) > maxOf(touchSlop, dp(6f))) requestShrink()
            }
            MotionEvent.ACTION_UP -> requestShrink()
        }
        return true
    }

    private fun withAlpha(color: Int, a: Float): Int =
        Color.argb((255f * a.coerceIn(0f, 1f)).roundToInt(), Color.red(color), Color.green(color), Color.blue(color))

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        val cx = width * 0.5f
        val cy = height * 0.5f
        val fade = shrinkT
        val contentAlpha = (1f - fade).pow(1.6f)
        if (contentAlpha <= 0.004f) return
        val sc = 1f - 0.16f * fade

        canvas.save()
        canvas.scale(sc, sc, cx, cy)
        if (failed) drawFailure(canvas, cx, cy, timeMs, contentAlpha)
        else drawSuccess(canvas, cx, cy, timeMs, contentAlpha)
        canvas.restore()
    }

    private fun drawSuccess(canvas: Canvas, cx: Float, cy: Float, t: Float, alpha: Float) {
        val radius = dp(30f)

        // Phase 1: Biểu tượng Face ID màu xanh lá xuất hiện lúc bắt đầu quét
        val frameIn = emphasisEase.getInterpolation((t / T_EXPAND_END).coerceIn(0f, 1f))
        val frameOut = ((t - T_EXPAND_END) / FRAME_FADE_MS).coerceIn(0f, 1f)
        val frameAlpha = frameIn * (1f - frameOut)

        if (frameAlpha > 0.01f) {
            val ghostEffect = 1f - frameIn
            if (ghostEffect > 0.02f) {
                for (g in 2 downTo 1) {
                    val s = 1f + 0.12f * g * ghostEffect
                    drawFaceIcon(canvas, cx, cy, alpha * frameAlpha * 0.25f * ghostEffect / g, green, s)
                }
            }
            drawFaceIcon(canvas, cx, cy, alpha * frameAlpha, green, 1f)
        }
        if (t <= T_EXPAND_END) return

        // Phase 2 & 3: Xoay vòng 3D -> Dung hợp -> Vẽ tick xanh
        val ringIn = ((t - T_EXPAND_END) / 120f).coerceIn(0f, 1f)
        val spinRaw = ((t - T_EXPAND_END) / (T_SPIN_END - T_EXPAND_END)).coerceIn(0f, 1f)
        val spin = spinEase.getInterpolation(spinRaw)
        val mergeRaw = ((t - T_SPIN_END) / (T_MERGE_END - T_SPIN_END)).coerceIn(0f, 1f)
        val merge = settleEase.getInterpolation(mergeRaw)
        val pop = 1f + 0.06f * sin(PI * mergeRaw).toFloat()
        val strokeW = lerp(dp(3.4f), dp(5f), merge)

        canvas.save()
        canvas.scale(pop, pop, cx, cy)

        val trail = (1f - spin).coerceAtLeast(0f).pow(0.6f) * (1f - merge)
        if (trail > 0.02f) {
            for (k in 2 downTo 1) {
                val trailSpin = (spin - 0.045f * k).coerceAtLeast(0f)
                for (i in 0..2) {
                    drawRing(canvas, cx, cy, radius, i, trailSpin, 0f, alpha * ringIn * trail * 0.22f / k, strokeW)
                }
            }
        }
        for (i in 0..2) drawRing(canvas, cx, cy, radius, i, spin, merge, alpha * ringIn, strokeW)

        val tickRaw = ((t - TICK_START) / (T_MERGE_END - TICK_START)).coerceIn(0f, 1f)
        if (tickRaw > 0f && tickLength > 0f) {
            val p = 1f - (1f - tickRaw) * (1f - tickRaw)
            tickSegment.rewind()
            tickMeasure.getSegment(0f, tickLength * p, tickSegment, true)
            tickPaint.color = withAlpha(green, alpha)
            canvas.drawPath(tickSegment, tickPaint)
        }
        canvas.restore()
    }

    private fun drawRing(
        canvas: Canvas, cx: Float, cy: Float, radius: Float,
        index: Int, spin: Float, merge: Float, alpha: Float, strokeW: Float
    ) {
        val ringAlpha = alpha * (if (index == 0) 1f else 1f - merge)
        if (ringAlpha <= 0.01f) return

        val theta = RING_BASE_ANGLE * index + 0.35f + spin * RING_TURNS * TWO_PI
        val c = cos(theta)
        val minor = lerp(radius * abs(c), radius, merge)
        val tilt = lerp(index * 60f + spin * 50f, 0f, merge)
        val frontStart = if (c >= 0f) 0f else 180f
        val backStart = 180f - frontStart
        val backFactor = lerp(0.30f, 1f, merge)

        canvas.save()
        canvas.translate(cx, cy)
        canvas.rotate(tilt)
        rect.set(-radius, -minor, radius, minor)

        ringPaint.strokeWidth = strokeW * 2.4f
        ringPaint.color = withAlpha(green, ringAlpha * 0.16f)
        canvas.drawArc(rect, 0f, 360f, false, ringPaint)

        ringPaint.strokeWidth = strokeW
        ringPaint.color = withAlpha(green, ringAlpha * backFactor)
        canvas.drawArc(rect, backStart, 180f, false, ringPaint)
        ringPaint.color = withAlpha(green, ringAlpha)
        canvas.drawArc(rect, frontStart, 180f, false, ringPaint)
        canvas.restore()
    }

    private fun drawFailure(canvas: Canvas, cx: Float, cy: Float, t: Float, alpha: Float) {
        val iconAlpha = ((t - 150f) / 200f).coerceIn(0f, 1f) * alpha
        val dx = shakeX(t)

        if (iconAlpha > 0.01f) {
            for (k in 2 downTo 1) {
                val gx = shakeX(t - 28f * k)
                if (abs(gx - dx) > dp(0.6f)) {
                    drawFaceIcon(canvas, cx + gx, cy, iconAlpha * 0.24f / k, red, 1f)
                }
            }
            drawFaceIcon(canvas, cx + dx, cy, iconAlpha, red, 1f)
        }
    }

    private fun shakeX(t: Float): Float {
        if (t < FAIL_SHAKE_START || t > FAIL_SHAKE_END) return 0f
        val u = (t - FAIL_SHAKE_START) / (FAIL_SHAKE_END - FAIL_SHAKE_START)
        return sin(u * PI * 6.0).toFloat() * dp(8f) * (1f - u)
    }

    /**
     * Biểu tượng Face ID chuẩn Apple (4 góc khung, 2 mắt dạng pill, mũi móc và miệng cười).
     */
    private fun drawFaceIcon(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        alpha: Float,
        color: Int,
        scale: Float = 1f
    ) {
        if (alpha <= 0.005f) return
        val col = withAlpha(color, alpha)
        iconPaint.color = col
        fillPaint.color = col

        val half = dp(28f)
        val cr = dp(11f)
        val len = dp(17f)

        canvas.save()
        if (scale != 1f) {
            canvas.scale(scale, scale, cx, cy)
        }

        // 1. 4 góc khung bao
        fun corner(sx: Float, sy: Float) {
            tmpPath.rewind()
            tmpPath.moveTo(cx + sx * (half - len), cy + sy * half)
            tmpPath.lineTo(cx + sx * (half - cr), cy + sy * half)
            tmpPath.quadTo(cx + sx * half, cy + sy * half, cx + sx * half, cy + sy * (half - cr))
            tmpPath.lineTo(cx + sx * half, cy + sy * (half - len))
            canvas.drawPath(tmpPath, iconPaint)
        }
        corner(-1f, -1f) // Trên Tái
        corner(1f, -1f)  // Trên Phải
        corner(-1f, 1f)  // Dưới Trái
        corner(1f, 1f)   // Dưới Phải

        // 2. Hai mắt (Dạng viên thuốc đứng chuẩn Face ID)
        val eyeWidth = dp(4f)
        val eyeHeight = dp(9f)
        val eyeY = cy - dp(6f)
        rect.set(cx - dp(10f) - eyeWidth / 2f, eyeY - eyeHeight / 2f, cx - dp(10f) + eyeWidth / 2f, eyeY + eyeHeight / 2f)
        canvas.drawRoundRect(rect, eyeWidth / 2f, eyeWidth / 2f, fillPaint)

        rect.set(cx + dp(10f) - eyeWidth / 2f, eyeY - eyeHeight / 2f, cx + dp(10f) + eyeWidth / 2f, eyeY + eyeHeight / 2f)
        canvas.drawRoundRect(rect, eyeWidth / 2f, eyeWidth / 2f, fillPaint)

        // 3. Mũi (Nét đứng cong sang trái ở góc dưới)
        tmpPath.rewind()
        tmpPath.moveTo(cx, cy - dp(7f))
        tmpPath.lineTo(cx, cy + dp(2f))
        tmpPath.quadTo(cx, cy + dp(5f), cx - dp(4f), cy + dp(5f))
        canvas.drawPath(tmpPath, iconPaint)

        // 4. Miệng cười (Đường cong nụ cười)
        tmpPath.rewind()
        tmpPath.moveTo(cx - dp(10f), cy + dp(11f))
        tmpPath.quadTo(cx, cy + dp(17f), cx + dp(10f), cy + dp(11f))
        canvas.drawPath(tmpPath, iconPaint)

        canvas.restore()
    }

    private companion object {
        const val T_EXPAND_END = 350f
        const val T_SPIN_END = 1100f
        const val TICK_START = 1250f
        const val T_MERGE_END = 1400f
        const val T_HOLD_END = 3500f
        const val SHRINK_MS = 400L
        const val FRAME_FADE_MS = 150f

        const val FAIL_SHAKE_START = 350f
        const val FAIL_SHAKE_END = 900f
        const val FAIL_HOLD_END = 1800f

        const val RING_TURNS = 1.25f
        val TWO_PI = (2.0 * PI).toFloat()
        val RING_BASE_ANGLE = (PI / 3.0).toFloat()
    }
}
