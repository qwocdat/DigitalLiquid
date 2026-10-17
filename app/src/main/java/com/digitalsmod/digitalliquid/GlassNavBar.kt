package com.digitalsmod.digitalliquid

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** Lightweight iOS-inspired header. Home uses the branded lockup; other tabs use a large page title. */
class GlassNavBar(
    context: Context,
    title: CharSequence,
    subtitle: CharSequence,
    active: Boolean,
    private val onAssistant: () -> Unit = {},
    private val onSearch: (() -> Unit)? = null
) : LinearLayout(context) {
    val titleView = TextView(context)
    val subtitleView = TextView(context)
    val stateView = TextView(context)

    private val density = resources.displayMetrics.density
    private fun dp(value: Int) = (value * density).toInt()
    private val lightMode = AppPrefs.get(context).getBoolean(AppPrefs.LIGHT_MODE, true)
    private val accentColor = when (AppPrefs.get(context).getString(AppPrefs.ACCENT_COLOR, "blue")) {
        "purple" -> 0xFFAA58E8.toInt()
        "pink" -> 0xFFF13070.toInt()
        "red" -> 0xFFF3423A.toInt()
        else -> 0xFF2488E8.toInt()
    }
    private val homeStyle = subtitle.toString().isNotBlank()

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(if (homeStyle) dp(2) else dp(1), dp(2), dp(1), dp(2))
        clipChildren = false
        clipToPadding = false
        minimumHeight = dp(if (homeStyle) 58 else 52)

        if (homeStyle) {
            addView(ImageView(context).apply {
                setImageResource(R.drawable.digitalliquid_brand)
                scaleType = ImageView.ScaleType.CENTER_CROP
                contentDescription = "DigitalLiquid"
            }, LayoutParams(dp(38), dp(38)))
        }

        val labels = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(if (homeStyle) dp(10) else 0, 0, dp(6), 0)
        }
        titleView.apply {
            text = title
            textSize = if (homeStyle) 22f else 26f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(if (lightMode) 0xFF171923.toInt() else Color.WHITE)
            includeFontPadding = false
            letterSpacing = -0.025f
            maxLines = 1
        }
        labels.addView(titleView, LayoutParams(-1, -2))
        if (homeStyle) {
            subtitleView.apply {
                text = subtitle
                textSize = 10f
                letterSpacing = 0.14f
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setTextColor(if (lightMode) accentColor else 0xFF9DBEFF.toInt())
                includeFontPadding = false
                maxLines = 1
            }
            labels.addView(subtitleView, LayoutParams(-1, -2).apply { topMargin = dp(4) })
        } else {
            subtitleView.visibility = View.GONE
        }
        addView(labels, LayoutParams(0, -2, 1f))

        // Keep old state field for callers compiled against earlier revisions, but do not show a badge
        // in the top bar: the reference uses only the title and circular search shortcut.
        stateView.text = if (active) "LIVE" else "READY"
        stateView.visibility = View.GONE

        if (homeStyle) {
            addView(actionCircle("➤", "Telegram") {
                openUrl("https://t.me/")
            }, LayoutParams(dp(44), dp(44)).apply { marginStart = dp(6) })
            addView(actionCircle("♪", "TikTok") {
                openUrl("https://www.tiktok.com/")
            }, LayoutParams(dp(44), dp(44)).apply { marginStart = dp(6) })
        }
        addView(actionCircle("⌕", "Tìm kiếm") {
            if (onSearch != null) onSearch.invoke() else onAssistant()
        }, LayoutParams(dp(44), dp(44)).apply { marginStart = dp(6) })
        contentDescription = "Thanh điều hướng DigitalLiquid"
    }

    private fun actionCircle(glyph: String, description: String, action: () -> Unit): View = TextView(context).apply {
        text = glyph
        textSize = if (glyph == "⌕") 29f else 22f
        gravity = Gravity.CENTER
        includeFontPadding = false
        setTextColor(if (lightMode) 0xFF222631.toInt() else 0xFFE9EDFA.toInt())
        background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            if (lightMode) intArrayOf(0xAFFFFFFF.toInt(), 0x76E8EEF7.toInt())
            else intArrayOf(0x36FFFFFF, 0x16FFFFFF)
        ).apply {
            shape = GradientDrawable.OVAL
            setStroke(dp(1), if (lightMode) 0x2293A2BB else 0x2AFFFFFF)
        }
        elevation = dp(2).toFloat()
        isClickable = true
        isFocusable = true
        contentDescription = description
        setOnClickListener {
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            action()
        }
    }

    private fun openUrl(url: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}
