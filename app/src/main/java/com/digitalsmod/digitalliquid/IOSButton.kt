package com.digitalsmod.digitalliquid

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.TextView

/** Reusable press-responsive capsule button without Material ripple/elevation. */
class IOSButton(context: Context) : TextView(context) {
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    fun style(label: CharSequence, primary: Boolean) {
        text = label
        textSize = 12f
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        val lightMode = AppPrefs.get(context).getBoolean(AppPrefs.LIGHT_MODE, true)
        setTextColor(if (primary) 0xFF07356E.toInt() else if (!lightMode) Color.WHITE else 0xFF25283A.toInt())
        gravity = android.view.Gravity.CENTER
        setPadding(dp(12), dp(10), dp(12), dp(10))
        minHeight = dp(44)
        background = if (primary) GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(0xFFA7D2FF.toInt(), 0xFF66B2FF.toInt())
        ).apply {
            cornerRadius = dp(15).toFloat()
            setStroke(dp(1), 0x42FFFFFF)
        } else if (lightMode) GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(0xEFFFFFFF.toInt(), 0xDDE9EEF7.toInt())
        ).apply {
            cornerRadius = dp(15).toFloat()
            setStroke(dp(1), 0x6698ACC9)
        } else GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(0x2CFFFFFF, 0x16FFFFFF)
        ).apply {
            cornerRadius = dp(15).toFloat()
            setStroke(dp(1), 0x35FFFFFF)
        }
        isClickable = true
        isFocusable = true
        stateListAnimator = null
        setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    view.animate().scaleX(0.96f).scaleY(0.96f).alpha(0.82f)
                        .setDuration(90L).start()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.animate().scaleX(1f).scaleY(1f).alpha(1f)
                        .setDuration(260L).setInterpolator(OvershootInterpolator(0.72f)).start()
                }
            }
            false
        }
    }
}
