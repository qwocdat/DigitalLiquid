package com.digitalsmod.digitalliquid

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.max

/** iOS-style 4dp slider with a 28dp white thumb. */
class IOSSlider(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private fun dp(value: Float) = value * density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var down = false
    private var value = 0
    private var minValue = 0
    private var maxValue = 100
    var onValueChanged: ((value: Int, fromUser: Boolean) -> Unit)? = null
    private var thumbRadius = dp(14f)

    init {
        isClickable = true
        isFocusable = true
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    fun setRange(min: Int, max: Int) {
        minValue = min
        maxValue = max.coerceAtLeast(min + 1)
        value = value.coerceIn(minValue, maxValue)
        invalidate()
    }

    fun setValue(newValue: Int, fromUser: Boolean = false) {
        val normalized = newValue.coerceIn(minValue, maxValue)
        if (normalized == value) return
        value = normalized
        invalidate()
        onValueChanged?.invoke(value, fromUser)
    }

    fun getValue(): Int = value

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            resolveSize(dp(260f).toInt(), widthMeasureSpec),
            resolveSize(dp(42f).toInt(), heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cy = height / 2f
        val left = thumbRadius
        val right = width - thumbRadius
        if (right <= left) return
        val fraction = (value - minValue).toFloat() / (maxValue - minValue).coerceAtLeast(1)
        val thumbX = left + (right - left) * fraction
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(4f)
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = 0xFF454754.toInt()
        canvas.drawLine(left, cy, right, cy, paint)
        paint.color = LiquidDesignTokens.systemBlue
        canvas.drawLine(left, cy, thumbX, cy, paint)
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        paint.setShadowLayer(dp(3f), 0f, dp(1.3f), 0x55000000)
        canvas.drawCircle(thumbX, cy, thumbRadius * if (down) 1.07f else 1f, paint)
        paint.clearShadowLayer()
    }

    private fun updateFromTouch(x: Float) {
        val left = thumbRadius
        val right = (width - thumbRadius).coerceAtLeast(left + 1f)
        val fraction = ((x - left) / (right - left)).coerceIn(0f, 1f)
        setValue(minValue + (fraction * (maxValue - minValue)).toInt(), fromUser = true)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                down = true
                parent?.requestDisallowInterceptTouchEvent(true)
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                updateFromTouch(event.x)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (down) updateFromTouch(event.x)
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (down) updateFromTouch(event.x)
                down = false
                invalidate()
                performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                down = false
                invalidate()
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onInitializeAccessibilityNodeInfo(info: android.view.accessibility.AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = android.widget.SeekBar::class.java.name
        info.isScrollable = true
        info.contentDescription = "Thanh điều chỉnh ${value.coerceIn(minValue, maxValue)}"
    }
}
