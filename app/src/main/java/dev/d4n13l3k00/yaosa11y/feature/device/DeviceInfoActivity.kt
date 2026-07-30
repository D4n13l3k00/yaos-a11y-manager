package dev.d4n13l3k00.yaosa11y.feature.device

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import dev.d4n13l3k00.yaosa11y.R
import dev.d4n13l3k00.yaosa11y.core.platform.PlatformProfileResolver
import dev.d4n13l3k00.yaosa11y.core.privilege.PrivilegeManager
import dev.d4n13l3k00.yaosa11y.core.ui.ActivityTaskScope
import dev.d4n13l3k00.yaosa11y.core.ui.applyTvActionStyle
import dev.d4n13l3k00.yaosa11y.core.ui.applyTvScreenInsets
import dev.d4n13l3k00.yaosa11y.core.ui.applyTvScreenSubtitleStyle
import dev.d4n13l3k00.yaosa11y.core.ui.applyTvScreenTitleStyle
import dev.d4n13l3k00.yaosa11y.core.ui.dp
import dev.d4n13l3k00.yaosa11y.core.ui.roundedDrawable
import dev.d4n13l3k00.yaosa11y.feature.accessibility.RootHookManager
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

class DeviceInfoActivity : Activity() {
    private val tasks = ActivityTaskScope(this)
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
            applyTvScreenInsets()
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = "Об устройстве"
            applyTvScreenTitleStyle(R.drawable.ic_device)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(
            actionButton("Назад", R.drawable.ic_back) { finish() },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = dp(14) },
        )
        panel.addView(header)

        panel.addView(TextView(this).apply {
            text = "Состояние системы, ADB и защиты спецвозможностей • обновляется автоматически"
            applyTvScreenSubtitleStyle()
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
        tasks.execute {
            val state = rootHookManager.queryState()
            val privilege = privilegeManager.snapshot()
            tasks.post {
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
        val profile = PlatformProfileResolver(this).resolve()
        deviceDetails.text = buildString {
            appendLine("Модель: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Устройство: ${Build.DEVICE}")
            appendLine("Плата: ${Build.BOARD}")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("ABI: ${Build.SUPPORTED_ABIS.joinToString()}")
            appendLine("IP: ${localIpv4Address()}")
            appendLine("Установлено пакетов: $packages")
            appendLine("Профиль: ${profile.displayName}")
            append(
                "CVTE Factory API: " +
                    if (profile.supportsCvteFactoryApi) "найден" else "не найден",
            )
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
            applyTvActionStyle(iconRes, horizontalPaddingDp = 24, radiusDp = 12)
            setOnClickListener { action() }
        }

    private fun rounded(color: Int, radiusDp: Int): GradientDrawable =
        roundedDrawable(color, radiusDp)

    private fun localIpv4Address(): String =
        runCatching {
            Collections.list(NetworkInterface.getNetworkInterfaces())
                .filter { it.isUp && !it.isLoopback }
                .flatMap { Collections.list(it.inetAddresses) }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress }
                ?.hostAddress
        }.getOrNull() ?: "недоступен"

    companion object {
        private const val REFRESH_INTERVAL_MILLIS = 2_000L
    }
}
