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
    private var previewSample = "music"
    private var previewExpanded = false

    private val prefs by lazy { AppPrefs.get(this) }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!NativeIntegrity.isTrusted(this)) {
            renderIntegrityBlocked()
            return
        }
        // One-time visual refresh: new installs and upgraded installs start with the redesigned light UI.
        // Users can still switch back to dark mode after this migration.
        if (!prefs.getBoolean("ui_redesign_153_migrated", false)) {
            prefs.edit()
                .putBoolean(AppPrefs.LIGHT_MODE, true)
                .putBoolean(AppPrefs.OLED_MODE, false)
                .putString(AppPrefs.THEME_MODE, "light")
                .putString(AppPrefs.ACCENT_COLOR, "blue")
                .putBoolean("ui_redesign_153_migrated", true)
                .apply()
        }
        applySystemBars()
        // Access is open while the app is in active development. Offer the operating
        // mode before the dashboard instead of requiring a Get Key flow or ROM gate.
        keyAuthorized = true
        if (prefs.getString(AppPrefs.OPERATION_MODE, "").isNullOrBlank()) {
            renderModeChooser()
        } else {
            initializeAuthorizedUi()
        }
    }

    private fun renderIntegrityBlocked() {
        window.statusBarColor = Color.rgb(12, 14, 20)
        window.navigationBarColor = Color.rgb(12, 14, 20)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(28), dp(28), dp(28))
            setBackgroundColor(Color.rgb(12, 14, 20))
        }
        root.addView(ImageView(this).apply {
            setImageResource(R.drawable.digitalliquid_brand)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "DigitalLiquid"
        }, LinearLayout.LayoutParams(dp(72), dp(72)).apply { bottomMargin = dp(20) })
        root.addView(TextView(this).apply {
            text = "Bản cài đặt không được xác minh"
            textSize = 21f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
        })
        root.addView(TextView(this).apply {
            text = "Chữ ký gói ứng dụng không khớp với bản phát hành được cấu hình. Hãy cài bản chính thức. Bản phát triển có thể bỏ qua bước này."
            textSize = 13f
            gravity = Gravity.CENTER
            setLineSpacing(dp(4).toFloat(), 1.02f)
            setTextColor(0xFFAFB8C8.toInt())
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        root.addView(textButton("Thoát", true) { finish() }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(22) })
        setContentView(root)
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
        if (isFinishing || isDestroyed) return
        if (prefs.getString(AppPrefs.OPERATION_MODE, "").isNullOrBlank()) {
            renderModeChooser()
            return
        }
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
        maybePickPanelBackground(intent)
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
        keyAuthorized = true
        if (prefs.getString(AppPrefs.OPERATION_MODE, "").isNullOrBlank()) {
            renderModeChooser()
            return
        }
        if (prefs.getBoolean(AppPrefs.ONBOARDING_DONE, false)) renderMain()
        checkOnlineControl(force = false)
    }

    private fun applySystemBars() {
        val lightMode = prefs.getBoolean(AppPrefs.LIGHT_MODE, true)
        val oledMode = prefs.getBoolean(AppPrefs.OLED_MODE, false)
        val systemBarColor = when { lightMode -> 0xFFF4F6FB.toInt(); oledMode -> Color.BLACK; else -> Color.rgb(10, 11, 16) }
        window.statusBarColor = systemBarColor
        window.navigationBarColor = systemBarColor
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
        if (Build.VERSION.SDK_INT >= 23) {
            try {
                window.attributes = window.attributes.apply {
                    preferredRefreshRate = prefs.getInt(AppPrefs.ISLAND_REFRESH_RATE, 120).toFloat()
                }
            } catch (_: Exception) { }
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

    private fun renderModeChooser() {
        val light = prefs.getBoolean(AppPrefs.LIGHT_MODE, true)
        val root = FrameLayout(this).apply {
            background = appBackground()
            clipChildren = false
            clipToPadding = false
        }
        val scrollable = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(24), dp(24), dp(26))
        }
        column.addView(ImageView(this).apply {
            setImageResource(R.drawable.digitalliquid_brand)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "DigitalLiquid"
        }, LinearLayout.LayoutParams(dp(68), dp(68)).apply { bottomMargin = dp(20) })
        column.addView(TextView(this).apply {
            text = "Chọn trải nghiệm của bạn"
            textSize = 27f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(if (light) 0xFF141821.toInt() else Color.WHITE)
        }, LinearLayout.LayoutParams(-1, -2))
        column.addView(TextView(this).apply {
            text = "DigitalLiquid có hai cách hoạt động. Bạn có thể đổi lựa chọn trong Cài đặt bất cứ lúc nào."
            textSize = 14f
            gravity = Gravity.CENTER
            setLineSpacing(dp(4).toFloat(), 1.02f)
            setTextColor(if (light) 0xFF616B7B.toInt() else 0xFFADB5C5.toInt())
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10); bottomMargin = dp(24) })

        fun modeCard(title: String, eyebrow: String, description: String, features: List<String>, selectedMode: String, emphasized: Boolean) {
            val shell = card().apply {
                setPadding(dp(17), dp(17), dp(17), dp(17))
                isClickable = true
                isFocusable = true
                background = roundedGradient(
                    if (light) intArrayOf(if (emphasized) 0xFFF8FCFF.toInt() else 0xF6FFFFFF.toInt(), if (emphasized) 0xFFE5F1FF.toInt() else 0xFFE9EEF7.toInt())
                    else intArrayOf(0xF1262A37.toInt(), 0xF20F121A.toInt()),
                    24,
                    if (emphasized) 0x883C9AF4.toInt() else if (light) 0x66FFFFFF else 0x35FFFFFF
                )
                elevation = dp(2).toFloat()
            }
            val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val icon = TextView(this).apply {
                text = if (selectedMode == AppPrefs.MODE_FULL) "◉" else "⌁"
                textSize = 28f
                gravity = Gravity.CENTER
                setTextColor(if (emphasized) 0xFF1688EF.toInt() else if (light) 0xFF287C89.toInt() else 0xFF7ADBD0.toInt())
                background = rounded(if (light) 0x1B258BEA else 0x263B95F3, 16)
            }
            head.addView(icon, LinearLayout.LayoutParams(dp(48), dp(48)))
            val titleBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            titleBox.addView(TextView(this@MainActivity).apply {
                text = eyebrow.uppercase()
                textSize = 9.5f
                letterSpacing = 0.09f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(if (light) 0xFF21847E.toInt() else 0xFF77D6CC.toInt())
            })
            titleBox.addView(TextView(this@MainActivity).apply {
                text = title
                textSize = 20f
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
                setTextColor(if (light) 0xFF17202B.toInt() else Color.WHITE)
            }, marginTop(4))
            head.addView(titleBox, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12) })
            head.addView(TextView(this).apply {
                text = "›"
                textSize = 30f
                setTextColor(if (light) 0xFF748092.toInt() else 0xFFB9C2D0.toInt())
            })
            shell.addView(head)
            shell.addView(TextView(this).apply {
                text = description
                textSize = 13f
                setLineSpacing(dp(3).toFloat(), 1.02f)
                setTextColor(if (light) 0xFF5B6575.toInt() else 0xFFB5BECD.toInt())
            }, marginTop(14))
            val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            features.forEach { feature ->
                val row = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                row.addView(View(this@MainActivity).apply {
                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (emphasized) 0xFF3B9AF2.toInt() else 0xFF64CDB4.toInt()) }
                }, LinearLayout.LayoutParams(dp(7), dp(7)))
                row.addView(TextView(this@MainActivity).apply {
                    text = feature
                    textSize = 12.5f
                    setTextColor(if (light) 0xFF404B5B.toInt() else 0xFFD1D8E3.toInt())
                }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(9) })
                list.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
            }
            shell.addView(list, marginTop(10))
            shell.addView(textButton(if (selectedMode == AppPrefs.MODE_HYBRID) "Dùng Hybrid" else "Dùng Dynamic Island đầy đủ", emphasized) {
                prefs.edit().putString(AppPrefs.OPERATION_MODE, selectedMode).apply()
                keyAuthorized = true
                if (prefs.getBoolean(AppPrefs.ONBOARDING_DONE, false)) {
                    authorizedUiInitialized = false
                    initializeAuthorizedUi()
                } else {
                    renderOnboarding(0)
                }
            }, marginTop(16))
            shell.setOnClickListener {
                prefs.edit().putString(AppPrefs.OPERATION_MODE, selectedMode).apply()
                if (prefs.getBoolean(AppPrefs.ONBOARDING_DONE, false)) {
                    authorizedUiInitialized = false
                    initializeAuthorizedUi()
                } else renderOnboarding(0)
            }
            column.addView(shell, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })
        }

        modeCard(
            "Hybrid Island", "TÙY BIẾN THEO HỆ ĐIỀU HÀNH",
            "Giữ kiểu đảo nổi DigitalLiquid hiện tại, nâng cấp hình ảnh, hiệu ứng và tương tác. Hợp với HyperOS hoặc máy Android cần giao diện nhất quán.",
            listOf("Tương tác chạm, giữ và vuốt", "Tùy chỉnh hình dạng và animation", "Ưu tiên tránh trùng với HyperIsland hệ thống"),
            AppPrefs.MODE_HYBRID, false
        )
        modeCard(
            "Dynamic Island đầy đủ", "CHẾ ĐỘ HIỂN THỊ LIÊN TỤC",
            "Giữ lớp phủ hiển thị khi nhạc, thông báo và hoạt động nền xuất hiện thay vì tự ẩn theo HyperIsland. Hỗ trợ phụ thuộc API và quyền Android.",
            listOf("Hiển thị liên tục cả khi có nhạc/thông báo", "Theo dõi media và thông báo được cấp quyền", "Không thể thay thế các chỉ báo riêng tư do Android bảo vệ"),
            AppPrefs.MODE_FULL, true
        )
        column.addView(TextView(this).apply {
            text = "Bạn có thể đổi chế độ sau này trong Cài đặt. Một số hoạt động hệ thống bị giới hạn bởi Android và phiên bản ROM."
            textSize = 11f
            gravity = Gravity.CENTER
            setLineSpacing(dp(3).toFloat(), 1f)
            setTextColor(if (light) 0xFF7A8493.toInt() else 0xFF9099A8.toInt())
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
        scrollable.addView(column)
        root.addView(scrollable, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        applySystemBars()
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
            background = rounded(0xFF167FE8.toInt(), 50)
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
            1 -> buildFeatures(pageContent)
            2 -> buildAssistant(pageContent)
            3 -> buildSettings(pageContent)
            else -> buildAppearance(pageContent)
        }
        bottomNavView = buildBottomNav()
        // GlassTabBar contains MATCH_PARENT children; WRAP_CONTENT here can make
        // FrameLayout measure itself as tall as the available screen. Keep it compact.
        shell.addView(bottomNavView, LinearLayout.LayoutParams(-1, dp(68)).apply {
            topMargin = dp(4)
        })
        lastScrollY = 0
        // The floating tab bar remains pinned and visible, matching the reference UI.
        // Do not collapse or fade the title based on scroll; the header is a stable navigation anchor.
        shell.alpha = 0f
        shell.translationY = dp(7).toFloat()
        setContentView(shell)
        applyPaletteToTree(shell)
        applySystemBars()
        installRootInsets(shell, dp(18), dp(18), dp(18), dp(8))
        shell.animate().alpha(1f).translationY(0f).setDuration(240L).start()
    }

    private fun buildTopBar(): View {
        val title = when (tab) {
            0 -> "Liquid Island"
            1 -> "Activities"
            2 -> "Features & Shortcuts"
            3 -> "Settings"
            else -> "Appearance"
        }
        val subtitle = if (tab == 0) "LIQUID EDITION" else ""
        return GlassNavBar(
            this,
            title,
            subtitle,
            prefs.getBoolean(AppPrefs.SERVICE_ENABLED, false),
            onAssistant = { openSiriEffect() },
            onSearch = { showSearchDialog() }
        ).also { bar ->
            titleLabel = bar.titleView
            subtitleLabel = bar.subtitleView
        }
    }

    private fun showSearchDialog() {
        val query = EditText(this).apply {
            hint = "Tìm Home, Activities, Siri, Settings…"
            setSingleLine(true)
            setPadding(dp(14), dp(11), dp(14), dp(11))
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Tìm trong DigitalLiquid")
            .setView(query)
            .setNegativeButton("Hủy", null)
            .setPositiveButton("Mở") { _, _ ->
                val q = query.text?.toString()?.trim()?.lowercase().orEmpty()
                tab = when {
                    q.contains("activity") || q.contains("hoạt động") || q.contains("nhạc") || q.contains("cuộc gọi") -> 1
                    q.contains("siri") || q.contains("ai") || q.contains("shortcut") || q.contains("phím tắt") -> 2
                    q.contains("setting") || q.contains("cài đặt") || q.contains("quyền") -> 3
                    q.contains("appearance") || q.contains("giao diện") || q.contains("màu") || q.contains("fps") -> 4
                    else -> 0
                }
                renderMain()
            }
            .show()
    }

    private fun coreFeatureTile(title: String, status: String, ready: Boolean, action: (() -> Unit)? = null): View {
        val light = prefs.getBoolean(AppPrefs.LIGHT_MODE, true)
        val tile = card().apply {
            setPadding(dp(11), dp(10), dp(11), dp(10))
            minimumHeight = dp(66)
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                if (light) intArrayOf(0xDFFFFFFF.toInt(), 0xBFE9EEF7.toInt()) else intArrayOf(0x2EFFFFFF, 0x170C0D15)
            ).apply {
                cornerRadius = dp(18).toFloat()
                setStroke(dp(1), if (light) 0xDFFFFFFF.toInt() else 0x24FFFFFF)
            }
            if (action != null) {
                isClickable = true
                isFocusable = true
                setOnClickListener { action() }
            }
        }
        tile.addView(TextView(this).apply {
            text = title
            textSize = 11.5f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(if (light) 0xFF525866.toInt() else 0xFFBFC4D0.toInt())
        })
        val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        line.addView(View(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (ready) 0xFF37C96C.toInt() else 0xFFFF514D.toInt()) }
        }, LinearLayout.LayoutParams(dp(7), dp(7)))
        line.addView(TextView(this).apply {
            text = status
            textSize = 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(if (light) 0xFF20232C.toInt() else Color.WHITE)
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) })
        tile.addView(line, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(7) })
        return tile
    }

    private fun addCoreTileRow(target: LinearLayout, left: View, right: View? = null) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.TOP }
        row.addView(left, LinearLayout.LayoutParams(0, -2, 1f))
        if (right != null) {
            row.addView(right, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(10) })
        }
        target.addView(row, marginTop(9))
    }

    private fun buildOverview(target: LinearLayout) {
        val hero = card().apply {
            setPadding(dp(12), dp(12), dp(12), dp(13))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                if (prefs.getBoolean(AppPrefs.LIGHT_MODE, true)) intArrayOf(0x28FFFFFF, 0x1EE4ECF8) else intArrayOf(0x19FFFFFF, 0x10080B13)
            ).apply {
                cornerRadius = dp(28).toFloat()
                setStroke(dp(1), 0xCFFFFFFF.toInt())
            }
        }
        hero.addView(buildGlassPreview(), LinearLayout.LayoutParams(-1, dp(190)))
        hero.addView(TextView(this).apply {
            text = "BẢN XEM TRƯỚC TRỰC TIẾP"
            textSize = 10f
            letterSpacing = 0.08f
            gravity = Gravity.CENTER
            setTextColor(if (prefs.getBoolean(AppPrefs.LIGHT_MODE, true)) 0xFF8D929D.toInt() else 0xFF9CA4B6.toInt())
        }, marginTop(8))
        hero.addView(textButton(
            if (prefs.getBoolean(AppPrefs.SERVICE_ENABLED, false)) "Tắt Dynamic Island" else "Bật Dynamic Island  →",
            primary = !prefs.getBoolean(AppPrefs.SERVICE_ENABLED, false)
        ) {
            if (prefs.getBoolean(AppPrefs.SERVICE_ENABLED, false)) stopIsland() else startIsland()
        }, marginTop(12))
        target.addView(hero, marginTop(6))

        addEyebrow(target, "CORE FEATURES", 22)
        addCoreTileRow(
            target,
            coreFeatureTile("Accessibility", if (hasHyperIslandAccessibility()) "Active" else "Inactive", hasHyperIslandAccessibility()) { openAccessibilitySettings() },
            coreFeatureTile("Notifications", if (hasNotificationAccess()) "Granted" else "Required", hasNotificationAccess()) { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        )
        val micGranted = Build.VERSION.SDK_INT < 23 || checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        addCoreTileRow(
            target,
            coreFeatureTile("Microphone", if (micGranted) "Granted" else "Required", micGranted) {
                if (!micGranted && Build.VERSION.SDK_INT >= 23) requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 8110)
                else openAppDetails()
            },
            coreFeatureTile("Call screening", "Not connected", false) { openAppDetails() }
        )
        addCoreTileRow(
            target,
            coreFeatureTile("Autostart", if (prefs.getBoolean(AppPrefs.AUTO_START, false)) "Enabled" else "Disabled", prefs.getBoolean(AppPrefs.AUTO_START, false)) { tab = 3; renderMain() },
            coreFeatureTile("Battery limit", if (batteryOptimizationSummary() == "Đã bỏ giới hạn pin") "Unrestricted" else "Restricted", batteryOptimizationSummary() == "Đã bỏ giới hạn pin") { requestBatteryExemption() }
        )
        addCoreTileRow(target, coreFeatureTile("Bluetooth", "Not required", true))

        target.addView(TextView(this).apply {
            text = "* Một số tùy chọn tự khởi động và pin nằm trong Cài đặt riêng của HyperOS."
            textSize = 10f
            gravity = Gravity.CENTER
            setLineSpacing(dp(2).toFloat(), 1.05f)
            setTextColor(if (prefs.getBoolean(AppPrefs.LIGHT_MODE, true)) 0xFF8B909C.toInt() else 0xFF8E96A6.toInt())
        }, marginTop(14))
        target.addView(infoCard("Cấp quyền", "Chạm vào mục đang báo Required hoặc Inactive để mở đúng trang Cài đặt. DigitalLiquid không tự bật quyền đặc biệt thay bạn."), marginTop(12))
        target.addView(textButton(if (hasHyperIslandAccessibility()) "Quản lý quyền Trợ năng" else "Bật quyền Trợ năng", false) { openAccessibilitySettings() }, marginTop(8))
        target.addView(textButton(if (hasNotificationAccess()) "Quản lý quyền thông báo" else "Bật quyền thông báo", false) {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }, marginTop(7))
    }

    private fun openAppDetails() {
        runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
            .onFailure { LiquidNotice.show(this, "Không mở được cài đặt ứng dụng.") }
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
            setTextColor(0xFF167FE8.toInt())
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
            setTextColor(0xFF167EE8.toInt())
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
        addEyebrow(target, "APPEARANCE · PERSONALIZE")
        target.addView(sectionTitle("Theme"), marginTop(6))
        val currentTheme = prefs.getString(AppPrefs.THEME_MODE, if (prefs.getBoolean(AppPrefs.LIGHT_MODE, true)) "light" else "dark") ?: "light"
        val themes = listOf("System" to "system", "Dark" to "dark", "OLED" to "oled", "Light" to "light")
        themes.chunked(2).forEachIndexed { rowIndex, themeRow ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.TOP }
            themeRow.forEachIndexed { itemIndex, item ->
                row.addView(themeTile(item.first, item.second, currentTheme == item.second), LinearLayout.LayoutParams(0, dp(132), 1f).apply {
                    if (itemIndex > 0) marginStart = dp(9)
                })
            }
            target.addView(row, marginTop(if (rowIndex == 0) 8 else 9))
        }

        addEyebrow(target, "ACCENT COLOR", 22)
        val colors = listOf(
            Triple("Blue", "blue", 0xFF1687F5.toInt()),
            Triple("Purple", "purple", 0xFFB451E8.toInt()),
            Triple("Pink", "pink", 0xFFF13070.toInt()),
            Triple("Red", "red", 0xFFF3423A.toInt())
        )
        val accentScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            setPadding(0, dp(2), 0, dp(2))
        }
        val accentRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val selectedAccent = prefs.getString(AppPrefs.ACCENT_COLOR, "blue") ?: "blue"
        colors.forEachIndexed { index, item ->
            val selected = selectedAccent == item.second
            val chip = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(7), dp(8), dp(7), dp(8))
                background = GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    if (selected) intArrayOf(0xDCE0EDFF.toInt(), 0xD7D9E9FA.toInt()) else intArrayOf(0xAFFFFFFF.toInt(), 0x63E4EAF3.toInt())
                ).apply {
                    cornerRadius = dp(18).toFloat()
                    setStroke(dp(if (selected) 2 else 1), if (selected) item.third else 0x2395A6BE)
                }
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    prefs.edit().putString(AppPrefs.ACCENT_COLOR, item.second).apply()
                    renderMain()
                }
            }
            chip.addView(View(this).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(item.third) }
            }, LinearLayout.LayoutParams(dp(35), dp(35)))
            chip.addView(TextView(this).apply {
                text = if (selected) "✓ ${item.first}" else item.first
                textSize = 11f
                typeface = Typeface.create("sans-serif-medium", if (selected) Typeface.BOLD else Typeface.NORMAL)
                gravity = Gravity.CENTER
                setTextColor(if (selected) item.third else 0xFF303441.toInt())
            }, LinearLayout.LayoutParams(-1, dp(23)).apply { topMargin = dp(4) })
            accentRow.addView(chip, LinearLayout.LayoutParams(dp(86), dp(78)).apply { if (index > 0) marginStart = dp(9) })
        }
        accentScroll.addView(accentRow)
        target.addView(accentScroll, marginTop(8))

        target.addView(sectionTitle("Island Refresh Rate"), marginTop(22))
        target.addView(refreshRateCard(), marginTop(8))
        target.addView(infoCard("Refresh rate", "DigitalLiquid requests the nearest supported frame rate. 140/165 FPS cannot exceed a 60/90/120 Hz display limit; Android and the device panel control the actual rate."), marginTop(10))
        target.addView(buildMultitaskingPreview(), marginTop(18))

        val previewCard = card().apply { setPadding(dp(14), dp(16), dp(14), dp(16)) }
        previewCard.addView(buildGlassPreview(), LinearLayout.LayoutParams(-1, dp(190)))
        previewCard.addView(TextView(this).apply {
            text = "LIVE PREVIEW · PASSIVE DEMO"
            textSize = 9.5f
            letterSpacing = 0.055f
            setTextColor(0xFF167FE8.toInt())
            gravity = Gravity.CENTER
        }, marginTop(12))
        target.addView(previewCard, marginTop(18))

        target.addView(sectionTitle("Tùy chỉnh chuyển động & Liquid Glass"), marginTop(22))
        target.addView(card().apply {
            setPadding(dp(13), dp(13), dp(13), dp(13))
            addView(TextView(this@MainActivity).apply {
                text = "Kiểu thu phóng"
                textSize = 13f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(if (prefs.getBoolean(AppPrefs.LIGHT_MODE, true)) 0xFF252838.toInt() else Color.WHITE)
            })
            addView(segmentedOptions(
                listOf("Fluid" to "fluid", "Spring" to "spring", "Soft" to "soft", "Linear" to "linear"),
                prefs.getString(AppPrefs.ANIMATION_STYLE, "fluid") ?: "fluid"
            ) { selected -> prefs.edit().putString(AppPrefs.ANIMATION_STYLE, selected).apply(); applyConfig(); renderMain() }, marginTop(9))
        }, marginTop(8))
        target.addView(seekCard("Thời lượng thu phóng", "220–720 ms · ngắn hơn không đồng nghĩa mượt hơn", AppPrefs.MORPH_DURATION_MS, 220, 720, " ms") { applyConfig() }, marginTop(8))
        target.addView(seekCard("Độ trong viên thuốc", "35–100% · ảnh hưởng bề mặt đảo nổi", AppPrefs.PILL_OPACITY, 35, 100, "%") { applyConfig() }, marginTop(8))
        target.addView(seekCard("Bóng mờ khi thu phóng", "Điều chỉnh độ đậm của vệt bóng chuyển động", AppPrefs.SHADOW_STRENGTH, 0, 100, "%") { applyConfig() }, marginTop(8))
        target.addView(seekCard("Cường độ motion blur", "Vệt mờ theo hướng vuốt và rung lắc", AppPrefs.MOTION_BLUR_STRENGTH, 0, 100, "%") { applyConfig() }, marginTop(8))
        target.addView(seekCard("Độ bóng Liquid Glass", "15–100% · cường độ phản chiếu trên viên thuốc", AppPrefs.GLASS_STRENGTH, 15, 100, "%") { applyConfig() }, marginTop(8))
    }

    private fun buildMultitaskingPreview(): View {
        val light = prefs.getBoolean(AppPrefs.LIGHT_MODE, true)
        val selectedMode = prefs.getString(AppPrefs.MULTITASKING_PREVIEW, "fluid_merge") ?: "fluid_merge"
        val outer = card().apply {
            setPadding(dp(15), dp(15), dp(15), dp(15))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                if (light) intArrayOf(0xEAFBFCFF.toInt(), 0xDCE5ECF7.toInt()) else intArrayOf(0x2AFFFFFF, 0x150D1119)
            ).apply {
                cornerRadius = dp(27).toFloat()
                setStroke(dp(1), if (light) 0xDFFFFFFF.toInt() else 0x2AFFFFFF)
            }
        }
        val heading = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        heading.addView(TextView(this).apply {
            text = "≡"
            textSize = 23f
            gravity = Gravity.CENTER
            setTextColor(0xFF2689EA.toInt())
            background = rounded(0x222B8BEE, 12)
        }, LinearLayout.LayoutParams(dp(42), dp(38)))
        heading.addView(TextView(this).apply {
            text = "Multitasking"
            textSize = 19f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(if (light) 0xFF20232D.toInt() else Color.WHITE)
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12) })
        outer.addView(heading)

        val modes = listOf(
            Triple("Cards", "3D stack", "cards"),
            Triple("Circle", "Swap on tap", "circle"),
            Triple("Fluid Merge", "Tap: expand", "fluid_merge"),
            Triple("App Launch", "Tap: app, hold: merge", "app_launch")
        )
        modes.chunked(2).forEachIndexed { rowIndex, rowModes ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.TOP }
            rowModes.forEachIndexed { colIndex, entry ->
                val isSelected = selectedMode == entry.third
                val tile = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(7), dp(7), dp(7), dp(7))
                    background = GradientDrawable(
                        GradientDrawable.Orientation.TL_BR,
                        if (isSelected && light) intArrayOf(0xE0E5F3FF.toInt(), 0xCEDBEAFF.toInt())
                        else if (light) intArrayOf(0xF9FFFFFF.toInt(), 0xE7EEF7FF.toInt())
                        else intArrayOf(0xE91B1D25.toInt(), 0xE9101118.toInt())
                    ).apply {
                        cornerRadius = dp(20).toFloat()
                        setStroke(dp(if (isSelected) 2 else 1), if (isSelected) 0xFF258AF0.toInt() else if (light) 0x28FFFFFF else 0x28FFFFFF)
                    }
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        prefs.edit().putString(AppPrefs.MULTITASKING_PREVIEW, entry.third).apply()
                        renderMain()
                    }
                }
                val demo = FrameLayout(this).apply {
                    background = rounded(if (light) 0xFF111116.toInt() else Color.BLACK, 12)
                    clipToOutline = true
                }
                when (entry.third) {
                    "cards" -> {
                        val first = View(this).apply { background = rounded(0xFF26262D.toInt(), 5) }
                        demo.addView(first, FrameLayout.LayoutParams(dp(55), dp(13), Gravity.CENTER).apply { topMargin = -dp(15) })
                        demo.addView(View(this).apply { background = rounded(0xFF1F1F26.toInt(), 5); background = (background as GradientDrawable).apply { setStroke(dp(1), 0x665C5C68) } }, FrameLayout.LayoutParams(dp(70), dp(15), Gravity.CENTER).apply { topMargin = dp(3) })
                        demo.addView(View(this).apply { background = rounded(0xFF19191E.toInt(), 5); background = (background as GradientDrawable).apply { setStroke(dp(1), 0x554B4B54) } }, FrameLayout.LayoutParams(dp(54), dp(12), Gravity.CENTER).apply { topMargin = dp(25) })
                    }
                    "circle" -> {
                        demo.addView(View(this).apply { background = rounded(Color.BLACK, 18); background = (background as GradientDrawable).apply { setStroke(dp(1), 0x393D424F) } }, FrameLayout.LayoutParams(dp(50), dp(17), Gravity.CENTER).apply { marginEnd = dp(13) })
                        demo.addView(View(this).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.BLACK); setStroke(dp(1), 0x393D424F) } }, FrameLayout.LayoutParams(dp(17), dp(17), Gravity.CENTER).apply { marginStart = dp(45) })
                    }
                    "app_launch" -> {
                        val pill = View(this).apply { background = rounded(Color.BLACK, 16); background = (background as GradientDrawable).apply { setStroke(dp(1), 0x483D424F) } }
                        demo.addView(pill, FrameLayout.LayoutParams(dp(70), dp(19), Gravity.CENTER))
                        demo.addView(TextView(this).apply { text = "↗"; textSize = 10f; gravity = Gravity.CENTER; setTextColor(Color.WHITE); background = rounded(0xFF24262D.toInt(), 10) }, FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER).apply { leftMargin = -dp(41); bottomMargin = dp(1) })
                    }
                    else -> {
                        demo.addView(View(this).apply { background = rounded(Color.BLACK, 15); background = (background as GradientDrawable).apply { setStroke(dp(1), 0x483D424F) } }, FrameLayout.LayoutParams(dp(80), dp(21), Gravity.CENTER))
                    }
                }
                tile.addView(demo, LinearLayout.LayoutParams(-1, dp(64)))
                tile.addView(TextView(this).apply {
                    text = entry.first
                    textSize = 12f
                    gravity = Gravity.CENTER
                    typeface = Typeface.create("sans-serif-medium", if (isSelected) Typeface.BOLD else Typeface.NORMAL)
                    setTextColor(if (isSelected) 0xFF258AF0.toInt() else if (light) 0xFF252832.toInt() else 0xFFE4E8F1.toInt())
                }, LinearLayout.LayoutParams(-1, dp(22)).apply { topMargin = dp(5) })
                tile.addView(TextView(this).apply {
                    text = entry.second
                    textSize = 10f
                    gravity = Gravity.CENTER
                    setTextColor(if (light) 0xFF777C88.toInt() else 0xFF969DAD.toInt())
                }, LinearLayout.LayoutParams(-1, dp(17)))
                row.addView(tile, LinearLayout.LayoutParams(0, dp(128), 1f).apply { if (colIndex > 0) marginStart = dp(9) })
            }
            outer.addView(row, marginTop(if (rowIndex == 0) 12 else 9))
        }

        outer.addView(TextView(this).apply {
            text = "Multitasking Preview"
            textSize = 16f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(if (light) 0xFF20232D.toInt() else Color.WHITE)
        }, marginTop(16))
        val showTimer = !prefs.getBoolean("appearance_multi_preview_music_first", false)
        val preview = FrameLayout(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                if (light) intArrayOf(0xFFE1ECFC.toInt(), 0xFFFDFEFF.toInt(), 0xFFE9F0FA.toInt()) else intArrayOf(0xFF202433.toInt(), 0xFF0E1018.toInt())
            ).apply { cornerRadius = dp(23).toFloat(); setStroke(dp(1), if (light) 0xCFFFFFFF.toInt() else 0x2AFFFFFF) }
            isClickable = true
            isFocusable = true
            contentDescription = "Chạm để đổi mẫu Timer và Music"
            setOnClickListener {
                prefs.edit().putBoolean("appearance_multi_preview_music_first", showTimer).apply()
                renderMain()
            }
        }
        val pill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = rounded(Color.BLACK, 28)
        }
        pill.addView(TextView(this).apply {
            text = if (showTimer) "◴" else "♫"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(if (showTimer) 0xFFFFA72B.toInt() else 0xFFFFD46A.toInt())
        }, LinearLayout.LayoutParams(dp(34), dp(34)))
        pill.addView(TextView(this).apply {
            text = if (showTimer) "14:59" else "Now Playing"
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(if (showTimer) 0xFFFFA72B.toInt() else Color.WHITE)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(if (showTimer) dp(70) else dp(95), dp(34)))
        preview.addView(pill, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER).apply { rightMargin = dp(36) })
        preview.addView(TextView(this).apply {
            text = if (showTimer) "♫" else "14:59"
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFFFFB24D.toInt(), 0xFFF04E9A.toInt())).apply { shape = GradientDrawable.OVAL }
        }, FrameLayout.LayoutParams(dp(42), dp(42), Gravity.CENTER).apply { leftMargin = dp(150) })
        preview.addView(TextView(this).apply {
            text = if (showTimer) "Timer + Music  ·  Tap to switch" else "Music + Timer  ·  Tap to switch"
            textSize = 11f
            letterSpacing = 0.04f
            gravity = Gravity.CENTER
            setTextColor(if (light) 0xFF3E93EB.toInt() else 0xFF9DBEFF.toInt())
        }, FrameLayout.LayoutParams(-1, dp(32), Gravity.BOTTOM).apply { bottomMargin = dp(2) })
        outer.addView(preview, LinearLayout.LayoutParams(-1, dp(145)).apply { topMargin = dp(10) })
        return outer
    }

    private fun themeTile(title: String, mode: String, selected: Boolean): View {
        val light = mode == "light" || (mode == "system" && prefs.getBoolean(AppPrefs.LIGHT_MODE, true))
        val previewBackground = when (mode) {
            "system" -> GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xFF111116.toInt(), 0xFFE4E4E8.toInt()))
            "oled" -> GradientDrawable().apply { setColor(Color.BLACK) }
            "dark" -> GradientDrawable().apply { setColor(0xFF15151B.toInt()) }
            else -> GradientDrawable().apply { setColor(0xFFE3E3E8.toInt()) }
        }.apply {
            cornerRadius = dp(14).toFloat()
            setStroke(dp(1), if (selected) 0xFF2488F3.toInt() else 0x20FFFFFF)
        }
        val tile = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(9), dp(9), dp(9), dp(8))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                if (selected) intArrayOf(0xDDE1EFFF.toInt(), 0xCDE0EAF9.toInt()) else intArrayOf(0xF8FFFFFF.toInt(), 0xDCE9EEF8.toInt())
            ).apply {
                cornerRadius = dp(22).toFloat()
                setStroke(dp(if (selected) 2 else 1), if (selected) 0xFF268AF0.toInt() else 0x48FFFFFF)
            }
            isClickable = true
            isFocusable = true
            setOnClickListener {
                val systemNight = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
                val lightMode = when (mode) { "light" -> true; "system" -> !systemNight; else -> false }
                prefs.edit()
                    .putString(AppPrefs.THEME_MODE, mode)
                    .putBoolean(AppPrefs.LIGHT_MODE, lightMode)
                    .putBoolean(AppPrefs.OLED_MODE, mode == "oled")
                    .apply()
                applySystemBars()
                renderMain()
            }
        }
        val screen = FrameLayout(this).apply {
            background = previewBackground
            clipToOutline = true
        }
        val pill = View(this).apply {
            background = GradientDrawable().apply {
                setColor(Color.BLACK)
                cornerRadius = dp(8).toFloat()
                if (mode == "oled") setStroke(dp(1), 0x5DFFFFFF)
            }
        }
        screen.addView(pill, FrameLayout.LayoutParams(dp(54), dp(13), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(11) })
        tile.addView(screen, LinearLayout.LayoutParams(-1, dp(56)))
        tile.addView(TextView(this).apply {
            text = title
            textSize = 12f
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", if (selected) Typeface.BOLD else Typeface.NORMAL)
            setTextColor(if (selected) 0xFF167FE8.toInt() else 0xFF222630.toInt())
        }, LinearLayout.LayoutParams(-1, dp(24)).apply { topMargin = dp(5) })
        return tile
    }

    private fun refreshRateCard(): View {
        val outer = card().apply { setPadding(dp(10), dp(12), dp(10), dp(12)) }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val rates = listOf(60, 90, 120, 140, 165)
        val selectedRate = prefs.getInt(AppPrefs.ISLAND_REFRESH_RATE, 120)
        rates.forEachIndexed { index, rate ->
            val selected = rate == selectedRate
            val tile = TextView(this).apply {
                text = "$rate\nFPS"
                textSize = 15f
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
                gravity = Gravity.CENTER
                setLineSpacing(0f, 0.92f)
                setTextColor(if (selected) 0xFF167FE8.toInt() else 0xFF252838.toInt())
                background = GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    if (selected) intArrayOf(0xDDE0EEFF.toInt(), 0xE0CFE2FF.toInt())
                    else intArrayOf(0xF7FFFFFF.toInt(), 0xEDEFF5FA.toInt())
                ).apply {
                    cornerRadius = dp(16).toFloat()
                    setStroke(dp(if (selected) 2 else 1), if (selected) 0xFF2687F5.toInt() else 0x2293A5C2)
                }
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    if (prefs.getInt(AppPrefs.ISLAND_REFRESH_RATE, 120) != rate) {
                        prefs.edit().putInt(AppPrefs.ISLAND_REFRESH_RATE, rate).apply()
                        applySystemBars()
                        applyConfig()
                        renderMain()
                        LiquidNotice.show(this@MainActivity, "Đã đặt mục tiêu $rate FPS. Tốc độ thực tế phụ thuộc tần số quét hỗ trợ của máy.", 2800L)
                    }
                }
            }
            row.addView(tile, LinearLayout.LayoutParams(0, dp(70), 1f).apply {
                if (index > 0) marginStart = dp(5)
                if (index < rates.lastIndex) marginEnd = dp(1)
            })
        }
        outer.addView(row)
        return outer
    }

    private fun buildFeatures(target: LinearLayout) {
        val light = prefs.getBoolean(AppPrefs.LIGHT_MODE, true)
        addEyebrow(target, "ACTIVITIES · LIVE STATUS")
        target.addView(TextView(this).apply {
            text = "Live Preview"
            textSize = 23f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(if (light) 0xFF20232D.toInt() else Color.WHITE)
        }, marginTop(4))

        val modeLabel = if (prefs.getString(AppPrefs.OPERATION_MODE, AppPrefs.MODE_HYBRID) == AppPrefs.MODE_FULL) "Dynamic Island Full" else "Hybrid Island"
        val previewCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF17181E.toInt(), 0xFF111218.toInt())
            ).apply { cornerRadius = dp(25).toFloat(); setStroke(dp(1), 0x26FFFFFF) }
            elevation = dp(3).toFloat()
        }
        val previewHeader = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        previewHeader.addView(View(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFF36CF78.toInt()) }
        }, LinearLayout.LayoutParams(dp(7), dp(7)))
        previewHeader.addView(TextView(this).apply {
            text = "Live Preview · $modeLabel"
            textSize = 12f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(0xFFE4E6ED.toInt())
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) })
        previewHeader.addView(TextView(this).apply {
            text = if (previewExpanded) "Expanded" else "Compact"
            textSize = 10f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(0xFF3695FF.toInt())
        })
        previewCard.addView(previewHeader)
        previewCard.addView(buildActivityPreviewStage(), marginTop(12))
        previewCard.addView(segmentedOptions(
            listOf("▣  Compact" to "compact", "↗  Expanded" to "expanded"),
            if (previewExpanded) "expanded" else "compact"
        ) { selected -> previewExpanded = selected == "expanded"; renderMain() }, marginTop(12))
        target.addView(previewCard, marginTop(14))

        addEyebrow(target, "EXAMPLES", 20)
        val sampleScroller = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            setPadding(0, dp(2), 0, dp(4))
        }
        val sampleRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val samples = listOf(
            Triple("♫", "Player", "music"), Triple("☎", "Call", "call"),
            Triple("●", "Voice Memo", "memo"), Triple("◉", "Screen Rec.", "recording"),
            Triple("●", "Notification", "notification"), Triple("◷", "Timer", "timer"),
            Triple("◌", "Silent/Ring", "silent"), Triple("◎", "Real", "actual")
        )
        samples.forEachIndexed { index, sample ->
            val selected = previewSample == sample.third
            val tile = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(6), dp(8), dp(6), dp(8))
                background = GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    if (selected) intArrayOf(0xFFD6E8FF.toInt(), 0xFFC4DEFC.toInt()) else intArrayOf(0xF7FFFFFF.toInt(), 0xDDE6F2FA.toInt())
                ).apply {
                    cornerRadius = dp(19).toFloat()
                    setStroke(dp(if (selected) 2 else 1), if (selected) 0xFF2589F4.toInt() else 0x2296A7C0)
                }
                isClickable = true
                isFocusable = true
                setOnClickListener { previewSample = sample.third; renderMain() }
            }
            tile.addView(TextView(this).apply {
                text = sample.first
                textSize = 23f
                gravity = Gravity.CENTER
                setTextColor(if (selected) 0xFF167FE8.toInt() else 0xFF3C536F.toInt())
            }, LinearLayout.LayoutParams(-1, dp(34)))
            tile.addView(TextView(this).apply {
                text = sample.second
                textSize = 10.5f
                gravity = Gravity.CENTER
                maxLines = 1
                setTextColor(if (selected) 0xFF167FE8.toInt() else 0xFF333743.toInt())
            }, LinearLayout.LayoutParams(-1, dp(20)))
            sampleRow.addView(tile, LinearLayout.LayoutParams(dp(88), dp(83)).apply {
                if (index > 0) marginStart = dp(8)
            })
        }
        sampleScroller.addView(sampleRow)
        target.addView(sampleScroller, marginTop(8))
        target.addView(TextView(this).apply {
            text = if (previewSample == "actual") "Hiển thị trạng thái thật nếu Android đã cấp quyền tương ứng; nếu chưa có sự kiện, pill sẽ ở trạng thái chờ." else "Đây là mẫu minh họa, không giả lập cuộc gọi hoặc thay đổi chế độ âm thanh trên máy."
            textSize = 11f
            setLineSpacing(dp(2).toFloat(), 1.08f)
            setTextColor(if (light) 0xFF737B8A.toInt() else 0xFF9CA4B5.toInt())
        }, marginTop(5))

        target.addView(infoCard("Hybrid Island", "Ưu tiên phối hợp với HyperIsland của hệ thống, tự ẩn khi hoạt động hệ thống chiếm vùng camera và trong lúc popup chuyển chế độ chuông/rung/im lặng xuất hiện."), marginTop(16))
        target.addView(infoCard("Dynamic Island Full", "Giữ viên thuốc hiện khi nhạc, thông báo và các trạng thái mà Android cho phép. Chỉ báo quyền riêng tư của Android vẫn do hệ thống quản lý."), marginTop(10))
        target.addView(featureMiniCard("QUYỀN", "Truy cập thông báo", if (hasNotificationAccess()) "Đã cấp quyền để nhận thông báo thực tế." else "Cấp quyền để nhận thông báo và trạng thái hoạt động được hỗ trợ."), marginTop(14))
        target.addView(textButton(if (hasNotificationAccess()) "Quản lý quyền thông báo" else "Bật quyền thông báo", false) {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }, marginTop(8))
        target.addView(featureMiniCard("HYPERISLAND", "Trợ năng hệ thống", if (hasHyperIslandAccessibility()) "Đã bật quan sát giao diện hệ thống." else "Bật Trợ năng để hỗ trợ lớp phủ và phát hiện popup HyperIsland."), marginTop(12))
        target.addView(textButton(if (hasHyperIslandAccessibility()) "Quản lý quyền Trợ năng" else "Bật quyền Trợ năng", false) { openAccessibilitySettings() }, marginTop(8))
    }

    private data class PreviewState(val kind: String, val title: String, val subtitle: String, val symbol: String, val active: Boolean)

    private fun previewStateForSelection(): PreviewState = when (previewSample) {
        "call" -> PreviewState("call", "Cuộc gọi đến", "Cuộc gọi đang diễn ra · 00:12", "☎", true)
        "memo" -> PreviewState("memo", "Đang ghi âm", "Âm lượng giọng nói", "●", true)
        "recording" -> PreviewState("recording", "Đang quay màn hình", "REC · Minh họa", "●", true)
        "notification" -> PreviewState("notification", "Thông báo mới", "Bạn vừa nhận được tin nhắn", "●", true)
        "timer" -> PreviewState("timer", "Hẹn giờ", "14:59 còn lại", "◷", true)
        "silent" -> PreviewState("silent", "Chuông / Rung / Im lặng", "Popup chuyển chế độ · Minh họa", "◌", true)
        "actual" -> readLivePreviewState()
        else -> PreviewState("music", "Đang phát nhạc", "Tên bài hát · Nghệ sĩ", "♫", true)
    }

    private fun buildActivityPreviewStage(): View {
        val stage = FrameLayout(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF090A0D.toInt(), 0xFF020203.toInt())
            ).apply { cornerRadius = dp(21).toFloat(); setStroke(dp(1), 0x18FFFFFF) }
            clipChildren = false
            clipToPadding = false
        }
        val state = previewStateForSelection()
        stage.addView(createPreviewIsland(state, previewExpanded), FrameLayout.LayoutParams(
            if (previewExpanded) dp(276) else dp(154), if (previewExpanded) dp(64) else dp(42), Gravity.CENTER
        ))
        stage.layoutParams = LinearLayout.LayoutParams(-1, dp(148))
        return stage
    }

    private fun buildAssistant(target: LinearLayout) {
        val light = prefs.getBoolean(AppPrefs.LIGHT_MODE, true)
        addEyebrow(target, "FEATURES & SHORTCUTS")
        target.addView(TextView(this).apply {
            text = "Swipe Left Gesture"
            textSize = 22f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(if (light) 0xFF20232D.toInt() else Color.WHITE)
        }, marginTop(5))
        val gestureHero = card().apply {
            setPadding(dp(12), dp(12), dp(12), dp(10))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                if (light) intArrayOf(0xFFFFFFFF.toInt(), 0xFFE7EDF7.toInt()) else intArrayOf(0xE91A1D25.toInt(), 0xE9101118.toInt())
            ).apply { cornerRadius = dp(26).toFloat(); setStroke(dp(1), if (light) 0xCFFFFFFF.toInt() else 0x2CFFFFFF) }
        }
        val demoStage = FrameLayout(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                if (light) intArrayOf(0xFFE9EFF9.toInt(), 0xFFFDFEFF.toInt()) else intArrayOf(0xFF11131B.toInt(), 0xFF08090D.toInt())
            ).apply { cornerRadius = dp(20).toFloat() }
        }
        val sample = PreviewState("music", "Đang phát nhạc", "Swipe left · Volume Down", "♫", true)
        demoStage.addView(createPreviewIsland(sample, false), FrameLayout.LayoutParams(dp(154), dp(42), Gravity.CENTER))
        gestureHero.addView(demoStage, LinearLayout.LayoutParams(-1, dp(132)))
        gestureHero.addView(TextView(this).apply {
            text = "SWIPE LEFT  ·  VOLUME DOWN"
            textSize = 10f
            letterSpacing = 0.10f
            gravity = Gravity.CENTER
            setTextColor(if (light) 0xFF8B929E.toInt() else 0xFF9DA5B5.toInt())
        }, marginTop(9))
        target.addView(gestureHero, marginTop(12))

        addEyebrow(target, "GESTURE CONTROL", 20)
        val gestureCard = card().apply { setPadding(dp(15), dp(4), dp(15), dp(4)) }
        fun gestureToggle(title: String, description: String, key: String, defaultValue: Boolean = true) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(11), 0, dp(11)) }
            val labels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            labels.addView(TextView(this@MainActivity).apply {
                text = title
                textSize = 14f
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setTextColor(if (light) 0xFF242731.toInt() else Color.WHITE)
            })
            labels.addView(TextView(this@MainActivity).apply {
                text = description
                textSize = 10.5f
                setLineSpacing(dp(2).toFloat(), 1.05f)
                setTextColor(if (light) 0xFF777E8B.toInt() else 0xFF9FA6B5.toInt())
            }, marginTop(4))
            row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(IOSSwitch(this@MainActivity).apply {
                isChecked = prefs.getBoolean(key, defaultValue)
                setOnCheckedChangeListener { _, checked -> prefs.edit().putBoolean(key, checked).apply(); applyConfig() }
            }, LinearLayout.LayoutParams(dp(51), dp(31)).apply { marginStart = dp(8) })
            gestureCard.addView(row)
            if (title != "Adaptive Gestures") gestureCard.addView(View(this).apply {
                setBackgroundColor(if (light) 0x1B687A91 else 0x22FFFFFF)
            }, LinearLayout.LayoutParams(-1, dp(1)))
        }
        gestureToggle("Hold Gestures", "Hold the island to access secondary actions.", "gesture_hold_enabled")
        gestureToggle("Only When Empty", "Run gestures only when no active item occupies the island.", "gesture_only_when_empty", false)
        gestureToggle("Adaptive Gestures", "Adapt the gesture response to the current activity.", "gesture_adaptive_enabled")
        val configRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, dp(8)) }
        configRow.addView(TextView(this).apply { text = "Gesture preferences"; textSize = 12f; setTextColor(if (light) 0xFF6B7482.toInt() else 0xFFB7BECC.toInt()) }, LinearLayout.LayoutParams(0, -2, 1f))
        configRow.addView(textButton("⚙", false) { showGesturePreferences() }, LinearLayout.LayoutParams(dp(54), dp(38)))
        gestureCard.addView(configRow)
        target.addView(gestureCard, marginTop(8))

        addEyebrow(target, "HORIZONTAL SWIPES", 22)
        target.addView(swipeActionCard("Swipe Left", "volume_down", listOf("Volume Down", "Previous Track", "Previous App", "Previous Notification")), marginTop(8))
        target.addView(swipeActionCard("Swipe Right", "volume_up", listOf("Volume Up", "Next Track", "Next App", "Next Notification")), marginTop(8))
        addEyebrow(target, "QUICK SETTINGS", 22)
        target.addView(toggleCard("Double-tap assistant", "Open the Siri-style Liquid Glass view from the real island.", AppPrefs.DOUBLE_TAP_ASSISTANT), marginTop(8))
        target.addView(toggleCard("Recent-app gesture", "Enable the rapid-tap gesture to switch between recent apps. Requires Usage Access.", AppPrefs.ISLAND_APP_SWITCH_GESTURE), marginTop(8))
        target.addView(settingAction("Usage Access", if (RecentAppSwitcher.hasUsageAccess(this)) "Granted" else "Required", "Android controls this permission. It is used only for the recent-app gesture.") {
            try { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) } catch (_: Exception) { startActivity(Intent(Settings.ACTION_SETTINGS)) }
        }, marginTop(8))
    }

    private fun swipeActionCard(title: String, key: String, options: List<String>): View {
        val light = prefs.getBoolean(AppPrefs.LIGHT_MODE, true)
        val stored = prefs.getString("gesture_action_$key", options.first()) ?: options.first()
        val outer = card().apply { setPadding(dp(15), dp(13), dp(14), dp(13)) }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(TextView(this).apply {
            text = "⇆"
            textSize = 23f
            gravity = Gravity.CENTER
            setTextColor(0xFF2689EA.toInt())
            background = rounded(0x202B8BEE, 12)
        }, LinearLayout.LayoutParams(dp(42), dp(40)))
        val labels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        labels.addView(TextView(this@MainActivity).apply {
            text = title
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(if (light) 0xFF242731.toInt() else Color.WHITE)
        })
        labels.addView(TextView(this@MainActivity).apply {
            text = stored
            textSize = 11f
            setTextColor(if (light) 0xFF757D8A.toInt() else 0xFFA7AEBD.toInt())
        }, marginTop(4))
        row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(11) })
        row.addView(TextView(this).apply { text = "⌄"; textSize = 21f; setTextColor(if (light) 0xFF7C8797.toInt() else 0xFFAFB7C7.toInt()) })
        outer.addView(row)
        outer.setOnClickListener {
            val selected = prefs.getString("gesture_action_$key", options.first()) ?: options.first()
            android.app.AlertDialog.Builder(this)
                .setTitle(title)
                .setSingleChoiceItems(options.toTypedArray(), options.indexOf(selected).coerceAtLeast(0)) { dialog, which ->
                    prefs.edit().putString("gesture_action_$key", options[which]).apply()
                    dialog.dismiss()
                    renderMain()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        return outer
    }

    private fun showGesturePreferences() {
        val light = prefs.getBoolean(AppPrefs.LIGHT_MODE, true)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(6), dp(18), dp(4)) }
        content.addView(TextView(this).apply { text = "Choose which action each swipe should trigger. The settings are saved locally on this device."; textSize = 13f; setTextColor(if (light) 0xFF343946.toInt() else 0xFFE6E9F0.toInt()) })
        content.addView(swipeActionCard("Swipe Left", "volume_down", listOf("Volume Down", "Previous Track", "Previous App", "Previous Notification")), marginTop(12))
        content.addView(swipeActionCard("Swipe Right", "volume_up", listOf("Volume Up", "Next Track", "Next App", "Next Notification")), marginTop(8))
        LiquidDialog.show(
            this,
            title = "Gesture preferences",
            message = "Choose which action each swipe should trigger. The settings are saved locally on this device.",
            eyebrow = "GESTURE CONTROL",
            content = content,
            actions = listOf(LiquidDialog.Action("Done", primary = true))
        )
    }

    private fun showAiEndpointInfo() {
        LiquidDialog.show(
            this,
            eyebrow = "DIGITALLIQUID AI · BẢO MẬT",
            title = "Kết nối AI",
            message = if (OpenRouterClient.isConfigured()) {
                "AI proxy đã được cấu hình. Khóa OpenRouter không được lưu trong ứng dụng."
            } else {
                "Bản mã nguồn này dùng Cloudflare Worker làm proxy. Quản trị viên cần triển khai Worker và đặt OPENROUTER_API_KEY trong mục Secrets của Worker, sau đó gán URL Worker vào OpenRouterClient.kt. Người dùng cuối không cần nhập key."
            },
            actions = listOf(LiquidDialog.Action("Đóng", primary = true))
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
        addEyebrow(target, "SYSTEM · SETTINGS")
        target.addView(sectionTitle("Live Preview"), marginTop(6))
        target.addView(buildGlassPreview(), marginTop(8))

        target.addView(card().apply {
            setPadding(dp(15), dp(14), dp(15), dp(14))
            val row = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val labels = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
            labels.addView(TextView(this@MainActivity).apply {
                text = "System Presence"
                textSize = 15f
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setTextColor(if (prefs.getBoolean(AppPrefs.LIGHT_MODE, true)) 0xFF222631.toInt() else Color.WHITE)
            })
            labels.addView(bodyText(if (prefs.getBoolean(AppPrefs.SERVICE_ENABLED, false)) "Island Enabled" else "Island Disabled"), marginTop(4))
            row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(IOSSwitch(this@MainActivity).apply {
                isChecked = prefs.getBoolean(AppPrefs.SERVICE_ENABLED, false)
                setOnCheckedChangeListener { _, checked -> if (checked) startIsland() else stopIsland() }
            }, LinearLayout.LayoutParams(dp(52), dp(31)))
            addView(row)
        }, marginTop(12))

        target.addView(collapsibleSettingGroup("Liquid Material", "Glass strength · tint · border · shadow", expanded = false, populate = { content ->
            content.addView(seekCard("Glass Strength", "Amount of liquid-glass distortion", AppPrefs.GLASS_STRENGTH, 0, 100, "%") { applyConfig() }, marginTop(8))
            content.addView(TextView(this).apply { text = "Tint"; textSize = 12f; setTextColor(if (prefs.getBoolean(AppPrefs.LIGHT_MODE, true)) 0xFF545C6C.toInt() else 0xFFBFC6D5.toInt()) }, marginTop(9))
            content.addView(segmentedOptions(listOf("Midnight" to "midnight", "Silver" to "silver", "Blue" to "blue"), prefs.getString(AppPrefs.TINT, "midnight") ?: "midnight") { selected -> prefs.edit().putString(AppPrefs.TINT, selected).apply(); applyConfig(); renderMain() }, marginTop(6))
            content.addView(seekCard("Pill Opacity", "Transparency of the pill surface", AppPrefs.PILL_OPACITY, 35, 100, "%") { applyConfig() }, marginTop(9))
            content.addView(seekCard("Shadow", "Soft shadow strength", AppPrefs.SHADOW_STRENGTH, 0, 100, "%") { applyConfig() }, marginTop(9))
            content.addView(seekCard("Motion Blur", "Blur applied during morph animation", AppPrefs.MOTION_BLUR_STRENGTH, 0, 100, "%") { applyConfig() }, marginTop(9))
        }), marginTop(10))

        target.addView(collapsibleSettingGroup("Geometry", "Width · height · offset · alignment", expanded = false, populate = { content ->
            content.addView(seekCard("Width", "72–330 dp", AppPrefs.WIDTH, 72, 330, " dp") { applyConfig() }, marginTop(8))
            content.addView(seekCard("Height", "22–72 dp", AppPrefs.HEIGHT, 22, 72, " dp") { applyConfig() }, marginTop(9))
            content.addView(seekCard("Top Offset", "0 sits near the status bar", AppPrefs.OFFSET_TOP, 0, 140, " dp") { applyConfig() }, marginTop(9))
            content.addView(TextView(this).apply { text = "Horizontal alignment"; textSize = 12f; setTextColor(if (prefs.getBoolean(AppPrefs.LIGHT_MODE, true)) 0xFF545C6C.toInt() else 0xFFBFC6D5.toInt()) }, marginTop(10))
            content.addView(segmentedOptions(listOf("Left" to "left", "Center" to "center", "Right" to "right"), prefs.getString(AppPrefs.ALIGNMENT, "center") ?: "center") { selected -> prefs.edit().putString(AppPrefs.ALIGNMENT, selected).apply(); applyConfig(); renderMain() }, marginTop(6))
        }), marginTop(10))

        target.addView(collapsibleSettingGroup("Interaction", "Gestures · animation · behavior", expanded = false, populate = { content ->
            content.addView(toggleCard("Reduce Motion", "Reduce bounce and morph movement.", AppPrefs.REDUCE_MOTION), marginTop(8))
            content.addView(toggleCard("Tap Wobble", "Keep a small elastic response on tap.", AppPrefs.TAP_WOBBLE), marginTop(8))
            content.addView(toggleCard("Screen Recording Indicator", "Show recording state when detected.", AppPrefs.SCREEN_RECORDING_EFFECT), marginTop(8))
            content.addView(toggleCard("Face Unlock Effect", "Play the optical unlock animation.", AppPrefs.FACE_UNLOCK_EFFECT), marginTop(8))
            content.addView(toggleCard("Hide during ring-mode popup", "Hybrid mode hides during system ringtone, vibrate or silent transition popups.", AppPrefs.HIDE_WHEN_RINGER_UI), marginTop(8))
            content.addView(toggleCard("Double-tap assistant", "Open Siri-style Liquid Glass from the real island.", AppPrefs.DOUBLE_TAP_ASSISTANT), marginTop(8))
            content.addView(toggleCard("Recent-app gesture", "Enable quick switching between recent apps.", AppPrefs.ISLAND_APP_SWITCH_GESTURE), marginTop(8))
        }), marginTop(10))

        target.addView(collapsibleSettingGroup("Customization", "Background · theme · reset", expanded = false, populate = { content ->
            val selectedBackground = prefs.getString(IslandService.PANEL_BACKGROUND_URI, null)
            content.addView(textButton(if (selectedBackground.isNullOrBlank()) "Choose image or video background" else "Change image/video background", true) { openBackgroundPicker() }, marginTop(8))
            if (!selectedBackground.isNullOrBlank()) content.addView(textButton("Remove custom background", false) {
                prefs.edit().remove(IslandService.PANEL_BACKGROUND_URI).remove(AppPrefs.PANEL_BACKGROUND_KIND).apply()
                applyConfig()
                renderMain()
            }, marginTop(8))
            content.addView(textButton("Appearance and refresh rate", false) { tab = 4; renderMain() }, marginTop(8))
            content.addView(textButton("Restore default size and position", false) {
                prefs.edit().remove(AppPrefs.WIDTH).remove(AppPrefs.HEIGHT).remove(AppPrefs.OFFSET_TOP).remove(AppPrefs.ALIGNMENT)
                    .remove(AppPrefs.GLASS_STRENGTH).remove(AppPrefs.PILL_OPACITY).remove(AppPrefs.SHADOW_STRENGTH).apply()
                applyConfig(); renderMain()
            }, marginTop(8))
        }), marginTop(10))

        target.addView(collapsibleSettingGroup("Permissions & Privacy", "Notification access · accessibility · battery", expanded = false, populate = { content ->
            content.addView(statusLine("Accessibility overlay", hasHyperIslandAccessibility()), marginTop(9))
            content.addView(textButton(if (hasHyperIslandAccessibility()) "Manage Accessibility" else "Enable Accessibility", false) { openAccessibilitySettings() }, marginTop(8))
            content.addView(statusLine("Notification access", hasNotificationAccess()), marginTop(9))
            content.addView(textButton(if (hasNotificationAccess()) "Manage Notification Access" else "Enable Notification Access", false) { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }, marginTop(8))
            val microphoneReady = Build.VERSION.SDK_INT < 23 || checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            content.addView(statusLine("Microphone", microphoneReady), marginTop(9))
            content.addView(textButton(if (microphoneReady) "Open app permissions" else "Grant microphone access", false) {
                if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 8110) else openAppDetails()
            }, marginTop(8))
            content.addView(textButton("Battery optimization", false) { requestBatteryExemption() }, marginTop(8))
            content.addView(infoCard("Permission control", "DigitalLiquid asks for permissions only when needed. Android still controls protected privacy indicators and special-access screens."), marginTop(10))
        }), marginTop(10))
    }

    private fun collapsibleSettingGroup(title: String, summary: String, expanded: Boolean = false, populate: (LinearLayout) -> Unit): View {
        val light = prefs.getBoolean(AppPrefs.LIGHT_MODE, true)
        val outer = card().apply {
            setPadding(dp(15), dp(13), dp(15), dp(13))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                if (light) intArrayOf(0xECFFFFFF.toInt(), 0xDDE8F0FB.toInt()) else intArrayOf(0x301F222D, 0x1A0D0F16)
            ).apply { cornerRadius = dp(23).toFloat(); setStroke(dp(1), if (light) 0xDFFFFFFF.toInt() else 0x2CFFFFFF) }
        }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; isClickable = true; isFocusable = true }
        header.addView(View(this).apply {
            background = rounded(0x212B8BEE, 11)
        }, LinearLayout.LayoutParams(dp(38), dp(38)))
        val labels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        labels.addView(TextView(this@MainActivity).apply {
            text = title
            textSize = 15f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(if (light) 0xFF20232D.toInt() else Color.WHITE)
        })
        labels.addView(TextView(this@MainActivity).apply {
            text = summary
            textSize = 10f
            maxLines = 2
            setTextColor(if (light) 0xFF77808F.toInt() else 0xFF9FA6B5.toInt())
        }, marginTop(4))
        header.addView(labels, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12) })
        val arrow = TextView(this).apply {
            text = if (expanded) "⌃" else "⌄"
            textSize = 19f
            gravity = Gravity.CENTER
            setTextColor(if (light) 0xFF7D8797.toInt() else 0xFFAFB7C7.toInt())
        }
        header.addView(arrow, LinearLayout.LayoutParams(dp(30), dp(38)))
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (expanded) View.VISIBLE else View.GONE
            alpha = if (expanded) 1f else 0f
        }
        populate(content)
        header.setOnClickListener {
            val show = content.visibility != View.VISIBLE
            content.visibility = if (show) View.VISIBLE else View.GONE
            arrow.text = if (show) "⌃" else "⌄"
            if (show) {
                content.alpha = 0f
                content.animate().alpha(1f).setDuration(180L).start()
            }
        }
        outer.addView(header)
        outer.addView(content, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(3) })
        return outer
    }

    private fun readLivePreviewState(): PreviewState {
        if (IslandService.isScreenRecordingActive()) {
            return PreviewState("recording", "Đang quay màn hình", "REC · Hoạt động thật", "●", true)
        }
        if (IslandService.isCameraActivityActive()) {
            return PreviewState("camera", "Camera đang hoạt động", "Chỉ báo quyền riêng tư", "◉", true)
        }
        try {
            val manager = getSystemService(android.content.Context.MEDIA_SESSION_SERVICE) as android.media.session.MediaSessionManager
            val sessions = manager.getActiveSessions(android.content.ComponentName(this, IslandNotificationListener::class.java))
            val playing = sessions.firstOrNull { controller ->
                val state = controller.playbackState?.state
                state == android.media.session.PlaybackState.STATE_PLAYING ||
                    state == android.media.session.PlaybackState.STATE_BUFFERING ||
                    state == android.media.session.PlaybackState.STATE_CONNECTING
            }
            val paused = sessions.firstOrNull { controller ->
                controller.playbackState?.state == android.media.session.PlaybackState.STATE_PAUSED && controller.metadata != null
            }
            val mediaController = playing ?: paused
            if (mediaController != null) {
                val metadata = mediaController.metadata
                val title = metadata?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE)?.trim().orEmpty()
                    .ifBlank { if (playing != null) "Đang phát nhạc" else "Nhạc đang tạm dừng" }
                val artist = metadata?.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST)?.trim().orEmpty()
                    .ifBlank { mediaController.packageName }
                val subtitle = if (playing != null) artist else "$artist · Đã tạm dừng"
                return PreviewState("music", title.take(42), subtitle.take(52), "♫", playing != null)
            }
        } catch (_: SecurityException) {
            // Notification access is optional; fall through to app and permission state.
        } catch (_: Throwable) { }

        val notificationTitle = IslandNotificationListener.latestPreviewNotificationTitle()
        if (!notificationTitle.isNullOrBlank()) {
            return PreviewState("notification", notificationTitle.take(42), "Thông báo đang hoạt động", "●", true)
        }
        if (IslandService.isMediaPlaybackActive()) {
            return PreviewState("music", "Media đang hoạt động", "Đã nhận trạng thái phát", "♫", true)
        }
        if (IslandService.isNativeActivityActive()) {
            return PreviewState("activity", "Hoạt động hệ thống", "Đã nhận trạng thái thông báo", "◉", true)
        }
        return if (prefs.getBoolean(AppPrefs.SERVICE_ENABLED, false)) {
            PreviewState("idle", "DigitalLiquid đang chạy", "Trạng thái chờ · bản xem trước không tương tác", "•", false)
        } else {
            PreviewState("idle", "Đảo nổi đang chờ", "Bật đảo nổi để xem trạng thái trực tiếp", "•", false)
        }
    }

    private fun buildGlassPreview(): View {
        val frame = FrameLayout(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFFF8FBFF.toInt(), 0xFFE5EDF9.toInt(), 0xFFF4F7FD.toInt())
            ).apply {
                cornerRadius = dp(28).toFloat()
                setStroke(dp(1), 0xCFFFFFFF.toInt())
            }
            clipToOutline = true
            clipChildren = false
            clipToPadding = false
            contentDescription = "Bản xem trước tĩnh của Dynamic Island, không có thao tác chạm để phóng to"
        }
        val shadow = View(this).apply {
            background = GradientDrawable().apply { setColor(0x26030B1A); cornerRadius = dp(25).toFloat() }
            alpha = prefs.getInt(AppPrefs.SHADOW_STRENGTH, 68).coerceIn(0, 100) / 100f * 0.62f
            elevation = dp(7).toFloat()
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        frame.addView(shadow, FrameLayout.LayoutParams(dp(160), dp(48), Gravity.CENTER))
        val state = readLivePreviewState()
        frame.addView(createPreviewIsland(state, false), FrameLayout.LayoutParams(dp(154), dp(42), Gravity.CENTER))
        frame.layoutParams = LinearLayout.LayoutParams(-1, dp(190))
        return frame
    }

    private fun createPreviewIsland(state: PreviewState, expanded: Boolean): View {
        val light = prefs.getBoolean(AppPrefs.LIGHT_MODE, true)
        val root = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                setColor(Color.BLACK)
                cornerRadius = dp(if (expanded) 30 else 22).toFloat()
                setStroke(dp(1), 0x3AFFFFFF)
            }
            elevation = dp(8).toFloat()
            clipToOutline = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            contentDescription = "${state.title}. ${state.subtitle}"
        }
        if (!state.active && state.kind == "idle") {
            val dot = View(this).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFFFFD56A.toInt()) }
            }
            root.addView(dot, FrameLayout.LayoutParams(dp(4), dp(4), Gravity.CENTER))
            return root
        }
        val appIcon = TextView(this).apply {
            text = state.symbol
            textSize = if (expanded) 18f else 17f
            gravity = Gravity.CENTER
            includeFontPadding = false
            setTextColor(Color.WHITE)
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                when (state.kind) {
                    "recording", "memo" -> intArrayOf(0xFFFC5B62.toInt(), 0xFFCC244B.toInt())
                    "call" -> intArrayOf(0xFF4EDB8B.toInt(), 0xFF14995C.toInt())
                    "notification" -> intArrayOf(0xFFFFC36D.toInt(), 0xFFF08042.toInt())
                    "timer", "silent" -> intArrayOf(0xFFFFB342.toInt(), 0xFFE87922.toInt())
                    else -> intArrayOf(0xFF3CD99B.toInt(), 0xFF0D9C77.toInt())
                }
            ).apply { cornerRadius = dp(9).toFloat() }
        }
        root.addView(appIcon, FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER_VERTICAL or Gravity.LEFT).apply { leftMargin = dp(9) })
        if (expanded) {
            val labels = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(46), 0, dp(54), 0)
            }
            labels.addView(TextView(this).apply {
                text = state.title
                textSize = 11.5f
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setTextColor(Color.WHITE)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            labels.addView(TextView(this).apply {
                text = state.subtitle
                textSize = 9f
                setTextColor(0xFFB6BDCA.toInt())
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(3) })
            root.addView(labels, FrameLayout.LayoutParams(-1, -1))
        } else {
            val waves = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
            val heights = listOf(8, 13, 18, 11, 16, 9)
            heights.forEachIndexed { index, height ->
                val bar = View(this).apply {
                    background = GradientDrawable().apply {
                        setColor(when (state.kind) {
                            "recording", "memo" -> 0xFFFF5366.toInt()
                            "call" -> 0xFF59E69A.toInt()
                            "notification" -> 0xFFFFC36D.toInt()
                            "timer", "silent" -> 0xFFFFB342.toInt()
                            else -> 0xFFE64591.toInt()
                        })
                        cornerRadius = dp(2).toFloat()
                    }
                    alpha = 0.95f
                }
                waves.addView(bar, LinearLayout.LayoutParams(dp(3), dp(height)).apply { if (index > 0) marginStart = dp(3) })
            }
            root.addView(waves, FrameLayout.LayoutParams(dp(42), dp(26), Gravity.CENTER_VERTICAL or Gravity.RIGHT).apply { rightMargin = dp(10) })
        }
        return root
    }

    private fun permissionStatusCard(): View {
        val outer = card().apply { setPadding(dp(14), dp(14), dp(14), dp(14)) }
        outer.addView(TextView(this).apply {
            text = "TỔNG QUAN QUYỀN"
            textSize = 10f
            letterSpacing = 0.11f
            setTextColor(0xFF247D73.toInt())
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
            AppPrefs.WIDTH -> 148; AppPrefs.HEIGHT -> 38; AppPrefs.OFFSET_TOP -> 6
            AppPrefs.GLASS_STRENGTH -> 72; AppPrefs.MORPH_DURATION_MS -> 420
            AppPrefs.PILL_OPACITY -> 100; AppPrefs.SHADOW_STRENGTH -> 68
            AppPrefs.MOTION_BLUR_STRENGTH -> 55; else -> min
        }).coerceIn(min, maxValue)
        val value = TextView(this).apply {
            text = "$initial$suffix"
            textSize = 12f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(0xFF167FE8.toInt())
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
        textColumn.addView(TextView(this@MainActivity).apply { text = status; textSize = 11f; setTextColor(0xFF167FE8.toInt()) }, marginTop(4))
        row.addView(textColumn, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(TextView(this).apply { text = "›"; textSize = 26f; setTextColor(0xFF167FE8.toInt()) })
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
            setTextColor(0xFF167FE8.toInt())
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
            setTextColor(0xFF47646D.toInt())
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
            setTextColor(0xFF167FE8.toInt())
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
        LiquidBackdropDrawable(prefs.getBoolean(AppPrefs.LIGHT_MODE, true), prefs.getBoolean(AppPrefs.OLED_MODE, false))

    /** Soft radial lighting, with separate readable palettes for light, dark and pure-black OLED mode. */
    private class LiquidBackdropDrawable(private val lightMode: Boolean, private val oledMode: Boolean) : android.graphics.drawable.Drawable() {
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)

        override fun draw(canvas: android.graphics.Canvas) {
            val area = bounds
            if (area.isEmpty) return
            canvas.drawColor(when { lightMode -> 0xFFF0F2F8.toInt(); oledMode -> Color.BLACK; else -> Color.rgb(7, 8, 13) })
            if (oledMode && !lightMode) {
                paint.shader = null
                return
            }
            val width = area.width().toFloat()
            val height = area.height().toFloat()
            val radius = maxOf(width, height) * 0.78f
            paint.shader = android.graphics.RadialGradient(
                area.left + width * 0.10f, area.top + height * 0.05f, radius,
                if (lightMode) intArrayOf(0x805A9BFF.toInt(), 0x40B9C9FF, 0x00F4F8FB)
                else intArrayOf(0x70453891, 0x302B245D, 0x0007080D),
                floatArrayOf(0f, 0.52f, 1f), android.graphics.Shader.TileMode.CLAMP
            )
            canvas.drawRect(area, paint)
            paint.shader = android.graphics.RadialGradient(
                area.left + width * 0.96f, area.top + height * 0.64f, radius * 0.78f,
                if (lightMode) intArrayOf(0x5072B4FF, 0x24C9D8FF, 0x00F4F8FB)
                else intArrayOf(0x302B6692, 0x14203953, 0x0007080D),
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
        val lightMode = prefs.getBoolean(AppPrefs.LIGHT_MODE, true)
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
                    r > 170 && b > 150 && g > 100 -> 0xFF167FE8.toInt()
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
                GlassTabBar.Item("⌂", "Home"),
                GlassTabBar.Item("▦", "Activities"),
                GlassTabBar.Item("✎", "Shortcuts"),
                GlassTabBar.Item("⚙", "Settings"),
                GlassTabBar.Item("◎", "Appearance")
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

    private fun openSiriEffect() {
        try {
            startActivity(Intent(this, AssistantFallbackActivity::class.java))
        } catch (error: Exception) {
            android.util.Log.e("DigitalLiquidSiri", "Could not open Siri Glass fallback", error)
            LiquidNotice.show(this, "Không mở được hiệu ứng Siri. Hãy cài lại bản DigitalLiquid mới nhất.", 3600L)
        }
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
