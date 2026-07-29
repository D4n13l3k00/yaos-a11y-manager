package dev.d4n13l3k00.yaosa11y

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import java.util.WeakHashMap

object RecoveryDialog {
    private val activeDialogs = WeakHashMap<Activity, AlertDialog>()

    fun show(
        activity: Activity,
        errorMessage: String,
        retry: () -> Unit,
    ) {
        activity.runOnUiThread {
            if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread

            activeDialogs.remove(activity)?.dismiss()
            val dialog = AlertDialog.Builder(activity)
                .setTitle("Автонастройка не завершена")
                .setMessage(
                    "$errorMessage\n\n" +
                        "Можно повторить попытку, включить ADB вручную в меню разработчика " +
                        "или открыть инженерное меню.",
                )
                .setPositiveButton("Повторить") { _, _ -> retry() }
                .setNeutralButton("Настройки ADB") { _, _ ->
                    openDeveloperSettings(activity)
                }
                .setNegativeButton("Инженерное меню") { _, _ ->
                    activity.startActivity(Intent(activity, EngineeringActivity::class.java))
                }
                .create()
            dialog.setOnDismissListener { activeDialogs.remove(activity) }
            activeDialogs[activity] = dialog
            dialog.show()
        }
    }

    private fun openDeveloperSettings(activity: Activity) {
        val candidates = listOf(
            Intent().setClassName(
                TV_SETTINGS_PACKAGE,
                TV_DEVELOPMENT_ACTIVITY,
            ),
            Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
            Intent(LEGACY_DEVELOPMENT_ACTION),
        )
        for (intent in candidates) {
            val launched = runCatching {
                activity.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.isSuccess
            if (launched) return
        }

        Toast.makeText(
            activity,
            "Меню разработчика не найдено — открыто инженерное меню",
            Toast.LENGTH_LONG,
        ).show()
        activity.startActivity(Intent(activity, EngineeringActivity::class.java))
    }

    private const val TV_SETTINGS_PACKAGE = "com.android.tv.settings"
    private const val TV_DEVELOPMENT_ACTIVITY =
        "com.android.tv.settings.system.development.DevelopmentActivity"
    private const val LEGACY_DEVELOPMENT_ACTION =
        "com.android.settings.APPLICATION_DEVELOPMENT_SETTINGS"
}
