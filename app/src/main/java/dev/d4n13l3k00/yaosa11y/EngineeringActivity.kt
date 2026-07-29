package dev.d4n13l3k00.yaosa11y

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import dadb.Dadb
import java.util.concurrent.Executors

class EngineeringActivity : Activity() {
    private lateinit var statusView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(createContent())
    }

    private fun createContent(): View {
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(16, 19, 23))
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(72), dp(40), dp(72), dp(40))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = "Инженерное меню"
            textSize = 32f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(actionButton("Назад", R.drawable.ic_back) { finish() })
        panel.addView(header)

        panel.addView(TextView(this).apply {
            text = "Factory Menu для MStar, MediaTek, CV9632 и SK706S • меню разработчика для ADB"
            textSize = 16f
            setTextColor(Color.rgb(166, 179, 191))
            setPadding(0, dp(6), 0, dp(18))
        })

        statusView = TextView(this).apply {
            text = "Выберите метод или запустите автоопределение"
            textSize = 16f
            setTextColor(Color.rgb(129, 216, 161))
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(22), dp(13), dp(22), dp(13))
            background = rounded(Color.rgb(28, 34, 43), 12)
        }
        panel.addView(
            statusView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(16) },
        )

        val autoButton = actionButton("Автоопределение", R.drawable.ic_refresh) {
            launchAutomatic()
        }
        panel.addView(
            buttonRow(
                autoButton,
                actionButton("Design Menu", R.drawable.ic_engineering) {
                    showResult(launchExactActivity(DESIGN_MENU_ACTIVITY))
                },
                actionButton("CVTE service", R.drawable.ic_settings) {
                    showResult(launchCvteService())
                },
            ),
            rowParams(),
        )
        panel.addView(
            buttonRow(
                actionButton("MStar action", R.drawable.ic_engineering) {
                    showResult(launchAction(MSTAR_FACTORY_ACTION))
                },
                actionButton("MediaTek action", R.drawable.ic_engineering) {
                    showResult(launchAction(MEDIATEK_FACTORY_ACTION))
                },
                actionButton("Cultraview action", R.drawable.ic_engineering) {
                    showResult(launchAction(CULTRAVIEW_FACTORY_ACTION))
                },
            ),
            rowParams(),
        )
        panel.addView(
            buttonRow(
                actionButton("Разработчик / ADB", R.drawable.ic_adb) {
                    showResult(launchDeveloperSettings())
                },
                actionButton("Комбинация пульта", R.drawable.ic_engineering) {
                    launchFactoryHotkey()
                },
            ),
            rowParams(),
        )

        root.addView(
            panel,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        autoButton.post { autoButton.requestFocus() }
        return root
    }

    private fun buttonRow(vararg buttons: View): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            buttons.forEachIndexed { index, button ->
                addView(button, buttonParams(last = index == buttons.lastIndex))
            }
        }

    private fun rowParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f,
        ).apply { bottomMargin = dp(12) }

    private fun buttonParams(last: Boolean = false): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.MATCH_PARENT,
            1f,
        ).apply {
            if (!last) marginEnd = dp(12)
        }

    private fun launchAutomatic() {
        statusView.text = "Автоопределение: проверка доступных компонентов…"
        val attempts = listOf(
            { launchExactActivity(DESIGN_MENU_ACTIVITY) },
            { launchAction(MSTAR_FACTORY_ACTION) },
            { launchAction(MEDIATEK_FACTORY_ACTION) },
            { launchAction(CULTRAVIEW_FACTORY_ACTION) },
            { launchCvteService() },
        )
        for (attempt in attempts) {
            val result = attempt()
            if (result.success) {
                showResult(result)
                return
            }
        }
        launchFactoryHotkey()
    }

    private fun launchExactActivity(className: String): LaunchResult {
        val info = runCatching {
            packageManager.getInstalledPackages(PackageManager.GET_ACTIVITIES)
                .asSequence()
                .flatMap { installed -> installed.activities.orEmpty().asSequence() }
                .firstOrNull { activity -> activity.name == className }
        }.getOrNull()
            ?: return LaunchResult(false, "Компонент $className не найден")

        val intent = Intent()
            .setClassName(info.packageName, info.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            startActivity(intent)
            LaunchResult(true, "Открыт ${info.packageName}/${info.name}")
        }.getOrElse { error ->
            LaunchResult(false, "Не удалось открыть ${info.packageName}: ${error.message}")
        }
    }

    private fun launchAction(action: String): LaunchResult {
        val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val component = packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            ?: packageManager.resolveActivity(intent, 0)
            ?: return LaunchResult(false, "Action $action не поддерживается")
        return runCatching {
            startActivity(intent)
            LaunchResult(true, "Открыт action $action через ${component.activityInfo.packageName}")
        }.getOrElse { error ->
            LaunchResult(false, "Action $action не запустился: ${error.message}")
        }
    }

    private fun launchCvteService(): LaunchResult {
        val intent = Intent()
            .setClassName(CVTE_FACTORY_PACKAGE, CVTE_FACTORY_SERVICE)
            .putExtra(CVTE_FACTORY_COMMAND_KEY, CVTE_FACTORY_COMMAND)
        return runCatching {
            val component = startService(intent)
                ?: error("сервис не найден")
            LaunchResult(true, "Команда Factory Menu отправлена в $component")
        }.getOrElse { error ->
            LaunchResult(false, "CVTE Factory service недоступен: ${error.message}")
        }
    }

    private fun launchDeveloperSettings(): LaunchResult {
        val exact = launchExactActivity(DEVELOPMENT_ACTIVITY)
        if (exact.success) return exact.copy(message = "Открыто меню разработчика / ADB")

        val standard = launchAction(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
        if (standard.success) return standard.copy(message = "Открыто меню разработчика / ADB")

        val legacy = launchAction(LEGACY_DEVELOPMENT_ACTION)
        if (legacy.success) return legacy.copy(message = "Открыто меню разработчика / ADB")

        return LaunchResult(false, "Меню разработчика не найдено на этой прошивке")
    }

    private fun launchFactoryHotkey() {
        statusView.text = "Включение локального ADB и отправка сервисной комбинации…"
        statusView.setTextColor(Color.rgb(255, 183, 77))
        EXECUTOR.execute {
            val result = runCatching {
                val bootstrap = PrivilegeManager(this).ensureAdb()
                check(bootstrap.success) { bootstrap.message }
                Dadb.create(
                    host = "127.0.0.1",
                    port = 5555,
                    keyPair = null,
                    connectTimeout = 5_000,
                    socketTimeout = 20_000,
                ).use { adb ->
                    val response = adb.shell(FACTORY_HOTKEY_COMMAND)
                    check(response.exitCode == 0) { response.allOutput.trim() }
                }
                LaunchResult(true, "Сервисная комбинация отправлена")
            }.getOrElse { error ->
                LaunchResult(false, "Не удалось отправить комбинацию: ${error.message}")
            }
            runOnUiThread { showResult(result) }
        }
    }

    private fun showResult(result: LaunchResult) {
        statusView.text = result.message
        statusView.setTextColor(
            if (result.success) Color.rgb(129, 216, 161) else Color.rgb(255, 128, 128),
        )
        Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
    }

    private fun actionButton(label: String, iconRes: Int, action: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(dp(18), dp(12), dp(18), dp(12))
            background = buttonBackground()
            isFocusable = true
            isClickable = true
            setCompoundDrawablesWithIntrinsicBounds(iconRes, 0, 0, 0)
            compoundDrawablePadding = dp(10)
            setOnClickListener { action() }
        }

    private fun buttonBackground(): StateListDrawable =
        StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), rounded(Color.rgb(44, 103, 148), 12))
            addState(intArrayOf(android.R.attr.state_pressed), rounded(Color.rgb(38, 91, 130), 12))
            addState(intArrayOf(), rounded(Color.rgb(28, 34, 43), 12))
        }

    private fun rounded(color: Int, radiusDp: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
        }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    private data class LaunchResult(
        val success: Boolean,
        val message: String,
    )

    companion object {
        private const val DESIGN_MENU_ACTIVITY =
            "mediatek.tvsetting.factory.ui.designmenu.DesignMenuActivity"
        private const val DEVELOPMENT_ACTIVITY =
            "com.android.tv.settings.system.development.DevelopmentActivity"
        private const val LEGACY_DEVELOPMENT_ACTION =
            "com.android.settings.APPLICATION_DEVELOPMENT_SETTINGS"

        private const val MSTAR_FACTORY_ACTION =
            "mstar.tvsetting.factory.intent.action.MainmenuActivity"
        private const val MEDIATEK_FACTORY_ACTION =
            "mediatek.intent.action.MainmenuActivity"
        private const val CULTRAVIEW_FACTORY_ACTION =
            "com.cultraview.ctvfactorymenu.ui.FactoryMenuActivity"

        private const val CVTE_FACTORY_PACKAGE = "com.cvte.fac.menu"
        private const val CVTE_FACTORY_SERVICE =
            "com.cvte.fac.menu.app.TvMenuWindowManagerService"
        private const val CVTE_FACTORY_COMMAND_KEY = "com.cvte.fac.menu.commmand"
        private const val CVTE_FACTORY_COMMAND =
            "com.cvte.fac.menu.commmand.factory_menu"

        private const val FACTORY_HOTKEY_COMMAND =
            "input keyevent 3; sleep 0.4; input keyevent 178; sleep 0.4; " +
                "input keyevent 21; input keyevent 19; input keyevent 21; " +
                "input keyevent 19; input keyevent 4; sleep 0.3; input keyevent 178"

        private val EXECUTOR = Executors.newSingleThreadExecutor()
    }
}
