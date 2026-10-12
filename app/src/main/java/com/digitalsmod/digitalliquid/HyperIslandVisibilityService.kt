package com.digitalsmod.digitalliquid

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Rect
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.util.ArrayDeque
import kotlin.math.abs

/**
 * Best-effort HyperOS live-activity observer. Xiaomi does not expose a public API that says
 * whether HyperIsland is visible. This service uses accessible System UI node bounds/labels,
 * while NotificationListener remains the primary screen-recorder signal.
 */
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
        var cameraPrivacyIndicatorVisible: Boolean = false
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
                    maxTop, maxHeight, density, sideCutout, cutout, result, isSystemUi
                )
            }
        } catch (_: Exception) {
            // If accessibility data is temporarily unavailable, don't permanently hide the pill.
            IslandService.setHyperIslandVisibilityOverride(false)
            IslandService.setAccessibilityScreenRecordingActive(false)
            IslandService.setCameraActivityActive(cameraForegroundActive)
            return
        }
        IslandService.setHyperIslandVisibilityOverride(result.hyperIslandVisible)
        IslandService.setAccessibilityScreenRecordingActive(result.screenRecordingVisible)
        IslandService.setCameraActivityActive(cameraForegroundActive || result.cameraPrivacyIndicatorVisible)
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
        allowIslandDetection: Boolean
    ) {
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(root to 0)
        var visited = 0
        val islandIds = listOf("island", "capsule", "live_activity", "dynamic_pill", "ongoing_chip", "hyperisland")
        val activityWords = listOf(
            "music", "track", "song", "timer", "stopwatch", "countdown",
            "call", "phone", "charging", "location", "navigation", "sharing", "hotspot", "bluetooth",
            "đang phát", "hẹn giờ", "bấm giờ", "đếm ngược",
            "cuộc gọi", "đang sạc", "chia sẻ", "định vị", "播放", "计时",
            "通话", "充电", "导航", "正在播放"
        )
        // Only phrases that imply a live capture count. Merely opening the recorder UI
        // often exposes labels like "Screen recorder" or "Screen recording" in its tree.
        val recordingWords = listOf(
            "screen is being recorded", "recording your screen", "recording screen",
            "recording in progress", "tap to stop recording", "stop screen recording", "stop recording",
            "currently recording", "đang quay màn hình", "đang ghi màn hình",
            "đang ghi lại màn hình", "nhấn để dừng quay", "chạm để dừng quay",
            "dừng quay màn hình", "正在录屏", "正在屏幕录制", "录屏中", "正在录制屏幕"
        )
        val cameraPrivacyWords = listOf(
            "camera in use", "camera is being used", "using the camera", "camera active",
            "máy ảnh đang được sử dụng", "đang sử dụng máy ảnh", "camera đang hoạt động",
            "đang dùng camera", "正在使用相机", "正在使用摄像头"
        )
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

                val isScreenRecordingNode = recordingWords.any(searchableLabel::contains)
                if (!result.screenRecordingVisible && isScreenRecordingNode) {
                    result.screenRecordingVisible = true
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
                val idHint = islandIds.any(resourceId::contains)
                val labelHint = activityWords.any(visibleLabel::contains)
                val longVisibleLabel = visibleLabel.isNotBlank() &&
                    width >= (132 * density).toInt() && height >= (22 * density).toInt()
                // Do not mistake the recorder chip/label for HyperIsland itself: recording
                // is exactly the activity this companion is meant to add an effect for.
                if (allowIslandDetection && !isScreenRecordingNode && atCameraPosition && pillShape &&
                    (idHint || labelHint || longVisibleLabel)) {
                    result.hyperIslandVisible = true
                }

                if (depth < 8 && !(result.hyperIslandVisible && result.screenRecordingVisible)) {
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
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        pendingScan?.let { handler.removeCallbacks(it) }
        IslandService.setHyperIslandVisibilityOverride(null)
        IslandService.setAccessibilityScreenRecordingActive(false)
        IslandService.setCameraActivityActive(false)
        cameraForegroundActive = false
        if (connectedInstance === this) connectedInstance = null
        IslandService.refreshOverlayWindowType()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        @Volatile private var connectedInstance: HyperIslandVisibilityService? = null

        fun overlayWindowManager(): WindowManager? {
            val service = connectedInstance ?: return null
            return try { service.getSystemService(WINDOW_SERVICE) as? WindowManager } catch (_: Exception) { null }
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
