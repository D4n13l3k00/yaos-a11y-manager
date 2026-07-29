package dev.d4n13l3k00.yaosa11y

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections
import java.util.concurrent.Executors

class DeviceInfoActivity : Activity() {
    private lateinit var deviceDetails: TextView
    private lateinit var protectionStatus: TextView
    private lateinit var protectionDetails: TextView
    private lateinit var adbStatus: TextView
    private lateinit var permissionStatus: TextView
    private lateinit var rootHookManager: RootHookManager
    private lateinit var privilegeManager: PrivilegeManager

    private val handler = Handler(Looper.getMainLooper())
    private var refreshInFlight = false
    private val refreshLoop = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, REFRESH_INTERVAL_MILLIS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        rootHookManager = RootHookManager(this)
        privilegeManager = PrivilegeManager(this)
        setContentView(createContent())
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(refreshLoop)
        refreshLoop.run()
    }

    override fun onPause() {
        handler.removeCallbacks(refreshLoop)
        super.onPause()
    }

    private fun createContent(): View {
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(16, 19, 23))
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(72), dp(30), dp(72), dp(28))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = "Об устройстве"
            textSize = 32f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(actionButton("Обновить", R.drawable.ic_refresh) { refresh() })
        header.addView(
            actionButton("Назад", R.drawable.ic_back) { finish() },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = dp(14) },
        )
        panel.addView(header)

        panel.addView(TextView(this).apply {
            text = "Состояние системы, автономного ADB и защиты спецвозможностей"
            textSize = 16f
            setTextColor(Color.rgb(166, 179, 191))
            setPadding(0, dp(4), 0, dp(16))
        })

        val cards = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        val deviceCard = card().apply {
            addView(cardTitle("Устройство", R.drawable.ic_device))
            deviceDetails = bodyText("Чтение сведений…")
            addView(deviceDetails)
        }
        cards.addView(
            deviceCard,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                marginEnd = dp(16)
            },
        )

        val statusCard = card().apply {
            addView(cardTitle("Автономные функции", R.drawable.ic_settings))
            protectionStatus = statusText(
                "Защита YAOS: проверка…",
                Color.rgb(255, 183, 77),
                R.drawable.ic_shield,
            )
            addView(protectionStatus)
            protectionDetails = bodyText("Проверяется native-hook в процессе YAOS")
            addView(protectionDetails)
            adbStatus = statusText(
                "ADB: проверка…",
                Color.rgb(255, 183, 77),
                R.drawable.ic_adb,
            )
            adbStatus.setPadding(0, dp(10), 0, 0)
            addView(adbStatus)
            permissionStatus = bodyText("WRITE_SECURE_SETTINGS: проверка…")
            addView(permissionStatus)
        }
        cards.addView(
            statusCard,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f),
        )

        panel.addView(
            cards,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ),
        )

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, 0)
        }
        actions.addView(actionButton("Восстановить защиту", R.drawable.ic_shield) {
            restoreProtection()
        })
        actions.addView(
            actionButton("Включить ADB", R.drawable.ic_adb) {
                enableAdb()
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = dp(14) },
        )
        actions.addView(
            actionButton("Системные настройки", R.drawable.ic_settings) {
            runCatching { startActivity(Intent(Settings.ACTION_SETTINGS)) }
                .onFailure {
                    Toast.makeText(this, "Системные настройки недоступны", Toast.LENGTH_LONG).show()
                }
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = dp(14) },
        )
        panel.addView(actions)

        root.addView(
            panel,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        return root
    }

    private fun restoreProtection() {
        protectionStatus.text = "Защита YAOS: запускается…"
        protectionStatus.setTextColor(Color.rgb(255, 183, 77))
        rootHookManager.runAsync(true) { result ->
            runOnUiThread {
                if (result.success) {
                    Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
                } else {
                    RecoveryDialog.show(this, result.message) {
                        restoreProtection()
                    }
                }
                refresh()
            }
        }
    }

    private fun enableAdb() {
        adbStatus.text = "ADB: автоматический поиск способа включения…"
        adbStatus.setTextColor(Color.rgb(255, 183, 77))
        EXECUTOR.execute {
            val result = privilegeManager.ensureAdb()
            runOnUiThread {
                if (result.success) {
                    Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
                } else {
                    RecoveryDialog.show(this, result.message) {
                        enableAdb()
                    }
                }
                refresh()
            }
        }
    }

    private fun refresh() {
        updateStaticDetails()
        if (refreshInFlight) return
        refreshInFlight = true
        EXECUTOR.execute {
            val state = rootHookManager.queryState()
            val privilege = privilegeManager.snapshot()
            runOnUiThread {
                refreshInFlight = false
                updateProtectionState(state)
                adbStatus.text =
                    "ADB shell: ${if (privilege.adbAvailable) "доступен" else "недоступен"}"
                adbStatus.setTextColor(
                    if (privilege.adbAvailable) {
                        Color.rgb(129, 216, 161)
                    } else {
                        Color.rgb(255, 128, 128)
                    },
                )
                permissionStatus.text = buildString {
                    append(
                        "WRITE_SECURE_SETTINGS: " +
                            if (privilege.secureSettingsGranted) "выдано" else "ещё не выдано",
                    )
                    appendLine()
                    append(
                        "Root-бэкенд: " +
                            (privilege.rootBackend?.displayName ?: "не выбран"),
                    )
                }
                permissionStatus.setTextColor(
                    if (privilege.secureSettingsGranted) {
                        Color.rgb(166, 179, 191)
                    } else {
                        Color.rgb(255, 183, 77)
                    },
                )
            }
        }
    }

    private fun updateStaticDetails() {
        val packages = runCatching { packageManager.getInstalledApplications(0).size }.getOrDefault(0)
        val cvteFactoryApi = runCatching {
            packageManager.getApplicationInfo(CVTE_FACTORY_PACKAGE, 0)
        }.isSuccess
        deviceDetails.text = buildString {
            appendLine("Модель: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Устройство: ${Build.DEVICE}")
            appendLine("Плата: ${Build.BOARD}")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("ABI: ${Build.SUPPORTED_ABIS.joinToString()}")
            appendLine("IP: ${localIpv4Address()}")
            appendLine("Установлено пакетов: $packages")
            append("CVTE Factory API: ${if (cvteFactoryApi) "найден" else "не найден"}")
        }

    }

    private fun updateProtectionState(state: RootHookManager.State) {
        val snapshot = rootHookManager.protectionSnapshot()
        val details = buildString {
            append("Закреплено: ${snapshot.protectedComponents.size}")
            append(" • включено: ${snapshot.desiredEnabled.size}")
            if (snapshot.lastReconcileMessage.isNotBlank()) {
                appendLine()
                append(
                    when {
                        snapshot.lastReconcileMessage.contains("в норме", ignoreCase = true) ->
                            "Сверка: в норме"
                        snapshot.lastReconcileMessage.length > 58 ->
                            snapshot.lastReconcileMessage.take(55) + "…"
                        else -> snapshot.lastReconcileMessage
                    },
                )
            }
        }
        when (state) {
            RootHookManager.State.ENABLED -> {
                protectionStatus.text = "Защита YAOS: включена"
                protectionStatus.setTextColor(Color.rgb(129, 216, 161))
                protectionDetails.text = "Native-hook: активен\n$details"
            }
            RootHookManager.State.GUARD_ONLY -> {
                protectionStatus.text = "Защита YAOS: guard активен"
                protectionStatus.setTextColor(Color.rgb(129, 216, 161))
                protectionDetails.text =
                    "Настройки сверяются без native-hook; CVTE не требуется\n$details"
            }
            RootHookManager.State.DISABLED -> {
                protectionStatus.text = "Защита YAOS: отключена"
                protectionStatus.setTextColor(Color.rgb(177, 187, 197))
                protectionDetails.text =
                    "Root-daemon установлен, фильтрация настроек отключена\n$details"
            }
            RootHookManager.State.STARTING -> {
                protectionStatus.text = "Защита YAOS: запускается…"
                protectionStatus.setTextColor(Color.rgb(255, 183, 77))
                protectionDetails.text = "Ожидание внедрения native-hook\n$details"
            }
            RootHookManager.State.UNAVAILABLE -> {
                protectionStatus.text = "Защита YAOS: хук пока не запущен"
                protectionStatus.setTextColor(Color.rgb(255, 128, 128))
                protectionDetails.text =
                    "Guard продолжает сверку настроек; можно восстановить native-hook\n$details"
            }
        }
    }

    private fun card(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(18), dp(28), dp(18))
            background = rounded(Color.rgb(28, 34, 43), 16)
        }

    private fun cardTitle(text: String, iconRes: Int): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 22f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(12))
            setCompoundDrawablesWithIntrinsicBounds(iconRes, 0, 0, 0)
            compoundDrawablePadding = dp(14)
        }

    private fun bodyText(text: String): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 15f
            setTextColor(Color.rgb(166, 179, 191))
            setLineSpacing(dp(1).toFloat(), 1f)
        }

    private fun statusText(text: String, color: Int, iconRes: Int): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 18f
            setTextColor(color)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(3))
            setCompoundDrawablesWithIntrinsicBounds(iconRes, 0, 0, 0)
            compoundDrawablePadding = dp(12)
        }

    private fun actionButton(text: String, iconRes: Int, action: () -> Unit): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(dp(24), dp(12), dp(24), dp(12))
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

    private fun localIpv4Address(): String =
        runCatching {
            Collections.list(NetworkInterface.getNetworkInterfaces())
                .filter { it.isUp && !it.isLoopback }
                .flatMap { Collections.list(it.inetAddresses) }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress }
                ?.hostAddress
        }.getOrNull() ?: "недоступен"

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        private const val CVTE_FACTORY_PACKAGE = "com.cvte.factory.service"
        private const val REFRESH_INTERVAL_MILLIS = 2_000L
        private val EXECUTOR = Executors.newSingleThreadExecutor()
    }
}
