package com.digitalsmod.digitalliquid

import android.content.Context

object AppPrefs {
    const val FILE = "digitalliquid_prefs"
    const val ONBOARDING_DONE = "onboarding_done"
    const val SERVICE_ENABLED = "service_enabled"
    const val WIDTH = "island_width"
    const val HEIGHT = "island_height"
    const val OFFSET_TOP = "offset_top"
    const val ALIGNMENT = "alignment"
    const val BLUR_RADIUS = "blur_radius"
    const val GLASS_STRENGTH = "glass_strength"
    const val TINT = "glass_tint"
    const val REDUCE_MOTION = "reduce_motion"
    const val SHOW_NOTIFICATIONS = "show_notifications"
    const val SHOW_MEDIA = "show_media"
    const val SHOW_CHARGING = "show_charging"
    const val SHOW_BATTERY = "show_battery"
    const val SHOW_TIMER = "show_timer"
    const val FILTER_NOTIFICATIONS = "filter_notifications"
    const val ALLOWED_NOTIFICATION_PACKAGES = "allowed_notification_packages"

    fun get(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
