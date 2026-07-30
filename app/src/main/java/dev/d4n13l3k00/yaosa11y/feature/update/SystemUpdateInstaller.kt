package dev.d4n13l3k00.yaosa11y.feature.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build

class SystemUpdateInstaller(context: Context) {
    private val appContext = context.applicationContext
    private val packageInstaller = appContext.packageManager.packageInstaller

    fun install(update: VerifiedUpdate) {
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            .apply {
                setAppPackageName(appContext.packageName)
                setSize(update.file.length())
            }
        val sessionId = packageInstaller.createSession(params)
        var committed = false
        try {
            packageInstaller.openSession(sessionId).use { session ->
                update.file.inputStream().buffered().use { input ->
                    session.openWrite(update.file.name, 0, update.file.length()).use { output ->
                        input.copyTo(output, 64 * 1024)
                        session.fsync(output)
                    }
                }
                UpdateStateStore(appContext).markInstallPending(update.versionName)
                session.commit(callbackIntent(sessionId).intentSender)
                committed = true
            }
        } finally {
            if (!committed) {
                runCatching { packageInstaller.abandonSession(sessionId) }
                UpdateStateStore(appContext).clearPendingInstall()
            }
            update.file.delete()
        }
    }

    private fun callbackIntent(sessionId: Int): PendingIntent {
        val intent = Intent(appContext, UpdateInstallReceiver::class.java)
            .setAction("${appContext.packageName}.UPDATE_INSTALL_RESULT")
            .putExtra(PackageInstaller.EXTRA_SESSION_ID, sessionId)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE
            } else {
                0
            }
        return PendingIntent.getBroadcast(appContext, sessionId, intent, flags)
    }
}
