package com.digitalsmod.digitalliquid

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.VideoView
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Minimal HyperOS companion: an always-present black pill, an iOS-like tap reaction,
 * a long-press customization panel, and a recording-only live effect. Media playback,
 * Clock, stopwatch, charging, battery and ordinary notifications are deliberately ignored.
 */
class IslandService : Service() {
    private lateinit var windowManager: WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private var islandRoot: FrameLayout? = null
    private var content: LinearLayout? = null
    private var glassView: LiquidGlassView? = null
    private var backgroundHost: FrameLayout? = null
    private var backgroundScrim: View? = null
    private var params: WindowManager.LayoutParams? = null

    private var widthDp = 148
    private var heightDp = 38
    private var topOffsetDp = 6
    private var alignment = "center"
    private var glassStrength = 72
    private var tint = "midnight"
    private var panelOpen = false
    private var screenRecording = false
    private var playPanelIntro = false
    private var lastBackgroundUri: String? = null
    private var panelBrandTitle: TextView? = null
    private var panelCustomizeLabel: TextView? = null
    private var panelUploadButton: TextView? = null
    private var panelTypewriter: Runnable? = null
    private var resizeAnimator: ValueAnimator? = null
    private var recordingPulse: ValueAnimator? = null
    private var downX = 0f
    private var downY = 0f
    private var longPressTriggered = false
    private val touchSlop by lazy { ViewConfiguration.get(this).scaledTouchSlop }

