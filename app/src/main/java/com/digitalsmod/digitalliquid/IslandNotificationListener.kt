package com.digitalsmod.digitalliquid

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class IslandNotificationListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: return
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val isMedia = sbn.notification.category == Notification.CATEGORY_TRANSPORT ||
            extras.containsKey(Notification.EXTRA_MEDIA_SESSION)

        IslandService.updateNotification(
            title = title,
            text = text,
            isMedia = isMedia
        )
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        IslandService.clearNotification()
    }
}
