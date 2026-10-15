package com.digitalsmod.digitalliquid

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.LinearGradient
import android.graphics.drawable.GradientDrawable
import android.widget.LinearLayout
import kotlin.math.min

/** A regular LinearLayout whose surface is drawn through the same pixel shader as the island. */
class LiquidGlassCardLayout(context: Context) : LinearLayout(context) {
    private val glass = LiquidGlassView(context)
    private val shape = Path()
    private val bounds = RectF()
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(0.8f)
    }
    private val lightSurface = Paint(Paint.ANTI_ALIAS_FLAG)
    private val radius = dp(20f)

    init {
        setWillNotDraw(false)
        clipChildren = true
        clipToPadding = true
        background = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            cornerRadius = radius
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        if (width > 0 && height > 0) {
            val p = AppPrefs.get(context)
            val lightMode = p.getBoolean(AppPrefs.LIGHT_MODE, false)
            bounds.set(0f, 0f, width.toFloat(), height.toFloat())
            shape.reset()
            shape.addRoundRect(bounds, radius, radius, Path.Direction.CW)
            val save = canvas.save()
            canvas.clipPath(shape)
            if (lightMode) {
                // A real light palette, not white text placed on the dark glass shader.
                lightSurface.shader = LinearGradient(
                    0f, 0f, width.toFloat(), height.toFloat(),
                    intArrayOf(0xF6FFFFFF.toInt(), 0xE8F0F2FA.toInt(), 0xF1F9FAFF.toInt()),
                    floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
                )
                canvas.drawRoundRect(bounds, radius, radius, lightSurface)
                lightSurface.shader = null
            } else {
                glass.configure(
                    p.getInt(AppPrefs.GLASS_STRENGTH, 68),
                    "graphite",
                    expandedNow = true
                )
                glass.layout(0, 0, width, height)
                glass.draw(canvas)
            }
            super.dispatchDraw(canvas)
            canvas.restoreToCount(save)

            rim.shader = LinearGradient(
                0f, 0f, width.toFloat(), height.toFloat(),
                if (lightMode) intArrayOf(0x85FFFFFF.toInt(), 0x36B99C65, 0x558CA1A8, 0x65FFFFFF)
                else intArrayOf(0x66F8D6A2, 0x16FFFFFF, 0x35B99A67, 0x22FFFFFF),
                floatArrayOf(0f, 0.35f, 0.72f, 1f), Shader.TileMode.CLAMP
            )
            canvas.drawRoundRect(bounds, radius, radius, rim)
            rim.shader = null
        } else {
            super.dispatchDraw(canvas)
        }
    }

    private fun dp(value: Float) = value * resources.displayMetrics.density
}
