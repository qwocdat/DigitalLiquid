package com.digitalsmod.digitalliquid

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** Only watches for an active screen-recording notification. All other system activities are ignored. */
class IslandNotificationListener : NotificationListenerService() {
    override fun onCreate() {
        super.onCreate()
        listenerInstance = this
    }

    override fun onDestroy() {
        if (listenerInstance === this) listenerInstance = null
        recordingKeys.clear()
        IslandService.setScreenRecordingActive(false)
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        val isRecording = isActiveScreenRecordingNotification(sbn)
        if (isRecording) recordingKeys.add(sbn.key) else recordingKeys.remove(sbn.key)
        IslandService.setScreenRecordingActive(recordingKeys.isNotEmpty())
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        recordingKeys.remove(sbn.key)
        IslandService.setScreenRecordingActive(recordingKeys.isNotEmpty())
    }

    private fun isActiveScreenRecordingNotification(sbn: StatusBarNotification): Boolean {
        val notification = sbn.notification ?: return false
        val extras = notification.extras ?: return false
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty()
        val combined = listOf(title, text, bigText, subText).joinToString(" ").lowercase(Locale.ROOT)
        val pkg = sbn.packageName.lowercase(Locale.ROOT)

        // Explicitly exclude audio/call recording: only display capture gets an island effect.
        val audioRecordingWords = listOf("call recording", "voice recording", "audio recording", "ghi âm cuộc gọi", "ghi âm giọng nói")
        if (audioRecordingWords.any { combined.contains(it) }) return false

        val screenRecordingWords = listOf(
            "screen recording", "screen recorder", "recording screen", "record your screen",
            "đang quay màn hình", "quay màn hình", "ghi màn hình", "trình ghi màn hình",
            "screen is being recorded", "screen capture recording", "录屏", "屏幕录制", "正在录屏"
        )
        val knownRecorderPackage = pkg.contains("screenrecorder") || pkg.contains("screen_recorder") ||
            pkg.contains("screenrecord") || pkg.contains("screen_record")
        val mentionsScreenRecording = screenRecordingWords.any { combined.contains(it) }
        val ongoing = sbn.isOngoing ||
            (notification.flags and Notification.FLAG_ONGOING_EVENT) != 0 ||
            (notification.flags and Notification.FLAG_NO_CLEAR) != 0

        // HyperOS versions may publish the status notification under System UI instead of recorder app.
        return ongoing && (mentionsScreenRecording || (knownRecorderPackage &&
            listOf("recording", "đang quay", "录制", "录屏").any { combined.contains(it) }))
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        refreshActiveNotifications()
    }

    private fun refreshActiveNotifications() {
        try {
            activeNotifications?.forEach { sbn ->
                if (isActiveScreenRecordingNotification(sbn)) recordingKeys.add(sbn.key)
            }
            IslandService.setScreenRecordingActive(recordingKeys.isNotEmpty())
        } catch (_: Exception) { }
    }

    companion object {
        @Volatile private var listenerInstance: IslandNotificationListener? = null
        private val recordingKeys = ConcurrentHashMap.newKeySet<String>()

        fun refreshFromServiceStart() {
            listenerInstance?.let { listener ->
                try { listener.refreshActiveNotifications() } catch (_: Exception) { }
            }
        }
    }
}
