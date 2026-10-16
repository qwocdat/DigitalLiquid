package com.digitalsmod.digitalliquid

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.util.ArrayDeque
import kotlin.math.abs

class HyperIslandVisibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var pendingScan: Runnable? = null
    private var cameraForegroundActive = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        connectedInstance = this
        serviceInfo = serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_WINDOWS_CHANGED or
                AccessibilityEvent.TYPE_VIEW_SCROLLED
            notificationTimeout = 40L
        }
        IslandService.refreshOverlayWindowType()
        scheduleScan(0L)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val sourcePackage = event?.packageName?.toString()?.lowercase().orEmpty()
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && sourcePackage.isNotBlank()) {
            when {
                isCameraPackage(sourcePackage) -> {
                    cameraForegroundActive = true
                    IslandService.setCameraActivityActive(true)
                }
                sourcePackage == "com.android.systemui" || sourcePackage == "com.miui.systemui" -> Unit
                sourcePackage != packageName -> {
                    cameraForegroundActive = false
                    IslandService.setCameraActivityActive(false)
                }
            }
        }
        if (sourcePackage.isNotBlank() && sourcePackage !in SYSTEM_UI_PACKAGES &&
            !isScreenRecorderPackage(sourcePackage) &&
            event?.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED) return
        scheduleScan(35L)
    }

    private fun clickVisibleStopRecordingAction(): Boolean {
        val stopPhrases = listOf(
            "stop screen recording", "stop recording", "end recording", "finish recording",
            "dừng quay màn hình", "dừng ghi màn hình", "kết thúc quay", "kết thúc ghi màn hình",
            "停止录屏", "停止屏幕录制", "停止录制", "结束录屏", "结束录制"
        )
        val shortStopLabels = listOf("stop", "dừng", "kết thúc", "停止", "结束")
        val startLabels = listOf("start recording", "record screen", "begin recording", "bắt đầu quay", "bắt đầu", "开始录制", "开始")
        val contextWords = listOf("screen recorder", "screen recording", "screenrecord", "screen_record", "quay màn hình", "录屏", "屏幕录制")
        val currentWindows = try { windows ?: emptyList() } catch (_: Exception) { emptyList() }
        for (window in currentWindows) {
            val root = try { window.root } catch (_: Exception) { null } ?: continue
            val nodes = ArrayList<AccessibilityNodeInfo>()
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)
            var visited = 0
            try {
                while (queue.isNotEmpty() && visited < 500) {
                    val node = queue.removeFirst()
                    nodes.add(node)
                    visited++
                    for (i in 0 until node.childCount) {
                        try { node.getChild(i)?.let(queue::add) } catch (_: Exception) { }
                    }
                }
                val pkg = root.packageName?.toString().orEmpty().lowercase()
                val labels = nodes.map { "${it.text ?: ""} ${it.contentDescription ?: ""} ${it.viewIdResourceName.orEmpty()}".lowercase() }
                val hasRecorderContext = isScreenRecorderPackage(pkg) || labels.any { label -> contextWords.any(label::contains) }
                val hasStartAction = nodes.indices.any { i -> nodes[i].isClickable && startLabels.any(labels[i]::contains) }
                val directMatch = nodes.indices.firstOrNull { i ->
                    nodes[i].isVisibleToUser && nodes[i].isEnabled && nodes[i].isClickable && stopPhrases.any(labels[i]::contains)
                }
                val contextualStop = if (hasRecorderContext && !hasStartAction) nodes.indices.firstOrNull { i ->
                    nodes[i].isVisibleToUser && nodes[i].isEnabled && nodes[i].isClickable && shortStopLabels.any(labels[i]::contains)
                } else null
                val chosen = directMatch ?: contextualStop
                if (chosen != null && nodes[chosen].performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            } catch (_: Exception) {
            } finally {
                nodes.forEach { try { it.recycle() } catch (_: Exception) { } }
            }
        }
        return false
    }

    private fun isCameraPackage(packageName: String): Boolean =
        packageName in CAMERA_PACKAGES ||
            (packageName.startsWith("com.") && packageName.contains("camera") &&
                !packageName.contains("scanner") && packageName !in SYSTEM_UI_PACKAGES)

    private fun isScreenRecorderPackage(packageName: String): Boolean =
        packageName.contains("screenrecorder") || packageName.contains("screen_recorder") ||
            packageName.contains("screenrecord") || packageName.contains("screen_record") ||
            packageName == "com.android.screenrecord"

    private fun scheduleScan(delay: Long) {
        pendingScan?.let { handler.removeCallbacks(it) }
        val task = Runnable { scanAndUpdate() }
        pendingScan = task
        handler.postDelayed(task, delay)
    }

    private data class ScanResult(
        var hyperIslandVisible: Boolean = false,
        var screenRecordingVisible: Boolean = false,
        var cameraPrivacyIndicatorVisible: Boolean = false,
        var ringerUiVisible: Boolean = false
    )

    private fun scanAndUpdate() {
        val result = ScanResult()
        try {
            val dm = resources.displayMetrics
            val screenWidth = if (android.os.Build.VERSION.SDK_INT >= 30) {
                try { (getSystemService(WINDOW_SERVICE) as WindowManager).currentWindowMetrics.bounds.width() }
                catch (_: Exception) { dm.widthPixels }
            } else dm.widthPixels
            val density = dm.density.coerceAtLeast(1f)
            val statusId = resources.getIdentifier("status_bar_height", "dimen", "android")
            val statusHeight = if (statusId > 0) resources.getDimensionPixelSize(statusId) else (24 * density).toInt()
            val cutout = currentCutoutBounds()
            val sideCutout = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE &&
                cutout != null && (cutout.left <= 18 * density || cutout.right >= screenWidth - 18 * density)
            val centerX = if (sideCutout) cutout!!.centerX() else screenWidth / 2
            val centerY = cutout?.centerY() ?: statusHeight / 2
            val centerTolerance = if (sideCutout) (112 * density).toInt() else (screenWidth * 0.20f).toInt()
            val minWidth = (82 * density).toInt()
            val maxWidth = (screenWidth * 0.78f).toInt()
            val maxTop = statusHeight + (34 * density).toInt()
            val maxHeight = ((if (sideCutout) 168f else 82f) * density).toInt()

            for (window in windows ?: emptyList<AccessibilityWindowInfo>()) {
                val root = window.root ?: continue
                val pkg = root.packageName?.toString().orEmpty().lowercase()
                val isSystemUi = pkg in SYSTEM_UI_PACKAGES
                if (!isSystemUi && !isScreenRecorderPackage(pkg)) {
                    try { root.recycle() } catch (_: Exception) { }
                    continue
                }
                scanTree(
                    root, screenWidth, centerX, centerY, centerTolerance, minWidth, maxWidth,
                    maxTop, maxHeight, density, sideCutout, cutout, result, isSystemUi, pkg
                )
            }
        } catch (_: Exception) {
            IslandService.setHyperIslandVisibilityOverride(false)
            IslandService.setAccessibilityScreenRecordingActive(false)
            IslandService.setCameraActivityActive(cameraForegroundActive)
            IslandService.setRingerUiActive(false)
            return
        }
        IslandService.setHyperIslandVisibilityOverride(result.hyperIslandVisible)
        IslandService.setAccessibilityScreenRecordingActive(result.screenRecordingVisible)
        IslandService.setCameraActivityActive(cameraForegroundActive || result.cameraPrivacyIndicatorVisible)
        IslandService.setRingerUiActive(result.ringerUiVisible)
    }

    private fun currentCutoutBounds(): Rect? {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            try {
                return (getSystemService(WINDOW_SERVICE) as WindowManager).currentWindowMetrics.windowInsets.displayCutout?.boundingRects
                    ?.filterNot { it.isEmpty }
                    ?.maxByOrNull { it.width().toLong() * it.height().toLong() }
                    ?.let { Rect(it) }
            } catch (_: Exception) { }
        }
        return null
    }

    private fun scanTree(
        root: AccessibilityNodeInfo,
        screenWidth: Int,
        centerX: Int,
        centerY: Int,
        centerTolerance: Int,
        minWidth: Int,
        maxWidth: Int,
        maxTop: Int,
        maxHeight: Int,
        density: Float,
        sideCutout: Boolean,
        cutout: Rect?,
        result: ScanResult,
        allowIslandDetection: Boolean,
        pkg: String
    ) {
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(root to 0)
        var visited = 0
        val islandIds = listOf("island", "capsule", "live_activity", "dynamic_pill", "ongoing_chip", "hyperisland")
        val activityWords = listOf(
            "music", "track", "song", "timer", "stopwatch", "countdown",
            "call", "phone", "charging", "location", "navigation", "sharing", "hotspot", "bluetooth",
            "đang phát", "hẹn giờ", "bấm giờ", "đếm ngược",
            "cuộc gọi", "đang sạc", "chia sẻ", "định vị",
            "播放", "计时", "请勿打扰",
            "通话", "充电", "导航", "正在播放"
        )
        val recordingWords = listOf(
            "screen is being recorded", "recording your screen", "recording screen",
            "recording in progress", "tap to stop recording", "stop screen recording", "stop recording",
            "currently recording", "đang quay màn hình", "đang ghi màn hình",
            "đang ghi lại màn hình", "nhấn để dừng quay", "chạm để dừng quay",
            "dừng quay màn hình", "正在录屏", "正在屏幕录制", "录屏中", "正在录制屏幕"
        )
        val settingsUiWords = listOf(
            "độ phân giải", "chất lượng video", "hướng màn hình", "nguồn âm thanh",
            "tốc độ khung hình", "tùy chọn quay", "khóa màn hình để kết thúc",
            "resolution", "video quality", "frame rate", "sound source"
        )
        val cameraPrivacyWords = listOf(
            "camera in use", "camera is being used", "using the camera", "camera active",
            "máy ảnh đang được sử dụng", "đang sử dụng máy ảnh", "camera đang hoạt động",
            "đang dùng camera", "正在使用相机", "正在使用摄像头"
        )
        val ringerUiWords = listOf(
            "silent mode", "vibrate mode", "ringer mode", "ring volume", "volume panel", "volume control",
            "do not disturb", "notifications silenced", "mute notifications", "ringtone", "ringer muted",
            "chế độ im lặng", "chế độ rung", "âm lượng chuông", "không làm phiền",
            "tắt tiếng thông báo", "tạm dừng thông báo", "notification controls", "notification settings",
            "notification shade", "notifications silenced", "no notifications", "silent notifications",
            "do not disturb", "volume slider", "ringer mode", "ring volume", "volume panel", "volume control",
            "notification_panel", "notification_panel_window", "status_bar_expanded", "quick_settings",
            "control_center", "volume_dialog", "volume_panel", "miui_volume", "ringer_panel",
            "静音模式", "振动模式", "铃声音量", "请勿打扰", "通知已静音", "通知设置", "通知面板", "音量面板"
        )

        val startActionWords = listOf("start recording", "record screen", "begin recording", "bắt đầu quay", "bắt đầu", "开始录制", "开始", "start")
        val startActionIds = listOf("start", "launch", "play", "record_btn", "btn_start", "start_button")
        val stopActionWords = listOf("stop", "finish", "end recording", "dừng", "kết thúc", "停止", "结束")

        var hasSettingsUi = false
        var startActionVisible = false
        var stopActionVisible = false
        var hasActiveTimerText = false
        var explicitActivePhraseVisible = false
        var recordingIndicatorIdVisible = false

        val timerRegex = Regex("\\b\\d{1,2}:\\d{2}\\b")

        while (queue.isNotEmpty() && visited < 700) {
            val (node, depth) = queue.removeFirst()
            visited++
            try {
                if (!node.isVisibleToUser) continue
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                val width = bounds.width()
                val height = bounds.height()
                val text = node.text?.toString().orEmpty()
                val description = node.contentDescription?.toString().orEmpty()
                val resourceId = try { node.viewIdResourceName.orEmpty().lowercase() } catch (_: Exception) { "" }
                val visibleLabel = "$text $description".lowercase()
                val searchableLabel = "$visibleLabel $resourceId"

                if (settingsUiWords.any(searchableLabel::contains)) {
                    hasSettingsUi = true
                }

                val isClockNode = resourceId.contains("clock") || resourceId.contains("time") || resourceId.contains("date") || resourceId.contains("header")
                val timerMatch = timerRegex.find(visibleLabel)?.value
                if (!isClockNode && timerMatch != null && timerMatch != "00:00" && timerMatch != "0:00") {
                    if (isScreenRecorderPackage(pkg) || recordingWords.any(searchableLabel::contains) || resourceId.contains("timer") || resourceId.contains("record")) {
                        hasActiveTimerText = true
                    }
                }

                val isStartButton = (node.isClickable || node.isCheckable) && (
                    startActionWords.any(searchableLabel::contains) ||
                    startActionIds.any(resourceId::contains)
                )
                if (isStartButton) {
                    startActionVisible = true
                }

                if (node.isClickable && stopActionWords.any(searchableLabel::contains)) {
                    stopActionVisible = true
                }

                if (recordingWords.any(searchableLabel::contains)) {
                    explicitActivePhraseVisible = true
                }

                if (listOf("screen_recording_indicator", "recording_indicator", "screen_recording_chip").any(resourceId::contains)) {
                    recordingIndicatorIdVisible = true
                }

                if (allowIslandDetection && !result.cameraPrivacyIndicatorVisible && cameraPrivacyWords.any(searchableLabel::contains)) {
                    result.cameraPrivacyIndicatorVisible = true
                }

                val ratio = width.toFloat() / height.coerceAtLeast(1).toFloat()
                val pillShape = ratio in 1.65f..8.8f || (sideCutout && ratio in 0.12f..0.62f)
                val atCameraPosition = if (sideCutout && cutout != null) {
                    val nearPhysicalSide = bounds.left <= (118 * density).toInt() ||
                        bounds.right >= screenWidth - (118 * density).toInt()
                    nearPhysicalSide && abs(bounds.centerX() - centerX) <= centerTolerance &&
                        abs(bounds.centerY() - centerY) <= (168 * density).toInt() &&
                        maxOf(width, height) >= (58 * density).toInt()
                } else {
                    bounds.top >= 0 && bounds.top <= maxTop &&
                        height in (15 * density).toInt()..maxHeight && width in minWidth..maxWidth &&
                        abs(bounds.centerX() - centerX) <= centerTolerance && width < (screenWidth * 0.78f).toInt()
                }
                val hasRingerOrNoticeSignal = allowIslandDetection && ringerUiWords.any(searchableLabel::contains)
                val displayHeight = resources.displayMetrics.heightPixels
                val nearCameraPopup = if (sideCutout && cutout != null) {
                    val nearPhysicalSide = bounds.left <= (118 * density).toInt() ||
                        bounds.right >= screenWidth - (118 * density).toInt()
                    nearPhysicalSide && abs(bounds.centerX() - centerX) <= centerTolerance &&
                        abs(bounds.centerY() - centerY) <= (180 * density).toInt()
                } else {
                    bounds.top >= 0 && bounds.top <= maxTop + (112 * density).toInt() &&
                        abs(bounds.centerX() - centerX) <= centerTolerance
                }
                val plausiblePopup = width >= (18 * density).toInt() && height >= (10 * density).toInt() &&
                    width <= (screenWidth * 0.88f).toInt() && height <= (displayHeight * 0.55f).toInt()
                if (hasRingerOrNoticeSignal && nearCameraPopup && plausiblePopup) {
                    result.ringerUiVisible = true
                }

                val idHint = islandIds.any(resourceId::contains)
                val labelHint = activityWords.any(visibleLabel::contains)
                val isRingerLabel = ringerUiWords.any(searchableLabel::contains)
                val longVisibleLabel = !isRingerLabel && visibleLabel.isNotBlank() &&
                    width >= (132 * density).toInt() && height >= (22 * density).toInt()
                if (allowIslandDetection && atCameraPosition && pillShape &&
                    (idHint || labelHint || longVisibleLabel)) {
                    result.hyperIslandVisible = true
                }

                if (depth < 8) {
                    for (i in 0 until node.childCount) {
                        try { node.getChild(i)?.let { queue.add(it to depth + 1) } } catch (_: Exception) { }
                    }
                }
            } finally {
                try { node.recycle() } catch (_: Exception) { }
            }
        }
        while (queue.isNotEmpty()) {
            try { queue.removeFirst().first.recycle() } catch (_: Exception) { }
        }

        if (hasSettingsUi || startActionVisible) {
            result.screenRecordingVisible = false
            return
        }

        val hasActiveSignal = hasActiveTimerText || stopActionVisible || explicitActivePhraseVisible || recordingIndicatorIdVisible
        result.screenRecordingVisible = hasActiveSignal
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        pendingScan?.let { handler.removeCallbacks(it) }
        IslandService.setHyperIslandVisibilityOverride(null)
        IslandService.setAccessibilityScreenRecordingActive(false)
        IslandService.setCameraActivityActive(false)
        IslandService.setRingerUiActive(false)
        cameraForegroundActive = false
        if (connectedInstance === this) connectedInstance = null
        IslandService.refreshOverlayWindowType()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        @Volatile private var connectedInstance: HyperIslandVisibilityService? = null

        fun instance(): HyperIslandVisibilityService? = connectedInstance

        /**
         * Chụp 1 khung hình sạch sẽ của nền launcher để shader tính toán khúc xạ quang học.
         * Không chụp tuần hoàn liên tục, loại bỏ hoàn toàn việc lặp đè hình.
         */
        fun captureCleanScreen(callback: (Bitmap?) -> Unit) {
            val service = connectedInstance
            if (service == null || Build.VERSION.SDK_INT < 30) {
                callback(null)
                return
            }
            try {
                service.takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    { runnable -> service.handler.post(runnable) },
                    object : AccessibilityService.TakeScreenshotCallback {
                        override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                            val buffer = screenshot.hardwareBuffer
                            val colorSpace = screenshot.colorSpace
                            val bmp = if (buffer != null) {
                                Bitmap.wrapHardwareBuffer(buffer, colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                            } else null
                            buffer?.close()
                            service.handler.post { callback(bmp) }
                        }

                        override fun onFailure(errorCode: Int) {
                            service.handler.post { callback(null) }
                        }
                    }
                )
            } catch (_: Exception) {
                callback(null)
            }
        }

        fun requestRecents(): Boolean {
            val service = connectedInstance ?: return false
            return try { service.performGlobalAction(GLOBAL_ACTION_RECENTS) } catch (_: Exception) { false }
        }

        fun requestHome(): Boolean {
            val service = connectedInstance ?: return false
            return try { service.performGlobalAction(GLOBAL_ACTION_HOME) } catch (_: Exception) { false }
        }

        fun overlayWindowManager(): WindowManager? {
            val service = connectedInstance ?: return null
            return try { service.getSystemService(WINDOW_SERVICE) as? WindowManager } catch (_: Exception) { null }
        }

        fun requestStopRecordingFromAccessibility(): Boolean {
            val service = connectedInstance ?: return false
            return try { service.clickVisibleStopRecordingAction() } catch (_: Exception) { false }
        }

        private val SYSTEM_UI_PACKAGES = setOf(
            "com.android.systemui",
            "com.miui.systemui",
            "com.miui.home"
        )
        private val CAMERA_PACKAGES = setOf(
            "com.android.camera",
            "com.miui.camera",
            "com.google.android.googlecamera",
            "com.android.camera2"
        )
    }
}
