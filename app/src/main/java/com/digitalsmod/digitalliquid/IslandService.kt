package com.digitalsmod.digitalliquid

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.res.Configuration
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Display
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewOutlineProvider
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

class IslandService : Service() {
    private lateinit var windowManager: WindowManager
    private lateinit var displayManager: DisplayManager
    private lateinit var audioManager: AudioManager
    private var usingAccessibilityOverlay = false
    private var overlayStartupAttempts = 0
    private val handler = Handler(Looper.getMainLooper())
    private val keyAuthorizationWatchdog = object : Runnable {
        override fun run() {
            if (!KeyAuthGate.isAuthorizedInCurrentProcess(this@IslandService)) {
                stopSelf()
                return
            }
            handler.postDelayed(this, 30_000L)
        }
    }

    private val onlinePolicyWatchdog = object : Runnable {
        override fun run() {
            if (!KeyAuthGate.isAuthorizedInCurrentProcess(this@IslandService)) {
                stopSelf()
                return
            }
            OnlineControlManager.fetch(this@IslandService) { control, _ ->
                val localVersion = installedVersionCode()
                if (control != null && control.configFound) {
                    if (control.announcementEnabled && control.announcementMessage.isNotBlank()) {
                        postRemoteAnnouncement(control)
                    }
                    if (control.blocksUse(localVersion)) {
                        postRemotePolicyNotice(control, localVersion)
                        AppPrefs.get(this@IslandService).edit().putBoolean(AppPrefs.SERVICE_ENABLED, false).apply()
                        stopSelf()
                        return@fetch
                    }
                }
                handler.postDelayed(this, 90_000L)
            }
        }
    }

    private var islandRoot: FrameLayout? = null
    private var overlayHost: FrameLayout? = null
    private val ghostLayers = mutableListOf<View>()
    private var hostInsetPx = 0
    private var lastRefreshRatePreference = -1
    private var content: LinearLayout? = null
    private var glassView: LiquidGlassView? = null
    private var backgroundHost: FrameLayout? = null
    private var backgroundScrim: View? = null
    private var params: WindowManager.LayoutParams? = null

    // ---- Face ID state -------------------------------------------------------------
    private var faceUnlocking = false
    private var isFaceUnlockShrinking = false
    private var faceFailed = false
    private var faceView: FaceUnlockAnimationView? = null
    private var faceGhostsActive = false
    private var currentMorphProgress = 0f
    private var expandedRadiusDp = 30f

    // ---- Unified Siri Lens state (nhấn 3 lần trực tiếp từ Dynamic Island) ----------
    private var siriActive = false
    private var isSiriShrinking = false
    private var siriView: SiriLensEffectView? = null
    private var siriDropletAnimator: ValueAnimator? = null
    private val siriShrinkSafety = Runnable {
        resizeAnimator?.cancel()
        finishSiriShrink()
    }

