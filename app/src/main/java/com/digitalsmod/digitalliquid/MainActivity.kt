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
            prefs.edit().putBoolean(AppPrefs.LIGHT_MODE, true).putBoolean("ui_redesign_153_migrated", true).apply()
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
            0 -> "DIGITALLIQUID · LIQUID EDITION"
            1 -> "ACTIVITIES · LIVE STATUS"
            2 -> "SHORTCUTS · SIRI & AI"
            3 -> "SYSTEM · PERMISSIONS"
            else -> "APPEARANCE · LIQUID GLASS"
        }
        return GlassNavBar(
            this,
            "Liquid Island",
            subtitle,
            prefs.getBoolean(AppPrefs.SERVICE_ENABLED, false),
            onAssistant = { openSiriEffect() }
        ).also { bar ->
            titleLabel = bar.titleView
            subtitleLabel = bar.subtitleView
        }
    }

    private fun buildOverview(target: LinearLayout) {
        addEyebrow(target, "LIQUID INTERFACE · OVERVIEW")
        target.addView(TextView(this).apply {
            text = "Không gian điều khiển"
            textSize = 25f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(Color.WHITE)
        }, marginTop(4))
        target.addView(bodyText("Một bề mặt kính gọn nhẹ cho đảo nổi, hoạt động và trợ lý AI. Các quyền hệ thống vẫn do bạn kiểm soát."), marginTop(7))
        target.addView(textButton("Mở hiệu ứng Siri  ↗", primary = true) { openSiriEffect() }, marginTop(14))

        val hero = card().apply { setPadding(dp(16), dp(16), dp(16), dp(16)) }
        val heroHeader = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        heroHeader.addView(TextView(this).apply {
            text = "ĐẢO NỔI"
            textSize = 10f
            letterSpacing = 0.08f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(0xFF1682F5.toInt())
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
                if (prefs.getBoolean(AppPrefs.LIGHT_MODE, true)) "light" else "dark"
            ) { selected ->
                prefs.edit().putBoolean(AppPrefs.LIGHT_MODE, selected == "light").apply()
                applySystemBars()
                renderMain()
            })
        }, marginTop(8))
        val previewCard = card().apply { setPadding(dp(14), dp(16), dp(14), dp(16)) }
        previewCard.addView(buildGlassPreview(), LinearLayout.LayoutParams(-1, dp(190)))
        previewCard.addView(TextView(this).apply {
            text = "CHẠM: RUNG LẮC  ·  GIỮ: TÙY CHỈNH"
            textSize = 9.5f
            letterSpacing = 0.055f
            setTextColor(0xFF167FE8.toInt())
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
        target.addView(sectionTitle("Island Refresh Rate"), marginTop(22))
        target.addView(refreshRateCard(), marginTop(8))
        target.addView(infoCard("Tần số khung hình", "DigitalLiquid yêu cầu mức gần nhất mà màn hình hỗ trợ. 140/165 FPS không thể ép màn hình 60/90/120 Hz vượt giới hạn phần cứng; hệ điều hành vẫn quyết định tốc độ thực tế."), marginTop(10))

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
        addEyebrow(target, "ACTIVITIES · LIVE STATUS")
        target.addView(TextView(this).apply {
            text = "Trạng thái hoạt động"
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
        addEyebrow(target, "SHORTCUTS · SIRI · AI")
        val siriLaunchCard = card().apply { setPadding(dp(15), dp(14), dp(15), dp(14)) }
        siriLaunchCard.addView(TextView(this).apply {
            text = "Siri Glass Effect"
            textSize = 18f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(Color.WHITE)
        })
        siriLaunchCard.addView(bodyText("Mở lớp kính quang học nhiều màu quanh Dynamic Island. Hiệu ứng hoạt động độc lập với trạng thái kết nối AI."), marginTop(5))
        siriLaunchCard.addView(textButton("Mở Siri  ↗", primary = true) { openSiriEffect() }, marginTop(12))
        target.addView(siriLaunchCard, marginTop(8))
        target.addView(toggleCard("Nhấn đúp viên thuốc để mở Siri", "Mở giao diện Siri Glass. Nếu trợ lý hệ thống chưa sẵn sàng, ứng dụng tự chuyển sang giao diện dự phòng.", AppPrefs.DOUBLE_TAP_ASSISTANT), marginTop(10))
        target.addView(toggleCard("Chạm 6 lần để chuyển ứng dụng", "Bật cử chỉ chuyển sang ứng dụng gần đây. Cần cấp quyền truy cập mức sử dụng ứng dụng trong Cài đặt.", AppPrefs.ISLAND_APP_SWITCH_GESTURE), marginTop(8))
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
        val lightPalette = prefs.getBoolean(AppPrefs.LIGHT_MODE, true)
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
                    setTextColor(if (active) 0xFF07356E.toInt() else if (lightPalette) 0xFF3A4554.toInt() else 0xFFE1E5EF.toInt())
                    background = GradientDrawable(
                        GradientDrawable.Orientation.LEFT_RIGHT,
                        if (active) intArrayOf(0xFF8CC5FF.toInt(), 0xFF287EF0.toInt())
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
        config.addView(textButton("Trạng thái kết nối AI", false) { showAiEndpointInfo() }, marginTop(8))
        config.addView(bodyText(if (OpenRouterClient.isConfigured()) "AI proxy đã được triển khai" else "AI proxy sẽ hoạt động sau khi quản trị viên triển khai Worker"), marginTop(8))
        target.addView(config, marginTop(14))

        val chatCard = card().apply { setPadding(dp(15), dp(15), dp(15), dp(15)) }
        chatCard.addView(sectionTitle("Cuộc trò chuyện"))
        val response = TextView(this).apply {
            text = if (assistantHistory.isEmpty()) "DigitalLiquid sẵn sàng. Hãy nhập câu hỏi bên dưới." else assistantHistory.takeLast(6).joinToString("\n\n") { (role, text) -> (if (role == "user") "Bạn" else "DigitalLiquid AI") + ":\n" + text }
            textSize = 13f
            setTextColor(if (lightPalette) 0xFF252838.toInt() else 0xFFE5E6F0.toInt())
            setLineSpacing(dp(3).toFloat(), 1.08f)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = rounded(if (lightPalette) 0xFFEAF0F9.toInt() else 0xA5181A25.toInt(), 16)
            minHeight = dp(130)
            gravity = Gravity.TOP or Gravity.START
        }
        chatCard.addView(response, marginTop(10))
        val prompt = EditText(this).apply {
            hint = "Viết câu hỏi của bạn…"
            setHintTextColor(if (lightPalette) 0xFF75849A.toInt() else 0xFF777B8A.toInt())
            setTextColor(if (lightPalette) 0xFF182735.toInt() else Color.WHITE)
            textSize = 14f
            minLines = 2
            maxLines = 5
            gravity = Gravity.TOP or Gravity.START
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = rounded(if (lightPalette) 0xFFF4F7FC.toInt() else 0xFF151722.toInt(), 16)
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
        target.addView(infoCard("Kết nối AI", "AI dùng một proxy HTTPS cố định. Khóa OpenRouter được lưu ở phía máy chủ, không yêu cầu người dùng nhập khóa trong ứng dụng."), marginTop(14))
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
        addEyebrow(target, "GIAO DIỆN & VẬN HÀNH")
        target.addView(settingAction(
            "Chế độ Dynamic Island",
            if (prefs.getString(AppPrefs.OPERATION_MODE, AppPrefs.MODE_HYBRID) == AppPrefs.MODE_FULL) "Dynamic Island đầy đủ" else "Hybrid Island",
            "Đổi cách ứng dụng xử lý nhạc, thông báo và HyperIsland của hệ thống. Việc đổi chế độ sẽ mở lại màn hình lựa chọn."
        ) { renderModeChooser() }, marginTop(8))
        addEyebrow(target, "QUYỀN & RIÊNG TƯ", 22)
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
        target.addView(settingAction("Kết nối AI bảo mật", if (OpenRouterClient.isConfigured()) "Đã cấu hình proxy" else "Chờ triển khai proxy", "API key được giữ trong Cloudflare Worker. Người dùng không phải nhập key hoặc URL máy chủ.") {
            showAiEndpointInfo()
        }, marginTop(8))
        target.addView(settingAction("Model AI mặc định", AiModels.labelFor(AiModels.selectedId(this)), "Mở tab Shortcuts để chọn model hoặc đặt model tùy chỉnh.") {
            tab = 2
            renderMain()
        }, marginTop(8))

        val autoStartSwitch = IOSSwitch(this).apply {
            isChecked = prefs.getBoolean(AppPrefs.AUTO_START, false)
            setOnCheckedChangeListener { _, checked ->
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
            addView(bodyText("Khôi phục đảo nổi sau khi khởi động. HyperOS có thể áp dụng thêm quyền Tự khởi động hoặc giới hạn chạy nền."), marginTop(6))
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
                            .putBoolean(AppPrefs.LIGHT_MODE, true)
                            .apply()
                        stopIsland()
                        renderMain()
                    }
                )
            )
        }, marginTop(10))
        target.addView(infoCard("DigitalLiquid", "DigitalLiquid chỉ cần các quyền bạn chủ động bật. Bạn có thể thu hồi quyền hoặc dừng viên thuốc bất cứ lúc nào."), marginTop(20))
    }

    private data class PreviewState(val kind: String, val title: String, val subtitle: String, val symbol: String, val active: Boolean)

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
            PreviewState("idle", "DigitalLiquid đang chạy", "Chạm để xem trạng thái · giữ để mở Siri", "•", false)
        } else {
            PreviewState("idle", "Đảo nổi đang chờ", "Bật đảo nổi để xem trạng thái trực tiếp", "•", false)
        }
    }

    private fun buildGlassPreview(): View {
        val frame = FrameLayout(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFFF9FBFF.toInt(), 0xFFE8EFFA.toInt(), 0xFFF4F7FD.toInt())
            ).apply {
                cornerRadius = dp(28).toFloat()
                setStroke(dp(1), 0x99FFFFFF.toInt())
            }
            clipToOutline = true
            clipChildren = false
            clipToPadding = false
            contentDescription = "Bản xem trước trực tiếp DigitalLiquid"
        }
        val shadow = View(this).apply {
            background = GradientDrawable().apply {
                setColor(0x26030B1A)
                cornerRadius = dp(30).toFloat()
            }
            alpha = prefs.getInt(AppPrefs.SHADOW_STRENGTH, 68).coerceIn(0, 100) / 100f * 0.62f
            elevation = dp((8f * prefs.getInt(AppPrefs.SHADOW_STRENGTH, 68).coerceIn(0, 100) / 100f).toInt()).toFloat()
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        frame.addView(shadow, FrameLayout.LayoutParams(dp(154), dp(42), Gravity.CENTER))

        val pill = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                setColor(Color.BLACK)
                cornerRadius = dp(22).toFloat()
                setStroke(dp(1), 0x332E3440)
            }
            clipToOutline = true
            tag = "digitalliquid-preview-island"
            isClickable = true
            isFocusable = true
            elevation = dp(9).toFloat()
            alpha = prefs.getInt(AppPrefs.PILL_OPACITY, 100).coerceIn(35, 100) / 100f
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
        }
        val glass = LiquidGlassView(this).apply {
            configure(prefs.getInt(AppPrefs.GLASS_STRENGTH, 78), prefs.getString(AppPrefs.TINT, "midnight").orEmpty(), expandedNow = false)
            setMotionBlur(0f)
        }
        pill.addView(glass, FrameLayout.LayoutParams(-1, -1))

        val leftIcon = TextView(this).apply {
            text = "•"
            textSize = 16f
            gravity = Gravity.CENTER
            includeFontPadding = false
            setTextColor(0xFFFFD36E.toInt())
            isClickable = false
        }
        pill.addView(leftIcon, FrameLayout.LayoutParams(dp(24), dp(28), Gravity.CENTER_VERTICAL or Gravity.LEFT).apply { leftMargin = dp(9) })

        val waves = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            isClickable = false
        }
        val waveBars = mutableListOf<View>()
        val heights = listOf(7, 13, 18, 10, 15)
        heights.forEachIndexed { index, height ->
            val bar = View(this).apply {
                background = GradientDrawable().apply { setColor(0xFFFF4E9B.toInt()); cornerRadius = dp(2).toFloat() }
                alpha = 0.88f
            }
            waveBars.add(bar)
            waves.addView(bar, LinearLayout.LayoutParams(dp(3), dp(height)).apply { marginStart = if (index == 0) 0 else dp(3) })
        }
        pill.addView(waves, FrameLayout.LayoutParams(dp(45), dp(26), Gravity.CENTER_VERTICAL or Gravity.RIGHT).apply { rightMargin = dp(10) })

        val expandedText = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(dp(17), dp(7), dp(17), dp(7))
            isClickable = false
        }
        val stateTitle = TextView(this).apply {
            text = "Đang chờ"
            textSize = 12f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(Color.WHITE)
        }
        val stateSubtitle = TextView(this).apply {
            text = ""
            textSize = 9.5f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(0xFFBFC5D0.toInt())
        }
        expandedText.addView(stateTitle, LinearLayout.LayoutParams(-1, -2))
        expandedText.addView(stateSubtitle, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(3) })
        pill.addView(expandedText, FrameLayout.LayoutParams(-1, -1))
        frame.addView(pill, FrameLayout.LayoutParams(dp(154), dp(42), Gravity.CENTER))

        var expanded = false
        var downX = 0f
        var downY = 0f
        var moved = false
        var longPressTriggered = false
        var lastTapAt = 0L
        var tapCount = 0
        val handler = Handler(Looper.getMainLooper())
        var morphAnimator: android.animation.ValueAnimator? = null
        val compactW = dp(154)
        val compactH = dp(42)
        val expandedW = dp(264)
        val expandedH = dp(74)

        fun updateState() {
            val state = readLivePreviewState()
            stateTitle.text = state.title
            stateSubtitle.text = state.subtitle
            leftIcon.text = state.symbol
            leftIcon.setTextColor(when (state.kind) {
                "recording" -> 0xFFFF514D.toInt()
                "camera" -> 0xFF49D89B.toInt()
                "music" -> 0xFF71E7BE.toInt()
                "notification" -> 0xFFFFC66E.toInt()
                else -> 0xFFFFD36E.toInt()
            })
            val waveColor = when (state.kind) {
                "recording" -> 0xFFFF5757.toInt()
                "camera" -> 0xFF4CE0A2.toInt()
                "notification" -> 0xFFFFC56D.toInt()
                else -> 0xFFFF4E9B.toInt()
            }
            waveBars.forEachIndexed { index, bar ->
                (bar.background as? GradientDrawable)?.setColor(waveColor)
                val lp = bar.layoutParams as? LinearLayout.LayoutParams ?: return@forEachIndexed
                val pulse = if (state.active && !prefs.getBoolean(AppPrefs.REDUCE_MOTION, false)) (index % 3) * dp(2) else 0
                lp.height = dp(heights[index]) + pulse
                bar.layoutParams = lp
                bar.alpha = if (state.active) 1f else 0.5f
            }
            if (state.kind == "idle") {
                waves.alpha = 0.34f
            } else waves.alpha = 1f
            pill.contentDescription = "${state.title}. ${state.subtitle}. Chạm để mở rộng, vuốt để chuyển trang, giữ để mở trợ lý AI."
        }

        fun setExpanded(targetExpanded: Boolean, animated: Boolean = true) {
            if (expanded == targetExpanded && morphAnimator?.isRunning != true) return
            expanded = targetExpanded
            morphAnimator?.cancel()
            if (!targetExpanded) {
                leftIcon.visibility = View.VISIBLE
                waves.visibility = View.VISIBLE
            }
            val startW = (pill.layoutParams?.width ?: compactW).toFloat()
            val startH = (pill.layoutParams?.height ?: compactH).toFloat()
            val endW = (if (targetExpanded) expandedW else compactW).toFloat()
            val endH = (if (targetExpanded) expandedH else compactH).toFloat()
            expandedText.visibility = if (targetExpanded) View.VISIBLE else expandedText.visibility
            if (targetExpanded) {
                expandedText.alpha = 0f
                expandedText.translationY = dp(3).toFloat()
                expandedText.animate().alpha(1f).translationY(0f).setDuration(190L)
                    .setInterpolator(android.view.animation.PathInterpolator(0.2f, 0.7f, 0.2f, 1f)).start()
            } else {
                expandedText.animate().alpha(0f).translationY(dp(2).toFloat()).setDuration(120L).start()
            }
            leftIcon.animate().alpha(if (targetExpanded) 0f else 1f).setDuration(110L).start()
            waves.animate().alpha(if (targetExpanded) 0f else if (readLivePreviewState().active) 1f else 0.34f).setDuration(110L).start()
            val shadowStrength = prefs.getInt(AppPrefs.SHADOW_STRENGTH, 68).coerceIn(0, 100) / 100f
            val targetShadowAlpha = shadowStrength * if (targetExpanded) 0.80f else 0.62f
            shadow.animate().alpha(targetShadowAlpha).translationY(if (targetExpanded) dp(3).toFloat() else 0f).setDuration(if (animated) 260L else 0L).start()
            if (!animated) {
                expandedText.animate().cancel()
                expandedText.visibility = if (targetExpanded) View.VISIBLE else View.GONE
                expandedText.alpha = if (targetExpanded) 1f else 0f
                expandedText.translationY = 0f
                pill.layoutParams = (pill.layoutParams as FrameLayout.LayoutParams).apply { width = endW.toInt(); height = endH.toInt() }
                (pill.background as? GradientDrawable)?.cornerRadius = endH / 2f
                shadow.layoutParams = (shadow.layoutParams as FrameLayout.LayoutParams).apply { width = endW.toInt() + dp(5); height = endH.toInt() + dp(5) }
                (shadow.background as? GradientDrawable)?.cornerRadius = (endH + dp(5)) / 2f
                glass.configure(prefs.getInt(AppPrefs.GLASS_STRENGTH, 78), prefs.getString(AppPrefs.TINT, "midnight").orEmpty(), targetExpanded)
                glass.setMotionBlur(0f)
                return
            }
            val configured = prefs.getInt(AppPrefs.MORPH_DURATION_MS, 420).coerceIn(220, 720).toLong()
            val time = if (targetExpanded) configured else (configured * 0.88f).toLong()
            morphAnimator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                duration = time
                interpolator = when (prefs.getString(AppPrefs.ANIMATION_STYLE, "fluid")) {
                    "spring" -> android.view.animation.PathInterpolator(0.20f, 0.92f, 0.22f, 1f)
                    "soft" -> android.view.animation.PathInterpolator(0.35f, 0f, 0.20f, 1f)
                    "linear" -> android.view.animation.AccelerateDecelerateInterpolator()
                    else -> android.view.animation.PathInterpolator(
                        if (targetExpanded) 0.20f else 0.40f,
                        if (targetExpanded) 0.86f else 0.00f,
                        if (targetExpanded) 0.25f else 0.20f,
                        1f
                    )
                }
                addUpdateListener { animation ->
                    val t = animation.animatedValue as Float
                    val widthNow = (startW + (endW - startW) * t).toInt().coerceAtLeast(dp(1))
                    val heightNow = (startH + (endH - startH) * t).toInt().coerceAtLeast(dp(1))
                    pill.layoutParams = (pill.layoutParams as FrameLayout.LayoutParams).apply { width = widthNow; height = heightNow }
                    (pill.background as? GradientDrawable)?.cornerRadius = heightNow / 2f
                    shadow.layoutParams = (shadow.layoutParams as FrameLayout.LayoutParams).apply { width = widthNow + dp(5); height = heightNow + dp(5) }
                    (shadow.background as? GradientDrawable)?.cornerRadius = (heightNow + dp(5)) / 2f
                    val blur = prefs.getInt(AppPrefs.MOTION_BLUR_STRENGTH, 55).coerceIn(0, 100) / 100f * 0.36f
                    glass.setMotionBlur((if (targetExpanded) 1f - t else t) * blur)
                    glass.configure(prefs.getInt(AppPrefs.GLASS_STRENGTH, 78), prefs.getString(AppPrefs.TINT, "midnight").orEmpty(), if (targetExpanded) t > 0.5f else t < 0.5f)
                }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        if (!targetExpanded) {
                            expandedText.visibility = View.GONE
                            expandedText.alpha = 0f
                            expandedText.translationY = 0f
                        }
                    }
                })
                start()
            }
            if (targetExpanded) {
                updateState()
                pill.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            }
        }

        val longPress = Runnable {
            longPressTriggered = true
            pill.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            openSiriEffect()
        }
        val updateTask = object : Runnable {
            override fun run() {
                if (!frame.isAttachedToWindow) return
                updateState()
                if (expanded) {
                    leftIcon.visibility = View.GONE
                    waves.visibility = View.GONE
                } else {
                    leftIcon.visibility = View.VISIBLE
                    waves.visibility = View.VISIBLE
                }
                handler.postDelayed(this, 1200L)
            }
        }
        frame.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { handler.removeCallbacks(updateTask); handler.post(updateTask) }
            override fun onViewDetachedFromWindow(v: View) { handler.removeCallbacks(updateTask); handler.removeCallbacks(longPress); morphAnimator?.cancel() }
        })

        pill.setOnTouchListener { view, event ->
            if (!prefs.getBoolean(AppPrefs.LIVE_PREVIEW_INTERACTIVE, true)) return@setOnTouchListener false
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    moved = false
                    longPressTriggered = false
                    handler.removeCallbacks(longPress)
                    handler.postDelayed(longPress, 520L)
                    view.animate().scaleX(0.985f).scaleY(0.965f).setDuration(95L).start()
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    if (kotlin.math.abs(event.rawX - downX) > dp(18) || kotlin.math.abs(event.rawY - downY) > dp(24)) {
                        moved = true
                        handler.removeCallbacks(longPress)
                    }
                    true
                }
                android.view.MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(longPress)
                    view.animate().scaleX(1f).scaleY(1f).setDuration(190L)
                        .setInterpolator(android.view.animation.PathInterpolator(0.2f, 0.75f, 0.25f, 1f)).start()
                    if (moved) {
                        val dx = event.rawX - downX
                        if (kotlin.math.abs(dx) > dp(42)) {
                            tab = if (dx < 0) (tab + 1).coerceAtMost(4) else (tab - 1).coerceAtLeast(0)
                            renderMain()
                            return@setOnTouchListener true
                        }
                    } else if (!longPressTriggered) {
                        val now = android.os.SystemClock.elapsedRealtime()
                        tapCount = if (now - lastTapAt < 360L) tapCount + 1 else 1
                        lastTapAt = now
                        if (tapCount >= 2) {
                            tapCount = 0
                            openSiriEffect()
                        } else {
                            setExpanded(!expanded)
                        }
                    }
                    true
                }
                android.view.MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPress)
                    view.animate().scaleX(1f).scaleY(1f).setDuration(160L).start()
                    true
                }
                else -> true
            }
        }
        updateState()
        frame.layoutParams = LinearLayout.LayoutParams(-1, dp(190))
        return frame
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
        LiquidBackdropDrawable(prefs.getBoolean(AppPrefs.LIGHT_MODE, true))

    /** Soft radial lighting, with separate readable palettes for dark and light mode. */
    private class LiquidBackdropDrawable(private val lightMode: Boolean) : android.graphics.drawable.Drawable() {
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)

        override fun draw(canvas: android.graphics.Canvas) {
            val area = bounds
            if (area.isEmpty) return
            canvas.drawColor(if (lightMode) 0xFFF0F2F8.toInt() else Color.rgb(7, 8, 13))
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
                GlassTabBar.Item("✦", "Shortcuts"),
                GlassTabBar.Item("⚙", "Settings"),
                GlassTabBar.Item("◉", "Appearance")
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
