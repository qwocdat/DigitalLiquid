package com.digitalsmod.digitalliquid

import android.app.Notification
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Reads only the existence/type of ongoing system notifications.
 * Screen recording drives DigitalLiquid's REC animation. Media, Clock, calls and
 * audio-recording activities suppress this overlay so HyperOS can remain in control.
 */
class IslandNotificationListener : NotificationListenerService() {
    override fun onCreate() {
        super.onCreate()
        listenerInstance = this
    }

    override fun onDestroy() {
        if (listenerInstance === this) listenerInstance = null
        recordingKeys.clear()
        nativeActivityKeys.clear()
        IslandService.setScreenRecordingActive(false)
        IslandService.setNativeIslandActivityActive(false)
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        updateKey(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        recordingKeys.remove(sbn.key)
        nativeActivityKeys.remove(sbn.key)
        notifyStates()
    }

    private fun updateKey(sbn: StatusBarNotification) {
        val recording = isActiveScreenRecordingNotification(sbn)
        if (recording) {
            recordingKeys.add(sbn.key)
            nativeActivityKeys.remove(sbn.key)
        } else {
            recordingKeys.remove(sbn.key)
            if (isHyperOsHandledActivity(sbn)) nativeActivityKeys.add(sbn.key)
            else nativeActivityKeys.remove(sbn.key)
        }
        notifyStates()
    }

    private fun notifyStates() {
        IslandService.setScreenRecordingActive(recordingKeys.isNotEmpty())
        IslandService.setNativeIslandActivityActive(nativeActivityKeys.isNotEmpty())
    }

    private fun notificationText(sbn: StatusBarNotification): String {
        val extras = sbn.notification.extras ?: return ""
        return listOf(
            extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty(),
            extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty(),
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty(),
            extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty(),
            extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT)?.toString().orEmpty()
        ).joinToString(" ").lowercase(Locale.ROOT)
    }

