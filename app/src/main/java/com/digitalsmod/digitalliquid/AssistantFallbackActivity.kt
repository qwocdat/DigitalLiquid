package com.digitalsmod.digitalliquid

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Full-screen visual fallback for the Siri-style assistant. This activity is also the reliable
 * in-app entry point when HyperOS has not selected the VoiceInteractionService as default.
 */
class AssistantFallbackActivity : Activity() {
    private val history = mutableListOf<Pair<String, String>>()
    private var input: EditText? = null
    private var answer: TextView? = null
    private var status: TextView? = null
    private var send: TextView? = null
    private var speechButton: TextView? = null
    private var lens: SiriLensEffectView? = null
    private var recognizer: SpeechRecognizer? = null
    private var requestInFlight = false
    private var speechInProgress = false

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(ColorDrawable(0xFF05060A.toInt()))
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        if (Build.VERSION.SDK_INT >= 23) {
            try {
                window.attributes = window.attributes.apply {
                    preferredRefreshRate = AppPrefs.get(this@AssistantFallbackActivity)
                        .getInt(AppPrefs.ISLAND_REFRESH_RATE, 120).toFloat()
                }
            } catch (_: Exception) { }
        }
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                window.isNavigationBarContrastEnforced = false
                window.isStatusBarContrastEnforced = false
            } catch (_: Exception) { }
        }

        val root = FrameLayout(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF05060A.toInt(), 0xFF101827.toInt(), 0xFF08090F.toInt())
            )
            isClickable = false
            clipChildren = false
            clipToPadding = false
        }

        val glow = SiriLensEffectView(this).also { lens = it }
        root.addView(glow, FrameLayout.LayoutParams(-1, dp(220), Gravity.TOP))

        val heroTitle = TextView(this).apply {
            text = "Siri"
            textSize = 34f
            letterSpacing = -0.04f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            includeFontPadding = false
        }
        root.addView(heroTitle, FrameLayout.LayoutParams(-1, dp(42), Gravity.TOP).apply { topMargin = dp(204) })

        val heroSubtitle = TextView(this).apply {
            text = "DIGITALLIQUID · LIQUID INTELLIGENCE"
            textSize = 9.5f
            letterSpacing = 0.18f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(0xFF9EBDFF.toInt())
            gravity = Gravity.CENTER
        }
        root.addView(heroSubtitle, FrameLayout.LayoutParams(-1, dp(24), Gravity.TOP).apply { topMargin = dp(248) })

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(15), dp(16), dp(16))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xF51A1E29.toInt(), 0xF20D111A.toInt())
            ).apply {
                cornerRadius = dp(28).toFloat()
                setStroke(dp(1), 0x42D8E7FF)
            }
            elevation = dp(18).toFloat()
            isClickable = true
            isFocusable = true
            setOnClickListener { /* Keep taps inside the assistant surface from dismissing it. */ }
            clipChildren = false
        }

        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val avatar = TextView(this).apply {
            text = "✦"
            textSize = 21f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF73B8FF.toInt(), 0xFF7667FF.toInt(), 0xFFD17BFF.toInt())
            ).apply { cornerRadius = dp(15).toFloat() }
        }
        header.addView(avatar, LinearLayout.LayoutParams(dp(42), dp(42)))
        val titleColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, dp(6), 0)
        }
        titleColumn.addView(TextView(this).apply {
            text = "DigitalLiquid"
            textSize = 15f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(Color.WHITE)
        })
        titleColumn.addView(TextView(this).apply {
            text = "TRỢ LÝ TRỰC QUAN"
            textSize = 9f
            letterSpacing = 0.12f
            setTextColor(0xFF98A5BB.toInt())
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(3) })
        header.addView(titleColumn, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(assistantButton("×", 38, 38, primary = false) { finish() }, LinearLayout.LayoutParams(dp(38), dp(38)))
        panel.addView(header, LinearLayout.LayoutParams(-1, dp(42)))

        status = TextView(this).apply {
            text = "Hiệu ứng Siri đã sẵn sàng"
            textSize = 11f
            setTextColor(0xFFB6C2D5.toInt())
            setPadding(dp(2), dp(9), dp(2), dp(8))
        }
        panel.addView(status, LinearLayout.LayoutParams(-1, -2))

        answer = TextView(this).apply {
            text = "Mình đang ở đây. Nhập câu hỏi hoặc nhấn Nói để thử nhận dạng giọng nói."
            textSize = 13.5f
            setTextColor(0xFFF4F6FC.toInt())
            setLineSpacing(dp(3).toFloat(), 1.06f)
            setPadding(dp(13), dp(12), dp(13), dp(12))
            background = glassField(0x371E2B41, 17, 0x2BFFFFFF)
            gravity = Gravity.TOP or Gravity.START
        }
        val answerScroll = ScrollView(this).apply {
            isFillViewport = false
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(answer, FrameLayout.LayoutParams(-1, -2))
        }
        panel.addView(answerScroll, LinearLayout.LayoutParams(-1, dp(102)))

        val composer = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        input = EditText(this).apply {
            hint = "Hỏi DigitalLiquid…"
            setHintTextColor(0xFF8793A8.toInt())
            setTextColor(Color.WHITE)
            textSize = 13.5f
            maxLines = 2
            minLines = 1
            gravity = Gravity.CENTER_VERTICAL
            imeOptions = EditorInfo.IME_ACTION_SEND
            setPadding(dp(13), dp(7), dp(13), dp(7))
            background = glassField(0x88212A39.toInt(), 15, 0x66FFFFFF)
            elevation = dp(2).toFloat()
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) { sendPrompt(); true } else false
            }
        }
        composer.addView(input, LinearLayout.LayoutParams(0, dp(47), 1f))
        speechButton = assistantButton("Nói", 50, 44, primary = false) { beginSpeechRecognition() }
        composer.addView(speechButton, LinearLayout.LayoutParams(dp(50), dp(44)).apply { marginStart = dp(7) })
        send = assistantButton("↑", 44, 44, primary = true) { sendPrompt() }
        composer.addView(send, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(7) })
        panel.addView(composer, LinearLayout.LayoutParams(-1, dp(47)).apply { topMargin = dp(10) })

        panel.addView(TextView(this).apply {
            text = "Văn bản và giọng nói · kết nối AI do DigitalLiquid quản lý"
            textSize = 9.5f
            letterSpacing = 0.015f
            gravity = Gravity.CENTER
            setTextColor(0xFF7D8AA0.toInt())
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })

        root.addView(panel, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
            leftMargin = dp(14)
            rightMargin = dp(14)
            bottomMargin = dp(24)
        })
        setContentView(root)

    }

    private fun glassField(color: Int, radiusDp: Int, stroke: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
        setStroke(dp(1), stroke)
    }

    private fun assistantButton(
        label: String,
        widthDp: Int,
        heightDp: Int,
        primary: Boolean,
        action: () -> Unit
    ): TextView = TextView(this).apply {
        text = label
        textSize = if (label == "↑") 23f else 12f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        background = if (primary) GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(0xFF3199FF.toInt(), 0xFF755BFF.toInt(), 0xFFC86DFF.toInt())
        ).apply {
            cornerRadius = dp(15).toFloat()
            setStroke(dp(1), 0x66FFFFFF)
        } else GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(0x3AFFFFFF, 0x1DFFFFFF)
        ).apply {
            cornerRadius = dp(14).toFloat()
            setStroke(dp(1), 0x35FFFFFF)
        }
        isClickable = true
        isFocusable = true
        setOnClickListener { action() }
    }

    private fun beginSpeechRecognition() {
        if (speechInProgress) {
            try { recognizer?.cancel() } catch (_: Exception) { }
            speechInProgress = false
            lens?.setListening(false)
            lens?.setAudioLevel(0f)
            speechButton?.text = "Nói"
            status?.text = "Đã dừng nghe."
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MICROPHONE)
            status?.text = "Cấp quyền micro để dùng Nói."
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            status?.text = "Máy chưa có dịch vụ nhận dạng giọng nói. Hiệu ứng Siri vẫn hoạt động bình thường."
            lens?.setListening(false)
            return
        }
        try {
            recognizer?.destroy()
            recognizer = SpeechRecognizer.createSpeechRecognizer(this).also { speech ->
                speech.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        status?.text = "Đang lắng nghe…"
                        lens?.setListening(true)
                    }
                    override fun onBeginningOfSpeech() { status?.text = "Mình đang nghe đây…" }
                    override fun onRmsChanged(rmsdB: Float) {
                        // Android reports decibels; normalize and let the view smooth the wave itself.
                        lens?.setAudioLevel(((rmsdB + 2f) / 14f).coerceIn(0f, 1f))
                    }
                    override fun onBufferReceived(buffer: ByteArray?) = Unit
                    override fun onEndOfSpeech() {
                        status?.text = "Đang xử lý giọng nói…"
                        speechInProgress = false
                        speechButton?.text = "Nói"
                        lens?.setListening(false)
                        lens?.setAudioLevel(0f)
                    }
                    override fun onError(error: Int) {
                        speechInProgress = false
                        speechButton?.text = "Nói"
                        lens?.setListening(false)
                        lens?.setAudioLevel(0f)
                        status?.text = when (error) {
                            SpeechRecognizer.ERROR_NO_MATCH -> "Chưa nghe rõ. Nhấn Nói và thử lại."
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Chưa phát hiện tiếng nói."
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Chưa có quyền micro."
                            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Dịch vụ nhận dạng không kết nối được mạng."
                            else -> "Không nhận dạng được giọng nói. Bạn có thể nhập câu hỏi."
                        }
                    }
                    override fun onResults(results: Bundle?) {
                        speechInProgress = false
                        speechButton?.text = "Nói"
                        lens?.setListening(false)
                        lens?.setAudioLevel(0f)
                        val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                        if (text.isNotBlank()) {
                            input?.setText(text)
                            input?.setSelection(text.length)
                            status?.text = "Đã nhận dạng · kiểm tra câu hỏi rồi nhấn gửi."
                        }
                    }
                    override fun onPartialResults(partialResults: Bundle?) {
                        val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                        if (!partial.isNullOrBlank()) {
                            input?.setText(partial)
                            input?.setSelection(partial.length)
                        }
                    }
                    override fun onEvent(eventType: Int, params: Bundle?) = Unit
                })
            }
            val speechIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "vi-VN")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "vi-VN")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Đang lắng nghe")
            }
            speechInProgress = true
            speechButton?.text = "Dừng"
            status?.text = "Đang khởi động nhận dạng giọng nói…"
            lens?.setListening(true)
            recognizer?.startListening(speechIntent)
        } catch (_: Exception) {
            speechInProgress = false
            speechButton?.text = "Nói"
            lens?.setListening(false)
            status?.text = "Không khởi động được nhận dạng giọng nói. Bạn vẫn có thể nhập câu hỏi."
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_MICROPHONE) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) beginSpeechRecognition()
            else status?.text = "Chưa cấp quyền micro. Hiệu ứng Siri vẫn dùng được; nhập câu hỏi để tiếp tục."
        }
    }

    private fun sendPrompt() {
        if (requestInFlight) return
        val prompt = input?.text?.toString()?.trim().orEmpty()
        if (prompt.isBlank()) {
            status?.text = "Nhập câu hỏi trước nhé."
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
        lens?.setListening(true)
        OpenRouterClient.chatMessages(this, AiModels.selectedId(this), history.toList()) { result, error ->
            if (isFinishing || isDestroyed) return@chatMessages
            requestInFlight = false
            input?.isEnabled = true
            send?.isEnabled = true
            lens?.setListening(false)
            if (result != null) {
                history.add("assistant" to result)
                answer?.text = history.takeLast(6).joinToString("\n\n") { (role, text) ->
                    (if (role == "user") "BẠN" else "DIGITALLIQUID AI") + "\n" + text
                }
                status?.text = "Đã trả lời · ${AiModels.labelFor(AiModels.selectedId(this))}"
            } else {
                answer?.text = history.takeLast(6).joinToString("\n\n") { (role, text) ->
                    (if (role == "user") "BẠN" else "DIGITALLIQUID AI") + "\n" + text
                }
                status?.text = error ?: "AI chưa phản hồi. Kiểm tra kết nối mạng hoặc cấu hình máy chủ."
            }
        }
    }

    override fun onDestroy() {
        try { recognizer?.cancel(); recognizer?.destroy() } catch (_: Exception) { }
        recognizer = null
        lens = null
        super.onDestroy()
    }

    companion object {
        private const val REQUEST_MICROPHONE = 8122
    }
}
