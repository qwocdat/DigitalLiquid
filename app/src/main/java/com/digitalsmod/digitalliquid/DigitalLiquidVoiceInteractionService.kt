package com.digitalsmod.digitalliquid

import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.util.Log

/** System-owned entry point for the real Android/HyperOS assistant gesture. */
class DigitalLiquidVoiceInteractionService : VoiceInteractionService() {
    @Volatile
    internal var sessionReady = false
        private set

    @Volatile
    private var destroyed = false

    override fun onCreate() {
        super.onCreate()
        destroyed = false
        activeInstance = this
        Log.i(TAG, "VoiceInteractionService created")
    }

    override fun onReady() {
        super.onReady()
        // The framework documents that onReady can arrive after onDestroy during teardown.
        if (destroyed) {
            sessionReady = false
            if (activeInstance === this) activeInstance = null
            Log.w(TAG, "Ignoring late onReady callback for a destroyed service instance")
            return
        }
        sessionReady = true
        activeInstance = this
        Log.i(TAG, "VoiceInteractionService ready; session launch is available")
    }

    override fun onShowSessionFailed(args: Bundle) {
        super.onShowSessionFailed(args)
        val requestId = args.getString(DigitalLiquidAssistantCommandReceiver.EXTRA_REQUEST_ID)
        Log.e(TAG, "System failed to show the DigitalLiquid voice session; requestId=${requestId ?: "system"}")
        if (!DigitalLiquidAssistantCommandReceiver.failPendingSession(requestId)) {
            // System gestures have no app-generated request token, so there is no watchdog to resolve.
            if (requestId.isNullOrBlank()) DigitalLiquidAssistantLauncher.openFallback(this)
        }
    }

    override fun onShutdown() {
        sessionReady = false
        if (activeInstance === this) activeInstance = null
        Log.i(TAG, "VoiceInteractionService shutdown")
        super.onShutdown()
    }

    override fun onDestroy() {
        destroyed = true
        sessionReady = false
        if (activeInstance === this) activeInstance = null
        super.onDestroy()
    }

    companion object {
        @Volatile
        internal var activeInstance: DigitalLiquidVoiceInteractionService? = null

        private const val TAG = "DigitalLiquidSiri"
    }
}
