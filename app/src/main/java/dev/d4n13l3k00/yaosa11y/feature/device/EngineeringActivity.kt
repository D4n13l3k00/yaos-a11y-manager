package dev.d4n13l3k00.yaosa11y.feature.device

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
import dev.d4n13l3k00.yaosa11y.R
import dev.d4n13l3k00.yaosa11y.core.adb.AdbGateway
import dev.d4n13l3k00.yaosa11y.core.platform.EngineeringEndpoint
import dev.d4n13l3k00.yaosa11y.core.platform.PlatformProfileResolver
import dev.d4n13l3k00.yaosa11y.core.privilege.PrivilegeManager
import dev.d4n13l3k00.yaosa11y.core.ui.ActivityTaskScope
import dev.d4n13l3k00.yaosa11y.core.ui.applyTvActionStyle
import dev.d4n13l3k00.yaosa11y.core.ui.dp
import dev.d4n13l3k00.yaosa11y.core.ui.roundedDrawable

class EngineeringActivity : Activity() {
    private val tasks = ActivityTaskScope(this)
    private val profile by lazy { PlatformProfileResolver(this).resolve() }
    private lateinit var statusView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(createContent())
    }

    override fun onDestroy() {
        tasks.close()
        super.onDestroy()
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
                    showResult(launchProfileEndpoint("Design Menu"))
                },
                actionButton("CVTE service", R.drawable.ic_settings) {
                    showResult(launchProfileEndpoint("CVTE service"))
                },
            ),
            rowParams(),
        )
        panel.addView(
            buttonRow(
                actionButton("MStar action", R.drawable.ic_engineering) {
                    showResult(launchProfileEndpoint("MStar Factory"))
                },
                actionButton("MediaTek action", R.drawable.ic_engineering) {
                    showResult(launchProfileEndpoint("MediaTek Factory"))
                },
                actionButton("Cultraview action", R.drawable.ic_engineering) {
                    showResult(launchProfileEndpoint("Cultraview Factory"))
                },
            ),
            rowParams(),
        )
        panel.addView(
            buttonRow(
                actionButton("Включить ADB", R.drawable.ic_adb) {
                    enableAdb()
                },
                actionButton("Настройки ADB", R.drawable.ic_settings) {
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
        for (endpoint in profile.engineeringEndpoints) {
            val result = launchEndpoint(endpoint)
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

    private fun launchCvteService(endpoint: EngineeringEndpoint.Service): LaunchResult {
        val intent = Intent()
            .setClassName(endpoint.packageName, endpoint.className)
            .putExtra(endpoint.extraKey, endpoint.extraValue)
        return runCatching {
            val component = startService(intent)
                ?: error("сервис не найден")
            LaunchResult(true, "Команда Factory Menu отправлена в $component")
        }.getOrElse { error ->
            LaunchResult(false, "CVTE Factory service недоступен: ${error.message}")
        }
    }

    private fun launchProfileEndpoint(label: String): LaunchResult =
        profile.engineeringEndpoints.firstOrNull { it.label == label }
            ?.let(::launchEndpoint)
            ?: LaunchResult(false, "Метод $label отсутствует в профиле ${profile.displayName}")

    private fun launchEndpoint(endpoint: EngineeringEndpoint): LaunchResult =
        when (endpoint) {
            is EngineeringEndpoint.Activity -> launchExactActivity(endpoint.className)
            is EngineeringEndpoint.Action -> launchAction(endpoint.action)
            is EngineeringEndpoint.Service -> launchCvteService(endpoint)
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

    private fun enableAdb() {
        statusView.text = "ADB: автоматический поиск доступного способа…"
        statusView.setTextColor(Color.rgb(255, 183, 77))
        tasks.execute {
            val result = PrivilegeManager(this).ensureAdb()
            tasks.post {
                showResult(LaunchResult(result.success, result.message))
            }
        }
    }

    private fun launchFactoryHotkey() {
        statusView.text = "Включение локального ADB и отправка сервисной комбинации…"
        statusView.setTextColor(Color.rgb(255, 183, 77))
        tasks.execute {
            val result = runCatching {
                val bootstrap = PrivilegeManager(this).ensureAdb()
                check(bootstrap.success) { bootstrap.message }
                val gateway = AdbGateway()
                gateway.withConnection(socketTimeout = 20_000) { adb ->
                    gateway.shell(adb, FACTORY_HOTKEY_COMMAND)
                }
                LaunchResult(true, "Сервисная комбинация отправлена")
            }.getOrElse { error ->
                LaunchResult(false, "Не удалось отправить комбинацию: ${error.message}")
            }
            tasks.post { showResult(result) }
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
            applyTvActionStyle(iconRes, horizontalPaddingDp = 18, radiusDp = 12)
            setOnClickListener { action() }
        }

    private fun rounded(color: Int, radiusDp: Int): GradientDrawable =
        roundedDrawable(color, radiusDp)

    private data class LaunchResult(
        val success: Boolean,
        val message: String,
    )

    companion object {
        private const val DEVELOPMENT_ACTIVITY =
            "com.android.tv.settings.system.development.DevelopmentActivity"
        private const val LEGACY_DEVELOPMENT_ACTION =
            "com.android.settings.APPLICATION_DEVELOPMENT_SETTINGS"

        private const val FACTORY_HOTKEY_COMMAND =
            "input keyevent 3; sleep 0.4; input keyevent 178; sleep 0.4; " +
                "input keyevent 21; input keyevent 19; input keyevent 21; " +
                "input keyevent 19; input keyevent 4; sleep 0.3; input keyevent 178"

    }
}
