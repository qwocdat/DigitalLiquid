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
        val text = notificationText(sbn)
        val pkg = sbn.packageName.lowercase(Locale.ROOT)

        val audioRecordingWords = listOf(
            "call recording", "voice recording", "audio recording", "recording audio",
            "ghi âm cuộc gọi", "ghi âm giọng nói", "ghi âm âm thanh", "đang ghi âm"
        )
        if (audioRecordingWords.any(text::contains)) return false

        val screenWords = listOf(
            "screen recording", "screen recorder", "recording screen", "record your screen",
            "screen is being recorded", "screen capture recording", "screen recording active",
            "tap to stop recording", "stop screen recording", "recording your screen",
            "đang quay màn hình", "quay màn hình", "ghi màn hình", "trình ghi màn hình",
            "đang ghi màn hình", "đang ghi lại màn hình", "ghi lại màn hình", "dừng quay màn hình",
            "录屏", "屏幕录制", "正在录屏", "正在屏幕录制"
        )
        val knownRecorderPackage = pkg.contains("screenrecorder") || pkg.contains("screen_recorder") ||
            pkg.contains("screenrecord") || pkg.contains("screen_record") ||
            pkg == "com.miui.screenrecorder" || pkg == "com.android.screenrecord"
        val ongoing = sbn.isOngoing ||
            (notification.flags and Notification.FLAG_ONGOING_EVENT) != 0 ||
            (notification.flags and Notification.FLAG_NO_CLEAR) != 0
        if (!ongoing) return false
        if (screenWords.any(text::contains)) return true
        // Xiaomi commonly posts the recorder notification from its dedicated recorder package.
        return knownRecorderPackage && !audioRecordingWords.any(text::contains)
    }


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

        // Prefer the actual MediaSession state so a paused media notification does not
        // keep the DigitalLiquid pill hidden indefinitely. Some vendor ROMs omit the token,
        // so retain an ongoing-notification fallback in that case.
        val hasMediaSession = n.extras?.containsKey(Notification.EXTRA_MEDIA_SESSION) == true ||
            n.category == Notification.CATEGORY_TRANSPORT
        if (hasMediaSession && ongoing) {
            val playbackState = readMediaPlaybackState(sbn)
            if (playbackState != null) return playbackState == PlaybackState.STATE_PLAYING
            return true
        }

        // Clock variants used by Android, Google and Xiaomi. Only suppress while a timer/stopwatch is live.
        val clockPackage = pkg.contains("deskclock") || pkg.contains("miui.clock") ||
            pkg.contains("google.android.deskclock") || pkg.endsWith(".clock")
        val clockWords = listOf("timer", "stopwatch", "countdown", "time remaining", "chronometer", "đồng hồ", "hẹn giờ", "bấm giờ", "đếm ngược", "剩余时间", "计时")
        val hasChronometer = n.extras?.getBoolean("android.showChronometer", false) == true ||
            n.extras?.getBoolean("android.chronometerCountDown", false) == true
        if (clockPackage && ongoing && (hasChronometer || clockWords.any(text::contains))) return true

        // Calls and audio-recording notifications belong to the native system interface as well.
        if (n.category == Notification.CATEGORY_CALL && ongoing) return true
        val audioRecordWords = listOf("voice recorder", "audio recorder", "recording audio", "audio recording", "ghi âm", "录音")
        val knownAudioRecorder = pkg.contains("soundrecorder") || pkg.contains("audio.recorder") || pkg.contains("voicerecorder")
        if (ongoing && !isActiveScreenRecordingNotification(sbn) &&
            (audioRecordWords.any(text::contains) || knownAudioRecorder)) return true

        // Notifications from HyperOS recorder/call/media apps that mention their current active session.
        val activeWords = listOf("playing in background", "phát nền", "đang phát", "正在播放")
        return ongoing && activeWords.any(text::contains) &&
            (pkg.contains("music") || pkg.contains("audio") || pkg.contains("media"))
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
    }
}
