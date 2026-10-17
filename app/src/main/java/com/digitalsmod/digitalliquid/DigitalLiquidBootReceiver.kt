package com.digitalsmod.digitalliquid

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings

/** Restores the opted-in overlay only after re-validating a key that the user chose to save. */
class DigitalLiquidBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (!NativeIntegrity.isTrusted(context)) return
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED && intent?.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val prefs = AppPrefs.get(context)
        if (!prefs.getBoolean(AppPrefs.AUTO_START, false) || !prefs.getBoolean(AppPrefs.SERVICE_ENABLED, false)) return

        val enabledAccessibility = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        val component = ComponentName(context, HyperIslandVisibilityService::class.java).flattenToString()
        val accessibilityEnabled = Settings.Secure.getInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1 &&
            enabledAccessibility.split(':').any { it.equals(component, ignoreCase = true) }
        if (!accessibilityEnabled && Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(context)) return

        val pending = goAsync()
        OnlineControlManager.fetch(context) { control, _ ->
            val localVersion = runCatching {
                val info = context.packageManager.getPackageInfo(context.packageName, 0)
                if (Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt() else legacyVersionCode(info)
            }.getOrDefault(0)
            val canStart = control == null || (!control.blocksUse(localVersion) && !control.maintenanceEnabled)
            if (canStart) {
                try {
                    val serviceIntent = Intent(context, IslandService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(serviceIntent) else context.startService(serviceIntent)
                } catch (_: Exception) { }
            }
            pending.finish()
        }
    }

    @Suppress("DEPRECATION")
    private fun legacyVersionCode(info: android.content.pm.PackageInfo): Int = info.versionCode
}
