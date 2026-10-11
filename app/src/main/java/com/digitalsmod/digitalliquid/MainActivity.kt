package com.digitalsmod.digitalliquid

import android.app.Activity
import android.app.AlertDialog
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
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    companion object { private const val REQUEST_PANEL_BACKGROUND = 2409 }

    private var launchedForPanelPicker = false
    private var tab = 0
    private var onboardingPage = 0
    private val uiHandler = Handler(Looper.getMainLooper())
    private var welcomeTypewriter: Runnable? = null
    private lateinit var shell: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var pageContent: LinearLayout

    private val prefs by lazy { AppPrefs.get(this) }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applySystemBars()
        if (prefs.getBoolean(AppPrefs.ONBOARDING_DONE, false)) renderMain() else renderOnboarding(0)
        maybePickPanelBackground(intent)
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
            Toast.makeText(this, "Không mở được trình chọn ảnh/video.", Toast.LENGTH_LONG).show()
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
                try {
                    val flags = resultIntent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION
                    if (flags != 0) contentResolver.takePersistableUriPermission(uri, flags)
                } catch (_: Exception) { }
                prefs.edit().putString(IslandService.PANEL_BACKGROUND_URI, uri.toString()).apply()
                sendBroadcast(Intent(IslandService.ACTION_APPLY_CONFIG).setPackage(packageName))
                Toast.makeText(this, "Đã cập nhật nền ảnh/video.", Toast.LENGTH_SHORT).show()
                if (!launchedForPanelPicker && prefs.getBoolean(AppPrefs.ONBOARDING_DONE, false)) renderMain()
            }
        }
        if (launchedForPanelPicker) {
            launchedForPanelPicker = false
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        if (prefs.getBoolean(AppPrefs.ONBOARDING_DONE, false)) renderMain()
    }

    private fun applySystemBars() {
        window.statusBarColor = Color.rgb(10, 11, 16)
        window.navigationBarColor = Color.rgb(10, 11, 16)
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
            background = rounded(0xFFB9AEFF.toInt(), 50)
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
            2 -> buildFeatures(pageContent)
            else -> buildSettings(pageContent)
        }
        shell.addView(buildBottomNav(), marginTop(4))
        shell.alpha = 0f
        shell.translationY = dp(7).toFloat()
        setContentView(shell)
        installRootInsets(shell, dp(18), dp(18), dp(18), dp(8))
        shell.animate().alpha(1f).translationY(0f).setDuration(240L).start()
    }

    private fun buildTopBar(): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val badge = ImageView(this).apply {
            setImageResource(R.drawable.digitalliquid_brand)
            scaleType = ImageView.ScaleType.CENTER_CROP
            contentDescription = "DigitalLiquid"
        }
        row.addView(badge, LinearLayout.LayoutParams(dp(44), dp(44)))
        val labels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(11), 0, 0, 0) }
        labels.addView(TextView(this).apply {
            text = "DigitalLiquid"
            textSize = 19f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(Color.WHITE)
        })
        labels.addView(TextView(this).apply {
            text = when (tab) { 0 -> "ĐẢO NỔI"; 1 -> "GIAO DIỆN"; 2 -> "TÍNH NĂNG"; else -> "CÀI ĐẶT" }
            textSize = 9f
            letterSpacing = 0.12f
            setTextColor(0xFF9699AA.toInt())
        }, marginTop(3))
        row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
        val state = TextView(this).apply {
            text = if (prefs.getBoolean(AppPrefs.SERVICE_ENABLED, false)) "ĐANG BẬT" else "ĐANG TẮT"
            textSize = 10f
            letterSpacing = 0.04f
            setTextColor(if (prefs.getBoolean(AppPrefs.SERVICE_ENABLED, false)) 0xFF8EF2BE.toInt() else 0xFFB9BCCD.toInt())
            setPadding(dp(10), dp(7), dp(10), dp(7))
            background = rounded(0x1FFFFFFF, 30)
        }
        row.addView(state)
        return row
    }

    private fun buildOverview(target: LinearLayout) {
        addEyebrow(target, "ĐẢO NỔI CỦA BẠN")
        target.addView(TextView(this).apply {
            text = "Trạng thái của bạn"
            textSize = 25f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(Color.WHITE)
        }, marginTop(4))
        target.addView(bodyText("Bật đảo nổi, cấp quyền cần thiết và để mọi thứ hoạt động bên trên ứng dụng khác."), marginTop(7))

        val hero = card().apply { setPadding(dp(16), dp(16), dp(16), dp(16)) }
        val heroHeader = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        heroHeader.addView(TextView(this).apply {
            text = "ĐẢO NỔI"
            textSize = 10f
            letterSpacing = 0.08f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(0xFFBAB4FF.toInt())
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

    private fun buildAppearance(target: LinearLayout) {
        addEyebrow(target, "VỊ TRÍ & CHUYỂN ĐỘNG")
        target.addView(TextView(this).apply {
            text = "Một viên thuốc. Không chiếm màn hình."
            textSize = 23f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(Color.WHITE)
        }, marginTop(4))
        target.addView(bodyText("Bề mặt khi rảnh giữ màu đen đặc, không viền quang học và không làm mờ ứng dụng phía sau."), marginTop(7))
        val previewCard = card().apply { setPadding(dp(14), dp(16), dp(14), dp(16)) }
        previewCard.addView(buildGlassPreview(), LinearLayout.LayoutParams(-1, dp(64)))
        previewCard.addView(TextView(this).apply {
            text = "CHẠM: RUNG LẮC  ·  GIỮ: TÙY CHỈNH"
            textSize = 9.5f
            letterSpacing = 0.055f
            setTextColor(0xFFB8B1FF.toInt())
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
                prefs.edit().remove(IslandService.PANEL_BACKGROUND_URI).apply()
                applyConfig()
                renderMain()
            }, marginTop(7))
        }

        target.addView(sectionTitle("Kích thước"), marginTop(22))
        target.addView(seekCard("Chiều rộng", "126–330 dp", AppPrefs.WIDTH, 126, 330, " dp") { applyConfig() }, marginTop(8))
        target.addView(seekCard("Chiều cao", "34–72 dp", AppPrefs.HEIGHT, 34, 72, " dp") { applyConfig() }, marginTop(8))

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

    private fun buildSettings(target: LinearLayout) {
        addEyebrow(target, "QUYỀN & RIÊNG TƯ")
        target.addView(TextView(this).apply {
            text = "Cài đặt hệ thống"
            textSize = 24f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(Color.WHITE)
        }, marginTop(4))
        target.addView(bodyText("Các quyền đặc biệt của Android không thể tự bật. Bạn sẽ được chuyển sang trang Cài đặt tương ứng."), marginTop(7))
        target.addView(settingAction("Notification Access", if (hasNotificationAccess()) "Đã cấp quyền" else "Chưa cấp quyền", "Đọc trạng thái thông báo đang hoạt động để phát hiện quay màn hình, nhạc và đồng hồ") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }, marginTop(10))
        target.addView(settingAction("Theo dõi HyperIsland", if (hasHyperIslandAccessibility()) "Đã bật" else "Chưa bật", "Cho phép nhận diện HyperIsland và dùng lớp phủ Trợ năng đáng tin cậy để chạm được ở vùng thanh trạng thái. Nhận diện phụ thuộc phiên bản HyperOS.") {
            openAccessibilitySettings()
        }, marginTop(10))
        target.addView(settingAction("Không hạn chế pin", batteryOptimizationSummary(), "Giảm khả năng hệ thống dừng overlay nền") { requestBatteryExemption() }, marginTop(10))

        val reduceMotion = Switch(this).apply {
            text = "Giảm chuyển động"
            textSize = 14f
            setTextColor(Color.WHITE)
            isChecked = prefs.getBoolean(AppPrefs.REDUCE_MOTION, false)
            buttonTintList = android.content.res.ColorStateList.valueOf(0xFFB9AEFF.toInt())
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean(AppPrefs.REDUCE_MOTION, checked).apply()
                applyConfig()
            }
        }
        target.addView(card().apply {
            addView(reduceMotion)
            addView(bodyText("Tắt chuyển động rung lắc và co giãn. Viên thuốc vẫn hiển thị màu đen."), marginTop(5))
        }.apply { setPadding(dp(15), dp(14), dp(15), dp(14)) }, marginTop(10))

        target.addView(settingAction("Chạy lại onboarding", "Xem lại giới thiệu", "Hiện lại màn hình Chào mừng") {
            prefs.edit().putBoolean(AppPrefs.ONBOARDING_DONE, false).apply()
            renderOnboarding(0)
        }, marginTop(22))
        target.addView(settingAction("Khôi phục mặc định", "Xóa các tùy chỉnh", "Đặt lại kích thước, vị trí và công tắc mô-đun") {
            AlertDialog.Builder(this)
                .setTitle("Khôi phục mặc định?")
                .setMessage("Các thiết lập giao diện và mô-đun sẽ bị đặt lại. Trạng thái onboarding không đổi.")
                .setNegativeButton("Hủy", null)
                .setPositiveButton("Khôi phục") { _, _ ->
                    prefs.edit().clear().putBoolean(AppPrefs.ONBOARDING_DONE, true).putBoolean(AppPrefs.SERVICE_ENABLED, false).apply()
                    stopIsland()
                    renderMain()
                }.show()
        }, marginTop(10))
        target.addView(infoCard("DigitalLiquid", "Được thiết kế cho Android, không cần tài khoản. Bạn có thể tắt quyền hoặc dừng viên thuốc bất cứ lúc nào."), marginTop(20))
    }

    private fun buildGlassPreview(): View {
        val frame = FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            clipChildren = false
            clipToPadding = false
        }
        val glass = LiquidGlassView(this).apply {
            configure(0, "midnight", expandedNow = false)
            setMotionBlur(0f)
        }
        frame.addView(glass, FrameLayout.LayoutParams(dp(150), dp(38), Gravity.CENTER))
        frame.layoutParams = LinearLayout.LayoutParams(-1, dp(64))
        return frame
    }

    private fun permissionStatusCard(): View {
        val outer = card().apply { setPadding(dp(14), dp(14), dp(14), dp(14)) }
        outer.addView(TextView(this).apply {
            text = "TỔNG QUAN QUYỀN"
            textSize = 10f
            letterSpacing = 0.11f
            setTextColor(0xFFA8A1F7.toInt())
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
        val toggle = Switch(this).apply {
            isChecked = prefs.getBoolean(key, true)
            buttonTintList = android.content.res.ColorStateList.valueOf(0xFFB7ACFF.toInt())
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
            setTextColor(0xFFBFB5FF.toInt())
            gravity = Gravity.END
        }
        line.addView(value, LinearLayout.LayoutParams(dp(72), -2))
        outer.addView(line)
        outer.addView(TextView(this).apply { text = description; textSize = 10f; setTextColor(0xFF8F94A6.toInt()) }, marginTop(4))
        val seek = SeekBar(this).apply {
            this.max = maxValue - min
            this.progress = initial - min
            progressTintList = android.content.res.ColorStateList.valueOf(0xFFAAA0FF.toInt())
            thumbTintList = android.content.res.ColorStateList.valueOf(0xFFE9E6FF.toInt())
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0xFF383947.toInt())
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val actual = min + progress
                    value.text = "$actual$suffix"
                    if (fromUser) {
                        prefs.edit().putInt(key, actual).apply()
                        onChange()
                    }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
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
        textColumn.addView(TextView(this@MainActivity).apply { text = status; textSize = 11f; setTextColor(0xFFB5ACFF.toInt()) }, marginTop(4))
        row.addView(textColumn, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(TextView(this).apply { text = "›"; textSize = 26f; setTextColor(0xFF9990D9.toInt()) })
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
            setTextColor(0xFFAFA6FF.toInt())
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
            setTextColor(0xFFDAD6FF.toInt())
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
            setTextColor(0xFF9D96ED.toInt())
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

    private fun textButton(label: String, primary: Boolean, action: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 12f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = if (primary) roundedGradient(intArrayOf(0xFF8172E5.toInt(), 0xFF5A4BB7.toInt()), 15)
        else roundedGradient(intArrayOf(0x2CFFFFFF, 0x16FFFFFF), 15, 0x35FFFFFF)
        isClickable = true
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

    private fun appBackground() = GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        intArrayOf(0xFF11121C.toInt(), 0xFF0B0D13.toInt(), 0xFF0E1119.toInt())
    )

    private fun marginTop(top: Int) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }
    private fun marginBottom(bottom: Int) = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(bottom) }
    private fun startMarginParams(start: Int) = LinearLayout.LayoutParams(-2, -2).apply { this.marginStart = dp(start) }

    private fun buildBottomNav(): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = roundedGradient(intArrayOf(0xE51B1D28.toInt(), 0xE012141D.toInt()), 22, 0x24FFFFFF)
            setPadding(dp(5), dp(6), dp(5), dp(6))
        }
        val entries = listOf("Đảo nổi" to 0, "Giao diện" to 1, "Chức năng" to 2, "Cài đặt" to 3)
        entries.forEach { entry ->
            container.addView(textButton(entry.first, tab == entry.second) {
                tab = entry.second
                renderMain()
            }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(2); marginEnd = dp(2) })
        }
        return container
    }

    override fun onDestroy() {
        welcomeTypewriter?.let { uiHandler.removeCallbacks(it) }
        welcomeTypewriter = null
        super.onDestroy()
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
            Toast.makeText(this, "Không mở được cài đặt Trợ năng.", Toast.LENGTH_LONG).show()
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
            Toast.makeText(this, "Hãy bật Trợ năng DigitalLiquid trước để viên thuốc nằm trên thanh trạng thái và nhận biết HyperIsland.", Toast.LENGTH_LONG).show()
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
            Toast.makeText(this, "DigitalLiquid đã bật.", Toast.LENGTH_SHORT).show()
            renderMain()
        } catch (e: Exception) {
            Toast.makeText(this, "Không thể bật đảo: ${e.message ?: "kiểm tra quyền hệ thống"}", Toast.LENGTH_LONG).show()
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
            Toast.makeText(this, "Ứng dụng đã được bỏ giới hạn pin.", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }
}