    private fun isActiveScreenRecordingNotification(sbn: StatusBarNotification): Boolean {
        val notification = sbn.notification
        val pkg = sbn.packageName.lowercase(Locale.ROOT)
        val extras = notification.extras

        // 1. Bắt buộc phải là thông báo dạng duy trì (ongoing)
        val ongoing = sbn.isOngoing ||
            (notification.flags and Notification.FLAG_ONGOING_EVENT) != 0 ||
            (notification.flags and Notification.FLAG_NO_CLEAR) != 0
        if (!ongoing) return false

        // 2. LOẠI BỎ NGHÊM NGẶT thông báo Trình phát đa phương tiện (YouTube, Music, MediaSession)
        if (extras != null) {
            if (extras.containsKey(Notification.EXTRA_MEDIA_SESSION)) return false
            val template = extras.getString(Notification.EXTRA_TEMPLATE).orEmpty()
            if (template.contains("MediaStyle", ignoreCase = true)) return false
        }
        if (notification.category == Notification.CATEGORY_TRANSPORT) return false

        // 3. Kiểm tra Channel ID (Android 8+): Bỏ qua tất cả các kênh Media/Playback/Music
        val channelId = if (Build.VERSION.SDK_INT >= 26) notification.channelId.orEmpty().lowercase(Locale.ROOT) else ""
        if (channelId.contains("media") || channelId.contains("playback") || channelId.contains("music")) return false

        // 4. Chỉ chấp nhận gói ứng dụng Quay màn hình chuyên dụng hoặc SystemUI
        val recorderPackage = isScreenRecorderPackage(pkg)
        val systemUiPackage = pkg == "com.android.systemui" || pkg == "com.miui.systemui" ||
            pkg == "com.miui.systemui.plugin"
        if (!recorderPackage && !systemUiPackage) return false

        val text = notificationText(sbn)

        // 5. LOẠI BỎ thông báo Ghi âm âm thanh / Cuộc gọi
        val audioRecordingWords = listOf(
            "call recording", "voice recording", "audio recording", "recording audio", "sound recorder",
            "ghi âm cuộc gọi", "ghi âm giọng nói", "ghi âm âm thanh", "đang ghi âm", "máy ghi âm", "录音", "通话录音"
        )
        if (audioRecordingWords.any(text::contains)) return false
        if (pkg.contains("soundrecorder") || pkg.contains("voicerecorder") || pkg.contains("audio.recorder")) return false

        // 6. Kiểm tra ngữ cảnh từ khóa quay màn hình
        val recorderContextWords = listOf(
            "screen recorder", "screen recording", "screenrecord", "screen_record",
            "quay màn hình", "ghi màn hình", "录屏", "屏幕录制", "录制屏幕"
        )
        val hasRecorderContext = recorderPackage || recorderContextWords.any(text::contains) ||
            channelId.contains("screen_record") || channelId.contains("screenrecorder") || channelId.contains("recording")

        if (!hasRecorderContext) return false

        val explicitActiveWords = listOf(
            "screen is being recorded", "recording your screen", "screen recording is active",
            "recording screen now", "recording in progress", "tap to stop recording",
            "tap stop to finish", "stop screen recording", "currently recording",
            "đang quay màn hình", "đang ghi màn hình", "đang ghi lại màn hình",
            "nhấn để dừng quay", "chạm để dừng quay", "dừng quay màn hình",
            "正在录屏", "屏幕正在录制", "正在屏幕录制", "录屏中", "正在录制屏幕",
            "录制中", "已开始录屏", "屏幕录制中"
        )
        val hasActivePhrase = explicitActiveWords.any(text::contains)

        // Nếu là ứng dụng quay màn hình chuyên dụng và có câu xác nhận đang quay
        if (recorderPackage && hasActivePhrase) return true

        // Kiểm tra nút điều khiển của thông báo (Actions)
        val actions = notification.actions
        if (actions != null) {
            val stopWords = listOf("stop", "finish", "end recording", "dừng", "kết thúc", "停止", "结束")
            val startWords = listOf("start recording", "record screen", "begin recording", "bắt đầu", "开始录制", "开始")
            val actionTitles = actions.map { it.title?.toString()?.lowercase(Locale.ROOT).orEmpty() }
            val hasStop = actionTitles.any { title -> stopWords.any(title::contains) }
            val hasStart = actionTitles.any { title -> startWords.any(title::contains) }

            if (hasStop && !hasStart) {
                if (systemUiPackage) {
                    val isServiceOrProgress = notification.category == Notification.CATEGORY_SERVICE ||
                        notification.category == Notification.CATEGORY_PROGRESS ||
                        notification.category == Notification.CATEGORY_STATUS
                    val channelMatches = channelId.contains("screen") || channelId.contains("record") || channelId.contains("cast")
                    if (hasActivePhrase || (isServiceOrProgress && channelMatches) || channelMatches) {
                        return true
                    }
                } else {
                    return true
                }
            }
        }

        // Trường hợp SystemUI khi thực sự đang quay màn hình
        if (systemUiPackage && hasActivePhrase) {
            return true
        }

        return false
    }

    private fun isScreenRecorderPackage(packageName: String): Boolean =
        packageName.contains("screenrecorder") || packageName.contains("screen_recorder") ||
            packageName.contains("screenrecord") || packageName.contains("screen_record") ||
            packageName == "com.android.screenrecord" || packageName == "com.miui.screenrecorder"

    private fun readMediaPlaybackState(sbn: StatusBarNotification): Int? {
        val extras = sbn.notification.extras ?: return null
        val token = try {
            if (Build.VERSION.SDK_INT >= 33) {
                extras.getParcelable(Notification.EXTRA_MEDIA_SESSION, MediaSession.Token::class.java)
            } else {
                @Suppress("DEPRECATION")
                extras.getParcelable(Notification.EXTRA_MEDIA_SESSION) as? MediaSession.Token
            }
        } catch (_: Exception) {
            null
        } ?: return null

        return try {
            MediaController(this, token).playbackState?.state
        } catch (_: Exception) {
            null
        }
    }

