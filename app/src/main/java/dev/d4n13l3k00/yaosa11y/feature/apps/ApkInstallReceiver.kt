package dev.d4n13l3k00.yaosa11y.feature.apps

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build

class ApkInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(
            PackageInstaller.EXTRA_STATUS,
            PackageInstaller.STATUS_FAILURE,
        )
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                notifyStatus(context, "Ожидание подтверждения в системном установщике…")
                val confirmation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (confirmation != null) {
                    confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(confirmation) }
                        .onFailure {
                            notifyStatus(
                                context,
                                "Не удалось открыть системный установщик: ${it.message}",
                            )
                        }
                } else {
                    notifyStatus(context, "Android не вернул экран подтверждения установки")
                }
            }

            PackageInstaller.STATUS_SUCCESS ->
                notifyStatus(context, "APK успешно установлен")

            else -> {
                val details = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                    ?.takeIf { it.isNotBlank() }
                    ?: "код $status"
                notifyStatus(context, "Установка не завершена: $details")
            }
        }
    }

    private fun notifyStatus(context: Context, message: String) {
        context.sendBroadcast(
            Intent(ACTION_STATUS)
                .setPackage(context.packageName)
                .putExtra(EXTRA_MESSAGE, message),
        )
    }

    companion object {
        const val ACTION_STATUS = "dev.d4n13l3k00.yaosa11y.APK_INSTALL_STATUS"
        const val EXTRA_MESSAGE = "message"
    }
}
