package dev.d4n13l3k00.yaosa11y.feature.home

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import dev.d4n13l3k00.yaosa11y.R
import dev.d4n13l3k00.yaosa11y.core.privilege.PrivilegeManager
import dev.d4n13l3k00.yaosa11y.core.ui.ActivityTaskScope
import dev.d4n13l3k00.yaosa11y.core.ui.applyTvScreenInsets
import dev.d4n13l3k00.yaosa11y.core.ui.applyTvScreenTitleStyle
import dev.d4n13l3k00.yaosa11y.core.ui.dp
import dev.d4n13l3k00.yaosa11y.core.ui.postIfAlive
import dev.d4n13l3k00.yaosa11y.core.ui.roundedDrawable
import dev.d4n13l3k00.yaosa11y.core.ui.tvFocusBackground
import dev.d4n13l3k00.yaosa11y.feature.accessibility.AccessibilityActivity
import dev.d4n13l3k00.yaosa11y.feature.accessibility.RootHookManager
import dev.d4n13l3k00.yaosa11y.feature.apps.AppManagerActivity
import dev.d4n13l3k00.yaosa11y.feature.apps.WebInstallActivity
import dev.d4n13l3k00.yaosa11y.feature.device.DeviceInfoActivity
import dev.d4n13l3k00.yaosa11y.feature.device.EngineeringActivity
import dev.d4n13l3k00.yaosa11y.feature.recovery.RecoveryDialog
import dev.d4n13l3k00.yaosa11y.feature.update.GitHubUpdateClient
import dev.d4n13l3k00.yaosa11y.feature.update.UpdateActivity
import dev.d4n13l3k00.yaosa11y.feature.update.UpdateStateStore
import dev.d4n13l3k00.yaosa11y.feature.update.VersionPolicy
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

class MainActivity : Activity() {
    private val tasks = ActivityTaskScope(this)
    private lateinit var protectionStatus: TextView
    private lateinit var deviceStatus: TextView
    private lateinit var versionStatus: TextView
    private lateinit var updateNotice: TextView
    private lateinit var rootHookManager: RootHookManager
    private lateinit var updateStateStore: UpdateStateStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        rootHookManager = RootHookManager(this)
        updateStateStore = UpdateStateStore(this)
        setContentView(createContent())
        refreshUpdateStatus()
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

