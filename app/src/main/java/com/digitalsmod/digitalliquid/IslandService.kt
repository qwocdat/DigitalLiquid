package com.digitalsmod.digitalliquid

import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.RemoteInput
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
import android.media.session.MediaController
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.SeekBar
import android.widget.Toast
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

class IslandService : Service() {
    private lateinit var windowManager: WindowManager
    private var islandRoot: FrameLayout? = null
    private var content: LinearLayout? = null
    private var glassView: LiquidGlassView? = null
    private var params: WindowManager.LayoutParams? = null
    private val handler = Handler(Looper.getMainLooper())
    private var expanded = false
    private var charging = false
    private var batteryPercent = -1
    private var notificationTitle = ""
    private var notificationText = ""
    private var notificationKey = ""
    private var notificationIntent: PendingIntent? = null
    private var notificationReplyAction: Notification.Action? = null
    private var mediaTitle = ""
    private var mediaNotificationKey = ""
    private var mediaArtist = ""
    private var mediaController: MediaController? = null
    private var timerRemainingMs = 0L
    private var timerEndAt = 0L
    private var timerRunning = false
    private var widthDp = 170
    private var heightDp = 42
    private var topOffsetDp = 8
    private var alignment = "center"
    private var glassStrength = 72
    private var tint = "midnight"
    private var resizeAnimator: ValueAnimator? = null
    private var mediaSeekBar: SeekBar? = null
    private var mediaSeekLabel: TextView? = null

