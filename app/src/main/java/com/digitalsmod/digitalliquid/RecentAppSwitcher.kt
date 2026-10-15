package com.digitalsmod.digitalliquid

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Process
import android.provider.Settings
import android.widget.Toast
import kotlin.random.Random

/**
 * Optional rapid-tap app switcher. Uses normal launch intents so launched apps keep their
 * own Android task/Recents entry. Access to recent-usage ordering is granted by the user.
 */
object RecentAppSwitcher {
    fun switch(context: Context, tapsBeyondThreshold: Int) {
        if (!hasUsageAccess(context)) {
            val recentsOpened = HyperIslandVisibilityService.requestRecents()
            if (!recentsOpened) HyperIslandVisibilityService.requestHome()
            LiquidNotice.makeText(
                context,
                "Để chuyển app gần đây tự động, hãy cấp quyền Truy cập mức sử dụng ứng dụng trong DigitalLiquid → Cài đặt.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val intent = if (tapsBeyondThreshold == 0) mostRecentlyUsedLaunchIntent(context) else randomLaunchIntent(context)
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
        val mode = if (android.os.Build.VERSION.SDK_INT >= 29) {
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
        val candidates = stats.asSequence()
            .filter { it.packageName != context.packageName && it.lastTimeUsed > 0L }
            .sortedByDescending { it.lastTimeUsed }
        for (item in candidates) {
            val launch = context.packageManager.getLaunchIntentForPackage(item.packageName) ?: continue
            if (item.packageName.startsWith("com.android.systemui") || item.packageName == "com.android.launcher") continue
            return launch
        }
        return null
    }

    private fun randomLaunchIntent(context: Context): Intent? {
        val pm = context.packageManager
        val launchQuery = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val activities = try {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(launchQuery, 0)
        } catch (_: Exception) {
            emptyList()
        }
        val excluded = setOf(context.packageName, "com.android.systemui", "com.android.settings")
        val candidates = activities.mapNotNull { resolve ->
            val packageName = resolve.activityInfo?.packageName ?: return@mapNotNull null
            if (packageName in excluded || !resolve.activityInfo.enabled) return@mapNotNull null
            pm.getLaunchIntentForPackage(packageName)
        }.distinctBy { it.component?.packageName ?: it.`package` }
        if (candidates.isEmpty()) return mostRecentlyUsedLaunchIntent(context)
        val prefs = AppPrefs.get(context)
        val last = prefs.getString(AppPrefs.APP_SWITCH_LAST_PACKAGE, null)
        val different = candidates.filter { (it.component?.packageName ?: it.`package`) != last }
        val selected = (if (different.isNotEmpty()) different else candidates)[Random.nextInt((if (different.isNotEmpty()) different else candidates).size)]
        val selectedPackage = selected.component?.packageName ?: selected.`package`
        if (!selectedPackage.isNullOrBlank()) prefs.edit().putString(AppPrefs.APP_SWITCH_LAST_PACKAGE, selectedPackage).apply()
        return selected
    }
}
