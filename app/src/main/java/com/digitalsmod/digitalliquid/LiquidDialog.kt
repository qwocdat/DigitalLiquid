package com.digitalsmod.digitalliquid

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.min

/** App-owned dialog surface. No platform AlertDialog widgets or default button styling. */
object LiquidDialog {
    data class Action(
        val label: String,
        val primary: Boolean = false,
        val dismissOnClick: Boolean = true,
        val onClick: (Dialog) -> Unit = {}
    )

    fun show(
        activity: Activity,
        title: String,
        message: String = "",
        eyebrow: String = "DIGITALLIQUID",
        content: View? = null,
        actions: List<Action> = listOf(Action("Đã hiểu", primary = true)),
        cancelable: Boolean = true
    ): Dialog {
        val dialog = Dialog(activity)
        val density = activity.resources.displayMetrics.density
        val lightMode = AppPrefs.get(activity).getBoolean(AppPrefs.LIGHT_MODE, true)
        fun dp(value: Int) = (value * density).toInt()
        val accent = if (lightMode) 0xFF147F78.toInt() else 0xFF9FECDC.toInt()
        val titleColor = if (lightMode) 0xFF17232F.toInt() else Color.WHITE
        val bodyColor = if (lightMode) 0xFF596878.toInt() else 0xFFD1D6E3.toInt()
        val mutedColor = if (lightMode) 0xFF6B798A.toInt() else 0xFFB6BDCE.toInt()

        val root = FrameLayout(activity).apply {
            setBackgroundColor(Color.TRANSPARENT)
            isClickable = true
            setOnClickListener { if (cancelable) dialog.dismiss() }
        }
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(21), dp(20), dp(21), dp(18))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                if (lightMode) intArrayOf(0xF9FFFFFF.toInt(), 0xF0F2F7FB.toInt(), 0xF8FFFFFF.toInt())
                else intArrayOf(0xF51B202C.toInt(), 0xF50B0E16.toInt(), 0xF31C2030.toInt())
            ).apply {
                cornerRadius = dp(28).toFloat()
                setStroke(dp(1), if (lightMode) 0x668A9CB5 else 0x4DFFFFFF)
            }
            elevation = dp(20).toFloat()
            isClickable = true
            isFocusable = true
            clipToOutline = true
        }
        card.setOnClickListener { /* Keep taps inside the card from dismissing it. */ }

        val heading = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        heading.addView(View(activity).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (lightMode) 0xFF20B7A2.toInt() else 0xFF62E6C4.toInt())
            }
        }, LinearLayout.LayoutParams(dp(8), dp(8)))
        heading.addView(TextView(activity).apply {
            text = eyebrow.uppercase()
            textSize = 9.5f
            letterSpacing = 0.12f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(accent)
            includeFontPadding = false
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(9) })
        if (cancelable) {
            heading.addView(TextView(activity).apply {
                text = "×"
                textSize = 25f
                gravity = Gravity.CENTER
                setTextColor(mutedColor)
                isClickable = true
                contentDescription = "Đóng thông báo"
                setOnClickListener { dialog.dismiss() }
            }, LinearLayout.LayoutParams(dp(32), dp(30)))
        }
        card.addView(heading)

        card.addView(TextView(activity).apply {
            text = title
            textSize = 21f
            letterSpacing = -0.02f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(titleColor)
            includeFontPadding = false
            setLineSpacing(dp(2).toFloat(), 1f)
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(13) })

        val scroll = CappedScrollView(activity, dp(420)).apply {
            isFillViewport = false
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        }
        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        if (message.isNotBlank()) {
            body.addView(TextView(activity).apply {
                text = message
                textSize = 13.5f
                setTextColor(bodyColor)
                setLineSpacing(dp(3).toFloat(), 1.06f)
                includeFontPadding = false
                setTextIsSelectable(true)
            }, LinearLayout.LayoutParams(-1, -2))
        }
        if (content != null) {
            val top = if (message.isNotBlank()) dp(13) else 0
            body.addView(content, LinearLayout.LayoutParams(-1, -2).apply { topMargin = top })
        }
        scroll.addView(body, ViewGroup.LayoutParams(-1, -2))
        card.addView(scroll, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })

        if (actions.isNotEmpty()) {
            val actionRow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            actions.take(3).forEachIndexed { index, action ->
                val button = TextView(activity).apply {
                    text = action.label
                    textSize = if (actions.size >= 3) 10.5f else 12f
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    gravity = Gravity.CENTER
                    setPadding(dp(7), dp(4), dp(7), dp(4))
                    minHeight = dp(44)
                    maxLines = 2
                    setTextColor(if (action.primary) Color.rgb(7, 31, 29) else if (lightMode) 0xFF384758.toInt() else 0xFFE1E5F0.toInt())
                    background = if (action.primary) GradientDrawable(
                        GradientDrawable.Orientation.LEFT_RIGHT,
                        intArrayOf(0xFF86F0D6.toInt(), 0xFF64D9C2.toInt())
                    ).apply {
                        cornerRadius = dp(15).toFloat()
                        setStroke(dp(1), 0x70FFFFFF)
                    } else GradientDrawable().apply {
                        setColor(if (lightMode) 0xFFE6EEF3.toInt() else 0x1FFFFFFF)
                        cornerRadius = dp(15).toFloat()
                        setStroke(dp(1), if (lightMode) 0x66899CAF else 0x35FFFFFF)
                    }
                    isClickable = true
                    isFocusable = true
                    tag = "liquid-action:${action.label}"
                    setOnClickListener {
                        if (action.dismissOnClick) dialog.dismiss()
                        action.onClick(dialog)
                    }
                }
                actionRow.addView(button, LinearLayout.LayoutParams(0, -2, 1f).apply {
                    if (index > 0) marginStart = dp(7)
                })
            }
            card.addView(actionRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18) })
        }

        val maxWidth = (activity.resources.displayMetrics.widthPixels - dp(34)).coerceAtLeast(dp(250))
        val cardWidth = min(dp(460), maxWidth)
        root.addView(card, FrameLayout.LayoutParams(cardWidth, -2, Gravity.CENTER))
        dialog.setCancelable(cancelable)
        dialog.setCanceledOnTouchOutside(cancelable)
        dialog.setContentView(root)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.apply { dimAmount = if (lightMode) 0.34f else 0.68f }
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        dialog.show()
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        card.alpha = 0f
        card.scaleX = 0.96f
        card.scaleY = 0.96f
        card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(190L).start()
        return dialog
    }

    fun setActionEnabled(dialog: Dialog, label: String, enabled: Boolean) {
        val root = dialog.window?.decorView ?: return
        val target = findTaggedView(root, "liquid-action:$label") as? TextView ?: return
        target.isEnabled = enabled
        target.alpha = if (enabled) 1f else 0.45f
    }

    private fun findTaggedView(view: View, tag: String): View? {
        if (view.tag == tag) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                val found = findTaggedView(view.getChildAt(index), tag)
                if (found != null) return found
            }
        }
        return null
    }

    private class CappedScrollView(context: Activity, private val maxHeight: Int) : ScrollView(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val mode = MeasureSpec.getMode(heightMeasureSpec)
            val size = MeasureSpec.getSize(heightMeasureSpec)
            val capped = if (mode == MeasureSpec.UNSPECIFIED) maxHeight else min(size, maxHeight)
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(capped, MeasureSpec.AT_MOST))
        }
    }
}