    override fun onDestroy() {
        tasks.close()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun createContent(): View {
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(16, 19, 23))
            clipChildren = false
            clipToPadding = false
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            applyTvScreenInsets()
            clipChildren = false
            clipToPadding = false
        }
        panel.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            addView(TextView(this@MainActivity).apply {
                text = getString(R.string.app_name)
                applyTvScreenTitleStyle()
            })
            versionStatus = TextView(this@MainActivity).apply {
                text = "v${installedVersionName()}"
                setTextColor(Color.rgb(126, 140, 154))
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding(dp(9), dp(3), dp(9), dp(3))
                background = tvFocusBackground(7)
                isFocusable = true
                isClickable = true
                contentDescription = "Проверить обновления приложения"
                setOnClickListener { openUpdateScreen() }
            }
            addView(versionStatus, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                marginStart = dp(10)
                bottomMargin = dp(1)
            })
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

        updateNotice = TextView(this).apply {
            visibility = View.GONE
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(9), dp(18), dp(9))
            background = tvFocusBackground(10)
            setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_update, 0, 0, 0)
            compoundDrawablePadding = dp(10)
            isFocusable = true
            isClickable = true
            setOnClickListener { openUpdateScreen() }
        }
        panel.addView(
            updateNotice,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin = dp(12)
            },
        )

        val firstRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            clipChildren = false
            clipToPadding = false
        }
        val applicationsCard = sectionCard(
                title = "Приложения",
                subtitle = "Заморозка, удаление, кэш, данные и системные пресеты",
                accent = Color.rgb(94, 184, 255),
                iconRes = R.drawable.ic_apps,
            ) { startActivity(Intent(this, AppManagerActivity::class.java)) }
        firstRow.addView(
            applicationsCard,
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
            clipChildren = false
            clipToPadding = false
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
        applicationsCard.post { applicationsCard.requestFocus() }
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
            isFocusable = true
            isClickable = true
            installAnimatedCardFocus(accent, titleView)
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

    private fun View.installAnimatedCardFocus(accent: Int, titleView: TextView) {
        val cardDrawable = roundedDrawable(Color.rgb(28, 34, 43), 16).apply {
            setStroke(dp(2), Color.TRANSPARENT)
        }
        background = cardDrawable
        var focusFraction = 0f
        var focusAnimator: ValueAnimator? = null
        setOnFocusChangeListener { _, hasFocus ->
            titleView.isSelected = hasFocus
            focusAnimator?.cancel()
            val target = if (hasFocus) 1f else 0f
            focusAnimator = ValueAnimator.ofFloat(focusFraction, target).apply {
                duration = 210
                interpolator = AccelerateDecelerateInterpolator()
                addUpdateListener {
                    focusFraction = it.animatedValue as Float
                    cardDrawable.setColor(
                        blendColor(
                            Color.rgb(28, 34, 43),
                            accent,
                            focusFraction * 0.16f,
                        ),
                    )
                    cardDrawable.setStroke(
                        dp(2),
                        Color.argb(
                            (220 * focusFraction).toInt(),
                            Color.red(accent),
                            Color.green(accent),
                            Color.blue(accent),
                        ),
                    )
                    translationZ = dp(7) * focusFraction
                }
                start()
            }
            animate()
                .scaleX(if (hasFocus) 1.02f else 1f)
                .scaleY(if (hasFocus) 1.02f else 1f)
                .setDuration(210)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .start()
        }
    }

    private fun blendColor(from: Int, to: Int, fraction: Float): Int {
        val amount = fraction.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(from) + (Color.red(to) - Color.red(from)) * amount).toInt(),
            (Color.green(from) + (Color.green(to) - Color.green(from)) * amount).toInt(),
            (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * amount).toInt(),
        )
    }

    private fun refreshStatus() {
        rootHookManager.queryStateAsync { state ->
            postIfAlive {
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
        tasks.execute {
            val packages = runCatching { packageManager.getInstalledApplications(0).size }.getOrDefault(0)
            val ip = localIpv4Address()
            tasks.post {
                deviceStatus.text = "${Build.MODEL} • Android ${Build.VERSION.RELEASE} • $packages пакетов • $ip"
            }
        }
    }

    private fun ensureAdbAsync(showResult: Boolean) {
        tasks.execute {
            val result = PrivilegeManager(this).ensureAdb()
            if (!result.success) {
                RecoveryDialog.show(this, result.message) {
                    ensureAdbAsync(showResult = true)
                }
            } else if (showResult) {
                tasks.post {
                    Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun refreshUpdateStatus() {
        val installed = installedVersionName()
        updateStateStore.consumeCompletedVersion()?.let { version ->
            showUpdateNotice(
                text = "Обновлено до v$version",
                color = Color.rgb(129, 216, 161),
            )
        }
        updateStateStore.cachedLatestVersion()
            ?.takeIf { VersionPolicy.isNewer(it, installed) }
            ?.let(::showAvailableUpdate)
        if (!updateStateStore.shouldRefresh()) return

        tasks.execute {
            runCatching { GitHubUpdateClient(this).latestRelease() }
                .onSuccess { latest ->
                    updateStateStore.recordCheck(latest.versionName)
                    if (VersionPolicy.isNewer(latest.versionName, installed)) {
                        tasks.post { showAvailableUpdate(latest.versionName) }
                    }
                }
        }
    }

    private fun showAvailableUpdate(version: String) {
        versionStatus.text = "↓ v$version"
        versionStatus.setTextColor(Color.rgb(129, 216, 161))
        if (updateStateStore.shouldShowAvailableNotice(version)) {
            showUpdateNotice(
                text = "Доступно обновление v$version  •  открыть OTA",
                color = Color.rgb(94, 184, 255),
            )
        }
    }

    private fun showUpdateNotice(text: String, color: Int) {
        updateNotice.text = text
        updateNotice.setTextColor(color)
        updateNotice.alpha = 0f
        updateNotice.translationY = -dp(8).toFloat()
        updateNotice.visibility = View.VISIBLE
        updateNotice.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(220)
            .start()
    }

    private fun openUpdateScreen() {
        updateStateStore.cachedLatestVersion()?.let(updateStateStore::acknowledgeAvailableNotice)
        updateNotice.visibility = View.GONE
        startActivity(Intent(this, UpdateActivity::class.java))
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

    private fun rounded(color: Int, radiusDp: Int): GradientDrawable =
        roundedDrawable(color, radiusDp)

    @Suppress("DEPRECATION")
    private fun installedVersionName(): String =
        packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()

}
