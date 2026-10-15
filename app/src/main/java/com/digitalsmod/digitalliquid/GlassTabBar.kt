package com.digitalsmod.digitalliquid

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** Floating five-or-fewer item tab bar with a sliding translucent selected indicator. */
class GlassTabBar(
    context: Context,
    private val items: List<Item>,
    selectedIndex: Int,
    private val onSelected: (Int) -> Unit
) : FrameLayout(context) {
    data class Item(val icon: String, val title: String)

    private val density = resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()
    private val indicator = View(context)
    private val row = LinearLayout(context)
    private var selected = selectedIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))

    init {
        val lightMode = AppPrefs.get(context).getBoolean(AppPrefs.LIGHT_MODE, true)
        background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            if (lightMode) intArrayOf(0xF4FFFFFF.toInt(), 0xE5EAF2FA.toInt())
            else intArrayOf(0xE61D1F2A.toInt(), 0xE80C0E15.toInt())
        ).apply {
            cornerRadius = dp(27).toFloat()
            setStroke(dp(1), if (lightMode) 0x558795B1 else 0x35FFFFFF)
        }
        setPadding(dp(5), dp(5), dp(5), dp(5))
        clipChildren = false
        clipToPadding = false

        indicator.background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(0xFF72E5CA.toInt(), 0xFF36AFAE.toInt())
        ).apply {
            cornerRadius = dp(23).toFloat()
            setStroke(dp(1), 0x80FFFFFF)
        }
        indicator.alpha = 0.95f
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
                setPadding(dp(2), dp(2), dp(2), dp(2))
            }
            val glyph = TextView(context).apply {
                text = item.icon
                textSize = 20f
                gravity = Gravity.CENTER
                setTextColor(if (index == selected || !lightMode) Color.WHITE else 0xFF5D6272.toInt())
                includeFontPadding = false
            }
            val label = TextView(context).apply {
                text = item.title
                textSize = 8.5f
                gravity = Gravity.CENTER
                maxLines = 1
                setTextColor(if (index == selected || !lightMode) Color.WHITE else 0xFF5D6272.toInt())
                includeFontPadding = false
            }
            cell.addView(glyph, LinearLayout.LayoutParams(-1, dp(23)))
            cell.addView(label, LinearLayout.LayoutParams(-1, dp(14)))
            cell.tag = Pair(glyph, label)
            cell.setOnClickListener {
                if (selected == index) {
                    it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    return@setOnClickListener
                }
                selected = index
                updateSelection(animated = true)
                it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                onSelected(index)
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
        val indicatorWidth = (cellWidth - dp(2)).toInt().coerceAtLeast(dp(34))
        val lp = indicator.layoutParams
        if (lp.width != indicatorWidth || lp.height != -1) {
            lp.width = indicatorWidth
            lp.height = -1
            indicator.layoutParams = lp
        }
        val x = selected * cellWidth + dp(1).toFloat()
        if (animated) {
            indicator.animate().translationX(x).scaleX(1.02f).setDuration(340L)
                .setInterpolator(OvershootInterpolator(0.72f)).withEndAction {
                    indicator.animate().scaleX(1f).setDuration(120L).start()
                }.start()
        } else indicator.translationX = x
        for (i in 0 until row.childCount) {
            val cell = row.getChildAt(i) as? LinearLayout ?: continue
            @Suppress("UNCHECKED_CAST")
            val pair = cell.tag as? Pair<TextView, TextView> ?: continue
            val lightMode = AppPrefs.get(context).getBoolean(AppPrefs.LIGHT_MODE, true)
            val color = if (i == selected) Color.WHITE else if (lightMode) 0xFF5D6272.toInt() else 0xFFB5B8C5.toInt()
            pair.first.setTextColor(color)
            pair.first.textSize = if (i == selected) 21f else 19f
            pair.second.setTextColor(color)
        }
    }
}
