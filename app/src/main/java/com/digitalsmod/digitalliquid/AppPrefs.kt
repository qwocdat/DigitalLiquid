package com.digitalsmod.digitalliquid

import android.content.Context
import android.content.SharedPreferences

/** Central preference keys shared by MainActivity and IslandService. */
object AppPrefs {
    private const val FILE_NAME = "digitalliquid_preferences"

    const val ONBOARDING_DONE = "onboarding_done"
    const val SERVICE_ENABLED = "service_enabled"
    const val WIDTH = "island_width_dp"
    const val HEIGHT = "island_height_dp"
    const val ISLAND_REFRESH_RATE = "island_refresh_rate_fps"
    const val OFFSET_TOP = "island_top_offset_dp"
    const val ALIGNMENT = "island_alignment"
    const val GLASS_STRENGTH = "glass_strength"
    const val TINT = "glass_tint"
    const val REDUCE_MOTION = "reduce_motion"
    const val SCREEN_RECORDING_EFFECT = "screen_recording_effect"
    const val FACE_UNLOCK_EFFECT = "face_unlock_effect"
    const val TAP_WOBBLE = "tap_wobble"
    const val MOTION_BLUR = "motion_blur"
    const val RECORDING_STOP_CONTROL = "recording_stop_control" // Kept for settings migration; now controls the Recording label.
    const val AUTO_START = "auto_start_after_boot"
    const val LAST_ANNOUNCEMENT_ID = "last_remote_announcement_id"
    const val AUTO_HIDE_SYSTEM_ISLAND = "auto_hide_system_island"
    const val HIDE_WHEN_MEDIA_PLAYING = "hide_when_media_playing"
    const val HIDE_WHEN_NATIVE_ACTIVITY = "hide_when_native_activity"
    const val HIDE_WHEN_RINGER_UI = "hide_when_ringer_ui"
    const val DOUBLE_TAP_ASSISTANT = "double_tap_assistant"
    const val ISLAND_APP_SWITCH_GESTURE = "island_app_switch_gesture"
    const val CUSTOM_APP_NOTICES = "custom_app_notices"
    const val AI_MODEL = "openrouter_model"
    const val AI_ENDPOINT = "openrouter_endpoint"
    const val LIGHT_MODE = "light_mode_enabled"
    const val LIVE_PREVIEW_INTERACTIVE = "live_preview_interactive"
    const val PANEL_BACKGROUND_KIND = "custom_panel_background_kind"
    const val APP_SWITCH_LAST_PACKAGE = "app_switch_last_package"
    const val DISMISSED_UPDATE_CODE = "dismissed_update_code"

    fun get(context: Context): SharedPreferences =
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
}
