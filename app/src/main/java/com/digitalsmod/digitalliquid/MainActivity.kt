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
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.Window
import android.widget.Button
import android.widget.FrameLayout
import android.widget.EditText
import android.widget.LinearLayout
import android.text.InputType
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private var tab = 0
    private var onboardingPage = 0
    private lateinit var shell: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var pageContent: LinearLayout

    private val prefs by lazy { AppPrefs.get(this) }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applySystemBars()
        if (prefs.getBoolean(AppPrefs.ONBOARDING_DONE, false)) renderMain() else renderOnboarding(0)
        maybeShowReplyComposer()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent != null) setIntent(intent)
        maybeShowReplyComposer()
    }

    private fun maybeShowReplyComposer() {
        if (!intent.getBooleanExtra(IslandService.EXTRA_SHOW_REPLY, false)) return
        intent.removeExtra(IslandService.EXTRA_SHOW_REPLY)
        window.decorView.post {
            val input = EditText(this).apply {
                hint = "Nhập nội dung trả lời…"
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                setSingleLine(false)
                minLines = 2
                maxLines = 5
            }
            AlertDialog.Builder(this)
                .setTitle("Trả lời thông báo")
                .setView(input)
                .setNegativeButton("Hủy", null)
                .setPositiveButton("Gửi") { _, _ ->
                    val sent = IslandService.sendQuickReply(input.text.toString())
                    Toast.makeText(
                        this,
                        if (sent) "Đã gửi yêu cầu trả lời tới ứng dụng nguồn." else "Ứng dụng nguồn không còn hỗ trợ trả lời nhanh.",
                        Toast.LENGTH_LONG
                    ).show()
                }
                .show()
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
        onboardingPage = page.coerceIn(0, 2)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(26), dp(22), dp(24))
            background = appBackground()
        }
        val brand = TextView(this).apply {
            text = "DIGITALSMOD  /  LAB"
            textSize = 11f
            letterSpacing = 0.16f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(0xFFAAA3FF.toInt())
        }
        root.addView(brand)
        val progress = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(22), 0, dp(10))
        }
        repeat(3) { index ->
            val marker = View(this).apply {
                background = rounded(if (index == onboardingPage) 0xFFB9AEFF.toInt() else 0xFF333442.toInt(), 50)
            }
            progress.addView(marker, LinearLayout.LayoutParams(if (index == onboardingPage) dp(28) else dp(8), dp(5)).apply {
                marginEnd = dp(7)
            })
        }
        root.addView(progress)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(content) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        when (onboardingPage) {
            0 -> {
                content.addView(TextView(this).apply {
                    text = "Một viên thuốc.\nCả màn hình sống động hơn."
                    textSize = 31f
                    lineSpacingMultiplier = 1.03f
                    typeface = Typeface.create("sans-serif", Typeface.BOLD)
                    setTextColor(Color.WHITE)
                })
                content.addView(bodyText("DigitalLiquid đưa nhạc, thông báo và trạng thái pin vào một đảo nổi có hiệu ứng kính lỏng. Không phải ảnh GIF giả dạng công nghệ."), marginTop(14))
                content.addView(buildGlassPreview(), marginTop(28).apply { bottomMargin = dp(28) })
                content.addView(featureMiniCard("01", "Shader theo từng pixel", "Highlight, viền quang học và chuyển động bề mặt bằng AGSL trên Android 13+; có fallback cho máy cũ."), marginBottom(10))
                content.addView(featureMiniCard("02", "Điều khiển có tác dụng", "Nút nhạc gửi lệnh tới phiên phát nhạc đang hoạt động, không chỉ đổi biểu tượng."), marginBottom(10))
            }
            1 -> {
                content.addView(TextView(this).apply {
                    text = "Kính lỏng,\nkhông phải nhựa bóng."
                    textSize = 31f
                    typeface = Typeface.create("sans-serif", Typeface.BOLD)
                    setTextColor(Color.WHITE)
                })
                content.addView(bodyText("DigitalLiquid kết hợp shader AGSL, chuyển động co giãn và blur phía sau cửa sổ khi Android/thiết bị hỗ trợ. Nếu máy tắt cross-window blur, hiệu ứng shader vẫn chạy."), marginTop(14))
                content.addView(infoCard("Giới hạn kỹ thuật", "Shader tạo highlight, méo quang học giả lập và viền kính ở cấp pixel. Android không cho overlay tự do đọc pixel của ứng dụng phía dưới; muốn khúc xạ chính hình nền thật sẽ cần quyền quay/chia sẻ màn hình riêng, nên app không tự xin quyền nhạy cảm đó."), marginTop(24))
                content.addView(infoCard("Riêng tư", "Đọc thông báo chỉ hoạt động sau khi bạn tự bật quyền Notification Access. App không cần tài khoản và không tải nội dung thông báo lên máy chủ."), marginTop(12))
            }
            2 -> {
                content.addView(TextView(this).apply {
                    text = "Bạn là người\nquyết định mọi thứ."
                    textSize = 31f
                    typeface = Typeface.create("sans-serif", Typeface.BOLD)
                    setTextColor(Color.WHITE)
                })
                content.addView(bodyText("Tùy chỉnh kích thước, căn trái/giữa/phải, khoảng cách từ mép trên, màu kính và mức độ blur. Không còn kéo viên thuốc lung tung trên màn hình."), marginTop(14))
                content.addView(featureMiniCard("HIỂN THỊ", "Vị trí và kích thước", "Thanh cài đặt tác động trực tiếp lên viên thuốc, cấu hình được lưu lại."), marginTop(26))
                content.addView(featureMiniCard("HOẠT ĐỘNG", "Nhạc, thông báo, sạc, pin, timer", "Mỗi mô-đun có công tắc riêng. Một số chức năng cần Android cấp quyền."), marginTop(10))
            }
        }

        val footer = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val skip = textButton(if (onboardingPage == 2) "Để sau" else "Bỏ qua", false) {
            completeOnboarding()
        }
        footer.addView(skip, LinearLayout.LayoutParams(0, dp(52), 0.75f))
        footer.addView(textButton(if (onboardingPage == 2) "Bắt đầu sử dụng  →" else "Tiếp tục  →", true) {
            if (onboardingPage < 2) renderOnboarding(onboardingPage + 1) else completeOnboarding()
        }, LinearLayout.LayoutParams(0, dp(52), 1.5f).apply { marginStart = dp(10) })
        root.addView(footer, marginTop(12))
        root.alpha = 0f
        setContentView(root)
        installRootInsets(root, dp(22), dp(26), dp(22), dp(24))
        root.animate().alpha(1f).translationY(0f).setDuration(280L).start()
    }

    private fun completeOnboarding() {
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
        val badge = TextView(this).apply {
            text = "DL"
            textSize = 15f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = roundedGradient(intArrayOf(0xFF8172E8.toInt(), 0xFF443A86.toInt()), 15)
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
            text = when (tab) { 0 -> "CONTROL CENTER"; 1 -> "LIQUID GLASS STUDIO"; 2 -> "LIVE ACTIVITIES"; else -> "SYSTEM & PERMISSIONS" }
            textSize = 9f
            letterSpacing = 0.12f
            setTextColor(0xFF9699AA.toInt())
        }, marginTop(3))
        row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
        val state = TextView(this).apply {
            text = if (prefs.getBoolean(AppPrefs.SERVICE_ENABLED, false)) "● LIVE" else "○ READY"
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
        addEyebrow(target, "YOUR ISLAND")
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
            text = "LIVE PREVIEW"
            textSize = 10f
            letterSpacing = 0.13f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(0xFFBAB4FF.toInt())
        }, LinearLayout.LayoutParams(0, -2, 1f))
        heroHeader.addView(TextView(this).apply {
            text = if (prefs.getBoolean(AppPrefs.SERVICE_ENABLED, false)) "ĐANG BẬT" else "BẢN XEM TRƯỚC"
            textSize = 9f
            setTextColor(0xFF9DA2B1.toInt())
        })
        hero.addView(heroHeader)
        hero.addView(buildGlassPreview(), marginTop(15))
        hero.addView(TextView(this).apply {
            text = "Shader preview · chạm viên thuốc nổi thật để mở bảng điều khiển"
            textSize = 11f
            setTextColor(0xFF9397A6.toInt())
        }, marginTop(12))
        hero.addView(textButton(
            if (prefs.getBoolean(AppPrefs.SERVICE_ENABLED, false)) "Tắt Dynamic Island" else "Bật Dynamic Island  →",
            primary = !prefs.getBoolean(AppPrefs.SERVICE_ENABLED, false)
        ) {
            if (prefs.getBoolean(AppPrefs.SERVICE_ENABLED, false)) stopIsland() else startIsland()
        }, marginTop(15))
        target.addView(hero, marginTop(20))

        addEyebrow(target, "QUICK SETUP", 24)
        target.addView(permissionStatusCard(), marginTop(8))
        target.addView(featureMiniCard("01  /  OVERLAY", "Hiển thị trên ứng dụng khác", if (Settings.canDrawOverlays(this)) "Đã cấp quyền. Viên thuốc có thể xuất hiện trên màn hình." else "Bắt buộc để hiển thị đảo nổi trên ứng dụng khác."), marginTop(10))
        target.addView(textButton(if (Settings.canDrawOverlays(this)) "Quản lý quyền hiển thị" else "Cấp quyền hiển thị", false) {
            openOverlayPermission()
        }, marginTop(8))
        target.addView(featureMiniCard("02  /  NOTIFICATIONS", "Thông báo và điều khiển nhạc", if (hasNotificationAccess()) "Đã cấp quyền đọc thông báo trên thiết bị." else "Cần bật Notification Access để đọc thông báo và tìm media session."), marginTop(10))
        target.addView(textButton(if (hasNotificationAccess()) "Mở cài đặt quyền thông báo" else "Bật quyền thông báo", false) {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }, marginTop(8))
        target.addView(infoCard("Thiết kế có chủ đích", "Không có chức năng kéo viên thuốc bằng tay. Hãy mở mục Giao diện để chỉnh vị trí, kích thước và hiệu ứng bằng các thanh điều khiển."), marginTop(18))
    }

    private fun buildAppearance(target: LinearLayout) {
        addEyebrow(target, "APPEARANCE ENGINE")
        target.addView(TextView(this).apply {
            text = "Tạo chất kính của riêng bạn"
            textSize = 24f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(Color.WHITE)
        }, marginTop(4))
        target.addView(bodyText("Mọi thay đổi được lưu tự động và đồng bộ vào viên thuốc đang chạy."), marginTop(7))
        val previewCard = card().apply { setPadding(dp(14), dp(16), dp(14), dp(16)) }
        previewCard.addView(buildGlassPreview(), LinearLayout.LayoutParams(-1, dp(70)))
        previewCard.addView(TextView(this).apply {
            text = "LIVE SHADER · ${if (Build.VERSION.SDK_INT >= 33) "AGSL / GPU" else "GRADIENT FALLBACK"}"
            textSize = 10f
            letterSpacing = 0.09f
            setTextColor(0xFFB8B1FF.toInt())
            gravity = Gravity.CENTER
        }, marginTop(12))
        target.addView(previewCard, marginTop(18))

        target.addView(sectionTitle("Kích thước"), marginTop(22))
        target.addView(seekCard("Chiều rộng", "170–330 dp", AppPrefs.WIDTH, 170, 330, " dp") { applyConfig() }, marginTop(8))
        target.addView(seekCard("Chiều cao", "40–72 dp", AppPrefs.HEIGHT, 40, 72, " dp") { applyConfig() }, marginTop(8))

        target.addView(sectionTitle("Vị trí · không kéo bằng tay"), marginTop(22))
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
        target.addView(seekCard("Khoảng cách từ mép trên", "0–140 dp", AppPrefs.OFFSET_TOP, 0, 140, " dp") { applyConfig() }, marginTop(8))

        target.addView(sectionTitle("Shader & ánh sáng"), marginTop(22))
        target.addView(seekCard("Cường độ kính", "Highlight và viền quang học", AppPrefs.GLASS_STRENGTH, 15, 100, "%") { applyConfig() }, marginTop(8))
        target.addView(seekCard("Blur phía sau", "Android 12+; tùy thiết bị", AppPrefs.BLUR_RADIUS, 0, 32, " px") { applyConfig() }, marginTop(8))
        target.addView(card().apply {
            addView(TextView(this@MainActivity).apply {
                text = "Tông màu kính"
                textSize = 13f
                setTextColor(Color.WHITE)
            })
            addView(segmentedOptions(
                listOf("Midnight" to "midnight", "Violet" to "violet", "Ice" to "ice", "Graphite" to "graphite"),
                prefs.getString(AppPrefs.TINT, "midnight") ?: "midnight"
            ) { selected -> prefs.edit().putString(AppPrefs.TINT, selected).apply(); applyConfig(); renderMain() }, marginTop(10))
        }.apply { setPadding(dp(15), dp(15), dp(15), dp(15)) }, marginTop(8))
        target.addView(infoCard("Tại sao shader khác blur?", "Blur làm mềm nội dung phía sau. Shader tạo hình dạng, độ sáng theo mặt cong, vùng highlight và viền kính thay đổi theo thời gian. Hai thứ kết hợp mới cho cảm giác kính lỏng; nếu ROM tắt blur hệ thống thì shader vẫn không bị mất."), marginTop(14))
    }

    private fun buildFeatures(target: LinearLayout) {
        addEyebrow(target, "LIVE ACTIVITIES")
        target.addView(TextView(this).apply {
            text = "Chọn thứ được xuất hiện"
            textSize = 24f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(Color.WHITE)
        }, marginTop(4))
        target.addView(bodyText("Tắt mô-đun bạn không cần. Các phần cần dữ liệu từ hệ thống sẽ chỉ hoạt động khi đã cấp quyền tương ứng."), marginTop(7))
        target.addView(toggleCard("Điều khiển nhạc", "Phát/tạm dừng, bài trước và bài tiếp theo qua MediaController.", AppPrefs.SHOW_MEDIA), marginTop(18))
        target.addView(toggleCard("Thông báo", "Tiêu đề, nội dung và nút mở thông báo gốc.", AppPrefs.SHOW_NOTIFICATIONS), marginTop(10))
        target.addView(toggleCard("Lọc theo ứng dụng", "Khi bật, chỉ các ứng dụng được chọn mới hiện thông báo trong đảo nổi.", AppPrefs.FILTER_NOTIFICATIONS), marginTop(10))
        target.addView(textButton("Chọn ứng dụng được hiển thị  →", false) { showNotificationAppFilter() }, marginTop(8))
        target.addView(toggleCard("Trạng thái sạc", "Hiện trạng thái khi cắm sạc hoặc pin đầy.", AppPrefs.SHOW_CHARGING), marginTop(10))
        target.addView(toggleCard("Phần trăm pin", "Đọc pin từ broadcast hệ thống.", AppPrefs.SHOW_BATTERY), marginTop(10))
        target.addView(toggleCard("Bộ đếm thời gian", "Countdown có thể bắt đầu nhanh ngay từ viên thuốc.", AppPrefs.SHOW_TIMER), marginTop(10))

        addEyebrow(target, "QUICK TIMER", 26)
        target.addView(bodyText("Các nút bên dưới bắt đầu countdown trên viên thuốc đang chạy."), marginTop(5))
        val timerRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(1, 5, 10).forEach { minutes ->
            timerRow.addView(textButton("$minutes phút", true) {
                if (!prefs.getBoolean(AppPrefs.SERVICE_ENABLED, false)) {
                    Toast.makeText(this, "Hãy bật Dynamic Island trước.", Toast.LENGTH_SHORT).show()
                } else {
                    IslandService.startQuickTimer(minutes)
                    Toast.makeText(this, "Đã bắt đầu $minutes phút.", Toast.LENGTH_SHORT).show()
                }
            }, LinearLayout.LayoutParams(0, dp(46), 1f).apply { if (minutes > 1) marginStart = dp(7) })
        }
        target.addView(timerRow, marginTop(10))
        target.addView(textButton("Dừng và đặt lại timer", false) {
            IslandService.stopTimer()
            Toast.makeText(this, "Đã đặt lại bộ đếm.", Toast.LENGTH_SHORT).show()
        }, marginTop(8))
        target.addView(infoCard("Về quyền thông báo", "Android yêu cầu bạn bật Notification Access bằng tay. Quyền này cho phép app đọc nội dung thông báo trên thiết bị để hiển thị; không có dữ liệu nào được gửi tới máy chủ."), marginTop(16))
        target.addView(textButton("Mở cài đặt Notification Access", false) {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }, marginTop(8))
    }


    private fun showNotificationAppFilter() {
        val launchIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps: List<Pair<String, String>> = try {
            packageManager.queryIntentActivities(launchIntent, 0)
                .mapNotNull { resolved ->
                    val pkg = resolved.activityInfo?.packageName ?: return@mapNotNull null
                    if (pkg == packageName) return@mapNotNull null
                    val label = try { resolved.loadLabel(packageManager).toString() } catch (_: Exception) { pkg }
                    pkg to label
                }
                .distinctBy { it.first }
                .sortedBy { it.second.lowercase() }
        } catch (_: Exception) { emptyList<Pair<String, String>>() }
        if (apps.isEmpty()) {
            Toast.makeText(this, "Không tìm thấy danh sách ứng dụng.", Toast.LENGTH_LONG).show()
            return
        }

        val selected = (prefs.getStringSet(AppPrefs.ALLOWED_NOTIFICATION_PACKAGES, emptySet()) ?: emptySet()).toMutableSet()
        val labels = apps.map { "${it.second}  ·  ${it.first}" }.toTypedArray()
        val checked = BooleanArray(apps.size) { index -> selected.contains(apps[index].first) }
        AlertDialog.Builder(this)
            .setTitle("Ứng dụng được phép hiện")
            .setMessage("Chọn app sẽ xuất hiện trong đảo nổi. Khi bộ lọc bật, app chưa chọn sẽ bị bỏ qua.")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                val pkg = apps[which].first
                if (isChecked) selected.add(pkg) else selected.remove(pkg)
            }
            .setNeutralButton("Chọn tất cả") { _, _ ->
                prefs.edit()
                    .putStringSet(AppPrefs.ALLOWED_NOTIFICATION_PACKAGES, apps.map { it.first }.toSet())
                    .putBoolean(AppPrefs.FILTER_NOTIFICATIONS, true)
                    .apply()
                applyConfig()
                renderMain()
            }
            .setNegativeButton("Hủy", null)
            .setPositiveButton("Lưu lựa chọn") { _, _ ->
                prefs.edit()
                    .putStringSet(AppPrefs.ALLOWED_NOTIFICATION_PACKAGES, selected.toSet())
                    .putBoolean(AppPrefs.FILTER_NOTIFICATIONS, true)
                    .apply()
                applyConfig()
                renderMain()
                Toast.makeText(this, "Đã lưu bộ lọc ứng dụng.", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun buildSettings(target: LinearLayout) {
        addEyebrow(target, "SYSTEM & PRIVACY")
        target.addView(TextView(this).apply {
            text = "Cài đặt hệ thống"
            textSize = 24f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(Color.WHITE)
        }, marginTop(4))
        target.addView(bodyText("Các quyền đặc biệt của Android không thể tự bật. Bạn sẽ được chuyển sang trang Cài đặt tương ứng."), marginTop(7))
        target.addView(settingAction("Hiển thị trên ứng dụng khác", if (Settings.canDrawOverlays(this)) "Đã cấp quyền" else "Chưa cấp quyền", "Mở trang cấp quyền overlay") { openOverlayPermission() }, marginTop(18))
        target.addView(settingAction("Notification Access", if (hasNotificationAccess()) "Đã cấp quyền" else "Chưa cấp quyền", "Cho phép đọc thông báo và media session") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
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
            addView(bodyText("Tắt hiệu ứng co giãn khi mở/đóng viên thuốc; ánh sáng shader tĩnh vẫn giữ nguyên."), marginTop(5))
        }.apply { setPadding(dp(15), dp(14), dp(15), dp(14)) }, marginTop(10))

        target.addView(settingAction("Chạy lại onboarding", "Xem lại giới thiệu", "Hiện lại phần giới thiệu 3 bước") {
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
        target.addView(infoCard("DigitalLiquid 1.1", "Native Android · Kotlin · AGSL shader trên Android 13+ · min Android 8.0. Không cần tài khoản, không dùng thư viện quảng cáo."), marginTop(20))
    }

    private fun buildGlassPreview(): View {
        val frame = FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            clipChildren = false
            clipToPadding = false
        }
        val glass = LiquidGlassView(this).apply {
            configure(
                prefs.getInt(AppPrefs.GLASS_STRENGTH, 72),
                prefs.getString(AppPrefs.TINT, "midnight") ?: "midnight",
                expandedNow = false
            )
        }
        frame.addView(glass, FrameLayout.LayoutParams(-1, -1))
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(10), 0, dp(10), 0)
        }
        row.addView(TextView(this).apply {
            text = "◈"
            textSize = 19f
            setTextColor(0xFFB9AEFF.toInt())
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(dp(26), -1))
        row.addView(TextView(this).apply {
            text = "DigitalLiquid"
            textSize = 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_VERTICAL
        })
        row.addView(View(this), LinearLayout.LayoutParams(dp(10), 1))
        row.addView(TextView(this).apply {
            text = "•••"
            textSize = 15f
            setTextColor(0xFFB3B7C5.toInt())
        })
        frame.addView(row, FrameLayout.LayoutParams(-1, -1))
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
        outer.addView(statusLine("Hiển thị nổi", Settings.canDrawOverlays(this)), marginTop(10))
        outer.addView(statusLine("Đọc thông báo", hasNotificationAccess()), marginTop(8))
        outer.addView(statusLine("Hiệu ứng shader", true, if (Build.VERSION.SDK_INT >= 33) "AGSL" else "Fallback"), marginTop(8))
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
            AppPrefs.WIDTH -> 226; AppPrefs.HEIGHT -> 46; AppPrefs.OFFSET_TOP -> 8; AppPrefs.GLASS_STRENGTH -> 72; AppPrefs.BLUR_RADIUS -> 18; else -> min
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
        lineSpacingMultiplier = 1.12f
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

    private fun hasNotificationAccess(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        return enabled.split(":").any { it.equals(componentNameForListener(), ignoreCase = true) }
    }

    private fun componentNameForListener(): String =
        android.content.ComponentName(this, IslandNotificationListener::class.java).flattenToString()

    private fun openOverlayPermission() {
        if (Build.VERSION.SDK_INT >= 23) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
    }

    private fun startIsland() {
        if (!Settings.canDrawOverlays(this)) {
            openOverlayPermission()
            Toast.makeText(this, "Hãy cấp quyền hiển thị nổi rồi bật lại đảo.", Toast.LENGTH_LONG).show()
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
