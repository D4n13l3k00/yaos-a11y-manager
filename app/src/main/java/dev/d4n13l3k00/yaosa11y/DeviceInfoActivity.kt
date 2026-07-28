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
            setPadding(dp(72), dp(42), dp(72), dp(42))
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
            text = "Состояние системы, автономного ADB и root-защиты YAOS"
            textSize = 16f
            setTextColor(Color.rgb(166, 179, 191))
            setPadding(0, dp(6), 0, dp(24))
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
            adbStatus.setPadding(0, dp(22), 0, 0)
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
            setPadding(0, dp(20), 0, 0)
        }
        actions.addView(actionButton("Восстановить защиту", R.drawable.ic_shield) {
            protectionStatus.text = "Защита YAOS: запускается…"
            protectionStatus.setTextColor(Color.rgb(255, 183, 77))
            rootHookManager.runAsync(true) { result ->
                runOnUiThread {
                    Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
                    refresh()
                }
            }
        })
        actions.addView(
            actionButton("Включить ADB", R.drawable.ic_adb) {
            adbStatus.text = "ADB: включается через CVTE Factory API…"
            adbStatus.setTextColor(Color.rgb(255, 183, 77))
            EXECUTOR.execute {
                val result = CvteAdbBootstrap(this).enableAndWait()
                runOnUiThread {
                    Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
                    refresh()
                }
            }
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

    private fun refresh() {
        updateStaticDetails()
        if (refreshInFlight) return
        refreshInFlight = true
        rootHookManager.queryStateAsync { state ->
            runOnUiThread {
                refreshInFlight = false
                updateProtectionState(state)
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

        val adbEnabled = Settings.Global.getInt(
            contentResolver,
            Settings.Global.ADB_ENABLED,
            0,
        ) != 0
        adbStatus.text = "ADB: ${if (adbEnabled) "включён" else "выключен"}"
        adbStatus.setTextColor(
            if (adbEnabled) Color.rgb(129, 216, 161) else Color.rgb(255, 128, 128),
        )

        val permissionGranted =
            checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
                PackageManager.PERMISSION_GRANTED
        permissionStatus.text =
            "WRITE_SECURE_SETTINGS: ${if (permissionGranted) "выдано через root" else "ещё не выдано"}"
        permissionStatus.setTextColor(
            if (permissionGranted) Color.rgb(166, 179, 191) else Color.rgb(255, 183, 77),
        )
    }

    private fun updateProtectionState(state: RootHookManager.State) {
        when (state) {
            RootHookManager.State.ENABLED -> {
                protectionStatus.text = "Защита YAOS: включена"
                protectionStatus.setTextColor(Color.rgb(129, 216, 161))
                protectionDetails.text = "Native-hook активен в текущем процессе YAOS"
            }
            RootHookManager.State.DISABLED -> {
                protectionStatus.text = "Защита YAOS: отключена"
                protectionStatus.setTextColor(Color.rgb(177, 187, 197))
                protectionDetails.text = "Root-daemon установлен, фильтрация настроек отключена"
            }
            RootHookManager.State.STARTING -> {
                protectionStatus.text = "Защита YAOS: запускается…"
                protectionStatus.setTextColor(Color.rgb(255, 183, 77))
                protectionDetails.text = "Ожидание внедрения native-hook"
            }
            RootHookManager.State.UNAVAILABLE -> {
                protectionStatus.text = "Защита YAOS: хук пока не запущен"
                protectionStatus.setTextColor(Color.rgb(255, 128, 128))
                protectionDetails.text =
                    "Статус обновляется автоматически; можно нажать «Восстановить защиту»"
            }
        }
    }

    private fun card(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(34), dp(30), dp(34), dp(30))
            background = rounded(Color.rgb(28, 34, 43), 16)
        }

    private fun cardTitle(text: String, iconRes: Int): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 25f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(22))
            setCompoundDrawablesWithIntrinsicBounds(iconRes, 0, 0, 0)
            compoundDrawablePadding = dp(14)
        }

    private fun bodyText(text: String): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 17f
            setTextColor(Color.rgb(166, 179, 191))
            setLineSpacing(dp(3).toFloat(), 1f)
        }

    private fun statusText(text: String, color: Int, iconRes: Int): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 21f
            setTextColor(color)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(6))
            setCompoundDrawablesWithIntrinsicBounds(iconRes, 0, 0, 0)
            compoundDrawablePadding = dp(12)
        }

    private fun actionButton(text: String, iconRes: Int, action: () -> Unit): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(dp(26), dp(15), dp(26), dp(15))
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