    private val configReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_APPLY_CONFIG) return
            applyPreferences()
            render(animateSize = false)
            updatePosition()
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        val foregroundNotification = buildForegroundNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(42, foregroundNotification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(42, foregroundNotification)
        }
        applyPreferences()
        AppPrefs.get(this).edit().putBoolean(AppPrefs.SERVICE_ENABLED, true).apply()
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(configReceiver, IntentFilter(ACTION_APPLY_CONFIG), Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(configReceiver, IntentFilter(ACTION_APPLY_CONFIG))
            }
        } catch (_: Exception) { }
        showIsland()
        IslandNotificationListener.refreshFromServiceStart()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "DigitalLiquid", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Keeps the DigitalLiquid pill available while enabled."
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL_ID)
        else @Suppress("DEPRECATION") Notification.Builder(this)
        return builder.setContentTitle("DigitalLiquid đang hoạt động")
            .setContentText("Chạm để hiệu ứng viên thuốc · Giữ để tùy chỉnh")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .build()
    }

    private fun applyPreferences() {
        val prefs = AppPrefs.get(this)
        widthDp = prefs.getInt(AppPrefs.WIDTH, 148).coerceIn(126, 330)
        heightDp = prefs.getInt(AppPrefs.HEIGHT, 38).coerceIn(34, 72)
        topOffsetDp = prefs.getInt(AppPrefs.OFFSET_TOP, 6).coerceIn(0, 140)
        alignment = prefs.getString(AppPrefs.ALIGNMENT, "center") ?: "center"
        glassStrength = prefs.getInt(AppPrefs.GLASS_STRENGTH, 72).coerceIn(15, 100)
        tint = prefs.getString(AppPrefs.TINT, "midnight") ?: "midnight"
    }

    @Suppress("DEPRECATION")
    private fun showIsland() {
        if (islandRoot != null) return
        val glass = LiquidGlassView(this).apply {
            configure(glassStrength, tint, expandedNow = false)
            contentDescription = "DigitalLiquid black pill"
        }
        val mediaBackground = FrameLayout(this).apply {
            visibility = View.GONE
            clipChildren = true
            clipToPadding = true
        }
        val scrim = View(this).apply {
            setBackgroundColor(0x70000000)
            visibility = View.GONE
        }
        val innerContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setBackgroundColor(Color.TRANSPARENT)
            isClickable = false
            isFocusable = false
        }
        val root = FrameLayout(this).apply {
            background = roundedBackground(if (panelOpen) 28f else heightDp / 2f, Color.BLACK)
            clipToOutline = false
            clipChildren = true
            clipToPadding = true
            isClickable = true
            isFocusable = false
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            addView(glass, FrameLayout.LayoutParams(-1, -1))
            addView(mediaBackground, FrameLayout.LayoutParams(-1, -1))
            addView(scrim, FrameLayout.LayoutParams(-1, -1))
            addView(innerContent, FrameLayout.LayoutParams(-1, -1))
            setOnTouchListener { view, event -> handlePillTouch(view, event) }
            contentDescription = "DigitalLiquid. Chạm một lần để tạo hiệu ứng; giữ để tùy chỉnh."
        }
        glassView = glass
        backgroundHost = mediaBackground
        backgroundScrim = scrim
        content = innerContent
        islandRoot = root

        val initialParams = WindowManager.LayoutParams(
            targetWidth(),
            targetHeight(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = gravityForAlignment()
            x = horizontalOffset()
            y = dp(topOffsetDp)
            if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= 30) {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                } else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            if (Build.VERSION.SDK_INT >= 30) {
                try { setFitInsetsTypes(0) } catch (_: Exception) { }
            }
        }
        params = initialParams
        try {
            windowManager.addView(root, initialParams)
        } catch (_: Exception) {
            stopSelf()
            return
        }
        render()
    }

    private fun handlePillTouch(view: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                longPressTriggered = false
                handler.removeCallbacks(longPressRunnable)
                handler.postDelayed(longPressRunnable, LONG_PRESS_MS)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (abs(event.rawX - downX) > touchSlop || abs(event.rawY - downY) > touchSlop) {
                    handler.removeCallbacks(longPressRunnable)
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(longPressRunnable)
                if (!longPressTriggered && abs(event.rawX - downX) <= touchSlop * 1.5f && abs(event.rawY - downY) <= touchSlop * 1.5f) {
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    wobblePill()
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPressRunnable)
                return true
            }
        }
        return true
    }

    private val longPressRunnable = Runnable {
        longPressTriggered = true
        islandRoot?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        openCustomizationPanel()
    }

    private fun wobblePill() {
        val root = islandRoot ?: return
        val lp = params ?: return
        if (panelOpen) return
        val reduceMotion = AppPrefs.get(this).getBoolean(AppPrefs.REDUCE_MOTION, false)
        if (reduceMotion) {
            root.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            return
        }
        val baseX = horizontalOffset()
        val distance = dp(5).toFloat()
        glassView?.setMotionBlur(0f)
        ValueAnimator.ofFloat(0f, -distance, distance * 0.82f, -distance * 0.52f, distance * 0.25f, 0f).apply {
            duration = 330L
            interpolator = PathInterpolator(0.2f, 0.82f, 0.25f, 1f)
            addUpdateListener { animator ->
                lp.x = baseX + (animator.animatedValue as Float).roundToInt()
                try { windowManager.updateViewLayout(root, lp) } catch (_: Exception) { }
                val progress = animator.animatedFraction
                glassView?.setMotionBlur(sin(progress * Math.PI).toFloat())
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    lp.x = baseX
                    glassView?.setMotionBlur(0f)
                    try { windowManager.updateViewLayout(root, lp) } catch (_: Exception) { }
                }
            })
            start()
        }
    }

    private fun openCustomizationPanel() {
        if (panelOpen) return
        panelOpen = true
        playPanelIntro = true
        render(animateSize = true)
    }

    private fun closeCustomizationPanel() {
        if (!panelOpen) return
        panelTypewriter?.let { handler.removeCallbacks(it) }
        panelOpen = false
        panelTypewriter = null
        panelBrandTitle = null
        panelCustomizeLabel = null
        panelUploadButton = null
        render(animateSize = true)
    }

    private fun targetWidth(): Int {
        val density = resources.displayMetrics.density.coerceAtLeast(1f)
        val screenWidthPx = resources.displayMetrics.widthPixels.coerceAtLeast(dp(180))
        val screenWidthDp = (screenWidthPx / density).toInt().coerceAtLeast(180)
        val desiredDp = if (panelOpen) screenWidthDp - 24 else if (screenRecording) maxOf(widthDp, 178) else widthDp
        return dp(desiredDp).coerceIn(dp(126), (screenWidthPx - dp(12)).coerceAtLeast(dp(126)))
    }

    private fun targetHeight(): Int = when {
        panelOpen -> dp(250)
        screenRecording -> dp(maxOf(heightDp, 42))
        else -> dp(heightDp)
    }

    private fun gravityForAlignment(): Int = when (alignment) {
        "left" -> Gravity.TOP or Gravity.LEFT
        "right" -> Gravity.TOP or Gravity.RIGHT
        else -> Gravity.TOP or Gravity.CENTER_HORIZONTAL
    }

    private fun horizontalOffset(): Int = if (alignment == "center") 0 else dp(12)

    private fun updatePosition() {
        val root = islandRoot ?: return
        val lp = params ?: return
        lp.width = targetWidth()
        lp.height = targetHeight()
        lp.gravity = gravityForAlignment()
        lp.x = horizontalOffset()
        lp.y = dp(topOffsetDp)
        try { windowManager.updateViewLayout(root, lp) } catch (_: Exception) { }
        root.background = roundedBackground(if (panelOpen) 28f else heightDp / 2f, Color.BLACK)
        glassView?.configure(glassStrength, tint, expandedNow = panelOpen)
    }

    private fun render(animateSize: Boolean = false) {
        val root = islandRoot ?: return
        val rootContent = content ?: return
        val prefs = AppPrefs.get(this)
        resizeAnimator?.cancel()
        rootContent.removeAllViews()
        recordingPulse?.cancel()
        recordingPulse = null
        rootContent.setPadding(dp(if (panelOpen) 18 else 8), dp(if (panelOpen) 15 else 2), dp(if (panelOpen) 18 else 8), dp(if (panelOpen) 14 else 2))
        root.background = roundedBackground(if (panelOpen) 28f else heightDp / 2f, Color.BLACK)
        root.clipToOutline = panelOpen
        glassView?.configure(glassStrength, tint, expandedNow = panelOpen)
        glassView?.visibility = if (panelOpen) View.GONE else View.VISIBLE
        syncPanelBackground()

        if (panelOpen) {
            val runIntro = playPanelIntro
            playPanelIntro = false
            renderCustomizationPanel(rootContent, runIntro)
        } else if (screenRecording) {
            renderRecordingIndicator(rootContent)
        }

        val lp = params
        if (lp != null) {
            if (animateSize) animateToTargetSize()
            else {
                lp.width = targetWidth()
                lp.height = targetHeight()
                lp.gravity = gravityForAlignment()
                lp.x = horizontalOffset()
                lp.y = dp(topOffsetDp)
                try { windowManager.updateViewLayout(root, lp) } catch (_: Exception) { }
            }
        }
    }

    private fun renderRecordingIndicator(target: LinearLayout) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            isClickable = false
        }
        val dotWrap = FrameLayout(this)
        val ring = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.TRANSPARENT)
                setStroke(dp(1), 0x99FF4D5F.toInt())
            }
        }
        val dot = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFFFF4054.toInt())
            }
        }
        dotWrap.addView(ring, FrameLayout.LayoutParams(dp(16), dp(16), Gravity.CENTER))
        dotWrap.addView(dot, FrameLayout.LayoutParams(dp(7), dp(7), Gravity.CENTER))
        row.addView(dotWrap, LinearLayout.LayoutParams(dp(20), dp(22)))
        row.addView(TextView(this).apply {
            text = "ĐANG QUAY MÀN HÌNH"
            textSize = 10.5f
            letterSpacing = 0.06f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_VERTICAL
        }, LinearLayout.LayoutParams(-2, dp(22)))
        target.addView(row, LinearLayout.LayoutParams(-1, -1))

        recordingPulse?.cancel()
        recordingPulse = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 980L
            repeatCount = ValueAnimator.INFINITE
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener { animator ->
                val phase = animator.animatedValue as Float
                val wave = (sin(phase * Math.PI * 2.0).toFloat() + 1f) * 0.5f
                dot.scaleX = 0.78f + wave * 0.22f
                dot.scaleY = 0.78f + wave * 0.22f
                dot.alpha = 0.66f + wave * 0.34f
                ring.scaleX = 0.78f + phase * 0.55f
                ring.scaleY = 0.78f + phase * 0.55f
                ring.alpha = 0.82f * (1f - phase)
            }
            start()
        }
    }

    private fun renderCustomizationPanel(target: LinearLayout, animateIntro: Boolean) {
        val topRow = FrameLayout(this).apply { clipChildren = false }
        val brand = TextView(this).apply {
            text = if (animateIntro) "" else "DigitalLiquid"
            textSize = 24f
            letterSpacing = -0.025f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        topRow.addView(brand, FrameLayout.LayoutParams(-1, dp(44), Gravity.CENTER))
        val close = TextView(this).apply {
            text = "×"
            textSize = 23f
            setTextColor(0xFFE7E7EB.toInt())
            gravity = Gravity.CENTER
            background = roundedBackground(18f, 0x28000000)
            isClickable = true
            setOnClickListener { closeCustomizationPanel() }
        }
        topRow.addView(close, FrameLayout.LayoutParams(dp(34), dp(34), Gravity.RIGHT or Gravity.TOP))
        target.addView(topRow, LinearLayout.LayoutParams(-1, dp(44)))

        val customize = TextView(this).apply {
            text = "Tùy chỉnh"
            textSize = 15f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(0xFFE5E5EB.toInt())
            gravity = Gravity.CENTER
            alpha = if (animateIntro) 0f else 1f
            translationY = if (animateIntro) dp(5).toFloat() else 0f
        }
        target.addView(customize, LinearLayout.LayoutParams(-1, dp(30)))

        val upload = TextView(this).apply {
            text = "Tải ảnh hoặc video từ thiết bị"
            textSize = 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(12), 0, dp(12), 0)
            background = roundedBackground(17f, 0xFF24242A.toInt())
            isClickable = true
            alpha = if (animateIntro) 0f else 1f
            translationY = if (animateIntro) dp(7).toFloat() else 0f
            setOnClickListener { requestBackgroundPicker() }
        }
        val uploadParams = LinearLayout.LayoutParams(-1, dp(48)).apply {
            topMargin = dp(7)
        }
        target.addView(upload, uploadParams)

        val hint = TextView(this).apply {
            text = "Ảnh hoặc video sẽ phủ nền bảng này. Tệp chỉ được đọc từ thiết bị của bạn."
            textSize = 10.5f
            setTextColor(0xFFB9BAC3.toInt())
            gravity = Gravity.CENTER
            maxLines = 2
            alpha = if (animateIntro) 0f else 1f
        }
        target.addView(hint, LinearLayout.LayoutParams(-1, dp(38)))

        panelBrandTitle = brand
        panelCustomizeLabel = customize
        panelUploadButton = upload
        if (animateIntro) startPanelTypewriter(brand, customize, upload, hint)
    }

    private fun startPanelTypewriter(brand: TextView, customize: TextView, upload: TextView, hint: TextView) {
        panelTypewriter?.let { handler.removeCallbacks(it) }
        val label = "DigitalLiquid"
        var index = 0
        val task = object : Runnable {
            override fun run() {
                if (!panelOpen || panelBrandTitle !== brand) return
                index = (index + 1).coerceAtMost(label.length)
                brand.text = label.substring(0, index)
                if (index < label.length) {
                    handler.postDelayed(this, 43L)
                } else {
                    customize.animate().alpha(1f).translationY(0f).setDuration(240L).setInterpolator(PathInterpolator(0.18f, 0.82f, 0.25f, 1f)).start()
                    upload.animate().alpha(1f).translationY(0f).setDuration(280L).setStartDelay(45L).setInterpolator(PathInterpolator(0.18f, 0.82f, 0.25f, 1f)).start()
                    hint.animate().alpha(1f).setDuration(220L).setStartDelay(85L).start()
                }
            }
        }
        panelTypewriter = task
        handler.postDelayed(task, 75L)
    }

    private fun requestBackgroundPicker() {
        try {
            val intent = Intent(this, MainActivity::class.java)
                .putExtra(EXTRA_PICK_PANEL_BACKGROUND, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            startActivity(intent)
        } catch (_: Exception) {
            android.widget.Toast.makeText(this, "Không mở được trình chọn tệp.", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun syncPanelBackground() {
        val host = backgroundHost ?: return
        val scrim = backgroundScrim ?: return
        val prefs = AppPrefs.get(this)
        val uriString = prefs.getString(PANEL_BACKGROUND_URI, null)
        val shouldShow = panelOpen && !uriString.isNullOrBlank()
        if (!shouldShow) {
            if (host.childCount > 0 || lastBackgroundUri != null) {
                (host.getChildAt(0) as? VideoView)?.stopPlayback()
                host.removeAllViews()
                lastBackgroundUri = null
            }
            host.visibility = View.GONE
            scrim.visibility = View.GONE
            return
        }
        host.visibility = View.VISIBLE
        scrim.visibility = View.VISIBLE
        if (lastBackgroundUri == uriString && host.childCount > 0) return
        (host.getChildAt(0) as? VideoView)?.stopPlayback()
        host.removeAllViews()
        lastBackgroundUri = uriString
        val uri = Uri.parse(uriString)
        val mime = try { contentResolver.getType(uri).orEmpty() } catch (_: Exception) { "" }
        if (mime.startsWith("video/", ignoreCase = true)) {
            val video = VideoView(this).apply {
                setBackgroundColor(Color.BLACK)
                setVideoURI(uri)
                setOnPreparedListener { player ->
                    player.isLooping = true
                    try { player.setVolume(0f, 0f) } catch (_: Exception) { }
                    start()
                }
                setOnErrorListener { _, _, _ -> true }
            }
            host.addView(video, FrameLayout.LayoutParams(-1, -1))
        } else {
            val image = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(Color.BLACK)
                contentDescription = "Nền tùy chỉnh DigitalLiquid"
                try { setImageURI(uri) } catch (_: Exception) { }
            }
            host.addView(image, FrameLayout.LayoutParams(-1, -1))
        }
    }

    private fun animateToTargetSize() {
        val root = islandRoot ?: return
        val lp = params ?: return
        resizeAnimator?.cancel()
        val startWidth = lp.width.toFloat()
        val startHeight = lp.height.toFloat()
        val endWidth = targetWidth().toFloat()
        val endHeight = targetHeight().toFloat()
        if (AppPrefs.get(this).getBoolean(AppPrefs.REDUCE_MOTION, false)) {
            lp.width = endWidth.roundToInt()
            lp.height = endHeight.roundToInt()
            root.background = roundedBackground(if (panelOpen) 28f else heightDp / 2f, Color.BLACK)
            try { windowManager.updateViewLayout(root, lp) } catch (_: Exception) { }
            return
        }
        resizeAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (panelOpen) 310L else 260L
            interpolator = PathInterpolator(0.18f, 0.82f, 0.25f, 1f)
            addUpdateListener { animator ->
                val t = animator.animatedValue as Float
                lp.width = (startWidth + (endWidth - startWidth) * t).roundToInt()
                lp.height = (startHeight + (endHeight - startHeight) * t).roundToInt()
                root.background = roundedBackground(
                    if (panelOpen) 28f else (lp.height / resources.displayMetrics.density) / 2f,
                    Color.BLACK
                )
                try { windowManager.updateViewLayout(root, lp) } catch (_: Exception) { }
            }
            start()
        }
    }

    private fun roundedBackground(radiusDp: Float, color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
        cornerRadius = dp(radiusDp.roundToInt()).toFloat()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        instance = null
        AppPrefs.get(this).edit().putBoolean(AppPrefs.SERVICE_ENABLED, false).apply()
        resizeAnimator?.cancel()
        recordingPulse?.cancel()
        panelTypewriter?.let { handler.removeCallbacks(it) }
        (backgroundHost?.getChildAt(0) as? VideoView)?.stopPlayback()
        try { unregisterReceiver(configReceiver) } catch (_: Exception) { }
        islandRoot?.let { try { windowManager.removeView(it) } catch (_: Exception) { } }
        islandRoot = null
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        const val ACTION_APPLY_CONFIG = "com.digitalsmod.digitalliquid.APPLY_CONFIG"
        const val EXTRA_PICK_PANEL_BACKGROUND = "com.digitalsmod.digitalliquid.PICK_PANEL_BACKGROUND"
        const val PANEL_BACKGROUND_URI = "custom_panel_background_uri"
        private const val CHANNEL_ID = "digitalliquid_overlay"
        private const val LONG_PRESS_MS = 430L
        @Volatile private var instance: IslandService? = null

        fun setScreenRecordingActive(active: Boolean) {
            val service = instance ?: return
            service.handler.post {
                if (service.screenRecording == active) return@post
                service.screenRecording = active
                service.render(animateSize = true)
            }
        }

    }
}
