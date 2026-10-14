package com.digitalsmod.digitalliquid

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.max

/** Compact voice-assistant plate anchored to the physical camera cutout. */
class DigitalLiquidVoiceInteractionSession(private val uiContext: Context) : VoiceInteractionSession(uiContext) {
    private var panel: FrameLayout? = null

    override fun onCreateContentView(): View {
        val card = FrameLayout(uiContext).apply {
            clipChildren = true
            clipToPadding = true
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(Color.TRANSPARENT)
                cornerRadius = dp(34f)
                setStroke(dp(1f), 0x55FFFFFF)
            }
            outlineProvider = ViewOutlineProvider.BACKGROUND
            clipToOutline = true
            elevation = dp(18f)
            isClickable = true
        }

        card.addView(SiriWaveGlassView(uiContext), FrameLayout.LayoutParams(-1, -1))

        val header = LinearLayout(uiContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(15), dp(8), dp(12), 0)
        }
        val statusDot = View(uiContext).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#75B8FF"))
            }
            elevation = dp(2f)
        }
        header.addView(statusDot, LinearLayout.LayoutParams(dp(7), dp(7)))
        val title = TextView(uiContext).apply {
            text = "DIGITALLIQUID"
            textSize = 10f
            letterSpacing = 0.15f
            setTextColor(0xDFFFFFFF.toInt())
            setPadding(dp(7), 0, 0, 0)
        }
        header.addView(title, LinearLayout.LayoutParams(0, dp(24), 1f))
        val close = TextView(uiContext).apply {
            text = "×"
            textSize = 25f
            gravity = Gravity.CENTER
            setTextColor(0xDDFFFFFF.toInt())
            setOnClickListener { hide() }
        }
        header.addView(close, LinearLayout.LayoutParams(dp(30), dp(28)))
        card.addView(header, FrameLayout.LayoutParams(-1, dp(36), Gravity.TOP))

        val footer = LinearLayout(uiContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(12), dp(10))
        }
        val hint = TextView(uiContext).apply {
            text = "Chạm Gemini để trò chuyện"
            textSize = 11f
            setTextColor(0xBBFFFFFF.toInt())
        }
        footer.addView(hint, LinearLayout.LayoutParams(0, dp(36), 1f))
        val gemini = TextView(uiContext).apply {
            text = "Mở Gemini  ↗"
            textSize = 12f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(13), 0, dp(13), 0)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(18f)
                setColor(0x2EFFFFFF)
                setStroke(dp(1f), 0x55FFFFFF)
            }
            isClickable = true
            setOnClickListener { openGemini() }
        }
        footer.addView(gemini, LinearLayout.LayoutParams(-2, dp(34)))
        card.addView(footer, FrameLayout.LayoutParams(-1, dp(47), Gravity.BOTTOM))

        panel = card
        return card
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        applyWindowStyleAndPosition()
        panel?.visibility = View.VISIBLE
    }

    override fun onHide() {
        panel?.visibility = View.GONE
        super.onHide()
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if (keyCode == android.view.KeyEvent.KEYCODE_BACK) {
            hide()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun applyWindowStyleAndPosition() {
        val win = window ?: return
        // Rounded window drawable gives Android's background-blur compositor the same
        // corner mask as the visible pill, so the backdrop cannot show as a rectangle.
        win.setBackgroundDrawable(GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(0x16090D17)
            cornerRadius = dp(34f)
            setStroke(dp(1f), 0x33FFFFFF)
        })
        val attrs = win.attributes
        val panelW = dp(320)
        val panelH = dp(154)
        attrs.width = panelW
        attrs.height = panelH
        attrs.flags = attrs.flags or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        // Keep the surrounding screen undimmed; the card's rounded background blur supplies depth.
        attrs.dimAmount = 0f
        if (Build.VERSION.SDK_INT >= 28) {
            attrs.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }

        val wm = uiContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        var width = uiContext.resources.displayMetrics.widthPixels
        var height = uiContext.resources.displayMetrics.heightPixels
        var cutout: Rect? = null
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                val metrics = wm.currentWindowMetrics
                width = metrics.bounds.width()
                height = metrics.bounds.height()
                cutout = metrics.windowInsets.displayCutout?.boundingRects
                    ?.filterNot { it.isEmpty }
                    ?.maxByOrNull { it.width().toLong() * it.height().toLong() }
            } catch (_: Exception) { }
        }

        val landscape = uiContext.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val sideCutout = landscape && cutout != null &&
            (cutout!!.left <= dp(18) || cutout!!.right >= width - dp(18))
        if (sideCutout) {
            attrs.gravity = Gravity.TOP or Gravity.LEFT
            attrs.x = if (cutout!!.centerX() < width / 2) dp(6) else max(dp(6), width - panelW - dp(6))
            attrs.y = (cutout!!.centerY() - panelH / 2).coerceIn(dp(4), max(dp(4), height - panelH - dp(4)))
        } else {
            attrs.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            attrs.x = 0
            attrs.y = dp(6)
        }
        win.attributes = attrs
        if (Build.VERSION.SDK_INT >= 31) {
            try { win.setBackgroundBlurRadius(dp(24)) } catch (_: Exception) { }
        }
    }

    private fun openGemini() {
        val packageNames = arrayOf("com.google.android.apps.bard", "com.google.android.apps.gemini")
        var launch: Intent? = null
        for (packageName in packageNames) {
            launch = uiContext.packageManager.getLaunchIntentForPackage(packageName)
            if (launch != null) break
        }
        try {
            val target = launch
            if (target != null) {
                target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                uiContext.startActivity(target)
            } else {
                uiContext.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://gemini.google.com/app"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        } catch (_: Exception) {
            try {
                uiContext.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://gemini.google.com/app"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Exception) { }
        }
        hide()
    }

    private fun dp(value: Int) = (value * uiContext.resources.displayMetrics.density).toInt()
    private fun dp(value: Float) = value * uiContext.resources.displayMetrics.density
}
