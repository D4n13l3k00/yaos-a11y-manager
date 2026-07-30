package dev.d4n13l3k00.yaosa11y.feature.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build

class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(
            PackageInstaller.EXTRA_STATUS,
            PackageInstaller.STATUS_FAILURE,
        )
        val store = UpdateStateStore(context)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
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
                            store.recordInstallerResult(
                                "Не удалось открыть подтверждение Android: ${it.message}",
                            )
                        }
                } else {
                    store.recordInstallerResult("Android не вернул экран подтверждения установки")
                }
            }

            PackageInstaller.STATUS_SUCCESS -> {
                store.recordInstallerResult("Обновление установлено")
            }

            else -> {
                store.clearPendingInstall()
                val details = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                    ?.takeIf { it.isNotBlank() }
                    ?: "код $status"
                store.recordInstallerResult("Установка не завершена: $details")
            }
        }
    }
}
