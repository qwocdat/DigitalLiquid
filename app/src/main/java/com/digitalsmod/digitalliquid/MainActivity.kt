package com.digitalsmod.digitalliquid

import android.app.Activity
import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.Window
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import android.widget.TextView
import kotlin.random.Random

class MainActivity : Activity() {
    companion object { private const val REQUEST_PANEL_BACKGROUND = 2409 }

    private var launchedForPanelPicker = false
    private var unsupportedDevice = false
    private var keyAuthorized = false
    private var authorizedUiInitialized = false
    private var remoteCheckInFlight = false
    private var lastRemoteCheckAt = 0L
    private var remoteDialogVisible = false
    private var remoteBlocked = false
    private var tab = 0
    private var onboardingPage = 0
    private val uiHandler = Handler(Looper.getMainLooper())
    private var welcomeTypewriter: Runnable? = null
    private lateinit var shell: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var pageContent: LinearLayout
    private var titleLabel: TextView? = null
    private var subtitleLabel: TextView? = null
    private var bottomNavView: View? = null
    private var lastScrollY = 0
    private val assistantHistory = mutableListOf<Pair<String, String>>()

    private val prefs by lazy { AppPrefs.get(this) }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun readSystemProperty(name: String): String {
        return try {
            val cls = Class.forName("android.os.SystemProperties")
            val method = cls.getMethod("get", String::class.java, String::class.java)
            (method.invoke(null, name, "") as? String).orEmpty()
        } catch (_: Throwable) {
            ""
        }
    }

    /** Allows only Xiaomi/Redmi/POCO devices whose ROM identifies itself as HyperOS 3+. */
    private fun checkDeviceCompatibility(): Pair<Boolean, String> {
        val familyIdentity = listOf(Build.MANUFACTURER, Build.BRAND, Build.DEVICE, Build.PRODUCT)
            .joinToString(" ").lowercase()
        val isXiaomiFamily = listOf("xiaomi", "redmi", "poco").any { familyIdentity.contains(it) }
        if (!isXiaomiFamily) {
            return false to "DigitalLiquid chỉ hỗ trợ điện thoại Xiaomi, Redmi và POCO chạy HyperOS 3 trở lên. Thiết bị hiện tại không thuộc nhóm được hỗ trợ."
        }

        val osIdentity = listOf(
            readSystemProperty("ro.mi.os.version.name"),
            readSystemProperty("ro.mi.os.version.incremental"),
            Build.DISPLAY
        ).filter { it.isNotBlank() }.joinToString(" ")
        // Avoid interpreting Android's generic text like “Android OS 16” as Xiaomi HyperOS.
        val versionMatch = Regex("(?i)\\bOS([0-9]+)(?:\\.[0-9]+)*").find(osIdentity)
            ?: Regex("(?i)HyperOS\\s+([0-9]+)(?:\\.[0-9]+)*").find(osIdentity)
        val major = versionMatch?.groupValues?.getOrNull(1)?.toIntOrNull()
        // Xiaomi's documented focus-protocol value is 3 for OS3 island support; use it first
        // because a version string may be hidden or vendor-formatted differently.
        val focusProtocolVersion = try {
            Settings.System.getInt(contentResolver, "notification_focus_protocol", 0)
        } catch (_: Exception) { 0 }
        val supported = focusProtocolVersion >= 3 || (major != null && major >= 3)
        if (!supported) {
            val found = buildString {
                append(osIdentity.ifBlank { "không xác định được phiên bản ROM" })
                if (focusProtocolVersion > 0) append(" · notification_focus_protocol=$focusProtocolVersion")
            }
            return false to "DigitalLiquid yêu cầu Xiaomi/Redmi/POCO chạy HyperOS 3, HyperOS 4 hoặc mới hơn. MIUI và HyperOS 2 trở xuống không được hỗ trợ. Hệ thống phát hiện: $found"
        }
        return true to ""
    }

