package com.digitalsmod.digitalliquid

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.util.WeakHashMap

/** Short app-owned status banner. This intentionally does not use Android's platform Toast UI. */
object LiquidNotice {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val dialogs = WeakHashMap<Activity, Dialog>()
    private val dismissals = WeakHashMap<Activity, Runnable>()

    fun show(activity: Activity, message: String, durationMs: Long = 2600L) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { show(activity, message, durationMs) }
            return
        }
        if (message.isBlank() || activity.isFinishing || activity.isDestroyed) return
        dismissals.remove(activity)?.let { mainHandler.removeCallbacks(it) }
        dialogs.remove(activity)?.let { try { it.dismiss() } catch (_: Exception) { } }

        val density = activity.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val light = AppPrefs.get(activity).getBoolean(AppPrefs.LIGHT_MODE, false)
        val dialog = Dialog(activity)
        val root = FrameLayout(activity).apply {
            setBackgroundColor(Color.TRANSPARENT)
            isClickable = false
        }
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(15), dp(12), dp(17), dp(12))
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                if (light) intArrayOf(0xF8FFFFFF.toInt(), 0xF2ECF5FA.toInt())
                else intArrayOf(0xF5222733.toInt(), 0xF50D111A.toInt())
            ).apply {
                cornerRadius = dp(20).toFloat()
                setStroke(dp(1), if (light) 0x66879EAF else 0x38FFFFFF)
            }
            elevation = dp(14).toFloat()
            alpha = 0f
            translationY = dp(12).toFloat()
            isClickable = true
            isFocusable = false
        }
        card.addView(View(activity).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (light) 0xFF18A999.toInt() else 0xFF67E6CB.toInt())
            }
        }, LinearLayout.LayoutParams(dp(8), dp(8)))
        card.addView(TextView(activity).apply {
            text = message
            textSize = 12.5f
            maxLines = 3
            ellipsize = android.text.TextUtils.TruncateAt.END
            includeFontPadding = false
            setLineSpacing(dp(1).toFloat(), 1.04f)
            setTextColor(if (light) 0xFF253747.toInt() else 0xFFF2F5FA.toInt())
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(11)
        })
        card.setOnClickListener { dialog.dismiss() }
        root.addView(card, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM
        ).apply {
            leftMargin = dp(18)
            rightMargin = dp(18)
            bottomMargin = dp(18)
        })
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)
        dialog.setContentView(root)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
            setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            attributes = attributes.apply { dimAmount = 0f; y = dp(34) }
        }
        try {
            dialog.show()
            dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            card.animate().alpha(1f).translationY(0f).setDuration(180L).start()
            dialogs[activity] = dialog
            val task = Runnable {
                if (dialogs[activity] === dialog) {
                    dialogs.remove(activity)
                    dismissals.remove(activity)
                    try { dialog.dismiss() } catch (_: Exception) { }
                }
            }
            dismissals[activity] = task
            mainHandler.postDelayed(task, durationMs.coerceIn(1200L, 5000L))
            dialog.setOnDismissListener {
                if (dialogs[activity] === dialog) dialogs.remove(activity)
                dismissals.remove(activity)?.let { mainHandler.removeCallbacks(it) }
            }
        } catch (_: Exception) {
            try { dialog.dismiss() } catch (_: Exception) { }
        }
    }
}
