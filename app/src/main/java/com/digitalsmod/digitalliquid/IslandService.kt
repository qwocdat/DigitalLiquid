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
import android.content.res.Configuration
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewOutlineProvider
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Minimal HyperOS companion: an always-present black pill, an iOS-like tap reaction,
 * a long-press customization panel, and a recording-only live effect. Media playback,
 * Clock, stopwatch, charging, battery and ordinary notifications are deliberately ignored.
 */
class IslandService : Service() {
    private lateinit var windowManager: WindowManager
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
    private var closingPanelWithMedia = false
    private var screenRecording = false
    private var notificationScreenRecording = false
    private var accessibilityScreenRecording = false
    private var nativeActivityActive = false
    private var cameraActive = false
    private var hyperIslandVisibilityOverride: Boolean? = null
    private var idleDot: View? = null
    private var wobbleAnimator: ValueAnimator? = null
    private var panelMediaPlayer: MediaPlayer? = null
    private var panelVideoSurface: Surface? = null
    private var panelTextureView: TextureView? = null
    private var playPanelIntro = false
    private var lastBackgroundUri: String? = null
    private var panelBrandTitle: TextView? = null
    private var panelTypewriter: Runnable? = null
    private var resizeAnimator: ValueAnimator? = null
    private var recordingPulse: ValueAnimator? = null
    private var recordingTextTypewriter: Runnable? = null
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

