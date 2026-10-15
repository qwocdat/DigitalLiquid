package com.digitalsmod.digitalliquid

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** In-process command bridge to the system-bound VoiceInteractionService. */
class DigitalLiquidAssistantCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            ACTION_SESSION_SHOWN -> {
                completeShown(intent.getStringExtra(EXTRA_REQUEST_ID))
                return
            }
            ACTION_SHOW_SIRI -> startShowRequest(context.applicationContext)
            else -> return
        }
    }

    private fun startShowRequest(appContext: Context) {
        // HyperOS can publish the selected component before onReady has completed. Keep this
        // receiver alive briefly, and only call it a success after the session confirms onShow.
        val result: BroadcastReceiver.PendingResult? = goAsync()
        val handler = Handler(Looper.getMainLooper())
        val requestId = UUID.randomUUID().toString()
        val request = PendingShow(appContext, result, handler)
        pendingShows[requestId] = request
        val readyDeadline = SystemClock.uptimeMillis() + READY_WAIT_MS

        val readyCheck = object : Runnable {
            override fun run() {
                val service = DigitalLiquidVoiceInteractionService.activeInstance
                if (service != null && service.sessionReady) {
                    try {
                        service.showSession(Bundle().apply {
                            putString("digitalliquid.invocation_source", "dynamic_island")
                            putString(EXTRA_REQUEST_ID, requestId)
                        }, 0)
                        Log.i(TAG, "Requested VoiceInteractionSession; waiting for onShow acknowledgement")
                        val timeout = Runnable {
                            Log.w(TAG, "No session onShow acknowledgement after ${SESSION_SHOW_TIMEOUT_MS}ms; using fallback")
                            completeFallback(requestId)
                        }
                        request.timeoutRunnable = timeout
                        handler.postDelayed(timeout, SESSION_SHOW_TIMEOUT_MS)
                    } catch (error: Exception) {
                        Log.e(TAG, "showSession failed synchronously; using visual fallback", error)
                        completeFallback(requestId)
                    }
                    return
                }

                if (SystemClock.uptimeMillis() < readyDeadline) {
                    handler.postDelayed(this, RETRY_INTERVAL_MS)
                } else {
                    Log.w(TAG, "VoiceInteractionService did not become ready within ${READY_WAIT_MS}ms; using visual fallback")
                    completeFallback(requestId)
                }
            }
        }
        request.readyRunnable = readyCheck
        handler.post(readyCheck)
    }

    private class PendingShow(
        val context: Context,
        val result: BroadcastReceiver.PendingResult?,
        val handler: Handler,
        val createdAt: Long = SystemClock.uptimeMillis()
    ) {
        var readyRunnable: Runnable? = null
        var timeoutRunnable: Runnable? = null
    }

    companion object {
        const val ACTION_SHOW_SIRI = "com.digitalsmod.digitalliquid.action.SHOW_SIRI"
        const val ACTION_SESSION_SHOWN = "com.digitalsmod.digitalliquid.action.SESSION_SHOWN"
        const val EXTRA_REQUEST_ID = "digitalliquid.session_request_id"
        private const val READY_WAIT_MS = 1_800L
        private const val RETRY_INTERVAL_MS = 150L
        private const val SESSION_SHOW_TIMEOUT_MS = 4_000L
        private const val TAG = "DigitalLiquidSiri"
        private val pendingShows = ConcurrentHashMap<String, PendingShow>()

        private fun completeShown(requestId: String?) {
            if (requestId.isNullOrBlank()) return
            val request = pendingShows.remove(requestId) ?: return
            request.readyRunnable?.let { request.handler.removeCallbacks(it) }
            request.timeoutRunnable?.let { request.handler.removeCallbacks(it) }
            Log.i(TAG, "VoiceInteractionSession confirmed visible via onShow")
            runCatching { request.result?.finish() }
        }

        private fun completeFallback(requestId: String) {
            val request = pendingShows.remove(requestId) ?: return
            request.readyRunnable?.let { request.handler.removeCallbacks(it) }
            request.timeoutRunnable?.let { request.handler.removeCallbacks(it) }
            try {
                DigitalLiquidAssistantLauncher.openFallback(request.context)
            } finally {
                runCatching { request.result?.finish() }
            }
        }

        /** Used by the API 34+ framework failure callback to avoid leaving a request hanging. */
        internal fun failPendingSession(requestId: String?): Boolean {
            // The framework callback documents a session ID but ROMs may omit our custom extra.
            // In that case resolve the newest app-originated show request to avoid a second fallback.
            val resolvedId: String = (if (!requestId.isNullOrBlank()) requestId
                else pendingShows.entries.maxByOrNull { it.value.createdAt }?.key)
                ?: return false
            if (!pendingShows.containsKey(resolvedId)) return false
            completeFallback(resolvedId)
            return true
        }
    }
}
