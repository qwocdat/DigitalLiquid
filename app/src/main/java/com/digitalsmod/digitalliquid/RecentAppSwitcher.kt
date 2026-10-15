package com.digitalsmod.digitalliquid

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Process
import android.os.SystemClock
import java.util.concurrent.Executors

/** Keeps a cached list of launchable apps ordered by recent use. Package queries run off the UI thread. */
object RecentAppSwitcher {
    private data class Candidate(val packageName: String, val launchIntent: Intent, val lastUsed: Long)
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "DigitalLiquid-AppSwitcher").apply { isDaemon = true }
    }
    private val lock = Any()
    @Volatile private var candidates: List<Candidate> = emptyList()
    @Volatile private var cacheUpdatedAt = 0L
    @Volatile private var refreshInFlight = false
    private var currentIndex = -1
    @Volatile private var lastLaunchAt = 0L

    fun warmUp(context: Context, force: Boolean = false) {
        val appContext = context.applicationContext
        if (!hasUsageAccess(appContext)) return
        val now = SystemClock.elapsedRealtime()
        synchronized(lock) {
            if (refreshInFlight || (!force && candidates.isNotEmpty() && now - cacheUpdatedAt < CACHE_TTL_MS)) return
            refreshInFlight = true
        }
        worker.execute {
            val fresh = buildCandidates(appContext)
            synchronized(lock) {
                if (fresh.isNotEmpty()) {
                    candidates = fresh
                    // UsageStats is descending: position 0 is presumed to be the foreground app.
                    currentIndex = 0
                    cacheUpdatedAt = SystemClock.elapsedRealtime()
                } else if (candidates.isEmpty()) {
                    cacheUpdatedAt = SystemClock.elapsedRealtime()
                }
                refreshInFlight = false
            }
        }
    }

    /** Advances to the next app in the cached recency order. This method never scans PackageManager on the touch thread. */
    fun switchNext(context: Context) {
        // Ignore only accidental same-frame duplicate events. There is no delayed launch queue;
        // accepted taps still launch immediately and package scans stay on the worker thread.
        val now = SystemClock.elapsedRealtime()
        synchronized(lock) {
            if (now - lastLaunchAt < MIN_SWITCH_INTERVAL_MS) return
            lastLaunchAt = now
        }
        if (!hasUsageAccess(context)) {
            if (!HyperIslandVisibilityService.requestRecents()) HyperIslandVisibilityService.requestHome()
            return
        }

        warmUp(context)
        val selected = synchronized(lock) {
            val snapshot = candidates
            if (snapshot.isEmpty()) null else {
                currentIndex = (currentIndex + 1) % snapshot.size
                Intent(snapshot[currentIndex].launchIntent)
            }
        }
        if (selected == null) {
            if (!HyperIslandVisibilityService.requestRecents()) HyperIslandVisibilityService.requestHome()
            return
        }
        selected.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        try {
            context.startActivity(selected)
        } catch (_: Exception) {
            if (!HyperIslandVisibilityService.requestRecents()) HyperIslandVisibilityService.requestHome()
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
    } catch (_: Exception) { false }

    private fun buildCandidates(context: Context): List<Candidate> {
        val usageManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return emptyList()
        val now = System.currentTimeMillis()
        val stats = try {
            usageManager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 14L * 24 * 60 * 60 * 1000, now)
                .asSequence()
                .filter { it.packageName != context.packageName && it.lastTimeUsed > 0L }
                .sortedByDescending { it.lastTimeUsed }
                .distinctBy { it.packageName }
                .toList()
        } catch (_: Exception) { emptyList() }
        if (stats.isEmpty()) return emptyList()

        val excludedPackages = setOf(
            context.packageName,
            "com.android.systemui", "com.miui.systemui", "com.android.launcher",
            "com.android.launcher3", "com.miui.home", "com.google.android.apps.nexuslauncher"
        )
        val output = ArrayList<Candidate>()
        val seen = HashSet<String>()
        for (stat in stats) {
            val packageName = stat.packageName
            if (packageName in excludedPackages || packageName.startsWith("com.android.systemui.")) continue
            if (!seen.add(packageName)) continue
            val launchIntent = try { context.packageManager.getLaunchIntentForPackage(packageName) } catch (_: Exception) { null } ?: continue
            output.add(Candidate(packageName, launchIntent, stat.lastTimeUsed))
        }
        return output
    }

    private const val CACHE_TTL_MS = 45_000L
    private const val MIN_SWITCH_INTERVAL_MS = 180L
}
