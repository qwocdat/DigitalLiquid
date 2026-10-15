package com.digitalsmod.digitalliquid

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process

/**
 * Switches to the most recently used launchable app other than the app currently in front.
 * App Usage Access is granted by the user in Android settings.
 */
object RecentAppSwitcher {
    fun switch(context: Context) {
        if (!hasUsageAccess(context)) {
            val recentsOpened = HyperIslandVisibilityService.requestRecents()
            if (!recentsOpened) HyperIslandVisibilityService.requestHome()
            LiquidNotice.makeText(
                context,
                "Để chuyển ứng dụng gần đây, hãy cấp quyền Truy cập mức sử dụng ứng dụng trong DigitalLiquid → Cài đặt.",
                android.widget.Toast.LENGTH_LONG
            ).show()
            return
        }

        val intent = mostRecentlyUsedLaunchIntent(context)
        if (intent == null) {
            HyperIslandVisibilityService.requestRecents()
            return
        }
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            context.startActivity(intent)
        } catch (_: Exception) {
            HyperIslandVisibilityService.requestRecents()
        }
    }

    fun hasUsageAccess(context: Context): Boolean = try {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
        mode == AppOpsManager.MODE_ALLOWED
    } catch (_: Exception) {
        false
    }

    private fun mostRecentlyUsedLaunchIntent(context: Context): Intent? {
        val usageManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return null
        val now = System.currentTimeMillis()
        val stats = try {
            usageManager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 7L * 24 * 60 * 60 * 1000, now)
        } catch (_: Exception) {
            emptyList()
        }

        // The old implementation picked the latest app but did not exclude the app already
        // on screen, so tapping six times could launch the same game again and appear broken.
        val systemPackages = setOf(
            "com.android.systemui", "com.miui.systemui", "com.android.settings",
            "com.android.launcher", "com.android.launcher3", "com.miui.home"
        )
        val foregroundPackage = try {
            val events = usageManager.queryEvents(now - 24L * 60 * 60 * 1000, now)
            val event = UsageEvents.Event()
            var current: String? = null
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                val isForegroundEvent = event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND ||
                    (Build.VERSION.SDK_INT >= 29 && event.eventType == UsageEvents.Event.ACTIVITY_RESUMED)
                // Ignore system overlays so they do not hide the real foreground app from the switcher.
                if (isForegroundEvent && event.packageName !in systemPackages) current = event.packageName
            }
            current
        } catch (_: Exception) {
            null
        }

        val excludedPackages = systemPackages + context.packageName
        val candidates = stats.asSequence()
            .filter { it.packageName !in excludedPackages && it.lastTimeUsed > 0L }
            .sortedByDescending { it.lastTimeUsed }
            .toList()
        // If OEM usage events omit the foreground activity, assume the newest launchable
        // package is the one already open, rather than repeatedly reopening it.
        val packageToSkip = foregroundPackage ?: candidates.firstOrNull()?.packageName

        for (item in candidates) {
            if (item.packageName == packageToSkip) continue
            val launch = try {
                context.packageManager.getLaunchIntentForPackage(item.packageName)
            } catch (_: Exception) {
                null
            } ?: continue
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            return launch
        }
        return null
    }
}
