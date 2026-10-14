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
    const val DISMISSED_UPDATE_CODE = "dismissed_update_code"

    fun get(context: Context): SharedPreferences =
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
}