    private fun isHyperOsHandledActivity(sbn: StatusBarNotification): Boolean {
        val n = sbn.notification
        val pkg = sbn.packageName.lowercase(Locale.ROOT)
        val text = notificationText(sbn)
        val ongoing = sbn.isOngoing ||
            (n.flags and Notification.FLAG_ONGOING_EVENT) != 0 ||
            (n.flags and Notification.FLAG_NO_CLEAR) != 0

        val hasMediaSession = n.extras?.containsKey(Notification.EXTRA_MEDIA_SESSION) == true ||
            n.category == Notification.CATEGORY_TRANSPORT
        if (hasMediaSession && ongoing) {
            val playbackState = readMediaPlaybackState(sbn)
            if (playbackState != null) return playbackState == PlaybackState.STATE_PLAYING
            return true
        }

        val clockPackage = pkg.contains("deskclock") || pkg.contains("miui.clock") ||
            pkg.contains("google.android.deskclock") || pkg.endsWith(".clock")
        val clockWords = listOf("timer", "stopwatch", "countdown", "time remaining", "chronometer", "đồng hồ", "hẹn giờ", "bấm giờ", "đếm ngược", "剩余时间", "计时")
        val hasChronometer = n.extras?.getBoolean("android.showChronometer", false) == true ||
            n.extras?.getBoolean("android.chronometerCountDown", false) == true
        if (clockPackage && ongoing && (hasChronometer || clockWords.any(text::contains))) return true

        if (n.category == Notification.CATEGORY_CALL && ongoing) return true
        val audioRecordWords = listOf("voice recorder", "audio recorder", "recording audio", "audio recording", "ghi âm", "录音")
        val knownAudioRecorder = pkg.contains("soundrecorder") || pkg.contains("audio.recorder") || pkg.contains("voicerecorder")
        if (ongoing && !isActiveScreenRecordingNotification(sbn) &&
            (audioRecordWords.any(text::contains) || knownAudioRecorder)) return true

        val activeWords = listOf("playing in background", "phát nền", "đang phát", "正在播放")
        return ongoing && activeWords.any(text::contains) &&
            (pkg.contains("music") || pkg.contains("audio") || pkg.contains("media"))
    }

    private fun sendStopRecordingAction(): Boolean {
        val notifications = try { activeNotifications } catch (_: Exception) { null } ?: return false
        val stopWords = listOf("stop", "end", "finish", "dừng", "kết thúc", "停止", "结束")
        for (sbn in notifications) {
            if (!isActiveScreenRecordingNotification(sbn)) continue
            val actions = sbn.notification.actions ?: continue
            for (action in actions) {
                val title = action.title?.toString()?.lowercase(Locale.ROOT).orEmpty()
                if (stopWords.any(title::contains)) {
                    try {
                        val pendingIntent = action.actionIntent ?: continue
                        pendingIntent.send()
                        return true
                    } catch (_: Exception) { }
                }
            }
        }
        return false
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        refreshActiveNotifications()
    }

    private fun refreshActiveNotifications() {
        try {
            recordingKeys.clear()
            nativeActivityKeys.clear()
            activeNotifications?.forEach { sbn ->
                if (sbn.packageName != packageName) updateKey(sbn)
            }
            notifyStates()
        } catch (_: Exception) { }
    }

    companion object {
        @Volatile private var listenerInstance: IslandNotificationListener? = null
        private val recordingKeys = ConcurrentHashMap.newKeySet<String>()
        private val nativeActivityKeys = ConcurrentHashMap.newKeySet<String>()

        fun refreshFromServiceStart() {
            listenerInstance?.let { listener ->
                try { listener.refreshActiveNotifications() } catch (_: Exception) { }
            }
        }

        fun requestStopRecording(): Boolean {
            val listener = listenerInstance
            val notificationStopped = try { listener?.sendStopRecordingAction() == true } catch (_: Exception) { false }
            if (notificationStopped) return true
            return try { HyperIslandVisibilityService.requestStopRecordingFromAccessibility() } catch (_: Exception) { false }
        }
    }
}