    private val orientationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_CONFIGURATION_CHANGED) return
            applyPreferences()
            // Re-read the physical display cutout after rotation so the island stays
            // aligned with the camera rather than following the app's portrait UI.
            render(animateSize = true)
            updatePosition()
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Sticky service restarts must not resurrect the island after a cold process start
        // until MainActivity has successfully verified a key in this process.
        if (!KeyAuthGate.isAuthorizedInCurrentProcess(this)) {
            // A foreground-service start must promptly call startForeground on modern Android.
            // Show its low-importance notification briefly, then stop without creating the overlay.
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
        hyperIslandVisibilityOverride = lastHyperIslandVisibilityOverride
        notificationScreenRecording = lastNotificationScreenRecording
        accessibilityScreenRecording = lastAccessibilityScreenRecording
        nativeActivityActive = lastNativeActivityActive
        cameraActive = lastCameraActivityActive
        screenRecording = notificationScreenRecording || accessibilityScreenRecording
        refreshOverlayWindowManager()
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
                registerReceiver(orientationReceiver, IntentFilter(Intent.ACTION_CONFIGURATION_CHANGED), Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(configReceiver, IntentFilter(ACTION_APPLY_CONFIG))
                @Suppress("DEPRECATION")
                registerReceiver(orientationReceiver, IntentFilter(Intent.ACTION_CONFIGURATION_CHANGED))
            }
        } catch (_: Exception) { }
        waitForAccessibilityOverlay()
        IslandNotificationListener.refreshFromServiceStart()
    }

    private fun waitForAccessibilityOverlay() {
        refreshOverlayWindowManager()
        if (usingAccessibilityOverlay) {
            overlayStartupAttempts = 0
            showIsland()
            return
        }
        // AccessibilityService can reconnect just after Activity returns from Settings.
        // Wait briefly rather than create a normal overlay below the status bar.
        if (overlayStartupAttempts++ < 8) {
            handler.postDelayed({ waitForAccessibilityOverlay() }, 250L)
        } else {
            Toast.makeText(this, "DigitalLiquid chưa kết nối được lớp phủ Trợ năng. Hãy bật lại quyền Theo dõi HyperIsland rồi thử lại.", Toast.LENGTH_LONG).show()
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

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyPreferences()
        render(animateSize = false)
        updatePosition()
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
        widthDp = prefs.getInt(AppPrefs.WIDTH, 148).coerceIn(126, 330)
        heightDp = prefs.getInt(AppPrefs.HEIGHT, 38).coerceIn(34, 72)
        topOffsetDp = prefs.getInt(AppPrefs.OFFSET_TOP, 0).coerceIn(0, 140)
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
            background = roundedBackground(if (panelOpen) 30f else heightDp / 2f, Color.BLACK)
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
            // iPhone Dynamic Island's privacy dot sits right-of-centre and vertically centred,
            // not glued to the upper-right corner. Recalculate proportionally when width changes.
            addView(dot, FrameLayout.LayoutParams(dp(3), dp(3), Gravity.LEFT or Gravity.TOP).apply {
                leftMargin = dp(99)
                topMargin = dp(17)
            })
            idleDot = dot
            addOnLayoutChangeListener { changedView, _, _, _, _, _, _, _, _ ->
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

        val initialParams = WindowManager.LayoutParams(
            targetWidth(),
            targetHeight(),
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = gravityForWindow()
            x = positionX(width, panelOpen)
            y = positionY(height, panelOpen)
            if (Build.VERSION.SDK_INT >= 30) {
                // Request high-refresh rendering where the panel/display supports it.
                preferredRefreshRate = 120f
            }
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
            // Never silently fall back to TYPE_APPLICATION_OVERLAY: it can be below the
            // status bar, recreating the invisible/non-responsive pill bug.
            Toast.makeText(this, "Android từ chối lớp phủ trên thanh trạng thái. Kiểm tra quyền Trợ năng DigitalLiquid.", Toast.LENGTH_LONG).show()
            stopSelf()
            return
        }
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
        val shouldUseTrusted = HyperIslandVisibilityService.overlayWindowManager() != null
        if (!shouldUseTrusted && usingAccessibilityOverlay) {
            // Accessibility was disabled while running. Stop rather than regress to an
            // overlay that cannot receive taps over the status bar.
            stopSelf()
            return
        }
        if (shouldUseTrusted == usingAccessibilityOverlay) return
        try { windowManager.removeView(root) } catch (_: Exception) { }
        stopPanelMedia()
        islandRoot = null
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
                    if (panelOpen) closeCustomizationPanel() else wobblePill()
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
        if (panelOpen) closeCustomizationPanel() else openCustomizationPanel()
    }

    private fun wobblePill() {
        val root = islandRoot ?: return
        val lp = params ?: return
        if (panelOpen) return
        if (AppPrefs.get(this).getBoolean(AppPrefs.REDUCE_MOTION, false)) {
            root.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            return
        }

        // Cancel the previous response first so its end callback restores its own starting point.
        wobbleAnimator?.cancel()
        val baseX = lp.x
        val baseY = lp.y
        val distance = dp(9).toFloat()
        var lastOffset = 0f
        glassView?.setMotionBlur(0f)
        root.scaleX = 1f
        root.scaleY = 1f

        // Slow damped oscillation: less springy than the old rubber-like overshoot.
        val animator = ValueAnimator.ofFloat(0f, 1f)
        wobbleAnimator = animator
        animator.duration = 880L
        animator.interpolator = android.view.animation.LinearInterpolator()
        animator.addUpdateListener { frame ->
            val t = frame.animatedValue as Float
            val envelope = exp(-4.15f * t)
            val phase = t * Math.PI.toFloat() * 3.8f
            val offset = -distance * envelope * sin(phase)
            lp.x = baseX + offset.roundToInt()
            lp.y = baseY
            try { windowManager.updateViewLayout(root, lp) } catch (_: Exception) { }

            // Blur strength follows per-frame travel; there is no permanent halo around the pill.
            val frameTravel = abs(offset - lastOffset)
            val blur = (frameTravel * 0.52f).coerceIn(0f, 0.78f)
            val direction = if (offset < lastOffset) -1f else 1f
            glassView?.setMotionBlur(blur, direction)
            lastOffset = offset
        }
        animator.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                lp.x = baseX
                lp.y = baseY
                root.translationX = 0f
                root.translationY = 0f
                root.scaleX = 1f
                root.scaleY = 1f
                glassView?.setMotionBlur(0f, 1f)
                try { windowManager.updateViewLayout(root, lp) } catch (_: Exception) { }
                if (wobbleAnimator === animator) wobbleAnimator = null
            }
        })
        animator.start()
    }

    private fun openCustomizationPanel() {
        if (panelOpen) return
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
        val desiredDp = when {
            panelOpen -> screenWidthDp - 24
            isLandscapeWithSideCutout() -> if (screenRecording) maxOf(heightDp, 42) else heightDp
            screenRecording -> maxOf(widthDp, 178)
            else -> widthDp
        }
        val maxWidthPx = (screenWidthPx - dp(12)).coerceAtLeast(dp(36))
        return dp(desiredDp).coerceIn(dp(if (isLandscapeWithSideCutout() && !panelOpen) 34 else 126), maxWidthPx)
    }

    private fun targetHeight(): Int = when {
        panelOpen -> dp(252)
        isLandscapeWithSideCutout() -> dp(if (screenRecording) maxOf(widthDp, 178) else widthDp)
        screenRecording -> dp(maxOf(heightDp, 42))
        else -> dp(heightDp)
    }

    private fun gravityForAlignment(): Int = when (alignment) {
        "left" -> Gravity.TOP or Gravity.LEFT
        "right" -> Gravity.TOP or Gravity.RIGHT
        else -> Gravity.TOP or Gravity.CENTER_HORIZONTAL
    }

    private fun gravityForWindow(): Int = when {
        isLandscapeWithSideCutout() -> {
            // In landscape the physical camera cutout moves to a side. Keep the pill upright
            // along that edge instead of letting the position follow a portrait top-center assumption.
            Gravity.TOP or Gravity.LEFT
        }
        isPortraitWithTopCutout() -> Gravity.TOP or Gravity.CENTER_HORIZONTAL
        else -> gravityForAlignment()
    }

    private fun horizontalOffset(): Int = if (alignment == "center") 0 else dp(12)

    private fun displayWidthPx(): Int = if (Build.VERSION.SDK_INT >= 30) {
        try { windowManager.currentWindowMetrics.bounds.width() } catch (_: Exception) { resources.displayMetrics.widthPixels }
    } else resources.displayMetrics.widthPixels

    private fun displayHeightPx(): Int = if (Build.VERSION.SDK_INT >= 30) {
        try { windowManager.currentWindowMetrics.bounds.height() } catch (_: Exception) { resources.displayMetrics.heightPixels }
    } else resources.displayMetrics.heightPixels

    private fun currentCameraCutoutBounds(): Rect? {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                return windowManager.currentWindowMetrics.windowInsets.displayCutout?.boundingRects
                    ?.filterNot { it.isEmpty }
                    ?.maxByOrNull { it.width().toLong() * it.height().toLong() }
                    ?.let { Rect(it) }
            } catch (_: Exception) { }
        }
        if (Build.VERSION.SDK_INT >= 28) {
            try {
                return islandRoot?.rootWindowInsets?.displayCutout?.boundingRects
                    ?.filterNot { it.isEmpty }
                    ?.maxByOrNull { it.width().toLong() * it.height().toLong() }
                    ?.let { Rect(it) }
            } catch (_: Exception) { }
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
        if (isLandscapeWithSideCutout()) {
            if (expanded) return ((displayWidthPx() - windowWidth) / 2).coerceAtLeast(0)
            val cutout = currentCameraCutoutBounds()
            if (cutout != null) return (cutout.centerX() - windowWidth / 2).coerceAtLeast(0)
        }
        if (isPortraitWithTopCutout()) {
            val cutout = currentCameraCutoutBounds()
            if (cutout != null) return cutout.centerX() - displayWidthPx() / 2
        }
        return horizontalOffset()
    }

    private fun positionY(windowHeight: Int = targetHeight(), expanded: Boolean = panelOpen): Int {
        if (isLandscapeWithSideCutout()) {
            if (expanded) return dp(topOffsetDp)
            val cutout = currentCameraCutoutBounds()
            if (cutout != null) return (cutout.centerY() - windowHeight / 2).coerceAtLeast(0)
        }
        return overlayTopY()
    }

    private fun updatePosition() {
        val root = islandRoot ?: return
        val lp = params ?: return
        lp.width = targetWidth()
        lp.height = targetHeight()
        lp.gravity = gravityForWindow()
        lp.x = positionX(lp.width, panelOpen)
        lp.y = positionY(lp.height, panelOpen)
        try { windowManager.updateViewLayout(root, lp) } catch (_: Exception) { }
        root.background = roundedBackground(if (panelOpen) 30f else heightDp / 2f, Color.BLACK)
        glassView?.configure(glassStrength, tint, expandedNow = panelOpen)
    }

    private fun render(animateSize: Boolean = false) {
        val root = islandRoot ?: return
        val rootContent = content ?: return
        val prefs = AppPrefs.get(this)
        resizeAnimator?.cancel()
        wobbleAnimator?.cancel()
        recordingPulse?.cancel()
        recordingPulse = null
        recordingTextTypewriter?.let { handler.removeCallbacks(it) }
        recordingTextTypewriter = null
        rootContent.removeAllViews()
        rootContent.visibility = View.VISIBLE
        rootContent.setPadding(dp(if (panelOpen) 18 else 8), dp(if (panelOpen) 15 else 2), dp(if (panelOpen) 18 else 8), dp(if (panelOpen) 14 else 2))
        root.background = roundedBackground(if (panelOpen) 30f else heightDp / 2f, Color.BLACK)
        root.clipToOutline = true
        root.clipChildren = true
        root.clipToPadding = true
        glassView?.configure(glassStrength, tint, expandedNow = panelOpen)
        glassView?.visibility = if (panelOpen) View.GONE else View.VISIBLE
        updatePrivacyDot()

        val customUri = prefs.getString(PANEL_BACKGROUND_URI, null)
        val showMediaOnTop = (panelOpen || closingPanelWithMedia) && !customUri.isNullOrBlank()
        if (panelOpen) {
            if (showMediaOnTop) {
                // The selected local media becomes the whole panel. No text/buttons are layered over it.
                rootContent.visibility = View.GONE
            } else {
                val runIntro = playPanelIntro
                playPanelIntro = false
                renderCustomizationPanel(rootContent, runIntro)
            }
        } else if (screenRecording) {
            renderRecordingIndicator(rootContent)
        }
        syncPanelBackground()

        val lp = params
        if (lp != null) {
            if (animateSize) animateToTargetSize()
            else {
                lp.width = targetWidth()
                lp.height = targetHeight()
                lp.gravity = gravityForWindow()
                lp.x = positionX(lp.width, panelOpen)
                lp.y = positionY(lp.height, panelOpen)
                try { windowManager.updateViewLayout(root, lp) } catch (_: Exception) { }
            }
        }
        applyNativeActivityVisibility()
    }

    private fun updatePrivacyDot() {
        val dot = idleDot ?: return
        val color = if (cameraActive) 0xFF30D158.toInt() else 0xFFFF9F0A.toInt()
        (dot.background as? GradientDrawable)?.setColor(color)
        dot.alpha = if (cameraActive) 1f else 0.96f
        dot.visibility = if (!panelOpen && !screenRecording) View.VISIBLE else View.GONE
        dot.contentDescription = if (cameraActive) "Camera đang hoạt động" else "DigitalLiquid trạng thái chờ"
    }

    private fun applyNativeActivityVisibility() {
        val root = islandRoot ?: return
        val lp = params ?: return
        // Native media/timer activities suppress the idle pill even when an accessibility scan
        // temporarily misses HyperIsland. Screen recording and camera mode take priority because
        // DigitalLiquid's recording indicator/privacy dot is the requested companion effect.
        val nativeIslandVisible = hyperIslandVisibilityOverride == true || nativeActivityActive
        val suppress = !screenRecording && !cameraActive && nativeIslandVisible
        val wantedVisibility = if (suppress) View.GONE else View.VISIBLE
        val hasNotTouchable = (lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) != 0
        if (root.visibility != wantedVisibility || hasNotTouchable != suppress) {
            root.visibility = wantedVisibility
            lp.flags = if (suppress) {
                lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            } else {
                lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            }
            try { windowManager.updateViewLayout(root, lp) } catch (_: Exception) { }
        }
    }

    // With insets disabled on this window, y=0 is already the physical screen top.
    // Subtracting status-bar height placed the pill partly outside the display and made
    // its touch region inconsistent. Use the chosen offset directly.
    private fun overlayTopY(): Int = dp(topOffsetDp)

    private fun statusBarHeightPx(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else dp(24)
    }

    private fun renderRecordingIndicator(target: LinearLayout) {
        val row = LinearLayout(this).apply {
            orientation = if (isLandscapeWithSideCutout()) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
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
        val recLabel = TextView(this).apply {
            text = ""
            textSize = 11f
            letterSpacing = 0.08f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_VERTICAL
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
        }
        row.addView(recLabel, LinearLayout.LayoutParams(-2, dp(22)))
        target.addView(row, LinearLayout.LayoutParams(-1, -1))
        startRecordingTypewriter(recLabel)

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

    private fun startRecordingTypewriter(labelView: TextView) {
        recordingTextTypewriter?.let { handler.removeCallbacks(it) }
        val phrase = "REC"
        var index = 0
        var deleting = false
        val task = object : Runnable {
            override fun run() {
                if (!screenRecording || islandRoot == null || !labelView.isAttachedToWindow) return
                index = if (deleting) (index - 1).coerceAtLeast(0) else (index + 1).coerceAtMost(phrase.length)
                labelView.text = phrase.substring(0, index)
                labelView.translationX = if (deleting) dp(1).toFloat() else -dp(1).toFloat()
                labelView.alpha = 0.72f + 0.28f * index / phrase.length.toFloat()
                if (Build.VERSION.SDK_INT >= 31) {
                    val blur = if (deleting) 0.8f else 0.35f
                    try { labelView.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(blur, 0f, android.graphics.Shader.TileMode.CLAMP)) } catch (_: Exception) { }
                }
                if (!deleting && index >= phrase.length) {
                    deleting = true
                    handler.postDelayed(this, 460L)
                    return
                }
                if (deleting && index <= 0) {
                    labelView.translationX = 0f
                    labelView.alpha = 1f
                    if (Build.VERSION.SDK_INT >= 31) try { labelView.setRenderEffect(null) } catch (_: Exception) { }
                    deleting = false
                    handler.postDelayed(this, 160L)
                    return
                }
                handler.postDelayed(this, if (deleting) 55L else 70L)
            }
        }
        recordingTextTypewriter = task
        handler.postDelayed(task, 80L)
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
        val shouldShow = (panelOpen || closingPanelWithMedia) && !uriString.isNullOrBlank()
        if (!shouldShow) {
            stopPanelMedia()
            host.removeAllViews()
            lastBackgroundUri = null
            host.visibility = View.GONE
            scrim.visibility = View.GONE
            rootContent.visibility = if (panelOpen) View.VISIBLE else View.VISIBLE
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
        val mime = try { contentResolver.getType(uri).orEmpty() } catch (_: Exception) { "" }
        if (mime.startsWith("video/", ignoreCase = true)) {
            val texture = TextureView(this)
            panelTextureView = texture
            texture.isOpaque = true
            texture.isClickable = false
            texture.isFocusable = false
            texture.background = roundedBackground(30f, Color.BLACK)
            texture.clipToOutline = true
            texture.outlineProvider = ViewOutlineProvider.BACKGROUND
            texture.elevation = dp(8).toFloat()
            texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(surfaceTexture: android.graphics.SurfaceTexture, width: Int, height: Int) {
                    val surface = Surface(surfaceTexture)
                    panelVideoSurface = surface
                    val player = MediaPlayer()
                    panelMediaPlayer = player
                    try {
                        player.setDataSource(this@IslandService, uri)
                        player.setSurface(surface)
                        player.isLooping = true
                        player.setVolume(0f, 0f)
                        player.setOnVideoSizeChangedListener { _, videoWidth, videoHeight ->
                            applyVideoCenterCrop(texture, videoWidth, videoHeight)
                        }
                        player.setOnPreparedListener { prepared ->
                            applyVideoCenterCrop(texture, prepared.videoWidth, prepared.videoHeight)
                            prepared.start()
                        }
                        player.setOnErrorListener { _, _, _ ->
                            stopPanelMedia()
                            true
                        }
                        player.prepareAsync()
                    } catch (_: Exception) {
                        stopPanelMedia()
                    }
                }
                override fun onSurfaceTextureSizeChanged(surfaceTexture: android.graphics.SurfaceTexture, width: Int, height: Int) {
                    val player = panelMediaPlayer
                    if (player != null) applyVideoCenterCrop(texture, player.videoWidth, player.videoHeight)
                }
                override fun onSurfaceTextureDestroyed(surfaceTexture: android.graphics.SurfaceTexture): Boolean {
                    stopPanelMedia()
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

    private fun stopPanelMedia() {
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

    private fun animateToTargetSize() {
        val root = islandRoot ?: return
        val lp = params ?: return
        resizeAnimator?.cancel()
        val opening = panelOpen
        val startWidth = lp.width.toFloat()
        val startHeight = lp.height.toFloat()
        val startX = lp.x.toFloat()
        val startY = lp.y.toFloat()
        val endWidth = targetWidth().toFloat()
        val endHeight = targetHeight().toFloat()
        val endX = positionX(endWidth.roundToInt(), panelOpen).toFloat()
        val endY = positionY(endHeight.roundToInt(), panelOpen).toFloat()
        lp.gravity = gravityForWindow()
        if (AppPrefs.get(this).getBoolean(AppPrefs.REDUCE_MOTION, false)) {
            lp.width = endWidth.roundToInt()
            lp.height = endHeight.roundToInt()
            lp.x = endX.roundToInt()
            lp.y = endY.roundToInt()
            root.background = roundedBackground(if (panelOpen) 30f else heightDp / 2f, Color.BLACK)
            root.scaleX = 1f
            root.scaleY = 1f
            try { windowManager.updateViewLayout(root, lp) } catch (_: Exception) { }
            if (closingPanelWithMedia) {
                closingPanelWithMedia = false
                syncPanelBackground()
            }
            applyNativeActivityVisibility()
            return
        }
        root.alpha = 0.98f
        root.scaleX = if (opening) 0.94f else 1.045f
        root.scaleY = if (opening) 0.90f else 1.035f
        resizeAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (panelOpen) 420L else 360L
            interpolator = PathInterpolator(0.16f, 0.92f, 0.18f, 1f)
            addUpdateListener { animator ->
                val t = animator.animatedValue as Float
                lp.width = (startWidth + (endWidth - startWidth) * t).roundToInt()
                lp.height = (startHeight + (endHeight - startHeight) * t).roundToInt()
                lp.x = (startX + (endX - startX) * t).roundToInt()
                lp.y = (startY + (endY - startY) * t).roundToInt()
                root.background = roundedBackground(
                    if (panelOpen) 30f else (lp.height / resources.displayMetrics.density) / 2f,
                    Color.BLACK
                )
                root.scaleX = if (opening) 0.94f + 0.06f * t else 1.045f - 0.045f * t
                root.scaleY = if (opening) 0.90f + 0.10f * t else 1.035f - 0.035f * t
                if (Build.VERSION.SDK_INT >= 31) {
                    val blur = (if (opening) 1f - t else t) * 1.15f
                    try { root.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(blur, blur, android.graphics.Shader.TileMode.CLAMP)) } catch (_: Exception) { }
                }
                try { windowManager.updateViewLayout(root, lp) } catch (_: Exception) { }
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    root.scaleX = 1f
                    root.scaleY = 1f
                    root.alpha = 1f
                    if (Build.VERSION.SDK_INT >= 31) try { root.setRenderEffect(null) } catch (_: Exception) { }
                    if (closingPanelWithMedia) {
                        closingPanelWithMedia = false
                        syncPanelBackground()
                    }
                    applyNativeActivityVisibility()
                }
            })
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
        handler.removeCallbacks(keyAuthorizationWatchdog)
        instance = null
        AppPrefs.get(this).edit().putBoolean(AppPrefs.SERVICE_ENABLED, false).apply()
        resizeAnimator?.cancel()
        wobbleAnimator?.cancel()
        recordingPulse?.cancel()
        panelTypewriter?.let { handler.removeCallbacks(it) }
        recordingTextTypewriter?.let { handler.removeCallbacks(it) }
        stopPanelMedia()
        try { unregisterReceiver(configReceiver) } catch (_: Exception) { }
        try { unregisterReceiver(orientationReceiver) } catch (_: Exception) { }
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
        @Volatile private var lastHyperIslandVisibilityOverride: Boolean? = null

        fun refreshOverlayWindowType() {
            val service = instance ?: return
            service.handler.post { service.rebuildOverlayForAccessibilityMode() }
        }

        @Volatile private var lastNotificationScreenRecording = false
        @Volatile private var lastAccessibilityScreenRecording = false
        @Volatile private var lastNativeActivityActive = false
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
            updateCombinedRecordingState()
        }

        fun setAccessibilityScreenRecordingActive(active: Boolean) {
            lastAccessibilityScreenRecording = active
            updateCombinedRecordingState()
        }

        private fun updateCombinedRecordingState() {
            val combined = lastNotificationScreenRecording || lastAccessibilityScreenRecording
            val service = instance ?: return
            service.handler.post {
                if (service.screenRecording == combined) return@post
                service.notificationScreenRecording = lastNotificationScreenRecording
                service.accessibilityScreenRecording = lastAccessibilityScreenRecording
                service.screenRecording = combined
                service.render(animateSize = true)
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
