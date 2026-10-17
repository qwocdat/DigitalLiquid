package com.digitalsmod.digitalliquid

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** Floating frosted-glass tab bar, with a soft selected capsule like the supplied references. */
class GlassTabBar(
    context: Context,
    private val items: List<Item>,
    selectedIndex: Int,
    private val onSelected: (Int) -> Unit
) : FrameLayout(context) {
    data class Item(val icon: String, val title: String)

    private val density = resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()
    private val lightMode = AppPrefs.get(context).getBoolean(AppPrefs.LIGHT_MODE, true)
    private val accentColor = when (AppPrefs.get(context).getString(AppPrefs.ACCENT_COLOR, "blue")) {
        "purple" -> 0xFFAA58E8.toInt()
        "pink" -> 0xFFF13070.toInt()
        "red" -> 0xFFF3423A.toInt()
        else -> 0xFF1687F5.toInt()
    }
    private val indicator = View(context)
    private val row = LinearLayout(context)
    private var selected = selectedIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))

    init {
        background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            if (lightMode) intArrayOf(0xF7FFFFFF.toInt(), 0xEAF1F8FF.toInt(), 0xDDE9EEF7.toInt())
            else intArrayOf(0xE91E222E.toInt(), 0xE610121A.toInt(), 0xE91B1F2B.toInt())
        ).apply {
            cornerRadius = dp(34).toFloat()
            setStroke(dp(1), if (lightMode) 0xBFFFFFFF.toInt() else 0x38FFFFFF)
        }
        setPadding(dp(5), dp(5), dp(5), dp(5))
        elevation = dp(18).toFloat()
        clipChildren = false
        clipToPadding = false
        isClickable = true
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES

        indicator.background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            if (lightMode) intArrayOf(0xE3D8E3F2.toInt(), 0xD4C8D8E9.toInt())
            else intArrayOf(0x44A7BFFF, 0x2692AAE8)
        ).apply {
            cornerRadius = dp(29).toFloat()
            setStroke(dp(1), if (lightMode) 0x8AFFFFFF.toInt() else 0x42FFFFFF)
        }
        indicator.alpha = 0.98f
        indicator.elevation = dp(1).toFloat()
        addView(indicator, LayoutParams(0, -1, Gravity.START or Gravity.TOP))

        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER
        addView(row, LayoutParams(-1, -1))
        items.take(5).forEachIndexed { index, item ->
            val cell = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                setPadding(dp(2), dp(1), dp(2), dp(1))
                contentDescription = item.title
            }
            val glyph = TextView(context).apply {
                text = item.icon
                textSize = 22f
                gravity = Gravity.CENTER
                includeFontPadding = false
                typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                setTextColor(if (index == selected) accentColor else if (lightMode) 0xFF1E2028.toInt() else 0xFFE8EAF2.toInt())
            }
            val label = TextView(context).apply {
                text = item.title
                textSize = 10f
                gravity = Gravity.CENTER
                maxLines = 1
                includeFontPadding = false
                typeface = Typeface.create("sans-serif-medium", if (index == selected) Typeface.BOLD else Typeface.NORMAL)
                setTextColor(if (index == selected) accentColor else if (lightMode) 0xFF242630.toInt() else 0xFFD4D7E0.toInt())
            }
            cell.addView(glyph, LinearLayout.LayoutParams(-1, dp(25)))
            cell.addView(label, LinearLayout.LayoutParams(-1, dp(15)))
            cell.tag = Pair(glyph, label)
            cell.setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                if (selected != index) {
                    selected = index
                    updateSelection(animated = true)
                    onSelected(index)
                }
            }
            row.addView(cell, LinearLayout.LayoutParams(0, -1, 1f))
        }
        addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateSelection(animated = false) }
        post { updateSelection(animated = false) }
    }

    private fun updateSelection(animated: Boolean) {
        if (items.isEmpty() || row.width <= 0) return
        val usable = row.width.toFloat()
        val cellWidth = usable / items.take(5).size
        val indicatorWidth = (cellWidth - dp(1)).toInt().coerceAtLeast(dp(42))
        val lp = indicator.layoutParams
        if (lp.width != indicatorWidth || lp.height != -1) {
            lp.width = indicatorWidth
            lp.height = -1
            indicator.layoutParams = lp
        }
        val x = selected * cellWidth + dp(1).toFloat()
        if (animated) {
            indicator.animate().cancel()
            indicator.animate().translationX(x).scaleX(1.035f).setDuration(320L)
                .setInterpolator(PathInterpolator(0.18f, 0.88f, 0.28f, 1f)).withEndAction {
                    indicator.animate().scaleX(1f).setDuration(140L).start()
                }.start()
        } else {
            indicator.translationX = x
            indicator.scaleX = 1f
        }
        for (i in 0 until row.childCount) {
            val cell = row.getChildAt(i) as? LinearLayout ?: continue
            @Suppress("UNCHECKED_CAST")
            val pair = cell.tag as? Pair<TextView, TextView> ?: continue
            val color = if (i == selected) accentColor else if (lightMode) 0xFF242630.toInt() else 0xFFD4D7E0.toInt()
            pair.first.setTextColor(color)
            pair.first.textSize = if (i == selected) 23f else 21f
            pair.second.setTextColor(color)
            pair.second.typeface = Typeface.create("sans-serif-medium", if (i == selected) Typeface.BOLD else Typeface.NORMAL)
        }
    }
}
