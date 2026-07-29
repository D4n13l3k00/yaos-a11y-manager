package dev.d4n13l3k00.yaosa11y

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var protectionStatus: TextView
    private lateinit var deviceStatus: TextView
    private lateinit var rootHookManager: RootHookManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        rootHookManager = RootHookManager(this)
        setContentView(createContent())
        if (!rootHookManager.hasStoredChoice()) {
            enableProtectionWithRecovery()
        } else if (rootHookManager.shouldBeEnabled()) {
            rootHookManager.ensureProtectionServiceRunning()
            rootHookManager.queryStateAsync { state ->
                if (
                    state == RootHookManager.State.ENABLED ||
                    state == RootHookManager.State.GUARD_ONLY
                ) {
                    refreshStatus()
                } else {
                    enableProtectionWithRecovery()
                }
            }
        } else {
            ensureAdbAsync(showResult = false)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun createContent(): View {
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(16, 19, 23))
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(72), dp(38), dp(72), dp(36))
        }
        panel.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            setTextColor(Color.WHITE)
            textSize = 31f
            typeface = Typeface.DEFAULT_BOLD
        })
        panel.addView(TextView(this).apply {
            text = getString(R.string.author_line)
            setTextColor(Color.rgb(94, 184, 255))
            textSize = 15f
            setPadding(0, dp(3), 0, 0)
        })

        val summary = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(20))
        }
        protectionStatus = TextView(this).apply {
            text = "Защита YAOS: проверка…"
            setTextColor(Color.rgb(255, 183, 77))
            textSize = 15f
        }
        summary.addView(
            protectionStatus,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        deviceStatus = TextView(this).apply {
            text = "Чтение сведений…"
            setTextColor(Color.rgb(153, 166, 179))
            textSize = 14f
            gravity = Gravity.END
        }
        summary.addView(
            deviceStatus,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        panel.addView(summary)

        val firstRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        firstRow.addView(
            sectionCard(
                title = "Приложения",
                subtitle = "Заморозка, удаление, кэш, данные и установка по ссылке",
                accent = Color.rgb(94, 184, 255),
                iconRes = R.drawable.ic_apps,
            ) { startActivity(Intent(this, AppManagerActivity::class.java)) },
            cardParams(),
        )
        firstRow.addView(
            sectionCard(
                title = "Спецвозможности",
                subtitle = "Управление службами и точечная защита от watchdog YAOS",
                accent = Color.rgb(129, 216, 161),
                iconRes = R.drawable.ic_accessibility,
            ) { startActivity(Intent(this, AccessibilityActivity::class.java)) },
            cardParams(),
        )
        firstRow.addView(
            sectionCard(
                title = "Установка APK",
                subtitle = "QR-код, локальный веб-сервер, журнал и прямые ссылки",
                accent = Color.rgb(216, 143, 255),
                iconRes = R.drawable.ic_install,
            ) { startActivity(Intent(this, WebInstallActivity::class.java)) },
            cardParams(),
        )
        panel.addView(
            firstRow,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ),
        )

        val secondRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, 0)
        }
        secondRow.addView(
            sectionCard(
                title = "Инженерное меню",
                subtitle = "Все варианты Factory Menu и меню разработчика для ADB",
                accent = Color.rgb(255, 183, 77),
                iconRes = R.drawable.ic_engineering,
            ) { startActivity(Intent(this, EngineeringActivity::class.java)) },
            cardParams(),
        )
        secondRow.addView(
            sectionCard(
                title = "Настройки Android",
                subtitle = "Сеть, экран, звук, аккаунты и системные параметры телевизора",
                accent = Color.rgb(255, 128, 128),
                iconRes = R.drawable.ic_settings,
            ) {
                runCatching { startActivity(Intent(Settings.ACTION_SETTINGS)) }
                    .onFailure { Toast.makeText(this, "Системные настройки недоступны", Toast.LENGTH_LONG).show() }
            },
            cardParams(),
        )
        secondRow.addView(
            sectionCard(
                title = "Об устройстве",
                subtitle = "Модель, плата, Android, IP-адрес и доступные привилегии",
                accent = Color.rgb(170, 180, 190),
                iconRes = R.drawable.ic_device,
            ) { startActivity(Intent(this, DeviceInfoActivity::class.java)) },
            cardParams(),
        )
        panel.addView(
            secondRow,
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

    private fun sectionCard(
        title: String,
        subtitle: String,
        accent: Int,
        iconRes: Int,
        action: () -> Unit,
    ): View {
        val titleView = TextView(this).apply {
            text = title
            setTextColor(Color.WHITE)
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.MARQUEE
            marqueeRepeatLimit = -1
            isHorizontalFadingEdgeEnabled = true
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP
            setPadding(dp(26), dp(22), dp(26), dp(22))
            background = cardBackground()
            isFocusable = true
            isClickable = true
            setOnFocusChangeListener { _, hasFocus -> titleView.isSelected = hasFocus }
            setOnClickListener { action() }
            addView(View(this@MainActivity).apply {
                background = rounded(accent, 2)
            }, LinearLayout.LayoutParams(dp(48), dp(4)).apply {
                bottomMargin = dp(16)
            })
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(ImageView(this@MainActivity).apply {
                    setImageResource(iconRes)
                    setColorFilter(accent)
                }, LinearLayout.LayoutParams(dp(30), dp(30)).apply {
                    marginEnd = dp(14)
                })
                addView(titleView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            })
            addView(TextView(this@MainActivity).apply {
                text = subtitle
                setTextColor(Color.rgb(166, 179, 191))
                textSize = 14f
                setLineSpacing(0f, 1.12f)
                setPadding(0, dp(7), 0, 0)
            })
        }
    }

    private fun cardParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
            marginEnd = dp(14)
        }

    private fun refreshStatus() {
        rootHookManager.queryStateAsync { state ->
            runOnUiThread {
                protectionStatus.text = when (state) {
                    RootHookManager.State.ENABLED -> "Защита YAOS: включена"
                    RootHookManager.State.GUARD_ONLY -> "Защита YAOS: guard без root"
                    RootHookManager.State.DISABLED -> "Защита YAOS: отключена"
                    RootHookManager.State.STARTING -> "Защита YAOS: запускается…"
                    RootHookManager.State.UNAVAILABLE -> "Защита YAOS: недоступна"
                }
                protectionStatus.setTextColor(
                    when (state) {
                        RootHookManager.State.ENABLED -> Color.rgb(129, 216, 161)
                        RootHookManager.State.GUARD_ONLY -> Color.rgb(129, 216, 161)
                        RootHookManager.State.DISABLED -> Color.rgb(177, 187, 197)
                        else -> Color.rgb(255, 183, 77)
                    },
                )
            }
        }
        EXECUTOR.execute {
            val packages = runCatching { packageManager.getInstalledApplications(0).size }.getOrDefault(0)
            val ip = localIpv4Address()
            runOnUiThread {
                deviceStatus.text = "${Build.MODEL} • Android ${Build.VERSION.RELEASE} • $packages пакетов • $ip"
            }
        }
    }

    private fun ensureAdbAsync(showResult: Boolean) {
        EXECUTOR.execute {
            val result = PrivilegeManager(this).ensureAdb()
            if (!result.success) {
                RecoveryDialog.show(this, result.message) {
                    ensureAdbAsync(showResult = true)
                }
            } else if (showResult) {
                runOnUiThread {
                    Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun enableProtectionWithRecovery() {
        rootHookManager.runAsync(true) { result ->
            refreshStatus()
            if (!result.success) {
                RecoveryDialog.show(this, result.message) {
                    enableProtectionWithRecovery()
                }
            }
        }
    }

    private fun localIpv4Address(): String =
        runCatching {
            Collections.list(NetworkInterface.getNetworkInterfaces())
                .filter { it.isUp && !it.isLoopback }
                .flatMap { Collections.list(it.inetAddresses) }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress }
                ?.hostAddress
        }.getOrNull() ?: "IP недоступен"

    private fun cardBackground(): StateListDrawable =
        StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), rounded(Color.rgb(44, 103, 148), 16))
            addState(intArrayOf(android.R.attr.state_pressed), rounded(Color.rgb(38, 91, 130), 16))
            addState(intArrayOf(), rounded(Color.rgb(28, 34, 43), 16))
        }

    private fun rounded(color: Int, radiusDp: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
        }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        private val EXECUTOR = Executors.newSingleThreadExecutor()
    }
}
