package com.digitalsmod.digitalliquid

import android.app.Notification
import android.content.Intent
import android.media.session.MediaController
import android.media.session.MediaSession
import android.os.Build
import java.util.Locale
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class IslandNotificationListener : NotificationListenerService() {
    override fun onCreate() {
        super.onCreate()
        listenerInstance = this
    }

    override fun onDestroy() {
        if (listenerInstance === this) listenerInstance = null
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return

        // Android Clock/Stopwatch notifications drive the timer activity automatically.
        // The listener never starts, pauses, or resets the timer itself.
        if (isClockPackage(sbn.packageName)) {
            val clockEvent = readClockEvent(sbn)
            if (clockEvent != null) {
                IslandService.updateClockEvent(
                    key = sbn.key,
                    title = clockEvent.display,
                    text = clockEvent.detail,
                    pendingIntent = sbn.notification.contentIntent,
                    isCountdown = clockEvent.isCountdown,
                    whenMs = sbn.notification.`when`,
                    usesChronometer = clockEvent.usesChronometer
                )
                return
            }
        }

        val filterPrefs = AppPrefs.get(this)
        if (filterPrefs.getBoolean(AppPrefs.FILTER_NOTIFICATIONS, false)) {
            val allowed = filterPrefs.getStringSet(AppPrefs.ALLOWED_NOTIFICATION_PACKAGES, emptySet()).orEmpty()
            if (sbn.packageName !in allowed) return
        }
        val notification = sbn.notification
        val extras = notification.extras
        val rawTitle = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()

        var controller: MediaController? = null
        val token: MediaSession.Token? = try {
            if (Build.VERSION.SDK_INT >= 33) {
                extras.getParcelable(Notification.EXTRA_MEDIA_SESSION, MediaSession.Token::class.java)
            } else {
                @Suppress("DEPRECATION")
                (extras.getParcelable(Notification.EXTRA_MEDIA_SESSION) as? MediaSession.Token)
            }
        } catch (_: Exception) { null }
        if (token != null) {
            controller = try { MediaController(this, token) } catch (_: Exception) { null }
        }

        val isMedia = notification.category == Notification.CATEGORY_TRANSPORT ||
            token != null || extras.containsKey(Notification.EXTRA_MEDIA_SESSION)
        val title = rawTitle.ifBlank {
            if (isMedia) "Đang phát nhạc" else sbn.packageName.substringAfterLast('.')
        }
        IslandService.updateNotification(
            title = title,
            text = text,
            isMedia = isMedia,
            key = sbn.key,
            pendingIntent = notification.contentIntent,
            controller = controller,
            replyAction = notification.actions?.firstOrNull { action ->
                action.remoteInputs?.any { it.allowFreeFormInput } == true
            }
        )
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        IslandService.clearClockEvent(sbn.key)
        val notification = sbn.notification
        val extras = notification.extras
        val isMedia = notification.category == Notification.CATEGORY_TRANSPORT ||
            extras.containsKey(Notification.EXTRA_MEDIA_SESSION)
        IslandService.clearNotification(sbn.key, isMedia)
    }

    private data class ClockEvent(
        val display: String,
        val detail: String,
        val isCountdown: Boolean,
        val usesChronometer: Boolean
    )

    private fun isClockPackage(packageName: String): Boolean {
        val pkg = packageName.lowercase(Locale.ROOT)
        return pkg in setOf(
            "com.google.android.deskclock",
            "com.android.deskclock",
            "com.sec.android.app.clockpackage",
            "com.miui.clock",
            "com.coloros.alarmclock",
            "com.oplus.alarmclock",
            "com.huawei.deskclock",
            "com.asus.deskclock"
        ) || pkg.endsWith(".deskclock") || pkg.endsWith(".clockpackage")
    }

    private fun readClockEvent(sbn: StatusBarNotification): ClockEvent? {
        val notification = sbn.notification
        val extras = notification.extras ?: return null
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty()
        val combined = listOf(title, text, bigText, subText).joinToString(" ").lowercase(Locale.ROOT)
        val flags = notification.flags
        val ongoing = (flags and Notification.FLAG_ONGOING_EVENT) != 0 ||
            (flags and Notification.FLAG_NO_CLEAR) != 0
        val showChronometer = extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER, false)
        val countDownChronometer = extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN, false)
        val clockWords = listOf(
            "timer", "stopwatch", "stop watch", "stop-watch", "chronometer", "countdown",
            "time left", "remaining", "minutes left", "seconds left", "bấm giờ", "đang bấm",
            "đếm ngược", "hẹn giờ", "còn lại", "phút nữa", "giây nữa", "thời gian đã trôi"
        )
        val mentionsTimer = clockWords.any { combined.contains(it) }
        // Do not mistake a scheduled alarm or the clock app's ordinary UI notification for a timer.
        if (!ongoing || (!showChronometer && !mentionsTimer)) return null

        val isCountdown = countDownChronometer || listOf("timer", "countdown", "đếm ngược", "hẹn giờ", "còn lại", "left", "remaining")
            .any { combined.contains(it) }
        val timePattern = Regex("(?<!\\d)(?:\\d{1,2}:)?\\d{1,2}:\\d{2}(?!\\d)")
        val displayTime = timePattern.find(text)?.value
            ?: timePattern.find(bigText)?.value
            ?: timePattern.find(title)?.value
        val label = if (isCountdown) "Hẹn giờ" else "Bấm giờ"
        val display = displayTime ?: title.takeIf { it.isNotBlank() && !it.equals("Clock", true) } ?: label
        val detail = listOf(text, bigText, subText).firstOrNull { it.isNotBlank() && it != displayTime }
            ?: if (isCountdown) "Hẹn giờ đang chạy trong ứng dụng Đồng hồ" else "Bấm giờ đang chạy trong ứng dụng Đồng hồ"
        return ClockEvent(
            display = display,
            detail = detail,
            isCountdown = isCountdown,
            usesChronometer = showChronometer || countDownChronometer
        )
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        refreshActiveNotifications()
    }

    private fun refreshActiveNotifications() {
        try {
            activeNotifications?.forEach { notification -> onNotificationPosted(notification) }
        } catch (_: Exception) { }
    }

    companion object {
        @Volatile private var listenerInstance: IslandNotificationListener? = null

        fun refreshFromServiceStart() {
            listenerInstance?.let { listener ->
                try { listener.refreshActiveNotifications() } catch (_: Exception) { }
            }
        }
    }
}
