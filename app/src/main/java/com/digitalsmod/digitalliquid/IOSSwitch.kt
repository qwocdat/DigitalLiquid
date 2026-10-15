package com.digitalsmod.digitalliquid

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.OvershootInterpolator
import kotlin.math.max

/** Compact iOS-style 51×31dp switch with a white thumb and animated green track. */
class IOSSwitch(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private fun dp(value: Float) = value * density
    private val track = RectF()
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private var animatedPosition = 0f
    private var checkedValue = false
    private var downInside = false
    private var animator: ValueAnimator? = null
    private var changeListener: ((IOSSwitch, Boolean) -> Unit)? = null

    var isChecked: Boolean
        get() = checkedValue
        set(value) { setChecked(value, notify = false, animate = false) }

    init {
        isClickable = true
        isFocusable = true
        contentDescription = "Công tắc"
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    fun setOnCheckedChangeListener(listener: ((IOSSwitch, Boolean) -> Unit)?) {
        changeListener = listener
    }

    fun setChecked(value: Boolean, notify: Boolean = false, animate: Boolean = true) {
        val changed = value != checkedValue
        checkedValue = value
        contentDescription = if (value) "Đang bật" else "Đang tắt"
        isSelected = value
        val target = if (value) 1f else 0f
        animator?.cancel()
        if (animate) {
            animator = ValueAnimator.ofFloat(animatedPosition, target).apply {
                duration = 240L
                interpolator = OvershootInterpolator(0.72f)
                addUpdateListener { animatedPosition = it.animatedValue as Float; invalidate() }
                start()
            }
        } else {
            animatedPosition = target
            invalidate()
        }
        if (notify && changed) changeListener?.invoke(this, value)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(resolveSize(dp(51f).toInt(), widthMeasureSpec), resolveSize(dp(31f).toInt(), heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val radius = h / 2f
        track.set(0f, 0f, w, h)
        trackPaint.color = if (animatedPosition > 0.01f) LiquidDesignTokens.systemGreen else Color.rgb(83, 85, 94)
        canvas.drawRoundRect(track, radius, radius, trackPaint)
        val thumbRadius = dp(12.1f)
        val inset = max(dp(2f), (h - thumbRadius * 2f) / 2f)
        val leftCenter = inset + thumbRadius
        val rightCenter = w - inset - thumbRadius
        val centerX = leftCenter + (rightCenter - leftCenter) * animatedPosition
        val centerY = h / 2f
        thumbPaint.setShadowLayer(dp(2.1f), 0f, dp(1f), 0x50000000)
        canvas.drawCircle(centerX, centerY, thumbRadius, thumbPaint)
        thumbPaint.clearShadowLayer()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downInside = true
                parent?.requestDisallowInterceptTouchEvent(true)
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                animate().scaleX(1.035f).scaleY(1.035f).setDuration(90L).start()
                return true
            }
            MotionEvent.ACTION_UP -> {
                val shouldToggle = downInside && event.x >= 0 && event.x <= width && event.y >= 0 && event.y <= height
                downInside = false
                animate().scaleX(1f).scaleY(1f).setDuration(180L).start()
                if (shouldToggle) performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                downInside = false
                animate().scaleX(1f).scaleY(1f).setDuration(180L).start()
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        setChecked(!checkedValue, notify = true)
        return true
    }

    override fun onInitializeAccessibilityNodeInfo(info: android.view.accessibility.AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = android.widget.Switch::class.java.name
        info.isCheckable = true
        info.isChecked = checkedValue
    }
}
