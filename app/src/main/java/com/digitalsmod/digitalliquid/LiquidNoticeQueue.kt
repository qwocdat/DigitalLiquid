package com.digitalsmod.digitalliquid

import android.content.Context

/** Queues app-owned announcements when the floating app surface is unavailable. */
object LiquidNoticeQueue {
    data class Pending(
        val id: String,
        val title: String,
        val message: String,
        val url: String
    )

    private const val FILE = "digitalliquid_remote_notices"
    private const val LAST_ID = "last_announcement_id"
    private const val PENDING_ID = "pending_notice_id"
    private const val PENDING_TITLE = "pending_notice_title"
    private const val PENDING_MESSAGE = "pending_notice_message"
    private const val PENDING_URL = "pending_notice_url"

    private fun preferences(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun wasHandled(context: Context, id: String): Boolean = preferences(context).getString(LAST_ID, "") == id

    fun markHandled(context: Context, id: String) {
        preferences(context).edit().putString(LAST_ID, id).apply()
        AppPrefs.get(context).edit().putString(AppPrefs.LAST_ANNOUNCEMENT_ID, id).apply()
    }

    fun enqueue(context: Context, id: String, title: String, message: String, url: String) {
        preferences(context).edit()
            .putString(LAST_ID, id)
            .putString(PENDING_ID, id)
            .putString(PENDING_TITLE, title)
            .putString(PENDING_MESSAGE, message)
            .putString(PENDING_URL, url)
            .apply()
        // Keep the Activity's online-control checker from displaying the same announcement twice.
        AppPrefs.get(context).edit().putString(AppPrefs.LAST_ANNOUNCEMENT_ID, id).apply()
    }

    fun pending(context: Context): Pending? {
        val prefs = preferences(context)
        val id = prefs.getString(PENDING_ID, "").orEmpty()
        val message = prefs.getString(PENDING_MESSAGE, "").orEmpty()
        if (id.isBlank() || message.isBlank()) return null
        return Pending(
            id = id,
            title = prefs.getString(PENDING_TITLE, "").orEmpty().ifBlank { "Thông báo DigitalLiquid" },
            message = message,
            url = prefs.getString(PENDING_URL, "").orEmpty()
        )
    }

    fun clear(context: Context, id: String) {
        val prefs = preferences(context)
        if (prefs.getString(PENDING_ID, "") != id) return
        prefs.edit()
            .remove(PENDING_ID)
            .remove(PENDING_TITLE)
            .remove(PENDING_MESSAGE)
            .remove(PENDING_URL)
            .apply()
    }
}
