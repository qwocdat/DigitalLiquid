package com.digitalsmod.digitalliquid

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.service.voice.VoiceInteractionService
import android.util.Log

/**
 * Single entry point used by the Dynamic Island double-tap, Assistant Studio and the
 * VoiceInteractionService failure callback.
 *
 * - If DigitalLiquid is the active system assistant, ask the command bridge to show the real
 *   VoiceInteractionSession.
 * - Otherwise (or if the bridge cannot be reached) open the in-app Siri-style fallback directly.
 */
object DigitalLiquidAssistantLauncher {
    private const val TAG = "DigitalLiquidSiri"

    /** True when the system's selected voice_interaction_service is DigitalLiquid. */
    fun isActiveAssistant(context: Context): Boolean {
        val component = ComponentName(context, DigitalLiquidVoiceInteractionService::class.java)
        return try {
            VoiceInteractionService.isActiveService(context, component)
        } catch (error: Exception) {
            Log.w(TAG, "Could not determine whether DigitalLiquid is the active assistant", error)
            false
        }
    }

    /** Request the real assistant session when active, otherwise open the visual fallback. */
    fun open(context: Context) {
        val appContext = context.applicationContext ?: context
        if (!isActiveAssistant(appContext)) {
            Log.i(TAG, "DigitalLiquid is not the active assistant; opening visual fallback")
            openFallback(appContext)
            return
        }
        try {
            appContext.sendBroadcast(
                Intent(appContext, DigitalLiquidAssistantCommandReceiver::class.java).apply {
                    action = DigitalLiquidAssistantCommandReceiver.ACTION_SHOW_SIRI
                }
            )
        } catch (error: Exception) {
            Log.e(TAG, "Could not reach the assistant command bridge; opening visual fallback", error)
            openFallback(appContext)
        }
    }

    /** Open the in-app Siri-style assistant surface. Safe to call from any Context. */
    fun openFallback(context: Context) {
        try {
            context.startActivity(
                Intent(context, AssistantFallbackActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (error: Exception) {
            Log.e(TAG, "Could not open the visual assistant fallback", error)
        }
    }
}
