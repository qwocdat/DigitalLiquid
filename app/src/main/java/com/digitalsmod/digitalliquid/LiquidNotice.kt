package com.digitalsmod.digitalliquid

import android.app.Activity
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** App-owned notice surface. It does not delegate presentation to Android's Toast UI. */
object LiquidNotice {
    private const val PREFS = "digitalliquid_local_notice_queue"
    private const val KEY_TEXT = "text"
    private const val KEY_DURATION = "duration"
    private const val SHORT_MS = 2_200L
    private const val LONG_MS = 3_600L
    private val main = Handler(Looper.getMainLooper())

    class Notice internal constructor(
        private val context: Context,
        private val text: CharSequence,
        private val duration: Int
    ) {
        fun show() {
            val action = Runnable {
                if (showInActivity()) return@Runnable
                if (showAsOverlay()) return@Runnable
                queueForNextLaunch()
            }
            if (Looper.myLooper() == Looper.getMainLooper()) action.run() else main.post(action)
        }

        private fun showInActivity(): Boolean {
            val activity = context as? Activity ?: return false
            if (activity.isFinishing || activity.isDestroyed) return false
            val root = activity.window.decorView as? ViewGroup ?: return false
            val density = context.resources.displayMetrics.density
            val card = createCard(context, text, density)
            val params = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            ).apply {
                leftMargin = (18 * density).toInt()
                rightMargin = (18 * density).toInt()
                bottomMargin = (74 * density).toInt()
            }
            return try {
                root.addView(card, params)
                animateIn(card)
                root.postDelayed({
                    if (card.parent === root) {
                        card.animate().alpha(0f).translationY((8 * density).toFloat())
                            .setDuration(160L).withEndAction { (card.parent as? ViewGroup)?.removeView(card) }.start()
                    }
                }, timeoutMs())
                true
            } catch (_: Exception) {
                false
            }
        }

        private fun showAsOverlay(): Boolean {
            if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(context)) return false
            val manager = try {
                context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            } catch (_: Exception) {
                return false
            }
            val density = context.resources.displayMetrics.density
            val wrapper = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding((18 * density).toInt(), 0, (18 * density).toInt(), 0)
                background = GradientDrawable().apply { setColor(android.graphics.Color.TRANSPARENT) }
                clipChildren = false
                clipToPadding = false
            }
            val card = createCard(context, text, density)
            wrapper.addView(card, LinearLayout.LayoutParams(-1, -2))
            val type = if (Build.VERSION.SDK_INT >= 26) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                x = 0
                y = (74 * density).toInt()
            }
            return try {
                manager.addView(wrapper, params)
                animateIn(card)
                main.postDelayed({
                    try {
                        card.animate().alpha(0f).translationY((8 * density).toFloat())
                            .setDuration(160L).withEndAction {
                                try { manager.removeView(wrapper) } catch (_: Exception) { }
                            }.start()
                    } catch (_: Exception) {
                        try { manager.removeView(wrapper) } catch (_: Exception) { }
                    }
                }, timeoutMs())
                true
            } catch (_: Exception) {
                false
            }
        }

        private fun queueForNextLaunch() {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_TEXT, text.toString())
                .putInt(KEY_DURATION, duration)
                .apply()
        }

        private fun timeoutMs() = if (duration == Toast.LENGTH_LONG) LONG_MS else SHORT_MS
    }

    fun makeText(context: Context, text: CharSequence, duration: Int): Notice =
        Notice(context, text, duration)

    /** Displays the last queued notice when the user next opens the app. */
    fun showPending(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val text = prefs.getString(KEY_TEXT, null) ?: return
        val duration = prefs.getInt(KEY_DURATION, Toast.LENGTH_SHORT)
        prefs.edit().remove(KEY_TEXT).remove(KEY_DURATION).apply()
        makeText(context, text, duration).show()
    }

    private fun createCard(context: Context, text: CharSequence, density: Float): LinearLayout {
        fun dp(value: Int) = (value * density).toInt()
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(15), dp(11), dp(18), dp(11))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFDFEFFFF.toInt(), 0xF1F1F4FA.toInt())
            ).apply {
                cornerRadius = dp(22).toFloat()
                setStroke(dp(1), 0x558795B1)
            }
            elevation = dp(8).toFloat()
            alpha = 0f
            translationY = dp(8).toFloat()
            contentDescription = text.toString()
        }
        card.addView(View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF7E70E4.toInt())
            }
        }, LinearLayout.LayoutParams(dp(7), dp(7)).apply { marginEnd = dp(11) })
        card.addView(TextView(context).apply {
            this.text = text
            textSize = 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(0xFF242838.toInt())
            maxLines = 4
            setLineSpacing(0f, 1.08f)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return card
    }

    private fun animateIn(view: View) {
        view.animate().alpha(1f).translationY(0f).setDuration(180L)
            .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
    }
}
