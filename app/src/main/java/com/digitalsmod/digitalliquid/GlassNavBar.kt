package com.digitalsmod.digitalliquid

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** Large-title header with a compact assistant shortcut and a readable service state. */
class GlassNavBar(
    context: Context,
    title: CharSequence,
    subtitle: CharSequence,
    active: Boolean,
    private val onAssistant: () -> Unit = {}
) : LinearLayout(context) {
    val titleView = TextView(context)
    val subtitleView = TextView(context)
    val stateView = TextView(context)
    private val density = resources.displayMetrics.density
    private fun dp(value: Int) = (value * density).toInt()

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val lightMode = AppPrefs.get(context).getBoolean(AppPrefs.LIGHT_MODE, true)

        val logo = ImageView(context).apply {
            setImageResource(R.drawable.digitalliquid_brand)
            scaleType = ImageView.ScaleType.CENTER_CROP
            contentDescription = "DigitalLiquid"
        }
        addView(logo, LayoutParams(dp(42), dp(42)))

        val labels = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), 0, dp(7), 0)
        }
        titleView.apply {
            text = title
            textSize = 25f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(if (lightMode) 0xFF161923.toInt() else Color.WHITE)
            includeFontPadding = false
            letterSpacing = -0.025f
        }
        subtitleView.apply {
            text = subtitle
            textSize = 8.2f
            letterSpacing = 0.10f
            setTextColor(if (lightMode) 0xFF3987E9.toInt() else 0xFF9DBEFF.toInt())
            includeFontPadding = false
            maxLines = 1
        }
        labels.addView(titleView)
        labels.addView(subtitleView, LayoutParams(-1, -2).apply { topMargin = dp(3) })
        addView(labels, LayoutParams(0, -2, 1f))

        val siri = TextView(context).apply {
            text = "✦"
            textSize = 19f
            gravity = Gravity.CENTER
            setTextColor(if (lightMode) 0xFF167FE8.toInt() else 0xFFB8D4FF.toInt())
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                if (lightMode) intArrayOf(0xF7FFFFFF.toInt(), 0xDBE5F5FF.toInt())
                else intArrayOf(0x35FFFFFF, 0x16FFFFFF)
            ).apply {
                shape = GradientDrawable.OVAL
                setStroke(dp(1), if (lightMode) 0x628EB8EC else 0x35FFFFFF)
            }
            isClickable = true
            isFocusable = true
            contentDescription = "Mở hiệu ứng Siri"
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                onAssistant()
            }
        }
        addView(siri, LayoutParams(dp(38), dp(38)).apply { marginEnd = dp(7) })

        stateView.apply {
            text = if (active) "LIVE" else "READY"
            textSize = 9f
            letterSpacing = 0.07f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(if (lightMode) { if (active) 0xFF197C49.toInt() else 0xFF526078.toInt() } else { if (active) 0xFF8EF2BE.toInt() else 0xFFB9BCCD.toInt() })
            setPadding(dp(9), dp(7), dp(9), dp(7))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                if (lightMode) intArrayOf(0xF7FFFFFF.toInt(), 0xE3EAF5FB.toInt()) else intArrayOf(0x32FFFFFF, 0x16FFFFFF)
            ).apply {
                cornerRadius = dp(30).toFloat()
                setStroke(dp(1), if (lightMode) 0x578B9BBC else 0x28FFFFFF)
            }
        }
        addView(stateView)
        contentDescription = "Thanh điều hướng DigitalLiquid"
    }
}
