package dev.d4n13l3k00.yaosa11y.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import dev.d4n13l3k00.yaosa11y.feature.accessibility.AccessibilityGuardService
import dev.d4n13l3k00.yaosa11y.feature.accessibility.RootHookManager
import dev.d4n13l3k00.yaosa11y.feature.update.UpdateStateStore

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (
            intent.action !in setOf(
                Intent.ACTION_BOOT_COMPLETED,
                Intent.ACTION_MY_PACKAGE_REPLACED,
                Intent.ACTION_USER_UNLOCKED,
            )
        ) {
            return
        }
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            UpdateStateStore(context).markPackageReplaced(installedVersionName(context))
        }
        if (!RootHookManager(context).shouldBeEnabled()) return

        val service = Intent(context, AccessibilityGuardService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(service)
        } else {
            context.startService(service)
        }
    }

    @Suppress("DEPRECATION")
    private fun installedVersionName(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
}