    private val mediaProgressTicker = object : Runnable {
        override fun run() {
            val seek = mediaSeekBar
            val controller = mediaController
            if (expanded && seek != null && controller != null) {
                val duration = controller.metadata?.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION) ?: 0L
                val position = controller.playbackState?.position ?: 0L
                if (duration > 0L && !seek.isPressed) seek.progress = position.coerceIn(0L, duration).toInt()
                if (duration > 0L) mediaSeekLabel?.text = "${formatElapsed(position)} / ${formatElapsed(duration)}"
            }
            handler.postDelayed(this, 1000L)
        }
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
            batteryPercent = if (level >= 0) (level * 100 / scale) else -1
            render()
        }
    }

    private val configReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_APPLY_CONFIG) {
                applyPreferences()
                render()
                updatePosition()
            }
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
            registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        } catch (_: Exception) { }
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(configReceiver, IntentFilter(ACTION_APPLY_CONFIG), Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(configReceiver, IntentFilter(ACTION_APPLY_CONFIG))
            }
        } catch (_: Exception) { }
        showIsland()
        handler.postDelayed(mediaProgressTicker, 1000L)
        IslandNotificationListener.refreshFromServiceStart()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "DigitalLiquid", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Keeps the floating island available while it is enabled."
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
            .setContentText("Chạm để mở cài đặt viên thuốc")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .build()
    }

    private fun applyPreferences() {
        val prefs = AppPrefs.get(this)
        widthDp = prefs.getInt(AppPrefs.WIDTH, 170).coerceIn(140, 300)
        heightDp = prefs.getInt(AppPrefs.HEIGHT, 42).coerceIn(36, 64)
        topOffsetDp = prefs.getInt(AppPrefs.OFFSET_TOP, 8).coerceIn(0, 140)
        alignment = prefs.getString(AppPrefs.ALIGNMENT, "center") ?: "center"
        glassStrength = prefs.getInt(AppPrefs.GLASS_STRENGTH, 72).coerceIn(15, 100)
        tint = prefs.getString(AppPrefs.TINT, "midnight") ?: "midnight"
    }

    @Suppress("DEPRECATION")
    private fun showIsland() {
        if (islandRoot != null) return
        val glass = LiquidGlassView(this).apply {
            configure(glassStrength, tint, expanded)
            contentDescription = "DigitalLiquid liquid glass"
        }
        val innerContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = null
            isClickable = false
        }
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            // The window must be only the pill's bounds; never use a full-screen overlay.
            clipChildren = true
            clipToPadding = true
            isClickable = true
            isFocusable = false
            addView(glass, FrameLayout.LayoutParams(-1, -1))
            addView(innerContent, FrameLayout.LayoutParams(-1, -1))
            setOnClickListener { toggleExpanded() }
            contentDescription = "DigitalLiquid black Dynamic Island. Chạm để mở rộng."
        }
        glassView = glass
        content = innerContent
        islandRoot = root
        params = WindowManager.LayoutParams(
            targetWidth(),
            dp(heightDp),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            // Keep taps on the pill working and let taps outside pass to the app below.
            // Never blur-behind the overlay; the pill shader draws its own dark surface.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = gravityForAlignment()
            x = horizontalOffset()
            y = dp(topOffsetDp)
        }
        try {
            windowManager.addView(root, params)
        } catch (e: Exception) {
            stopSelf()
            return
        }
        render()
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
        glassView?.configure(glassStrength, tint, expanded)
    }

    private fun targetWidth(): Int {
        val screenWidth = resources.displayMetrics.widthPixels.coerceAtLeast(dp(180))
        val minWidth = dp(140)
        val safeMaxWidth = (screenWidth - dp(16)).coerceAtLeast(minWidth)
        val requested = if (expanded) maxOf(widthDp + 56, 276) else widthDp
        return dp(requested).coerceIn(minWidth, safeMaxWidth)
    }

    private fun targetHeight(): Int {
        if (!expanded) return dp(heightDp)
        // Keep the expanded island compact. This is not a full-screen panel.
        return dp(when {
            mediaTitle.isNotBlank() -> 184
            notificationTitle.isNotBlank() -> 144
            timerRunning || timerRemainingMs > 0L -> 144
            else -> 142
        }).coerceAtMost((resources.displayMetrics.heightPixels * 0.30f).toInt().coerceAtLeast(dp(142)))
    }

    private fun render(animateSize: Boolean = false) {
        val rootContent = content ?: return
        val root = islandRoot ?: return
        rootContent.removeAllViews()
        mediaSeekBar = null
        mediaSeekLabel = null
        rootContent.setPadding(dp(12), dp(if (expanded) 9 else 4), dp(12), dp(if (expanded) 8 else 4))
        glassView?.configure(glassStrength, tint, expanded)

        val prefs = AppPrefs.get(this)
        val showMedia = prefs.getBoolean(AppPrefs.SHOW_MEDIA, true)
        val showNotifications = prefs.getBoolean(AppPrefs.SHOW_NOTIFICATIONS, true)
        val showCharging = prefs.getBoolean(AppPrefs.SHOW_CHARGING, true)
        val showBattery = prefs.getBoolean(AppPrefs.SHOW_BATTERY, true)
        val showTimer = prefs.getBoolean(AppPrefs.SHOW_TIMER, true)
        val activeMedia = showMedia && mediaTitle.isNotBlank()
        val activeNotification = showNotifications && notificationTitle.isNotBlank()
        val activeCharging = showCharging && charging

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            setOnClickListener { toggleExpanded() }
        }
        val statusDot = TextView(this).apply {
            text = when {
                activeCharging -> "ϟ"
                activeMedia -> "♫"
                activeNotification -> "•"
                else -> "◉"
            }
            textSize = 17f
            setTextColor(when {
                activeCharging -> 0xFF7EF0B2.toInt()
                activeMedia -> 0xFFB9A2FF.toInt()
                else -> Color.WHITE
            })
            gravity = Gravity.CENTER
        }
        header.addView(statusDot, LinearLayout.LayoutParams(dp(25), dp(28)))
        val heading = TextView(this).apply {
            text = when {
                activeCharging -> "Đang sạc${if (showBattery && batteryPercent >= 0) " · $batteryPercent%" else ""}"
                activeMedia -> mediaTitle
                activeNotification -> notificationTitle
                timerRunning && showTimer -> "Bộ đếm đang chạy"
                showBattery && batteryPercent >= 0 && charging -> "Pin $batteryPercent%"
                else -> "DigitalLiquid"
            }
            setTextColor(Color.WHITE)
            textSize = if (expanded) 14f else 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(heading, LinearLayout.LayoutParams(0, dp(28), 1f))
        if (expanded) {
            val live = TextView(this).apply {
                text = "LIVE"
                textSize = 9f
                letterSpacing = 0.08f
                setTextColor(0xFFD7D9E3.toInt())
                gravity = Gravity.CENTER
                setPadding(dp(7), dp(3), dp(7), dp(3))
                background = pillBackground(0x24FFFFFF, 30f)
            }
            header.addView(live)
        }
        rootContent.addView(header, LinearLayout.LayoutParams(-1, dp(if (expanded) 30 else 32)))

        if (expanded) {
            val secondaryText = when {
                activeMedia -> mediaArtist.ifBlank { "Điều khiển phát nhạc" }
                activeNotification -> notificationText.ifBlank { "Thông báo mới" }
                activeCharging -> "Đã kết nối nguồn điện"
                showTimer && timerRemainingMs > 0L -> formatTimer(timerRemainingMs)
                showBattery && batteryPercent >= 0 -> "Pin hiện tại $batteryPercent%"
                else -> "Kính lỏng · chạm viên thuốc để thu gọn"
            }
            val detail = TextView(this).apply {
                text = secondaryText
                textSize = 12f
                setTextColor(0xFFD2D5DE.toInt())
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), 0, dp(4), dp(4))
            }
            rootContent.addView(detail, LinearLayout.LayoutParams(-1, dp(27)))

            if (activeMedia) {
                val duration = mediaController?.metadata?.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION) ?: 0L
                if (duration > 0L) {
                    val seekRow = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(0, 0, 0, dp(1))
                    }
                    val seek = SeekBar(this).apply {
                        max = duration.coerceAtMost(Int.MAX_VALUE.toLong()).toInt().coerceAtLeast(1)
                        progress = (mediaController?.playbackState?.position ?: 0L).coerceIn(0L, duration).toInt()
                        isFocusable = true
                        contentDescription = "Tua nhạc"
                        setPadding(dp(2), 0, dp(2), 0)
                        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                                if (fromUser) mediaSeekLabel?.text = "${formatElapsed(progress.toLong())} / ${formatElapsed(duration)}"
                            }
                            override fun onStartTrackingTouch(bar: SeekBar?) { }
                            override fun onStopTrackingTouch(bar: SeekBar?) {
                                val position = bar?.progress?.toLong() ?: return
                                safeMediaAction { mediaController?.transportControls?.seekTo(position) }
                            }
                        })
                    }
                    mediaSeekBar = seek
                    seekRow.addView(seek, LinearLayout.LayoutParams(-1, dp(22)))
                    val seekLabel = TextView(this).apply {
                        text = "${formatElapsed(seek.progress.toLong())} / ${formatElapsed(duration)}"
                        textSize = 9f
                        gravity = Gravity.END
                        setTextColor(0xFFB5B8C5.toInt())
                    }
                    mediaSeekLabel = seekLabel
                    seekRow.addView(seekLabel, LinearLayout.LayoutParams(-1, dp(14)))
                    rootContent.addView(seekRow, LinearLayout.LayoutParams(-1, dp(36)))
                }
            }

            val actions = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            if (activeMedia) {
                actions.addView(actionButton("‹‹") { safeMediaAction { mediaController?.transportControls?.skipToPrevious() } }, equalButtonParams())
                val isPlaying = mediaController?.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING
                actions.addView(actionButton(if (isPlaying) "Ⅱ" else "▶") {
                    safeMediaAction {
                        val controller = mediaController ?: return@safeMediaAction
                        if (controller.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING) controller.transportControls.pause()
                        else controller.transportControls.play()
                    }
                }, equalButtonParams())
                actions.addView(actionButton("››") { safeMediaAction { mediaController?.transportControls?.skipToNext() } }, equalButtonParams())
            } else if (showTimer && (timerRunning || timerRemainingMs > 0L)) {
                actions.addView(actionButton(if (timerRunning) "Tạm dừng" else "Tiếp tục") {
                    if (timerRunning) pauseTimer() else resumeTimer()
                    render()
                }, equalButtonParams())
                actions.addView(actionButton("Đặt lại") { resetTimer(); render() }, equalButtonParams())
            } else if (activeNotification) {
                if (notificationReplyAction?.remoteInputs?.any { it.allowFreeFormInput } == true) {
                    actions.addView(actionButton("Trả lời") { openReplyComposer() }, equalButtonParams())
                }
                if (notificationIntent != null) {
                    actions.addView(actionButton("Mở") { openCurrentNotification() }, equalButtonParams())
                }
                if (actions.childCount == 0) {
                    actions.addView(actionButton("Bộ đếm 5 phút") { startTimer(5); render() }, equalButtonParams())
                }
            } else {
                actions.addView(actionButton("Bộ đếm 5 phút") { startTimer(5); render() }, equalButtonParams())
            }
            if (actions.childCount > 0) rootContent.addView(actions, LinearLayout.LayoutParams(-1, dp(38)))
            val bottom = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val state = TextView(this).apply {
                text = when {
                    activeCharging -> "NGUỒN ĐIỆN"
                    activeMedia -> "MEDIA SESSION"
                    activeNotification -> "THÔNG BÁO"
                    showTimer && timerRunning -> "COUNTDOWN"
                    else -> "LIQUID GLASS"
                }
                textSize = 9f
                letterSpacing = 0.07f
                setTextColor(0xFF9298A7.toInt())
            }
            bottom.addView(state, LinearLayout.LayoutParams(0, dp(18), 1f))
            bottom.addView(actionButton("Thu gọn") { expanded = false; render(animateSize = true) }, LinearLayout.LayoutParams(-2, dp(26)))
            rootContent.addView(bottom, LinearLayout.LayoutParams(-1, dp(27)))
        }

        if (animateSize) animateToTargetSize()
        else {
            params?.let { lp ->
                val desiredWidth = targetWidth()
                val desiredHeight = targetHeight()
                if (lp.width != desiredWidth || lp.height != desiredHeight) {
                    lp.width = desiredWidth
                    lp.height = desiredHeight
                    try { windowManager.updateViewLayout(root, lp) } catch (_: Exception) { }
                }
            }
        }
    }

    private fun toggleExpanded() {
        expanded = !expanded
        islandRoot?.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
        render(animateSize = true)
    }

    private fun animateToTargetSize() {
        val root = islandRoot ?: return
        val lp = params ?: return
        resizeAnimator?.cancel()
        val startWidth = lp.width.toFloat()
        val startHeight = lp.height.toFloat()
        val endWidth = targetWidth().toFloat()
        val endHeight = targetHeight().toFloat()
        val reduceMotion = AppPrefs.get(this).getBoolean(AppPrefs.REDUCE_MOTION, false)
        if (reduceMotion) {
            lp.width = endWidth.toInt(); lp.height = endHeight.toInt()
            try { windowManager.updateViewLayout(root, lp) } catch (_: Exception) { }
            glassView?.setPhase(1f)
            return
        }
        resizeAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 360L
            interpolator = OvershootInterpolator(0.72f)
            addUpdateListener { animator ->
                val f = animator.animatedValue as Float
                val maxWidth = (resources.displayMetrics.widthPixels - dp(16)).coerceAtLeast(dp(164))
                val maxHeight = (resources.displayMetrics.heightPixels * 0.30f).toInt().coerceAtLeast(dp(142))
                lp.width = (startWidth + (endWidth - startWidth) * f).toInt().coerceIn(dp(140), maxWidth)
                lp.height = (startHeight + (endHeight - startHeight) * f).toInt().coerceIn(dp(32), maxHeight)
                try { windowManager.updateViewLayout(root, lp) } catch (_: Exception) { }
                glassView?.setPhase(f)
            }
            start()
        }
    }

    private fun equalButtonParams() = LinearLayout.LayoutParams(0, dp(34), 1f).apply {
        marginStart = dp(3); marginEnd = dp(3)
    }

    private fun actionButton(label: String, action: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 11f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        setPadding(dp(7), dp(4), dp(7), dp(4))
        background = pillBackground(0x22FFFFFF, 16f)
        isClickable = true
        isFocusable = false
        setOnClickListener {
            performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            action()
        }
    }

    private fun pillBackground(color: Int, radiusDp: Float) = android.graphics.drawable.GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp.toInt()).toFloat()
        setStroke(dp(1), 0x26FFFFFF)
    }

    private fun safeMediaAction(action: () -> Unit) {
        try { action() } catch (_: Exception) { }
        handler.postDelayed({ render() }, 180)
    }

    private fun openCurrentNotification() {
        try { notificationIntent?.send() } catch (_: Exception) { }
        expanded = false
        render(animateSize = true)
    }

    private fun openReplyComposer() {
        try {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .putExtra(EXTRA_SHOW_REPLY, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) {
            Toast.makeText(this, "Không mở được khung trả lời.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun sendReply(text: String): Boolean {
        val action = notificationReplyAction ?: return false
        val inputs = action.remoteInputs ?: return false
        val targetInput = inputs.firstOrNull { it.allowFreeFormInput } ?: return false
        if (text.isBlank()) return false
        return try {
            val fillIn = Intent()
            val results = android.os.Bundle().apply { putCharSequence(targetInput.resultKey, text) }
            RemoteInput.addResultsToIntent(inputs, fillIn, results)
            action.actionIntent.send(this, 0, fillIn)
            notificationReplyAction = null
            render()
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun startTimer(minutes: Int) {
        timerRemainingMs = minutes.coerceIn(1, 180) * 60_000L
        timerEndAt = System.currentTimeMillis() + timerRemainingMs
        timerRunning = true
        handler.removeCallbacks(timerTick)
        handler.post(timerTick)
    }

    private fun pauseTimer() {
        if (timerRunning) timerRemainingMs = (timerEndAt - System.currentTimeMillis()).coerceAtLeast(0L)
        timerRunning = false
        handler.removeCallbacks(timerTick)
    }

    private fun resumeTimer() {
        if (timerRemainingMs <= 0L) timerRemainingMs = 5 * 60_000L
        timerEndAt = System.currentTimeMillis() + timerRemainingMs
        timerRunning = true
        handler.removeCallbacks(timerTick)
        handler.post(timerTick)
    }

    private fun resetTimer() {
        timerRunning = false
        timerRemainingMs = 0L
        handler.removeCallbacks(timerTick)
    }

    private val timerTick = object : Runnable {
        override fun run() {
            if (!timerRunning) return
            timerRemainingMs = (timerEndAt - System.currentTimeMillis()).coerceAtLeast(0L)
            if (timerRemainingMs <= 0L) {
                timerRunning = false
                try {
                    val manager = getSystemService(NotificationManager::class.java)
                    manager.notify(43, Notification.Builder(this@IslandService, CHANNEL_ID)
                        .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                        .setContentTitle("DigitalLiquid · Hết giờ")
                        .setContentText("Bộ đếm thời gian đã kết thúc.")
                        .setAutoCancel(true).build())
                } catch (_: Exception) { }
            } else {
                handler.postDelayed(this, 1000L)
            }
            render()
        }
    }

    private fun formatTimer(ms: Long): String {
        val total = (ms.coerceAtLeast(0L) / 1000L)
        return String.format(Locale.getDefault(), "%02d:%02d còn lại", total / 60, total % 60)
    }

    private fun formatElapsed(ms: Long): String {
        val total = (ms.coerceAtLeast(0L) / 1000L)
        return String.format(Locale.getDefault(), "%d:%02d", total / 60, total % 60)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        instance = null
        AppPrefs.get(this).edit().putBoolean(AppPrefs.SERVICE_ENABLED, false).apply()
        resizeAnimator?.cancel()
        try { unregisterReceiver(batteryReceiver) } catch (_: Exception) { }
        try { unregisterReceiver(configReceiver) } catch (_: Exception) { }
        islandRoot?.let { try { windowManager.removeView(it) } catch (_: Exception) { } }
        islandRoot = null
        handler.removeCallbacksAndMessages(null)
        mediaSeekBar = null
        mediaSeekLabel = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_APPLY_CONFIG = "com.digitalsmod.digitalliquid.APPLY_CONFIG"
        const val EXTRA_SHOW_REPLY = "com.digitalsmod.digitalliquid.SHOW_REPLY_COMPOSER"
        private const val CHANNEL_ID = "digitalliquid_overlay"
        @Volatile private var instance: IslandService? = null

        fun updateNotification(
            title: String,
            text: String,
            isMedia: Boolean,
            key: String,
            pendingIntent: PendingIntent?,
            controller: MediaController?,
            replyAction: Notification.Action?
        ) {
            val service = instance ?: return
            service.handler.post {
                if (isMedia) {
                    service.mediaNotificationKey = key
                    val metadata = controller?.metadata
                    service.mediaTitle = metadata?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE)
                        ?.takeIf { it.isNotBlank() } ?: title
                    service.mediaArtist = metadata?.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST)
                        ?.takeIf { it.isNotBlank() }
                        ?: metadata?.getString(android.media.MediaMetadata.METADATA_KEY_ALBUM_ARTIST).orEmpty()
                    service.mediaController = controller
                } else {
                    service.notificationTitle = title
                    service.notificationText = text
                    service.notificationKey = key
                    service.notificationIntent = pendingIntent
                    service.notificationReplyAction = replyAction
                }
                service.render()
            }
        }

        fun clearNotification(key: String, isMedia: Boolean) {
            val service = instance ?: return
            service.handler.post {
                if (isMedia && service.mediaNotificationKey == key) {
                    service.mediaTitle = ""
                    service.mediaArtist = ""
                    service.mediaController = null
                    service.mediaNotificationKey = ""
                } else if (service.notificationKey == key) {
                    service.notificationTitle = ""
                    service.notificationText = ""
                    service.notificationIntent = null
                    service.notificationReplyAction = null
                    service.notificationKey = ""
                }
                service.render()
            }
        }

        fun startQuickTimer(minutes: Int) {
            instance?.let { service -> service.handler.post { service.startTimer(minutes); service.render() } }
        }

        fun stopTimer() {
            instance?.let { service -> service.handler.post { service.resetTimer(); service.render() } }
        }

        fun sendQuickReply(text: String): Boolean = instance?.sendReply(text) ?: false
    }
}
