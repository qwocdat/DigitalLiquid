package com.digitalsmod.digitalliquid

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/** Consistent app-owned dialog surface; OS-owned permission screens remain system UI. */
object LiquidDialog {
    data class Action(
        val label: String,
        val primary: Boolean = true,
        val run: (() -> Unit)? = null
    )

    fun show(
        context: Context,
        title: String,
        message: String,
        actions: List<Action> = listOf(Action("Đã hiểu")),
        cancelable: Boolean = true,
        onDismiss: (() -> Unit)? = null
    ): Dialog {
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val dialog = Dialog(context)
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(22), dp(22), dp(18))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFFFEFFFF.toInt(), 0xFFF0F3FA.toInt())
            ).apply {
                cornerRadius = dp(28).toFloat()
                setStroke(dp(1), 0x668795B1)
            }
            elevation = dp(12).toFloat()
        }
        panel.addView(View(context).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(0xFF9A8CF6.toInt(), 0xFF6256BB.toInt())
            ).apply { cornerRadius = dp(4).toFloat() }
        }, LinearLayout.LayoutParams(dp(34), dp(4)).apply { bottomMargin = dp(17) })
        panel.addView(TextView(context).apply {
            text = title
            textSize = 19f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(0xFF202333.toInt())
            includeFontPadding = false
        })
        panel.addView(TextView(context).apply {
            text = message
            textSize = 14f
            setTextColor(0xFF62697A.toInt())
            setLineSpacing(dp(3).toFloat(), 1.04f)
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        actions.take(2).forEachIndexed { index, action ->
            val button = IOSButton(context).apply {
                style(action.label, action.primary)
                setOnClickListener {
                    dialog.dismiss()
                    action.run?.invoke()
                }
            }
            row.addView(button, LinearLayout.LayoutParams(0, dp(46), 1f).apply {
                if (index > 0) marginStart = dp(8)
            })
        }
        if (actions.isNotEmpty()) panel.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20) })
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(panel)
        dialog.setCancelable(cancelable)
        dialog.setCanceledOnTouchOutside(cancelable)
        dialog.setOnDismissListener { onDismiss?.invoke() }
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.22f)
            setLayout((context.resources.displayMetrics.widthPixels * 0.90f).toInt(), WindowManager.LayoutParams.WRAP_CONTENT)
        }
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout((context.resources.displayMetrics.widthPixels * 0.90f).toInt(), WindowManager.LayoutParams.WRAP_CONTENT)
        return dialog
    }
}
