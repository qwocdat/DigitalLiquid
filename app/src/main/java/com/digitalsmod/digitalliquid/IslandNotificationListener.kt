package com.digitalsmod.digitalliquid

import android.app.Notification
import android.content.Intent
import android.media.session.MediaController
import android.media.session.MediaSession
import android.os.Build
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
        val notification = sbn.notification
        val extras = notification.extras
        val isMedia = notification.category == Notification.CATEGORY_TRANSPORT ||
            extras.containsKey(Notification.EXTRA_MEDIA_SESSION)
        IslandService.clearNotification(sbn.key, isMedia)
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
