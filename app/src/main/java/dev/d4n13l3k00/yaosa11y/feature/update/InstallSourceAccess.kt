package dev.d4n13l3k00.yaosa11y.feature.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import dev.d4n13l3k00.yaosa11y.core.adb.AdbGateway
import dev.d4n13l3k00.yaosa11y.core.adb.ShellPolicy
import dev.d4n13l3k00.yaosa11y.core.privilege.PrivilegeManager

class InstallSourceAccess(context: Context) {
    data class Result(
        val allowed: Boolean,
        val message: String,
    )

    private val appContext = context.applicationContext

    fun isAllowed(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            appContext.packageManager.canRequestPackageInstalls()

    fun tryGrantThroughAvailableBackend(): Result {
        if (isAllowed()) return Result(true, "Установка из этого источника уже разрешена")

        val gateway = AdbGateway()
        val privilege = PrivilegeManager(appContext, gateway)
        val adb = privilege.ensureAdb()
        if (!adb.success) {
            return Result(false, "Нужно разрешение Android на установку из этого источника")
        }

        val packageName = ShellPolicy.requirePackageName(appContext.packageName)
        val command = "cmd appops set $packageName REQUEST_INSTALL_PACKAGES allow"
        val ordinary = runCatching {
            privilege.withAdb { connection -> gateway.shell(connection, command) }
            waitUntilAllowed()
        }.getOrDefault(false)
        if (ordinary) {
            return Result(true, "Разрешение на установку выдано через ADB shell")
        }

        val root = privilege.ensureRootBackend(allowAdbRestart = false)
        val rootGranted = if (root.success && root.rootBackend != null) {
            runCatching {
                privilege.withAdb { connection ->
                    privilege.shellAsRoot(connection, root.rootBackend, command)
                }
                waitUntilAllowed()
            }.getOrDefault(false)
        } else {
            false
        }
        return if (rootGranted) {
            Result(true, "Разрешение на установку выдано через ${root.rootBackend?.displayName}")
        } else {
            Result(false, "Android требует один раз разрешить установку из этого источника")
        }
    }

    fun settingsIntent(): Intent {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val packageUri = Uri.parse("package:${appContext.packageName}")
            val primary = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, packageUri)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (primary.resolveActivity(appContext.packageManager) != null) return primary
        }
        return Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun waitUntilAllowed(): Boolean {
        repeat(10) {
            if (isAllowed()) return true
            Thread.sleep(100)
        }
        return isAllowed()
    }
}
