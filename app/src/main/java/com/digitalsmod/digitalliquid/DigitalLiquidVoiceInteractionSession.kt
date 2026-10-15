package com.digitalsmod.digitalliquid

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.service.voice.VoiceInteractionSession
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import kotlin.math.max
import android.widget.Toast

/** Interactive Siri-inspired assistant session with text and optional speech input. */
class DigitalLiquidVoiceInteractionSession(private val uiContext: Context) : VoiceInteractionSession(uiContext) {
    private var panel: FrameLayout? = null
    private var conversation: TextView? = null
    private var promptInput: EditText? = null
    private var sendButton: TextView? = null
    private var status: TextView? = null
    private var recognizer: SpeechRecognizer? = null
    private val history = mutableListOf<Pair<String, String>>()
    private val main = Handler(Looper.getMainLooper())
    private val busy = false
    private fun dp(v: Int) = (v * uiContext.resources.displayMetrics.density).toInt()

    override fun onCreateContentView(): View {
        val card = FrameLayout(uiContext).apply {
            clipChildren = true
            clipToPadding = true
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(0xF90A0B10.toInt())
                cornerRadius = dp(32).toFloat()
                setStroke(dp(1), 0x55FFFFFF)
            }
            outlineProvider = ViewOutlineProvider.BACKGROUND
            clipToOutline = true
            elevation = dp(18).toFloat()
            isClickable = true
        }
        val wave = SiriWaveGlassView(uiContext).apply { alpha = 0.95f; isClickable = false }
        card.addView(wave, FrameLayout.LayoutParams(-1, dp(152), Gravity.BOTTOM))

