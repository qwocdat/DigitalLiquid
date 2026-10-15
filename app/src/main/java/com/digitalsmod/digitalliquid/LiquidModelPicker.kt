package com.digitalsmod.digitalliquid

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Branded in-app model chooser. It does not expose a platform Spinner popup. */
object LiquidModelPicker {
    fun show(context: Context, currentId: String, onSelected: (String) -> Unit): Dialog {
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val dialog = Dialog(context)
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(21), dp(20), dp(18))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFFFEFFFF.toInt(), 0xFFF0F3FA.toInt())).apply {
                cornerRadius = dp(27).toFloat()
                setStroke(dp(1), 0x558795B1)
            }
        }
        panel.addView(View(context).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(0xFF9A8CF6.toInt(), 0xFF6256BB.toInt())).apply { cornerRadius = dp(4).toFloat() }
        }, LinearLayout.LayoutParams(dp(34), dp(4)).apply { bottomMargin = dp(15) })
        panel.addView(TextView(context).apply {
            text = "Chọn mô hình"
            textSize = 21f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(0xFF202333.toInt())
        })
        panel.addView(TextView(context).apply {
            text = "Chọn cách trả lời phù hợp với nhu cầu của bạn."
            textSize = 12.5f
            setTextColor(0xFF62697A.toInt())
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5); bottomMargin = dp(12) })
        val scroll = ScrollView(context).apply { isFillViewport = false; clipToPadding = false }
        val options = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val all = AiModels.all.toMutableList()
        if (AiModels.all.none { it.id == currentId } && currentId.isNotBlank()) {
            all.add(0, AiModels.Model("Mô hình tùy chỉnh hiện tại", currentId))
        }
        all.forEach { model ->
            val selected = model.id == currentId
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(13), dp(11), dp(13), dp(11))
                background = GradientDrawable().apply {
                    setColor(if (selected) 0xFFECE9FF.toInt() else 0xFFF9FAFD.toInt())
                    cornerRadius = dp(15).toFloat()
                    setStroke(dp(1), if (selected) 0xFF9A8CF6.toInt() else 0x228795B1)
                }
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    dialog.dismiss()
                    onSelected(model.id)
                }
            }
            val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            col.addView(TextView(context).apply {
                text = model.label
                textSize = 13.5f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(0xFF282B3C.toInt())
            })
            col.addView(TextView(context).apply {
                text = when {
                    model.id !in AiModels.all.map { it.id } -> "Lựa chọn riêng đang được lưu"
                    model.id.contains("flash", true) || model.id.contains("mini", true) -> "Nhanh, phù hợp dùng hằng ngày"
                    else -> "Cân bằng giữa chất lượng và độ chi tiết"
                }
                textSize = 10.5f
                setTextColor(0xFF72788A.toInt())
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(3) })
            row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(TextView(context).apply {
                text = if (selected) "✓" else "›"
                textSize = 20f
                gravity = Gravity.CENTER
                setTextColor(if (selected) 0xFF6556BB.toInt() else 0xFF878DA0.toInt())
            }, LinearLayout.LayoutParams(dp(28), dp(34)))
            options.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(7) })
        }
        scroll.addView(options, LinearLayout.LayoutParams(-1, -2))
        panel.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(2) })
        panel.addView(IOSButton(context).apply {
            style("Đóng", false)
            setOnClickListener { dialog.dismiss() }
        }, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(10) })
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        dialog.setContentView(panel)
        dialog.setCancelable(true)
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout((context.resources.displayMetrics.widthPixels * .90f).toInt(), (context.resources.displayMetrics.heightPixels * .78f).toInt())
        }
        return dialog
    }
}