    private val rootBackground = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(Color.BLACK)
    }
    private val cornerOutline = object : ViewOutlineProvider() {
        var radiusPx = 0f
        override fun getOutline(view: View, outline: Outline) {
            outline.setRoundRect(0, 0, view.width, view.height, radiusPx)
        }
    }
    private val faceShrinkSafety = Runnable {
        resizeAnimator?.cancel()
        finishFaceUnlockShrink()
    }

    private var widthDp = 148
    private var heightDp = 38
    private var topOffsetDp = 6
    private var alignment = "center"
    private var glassStrength = 72
    private var tint = "midnight"
    private var panelOpen = false
    private var closingPanelWithMedia = false
    
    private var screenRecording = false
    private var notificationScreenRecording = false
    private var accessibilityScreenRecording = false
    private var isVirtualDisplayActive = false
    private var isAudioRecordingActive = false
    
    private var nativeActivityActive = false
    private var mediaPlaybackActive = false
    private var ringerUiActive = false
    private var cameraActive = false
    private var hyperIslandVisibilityOverride: Boolean? = null
    private var idleDot: View? = null
    private var wobbleAnimator: ValueAnimator? = null
    private var panelMediaPlayer: MediaPlayer? = null
    private var panelVideoSurface: Surface? = null
    private var panelTextureView: TextureView? = null
    private var panelMediaGeneration = 0
    private var playPanelIntro = false
    private var lastBackgroundUri: String? = null
    private var panelBrandTitle: TextView? = null
    private var panelTypewriter: Runnable? = null
    private var resizeAnimator: ValueAnimator? = null
    private var recordingPulse: ValueAnimator? = null
    private var recordingTextTypewriter: Runnable? = null
    private var recordingStartedAtElapsed: Long = 0L
    private var recordingTimerTask: Runnable? = null
    private var recordingExpanded = false
    private var customNoticeOverlay: View? = null
    private var customNoticeDismissTask: Runnable? = null
    private var downX = 0f
    private var downY = 0f
    private var longPressTriggered = false
    private var lastTapAt = 0L
    private var tapBurstCount = 0
    private var appSwitchTapCount = 0
    private var lastAppSwitchTapAt = 0L
    private var appSwitchModeActive = false
    private var ringerTransitionHideUntil = 0L
    private var lastGeometrySignature = ""
    private val ringerTransitionHideReset = object : Runnable {
        override fun run() {
            val remaining = ringerTransitionHideUntil - android.os.SystemClock.elapsedRealtime()
            if (remaining > 0L) handler.postDelayed(this, remaining)
            else {
                ringerTransitionHideUntil = 0L
                applyNativeActivityVisibility()
            }
        }
    }
    private val appSwitchModeExitRunnable = Runnable {
        appSwitchModeActive = false
        appSwitchTapCount = 0
        lastAppSwitchTapAt = 0L
        tapBurstCount = 0
        lastTapAt = 0L
    }
    private val geometryRefreshRunnable = Runnable {
        if (isFaceUnlockShrinking || isSiriShrinking || islandRoot == null) return@Runnable
        val signature = "${displayWidthPx()}x${displayHeightPx()}:${resources.configuration.orientation}:${currentCameraCutoutBounds()}"
        if (signature == lastGeometrySignature) return@Runnable
        lastGeometrySignature = signature
        applyPreferences()
        render(animateSize = false)
        updatePosition()
    }
    private val singleTapRunnable = Runnable {
        val prefs = AppPrefs.get(this)
        val appSwitchSequenceInProgress = prefs.getBoolean(AppPrefs.ISLAND_APP_SWITCH_GESTURE, false) && appSwitchTapCount in 2 until APP_SWITCH_START_TAPS
        when {
            panelOpen -> closeCustomizationPanel()
            tapBurstCount == 3 && !appSwitchSequenceInProgress -> toggleSiriFromIsland()
            prefs.getBoolean(AppPrefs.DOUBLE_TAP_ASSISTANT, false) && tapBurstCount == 2 && !appSwitchSequenceInProgress -> openDefaultAssistant()
            else -> wobblePill()
        }
        tapBurstCount = 0
    }
    private val touchSlop by lazy { ViewConfiguration.get(this).scaledTouchSlop }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) { checkVirtualDisplayState() }
        override fun onDisplayRemoved(displayId: Int) { checkVirtualDisplayState() }
        override fun onDisplayChanged(displayId: Int) { checkVirtualDisplayState(); if (displayId == Display.DEFAULT_DISPLAY) scheduleGeometryRefresh() }
    }

    private val audioRecordingCallback = if (Build.VERSION.SDK_INT >= 24) {
        object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: List<AudioRecordingConfiguration>) {
                val isScreenAudioRecording = configs.any { config ->
                    val source = config.clientAudioSource
                    val isAudioSourceRecording = source == MediaRecorder.AudioSource.REMOTE_SUBMIX || source == 8

                    val pkg = if (Build.VERSION.SDK_INT >= 29) {
                        try {
                            val method = config.javaClass.getMethod("getClientPackageName")
                            (method.invoke(config) as? String)?.lowercase().orEmpty()
                        } catch (_: Exception) { "" }
                    } else ""

                    pkg.isNotBlank() && (pkg.contains("screenrecorder") || pkg.contains("screenrecord") || pkg == "com.miui.screenrecorder") && isAudioSourceRecording
                }

                if (isAudioRecordingActive != isScreenAudioRecording) {
                    isAudioRecordingActive = isScreenAudioRecording
                    evaluateRecordingState()
                }
            }
        }
    } else null

    private val miuiScreenRecorderReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "com.miui.screenrecorder.rec.start",
                "com.miui.screenrecorder.START",
                "miui.intent.action.SCREEN_RECORDER_STARTED" -> {
                    isAudioRecordingActive = true
                    evaluateRecordingState()
                }
                "com.miui.screenrecorder.rec.stop",
                "com.miui.screenrecorder.STOP",
                "miui.intent.action.SCREEN_RECORDER_STOPPED" -> {
                    isAudioRecordingActive = false
                    evaluateRecordingState()
                }
            }
        }
    }

    private val soundNotificationTransitionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                AudioManager.RINGER_MODE_CHANGED_ACTION,
                NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED,
                NotificationManager.ACTION_NOTIFICATION_POLICY_CHANGED -> beginRingerTransitionHide()
            }
        }
    }

    private val configReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_APPLY_CONFIG) return
            if (isFaceUnlockShrinking || isSiriShrinking) return
            applyPreferences()
            evaluateRecordingState()
            render(animateSize = false)
            updatePosition()
        }
    }

    private val orientationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_CONFIGURATION_CHANGED) return
            if (isFaceUnlockShrinking || isSiriShrinking) return
            applyPreferences()
            scheduleGeometryRefresh(force = true)
        }
    }

    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> wobblePill()
                Intent.ACTION_SCREEN_OFF -> {
                    abortFaceUnlock()
                    abortSiri()
                }
                Intent.ACTION_USER_PRESENT -> playFaceUnlockAnimation(failed = false)
                ACTION_FACE_UNLOCK_FAILED -> playFaceUnlockAnimation(failed = true)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        if (!KeyAuthGate.isAuthorizedInCurrentProcess(this)) {
            createNotificationChannel()
            val blockedNotification = buildForegroundNotification()
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(42, blockedNotification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(42, blockedNotification)
            }
            stopSelf()
            return
        }
        handler.postDelayed(keyAuthorizationWatchdog, 30_000L)
        instance = this
        
        displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        displayManager.registerDisplayListener(displayListener, handler)

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (Build.VERSION.SDK_INT >= 24 && audioRecordingCallback != null) {
            audioManager.registerAudioRecordingCallback(audioRecordingCallback, handler)
        }

        hyperIslandVisibilityOverride = lastHyperIslandVisibilityOverride
        notificationScreenRecording = lastNotificationScreenRecording
        accessibilityScreenRecording = lastAccessibilityScreenRecording
        nativeActivityActive = lastNativeActivityActive
        mediaPlaybackActive = lastMediaPlaybackActive
        ringerUiActive = lastRingerUiActive
        cameraActive = lastCameraActivityActive
        
        checkVirtualDisplayState()
        handler.postDelayed(onlinePolicyWatchdog, 12_000L)

        refreshOverlayWindowManager()
        createNotificationChannel()
        val foregroundNotification = buildForegroundNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(42, foregroundNotification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(42, foregroundNotification)
        }
        applyPreferences()
        RecentAppSwitcher.warmUp(this)
        AppPrefs.get(this).edit().putBoolean(AppPrefs.SERVICE_ENABLED, true).apply()
        try {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(ACTION_FACE_UNLOCK_FAILED)
            }
            val miuiFilter = IntentFilter().apply {
                addAction("com.miui.screenrecorder.rec.start")
                addAction("com.miui.screenrecorder.rec.stop")
                addAction("com.miui.screenrecorder.START")
                addAction("com.miui.screenrecorder.STOP")
                addAction("miui.intent.action.SCREEN_RECORDER_STARTED")
                addAction("miui.intent.action.SCREEN_RECORDER_STOPPED")
            }
            val modeTransitionFilter = IntentFilter(AudioManager.RINGER_MODE_CHANGED_ACTION).apply {
                if (Build.VERSION.SDK_INT >= 23) {
                    addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
                    addAction(NotificationManager.ACTION_NOTIFICATION_POLICY_CHANGED)
                }
            }

            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(configReceiver, IntentFilter(ACTION_APPLY_CONFIG), Context.RECEIVER_NOT_EXPORTED)
                registerReceiver(orientationReceiver, IntentFilter(Intent.ACTION_CONFIGURATION_CHANGED), Context.RECEIVER_NOT_EXPORTED)
                registerReceiver(unlockReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
                registerReceiver(miuiScreenRecorderReceiver, miuiFilter, Context.RECEIVER_EXPORTED)
                registerReceiver(soundNotificationTransitionReceiver, modeTransitionFilter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(configReceiver, IntentFilter(ACTION_APPLY_CONFIG))
                @Suppress("DEPRECATION")
                registerReceiver(orientationReceiver, IntentFilter(Intent.ACTION_CONFIGURATION_CHANGED))
                registerReceiver(unlockReceiver, filter)
                @Suppress("DEPRECATION")
                registerReceiver(miuiScreenRecorderReceiver, miuiFilter)
                registerReceiver(soundNotificationTransitionReceiver, modeTransitionFilter)
            }
        } catch (_: Exception) { }
        waitForAccessibilityOverlay()
        IslandNotificationListener.refreshFromServiceStart()
    }

    private fun isVirtualDisplay(display: Display): Boolean {
        if (display.displayId == Display.DEFAULT_DISPLAY) return false
        return try {
            val getTypeMethod = display.javaClass.getMethod("getType")
            val typeVal = getTypeMethod.invoke(display) as? Int
            val virtualTypeConst = try {
                Display::class.java.getField("TYPE_VIRTUAL").getInt(null)
            } catch (_: Exception) {
                5
            }
            typeVal == virtualTypeConst || typeVal == 3 || typeVal == 4
        } catch (_: Exception) {
            false
        }
    }

    private fun checkVirtualDisplayState() {
        val displays = displayManager.displays
        val hasVirtual = displays.any { display -> isVirtualDisplay(display) }
        if (isVirtualDisplayActive != hasVirtual) {
            isVirtualDisplayActive = hasVirtual
            evaluateRecordingState()
        }
    }

    private fun evaluateRecordingState() {
        val enabledInPrefs = AppPrefs.get(this).getBoolean(AppPrefs.SCREEN_RECORDING_EFFECT, true)
        val active = enabledInPrefs && (isVirtualDisplayActive || isAudioRecordingActive || accessibilityScreenRecording)

        if (screenRecording != active) {
            screenRecording = active
            if (active) {
                if (recordingStartedAtElapsed == 0L) {
                    recordingStartedAtElapsed = android.os.SystemClock.elapsedRealtime()
                }
            } else {
                recordingStartedAtElapsed = 0L
                recordingExpanded = false
                panelOpen = false
                recordingTimerTask?.let { handler.removeCallbacks(it) }
                recordingTimerTask = null
            }
            render(animateSize = true)
        }
    }

    private fun waitForAccessibilityOverlay() {
        refreshOverlayWindowManager()
        if (usingAccessibilityOverlay) {
            overlayStartupAttempts = 0
            showIsland()
            return
        }
        if (overlayStartupAttempts++ < 8) {
            handler.postDelayed({ waitForAccessibilityOverlay() }, 250L)
        } else {
            showTransientServiceNotice("DigitalLiquid chưa kết nối được lớp phủ Trợ năng. Hãy bật lại quyền Theo dõi HyperIsland rồi thử lại.")
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!KeyAuthGate.isAuthorizedInCurrentProcess(this)) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun scheduleGeometryRefresh(force: Boolean = false) {
        if (isFaceUnlockShrinking || isSiriShrinking) return
        if (force) lastGeometrySignature = ""
        handler.removeCallbacks(geometryRefreshRunnable)
        handler.postDelayed(geometryRefreshRunnable, 90L)
    }

    private fun beginRingerTransitionHide() {
        ringerTransitionHideUntil = android.os.SystemClock.elapsedRealtime() + RINGER_TRANSITION_HIDE_MS
        handler.removeCallbacks(ringerTransitionHideReset)
        handler.postDelayed(ringerTransitionHideReset, RINGER_TRANSITION_HIDE_MS)
        applyNativeActivityVisibility()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (isFaceUnlockShrinking || isSiriShrinking) return
        applyPreferences()
        handler.post {
            if (isFaceUnlockShrinking || isSiriShrinking || islandRoot == null) return@post
            render(animateSize = false)
            updatePosition()
        }
    }

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
        widthDp = prefs.getInt(AppPrefs.WIDTH, 148).coerceIn(72, 330)
        heightDp = prefs.getInt(AppPrefs.HEIGHT, 38).coerceIn(22, 72)
        topOffsetDp = prefs.getInt(AppPrefs.OFFSET_TOP, 0).coerceIn(0, 140)
        alignment = prefs.getString(AppPrefs.ALIGNMENT, "center") ?: "center"
        glassStrength = prefs.getInt(AppPrefs.GLASS_STRENGTH, 72).coerceIn(15, 100)
        tint = prefs.getString(AppPrefs.TINT, "midnight") ?: "midnight"
    }

    private fun isRecordingControlExpanded(): Boolean = panelOpen && screenRecording && recordingExpanded

    private fun targetHostInsetPx(): Int = if (panelOpen || isRecordingControlExpanded() || faceUnlocking || siriActive) 0 else dp(TRAIL_EXTENT_DP)

    private fun targetWindowWidth(): Int = if (isRecordingControlExpanded()) {
        (displayWidthPx() - dp(12)).coerceAtLeast(dp(180))
    } else {
        targetWidth() + targetHostInsetPx() * 2
    }

    private fun windowGravity(): Int = when {
        isRecordingControlExpanded() -> Gravity.TOP or Gravity.CENTER_HORIZONTAL
        isLandscapeWithSideCutout() && !panelOpen && !siriActive -> Gravity.TOP or Gravity.LEFT
        else -> gravityForWindow()
    }

    private fun windowX(): Int {
        if (isRecordingControlExpanded()) return 0
        val inset = targetHostInsetPx()
        val pillX = positionX(targetWidth(), panelOpen || siriActive)
        val gravity = windowGravity()
        return when {
            (gravity and Gravity.HORIZONTAL_GRAVITY_MASK) == Gravity.LEFT -> pillX - inset
            (gravity and Gravity.HORIZONTAL_GRAVITY_MASK) == Gravity.RIGHT -> pillX - inset
            else -> pillX
        }
    }

    private fun windowY(): Int = if (isRecordingControlExpanded()) 0 else positionY(targetHeight(), panelOpen || siriActive)

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    private fun targetMorph(): Float = when {
        siriActive -> 1f
        isSiriShrinking -> 0f
        faceUnlocking -> 1f
        isFaceUnlockShrinking -> 0f
        panelOpen && !isRecordingControlExpanded() -> 1f
        else -> 0f
    }

    private fun updateExpandedRadius() {
        when {
            siriActive || isSiriShrinking -> expandedRadiusDp = SIRI_CORNER_RADIUS_DP
            faceUnlocking -> expandedRadiusDp = FACE_SQUIRCLE_RADIUS_DP
            panelOpen && !isRecordingControlExpanded() -> expandedRadiusDp = 30f
        }
    }

    private fun applyRootCorner(pillW: Int = targetWidth(), pillH: Int = targetHeight()) {
        val root = islandRoot ?: return
        val r = lerp(pillH / 2f, dp(expandedRadiusDp), currentMorphProgress)
            .coerceIn(0f, minOf(pillW, pillH) / 2f)
        val siriMix = if (siriActive || isSiriShrinking) currentMorphProgress.coerceIn(0f, 1f) else 0f
        val bgAlpha = ((1f - siriMix) * 255f).roundToInt().coerceIn(0, 255)
        rootBackground.setColor(Color.argb(bgAlpha, 0, 0, 0))
        rootBackground.cornerRadius = r
        cornerOutline.radiusPx = r
        root.invalidateOutline()
    }

    private fun syncOverlayChildBounds(pillWidth: Int = targetWidth(), pillHeight: Int = targetHeight()) {
        val root = islandRoot ?: return
        val centeredParams = FrameLayout.LayoutParams(pillWidth, pillHeight, Gravity.CENTER)
        root.layoutParams = centeredParams
        if (faceGhostsActive) return
        ghostLayers.forEach { ghost ->
            ghost.layoutParams = FrameLayout.LayoutParams(pillWidth, pillHeight, Gravity.CENTER)
            ghost.background = ghostCapsule(pillHeight / 2f)
            ghost.visibility = if (wobbleAnimator != null && !panelOpen && !faceUnlocking && !isFaceUnlockShrinking && !siriActive && !isSiriShrinking) View.VISIBLE else View.INVISIBLE
        }
    }

    private fun ghostCapsule(radiusPx: Float): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(0xFF080A10.toInt())
        setStroke(dp(1), 0x143B414C)
        cornerRadius = radiusPx
    }

    @Suppress("DEPRECATION")
    private fun applyPreferredRefreshRate(layout: WindowManager.LayoutParams) {
        if (Build.VERSION.SDK_INT < 23) return
        val requested = AppPrefs.get(this).getInt(AppPrefs.ISLAND_REFRESH_RATE, 120).coerceIn(30, 240)
        if (lastRefreshRatePreference == requested && layout.preferredRefreshRate > 0f) return
        try {
            val supported = windowManager.defaultDisplay.supportedModes.toList()
            val selected = supported.minByOrNull { kotlin.math.abs(it.refreshRate - requested.toFloat()) }
            if (selected != null) {
                layout.preferredDisplayModeId = selected.modeId
                layout.preferredRefreshRate = selected.refreshRate
            } else {
                layout.preferredRefreshRate = requested.toFloat()
            }
        } catch (_: Exception) {
            layout.preferredRefreshRate = requested.toFloat()
        }
        lastRefreshRatePreference = requested
    }

    private fun updateOverlayWindow(lp: WindowManager.LayoutParams? = params) {
        val host = overlayHost ?: return
        val layout = lp ?: return
        applyPreferredRefreshRate(layout)
        try { windowManager.updateViewLayout(host, layout) } catch (_: Exception) { }
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
            isClickable = false
            isFocusable = false
            background = roundedBackground(30f, Color.BLACK)
            clipToOutline = true
        }
        val scrim = View(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            visibility = View.GONE
            isClickable = false
            isFocusable = false
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
            background = rootBackground
            outlineProvider = cornerOutline
            clipToOutline = true
            clipChildren = true
            clipToPadding = true
            isClickable = true
            isFocusable = false
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            addView(glass, FrameLayout.LayoutParams(-1, -1))
            addView(mediaBackground, FrameLayout.LayoutParams(-1, -1))
            addView(scrim, FrameLayout.LayoutParams(-1, -1))
            addView(innerContent, FrameLayout.LayoutParams(-1, -1))
            val dot = View(this@IslandService).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0xFFFFD36E.toInt())
                }
                isClickable = false
                isFocusable = false
                elevation = dp(3).toFloat()
                alpha = 0.95f
            }
            addView(dot, FrameLayout.LayoutParams(dp(3), dp(3), Gravity.LEFT or Gravity.TOP).apply {
                leftMargin = dp(99)
                topMargin = dp(17)
            })
            idleDot = dot
            addOnLayoutChangeListener { changedView, _, _, _, _, _, _, _, _ ->
                changedView.invalidateOutline()
                val dotView = idleDot ?: return@addOnLayoutChangeListener
                val dotParams = dotView.layoutParams as? FrameLayout.LayoutParams ?: return@addOnLayoutChangeListener
                val dotWidth = if (dotView.measuredWidth > 0) dotView.measuredWidth else dp(3)
                val dotHeight = if (dotView.measuredHeight > 0) dotView.measuredHeight else dp(3)
                val wantedLeft = ((changedView.width * 0.665f) - dotWidth / 2f).roundToInt()
                    .coerceIn(0, (changedView.width - dotWidth).coerceAtLeast(0))
                val wantedTop = ((changedView.height - dotHeight) / 2f).roundToInt()
                if (dotParams.leftMargin != wantedLeft || dotParams.topMargin != wantedTop ||
                    dotParams.gravity != (Gravity.LEFT or Gravity.TOP)) {
                    dotParams.gravity = Gravity.LEFT or Gravity.TOP
                    dotParams.leftMargin = wantedLeft
                    dotParams.topMargin = wantedTop
                    dotView.layoutParams = dotParams
                }
            }
            setOnTouchListener { view, event -> handlePillTouch(view, event) }
            contentDescription = "DigitalLiquid"
        }
        glassView = glass
        backgroundHost = mediaBackground
        backgroundScrim = scrim
        content = innerContent
        islandRoot = root
        applyRootCorner(targetWidth(), targetHeight())

        val host = FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            clipChildren = false
            clipToPadding = false
            isClickable = false
            isFocusable = false
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
        }
        ghostLayers.clear()
        val ghostBlurDp = floatArrayOf(1.2f, 2.0f, 2.9f, 4.1f, 5.5f, 7.2f, 9.2f)
        val ghostBaseAlpha = floatArrayOf(0.44f, 0.37f, 0.31f, 0.25f, 0.20f, 0.15f, 0.10f)
        for (i in ghostBlurDp.indices) {
            val ghost = View(this).apply {
                background = ghostCapsule(targetHeight() / 2f)
                outlineProvider = ViewOutlineProvider.BACKGROUND
                clipToOutline = true
                alpha = ghostBaseAlpha[i]
                visibility = View.INVISIBLE
                isClickable = false
                isFocusable = false
                if (Build.VERSION.SDK_INT >= 31) {
                    try {
                        setRenderEffect(android.graphics.RenderEffect.createBlurEffect(
                            dp(ghostBlurDp[i]), dp(ghostBlurDp[i] * 0.45f), android.graphics.Shader.TileMode.CLAMP
                        ))
                    } catch (_: Exception) { }
                }
            }
            ghostLayers.add(ghost)
            host.addView(ghost, FrameLayout.LayoutParams(targetWidth(), targetHeight(), Gravity.CENTER))
        }
        host.addView(root, FrameLayout.LayoutParams(targetWidth(), targetHeight(), Gravity.CENTER))
        overlayHost = host
        hostInsetPx = targetHostInsetPx()

        val initialParams = WindowManager.LayoutParams(
            targetWindowWidth(),
            targetHeight(),
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = windowGravity()
            x = windowX()
            y = windowY()
            applyPreferredRefreshRate(this)
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
            windowManager.addView(host, initialParams)
        } catch (_: Exception) {
            showTransientServiceNotice("Android từ chối lớp phủ trên thanh trạng thái. Kiểm tra quyền Trợ năng DigitalLiquid.")
            stopSelf()
            return
        }
        syncOverlayChildBounds(targetWidth(), targetHeight())
        render()
    }

    private fun refreshOverlayWindowManager() {
        val trustedManager = HyperIslandVisibilityService.overlayWindowManager()
        if (trustedManager != null) {
            windowManager = trustedManager
            usingAccessibilityOverlay = true
        } else {
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            usingAccessibilityOverlay = false
        }
    }

    private fun overlayWindowType(): Int {
        check(usingAccessibilityOverlay) { "Accessibility overlay must be connected before showing the pill." }
        return WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
    }

    private fun rebuildOverlayForAccessibilityMode() {
        val root = islandRoot ?: return
        val host = overlayHost ?: return
        val shouldUseTrusted = HyperIslandVisibilityService.overlayWindowManager() != null
        if (!shouldUseTrusted && usingAccessibilityOverlay) {
            stopSelf()
            return
        }
        if (shouldUseTrusted == usingAccessibilityOverlay) return
        try { windowManager.removeView(host) } catch (_: Exception) { }
        stopPanelMedia()
        stopSiriDropletFloatAnimation()
        faceView?.release()
        faceView = null
        siriView?.releaseBackground()
        siriView = null
        siriActive = false
        isSiriShrinking = false
        islandRoot = null
        overlayHost = null
        ghostLayers.clear()
        content = null
        glassView = null
        backgroundHost = null
        backgroundScrim = null
        idleDot = null
        params = null
        refreshOverlayWindowManager()
        if (usingAccessibilityOverlay) showIsland() else stopSelf()
    }

    private fun handlePillTouch(view: View, event: MotionEvent): Boolean {
        if (faceUnlocking) {
            beginFaceUnlockShrink()
            return true
        }
        if (isFaceUnlockShrinking) return true

        // Cử chỉ khi đang mở Siri: Hỗ trợ nhấn hoặc vuốt lên (Swipe Up) để đóng
        if (siriActive) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    val deltaY = downY - event.rawY
                    val deltaX = abs(event.rawX - downX)
                    // Vuốt lên trên ít nhất 24dp hoặc nhả tay chạm nhẹ để đóng
                    if (deltaY > dp(24) && deltaY > deltaX) {
                        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        beginSiriShrink()
                    } else if (abs(deltaY) <= touchSlop * 1.5f && deltaX <= touchSlop * 1.5f) {
                        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        beginSiriShrink()
                    }
                    return true
                }
            }
            return true
        }
        if (isSiriShrinking) return true

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                longPressTriggered = false
                handler.removeCallbacks(longPressRunnable)
                handler.removeCallbacks(singleTapRunnable)
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
                val isTap = abs(event.rawX - downX) <= touchSlop * 1.5f &&
                    abs(event.rawY - downY) <= touchSlop * 1.5f
                if (!longPressTriggered && isTap) {
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    val now = android.os.SystemClock.elapsedRealtime()
                    val prefs = AppPrefs.get(this)
                    val appSwitchEnabled = prefs.getBoolean(AppPrefs.ISLAND_APP_SWITCH_GESTURE, false)

                    if (appSwitchEnabled && appSwitchModeActive) {
                        handler.removeCallbacks(singleTapRunnable)
                        RecentAppSwitcher.switchNext(this)
                        handler.removeCallbacks(appSwitchModeExitRunnable)
                        handler.postDelayed(appSwitchModeExitRunnable, APP_SWITCH_MODE_IDLE_MS)
                        tapBurstCount = 0
                        lastTapAt = now
                        return true
                    }

                    if (appSwitchEnabled) {
                        appSwitchTapCount = if (now - lastAppSwitchTapAt <= APP_SWITCH_SEQUENCE_GAP_MS) appSwitchTapCount + 1 else 1
                        lastAppSwitchTapAt = now
                        if (appSwitchTapCount >= APP_SWITCH_START_TAPS - 1) RecentAppSwitcher.warmUp(this)
                        if (appSwitchTapCount >= APP_SWITCH_START_TAPS) {
                            appSwitchTapCount = 0
                            lastAppSwitchTapAt = 0L
                            appSwitchModeActive = true
                            handler.removeCallbacks(singleTapRunnable)
                            tapBurstCount = 0
                            RecentAppSwitcher.warmUp(this, force = true)
                            handler.removeCallbacks(appSwitchModeExitRunnable)
                            handler.postDelayed(appSwitchModeExitRunnable, APP_SWITCH_MODE_IDLE_MS)
                            return true
                        }
                    } else {
                        appSwitchTapCount = 0
                        lastAppSwitchTapAt = 0L
                    }

                    if (now - lastTapAt <= APP_SWITCH_SEQUENCE_GAP_MS) tapBurstCount++ else tapBurstCount = 1
                    lastTapAt = now
                    handler.removeCallbacks(singleTapRunnable)
                    handler.postDelayed(singleTapRunnable, DOUBLE_TAP_WINDOW_MS)
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPressRunnable)
                handler.removeCallbacks(singleTapRunnable)
                tapBurstCount = 0
                return true
            }
        }
        return true
    }

    private fun toggleSiriFromIsland() {
        if (islandRoot == null || faceUnlocking || isFaceUnlockShrinking) return
        if (siriActive) {
            beginSiriShrink()
            return
        }
        if (isSiriShrinking) return
        if (panelOpen) {
            panelOpen = false
            closingPanelWithMedia = false
        }

        val sWidth = displayWidthPx().toFloat()
        val sHeight = displayHeightPx().toFloat()
        val winX = ((sWidth - targetWidth()) / 2f).coerceAtLeast(0f)
        val winY = overlayTopY().toFloat()

        // Ẩn nhẹ overlay trong 1 khung hình để chụp một bức ảnh sạch tuyệt đối của launcher
        overlayHost?.alpha = 0f
        handler.postDelayed({
            HyperIslandVisibilityService.captureCleanScreen { capturedBmp ->
                overlayHost?.alpha = 1f
                siriView?.setBackgroundSource(capturedBmp, winX, winY, sWidth, sHeight)
            }
        }, 30L)

        siriActive = true
        isSiriShrinking = false
        render(animateSize = true)
        startSiriDropletFloatAnimation()
    }

    /** Chuyển động đung đưa, co giãn bề mặt hữu cơ giống giọt nước lơ lửng */
    private fun startSiriDropletFloatAnimation() {
        stopSiriDropletFloatAnimation()
        val root = islandRoot ?: return
        val animator = ValueAnimator.ofFloat(0f, (Math.PI * 2.0).toFloat()).apply {
            duration = 3800L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                if (!siriActive || isSiriShrinking) return@addUpdateListener
                val t = anim.animatedValue as Float
                // Đung đưa nhẹ translation theo hình vô cực lơ lửng
                root.translationX = (sin(t) * dp(1.8f)).toFloat()
                root.translationY = (cos(t * 1.5f) * dp(1.2f)).toFloat()
                // Co giãn nhịp thở nhẹ nhàng theo sức căng bề mặt chất lỏng
                root.scaleX = 1.0f + 0.012f * sin(t * 2.0f)
                root.scaleY = 1.0f - 0.012f * sin(t * 2.0f)
            }
        }
        siriDropletAnimator = animator
        animator.start()
    }

    private fun stopSiriDropletFloatAnimation() {
        siriDropletAnimator?.cancel()
        siriDropletAnimator = null
        islandRoot?.apply {
            translationX = 0f
            translationY = 0f
            scaleX = 1f
            scaleY = 1f
        }
    }

    private fun beginSiriShrink() {
        if (!siriActive || isSiriShrinking) return
        isSiriShrinking = true
        siriActive = false
        stopSiriDropletFloatAnimation()

        glassView?.apply {
            configure(glassStrength, tint, expandedNow = false)
            alpha = 0f
            visibility = View.VISIBLE
        }
        handler.removeCallbacks(siriShrinkSafety)
        handler.postDelayed(siriShrinkSafety, FACE_SHRINK_SAFETY_MS)
        animateToTargetSize { finishSiriShrink() }
    }

    private fun finishSiriShrink() {
        handler.removeCallbacks(siriShrinkSafety)
        if (!isSiriShrinking) return
        isSiriShrinking = false
        faceGhostsActive = false
        siriView?.releaseBackground()
        siriView = null
        stopSiriDropletFloatAnimation()
        currentMorphProgress = 0f
        glassView?.alpha = 1f
        applyPreferences()
        render(animateSize = false)
        updatePosition()
    }

    private fun abortSiri() {
        if (!siriActive && !isSiriShrinking) return
        handler.removeCallbacks(siriShrinkSafety)
        resizeAnimator?.cancel()
        stopSiriDropletFloatAnimation()
        siriView?.releaseBackground()
        siriView = null
        siriActive = false
        isSiriShrinking = false
        faceGhostsActive = false
        currentMorphProgress = 0f
        render(animateSize = false)
    }

    private fun openDefaultAssistant() {
        val roleHeld = if (Build.VERSION.SDK_INT >= 29) {
            try {
                val manager = getSystemService(android.app.role.RoleManager::class.java)
                manager != null && manager.isRoleAvailable(android.app.role.RoleManager.ROLE_ASSISTANT) &&
                    manager.isRoleHeld(android.app.role.RoleManager.ROLE_ASSISTANT)
            } catch (_: Exception) { false }
        } else {
            try {
                val selected = android.provider.Settings.Secure.getString(contentResolver, "voice_interaction_service").orEmpty()
                selected.contains("DigitalLiquidVoiceInteractionService", ignoreCase = true)
            } catch (_: Exception) { false }
        }
        val launch = if (roleHeld) {
            Intent(Intent.ACTION_ASSIST).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } else {
            Intent(this, AssistantFallbackActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(launch)
        } catch (_: Exception) {
            try { startActivity(Intent(this, AssistantFallbackActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            catch (_: Exception) { showTransientServiceNotice("Không thể mở giao diện trợ lý trên thiết bị này.") }
        }
    }

    private val longPressRunnable = Runnable {
        handler.removeCallbacks(singleTapRunnable)
        if (faceUnlocking || isFaceUnlockShrinking || siriActive || isSiriShrinking) return@Runnable
        longPressTriggered = true
        islandRoot?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        if (screenRecording) {
            recordingExpanded = !recordingExpanded
            panelOpen = recordingExpanded
            playPanelIntro = false
            render(animateSize = true)
        } else if (panelOpen) closeCustomizationPanel() else openCustomizationPanel()
    }

    private fun wobblePill() {
        val root = islandRoot ?: return
        if (panelOpen || faceUnlocking || isFaceUnlockShrinking || siriActive || isSiriShrinking) return
        val motionPrefs = AppPrefs.get(this)
        if (motionPrefs.getBoolean(AppPrefs.REDUCE_MOTION, false) ||
            !motionPrefs.getBoolean(AppPrefs.TAP_WOBBLE, true)) {
            root.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            return
        }

        val previous = wobbleAnimator
        val wasRunning = previous?.isRunning == true
        val startOffset = if (wasRunning) root.translationX else 0f
        val startOffsetY = if (wasRunning) root.translationY else 0f
        val startGhostX = ghostLayers.map { if (wasRunning) it.translationX else 0f }
        val startGhostY = ghostLayers.map { if (wasRunning) it.translationY else 0f }
        val baseAlphas = floatArrayOf(0.35f, 0.28f, 0.20f, 0.14f, 0.09f, 0.05f, 0.03f)
        val startGhostAlpha = ghostLayers.mapIndexed { i, ghost ->
            if (wasRunning) ghost.alpha else baseAlphas[i]
        }
        if (previous != null) {
            previous.removeAllUpdateListeners()
            previous.removeAllListeners()
            previous.cancel()
        }
        wobbleAnimator = null
        if (!wasRunning) {
            root.translationX = 0f
            root.translationY = 0f
        }

        val distance = dp(5.5f + Random.nextFloat() * 2f)
        val swingDirection = if (Random.nextBoolean()) -1f else 1f
        val lags = floatArrayOf(0.012f, 0.024f, 0.036f, 0.048f, 0.060f, 0.072f, 0.084f)
        val ghostAlphaOrder = baseAlphas
        ghostLayers.forEachIndexed { index, ghost ->
            if (!wasRunning) {
                ghost.translationX = 0f
                ghost.translationY = 0f
                ghost.alpha = ghostAlphaOrder[index]
            }
            ghost.visibility = View.VISIBLE
        }
        glassView?.setMotionBlur(0f, swingDirection)

        fun smooth(progress: Float): Float {
            val t = progress.coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }
        fun waveAt(progress: Float): Float {
            val t = progress.coerceIn(0f, 1f)
            val settle = if (t < 0.66f) 1f else {
                val u = ((t - 0.66f) / 0.34f).coerceIn(0f, 1f)
                1f - u * u * (3f - 2f * u)
            }
            val envelope = kotlin.math.exp(-1.0f * t) * settle
            val phase = t * Math.PI.toFloat() * 4f
            return swingDirection * distance * envelope * kotlin.math.sin(phase)
        }
        fun offsetAt(progress: Float): Float {
            val t = progress.coerceIn(0f, 1f)
            return startOffset * (1f - smooth(t)) + waveAt(t)
        }

        var lastOffset = startOffset
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 460L
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener { frame ->
                val t = frame.animatedValue as Float
                val offset = offsetAt(t)
                root.translationX = offset
                root.translationY = startOffsetY * (1f - smooth(t)) +
                    (kotlin.math.abs(waveAt(t)) / distance.coerceAtLeast(1f)) * dp(0.3f) * kotlin.math.sin(t * Math.PI.toFloat() * 2f)
                ghostLayers.forEachIndexed { index, ghost ->
                    val delayed = waveAt((t - lags[index]).coerceAtLeast(0f))
                    ghost.translationX = startGhostX[index] * (1f - smooth(t)) + delayed
                    ghost.translationY = startGhostY[index] * (1f - smooth(t)) +
                        (index + 1) * dp(0.08f) * (1f - smooth(t))
                    val tailFade = if (t < 0.62f) 1f else (1f - (t - 0.62f) / 0.38f).coerceIn(0f, 1f)
                    ghost.alpha = startGhostAlpha[index] * (1f - smooth(t)) +
                        ghostAlphaOrder[index] * tailFade * smooth(t)
                }
                val travel = kotlin.math.abs(offset - lastOffset)
                val direction = if (offset < lastOffset) -1f else 1f
                val normalizedVelocity = (travel / dp(3.5f).coerceAtLeast(1f)).coerceIn(0f, 1f)
                val blurAllowed = AppPrefs.get(this@IslandService).getBoolean(AppPrefs.MOTION_BLUR, true)
                glassView?.setMotionBlur(if (blurAllowed) normalizedVelocity * 0.55f else 0f, direction)
                lastOffset = offset
            }
        }
        wobbleAnimator = animator
        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (wobbleAnimator !== animator) return
                root.translationX = 0f
                root.translationY = 0f
                root.scaleX = 1f
                root.scaleY = 1f
                ghostLayers.forEach { ghost ->
                    ghost.translationX = 0f
                    ghost.translationY = 0f
                    ghost.alpha = 0f
                    ghost.visibility = View.INVISIBLE
                }
                glassView?.setMotionBlur(0f, 1f)
                wobbleAnimator = null
            }
        })
        animator.start()
    }

    private fun resetWobble() {
        val animator = wobbleAnimator
        wobbleAnimator = null
        if (animator != null) {
            animator.removeAllUpdateListeners()
            animator.removeAllListeners()
            animator.cancel()
        }
        islandRoot?.let { it.translationX = 0f; it.translationY = 0f; it.scaleX = 1f; it.scaleY = 1f }
        ghostLayers.forEach { ghost ->
            ghost.translationX = 0f
            ghost.translationY = 0f
            ghost.alpha = 0f
            ghost.visibility = View.INVISIBLE
        }
        glassView?.setMotionBlur(0f, 1f)
    }

    private fun openCustomizationPanel() {
        if (panelOpen || faceUnlocking || isFaceUnlockShrinking || siriActive || isSiriShrinking) return
        closingPanelWithMedia = false
        panelOpen = true
        playPanelIntro = true
        render(animateSize = true)
    }

    private fun closeCustomizationPanel() {
        if (!panelOpen) return
        closingPanelWithMedia = !AppPrefs.get(this).getString(PANEL_BACKGROUND_URI, null).isNullOrBlank()
        panelTypewriter?.let { handler.removeCallbacks(it) }
        panelOpen = false
        panelTypewriter = null
        panelBrandTitle = null
        render(animateSize = true)
    }

    private fun targetWidth(): Int {
        val density = resources.displayMetrics.density.coerceAtLeast(1f)
        val screenWidthPx = displayWidthPx().coerceAtLeast(dp(180))
        val screenWidthDp = (screenWidthPx / density).toInt().coerceAtLeast(180)
        if (siriActive) return dp(SIRI_WIDTH_DP)
        if (faceUnlocking) return dp(FACE_SIZE_DP)
        if (isRecordingControlExpanded()) return (screenWidthPx - dp(12)).coerceAtLeast(dp(180))
        val desiredDp = when {
            panelOpen && screenRecording -> 238
            panelOpen -> screenWidthDp - 24
            screenRecording -> maxOf(widthDp, 178)
            else -> widthDp
        }
        val maxWidthPx = (screenWidthPx - dp(12)).coerceAtLeast(dp(36))
        return dp(desiredDp).coerceIn(dp(72).coerceAtMost(maxWidthPx), maxWidthPx)
    }

    private fun targetHeight(): Int = when {
        siriActive -> dp(SIRI_HEIGHT_DP)
        faceUnlocking -> dp(FACE_SIZE_DP)
        isRecordingControlExpanded() -> maxOf(statusBarHeightPx() + dp(24), dp(50))
        panelOpen && screenRecording -> dp(66)
        panelOpen -> dp(252)
        screenRecording -> dp(maxOf(heightDp, 42))
        else -> dp(heightDp)
    }

    private fun gravityForAlignment(): Int = when (alignment) {
        "left" -> Gravity.TOP or Gravity.LEFT
        "right" -> Gravity.TOP or Gravity.RIGHT
        else -> Gravity.TOP or Gravity.CENTER_HORIZONTAL
    }

    private fun gravityForWindow(): Int = Gravity.TOP or Gravity.CENTER_HORIZONTAL

    private fun horizontalOffset(): Int = 0

    private fun displayWidthPx(): Int = if (Build.VERSION.SDK_INT >= 30) {
        try { windowManager.currentWindowMetrics.bounds.width() } catch (_: Exception) { resources.displayMetrics.widthPixels }
    } else resources.displayMetrics.widthPixels

    private fun displayHeightPx(): Int = if (Build.VERSION.SDK_INT >= 30) {
        try { windowManager.currentWindowMetrics.bounds.height() } catch (_: Exception) { resources.displayMetrics.heightPixels }
    } else resources.displayMetrics.heightPixels

    private fun currentCameraCutoutBounds(): Rect? {
        fun largest(rects: List<Rect>?): Rect? = rects
            ?.asSequence()
            ?.filterNot { it.isEmpty }
            ?.maxByOrNull { it.width().toLong() * it.height().toLong() }
            ?.let { Rect(it) }

        if (Build.VERSION.SDK_INT >= 30) {
            val metricsCutout = try {
                largest(windowManager.currentWindowMetrics.windowInsets.displayCutout?.boundingRects)
            } catch (_: Exception) { null }
            if (metricsCutout != null) return metricsCutout
        }
        if (Build.VERSION.SDK_INT >= 28) {
            val viewCutout = try {
                largest(overlayHost?.rootWindowInsets?.displayCutout?.boundingRects)
                    ?: largest(islandRoot?.rootWindowInsets?.displayCutout?.boundingRects)
            } catch (_: Exception) { null }
            if (viewCutout != null) return viewCutout
        }
        return null
    }

    private fun isLandscapeWithSideCutout(): Boolean {
        if (resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE) return false
        val rect = currentCameraCutoutBounds() ?: return false
        val edgeTolerance = dp(18)
        val screenWidth = displayWidthPx()
        return rect.left <= edgeTolerance || rect.right >= screenWidth - edgeTolerance
    }

    private fun isPortraitWithTopCutout(): Boolean {
        if (resources.configuration.orientation != Configuration.ORIENTATION_PORTRAIT) return false
        val rect = currentCameraCutoutBounds() ?: return false
        return rect.top <= dp(18) && rect.centerY() <= dp(90)
    }

    private fun positionX(windowWidth: Int = targetWidth(), expanded: Boolean = panelOpen): Int {
        if (isRecordingControlExpanded()) return 0
        if (isLandscapeWithSideCutout() && !panelOpen && !siriActive) {
            val cutout = currentCameraCutoutBounds()
            if (cutout != null) {
                val edgeInset = dp(12)
                return if (cutout.centerX() < displayWidthPx() / 2) edgeInset
                else (displayWidthPx() - windowWidth - edgeInset).coerceAtLeast(edgeInset)
            }
        }
        if (isPortraitWithTopCutout()) {
            val cutout = currentCameraCutoutBounds()
            if (cutout != null) return cutout.centerX() - displayWidthPx() / 2
        }
        return horizontalOffset()
    }

    private fun positionY(windowHeight: Int = targetHeight(), expanded: Boolean = panelOpen): Int {
        if (isRecordingControlExpanded()) return 0
        if (isLandscapeWithSideCutout() && !panelOpen && !siriActive) {
            val cutout = currentCameraCutoutBounds()
            if (cutout != null) {
                return (cutout.centerY() - windowHeight / 2)
                    .coerceIn(0, (displayHeightPx() - windowHeight).coerceAtLeast(0))
            }
        }
        return overlayTopY()
    }

    private fun updatePosition() {
        val root = islandRoot ?: return
        val lp = params ?: return
        if (isFaceUnlockShrinking || isSiriShrinking) return
        lp.width = targetWindowWidth()
        lp.height = targetHeight()
        lp.gravity = windowGravity()
        lp.x = windowX()
        lp.y = windowY()
        hostInsetPx = targetHostInsetPx()
        syncOverlayChildBounds(targetWidth(), targetHeight())
        updateExpandedRadius()
        currentMorphProgress = targetMorph()
        applyRootCorner(targetWidth(), targetHeight())
        glassView?.configure(glassStrength, tint, expandedNow = panelOpen)
        updateOverlayWindow(lp)
    }

    private fun render(animateSize: Boolean = false) {
        val root = islandRoot ?: return
        val rootContent = content ?: return
        if ((isFaceUnlockShrinking || isSiriShrinking) && resizeAnimator?.isRunning == true) return
        val prefs = AppPrefs.get(this)
        resizeAnimator?.cancel()
        resetWobble()
        recordingPulse?.cancel()
        recordingPulse = null
        recordingTextTypewriter?.let { handler.removeCallbacks(it) }
        recordingTextTypewriter = null
        glassView?.alpha = 1f
        rootContent.visibility = View.VISIBLE

        if (siriActive) {
            rootContent.setPadding(0, 0, 0, 0)
            faceView?.release()
            faceView = null
            if (siriView?.parent !== rootContent) {
                rootContent.removeAllViews()
                val sv = SiriLensEffectView(this)
                siriView = sv
                rootContent.addView(sv, LinearLayout.LayoutParams(-1, -1))
            }
        } else if (faceUnlocking) {
            siriView = null
            rootContent.setPadding(0, 0, 0, 0)
            if (faceView?.parent !== rootContent) {
                rootContent.removeAllViews()
                val fv = FaceUnlockAnimationView(this, faceFailed) { beginFaceUnlockShrink() }
                faceView = fv
                rootContent.addView(fv, LinearLayout.LayoutParams(-1, -1))
            }
        } else {
            rootContent.removeAllViews()
            faceView?.release()
            faceView = null
            siriView = null
            if (isRecordingControlExpanded()) {
                rootContent.setPadding(dp(20), 0, dp(18), 0)
            } else {
                rootContent.setPadding(dp(if (panelOpen) 18 else 10), dp(if (panelOpen) 15 else 2), dp(if (panelOpen) 18 else 10), dp(if (panelOpen) 14 else 2))
            }
        }

        updateExpandedRadius()
        if (!animateSize) currentMorphProgress = targetMorph()
        applyRootCorner(
            if (animateSize && params != null) (root.width.takeIf { it > 0 } ?: targetWidth()) else targetWidth(),
            if (animateSize && params != null) (root.height.takeIf { it > 0 } ?: targetHeight()) else targetHeight()
        )
        root.clipToOutline = true
        root.clipChildren = true
        root.clipToPadding = true
        glassView?.configure(glassStrength, tint, expandedNow = panelOpen || faceUnlocking || siriActive)
        glassView?.visibility = if (panelOpen || faceUnlocking || siriActive) View.GONE else View.VISIBLE
        updatePrivacyDot()

        val customUri = prefs.getString(PANEL_BACKGROUND_URI, null)
        val showMediaOnTop = !isRecordingControlExpanded() && !faceUnlocking && !siriActive && (panelOpen || closingPanelWithMedia) && !customUri.isNullOrBlank()

        if (!faceUnlocking && !siriActive) {
            if (isRecordingControlExpanded()) {
                renderRecordingControlPanel(rootContent)
            } else if (panelOpen) {
                if (showMediaOnTop) {
                    rootContent.visibility = View.GONE
                } else {
                    val runIntro = playPanelIntro
                    playPanelIntro = false
                    renderCustomizationPanel(rootContent, runIntro)
                }
            } else if (screenRecording) {
                renderRecordingIndicator(rootContent)
            }
        }
        syncPanelBackground()

        val lp = params
        if (lp != null) {
            if (animateSize) animateToTargetSize()
            else {
                lp.width = targetWindowWidth()
                lp.height = targetHeight()
                lp.gravity = windowGravity()
                lp.x = windowX()
                lp.y = windowY()
                hostInsetPx = targetHostInsetPx()
                syncOverlayChildBounds(targetWidth(), targetHeight())
                updateOverlayWindow(lp)
            }
        }
        applyNativeActivityVisibility()
    }

    private fun updatePrivacyDot() {
        val dot = idleDot ?: return
        val color = if (cameraActive) 0xFF30D158.toInt() else 0xFFFFD36E.toInt()
        (dot.background as? GradientDrawable)?.setColor(color)
        dot.alpha = if (cameraActive) 1f else 0.95f
        dot.visibility = if (!panelOpen && !screenRecording && !faceUnlocking && !isFaceUnlockShrinking && !siriActive && !isSiriShrinking) View.VISIBLE else View.GONE
        dot.contentDescription = if (cameraActive) "Camera đang hoạt động" else "DigitalLiquid trạng thái chờ"
    }

    private fun applyNativeActivityVisibility() {
        val root = islandRoot ?: return
        val host = overlayHost ?: return
        val lp = params ?: return
        val prefs = AppPrefs.get(this)
        val hideForSystemIsland = prefs.getBoolean(AppPrefs.AUTO_HIDE_SYSTEM_ISLAND, true) && hyperIslandVisibilityOverride == true
        val hideForOtherActivity = prefs.getBoolean(AppPrefs.HIDE_WHEN_NATIVE_ACTIVITY, true) && nativeActivityActive
        val hideForMusic = prefs.getBoolean(AppPrefs.HIDE_WHEN_MEDIA_PLAYING, true) && mediaPlaybackActive
        val ringerTransitionActive = android.os.SystemClock.elapsedRealtime() < ringerTransitionHideUntil
        val hideForRingerUi = prefs.getBoolean(AppPrefs.HIDE_WHEN_RINGER_UI, true) && ringerTransitionActive
        val nativeIslandVisible = hideForSystemIsland || hideForOtherActivity || hideForMusic || hideForRingerUi
        val suppress = !faceUnlocking && !isFaceUnlockShrinking && !siriActive && !isSiriShrinking && (
            ringerTransitionActive || (!screenRecording && !cameraActive && nativeIslandVisible)
        )
        val wantedVisibility = if (suppress) View.GONE else View.VISIBLE
        val hasNotTouchable = (lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) != 0
        if (host.visibility != wantedVisibility || hasNotTouchable != suppress) {
            host.visibility = wantedVisibility
            lp.flags = if (suppress) {
                lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            } else {
                lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            }
            updateOverlayWindow(lp)
        }
    }

    private fun overlayTopY(): Int = dp(topOffsetDp)

    private fun statusBarHeightPx(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else dp(24)
    }

    private fun playFaceUnlockAnimation(failed: Boolean = false) {
        val prefs = AppPrefs.get(this)
        if (!prefs.getBoolean(AppPrefs.FACE_UNLOCK_EFFECT, true) ||
            prefs.getBoolean(AppPrefs.REDUCE_MOTION, false)) return
        if (islandRoot == null) return
        if (faceUnlocking || isFaceUnlockShrinking || siriActive || isSiriShrinking || panelOpen) return
        faceFailed = failed
        faceUnlocking = true
        isFaceUnlockShrinking = false
        render(animateSize = true)
    }

    private fun beginFaceUnlockShrink() {
        if (!faceUnlocking || isFaceUnlockShrinking) return
        isFaceUnlockShrinking = true
        faceUnlocking = false
        faceView?.startShrinkFade()

        glassView?.apply {
            configure(glassStrength, tint, expandedNow = false)
            alpha = 0f
            visibility = View.VISIBLE
        }
        handler.removeCallbacks(faceShrinkSafety)
        handler.postDelayed(faceShrinkSafety, FACE_SHRINK_SAFETY_MS)
        animateToTargetSize { finishFaceUnlockShrink() }
    }

    private fun finishFaceUnlockShrink() {
        handler.removeCallbacks(faceShrinkSafety)
        if (!isFaceUnlockShrinking) return
        isFaceUnlockShrinking = false
        faceGhostsActive = false
        siriView?.releaseBackground()
        faceFailed = false
        faceView?.release()
        faceView = null
        stopSiriDropletFloatAnimation()
        currentMorphProgress = 0f
        glassView?.alpha = 1f
        applyPreferences()
        render(animateSize = false)
        updatePosition()
    }

    private fun abortFaceUnlock() {
        if (!faceUnlocking && !isFaceUnlockShrinking) return
        handler.removeCallbacks(faceShrinkSafety)
        resizeAnimator?.cancel()
        faceView?.release()
        faceView = null
        faceUnlocking = false
        isFaceUnlockShrinking = false
        faceGhostsActive = false
        currentMorphProgress = 0f
        render(animateSize = false)
    }

    private fun renderRecordingControlPanel(target: LinearLayout) {
        target.orientation = LinearLayout.HORIZONTAL
        target.gravity = Gravity.CENTER_VERTICAL
        target.setPadding(dp(22), 0, dp(24), 0)
        target.background = null

        val marker = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFFFF3B50.toInt())
            }
            elevation = dp(2).toFloat()
            contentDescription = "Đang quay màn hình"
        }
        target.addView(marker, LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(9) })

        val showRecordingLabel = AppPrefs.get(this).getBoolean(AppPrefs.RECORDING_STOP_CONTROL, true)
        if (showRecordingLabel) {
            target.addView(TextView(this).apply {
                text = "Recording"
                textSize = 14f
                letterSpacing = 0.015f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER_VERTICAL
                contentDescription = "Recording"
            }, LinearLayout.LayoutParams(0, -1, 1f))
        } else {
            target.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        }

        val timer = TextView(this).apply {
            text = "00:00"
            textSize = 14f
            letterSpacing = 0.025f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(0xFFE6E8EE.toInt())
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            contentDescription = "Thời gian quay màn hình"
        }
        target.addView(timer, LinearLayout.LayoutParams(dp(62), -1))

        recordingTimerTask?.let { handler.removeCallbacks(it) }
        val task = object : Runnable {
            override fun run() {
                if (!screenRecording || !isRecordingControlExpanded() || !timer.isAttachedToWindow) return
                val start = recordingStartedAtElapsed.takeIf { it > 0L } ?: android.os.SystemClock.elapsedRealtime()
                val elapsed = ((android.os.SystemClock.elapsedRealtime() - start) / 1000L).coerceAtLeast(0L)
                timer.text = String.format(java.util.Locale.ROOT, "%02d:%02d", elapsed / 60L, elapsed % 60L)
                handler.postDelayed(this, 250L)
            }
        }
        recordingTimerTask = task
        task.run()
    }

    private fun renderRecordingIndicator(target: LinearLayout) {
        target.orientation = LinearLayout.VERTICAL
        target.gravity = Gravity.CENTER_VERTICAL or Gravity.START
        target.setPadding(dp(9), 0, dp(8), 0)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            isClickable = false
        }
        val dotWrap = FrameLayout(this)
        val halo = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x22FF354D)
                setStroke(dp(1), 0x66FF5266)
            }
        }
        val dot = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFFFF304B.toInt())
            }
        }
        dotWrap.addView(halo, FrameLayout.LayoutParams(dp(19), dp(19), Gravity.CENTER))
        dotWrap.addView(dot, FrameLayout.LayoutParams(dp(7), dp(7), Gravity.CENTER))
        row.addView(dotWrap, LinearLayout.LayoutParams(dp(23), dp(23)))
        val recLabel = TextView(this).apply {
            text = "REC"
            textSize = 11f
            letterSpacing = 0.10f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(0xFFFFE9EC.toInt())
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            setPadding(dp(2), 0, 0, 0)
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            if (Build.VERSION.SDK_INT >= 31) {
                try { setRenderEffect(android.graphics.RenderEffect.createBlurEffect(0.18f, 0f, android.graphics.Shader.TileMode.CLAMP)) } catch (_: Exception) { }
            }
        }
        row.addView(recLabel, LinearLayout.LayoutParams(-2, dp(23)))
        target.addView(row, LinearLayout.LayoutParams(-1, -1))

        recordingPulse?.cancel()
        recordingPulse = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 760L
            repeatCount = ValueAnimator.INFINITE
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener { animator ->
                val phase = animator.animatedValue as Float
                val wave = (sin(phase * Math.PI * 2.0).toFloat() + 1f) * 0.5f
                dot.scaleX = 0.90f + wave * 0.10f
                dot.scaleY = 0.90f + wave * 0.10f
                dot.alpha = 0.72f + wave * 0.28f
                halo.scaleX = 0.78f + phase * 0.48f
                halo.scaleY = 0.78f + phase * 0.48f
                halo.alpha = 0.60f * (1f - phase)
                recLabel.alpha = 0.82f + wave * 0.18f
            }
            start()
        }
    }

    private fun renderCustomizationPanel(target: LinearLayout, animateIntro: Boolean) {
        target.orientation = LinearLayout.VERTICAL
        target.gravity = Gravity.CENTER
        target.setPadding(dp(18), dp(15), dp(18), dp(14))
        val brand = TextView(this).apply {
            text = if (animateIntro) "" else "DigitalLiquid"
            textSize = 23f
            letterSpacing = -0.025f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            isClickable = false
        }
        target.addView(brand, LinearLayout.LayoutParams(-1, dp(54)))
        val customize = TextView(this).apply {
            text = if (animateIntro) "" else "Tùy chỉnh"
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(0xFFC6C6D0.toInt())
            gravity = Gravity.CENTER
            isClickable = false
        }
        target.addView(customize, LinearLayout.LayoutParams(-1, dp(34)))
        panelBrandTitle = brand
        if (animateIntro) startPanelTypewriter(brand, customize)
    }

    private fun startPanelTypewriter(brand: TextView, customize: TextView) {
        panelTypewriter?.let { handler.removeCallbacks(it) }
        val first = "DigitalLiquid"
        val second = "Tùy chỉnh"
        var index = 0
        var deleting = false
        var row = 0
        val task = object : Runnable {
            override fun run() {
                if (!panelOpen || panelBrandTitle !== brand || content?.visibility != View.VISIBLE) return
                val label = if (row == 0) first else second
                val view = if (row == 0) brand else customize
                index = if (deleting) (index - 1).coerceAtLeast(0) else (index + 1).coerceAtMost(label.length)
                view.text = label.substring(0, index)
                if (Build.VERSION.SDK_INT >= 31) {
                    val blur = if (deleting) 0.65f else 0.35f
                    try { view.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(blur, 0f, android.graphics.Shader.TileMode.CLAMP)) } catch (_: Exception) { }
                }
                view.alpha = 0.76f + 0.24f * (index.toFloat() / label.length.coerceAtLeast(1))
                view.translationX = if (deleting) dp(1).toFloat() else -dp(1).toFloat()
                if (!deleting && index >= label.length) {
                    deleting = true
                    handler.postDelayed(this, 720L)
                    return
                }
                if (deleting && index <= 0) {
                    view.setRenderEffectCompatClear()
                    view.translationX = 0f
                    view.alpha = 1f
                    deleting = false
                    row = (row + 1) % 2
                    handler.postDelayed(this, 180L)
                    return
                }
                if (index in 0..label.length) {
                    if (index == 0) view.setRenderEffectCompatClear()
                    handler.postDelayed(this, if (deleting) 34L else 46L)
                }
            }
        }
        panelTypewriter = task
        handler.postDelayed(task, 50L)
    }

    private fun syncPanelBackground() {
        val host = backgroundHost ?: return
        val scrim = backgroundScrim ?: return
        val rootContent = content ?: return
        val uriString = AppPrefs.get(this).getString(PANEL_BACKGROUND_URI, null)
        val recordingControlOwnsOverlay = isRecordingControlExpanded()
        if (recordingControlOwnsOverlay) closingPanelWithMedia = false
        val shouldShow = !recordingControlOwnsOverlay && !faceUnlocking && !isFaceUnlockShrinking && !siriActive && !isSiriShrinking && (panelOpen || closingPanelWithMedia) && !uriString.isNullOrBlank()
        if (!shouldShow) {
            stopPanelMedia()
            host.removeAllViews()
            lastBackgroundUri = null
            host.visibility = View.GONE
            scrim.visibility = View.GONE
            rootContent.visibility = View.VISIBLE
            return
        }

        host.visibility = View.VISIBLE
        scrim.visibility = View.GONE
        host.elevation = dp(8).toFloat()
        host.background = roundedBackground(30f, Color.BLACK)
        host.clipToOutline = true
        host.clipChildren = true
        host.clipToPadding = true
        rootContent.visibility = View.GONE
        if (lastBackgroundUri == uriString && host.childCount > 0) {
            host.bringToFront()
            return
        }
        stopPanelMedia()
        host.removeAllViews()
        lastBackgroundUri = uriString
        val uri = Uri.parse(uriString)
        val savedKind = AppPrefs.get(this).getString(AppPrefs.PANEL_BACKGROUND_KIND, null)
        val kind = savedKind ?: PanelBackgroundMedia.kind(this, uri)
        if (kind == "video") {
            val texture = TextureView(this)
            panelTextureView = texture
            texture.isOpaque = false
            texture.isClickable = false
            texture.isFocusable = false
            texture.background = roundedBackground(30f, Color.BLACK)
            texture.clipToOutline = true
            texture.outlineProvider = ViewOutlineProvider.BACKGROUND
            texture.elevation = dp(8).toFloat()
            val generation = panelMediaGeneration
            var localPlayer: MediaPlayer? = null
            var localSurface: Surface? = null
            texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(surfaceTexture: android.graphics.SurfaceTexture, width: Int, height: Int) {
                    if (generation != panelMediaGeneration || panelTextureView !== texture) return
                    try {
                        val surface = Surface(surfaceTexture)
                        val player = MediaPlayer()
                        localSurface = surface
                        localPlayer = player
                        panelVideoSurface = surface
                        panelMediaPlayer = player
                        player.setOnErrorListener { failed, _, _ ->
                            if (generation == panelMediaGeneration && panelMediaPlayer === failed) {
                                stopPanelMedia()
                            } else {
                                releaseDetachedPanelMedia(localPlayer, localSurface)
                            }
                            true
                        }
                        player.setOnVideoSizeChangedListener { _, videoWidth, videoHeight ->
                            if (generation == panelMediaGeneration && panelTextureView === texture) {
                                applyVideoCenterCrop(texture, videoWidth, videoHeight)
                            }
                        }
                        player.setOnPreparedListener { prepared ->
                            if (generation != panelMediaGeneration || panelTextureView !== texture || panelMediaPlayer !== prepared) {
                                releaseDetachedPanelMedia(localPlayer, localSurface)
                                return@setOnPreparedListener
                            }
                            try {
                                applyVideoCenterCrop(texture, prepared.videoWidth, prepared.videoHeight)
                                prepared.start()
                            } catch (_: Exception) {
                                stopPanelMedia()
                            }
                        }
                        player.setDataSource(this@IslandService, uri)
                        player.setSurface(surface)
                        player.isLooping = true
                        player.setVolume(0f, 0f)
                        player.prepareAsync()
                    } catch (_: Exception) {
                        if (generation == panelMediaGeneration) stopPanelMedia()
                        else releaseDetachedPanelMedia(localPlayer, localSurface)
                    }
                }
                override fun onSurfaceTextureSizeChanged(surfaceTexture: android.graphics.SurfaceTexture, width: Int, height: Int) {
                    if (generation != panelMediaGeneration || panelTextureView !== texture) return
                    val player = localPlayer ?: return
                    try { applyVideoCenterCrop(texture, player.videoWidth, player.videoHeight) } catch (_: Exception) { }
                }
                override fun onSurfaceTextureDestroyed(surfaceTexture: android.graphics.SurfaceTexture): Boolean {
                    if (generation == panelMediaGeneration && panelTextureView === texture) stopPanelMedia()
                    else releaseDetachedPanelMedia(localPlayer, localSurface)
                    return true
                }
                override fun onSurfaceTextureUpdated(surfaceTexture: android.graphics.SurfaceTexture) = Unit
            }
            host.addView(texture, FrameLayout.LayoutParams(-1, -1))
        } else {
            val image = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(Color.BLACK)
                contentDescription = ""
                isClickable = false
                isFocusable = false
                try { setImageURI(uri) } catch (_: Exception) { }
            }
            host.addView(image, FrameLayout.LayoutParams(-1, -1))
        }
        host.bringToFront()
    }

    private fun applyVideoCenterCrop(texture: TextureView, videoWidth: Int, videoHeight: Int) {
        if (videoWidth <= 0 || videoHeight <= 0 || texture.width <= 0 || texture.height <= 0) return
        val viewWidth = texture.width.toFloat()
        val viewHeight = texture.height.toFloat()
        val videoAspect = videoWidth.toFloat() / videoHeight.toFloat()
        val viewAspect = viewWidth / viewHeight
        val scaleX = if (videoAspect > viewAspect) videoAspect / viewAspect else 1f
        val scaleY = if (videoAspect < viewAspect) viewAspect / videoAspect else 1f
        val matrix = Matrix()
        matrix.setScale(scaleX, scaleY, viewWidth / 2f, viewHeight / 2f)
        texture.setTransform(matrix)
    }

    private fun releaseDetachedPanelMedia(player: MediaPlayer?, surface: Surface?) {
        if (player != null && player !== panelMediaPlayer) {
            try { player.setOnPreparedListener(null) } catch (_: Exception) { }
            try { player.setOnVideoSizeChangedListener(null) } catch (_: Exception) { }
            try { player.setOnErrorListener(null) } catch (_: Exception) { }
            try { player.stop() } catch (_: Exception) { }
            try { player.reset() } catch (_: Exception) { }
            try { player.release() } catch (_: Exception) { }
        }
        if (surface != null && surface !== panelVideoSurface) try { surface.release() } catch (_: Exception) { }
    }

    private fun stopPanelMedia() {
        panelMediaGeneration++
        try { panelMediaPlayer?.setOnPreparedListener(null) } catch (_: Exception) { }
        try { panelMediaPlayer?.setOnVideoSizeChangedListener(null) } catch (_: Exception) { }
        try { panelMediaPlayer?.setOnErrorListener(null) } catch (_: Exception) { }
        try { panelMediaPlayer?.stop() } catch (_: Exception) { }
        try { panelMediaPlayer?.reset() } catch (_: Exception) { }
        try { panelMediaPlayer?.release() } catch (_: Exception) { }
        panelMediaPlayer = null
        try { panelVideoSurface?.release() } catch (_: Exception) { }
        panelVideoSurface = null
        panelTextureView = null
    }

    private fun TextView.setRenderEffectCompatClear() {
        if (Build.VERSION.SDK_INT >= 31) {
            try { setRenderEffect(null) } catch (_: Exception) { }
        }
    }

    private fun animateToTargetSize(onEnd: (() -> Unit)? = null) {
        val root = islandRoot
        val host = overlayHost
        val lp = params
        if (root == null || host == null || lp == null) {
            onEnd?.invoke()
            return
        }
        resizeAnimator?.cancel()
        updateExpandedRadius()

        val faceShrink = isFaceUnlockShrinking || isSiriShrinking
        val faceExpand = faceUnlocking || siriActive
        val opening = panelOpen || faceUnlocking || siriActive
        val startWindowWidth = lp.width.toFloat()
        val startHeight = lp.height.toFloat()
        val startX = lp.x.toFloat()
        val startY = lp.y.toFloat()
        val startPillWidth = (root.width.takeIf { it > 0 } ?: (lp.width - 2 * hostInsetPx)).toFloat()
        val endPillWidth = targetWidth().toFloat()
        val endWidth = targetWindowWidth().toFloat()
        val endHeight = targetHeight().toFloat()
        val endX = windowX().toFloat()
        val endY = windowY().toFloat()
        val startInset = hostInsetPx.toFloat()
        val endInset = targetHostInsetPx().toFloat()
        val startMorph = currentMorphProgress
        val targetMorph = targetMorph()
        lp.gravity = windowGravity()

        if (AppPrefs.get(this).getBoolean(AppPrefs.REDUCE_MOTION, false)) {
            currentMorphProgress = targetMorph
            lp.width = endWidth.roundToInt()
            lp.height = endHeight.roundToInt()
            lp.x = endX.roundToInt()
            lp.y = endY.roundToInt()
            hostInsetPx = endInset.roundToInt()
            faceGhostsActive = false
            syncOverlayChildBounds(endPillWidth.roundToInt(), endHeight.roundToInt())
            applyRootCorner(endPillWidth.roundToInt(), endHeight.roundToInt())
            siriView?.setMorphProgress(currentMorphProgress)
            root.scaleX = 1f
            root.scaleY = 1f
            updateOverlayWindow(lp)
            if (closingPanelWithMedia) {
                closingPanelWithMedia = false
                syncPanelBackground()
            }
            applyNativeActivityVisibility()
            onEnd?.invoke()
            return
        }

        root.alpha = 0.985f
        faceGhostsActive = faceShrink

        var canceled = false
        resizeAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = when {
                siriActive -> SIRI_EXPAND_MS
                isSiriShrinking -> SIRI_SHRINK_MS
                faceExpand -> FACE_EXPAND_MS
                faceShrink -> FACE_SHRINK_MS
                opening -> 420L
                else -> 460L
            }
            interpolator = if (faceExpand) FACE_EXPAND_INTERPOLATOR else PathInterpolator(0.22f, 1f, 0.36f, 1f)
            addUpdateListener { animator ->
                val t = animator.animatedValue as Float
                val tc = t.coerceIn(0f, 1f)
                currentMorphProgress = lerp(startMorph, targetMorph, t)

                lp.width = (startWindowWidth + (endWidth - startWindowWidth) * t).roundToInt().coerceAtLeast(1)
                lp.height = (startHeight + (endHeight - startHeight) * t).roundToInt().coerceAtLeast(1)
                lp.x = (startX + (endX - startX) * t).roundToInt()
                lp.y = (startY + (endY - startY) * t).roundToInt()
                hostInsetPx = (startInset + (endInset - startInset) * tc).roundToInt()

                val pillW = (startPillWidth + (endPillWidth - startPillWidth) * t).roundToInt().coerceAtLeast(1)
                val pillH = (startHeight + (endHeight - startHeight) * t).roundToInt().coerceAtLeast(1)

                syncOverlayChildBounds(pillW, pillH)
                applyRootCorner(pillW, pillH)
                siriView?.setMorphProgress(currentMorphProgress.coerceIn(0f, 1f))
                root.scaleX = 1f
                root.scaleY = 1f
                if (Build.VERSION.SDK_INT >= 31) {
                    try { root.setRenderEffect(null) } catch (_: Exception) { }
                }

                if (faceShrink) {
                    glassView?.alpha = tc * tc
                    updateFaceShrinkGhosts(tc, startPillWidth, endPillWidth, startHeight, endHeight, startMorph, targetMorph)
                }
                updateOverlayWindow(lp)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationCancel(animation: Animator) {
                    canceled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (canceled) return
                    currentMorphProgress = targetMorph
                    hostInsetPx = endInset.roundToInt()
                    faceGhostsActive = false
                    syncOverlayChildBounds(endPillWidth.roundToInt(), endHeight.roundToInt())
                    applyRootCorner(endPillWidth.roundToInt(), endHeight.roundToInt())
                    siriView?.setMorphProgress(currentMorphProgress.coerceIn(0f, 1f))
                    root.scaleX = 1f
                    root.scaleY = 1f
                    root.alpha = 1f
                    if (Build.VERSION.SDK_INT >= 31) try { root.setRenderEffect(null) } catch (_: Exception) { }
                    if (faceShrink) glassView?.alpha = 1f
                    if (closingPanelWithMedia) {
                        closingPanelWithMedia = false
                        syncPanelBackground()
                    }
                    applyNativeActivityVisibility()
                    onEnd?.invoke()
                }
            })
            start()
        }
    }

    private fun updateFaceShrinkGhosts(
        t: Float, startW: Float, endW: Float, startH: Float, endH: Float,
        startMorph: Float, endMorph: Float
    ) {
        val envelope = sin(Math.PI * t).toFloat()
        ghostLayers.forEachIndexed { i, ghost ->
            val tl = (t - FACE_GHOST_LAG * (i + 1)).coerceIn(0f, 1f)
            val gw = lerp(startW, endW, tl).roundToInt().coerceAtLeast(1)
            val gh = lerp(startH, endH, tl).roundToInt().coerceAtLeast(1)
            val gm = lerp(startMorph, endMorph, tl)
            val gr = lerp(gh / 2f, dp(expandedRadiusDp), gm).coerceIn(0f, minOf(gw, gh) / 2f)
            ghost.layoutParams = FrameLayout.LayoutParams(gw, gh, Gravity.CENTER)
            val bg = ghost.background as? GradientDrawable
            if (bg != null) bg.cornerRadius = gr else ghost.background = ghostCapsule(gr)
            ghost.translationX = 0f
            ghost.translationY = 0f
            ghost.alpha = FACE_GHOST_ALPHA[i.coerceAtMost(FACE_GHOST_ALPHA.lastIndex)] * envelope
            ghost.visibility = View.VISIBLE
        }
    }

    private fun roundedBackground(radiusDp: Float, color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
        cornerRadius = dp(radiusDp.roundToInt()).toFloat()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
    private fun dp(value: Float): Float = value * resources.displayMetrics.density
    override fun onBind(intent: Intent?): IBinder? = null

    private fun installedVersionCode(): Int = runCatching {
        val info = packageManager.getPackageInfo(packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt() else legacyVersionCode(info)
    }.getOrDefault(0)

    @Suppress("DEPRECATION")
    private fun legacyVersionCode(info: android.content.pm.PackageInfo): Int = info.versionCode

    private fun showTransientServiceNotice(message: String) {
        if (!showCustomAppNotice("Trạng thái DigitalLiquid", message, "", force = true)) {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun showCustomAppNotice(title: String, message: String, url: String, force: Boolean = false): Boolean {
        val host = overlayHost ?: return false
        if (!usingAccessibilityOverlay || (!force && !AppPrefs.get(this).getBoolean(AppPrefs.CUSTOM_APP_NOTICES, true))) return false
        dismissCustomAppNotice(animate = false)

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(17), dp(15), dp(17), dp(15))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xF51B1D28.toInt(), 0xF20A0C13.toInt())
            ).apply {
                cornerRadius = dp(25).toFloat()
                setStroke(dp(1), 0x55FFFFFF)
            }
            alpha = 0f
            translationY = -dp(10).toFloat()
            elevation = dp(16).toFloat()
            isClickable = true
            isFocusable = false
        }
        val eyebrow = TextView(this).apply {
            text = "DIGITALLIQUID · THÔNG BÁO"
            textSize = 9.5f
            letterSpacing = 0.08f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(0xFFB9AEFF.toInt())
        }
        card.addView(eyebrow)
        card.addView(TextView(this).apply {
            text = title.ifBlank { "Thông báo DigitalLiquid" }
            textSize = 16f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(Color.WHITE)
            maxLines = 2
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(7) })
        card.addView(TextView(this).apply {
            text = message
            textSize = 13f
            setTextColor(0xFFD2D4DF.toInt())
            setLineSpacing(dp(2).toFloat(), 1.08f)
            maxLines = 5
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5) })
        val bottom = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        bottom.addView(TextView(this).apply {
            text = if (url.startsWith("https://") || url.startsWith("http://")) "MỞ LIÊN KẾT ↗" else "ĐÃ NHẬN"
            textSize = 10f
            letterSpacing = 0.03f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(0xFFE2DCFF.toInt())
        }, LinearLayout.LayoutParams(0, dp(28), 1f))
        bottom.addView(TextView(this).apply {
            text = "×"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(0xFFB8BBC9.toInt())
            setPadding(dp(8), 0, dp(3), 0)
            setOnClickListener { dismissCustomAppNotice() }
        }, LinearLayout.LayoutParams(dp(32), dp(30)))
        card.addView(bottom, LinearLayout.LayoutParams(-1, dp(31)).apply { topMargin = dp(8) })

        card.setOnClickListener {
            dismissCustomAppNotice()
            val target = url.trim()
            try {
                val intent = if (target.startsWith("https://") || target.startsWith("http://")) {
                    Intent(Intent.ACTION_VIEW, Uri.parse(target))
                } else Intent(this, MainActivity::class.java)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                startActivity(intent)
            } catch (_: Exception) { }
        }
        val lp = WindowManager.LayoutParams(
            minOf(dp(360), (displayWidthPx() - dp(20)).coerceAtLeast(dp(240))),
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = 0
            y = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE && isLandscapeWithSideCutout()) {
                (positionY() + targetHeight() + dp(12)).coerceAtMost((displayHeightPx() - dp(140)).coerceAtLeast(0))
            } else statusBarHeightPx() + dp(10)
        }
        return try {
            windowManager.addView(card, lp)
            customNoticeOverlay = card
            card.animate().alpha(1f).translationY(0f).setDuration(260L).start()
            val task = Runnable { dismissCustomAppNotice() }
            customNoticeDismissTask = task
            handler.postDelayed(task, CUSTOM_NOTICE_DURATION_MS)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun dismissCustomAppNotice(animate: Boolean = true) {
        customNoticeDismissTask?.let { handler.removeCallbacks(it) }
        customNoticeDismissTask = null
        val current = customNoticeOverlay ?: return
        customNoticeOverlay = null
        if (animate) {
            current.animate().alpha(0f).translationY(-dp(8).toFloat()).setDuration(150L).withEndAction {
                try { windowManager.removeView(current) } catch (_: Exception) { }
            }.start()
        } else {
            try { windowManager.removeView(current) } catch (_: Exception) { }
        }
    }

    private fun remoteNotificationsManager(): NotificationManager? {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return null
        if (Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel(REMOTE_NOTICE_CHANNEL) == null) {
            manager.createNotificationChannel(NotificationChannel(REMOTE_NOTICE_CHANNEL, "DigitalLiquid Admin", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Thông báo, bảo trì và cập nhật từ quản trị viên DigitalLiquid"
            })
        }
        return manager
    }

    private fun postRemoteAnnouncement(control: OnlineControlManager.RemoteControl) {
        val noticeId = control.announcementId.ifBlank { control.announcementTitle + "|" + control.announcementMessage }
        val noticePrefs = getSharedPreferences(REMOTE_NOTICE_PREFS, MODE_PRIVATE)
        if (noticePrefs.getString(REMOTE_NOTICE_LAST_ID, "") == noticeId) return
        if (showCustomAppNotice(control.announcementTitle, control.announcementMessage, control.announcementUrl)) {
            noticePrefs.edit().putString(REMOTE_NOTICE_LAST_ID, noticeId).apply()
            return
        }
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            this, REMOTE_ANNOUNCEMENT_NOTIFICATION_ID, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, REMOTE_NOTICE_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(control.announcementTitle.ifBlank { "Thông báo DigitalLiquid" })
            .setContentText(control.announcementMessage)
            .setStyle(Notification.BigTextStyle().bigText(control.announcementMessage))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        try {
            remoteNotificationsManager()?.notify(REMOTE_ANNOUNCEMENT_NOTIFICATION_ID, notification)
            noticePrefs.edit().putString(REMOTE_NOTICE_LAST_ID, noticeId).apply()
        } catch (_: Exception) { }
    }

    private fun postRemotePolicyNotice(control: OnlineControlManager.RemoteControl, localVersion: Int) {
        val title: String
        val message: String
        val targetUrl: String
        when {
            !control.signerMatches -> {
                title = "Bản dựng không được xác minh"
                message = "Chữ ký APK không khớp với chứng thư được quản trị viên cho phép. Hãy cài bản chính thức."
                targetUrl = control.downloadUrl
            }
            !control.appEnabled -> {
                title = "DigitalLiquid đã bị vô hiệu hóa"
                message = "Quản trị viên đã tắt quyền sử dụng phiên bản này."
                targetUrl = ""
            }
            control.maintenanceEnabled -> {
                title = "DigitalLiquid đang bảo trì"
                message = control.maintenanceMessage
                targetUrl = ""
            }
            control.minVersionCode > localVersion && control.downloadUrl.isNotBlank() -> {
                title = "Cần cập nhật DigitalLiquid"
                message = "Phiên bản này đã ngừng hỗ trợ. Mở liên kết để tải bản cập nhật mới."
                targetUrl = control.downloadUrl
            }
            else -> return
        }
        val openIntent = try {
            (if (targetUrl.startsWith("https://") || targetUrl.startsWith("http://")) {
                Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl))
            } else {
                Intent(this, MainActivity::class.java)
            }).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
        } catch (_: Exception) {
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
        }
        val pending = PendingIntent.getActivity(
            this, REMOTE_POLICY_NOTIFICATION_ID, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, REMOTE_NOTICE_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(Notification.BigTextStyle().bigText(message))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        try { remoteNotificationsManager()?.notify(REMOTE_POLICY_NOTIFICATION_ID, notification) } catch (_: Exception) { }
    }

    override fun onDestroy() {
        handler.removeCallbacks(keyAuthorizationWatchdog)
        handler.removeCallbacks(onlinePolicyWatchdog)
        handler.removeCallbacks(faceShrinkSafety)
        handler.removeCallbacks(siriShrinkSafety)
        handler.removeCallbacks(appSwitchModeExitRunnable)
        handler.removeCallbacks(ringerTransitionHideReset)
        handler.removeCallbacks(geometryRefreshRunnable)
        handler.removeCallbacks(singleTapRunnable)
        handler.removeCallbacks(longPressRunnable)
        
        try { displayManager.unregisterDisplayListener(displayListener) } catch (_: Exception) { }
        if (Build.VERSION.SDK_INT >= 24 && audioRecordingCallback != null) {
            try { audioManager.unregisterAudioRecordingCallback(audioRecordingCallback) } catch (_: Exception) { }
        }

        stopSiriDropletFloatAnimation()
        instance = null
        AppPrefs.get(this).edit().putBoolean(AppPrefs.SERVICE_ENABLED, false).apply()
        resizeAnimator?.cancel()
        wobbleAnimator?.cancel()
        recordingPulse?.cancel()
        panelTypewriter?.let { handler.removeCallbacks(it) }
        recordingTextTypewriter?.let { handler.removeCallbacks(it) }
        recordingTimerTask?.let { handler.removeCallbacks(it) }
        faceView?.release()
        faceView = null
        siriView?.releaseBackground()
        siriView = null
        dismissCustomAppNotice(animate = false)
        stopPanelMedia()
        try { unregisterReceiver(configReceiver) } catch (_: Exception) { }
        try { unregisterReceiver(orientationReceiver) } catch (_: Exception) { }
        try { unlockReceiver.let { unregisterReceiver(it) } } catch (_: Exception) { }
        try { miuiScreenRecorderReceiver.let { unregisterReceiver(it) } } catch (_: Exception) { }
        try { soundNotificationTransitionReceiver.let { unregisterReceiver(it) } } catch (_: Exception) { }
        overlayHost?.let { try { windowManager.removeView(it) } catch (_: Exception) { } }
        overlayHost = null
        islandRoot = null
        ghostLayers.clear()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        const val ACTION_APPLY_CONFIG = "com.digitalsmod.digitalliquid.APPLY_CONFIG"
        const val ACTION_FACE_UNLOCK_FAILED = "com.digitalsmod.digitalliquid.FACE_UNLOCK_FAILED"
        const val EXTRA_PICK_PANEL_BACKGROUND = "com.digitalsmod.digitalliquid.PICK_PANEL_BACKGROUND"
        const val PANEL_BACKGROUND_URI = "custom_panel_background_uri"
        private const val CHANNEL_ID = "digitalliquid_overlay"
        private const val REMOTE_NOTICE_CHANNEL = "digitalliquid_admin_notices"
        private const val REMOTE_NOTICE_PREFS = "digitalliquid_remote_notices"
        private const val REMOTE_NOTICE_LAST_ID = "last_announcement_id"
        private const val REMOTE_ANNOUNCEMENT_NOTIFICATION_ID = 43
        private const val REMOTE_POLICY_NOTIFICATION_ID = 44
        private const val CUSTOM_NOTICE_DURATION_MS = 6500L
        private const val LONG_PRESS_MS = 430L
        private const val DOUBLE_TAP_WINDOW_MS = 310L
        private const val APP_SWITCH_SEQUENCE_GAP_MS = 720L
        private const val APP_SWITCH_START_TAPS = 6
        private const val APP_SWITCH_MODE_IDLE_MS = 1800L
        private const val RINGER_TRANSITION_HIDE_MS = 2100L
        private const val TRAIL_EXTENT_DP = 38

        private const val SIRI_WIDTH_DP = 168
        private const val SIRI_HEIGHT_DP = 142
        private const val SIRI_CORNER_RADIUS_DP = 71f
        private const val SIRI_EXPAND_MS = 400L
        private const val SIRI_SHRINK_MS = 370L

        private const val FACE_SIZE_DP = 150
        private const val FACE_SQUIRCLE_RADIUS_DP = 38f
        private const val FACE_EXPAND_MS = 350L
        private const val FACE_SHRINK_MS = 400L
        private const val FACE_SHRINK_SAFETY_MS = 900L
        private const val FACE_GHOST_LAG = 0.035f
        private val FACE_GHOST_ALPHA = floatArrayOf(0.34f, 0.28f, 0.22f, 0.17f, 0.12f, 0.08f, 0.05f)
        private val FACE_EXPAND_INTERPOLATOR = PathInterpolator(0.30f, 1.18f, 0.40f, 1f)

        @Volatile private var instance: IslandService? = null
        @Volatile private var lastHyperIslandVisibilityOverride: Boolean? = null

        fun refreshOverlayWindowType() {
            val service = instance ?: return
            service.handler.post { service.rebuildOverlayForAccessibilityMode() }
        }

        fun showFaceUnlockResult(success: Boolean) {
            val service = instance ?: return
            service.handler.post { service.playFaceUnlockAnimation(failed = !success) }
        }

        @Volatile private var lastNotificationScreenRecording = false
        @Volatile private var lastAccessibilityScreenRecording = false
        @Volatile private var lastNativeActivityActive = false
        @Volatile private var lastMediaPlaybackActive = false
        @Volatile private var lastRingerUiActive = false
        @Volatile private var lastCameraActivityActive = false

        fun setCameraActivityActive(active: Boolean) {
            lastCameraActivityActive = active
            val service = instance ?: return
            service.handler.post {
                if (service.cameraActive == active) return@post
                service.cameraActive = active
                service.updatePrivacyDot()
                service.applyNativeActivityVisibility()
            }
        }

        fun setScreenRecordingActive(active: Boolean) {
            lastNotificationScreenRecording = active
            val service = instance ?: return
            service.handler.post {
                service.notificationScreenRecording = active
                service.evaluateRecordingState()
            }
        }

        fun setAccessibilityScreenRecordingActive(active: Boolean) {
            lastAccessibilityScreenRecording = active
            val service = instance ?: return
            service.handler.post {
                service.accessibilityScreenRecording = active
                service.evaluateRecordingState()
            }
        }

        fun setRingerUiActive(active: Boolean) {
            lastRingerUiActive = active
            val service = instance ?: return
            service.handler.post {
                if (service.ringerUiActive == active) {
                    if (active) service.beginRingerTransitionHide()
                    return@post
                }
                service.ringerUiActive = active
                if (active) service.beginRingerTransitionHide()
                else service.applyNativeActivityVisibility()
            }
        }

        fun setMediaPlaybackActive(active: Boolean) {
            lastMediaPlaybackActive = active
            val service = instance ?: return
            service.handler.post {
                if (service.mediaPlaybackActive == active) return@post
                service.mediaPlaybackActive = active
                service.applyNativeActivityVisibility()
            }
        }

        fun setNativeIslandActivityActive(active: Boolean) {
            lastNativeActivityActive = active
            val service = instance ?: return
            service.handler.post {
                if (service.nativeActivityActive == active) return@post
                service.nativeActivityActive = active
                service.applyNativeActivityVisibility()
            }
        }

        fun setHyperIslandVisibilityOverride(visible: Boolean?) {
            lastHyperIslandVisibilityOverride = visible
            val service = instance ?: return
            service.handler.post {
                if (service.hyperIslandVisibilityOverride == visible) return@post
                service.hyperIslandVisibilityOverride = visible
                service.applyNativeActivityVisibility()
            }
        }
    }
}
