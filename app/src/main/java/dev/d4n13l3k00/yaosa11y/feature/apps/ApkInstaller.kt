package dev.d4n13l3k00.yaosa11y.feature.apps

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import dev.d4n13l3k00.yaosa11y.core.adb.AdbGateway
import dev.d4n13l3k00.yaosa11y.core.adb.ShellPolicy
import dev.d4n13l3k00.yaosa11y.core.privilege.PrivilegeManager
import dev.d4n13l3k00.yaosa11y.core.privilege.RootBackend
import java.io.File

class ApkInstaller(
    context: Context,
    private val gateway: AdbGateway,
    private val privilegeManager: PrivilegeManager,
) {
    private val appContext = context.applicationContext
    private val cacheDirectory = appContext.cacheDir
    private val packageInstaller = appContext.packageManager.packageInstaller

    fun install(file: File): String {
        check(file.isFile && file.length() > 0) { "APK не найден или пуст" }
        check(file.length() <= ApkDownloader.MAX_APK_BYTES) {
            "APK больше допустимого размера"
        }
        val silent = runCatching { installSilently(file) }
        if (silent.isSuccess) {
            if (file.parentFile == cacheDirectory) file.delete()
            return silent.getOrThrow()
        }
        val fallbackReason = silent.exceptionOrNull()?.message
            ?.takeIf { it.isNotBlank() }
            ?: "привилегированный бэкенд недоступен"
        val result = installThroughPackageInstaller(file)
        return "Silent-установка недоступна ($fallbackReason). $result"
    }

    private fun installSilently(file: File): String {
        val ordinaryAdb = runCatching {
            installThroughAdb(file, root = null)
            "APK установлен без подтверждения через локальный ADB"
        }
        if (ordinaryAdb.isSuccess) return ordinaryAdb.getOrThrow()

        val adbReady = privilegeManager.ensureAdb()
        if (adbReady.success) {
            val enabledAdb = runCatching {
                installThroughAdb(file, root = null)
                "APK установлен без подтверждения через локальный ADB"
            }
            if (enabledAdb.isSuccess) return enabledAdb.getOrThrow()
        }

        val root = privilegeManager.ensureRootBackend(allowAdbRestart = false)
        check(root.success && root.rootBackend != null) {
            "${ordinaryAdb.exceptionOrNull()?.message}; ${adbReady.message}; ${root.message}"
        }
        val backend = requireNotNull(root.rootBackend)
        if (backend.requiresAdb) {
            installThroughAdb(file, root = backend)
        } else {
            installThroughDirectRoot(file, backend)
        }
        return "APK установлен без подтверждения через ${backend.displayName}"
    }

    private fun installThroughDirectRoot(file: File, backend: RootBackend) {
        val path = ShellPolicy.quote(file.absolutePath)
        val output = privilegeManager.shellAsRoot(
            backend,
            "pm install -r -d --user 0 $path",
        )
        check("Success" in output) { "Package Manager: ${output.trim()}" }
    }

    private fun installThroughAdb(
        file: File,
        root: dev.d4n13l3k00.yaosa11y.core.privilege.RootBackend?,
    ) {
        gateway.withConnection(socketTimeout = INSTALL_TIMEOUT_MILLIS) { adb ->
            val remote = "/data/local/tmp/yaos-upload-${System.currentTimeMillis()}.apk"
            try {
                gateway.push(adb, file, remote, MODE_FILE)
                val command = "pm install -r -d --user 0 $remote"
                val output = if (root == null) {
                    gateway.shell(adb, command)
                } else {
                    privilegeManager.shellAsRoot(adb, root, command)
                }
                check("Success" in output) { "Package Manager: ${output.trim()}" }
            } finally {
                runCatching { gateway.shell(adb, "rm -f $remote") }
            }
        }
    }

    private fun installThroughPackageInstaller(file: File): String {
        val archive = appContext.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
            ?: error("Файл не распознан как APK")
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            .apply {
                setAppPackageName(archive.packageName)
                setSize(file.length())
            }
        val sessionId = packageInstaller.createSession(params)
        var committed = false
        try {
            packageInstaller.openSession(sessionId).use { session ->
                file.inputStream().buffered().use { input ->
                    session.openWrite(file.name, 0, file.length()).use { output ->
                        input.copyTo(output, 64 * 1024)
                        session.fsync(output)
                    }
                }
                session.commit(callbackIntent(sessionId).intentSender)
                committed = true
            }
        } finally {
            if (!committed) {
                runCatching { packageInstaller.abandonSession(sessionId) }
            }
            if (file.parentFile == cacheDirectory) file.delete()
        }
        return "APK передан системному установщику — подтвердите установку на телевизоре"
    }

    private fun callbackIntent(sessionId: Int): PendingIntent {
        val intent = Intent(appContext, ApkInstallReceiver::class.java)
            .setAction("${appContext.packageName}.APK_INSTALL_RESULT")
            .putExtra(PackageInstaller.EXTRA_SESSION_ID, sessionId)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE
            } else {
                0
            }
        return PendingIntent.getBroadcast(appContext, sessionId, intent, flags)
    }

    companion object {
        private const val MODE_FILE = 420
        private const val INSTALL_TIMEOUT_MILLIS = 180_000
    }
}
