package com.digitalsmod.digitalliquid

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.view.inputmethod.EditorInfo

/** Visual/interactive fallback used when the device has not selected DigitalLiquid as its assistant yet. */
class AssistantFallbackActivity : Activity() {
    private val history = mutableListOf<Pair<String, String>>()
    private var input: EditText? = null
    private var answer: TextView? = null
    private var status: TextView? = null
    private var send: TextView? = null
    private var requestInFlight = false

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
        if (Build.VERSION.SDK_INT >= 28) window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }

        val root = FrameLayout(this).apply {
            setBackgroundColor(0x30000000)
            isClickable = true
            setOnClickListener { finish() }
        }
        val card = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(0xF20B0E18.toInt())
                cornerRadius = dp(30).toFloat()
                setStroke(dp(1), 0x55FFFFFF)
            }
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            clipToOutline = true
            clipChildren = true
            clipToPadding = true
            elevation = dp(18).toFloat()
            isClickable = true
            setOnClickListener { /* Consume touches inside the assistant card. */ }
        }
        val landscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val wave = SiriWaveGlassView(this).apply { alpha = 0.92f; isClickable = false }
        card.addView(wave, FrameLayout.LayoutParams(-1, dp(if (landscape) 78 else 116), Gravity.TOP))

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(9), dp(14), dp(12))
            background = GradientDrawable().apply {
                setColor(0xC90B0E18.toInt())
                cornerRadius = dp(30).toFloat()
            }
        }
        card.addView(content, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val dot = View(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#8B7CFF")) }
        }
        header.addView(dot, LinearLayout.LayoutParams(dp(7), dp(7)))
        val titleColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), 0, 0, 0) }
        titleColumn.addView(TextView(this).apply {
            text = "DigitalLiquid"
            textSize = 15f
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
        })
        titleColumn.addView(TextView(this).apply {
            text = "Trợ lý AI · Siri effect"
            textSize = 10f
            setTextColor(0xFFC4C9D8.toInt())
        })
        header.addView(titleColumn, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(assistantButton("×", 32, 36) { finish() }, LinearLayout.LayoutParams(dp(32), dp(36)))
        content.addView(header, LinearLayout.LayoutParams(-1, dp(40)))

        status = TextView(this).apply {
            text = "Hiệu ứng trợ lý đã sẵn sàng"
            textSize = 11f
            setTextColor(0xFFD9DCE8.toInt())
            setPadding(0, dp(4), 0, dp(4))
        }
        content.addView(status, LinearLayout.LayoutParams(-1, -2))

        answer = TextView(this).apply {
            text = "Tôi đang lắng nghe. Bạn có thể nhập câu hỏi bên dưới."
            textSize = 13f
            setTextColor(0xFFF7F7FC.toInt())
            setLineSpacing(dp(2).toFloat(), 1.04f)
            maxLines = if (landscape) 2 else 4
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = roundedField(0x44161B2B, 16)
        }
        val answerScroll = ScrollView(this).apply {
            isFillViewport = false
            isVerticalScrollBarEnabled = false
            addView(answer, FrameLayout.LayoutParams(-1, -2))
        }
        content.addView(answerScroll, LinearLayout.LayoutParams(-1, dp(if (landscape) 42 else 58)).apply { topMargin = dp(4) })

        val composer = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        input = EditText(this).apply {
            hint = "Hỏi DigitalLiquid…"
            setHintTextColor(0xFF969DB0.toInt())
            setTextColor(Color.WHITE)
            textSize = 13f
            maxLines = 2
            singleLine = false
            imeOptions = EditorInfo.IME_ACTION_SEND
            setPadding(dp(11), dp(7), dp(11), dp(7))
            background = roundedField(0xD9141722.toInt(), 14)
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) { sendPrompt(); true } else false
            }
        }
        composer.addView(input, LinearLayout.LayoutParams(0, dp(43), 1f))
        send = assistantButton("↑", 43, 43) { sendPrompt() }
        composer.addView(send, LinearLayout.LayoutParams(dp(43), dp(43)).apply { marginStart = dp(7) })
        content.addView(composer, LinearLayout.LayoutParams(-1, dp(43)).apply { topMargin = dp(8) })

        val maxWidth = (resources.displayMetrics.widthPixels - dp(20)).coerceAtLeast(dp(1))
        val width = minOf(dp(if (landscape) 520 else 430), maxWidth)
        val topMargin = dp(if (landscape) 8 else 34)
        root.addView(card, FrameLayout.LayoutParams(width, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            setMargins(dp(10), topMargin, dp(10), dp(10))
        })
        setContentView(root)
        if (Build.VERSION.SDK_INT >= 29) {
            try { window.isNavigationBarContrastEnforced = false; window.isStatusBarContrastEnforced = false } catch (_: Exception) { }
        }
    }

    private fun roundedField(color: Int, radiusDp: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
        setStroke(dp(1), 0x28FFFFFF)
    }

    private fun assistantButton(label: String, widthDp: Int, heightDp: Int, action: () -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = if (label == "↑") 22f else 22f
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(if (label == "↑") 0xFF7668DB.toInt() else 0x552F3547)
            cornerRadius = dp(14).toFloat()
            setStroke(dp(1), 0x22FFFFFF)
        }
        isClickable = true
        isFocusable = true
        setOnClickListener { action() }
    }

    private fun sendPrompt() {
        if (requestInFlight) return
        val prompt = input?.text?.toString()?.trim().orEmpty()
        if (prompt.isBlank()) return
        if (!KeyAuthGate.isAuthorizedInCurrentProcess(this)) {
            status?.text = "Cần mở DigitalLiquid và xác thực key trước khi dùng AI."
            return
        }
        requestInFlight = true
        history.add("user" to prompt)
        answer?.text = history.takeLast(6).joinToString("\n\n") { (role, text) ->
            (if (role == "user") "BẠN" else "DIGITALLIQUID AI") + "\n" + text
        }
        input?.setText("")
        input?.isEnabled = false
        send?.isEnabled = false
        status?.text = "Đang xử lý câu hỏi…"
        OpenRouterClient.chatMessages(this, AiModels.selectedId(this), history.toList()) { result, error ->
            if (isFinishing || isDestroyed) return@chatMessages
            requestInFlight = false
            input?.isEnabled = true
            send?.isEnabled = true
            if (result != null) {
                history.add("assistant" to result)
                answer?.text = history.takeLast(6).joinToString("\n\n") { (role, text) ->
                    (if (role == "user") "BẠN" else "DIGITALLIQUID AI") + "\n" + text
                }
                status?.text = "Đã trả lời"
            } else {
                answer?.text = history.takeLast(4).joinToString("\n\n") { (role, text) ->
                    (if (role == "user") "BẠN" else "DIGITALLIQUID AI") + "\n" + text
                }
                status?.text = error ?: "AI chưa phản hồi."
            }
        }
    }
}