        val content = LinearLayout(uiContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(9), dp(14), dp(12))
            background = GradientDrawable().apply {
                setColor(0xD9080A10.toInt())
                cornerRadius = dp(32).toFloat()
            }
        }
        card.addView(content, FrameLayout.LayoutParams(-1, -1))

        val header = LinearLayout(uiContext).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val dot = View(uiContext).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#75B8FF")) }
        }
        header.addView(dot, LinearLayout.LayoutParams(dp(7), dp(7)))
        val titleColumn = LinearLayout(uiContext).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), 0, 0, 0) }
        titleColumn.addView(TextView(uiContext).apply {
            text = "DigitalLiquid AI"
            textSize = 15f
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
        })
        titleColumn.addView(TextView(uiContext).apply {
            text = "Siri mode · OpenRouter"
            textSize = 10f
            setTextColor(0xFFB2B8C8.toInt())
        })
        header.addView(titleColumn, LinearLayout.LayoutParams(0, -2, 1f))
        val close = TextView(uiContext).apply {
            text = "×"
            textSize = 27f
            gravity = Gravity.CENTER
            setTextColor(0xDDFFFFFF.toInt())
            setOnClickListener { hide() }
        }
        header.addView(close, LinearLayout.LayoutParams(dp(32), dp(38)))
        content.addView(header, LinearLayout.LayoutParams(-1, dp(42)))

        val currentModel = AiModels.selectedId(uiContext)
        val modelLabels = AiModels.all.map { it.label }.toMutableList()
        val customModelIndex = if (AiModels.all.none { it.id == currentModel }) {
            modelLabels.add("Tùy chỉnh · $currentModel")
            modelLabels.lastIndex
        } else -1
        val modelSpinner = Spinner(uiContext).apply {
            adapter = ArrayAdapter(uiContext, android.R.layout.simple_spinner_item, modelLabels).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            setSelection(AiModels.all.indexOfFirst { it.id == currentModel }.let { if (it >= 0) it else customModelIndex }, false)
            setBackground(GradientDrawable().apply { setColor(0xFF191C28.toInt()); cornerRadius = dp(12).toFloat() })
            setPadding(dp(7), dp(2), dp(7), dp(2))
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                    AiModels.all.getOrNull(position)?.let { AppPrefs.get(uiContext).edit().putString(AppPrefs.AI_MODEL, it.id).apply() }
                }
            }
        }
        content.addView(modelSpinner, LinearLayout.LayoutParams(-1, dp(39)).apply { topMargin = dp(6) })

        status = TextView(uiContext).apply {
            text = if (AppPrefs.get(uiContext).getString(AppPrefs.AI_ENDPOINT, "").orEmpty().isBlank())
                "Chưa cấu hình AI proxy trong DigitalLiquid → Trợ lý AI."
            else "Sẵn sàng. Nhập câu hỏi hoặc dùng nút Nói."
            textSize = 10.5f
            setTextColor(0xFFD8D9E3.toInt())
            setPadding(dp(2), dp(6), dp(2), dp(4))
        }
        content.addView(status, LinearLayout.LayoutParams(-1, -2))

        val conversationText = TextView(uiContext).apply {
            text = "Chào bạn. Tôi là trợ lý AI của DigitalLiquid."
            textSize = 13f
            setTextColor(0xFFF1F2F8.toInt())
            setLineSpacing(dp(3).toFloat(), 1.08f)
            setPadding(dp(11), dp(10), dp(11), dp(10))
            background = GradientDrawable().apply { setColor(0x55181B28); cornerRadius = dp(15).toFloat(); setStroke(dp(1), 0x22FFFFFF) }
        }
        conversation = conversationText
        val chatScroll = ScrollView(uiContext).apply {
            isFillViewport = true
            clipToPadding = false
            setPadding(0, dp(2), 0, dp(2))
            addView(conversationText, ScrollView.LayoutParams(-1, -2))
        }
        content.addView(chatScroll, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(5) })

        val composer = LinearLayout(uiContext).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val input = EditText(uiContext).apply {
            hint = "Hỏi điều gì đó…"
            setHintTextColor(0xFF818797.toInt())
            setTextColor(Color.WHITE)
            textSize = 13f
            maxLines = 3
            minLines = 1
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(11), dp(8), dp(11), dp(8))
            background = GradientDrawable().apply { setColor(0xFF141722.toInt()); cornerRadius = dp(15).toFloat(); setStroke(dp(1), 0x33FFFFFF) }
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        promptInput = input
        composer.addView(input, LinearLayout.LayoutParams(0, dp(47), 1f))
        val speak = TextView(uiContext).apply {
            text = "Nói"
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(dp(9), 0, dp(9), 0)
            background = GradientDrawable().apply { setColor(0xFF323746.toInt()); cornerRadius = dp(14).toFloat() }
            setOnClickListener { beginSpeechRecognition() }
        }
        composer.addView(speak, LinearLayout.LayoutParams(-2, dp(43)).apply { marginStart = dp(6) })
        val send = TextView(uiContext).apply {
            text = "↑"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply { setColor(0xFF7465D5.toInt()); cornerRadius = dp(14).toFloat() }
            setOnClickListener { sendPrompt() }
        }
        sendButton = send
        composer.addView(send, LinearLayout.LayoutParams(dp(43), dp(43)).apply { marginStart = dp(6) })
        content.addView(composer, LinearLayout.LayoutParams(-1, dp(49)).apply { topMargin = dp(8) })

        panel = card
        return card
    }

    private fun sendPrompt() {
        val input = promptInput ?: return
        val question = input.text?.toString()?.trim().orEmpty()
        if (question.isBlank()) return
        if (!KeyAuthGate.isAuthorizedInCurrentProcess(uiContext)) {
            status?.text = "Cần xác thực lại key trước khi sử dụng trợ lý."
            return
        }
        history.add("user" to question)
        conversation?.text = formatHistory()
        input.setText("")
        input.isEnabled = false
        sendButton?.isEnabled = false
        status?.text = "Đang kết nối OpenRouter…"
        val model = AiModels.selectedId(uiContext)
        OpenRouterClient.chatMessages(uiContext, model, history.toList()) { answer, error ->
            input.isEnabled = true
            sendButton?.isEnabled = true
            if (answer != null) {
                history.add("assistant" to answer)
                conversation?.text = formatHistory()
                status?.text = "Đã trả lời · ${AiModels.labelFor(model)}"
            } else {
                status?.text = error ?: "AI không phản hồi."
                conversation?.text = formatHistory()
            }
        }
    }

    private fun formatHistory(): String = history.takeLast(8).joinToString("\n\n") { (role, text) ->
        (if (role == "user") "BẠN" else "DIGITALLIQUID AI") + "\n" + text
    }.ifBlank { "Chào bạn. Tôi là trợ lý AI của DigitalLiquid." }

    private fun beginSpeechRecognition() {
        if (uiContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            status?.text = "Cần cấp quyền micro trong DigitalLiquid → Cài đặt trước khi dùng Nói."
            Toast.makeText(uiContext, "Cấp quyền micro từ màn hình Cài đặt của DigitalLiquid.", Toast.LENGTH_LONG).show()
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(uiContext)) {
            status?.text = "Dịch vụ nhận dạng giọng nói không khả dụng trên thiết bị này."
            return
        }
        try {
            recognizer?.destroy()
            recognizer = SpeechRecognizer.createSpeechRecognizer(uiContext).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) { status?.text = "Đang nghe…" }
                    override fun onBeginningOfSpeech() = Unit
                    override fun onRmsChanged(rmsdB: Float) = Unit
                    override fun onBufferReceived(buffer: ByteArray?) = Unit
                    override fun onEndOfSpeech() { status?.text = "Đang xử lý giọng nói…" }
                    override fun onError(error: Int) { status?.text = "Không nhận dạng được giọng nói (mã $error)." }
                    override fun onResults(results: Bundle?) {
                        val words = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                        if (!words.isNullOrBlank()) {
                            promptInput?.setText(words)
                            promptInput?.setSelection(words.length)
                            status?.text = "Đã nhận dạng. Nhấn gửi để hỏi AI."
                        }
                    }
                    override fun onPartialResults(partialResults: Bundle?) {
                        val words = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                        if (!words.isNullOrBlank()) promptInput?.setText(words)
                    }
                    override fun onEvent(eventType: Int, params: Bundle?) = Unit
                })
            }
            val recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "vi-VN")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            }
            recognizer?.startListening(recognizerIntent)
        } catch (e: Exception) {
            status?.text = "Không thể mở micro: ${e.message ?: "lỗi hệ thống"}"
        }
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        panel?.visibility = View.GONE
        if (KeyAuthGate.isAuthorizedInCurrentProcess(uiContext)) {
            applyWindowStyleAndPosition()
            panel?.visibility = View.VISIBLE
            return
        }
        // Voice interaction runs in its own system-managed process; require the saved-key path.
        KeyAuthGate.reauthenticateSavedKey(uiContext) { authorized ->
            if (authorized) {
                applyWindowStyleAndPosition()
                panel?.visibility = View.VISIBLE
            } else hide()
        }
    }

    override fun onHide() {
        panel?.visibility = View.GONE
        try { recognizer?.stopListening() } catch (_: Exception) { }
        try { recognizer?.destroy() } catch (_: Exception) { }
        recognizer = null
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
        val win = window?.window ?: return
        win.setBackgroundDrawable(GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(0x08090D15)
            cornerRadius = dp(32).toFloat()
            setStroke(dp(1), 0x33FFFFFF)
        })
        val attrs = win.attributes
        val wm = uiContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        var width = uiContext.resources.displayMetrics.widthPixels
        var height = uiContext.resources.displayMetrics.heightPixels
        var cutout: Rect? = null
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                val metrics = wm.currentWindowMetrics
                width = metrics.bounds.width()
                height = metrics.bounds.height()
                cutout = metrics.windowInsets.displayCutout?.boundingRects?.filterNot { it.isEmpty }
                    ?.maxByOrNull { it.width().toLong() * it.height().toLong() }
            } catch (_: Exception) { }
        }
        val panelW = minOf(dp(360), width - dp(20)).coerceAtLeast(dp(280))
        val panelH = minOf(dp(455), height - dp(24)).coerceAtLeast(dp(300))
        attrs.width = panelW
        attrs.height = panelH
        attrs.flags = attrs.flags or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        attrs.flags = attrs.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND.inv()
        attrs.dimAmount = 0f
        attrs.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
        if (Build.VERSION.SDK_INT >= 28) attrs.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        val landscape = uiContext.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val sideCutout = landscape && cutout != null && (cutout!!.left <= dp(18) || cutout!!.right >= width - dp(18))
        if (sideCutout) {
            attrs.gravity = Gravity.TOP or Gravity.LEFT
            val edgeInset = dp(7)
            attrs.x = if (cutout!!.centerX() < width / 2) edgeInset else max(edgeInset, width - panelW - edgeInset)
            attrs.y = (cutout!!.centerY() - dp(28)).coerceIn(dp(4), max(dp(4), height - panelH - dp(4)))
        } else {
            attrs.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            attrs.x = 0
            attrs.y = dp(4)
        }
        win.attributes = attrs
        win.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        if (Build.VERSION.SDK_INT >= 31) try { win.setBackgroundBlurRadius(dp(28)) } catch (_: Exception) { }
    }

    private fun dpFloat(value: Float) = value * uiContext.resources.displayMetrics.density
}
