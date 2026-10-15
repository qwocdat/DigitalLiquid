package com.digitalsmod.digitalliquid

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** Reusable large-title navigation header for the native Liquid Glass dashboard. */
class GlassNavBar(
    context: Context,
    title: CharSequence,
    subtitle: CharSequence,
    active: Boolean
) : LinearLayout(context) {
    val titleView = TextView(context)
    val subtitleView = TextView(context)
    val stateView = TextView(context)
    private val density = resources.displayMetrics.density
    private fun dp(value: Int) = (value * density).toInt()

    init {
        orientation = HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        val lightMode = AppPrefs.get(context).getBoolean(AppPrefs.LIGHT_MODE, false)
        val logo = ImageView(context).apply {
            setImageResource(R.drawable.digitalliquid_brand)
            scaleType = ImageView.ScaleType.CENTER_CROP
            contentDescription = "DigitalLiquid"
        }
        addView(logo, LayoutParams(dp(44), dp(44)))

        val labels = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(11), 0, dp(8), 0)
        }
        titleView.apply {
            text = title
            textSize = 27f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(if (lightMode) 0xFF161823.toInt() else Color.WHITE)
            includeFontPadding = false
        }
        subtitleView.apply {
            text = subtitle
            textSize = 9f
            letterSpacing = 0.12f
            setTextColor(if (lightMode) 0xFF64697A.toInt() else 0xFF9699AA.toInt())
            includeFontPadding = false
        }
        labels.addView(titleView)
        labels.addView(subtitleView, LayoutParams(-1, -2).apply { topMargin = dp(3) })
        addView(labels, LayoutParams(0, -2, 1f))

        stateView.apply {
            text = if (active) "ĐANG BẬT" else "ĐANG TẮT"
            textSize = 10f
            letterSpacing = 0.04f
            setTextColor(if (lightMode) { if (active) 0xFF20784A.toInt() else 0xFF51576A.toInt() } else { if (active) 0xFF8EF2BE.toInt() else 0xFFB9BCCD.toInt() })
            setPadding(dp(10), dp(7), dp(10), dp(7))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                if (lightMode) intArrayOf(0xD9FFFFFF.toInt(), 0xBDECF0F7.toInt()) else intArrayOf(0x32FFFFFF, 0x16FFFFFF)
            ).apply {
                cornerRadius = dp(30).toFloat()
                setStroke(dp(1), if (lightMode) 0x558795B1 else 0x28FFFFFF)
            }
        }
        addView(stateView)
        contentDescription = "Thanh điều hướng DigitalLiquid"
    }
}