    private fun renderUnsupportedDevice(reason: String) {
        unsupportedDevice = true
        try { stopService(Intent(this, IslandService::class.java)) } catch (_: Exception) { }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(32), dp(28), dp(32))
            background = GradientDrawable().apply {
                setColor(Color.rgb(7, 8, 12))
            }
        }
        root.addView(TextView(this).apply {
            text = "DigitalLiquid"
            textSize = 27f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        })
        root.addView(TextView(this).apply {
            text = "THIẾT BỊ KHÔNG ĐƯỢC HỖ TRỢ"
            textSize = 12f
            letterSpacing = 0.08f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(0xFFFF6B6B.toInt())
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
        root.addView(TextView(this).apply {
            text = reason
            textSize = 14f
            setTextColor(0xFF6B798A.toInt())
            gravity = Gravity.CENTER
            setLineSpacing(dp(4).toFloat(), 1f)
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        val exit = Button(this).apply {
            text = "Thoát"
            setTextColor(Color.WHITE)
            background = roundedGradient(intArrayOf(0xFF242631.toInt(), 0xFF151720.toInt()), 16, 0x35FFFFFF)
            setOnClickListener { finish() }
        }
        root.addView(exit, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(24) })
        setContentView(root)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Spatial Glass redesign migration. Existing feature preferences remain untouched; only
        // the first-launch palette is migrated to the new obsidian/champagne visual identity.
        if (!prefs.getBoolean("ui_spatial_glass_migrated", false)) {
            prefs.edit().putBoolean(AppPrefs.LIGHT_MODE, false)
                .putBoolean("ui_spatial_glass_migrated", true).apply()
        }
        applySystemBars()
        val compatibility = checkDeviceCompatibility()
        if (!compatibility.first) {
            renderUnsupportedDevice(compatibility.second)
            return
        }

        // Keep the app behind a blank surface until the server accepts a typed key.
        setContentView(LinearLayout(this).apply {
            setBackgroundColor(Color.rgb(7, 8, 12))
            layoutParams = ViewGroup.LayoutParams(-1, -1)
        })
        KeyAuthGate.requireKey(this) {
            if (isFinishing || isDestroyed) return@requireKey
            keyAuthorized = true
            initializeAuthorizedUi()
        }
    }

    private fun initializeAuthorizedUi() {
        if (!keyAuthorized || authorizedUiInitialized || isFinishing || isDestroyed) return
        authorizedUiInitialized = true
        if (prefs.getBoolean(AppPrefs.ONBOARDING_DONE, false)) renderMain() else renderOnboarding(0)
        checkOnlineControl(force = true)
        maybePickPanelBackground(intent)
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        applySystemBars()
        if (!keyAuthorized || isFinishing || isDestroyed) return
        if (prefs.getBoolean(AppPrefs.ONBOARDING_DONE, false)) {
            val savedScroll = if (::scroll.isInitialized) scroll.scrollY else lastScrollY
            renderMain()
            if (::scroll.isInitialized) {
                scroll.post {
                    val childHeight = scroll.getChildAt(0)?.height ?: 0
                    val maxScroll = (childHeight - scroll.height).coerceAtLeast(0)
                    scroll.scrollTo(0, savedScroll.coerceIn(0, maxScroll))
                }
            }
        } else {
            renderOnboarding(onboardingPage)
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent != null) setIntent(intent)
        // A service/panel intent must not open a picker or other app UI before the key gate passes.
        if (keyAuthorized) maybePickPanelBackground(intent)
    }

    private fun openBackgroundPicker() {
        val picker = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*"))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        try {
            startActivityForResult(picker, REQUEST_PANEL_BACKGROUND)
        } catch (_: Exception) {
            LiquidNotice.show(this, "Không mở được trình chọn ảnh/video.")
        }
    }

    private fun maybePickPanelBackground(sourceIntent: Intent?) {
        if (sourceIntent?.getBooleanExtra(IslandService.EXTRA_PICK_PANEL_BACKGROUND, false) != true) return
        launchedForPanelPicker = true
        sourceIntent.removeExtra(IslandService.EXTRA_PICK_PANEL_BACKGROUND)
        window.decorView.post { openBackgroundPicker() }
    }

    @Deprecated("Deprecated in Android API, still supported for ACTION_OPEN_DOCUMENT compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PANEL_BACKGROUND) return
        if (resultCode == RESULT_OK) {
            val resultIntent = data
            val uri = resultIntent?.data
            if (resultIntent != null && uri != null) {
                val mediaKind = PanelBackgroundMedia.kind(this, uri)
                if (mediaKind == null) {
                    LiquidNotice.show(this, "Định dạng không được hỗ trợ. Hãy chọn ảnh hoặc video phổ biến (MP4, MOV, JPG, PNG, WebP…).", 3600L)
                } else {
                    val grantedFlags = resultIntent.flags and
                        (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    val persistableGrant = (resultIntent.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) != 0
                    if (persistableGrant && grantedFlags != 0) {
                        try { contentResolver.takePersistableUriPermission(uri, grantedFlags) } catch (_: SecurityException) { }
                        catch (_: Exception) { }
                    }
                    prefs.edit()
                        .putString(IslandService.PANEL_BACKGROUND_URI, uri.toString())
                        .putString(AppPrefs.PANEL_BACKGROUND_KIND, mediaKind)
                        .apply()
                    sendBroadcast(Intent(IslandService.ACTION_APPLY_CONFIG).setPackage(packageName))
                    LiquidNotice.show(this, if (mediaKind == "video") "Đã chọn video nền. Video sẽ phát khi mở bảng tùy chỉnh." else "Đã cập nhật nền ảnh.")
                    if (!launchedForPanelPicker && prefs.getBoolean(AppPrefs.ONBOARDING_DONE, false)) renderMain()
                }
            }
        }
        if (launchedForPanelPicker) {
            launchedForPanelPicker = false
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        if (unsupportedDevice) return
        val compatibility = checkDeviceCompatibility()
        if (!compatibility.first) {
            renderUnsupportedDevice(compatibility.second)
            return
        }
        // Do not render the dashboard/onboarding while the key dialog is pending or the user is in Get Key.
        if (!keyAuthorized) return
        if (prefs.getBoolean(AppPrefs.ONBOARDING_DONE, false)) renderMain()
        checkOnlineControl(force = false)
    }

    private fun applySystemBars() {
        val lightMode = prefs.getBoolean(AppPrefs.LIGHT_MODE, false)
        window.statusBarColor = if (lightMode) 0xFFF4F6FB.toInt() else Color.rgb(10, 11, 16)
        window.navigationBarColor = if (lightMode) 0xFFF4F6FB.toInt() else Color.rgb(10, 11, 16)
        val lightFlags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        window.decorView.systemUiVisibility = if (lightMode) {
            window.decorView.systemUiVisibility or lightFlags
        } else {
            window.decorView.systemUiVisibility and lightFlags.inv()
        }
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        if (Build.VERSION.SDK_INT >= 29) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }
    }


    private fun installRootInsets(root: View, left: Int, top: Int, right: Int, bottom: Int) {
        if (Build.VERSION.SDK_INT < 30) return
        root.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            view.setPadding(left, top + bars.top, right, bottom + bars.bottom)
            insets
        }
        root.requestApplyInsets()
    }

    private fun renderOnboarding(page: Int) {
        onboardingPage = 0
        welcomeTypewriter?.let { uiHandler.removeCallbacks(it) }
        val root = FrameLayout(this).apply {
            background = appBackground()
            clipChildren = false
            clipToPadding = false
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(26), dp(28), dp(26), dp(30))
        }
        column.addView(ImageView(this).apply {
            setImageResource(R.drawable.digitalliquid_brand)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "DigitalLiquid"
        }, LinearLayout.LayoutParams(dp(76), dp(76)).apply { bottomMargin = dp(26) })

        val welcome = TextView(this).apply {
            text = ""
            textSize = 31f
            letterSpacing = -0.02f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
        }
        column.addView(welcome, LinearLayout.LayoutParams(-1, dp(64)))
        column.addView(View(this).apply {
            background = rounded(0xFFC99A5B.toInt(), 50)
        }, LinearLayout.LayoutParams(dp(28), dp(3)).apply { topMargin = dp(16); bottomMargin = dp(26) })
        column.addView(ImageView(this).apply {
            setImageResource(R.drawable.digitalliquid_hero)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = ""
        }, LinearLayout.LayoutParams(-1, dp(230)).apply { bottomMargin = dp(24) })
        column.addView(textButton("Bắt đầu sử dụng  →", true) {
            completeOnboarding()
        }, LinearLayout.LayoutParams(-1, dp(54)))
        root.addView(column, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        applyPaletteToTree(root)
        applySystemBars()
        installRootInsets(root, 0, 0, 0, 0)
        column.alpha = 0f
        column.translationY = dp(8).toFloat()
        column.animate().alpha(1f).translationY(0f).setDuration(420L).setInterpolator(android.view.animation.PathInterpolator(0.18f, 0.82f, 0.2f, 1f)).start()
        animateWelcomeTypewriter(welcome)
    }

    private fun animateWelcomeTypewriter(target: TextView) {
        welcomeTypewriter?.let { uiHandler.removeCallbacks(it) }
        val label = "Chào mừng"
        var index = 0
        var deleting = false
        val task = object : Runnable {
            override fun run() {
                if (!target.isAttachedToWindow || prefs.getBoolean(AppPrefs.ONBOARDING_DONE, false)) return
                index = if (deleting) (index - 1).coerceAtLeast(0) else (index + 1).coerceAtMost(label.length)
                target.text = label.substring(0, index)
                val progress = index.toFloat() / label.length.coerceAtLeast(1)
                target.alpha = 0.78f + progress * 0.22f
                target.translationX = if (deleting) dp(1).toFloat() else -dp(1).toFloat()
                if (Build.VERSION.SDK_INT >= 31) {
                    val radius = if (deleting) 0.85f else 0.4f
                    try { target.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(radius, 0f, android.graphics.Shader.TileMode.CLAMP)) } catch (_: Exception) { }
                }
                if (!deleting && index >= label.length) {
                    deleting = true
                    uiHandler.postDelayed(this, 850L)
                    return
                }
                if (deleting && index <= 0) {
                    target.translationX = 0f
                    target.alpha = 1f
                    if (Build.VERSION.SDK_INT >= 31) try { target.setRenderEffect(null) } catch (_: Exception) { }
                    deleting = false
                    uiHandler.postDelayed(this, 240L)
                    return
                }
                uiHandler.postDelayed(this, if (deleting) 40L else 55L)
            }
        }
        welcomeTypewriter = task
        uiHandler.postDelayed(task, 180L)
    }

    private fun completeOnboarding() {
        welcomeTypewriter?.let { uiHandler.removeCallbacks(it) }
        welcomeTypewriter = null
        prefs.edit().putBoolean(AppPrefs.ONBOARDING_DONE, true).apply()
        tab = 0
        renderMain()
    }

    private fun renderMain() {
        if (!prefs.getBoolean(AppPrefs.ONBOARDING_DONE, false)) {
            renderOnboarding(onboardingPage)
            return
        }
        shell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(8))
            background = appBackground()
        }
        shell.addView(buildTopBar(), marginBottom(18))
        scroll = ScrollView(this).apply {
            isFillViewport = false
            clipToPadding = false
            setPadding(0, 0, 0, dp(8))
        }
        pageContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        scroll.addView(pageContent)
        shell.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        when (tab) {
            0 -> buildOverview(pageContent)
            1 -> buildAppearance(pageContent)
            2 -> buildAssistant(pageContent)
            3 -> buildFeatures(pageContent)
            else -> buildSettings(pageContent)
        }
        bottomNavView = buildBottomNav()
        // GlassTabBar contains MATCH_PARENT children; WRAP_CONTENT here can make
        // FrameLayout measure itself as tall as the available screen. Keep it compact.
        shell.addView(bottomNavView, LinearLayout.LayoutParams(-1, dp(68)).apply {
            topMargin = dp(4)
        })
        lastScrollY = 0
        scroll.setOnScrollChangeListener { _, _, y, _, oldY ->
            val collapsed = y > dp(28)
            titleLabel?.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, if (collapsed) 17f else 27f)
            if (collapsed) {
                subtitleLabel?.animate()?.alpha(0f)?.setDuration(120L)?.withEndAction {
                    subtitleLabel?.visibility = View.GONE
                }?.start()
            } else {
                subtitleLabel?.visibility = View.VISIBLE
                subtitleLabel?.animate()?.alpha(1f)?.setDuration(180L)?.start()
            }
            val nav = bottomNavView
            if (y > oldY && y > dp(48)) {
                nav?.animate()?.translationY(dp(90).toFloat())?.alpha(0f)?.setDuration(170L)?.start()
            } else if (y < oldY || y <= dp(24)) {
                nav?.visibility = View.VISIBLE
                nav?.animate()?.translationY(0f)?.alpha(1f)?.setDuration(220L)?.start()
            }
            lastScrollY = y
        }
        shell.alpha = 0f
        shell.translationY = dp(7).toFloat()
        setContentView(shell)
        applyPaletteToTree(shell)
        applySystemBars()
        installRootInsets(shell, dp(18), dp(18), dp(18), dp(8))
        shell.animate().alpha(1f).translationY(0f).setDuration(240L).start()
    }

    private fun buildTopBar(): View {
        val subtitle = when (tab) {
            0 -> "ĐẢO NỔI"
            1 -> "GIAO DIỆN"
            2 -> "TRỢ LÝ AI"
            3 -> "TÍNH NĂNG"
            else -> "CÀI ĐẶT"
        }
        return GlassNavBar(
            this,
            "DigitalLiquid",
            subtitle,
            prefs.getBoolean(AppPrefs.SERVICE_ENABLED, false)
        ).also { bar ->
            titleLabel = bar.titleView
            subtitleLabel = bar.subtitleView
        }
    }

    private fun buildOverview(target: LinearLayout) {
        addEyebrow(target, "LIQUID EDITION  ·  SPATIAL GLASS")
        target.addView(TextView(this).apply {
            text = "Một giao diện. Một chút phép màu."
            textSize = 25f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(Color.WHITE)
        }, marginTop(4))
        target.addView(bodyText("Đảo nổi tối giản, chất liệu kính khúc xạ và hiệu ứng trợ lý lấy cảm hứng từ ánh sáng trên mặt kính."), marginTop(7))
        target.addView(buildSiriHero(), marginTop(18))

        val hero = card().apply { setPadding(dp(16), dp(16), dp(16), dp(16)) }
        val heroHeader = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        heroHeader.addView(TextView(this).apply {
            text = "ĐẢO NỔI"
            textSize = 10f
            letterSpacing = 0.08f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(0xFFD9AE71.toInt())
        }, LinearLayout.LayoutParams(0, -2, 1f))
        heroHeader.addView(TextView(this).apply {
            text = if (prefs.getBoolean(AppPrefs.SERVICE_ENABLED, false)) "ĐANG CHẠY" else "CHƯA BẬT"
            textSize = 9f
            setTextColor(0xFF9DA2B1.toInt())
        })
        hero.addView(heroHeader)
        hero.addView(buildGlassPreview(), marginTop(15))
        hero.addView(TextView(this).apply {
            text = "Khi rảnh, viên thuốc luôn đen đặc. Chạm để rung lắc; giữ để mở bảng tùy chỉnh."
            textSize = 11f
            setTextColor(0xFF9397A6.toInt())
        }, marginTop(12))
        hero.addView(textButton(
            if (prefs.getBoolean(AppPrefs.SERVICE_ENABLED, false)) "Tắt đảo nổi" else "Bật đảo nổi  →",
            primary = !prefs.getBoolean(AppPrefs.SERVICE_ENABLED, false)
        ) {
            if (prefs.getBoolean(AppPrefs.SERVICE_ENABLED, false)) stopIsland() else startIsland()
        }, marginTop(15))
        target.addView(hero, marginTop(20))

        addEyebrow(target, "CÀI ĐẶT NHANH", 24)
        target.addView(permissionStatusCard(), marginTop(8))
        target.addView(buildOnlineControlCard(), marginTop(12))
        target.addView(featureMiniCard("TRỢ NĂNG", "Lớp phủ trên thanh trạng thái", if (hasHyperIslandAccessibility()) "Đã bật lớp phủ tin cậy và theo dõi HyperIsland." else "Bắt buộc để viên thuốc nằm trên status bar và nhận chạm trong vùng camera."), marginTop(10))
        target.addView(textButton(if (hasHyperIslandAccessibility()) "Quản lý quyền Trợ năng" else "Bật quyền Trợ năng", false) {
            openAccessibilitySettings()
        }, marginTop(8))
        target.addView(featureMiniCard("QUAY MÀN HÌNH", "Hiệu ứng ghi hình", if (hasNotificationAccess()) "Đã cấp quyền để phát hiện thông báo quay màn hình của hệ thống." else "Cần bật Notification Access để phát hiện trạng thái quay màn hình."), marginTop(10))
        target.addView(textButton(if (hasNotificationAccess()) "Mở cài đặt quyền thông báo" else "Bật quyền thông báo", false) {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }, marginTop(8))
        target.addView(infoCard("Tương tác", "Chạm một lần để viên thuốc rung lắc với vệt mờ chuyển động. Giữ khoảng nửa giây để mở bảng đen; tải ảnh hoặc video trong máy để dùng làm nền."), marginTop(18))
    }

    private fun buildSiriHero(): View {
        val outer = card().apply {
            setPadding(dp(14), dp(12), dp(14), dp(14))
            clipChildren = false
            clipToPadding = false
        }
        val caption = TextView(this).apply {
            text = "SIRI  /  SPATIAL ASSISTANT"
            textSize = 9.5f
            letterSpacing = 0.16f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(0xFFE8C995.toInt())
        }
        outer.addView(caption)
        val stage = FrameLayout(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF17171A.toInt(), 0xFF101319.toInt(), 0xFF1B1712.toInt())).apply {
                cornerRadius = dp(25).toFloat()
                setStroke(dp(1), 0x36E9C68E)
            }
            clipChildren = false
            clipToPadding = false
            contentDescription = "Xem trước hiệu ứng Siri Glass"
        }
        stage.addView(SiriGlassOrbView(this), FrameLayout.LayoutParams(-1, dp(188), Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        val stageLabel = TextView(this).apply {
            text = "KÍCH HOẠT HIỆU ỨNG"
            textSize = 9f
            letterSpacing = 0.18f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            setTextColor(0xFFC9C3B9.toInt())
        }
        stage.addView(stageLabel, FrameLayout.LayoutParams(-1, dp(26), Gravity.BOTTOM))
        outer.addView(stage, LinearLayout.LayoutParams(-1, dp(210)).apply { topMargin = dp(10) })
        outer.addView(TextView(this).apply {
            text = "Quả cầu kính · quang phổ chuyển động · viền phản xạ"
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(0xFFA7A5A1.toInt())
        }, marginTop(10))
        outer.addView(textButton("Mở giao diện Siri  ↗", true) { openSiriVisual() }, marginTop(12))
        return outer
    }

    private fun openSiriVisual() {
        try {
            // The visual fallback is declared as a real Activity and does not depend on the
            // phone accepting DigitalLiquid as its system-wide assistant first.
            startActivity(Intent(this, AssistantFallbackActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            })
        } catch (error: Exception) {
            LiquidNotice.show(this, "Không mở được hiệu ứng Siri: ${error.message ?: "kiểm tra cấu hình ứng dụng"}", 3600L)
        }
    }

    private fun buildOnlineControlCard(): View {
        val outer = card().apply { setPadding(dp(15), dp(14), dp(15), dp(14)) }
        val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val textColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        textColumn.addView(TextView(this@MainActivity).apply {
            text = "ĐIỀU KHIỂN TRỰC TUYẾN"
            textSize = 10f
            letterSpacing = 0.08f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(0xFFC99A5B.toInt())
        })
        textColumn.addView(TextView(this@MainActivity).apply {
            text = "Thông báo · phiên bản · bảo trì"
            textSize = 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Color.WHITE)
        }, marginTop(5))
        line.addView(textColumn, LinearLayout.LayoutParams(0, -2, 1f))
        line.addView(TextView(this@MainActivity).apply {
            text = "↻"
            textSize = 22f
            setTextColor(0xFFD9AE71.toInt())
        })
        outer.addView(line)
        outer.addView(textButton("Kiểm tra máy chủ", false) {
            checkOnlineControl(force = true)
            LiquidNotice.show(this@MainActivity, "Đang kiểm tra trạng thái dịch vụ…")
        }, marginTop(10))
        return outer
    }

    private fun currentInstalledVersionCode(): Int {
        val info = packageManager.getPackageInfo(packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt() else legacyVersionCode(info)
    }

    @Suppress("DEPRECATION")
    private fun legacyVersionCode(info: android.content.pm.PackageInfo): Int = info.versionCode

    private fun checkOnlineControl(force: Boolean) {
        if (!keyAuthorized || isFinishing || isDestroyed || remoteCheckInFlight || remoteBlocked) return
        val now = System.currentTimeMillis()
        if (!force && now - lastRemoteCheckAt < 45_000L) return
        remoteCheckInFlight = true
        lastRemoteCheckAt = now
        OnlineControlManager.fetch(this) { control, error ->
            remoteCheckInFlight = false
            if (isFinishing || isDestroyed) return@fetch
            if (control == null) {
                if (force) LiquidNotice.show(this, "Không kết nối được dịch vụ trực tuyến. ${if (error.isNullOrBlank()) "Hãy kiểm tra kết nối mạng rồi thử lại." else "Vui lòng thử lại sau."}", 3600L)
                return@fetch
            }
            if (!control.configFound) {
                if (force) LiquidNotice.show(this, "Dịch vụ trực tuyến chưa sẵn sàng. Hãy thử lại sau.", 3400L)
                return@fetch
            }
            val currentVersion = try {
                currentInstalledVersionCode()
            } catch (_: Exception) { 0 }

            if (!control.signerMatches) {
                remoteBlocked = true
                prefs.edit().putBoolean(AppPrefs.SERVICE_ENABLED, false).apply()
                stopService(Intent(this, IslandService::class.java))
                LiquidDialog.show(
                    this,
                    eyebrow = "DIGITALLIQUID · BẢO MẬT",
                    title = "Bản dựng chưa được xác minh",
                    message = "Chữ ký của ứng dụng không khớp với chứng thư phát hành được cho phép. Hãy cài bản chính thức từ nguồn phát hành.",
                    cancelable = false,
                    actions = listOf(LiquidDialog.Action("Đóng ứng dụng", primary = true) { finish() })
                )
                return@fetch
            }
            if (!control.appEnabled || control.maintenanceEnabled) {
                remoteBlocked = true
                prefs.edit().putBoolean(AppPrefs.SERVICE_ENABLED, false).apply()
                stopService(Intent(this, IslandService::class.java))
                val message = if (!control.appEnabled) "Phiên bản ứng dụng này đã bị quản trị viên vô hiệu hóa." else control.maintenanceMessage
                LiquidDialog.show(
                    this,
                    eyebrow = "DIGITALLIQUID · TRẠNG THÁI DỊCH VỤ",
                    title = if (!control.appEnabled) "Ứng dụng đang bị tạm dừng" else "DigitalLiquid đang bảo trì",
                    message = message,
                    cancelable = false,
                    actions = listOf(LiquidDialog.Action("Đóng ứng dụng", primary = true) { finish() })
                )
                return@fetch
            }

            val mandatoryUpdate = control.minVersionCode > currentVersion && control.downloadUrl.isNotBlank()
            val hasUpdate = control.latestVersionCode > currentVersion && control.downloadUrl.isNotBlank()
            val dismissedCode = prefs.getInt(AppPrefs.DISMISSED_UPDATE_CODE, 0)
            if (mandatoryUpdate || (hasUpdate && dismissedCode != control.latestVersionCode)) {
                if (remoteDialogVisible) return@fetch
                remoteDialogVisible = true
                val versionText = control.latestVersionName.ifBlank { "mã ${control.latestVersionCode}" }
                val updateActions = mutableListOf<LiquidDialog.Action>()
                if (!mandatoryUpdate) {
                    updateActions += LiquidDialog.Action("Để sau") {
                        prefs.edit().putInt(AppPrefs.DISMISSED_UPDATE_CODE, control.latestVersionCode).apply()
                    }
                }
                updateActions += LiquidDialog.Action("Tải bản cập nhật", primary = true) {
                    openRemoteUrl(control.downloadUrl)
                    if (mandatoryUpdate) finish()
                }
                val dialog = LiquidDialog.show(
                    this,
                    eyebrow = "DIGITALLIQUID · CẬP NHẬT",
                    title = if (mandatoryUpdate) "Cần cập nhật ứng dụng" else "Có phiên bản mới",
                    message = "Phiên bản mới: $versionText\n\n${if (mandatoryUpdate) "Phiên bản hiện tại đã hết thời gian hỗ trợ. Hãy cập nhật để tiếp tục sử dụng." else "Bản cập nhật mới đã sẵn sàng."}",
                    actions = updateActions,
                    cancelable = !mandatoryUpdate
                )
                dialog.setOnDismissListener { remoteDialogVisible = false }
                return@fetch
            }

            if (control.announcementEnabled && control.announcementMessage.isNotBlank()) {
                val announcementId = control.announcementId.ifBlank { control.announcementTitle + control.announcementMessage }
                if (prefs.getString(AppPrefs.LAST_ANNOUNCEMENT_ID, "") != announcementId && !remoteDialogVisible) {
                    remoteDialogVisible = true
                    val dialog = LiquidDialog.show(
                        this,
                        eyebrow = "DIGITALLIQUID · THÔNG BÁO",
                        title = control.announcementTitle.ifBlank { "Thông báo DigitalLiquid" },
                        message = control.announcementMessage,
                        actions = listOf(LiquidDialog.Action(
                            if (control.announcementUrl.isBlank()) "Đã hiểu" else "Mở liên kết",
                            primary = true
                        ) {
                            prefs.edit().putString(AppPrefs.LAST_ANNOUNCEMENT_ID, announcementId).apply()
                            if (control.announcementUrl.isNotBlank()) openRemoteUrl(control.announcementUrl)
                        })
                    )
                    dialog.setOnDismissListener {
                        prefs.edit().putString(AppPrefs.LAST_ANNOUNCEMENT_ID, announcementId).apply()
                        remoteDialogVisible = false
                    }
                }
            } else if (force) {
                LiquidNotice.show(this, "Đã kiểm tra dịch vụ. Không có thông báo hoặc cập nhật mới.")
            }
        }
    }

    private fun openRemoteUrl(url: String) {
        try {
            val uri = Uri.parse(url)
            if (uri.scheme != "https" && uri.scheme != "http") throw IllegalArgumentException("URL không hợp lệ")
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (_: Exception) {
            LiquidNotice.show(this, "Liên kết tải xuống không hợp lệ.")
        }
    }

    private fun buildAppearance(target: LinearLayout) {
        addEyebrow(target, "VỊ TRÍ & CHUYỂN ĐỘNG")
        target.addView(TextView(this).apply {
            text = "Một viên thuốc. Không chiếm màn hình."
            textSize = 23f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(Color.WHITE)
        }, marginTop(4))
        target.addView(bodyText("Bề mặt khi rảnh giữ màu đen đặc, không viền quang học và không làm mờ ứng dụng phía sau."), marginTop(7))
        target.addView(sectionTitle("Chế độ giao diện"), marginTop(18))
        target.addView(card().apply {
            setPadding(dp(10), dp(10), dp(10), dp(10))
            addView(segmentedOptions(
                listOf("Tối" to "dark", "Sáng" to "light"),
                if (prefs.getBoolean(AppPrefs.LIGHT_MODE, false)) "light" else "dark"
            ) { selected ->
                prefs.edit().putBoolean(AppPrefs.LIGHT_MODE, selected == "light").apply()
                applySystemBars()
                renderMain()
            })
        }, marginTop(8))
        val previewCard = card().apply { setPadding(dp(14), dp(16), dp(14), dp(16)) }
        previewCard.addView(buildGlassPreview(), LinearLayout.LayoutParams(-1, dp(64)))
        previewCard.addView(TextView(this).apply {
            text = "CHẠM: RUNG LẮC  ·  GIỮ: TÙY CHỈNH"
            textSize = 9.5f
            letterSpacing = 0.055f
            setTextColor(0xFFD9AE71.toInt())
            gravity = Gravity.CENTER
        }, marginTop(12))
        target.addView(previewCard, marginTop(18))

        val selectedBackground = prefs.getString(IslandService.PANEL_BACKGROUND_URI, null)
        target.addView(textButton(
            if (selectedBackground.isNullOrBlank()) "Chọn ảnh hoặc video nền" else "Thay ảnh/video nền",
            primary = true
        ) { openBackgroundPicker() }, marginTop(14))
        if (!selectedBackground.isNullOrBlank()) {
            target.addView(textButton("Xóa nền đã chọn", primary = false) {
                prefs.edit().remove(IslandService.PANEL_BACKGROUND_URI).remove(AppPrefs.PANEL_BACKGROUND_KIND).apply()
                applyConfig()
                renderMain()
            }, marginTop(7))
        }

        target.addView(sectionTitle("Kích thước"), marginTop(22))
        target.addView(seekCard("Chiều rộng", "72–330 dp · có thể thu nhỏ sát cụm camera", AppPrefs.WIDTH, 72, 330, " dp") { applyConfig() }, marginTop(8))
        target.addView(seekCard("Chiều cao", "22–72 dp", AppPrefs.HEIGHT, 22, 72, " dp") { applyConfig() }, marginTop(8))

        target.addView(sectionTitle("Vị trí"), marginTop(22))
        target.addView(card().apply {
            addView(TextView(this@MainActivity).apply {
                text = "Căn ngang"
                textSize = 13f
                setTextColor(Color.WHITE)
            })
            addView(segmentedOptions(
                listOf("Trái" to "left", "Giữa" to "center", "Phải" to "right"),
                prefs.getString(AppPrefs.ALIGNMENT, "center") ?: "center"
            ) { selected -> prefs.edit().putString(AppPrefs.ALIGNMENT, selected).apply(); applyConfig(); renderMain() }, marginTop(10))
        }.apply { setPadding(dp(15), dp(15), dp(15), dp(15)) }, marginTop(8))
        target.addView(seekCard("Vị trí dọc", "0 là sát mép trên trong vùng thanh trạng thái", AppPrefs.OFFSET_TOP, 0, 140, " dp") { applyConfig() }, marginTop(8))
        target.addView(toggleCard("Giảm chuyển động", "Tắt chuyển động rung lắc và co giãn; giữ giao diện cơ bản.", AppPrefs.REDUCE_MOTION), marginTop(18))
        target.addView(infoCard("Hiệu ứng theo tần số màn hình", "Chuyển động dùng animation phần cứng và cập nhật theo nhịp khung hình Android. Tốc độ thực tế vẫn do tần số quét, hiệu năng và chính sách hệ thống của máy quyết định."), marginTop(14))
    }

    private fun buildFeatures(target: LinearLayout) {
        addEyebrow(target, "TÍCH HỢP HYPEROS")
        target.addView(TextView(this).apply {
            text = "Bổ sung, không giành việc của hệ thống"
            textSize = 22f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(Color.WHITE)
        }, marginTop(4))
        target.addView(bodyText("DigitalLiquid không tạo lại giao diện cho nhạc, ghi âm, hẹn giờ, bấm giờ hay phát nền. Các hoạt động đó giữ nguyên cách HyperOS hiển thị. Ứng dụng chỉ thêm hiệu ứng riêng cho lúc quay màn hình."), marginTop(7))
        target.addView(infoCard("Hiệu ứng quay màn hình", "Khi HyperOS hoặc trình ghi màn hình phát thông báo đang quay, viên thuốc mở rộng nhẹ và hiển thị chấm REC nhịp sáng. Khi thông báo biến mất, viên thuốc trở về trạng thái đen ban đầu."), marginTop(18))
        target.addView(featureMiniCard("QUYỀN CẦN THIẾT", "Truy cập thông báo", if (hasNotificationAccess()) "Đã cấp quyền phát hiện thông báo ghi màn hình." else "Cấp quyền để phát hiện trạng thái quay màn hình do hệ thống thông báo."), marginTop(10))
        target.addView(textButton(if (hasNotificationAccess()) "Quản lý quyền thông báo" else "Bật quyền thông báo", false) {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }, marginTop(8))
        target.addView(featureMiniCard("HYPERISLAND", "Tự ẩn khi hệ thống xuất hiện", if (hasHyperIslandAccessibility()) "Đã bật quan sát giao diện hệ thống." else "Bật Trợ năng để đặt viên thuốc lên lớp thanh trạng thái và thử nhận biết lúc HyperIsland xuất hiện/biến mất."), marginTop(16))
        target.addView(textButton(if (hasHyperIslandAccessibility()) "Quản lý quyền Trợ năng" else "Bật theo dõi HyperIsland", false) {
            openAccessibilitySettings()
        }, marginTop(8))
        target.addView(infoCard("Nền bảng tùy chỉnh", "Chọn ảnh hoặc video tại mục Vị trí & Chuyển động trong ứng dụng. Khi mở bảng, nội dung đã chọn chiếm toàn bộ bề mặt, không bị chữ hoặc nút che. Video lặp không tiếng."), marginTop(14))
    }

    private fun buildAssistant(target: LinearLayout) {
        addEyebrow(target, "DIGITALLIQUID  ·  SIRI GLASS")
        target.addView(buildSiriHero(), marginTop(10))
        target.addView(TextView(this).apply {
            text = "Hỏi DigitalLiquid"
            textSize = 30f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(Color.WHITE)
        }, marginTop(4))
        target.addView(bodyText("Chọn mô hình, nhập câu hỏi và nhận câu trả lời ngay trong DigitalLiquid. Thông tin truy cập AI được xử lý an toàn ở phía dịch vụ."), marginTop(7))

        val config = card().apply { setPadding(dp(15), dp(15), dp(15), dp(15)) }
        config.addView(sectionTitle("Model mặc định"))
        var selectedModel = AiModels.selectedId(this)
        val modelOptions = AiModels.all.map { it.label to it.id }.toMutableList()
        if (AiModels.all.none { it.id == selectedModel }) modelOptions.add("Tùy chỉnh · ${selectedModel.substringAfterLast('/') }" to selectedModel)
        val modelRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val lightPalette = prefs.getBoolean(AppPrefs.LIGHT_MODE, false)
        fun renderModelPicker() {
            modelRow.removeAllViews()
            modelOptions.forEachIndexed { index, option ->
                val active = option.second == selectedModel
                val chip = TextView(this).apply {
                    text = option.first
                    textSize = 10.5f
                    maxLines = 1
                    gravity = Gravity.CENTER
                    setPadding(dp(12), 0, dp(12), 0)
                    setTextColor(if (active) 0xFF102727.toInt() else if (lightPalette) 0xFF3A4554.toInt() else 0xFFE1E5EF.toInt())
                    background = GradientDrawable(
                        GradientDrawable.Orientation.LEFT_RIGHT,
                        if (active) intArrayOf(0xFF82EAD3.toInt(), 0xFF48C3BB.toInt())
                        else if (lightPalette) intArrayOf(0xFFF6FAFC.toInt(), 0xFFE7EEF4.toInt())
                        else intArrayOf(0xFF242936.toInt(), 0xFF171B25.toInt())
                    ).apply {
                        cornerRadius = dp(14).toFloat()
                        setStroke(dp(1), if (active) 0x80FFFFFF.toInt() else if (lightPalette) 0x447A91A7 else 0x35FFFFFF)
                    }
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        selectedModel = option.second
                        prefs.edit().putString(AppPrefs.AI_MODEL, selectedModel).apply()
                        renderModelPicker()
                    }
                }
                modelRow.addView(chip, LinearLayout.LayoutParams(-2, dp(37)).apply {
                    if (index > 0) marginStart = dp(7)
                })
            }
        }
        renderModelPicker()
        config.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(modelRow, ViewGroup.LayoutParams(-2, -1))
        }, LinearLayout.LayoutParams(-1, dp(39)).apply { topMargin = dp(8) })
        config.addView(textButton("Nhập model ID tùy chỉnh", false) { editAiModel() }, marginTop(8))
        config.addView(textButton("Cấu hình endpoint máy chủ", false) { editAiEndpoint() }, marginTop(8))
        val endpointText = prefs.getString(AppPrefs.AI_ENDPOINT, "").orEmpty().ifBlank { "Chưa cấu hình máy chủ HTTPS" }
        config.addView(bodyText(endpointText), marginTop(8))
        target.addView(config, marginTop(14))

        val chatCard = card().apply { setPadding(dp(15), dp(15), dp(15), dp(15)) }
        chatCard.addView(sectionTitle("Cuộc trò chuyện"))
        val response = TextView(this).apply {
            text = if (assistantHistory.isEmpty()) "DigitalLiquid sẵn sàng. Hãy nhập câu hỏi bên dưới." else assistantHistory.takeLast(6).joinToString("\n\n") { (role, text) -> (if (role == "user") "Bạn" else "DigitalLiquid AI") + ":\n" + text }
            textSize = 13f
            setTextColor(0xFFE5E6F0.toInt())
            setLineSpacing(dp(3).toFloat(), 1.08f)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = rounded(0xA5181A25.toInt(), 16)
            minHeight = dp(130)
            gravity = Gravity.TOP or Gravity.START
        }
        chatCard.addView(response, marginTop(10))
        val prompt = EditText(this).apply {
            hint = "Viết câu hỏi của bạn…"
            setHintTextColor(0xFF777B8A.toInt())
            setTextColor(Color.WHITE)
            textSize = 14f
            minLines = 2
            maxLines = 5
            gravity = Gravity.TOP or Gravity.START
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = rounded(0xFF151722.toInt(), 16)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        chatCard.addView(prompt, marginTop(10))
        val send = textButton("Gửi câu hỏi", true) {
            val text = prompt.text?.toString()?.trim().orEmpty()
            if (text.isBlank()) {
                LiquidNotice.show(this, "Hãy nhập câu hỏi trước.")
            } else {
                assistantHistory.add("user" to text)
                response.text = "Đang xử lý câu hỏi…"
                prompt.isEnabled = false
                val model = AiModels.selectedId(this)
                OpenRouterClient.chatMessages(this, model, assistantHistory.toList()) { answer, error ->
                    prompt.isEnabled = true
                    if (answer != null) {
                        assistantHistory.add("assistant" to answer)
                        response.text = assistantHistory.takeLast(8).joinToString("\n\n") { (role, message) -> (if (role == "user") "Bạn" else "DigitalLiquid AI") + ":\n" + message }
                        prompt.setText("")
                    } else {
                        // Keep the user's prompt so it can be retried once the endpoint is configured.
                        response.text = (assistantHistory.takeLast(4).joinToString("\n\n") { (role, message) -> (if (role == "user") "Bạn" else "DigitalLiquid AI") + ":\n" + message } + "\n\nLỗi: " + (error ?: "Không rõ lỗi"))
                    }
                }
            }
        }
        chatCard.addView(send, marginTop(10))
        chatCard.addView(textButton("Xóa lịch sử trò chuyện", false) {
            assistantHistory.clear()
            response.text = "Đã xóa lịch sử. Hãy đặt câu hỏi mới."
            prompt.setText("")
        }, marginTop(7))
        target.addView(chatCard, marginTop(14))
        target.addView(infoCard("Kết nối AI", "Nhập địa chỉ dịch vụ AI bảo mật do bạn quản lý. Không lưu khóa bí mật trực tiếp trong ứng dụng."), marginTop(14))
    }

    private fun editAiEndpoint() {
        val input = EditText(this).apply {
            hint = "https://ai-service.example"
            setText(prefs.getString(AppPrefs.AI_ENDPOINT, "").orEmpty())
            setTextColor(Color.WHITE)
            setHintTextColor(0xFF8E9AAC.toInt())
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded(0xD91B2636.toInt(), 14)
        }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 0)
            addView(input, LinearLayout.LayoutParams(-1, -2))
        }
        LiquidDialog.show(
            this,
            eyebrow = "TRỢ LÝ AI · KẾT NỐI",
            title = "Kết nối AI bảo mật",
            message = "Nhập địa chỉ HTTPS của dịch vụ AI bảo mật. Không nhập khóa bí mật vào ứng dụng.",
            content = form,
            actions = listOf(
                LiquidDialog.Action("Hủy"),
                LiquidDialog.Action("Xóa", dismissOnClick = true) {
                    prefs.edit().remove(AppPrefs.AI_ENDPOINT).apply()
                    renderMain()
                },
                LiquidDialog.Action("Lưu", primary = true, dismissOnClick = false) { dialog ->
                    val value = input.text?.toString()?.trim().orEmpty().trimEnd('/')
                    if (value.isNotBlank() && !value.startsWith("https://", true)) {
                        input.error = "Endpoint phải bắt đầu bằng https://"
                        return@Action
                    }
                    prefs.edit().putString(AppPrefs.AI_ENDPOINT, value).apply()
                    dialog.dismiss()
                    renderMain()
                }
            )
        )
    }

    private fun editAiModel() {
        val input = EditText(this).apply {
            hint = "provider/model-id"
            setText(AiModels.selectedId(this@MainActivity))
            setTextColor(Color.WHITE)
            setHintTextColor(0xFF8E9AAC.toInt())
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded(0xD91B2636.toInt(), 14)
        }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(input, LinearLayout.LayoutParams(-1, -2))
        }
        LiquidDialog.show(
            this,
            eyebrow = "TRỢ LÝ AI · MÔ HÌNH",
            title = "Model ID tùy chỉnh",
            message = "Mô hình phải nằm trong danh sách được dịch vụ AI cho phép.",
            content = form,
            actions = listOf(
                LiquidDialog.Action("Hủy"),
                LiquidDialog.Action("Lưu", primary = true, dismissOnClick = false) { dialog ->
                    val value = input.text?.toString()?.trim().orEmpty()
                    if (value.isBlank() || !value.contains('/')) {
                        input.error = "Dùng định dạng provider/model-id"
                        return@Action
                    }
                    prefs.edit().putString(AppPrefs.AI_MODEL, value).apply()
                    dialog.dismiss()
                    renderMain()
                }
            )
        )
    }

    private fun buildSettings(target: LinearLayout) {
        addEyebrow(target, "QUYỀN & RIÊNG TƯ")
        target.addView(TextView(this).apply {
            text = "Cài đặt hệ thống"
            textSize = 24f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(Color.WHITE)
        }, marginTop(4))
        target.addView(bodyText("Các quyền đặc biệt của Android không thể tự bật. Bạn sẽ được chuyển sang trang Cài đặt tương ứng."), marginTop(7))
        target.addView(settingAction("Trợ lý mặc định", if (isDigitalLiquidAssistant()) "Đã chọn DigitalLiquid" else "Chưa chọn DigitalLiquid", "Sau khi chọn DigitalLiquid làm trợ lý mặc định, giữ nút nguồn theo cử chỉ trợ lý của hệ thống khoảng 2 giây để gọi giao diện Siri. Android/HyperOS vẫn có thể thay đổi thời lượng hoặc cử chỉ này.") {
            requestAssistantRole()
        }, marginTop(10))
        target.addView(settingAction("Quyền truy cập thông báo", if (hasNotificationAccess()) "Đã cấp quyền" else "Chưa cấp quyền", "Đọc trạng thái thông báo đang hoạt động để phát hiện quay màn hình, nhạc và đồng hồ") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }, marginTop(10))
        val microphoneGranted = Build.VERSION.SDK_INT < 23 || checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        target.addView(settingAction("Micro và nhận dạng giọng nói", if (microphoneGranted) "Đã cấp quyền" else "Chưa cấp quyền", "Cho phép dùng nút Nói trong giao diện Siri. Giọng nói chỉ được chuyển thành văn bản trên thiết bị thông qua dịch vụ nhận dạng Android.") {
            if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 8110)
            } else {
                try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) } catch (_: Exception) { }
            }
        }, marginTop(10))
        target.addView(settingAction("Theo dõi HyperIsland", if (hasHyperIslandAccessibility()) "Đã bật" else "Chưa bật", "Cho phép nhận diện HyperIsland và dùng lớp phủ Trợ năng đáng tin cậy để chạm được ở vùng thanh trạng thái. Nhận diện phụ thuộc phiên bản HyperOS.") {
            openAccessibilitySettings()
        }, marginTop(10))
        target.addView(settingAction("Không hạn chế pin", batteryOptimizationSummary(), "Giảm khả năng hệ thống dừng overlay nền") { requestBatteryExemption() }, marginTop(10))

        val reduceMotion = IOSSwitch(this).apply {
            isChecked = prefs.getBoolean(AppPrefs.REDUCE_MOTION, false)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean(AppPrefs.REDUCE_MOTION, checked).apply()
                applyConfig()
            }
        }
        target.addView(card().apply {
            val row = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(TextView(this@MainActivity).apply {
                text = "Giảm chuyển động"
                textSize = 14f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(Color.WHITE)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(reduceMotion, LinearLayout.LayoutParams(dp(51), dp(31)))
            addView(row)
            addView(bodyText("Tắt chuyển động rung lắc và co giãn. Viên thuốc vẫn hiển thị màu đen."), marginTop(5))
        }.apply { setPadding(dp(15), dp(14), dp(15), dp(14)) }, marginTop(10))

        target.addView(sectionTitle("Hiệu ứng DigitalLiquid"), marginTop(18))
        target.addView(toggleCard("Hiệu ứng REC khi quay màn hình", "Hiện chấm đỏ và pill điều khiển khi hệ thống xác nhận đang quay thật.", AppPrefs.SCREEN_RECORDING_EFFECT), marginTop(8))
        
        // DÒNG ĐÃ SỬA VỀ MÔ TẢ HIỆU ỨNG MỞ KHÓA Ở DƯỚI ĐÂY:
        target.addView(toggleCard("Hiệu ứng mở khóa", "Xuất hiện chính xác lúc vừa mở khoá thành công: pill đi xuống, nở thành ô vuông rồi hiển thị icon Face ID và tick xanh 100% giống iOS. (Đã khắc phục lỗi xuất hiện sớm)", AppPrefs.FACE_UNLOCK_EFFECT), marginTop(8))
        
        target.addView(toggleCard("Rung nhẹ khi chạm", "Mỗi lần chạm dùng hướng ngẫu nhiên, biên độ nhỏ hơn và chuyển động cân bằng hơn.", AppPrefs.TAP_WOBBLE), marginTop(8))
        target.addView(toggleCard("Motion blur", "Bật/tắt vệt mờ theo hướng chuyển động của viên thuốc.", AppPrefs.MOTION_BLUR), marginTop(8))
        target.addView(toggleCard("Nhãn Recording khi mở rộng", "Khi đang quay màn hình, giữ viên thuốc để xem nhãn Recording và bộ đếm thời gian. Điều khiển dừng vẫn do trình quay màn hình của hệ thống quản lý.", AppPrefs.RECORDING_STOP_CONTROL), marginTop(8))

        target.addView(sectionTitle("Ẩn / hiện thông minh"), marginTop(20))
        target.addView(toggleCard("Tự ẩn khi HyperIsland của hệ thống hiện", "Chỉ ẩn tạm thời khi popup HyperIsland của hệ thống xuất hiện ở vùng camera, rồi hiện lại khi popup biến mất.", AppPrefs.AUTO_HIDE_SYSTEM_ISLAND), marginTop(8))
        target.addView(toggleCard("Tự ẩn khi đang phát nhạc", "Theo dõi trạng thái MediaController và hiện lại khi nhạc dừng hoặc tạm dừng.", AppPrefs.HIDE_WHEN_MEDIA_PLAYING), marginTop(8))
        target.addView(toggleCard("Tự ẩn khi hoạt động hệ thống khác xuất hiện", "Áp dụng cho hẹn giờ, cuộc gọi và các hoạt động nền được NotificationListener nhận diện.", AppPrefs.HIDE_WHEN_NATIVE_ACTIVITY), marginTop(8))
        target.addView(toggleCard("Ẩn khi popup chuông/thông báo hiện", "Chỉ ẩn tạm thời khi HyperOS hiển thị popup do thao tác bật/tắt chuông hoặc thông báo; không phụ thuộc trạng thái im lặng đang bật hay tắt.", AppPrefs.HIDE_WHEN_RINGER_UI), marginTop(8))
        target.addView(toggleCard("Nhấn đúp viên thuốc để mở trợ lý AI", "Mở hiệu ứng Siri của DigitalLiquid. Nếu chưa chọn trợ lý mặc định, ứng dụng vẫn mở giao diện dự phòng riêng.", AppPrefs.DOUBLE_TAP_ASSISTANT), marginTop(8))
        target.addView(toggleCard("Nhấn từ 6 lần để chuyển ứng dụng", "Chạm nhanh 6 lần để kích hoạt. Mỗi lần chạm tiếp theo sẽ chuyển ngay sang ứng dụng gần đây khác. Ngừng chạm 1,8 giây để thoát chế độ. Cần bật quyền Truy cập mức sử dụng ứng dụng.", AppPrefs.ISLAND_APP_SWITCH_GESTURE), marginTop(8))
        target.addView(toggleCard("Thông báo Liquid Glass trong ứng dụng", "Hiển thị thông báo quản trị dưới dạng thẻ tùy chỉnh nổi khi lớp phủ được kết nối; nếu không thể hiện, dùng thông báo hệ thống làm dự phòng.", AppPrefs.CUSTOM_APP_NOTICES), marginTop(8))
        target.addView(toggleCard("Tương tác Live Preview", "Cho phép thử chạm, nhấn đúp, nhấn giữ và cử chỉ nhiều lần ngay trong bản xem trước.", AppPrefs.LIVE_PREVIEW_INTERACTIVE), marginTop(8))
        target.addView(settingAction("Truy cập mức sử dụng ứng dụng", if (RecentAppSwitcher.hasUsageAccess(this)) "Đã cấp quyền" else "Chưa cấp quyền", "Cho phép sắp xếp ứng dụng theo lần dùng gần nhất và chuyển ứng dụng. Quyền này do Android quản lý.") {
            try { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) } catch (_: Exception) { startActivity(Intent(Settings.ACTION_SETTINGS)) }
        }, marginTop(8))

        target.addView(sectionTitle("Trợ lý AI / Kết nối"), marginTop(20))
        target.addView(settingAction("Kết nối AI bảo mật", if (prefs.getString(AppPrefs.AI_ENDPOINT, "").orEmpty().isBlank()) "Chưa cấu hình" else "Đã lưu endpoint HTTPS", "Thiết lập địa chỉ dịch vụ AI. Thông tin bí mật không được lưu trực tiếp trong ứng dụng.") {
            editAiEndpoint()
        }, marginTop(8))
        target.addView(settingAction("Model AI mặc định", AiModels.labelFor(AiModels.selectedId(this)), "Mở tab Trợ lý AI để chọn model hoặc đặt model tùy chỉnh.") {
            tab = 2
            renderMain()
        }, marginTop(8))

        val autoStartSwitch = IOSSwitch(this).apply {
            isChecked = prefs.getBoolean(AppPrefs.AUTO_START, false)
            setOnCheckedChangeListener { _, checked ->
                if (checked) {
                    val authPrefs = getSharedPreferences("digitalsmod_key_auth", MODE_PRIVATE)
                    val savedKeyEnabled = authPrefs.getBoolean("save_key_enabled", false) && !authPrefs.getString("saved_key", null).isNullOrBlank()
                    if (!savedKeyEnabled) {
                        prefs.edit().putBoolean(AppPrefs.AUTO_START, false).apply()
                        isChecked = false
                        LiquidNotice.show(this@MainActivity, "Để tự khởi chạy an toàn, hãy xác thực key và chọn ‘Lưu key trên thiết bị’ trước.", 3600L)
                        return@setOnCheckedChangeListener
                    }
                }
                prefs.edit().putBoolean(AppPrefs.AUTO_START, checked).apply()
                LiquidNotice.show(this@MainActivity, if (checked) "Đã bật tự khởi chạy. HyperOS vẫn có thể yêu cầu quyền tự khởi động." else "Đã tắt tự khởi chạy.")
            }
        }
        target.addView(card().apply {
            val row = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(TextView(this@MainActivity).apply {
                text = "Tự khởi chạy sau khi bật máy"
                textSize = 14f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(Color.WHITE)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(autoStartSwitch, LinearLayout.LayoutParams(dp(51), dp(31)))
            addView(row)
            addView(bodyText("Chỉ khôi phục khi bạn đã bật đảo nổi, lưu key và máy chủ xác thực key vẫn còn hiệu lực. HyperOS có thể áp dụng thêm quyền Tự khởi động."), marginTop(6))
        }.apply { setPadding(dp(15), dp(14), dp(14), dp(14)) }, marginTop(18))

        target.addView(settingAction("Điều khiển trực tuyến", "Thông báo · phiên bản · bảo trì", "Ứng dụng nhận trạng thái vận hành và thông báo mới từ dịch vụ trực tuyến.") {
            checkOnlineControl(force = true)
            LiquidNotice.show(this, "Đang kiểm tra cấu hình trực tuyến…")
        }, marginTop(12))
        target.addView(infoCard("Bảo vệ ứng dụng", "Bản phát hành được kiểm tra tính toàn vẹn và chữ ký. Hãy cài ứng dụng từ nguồn phát hành chính thức để đảm bảo độ tin cậy."), marginTop(12))

        target.addView(settingAction("Chạy lại onboarding", "Xem lại giới thiệu", "Hiện lại màn hình Chào mừng") {
            prefs.edit().putBoolean(AppPrefs.ONBOARDING_DONE, false).apply()
            renderOnboarding(0)
        }, marginTop(22))
        target.addView(settingAction("Khôi phục mặc định", "Xóa các tùy chỉnh", "Đặt lại kích thước, vị trí và công tắc mô-đun") {
            LiquidDialog.show(
                this,
                eyebrow = "DIGITALLIQUID · CÀI ĐẶT",
                title = "Khôi phục mặc định?",
                message = "Các tùy chỉnh giao diện và mô-đun sẽ được đặt lại. Trạng thái giới thiệu ban đầu không đổi.",
                actions = listOf(
                    LiquidDialog.Action("Hủy"),
                    LiquidDialog.Action("Khôi phục", primary = true) {
                        prefs.edit().clear()
                            .putBoolean(AppPrefs.ONBOARDING_DONE, true)
                            .putBoolean(AppPrefs.SERVICE_ENABLED, false)
                            .putBoolean(AppPrefs.LIGHT_MODE, false)
                            .apply()
                        stopIsland()
                        renderMain()
                    }
                )
            )
        }, marginTop(10))
        target.addView(infoCard("DigitalLiquid", "DigitalLiquid chỉ cần các quyền bạn chủ động bật. Bạn có thể thu hồi quyền hoặc dừng viên thuốc bất cứ lúc nào."), marginTop(20))
    }

    private fun buildGlassPreview(): View {
        val frame = FrameLayout(this).apply {
            // Obsidian surface with a champagne rim matches the new Spatial Glass identity.
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF1A1B20.toInt(), 0xFF0B0D12.toInt(), 0xFF211A13.toInt())).apply {
                cornerRadius = dp(22).toFloat()
                setStroke(dp(1), 0x2DE3C18A)
            }
            clipChildren = false
            clipToPadding = false
            contentDescription = "Bản xem trước tương tác DigitalLiquid"
        }
        val pill = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                setColor(Color.BLACK)
                cornerRadius = dp(24).toFloat()
                setStroke(dp(1), 0x55383A44)
            }
            clipToOutline = true
            tag = "digitalliquid-preview-island"
            isClickable = true
            isFocusable = true
        }
        val glass = LiquidGlassView(this).apply {
            configure(78, "midnight", expandedNow = false)
            setMotionBlur(0f)
        }
        pill.addView(glass, FrameLayout.LayoutParams(-1, -1))
        val dot = View(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFFFFD36E.toInt()) }
            isClickable = false
        }
        pill.addView(dot, FrameLayout.LayoutParams(dp(4), dp(4), Gravity.CENTER_VERTICAL or Gravity.RIGHT).apply { rightMargin = dp(20) })
        val previewLabel = TextView(this).apply {
            text = "DigitalLiquid"
            textSize = 12f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            maxLines = 2
            visibility = View.GONE
            isClickable = false
        }
        pill.addView(previewLabel, FrameLayout.LayoutParams(-1, -1))
        frame.addView(pill, FrameLayout.LayoutParams(dp(150), dp(38), Gravity.CENTER))

        val previewModes = listOf(
            "DigitalLiquid  ·  Đang chờ",
            "♫  Now Playing  ·  00:31",
            "●  Recording  ·  00:08",
            "◷  Hẹn giờ  ·  00:12",
            "✦  DigitalLiquid AI"
        )
        var previewMode = 0
        var previewExpanded = false
        var previewLastTap = 0L
        var previewTapCount = 0
        var previewSwitchModeActive = false
        val previewHandler = Handler(Looper.getMainLooper())
        val previewSwitchReset = object : Runnable {
            override fun run() {
                previewSwitchModeActive = false
                previewTapCount = 0
                previewLastTap = 0L
            }
        }
        var previewLong = false
        val previewLongPress = Runnable {
            previewLong = true
            previewExpanded = !previewExpanded
            previewMode = (previewMode + 1) % previewModes.size
            previewLabel.text = previewModes[previewMode]
            previewLabel.visibility = if (previewExpanded) View.VISIBLE else View.GONE
            val targetW = if (previewExpanded) dp(276) else dp(150)
            val targetH = if (previewExpanded) dp(70) else dp(38)
            pill.animate().scaleX(targetW.toFloat() / dp(150)).scaleY(targetH.toFloat() / dp(38))
                .setDuration(280L).setInterpolator(android.view.animation.OvershootInterpolator(0.7f)).start()
            dot.visibility = if (previewExpanded) View.GONE else View.VISIBLE
            pill.contentDescription = if (previewExpanded) "Bảng xem trước: ${previewModes[previewMode]}" else "Viên thuốc đen DigitalLiquid"
            pill.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        }
        pill.setOnTouchListener { view, event ->
            if (!prefs.getBoolean(AppPrefs.LIVE_PREVIEW_INTERACTIVE, true)) return@setOnTouchListener false
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    previewLong = false
                    previewHandler.removeCallbacks(previewLongPress)
                    previewHandler.postDelayed(previewLongPress, 430L)
                    view.animate().scaleX(if (previewExpanded) 1.02f else 0.97f).scaleY(0.97f).setDuration(100L).start()
                    true
                }
                android.view.MotionEvent.ACTION_UP -> {
                    previewHandler.removeCallbacks(previewLongPress)
                    view.animate().scaleX(if (previewExpanded) 276f / 150f else 1f).scaleY(if (previewExpanded) 70f / 38f else 1f)
                        .setDuration(240L).setInterpolator(android.view.animation.OvershootInterpolator(0.7f)).start()
                    if (!previewLong) {
                        val now = android.os.SystemClock.elapsedRealtime()
                        val switchEnabled = prefs.getBoolean(AppPrefs.ISLAND_APP_SWITCH_GESTURE, false)
                        if (switchEnabled && previewSwitchModeActive) {
                            RecentAppSwitcher.switchNext(this@MainActivity)
                            previewHandler.removeCallbacks(previewSwitchReset)
                            previewHandler.postDelayed(previewSwitchReset, 1800L)
                            previewLastTap = now
                            return@setOnTouchListener true
                        }
                        previewTapCount = if (now - previewLastTap <= 720L) previewTapCount + 1 else 1
                        previewLastTap = now
                        when {
                            previewTapCount >= 6 && switchEnabled -> {
                                previewTapCount = 0
                                previewSwitchModeActive = true
                                previewHandler.removeCallbacks(previewSwitchReset)
                                previewHandler.postDelayed(previewSwitchReset, 1800L)
                            }
                            previewTapCount == 2 && prefs.getBoolean(AppPrefs.DOUBLE_TAP_ASSISTANT, false) -> {
                                view.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                                // Route to the real assistant screen, not a decorative fake response.
                                openSiriVisual()
                                return@setOnTouchListener true
                            }
                            previewExpanded -> {
                                previewMode = (previewMode + 1) % previewModes.size
                                previewLabel.text = previewModes[previewMode]
                                pill.contentDescription = "Bảng xem trước: ${previewModes[previewMode]}"
                                view.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                            }
                            else -> {
                                view.animate().rotationBy(if (Random.nextBoolean()) 2.5f else -2.5f).setDuration(90L).withEndAction {
                                    view.animate().rotation(0f).setDuration(180L).start()
                                }.start()
                            }
                        }
                    }
                    true
                }
                android.view.MotionEvent.ACTION_CANCEL -> {
                    previewHandler.removeCallbacks(previewLongPress)
                    view.animate().scaleX(if (previewExpanded) 276f / 150f else 1f).scaleY(if (previewExpanded) 70f / 38f else 1f).setDuration(180L).start()
                    true
                }
                else -> true
            }
        }
        frame.layoutParams = LinearLayout.LayoutParams(-1, dp(72))
        return frame
    }

    private fun permissionStatusCard(): View {
        val outer = card().apply { setPadding(dp(14), dp(14), dp(14), dp(14)) }
        outer.addView(TextView(this).apply {
            text = "TỔNG QUAN QUYỀN"
            textSize = 10f
            letterSpacing = 0.11f
            setTextColor(0xFFC99A5B.toInt())
        })
        outer.addView(statusLine("Lớp phủ trên thanh trạng thái", hasHyperIslandAccessibility()), marginTop(10))
        outer.addView(statusLine("Phát hiện quay màn hình", hasNotificationAccess()), marginTop(8))
        outer.addView(statusLine("Theo dõi HyperIsland", hasHyperIslandAccessibility()), marginTop(8))
        outer.addView(statusLine("Hiệu ứng viên thuốc", true, "Sẵn sàng"), marginTop(8))
        return outer
    }

    private fun statusLine(title: String, ready: Boolean, suffix: String? = null): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(View(this).apply { background = rounded(if (ready) 0xFF71E3A9.toInt() else 0xFFFFBC74.toInt(), 50) }, LinearLayout.LayoutParams(dp(7), dp(7)))
        row.addView(TextView(this).apply { text = title; textSize = 12f; setTextColor(0xFFE7E8EF.toInt()) }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(9) })
        row.addView(TextView(this).apply {
            text = suffix ?: if (ready) "Đã sẵn sàng" else "Cần cấp quyền"
            textSize = 10f
            setTextColor(if (ready) 0xFF91DAB2.toInt() else 0xFFFFC38D.toInt())
        })
        return row
    }

    private fun toggleCard(title: String, description: String, key: String): View {
        val outer = card().apply { setPadding(dp(14), dp(13), dp(14), dp(13)) }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(this@MainActivity).apply {
            text = title
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Color.WHITE)
        })
        texts.addView(bodyText(description), marginTop(5))
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        val toggle = IOSSwitch(this).apply {
            val defaultEnabled = key != AppPrefs.ISLAND_APP_SWITCH_GESTURE
            isChecked = prefs.getBoolean(key, defaultEnabled)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean(key, checked).apply()
                applyConfig()
            }
        }
        row.addView(toggle, startMarginParams(8))
        outer.addView(row)
        return outer
    }

    private fun seekCard(
        label: String,
        description: String,
        key: String,
        min: Int,
        maxValue: Int,
        suffix: String,
        onChange: () -> Unit
    ): View {
        val outer = card().apply { setPadding(dp(14), dp(12), dp(14), dp(10)) }
        val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        line.addView(TextView(this).apply { text = label; textSize = 13f; setTextColor(Color.WHITE) }, LinearLayout.LayoutParams(0, -2, 1f))
        val initial = prefs.getInt(key, when (key) {
            AppPrefs.WIDTH -> 148; AppPrefs.HEIGHT -> 38; AppPrefs.OFFSET_TOP -> 6; AppPrefs.GLASS_STRENGTH -> 72; else -> min
        }).coerceIn(min, maxValue)
        val value = TextView(this).apply {
            text = "$initial$suffix"
            textSize = 12f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(0xFF267F78.toInt())
            gravity = Gravity.END
        }
        line.addView(value, LinearLayout.LayoutParams(dp(72), -2))
        outer.addView(line)
        outer.addView(TextView(this).apply { text = description; textSize = 10f; setTextColor(0xFF8F94A6.toInt()) }, marginTop(4))
        val seek = IOSSlider(this).apply {
            setRange(min, maxValue)
            setValue(initial)
            onValueChanged = { actual, fromUser ->
                value.text = "$actual$suffix"
                if (fromUser) {
                    prefs.edit().putInt(key, actual).apply()
                    onChange()
                }
            }
        }
        outer.addView(seek, marginTop(5))
        return outer
    }

    private fun segmentedOptions(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        options.forEachIndexed { index, item ->
            row.addView(textButton(item.first, selected == item.second) { onSelect(item.second) }, LinearLayout.LayoutParams(0, dp(40), 1f).apply {
                if (index > 0) marginStart = dp(5)
            })
        }
        return row
    }

    private fun settingAction(title: String, status: String, description: String, action: () -> Unit): View {
        val outer = card().apply { setPadding(dp(15), dp(14), dp(14), dp(14)) }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val textColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        textColumn.addView(TextView(this@MainActivity).apply {
            text = title
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Color.WHITE)
        })
        textColumn.addView(TextView(this@MainActivity).apply { text = status; textSize = 11f; setTextColor(0xFFD9AE71.toInt()) }, marginTop(4))
        row.addView(textColumn, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(TextView(this).apply { text = "›"; textSize = 26f; setTextColor(0xFFD9AE71.toInt()) })
        outer.addView(row)
        outer.addView(bodyText(description), marginTop(8))
        outer.setOnClickListener { action() }
        outer.isClickable = true
        return outer
    }

    private fun featureMiniCard(index: String, title: String, description: String): View {
        val outer = card().apply { setPadding(dp(14), dp(14), dp(14), dp(14)) }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.TOP }
        row.addView(TextView(this@MainActivity).apply {
            text = index
            textSize = 10f
            letterSpacing = 0.06f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(0xFFD9AE71.toInt())
            gravity = Gravity.CENTER
            background = rounded(0x1EFFFEFF, 9)
        }, LinearLayout.LayoutParams(dp(40), dp(36)))
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(this@MainActivity).apply {
            text = title
            textSize = 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Color.WHITE)
        })
        texts.addView(bodyText(description), marginTop(5))
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(11) })
        outer.addView(row)
        return outer
    }

    private fun infoCard(title: String, text: String): View {
        val outer = LiquidGlassCardLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        outer.addView(TextView(this).apply {
            this.text = title
            textSize = 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(0xFFE8C995.toInt())
        })
        outer.addView(bodyText(text), marginTop(6))
        return outer
    }

    private fun sectionTitle(text: String) = TextView(this).apply {
        this.text = text
        textSize = 16f
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        setTextColor(Color.WHITE)
    }

    private fun addEyebrow(target: LinearLayout, text: String, topMarginDp: Int = 0) {
        val label = TextView(this).apply {
            this.text = text
            textSize = 10f
            letterSpacing = 0.13f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(0xFFC99A5B.toInt())
        }
        target.addView(label, marginTop(topMarginDp))
    }

    private fun bodyText(text: String) = TextView(this).apply {
        this.text = text
        textSize = 12f
        setLineSpacing(0f, 1.12f)
        setTextColor(0xFF9FA3B3.toInt())
    }

    private fun card() = LiquidGlassCardLayout(this).apply {
        orientation = LinearLayout.VERTICAL
    }

    private fun textButton(label: String, primary: Boolean, action: () -> Unit) = IOSButton(this).apply {
        style(label, primary)
        setOnClickListener { action() }
    }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    private fun roundedGradient(colors: IntArray, radius: Int, strokeColor: Int? = null) = GradientDrawable(
        GradientDrawable.Orientation.TL_BR, colors
    ).apply {
        cornerRadius = dp(radius).toFloat()
        strokeColor?.let { setStroke(dp(1), it) }
    }

    private fun appBackground(): android.graphics.drawable.Drawable =
        LiquidBackdropDrawable(prefs.getBoolean(AppPrefs.LIGHT_MODE, false))

    /** Soft radial lighting, with separate readable palettes for dark and light mode. */
    private class LiquidBackdropDrawable(private val lightMode: Boolean) : android.graphics.drawable.Drawable() {
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)

        override fun draw(canvas: android.graphics.Canvas) {
            val area = bounds
            if (area.isEmpty) return
            canvas.drawColor(if (lightMode) 0xFFF2F0EC.toInt() else Color.rgb(5, 6, 9))
            val width = area.width().toFloat()
            val height = area.height().toFloat()
            val radius = maxOf(width, height) * 0.78f
            paint.shader = android.graphics.RadialGradient(
                area.left + width * 0.08f, area.top + height * 0.06f, radius,
                if (lightMode) intArrayOf(0x75E6C99C, 0x39D8D4CC, 0x00F2F0EC)
                else intArrayOf(0x644C3020, 0x2A423124, 0x00050609),
                floatArrayOf(0f, 0.48f, 1f), android.graphics.Shader.TileMode.CLAMP
            )
            canvas.drawRect(area, paint)
            paint.shader = android.graphics.RadialGradient(
                area.left + width * 0.96f, area.top + height * 0.62f, radius * 0.78f,
                if (lightMode) intArrayOf(0x4ABBC9D1, 0x20E5D7BC, 0x00F2F0EC)
                else intArrayOf(0x323B4A5E, 0x1E8C6340, 0x00050609),
                floatArrayOf(0f, 0.55f, 1f), android.graphics.Shader.TileMode.CLAMP
            )
            canvas.drawRect(area, paint)
            paint.shader = null
        }

        override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
        override fun getOpacity(): Int = android.graphics.PixelFormat.OPAQUE
    }

    private fun applyPaletteToTree(root: View) {
        val lightMode = prefs.getBoolean(AppPrefs.LIGHT_MODE, false)
        if (!lightMode) return
        fun walk(view: View) {
            if (view.tag == "digitalliquid-preview-island") return
            if (view is EditText) {
                if (lightMode) {
                    view.setTextColor(0xFF182735.toInt())
                    view.setHintTextColor(0xFF758497.toInt())
                    view.background = rounded(0xFFF2F7FA.toInt(), 14).apply { setStroke(dp(1), 0x66829CAC) }
                }
                return
            }
            if (view is GlassTabBar || view is GlassNavBar || view is IOSButton) return
            if (view is TextView) {
                val old = view.currentTextColor
                val r = Color.red(old); val g = Color.green(old); val b = Color.blue(old)
                val luminance = (0.2126 * r + 0.7152 * g + 0.0722 * b).toInt()
                val mapped = when {
                    r > g * 1.35 && r > b * 1.35 -> 0xFFB12C39.toInt()
                    g > r * 1.15 && g > b * 1.06 -> 0xFF236C49.toInt()
                    b > r * 1.15 && b > g * 1.08 -> 0xFF3555A4.toInt()
                    r > 170 && b > 150 && g > 100 -> 0xFF167D74.toInt()
                    luminance > 165 -> 0xFF252838.toInt()
                    luminance > 112 -> 0xFF5A6072.toInt()
                    else -> old
                }
                view.setTextColor(mapped)
            }
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) walk(view.getChildAt(index))
            }
        }
        walk(root)
    }

    private fun marginTop(top: Int) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }
    private fun marginBottom(bottom: Int) = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(bottom) }
    private fun startMarginParams(start: Int) = LinearLayout.LayoutParams(-2, -2).apply { this.marginStart = dp(start) }

    private fun buildBottomNav(): View {
        return GlassTabBar(
            this,
            listOf(
                GlassTabBar.Item("⌂", "Đảo nổi"),
                GlassTabBar.Item("◉", "Giao diện"),
                GlassTabBar.Item("✦", "Trợ lý AI"),
                GlassTabBar.Item("◌", "Tính năng"),
                GlassTabBar.Item("⚙", "Cài đặt")
            ),
            tab
        ) { selected ->
            tab = selected
            renderMain()
        }
    }

    override fun onDestroy() {
        welcomeTypewriter?.let { uiHandler.removeCallbacks(it) }
        welcomeTypewriter = null
        super.onDestroy()
    }

    private fun isDigitalLiquidAssistant(): Boolean {
        if (Build.VERSION.SDK_INT < 29) return false
        return try {
            val manager = getSystemService(android.app.role.RoleManager::class.java)
            manager != null && manager.isRoleAvailable(android.app.role.RoleManager.ROLE_ASSISTANT) &&
                manager.isRoleHeld(android.app.role.RoleManager.ROLE_ASSISTANT)
        } catch (_: Exception) { false }
    }

    private fun requestAssistantRole() {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val manager = getSystemService(android.app.role.RoleManager::class.java)
                if (manager != null && manager.isRoleAvailable(android.app.role.RoleManager.ROLE_ASSISTANT)) {
                    startActivity(manager.createRequestRoleIntent(android.app.role.RoleManager.ROLE_ASSISTANT))
                    return
                }
            }
            startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
        } catch (_: Exception) {
            try { startActivity(Intent(Settings.ACTION_SETTINGS)) } catch (_: Exception) { }
            LiquidNotice.show(this, "Hãy vào Cài đặt hệ thống và chọn DigitalLiquid trong mục ứng dụng trợ lý mặc định.", 3600L)
        }
    }

    private fun hasHyperIslandAccessibility(): Boolean {
        val component = android.content.ComponentName(this, HyperIslandVisibilityService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        val accessibilityOn = Settings.Secure.getInt(contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1
        return accessibilityOn && enabled.split(':').any { it.equals(component, ignoreCase = true) }
    }

    private fun openAccessibilitySettings() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (_: Exception) {
            LiquidNotice.show(this, "Không mở được cài đặt Trợ năng.")
        }
    }

    private fun hasNotificationAccess(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        return enabled.split(":").any { it.equals(componentNameForListener(), ignoreCase = true) }
    }

    private fun componentNameForListener(): String =
        android.content.ComponentName(this, IslandNotificationListener::class.java).flattenToString()

    private fun startIsland() {
        // An ordinary TYPE_APPLICATION_OVERLAY is placed below critical system windows
        // such as the status bar. Require the enabled AccessibilityService so the pill
        // can use TYPE_ACCESSIBILITY_OVERLAY and remain touchable around the camera cutout.
        if (!hasHyperIslandAccessibility()) {
            LiquidNotice.show(this, "Hãy bật Trợ năng DigitalLiquid trước để viên thuốc nằm trên thanh trạng thái và nhận biết HyperIsland.", 3600L)
            openAccessibilitySettings()
            return
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1201)
        }
        try {
            val intent = Intent(this, IslandService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
            prefs.edit().putBoolean(AppPrefs.SERVICE_ENABLED, true).apply()
            LiquidNotice.show(this, "DigitalLiquid đã bật.")
            renderMain()
        } catch (e: Exception) {
            LiquidNotice.show(this, "Không thể bật đảo. ${e.message ?: "Kiểm tra quyền hệ thống."}", 3600L)
        }
    }

    private fun stopIsland() {
        stopService(Intent(this, IslandService::class.java))
        prefs.edit().putBoolean(AppPrefs.SERVICE_ENABLED, false).apply()
        if (prefs.getBoolean(AppPrefs.ONBOARDING_DONE, false)) renderMain()
    }

    private fun applyConfig() {
        if (!prefs.getBoolean(AppPrefs.SERVICE_ENABLED, false)) return
        sendBroadcast(Intent(IslandService.ACTION_APPLY_CONFIG).setPackage(packageName))
    }

    private fun batteryOptimizationSummary(): String {
        if (Build.VERSION.SDK_INT < 23) return "Không áp dụng"
        val manager = getSystemService(POWER_SERVICE) as PowerManager
        return if (manager.isIgnoringBatteryOptimizations(packageName)) "Đã bỏ giới hạn pin" else "Có thể bị dừng nền"
    }

    private fun requestBatteryExemption() {
        if (Build.VERSION.SDK_INT < 23) return
        val manager = getSystemService(POWER_SERVICE) as PowerManager
        if (manager.isIgnoringBatteryOptimizations(packageName)) {
            LiquidNotice.show(this, "Ứng dụng đã được bỏ giới hạn pin.")
            return
        }
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }
}
