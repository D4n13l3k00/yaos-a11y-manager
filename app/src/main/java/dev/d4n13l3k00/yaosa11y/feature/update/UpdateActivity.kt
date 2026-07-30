package dev.d4n13l3k00.yaosa11y.feature.update

import android.annotation.SuppressLint
import android.app.Activity
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.d4n13l3k00.yaosa11y.R
import dev.d4n13l3k00.yaosa11y.core.ui.ActivityTaskScope
import dev.d4n13l3k00.yaosa11y.core.ui.TvColors
import dev.d4n13l3k00.yaosa11y.core.ui.applyTvActionStyle
import dev.d4n13l3k00.yaosa11y.core.ui.applyTvScreenInsets
import dev.d4n13l3k00.yaosa11y.core.ui.applyTvScreenSubtitleStyle
import dev.d4n13l3k00.yaosa11y.core.ui.applyTvScreenTitleStyle
import dev.d4n13l3k00.yaosa11y.core.ui.dp
import dev.d4n13l3k00.yaosa11y.core.ui.roundedDrawable
import java.io.File
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@SuppressLint("SetTextI18n")
class UpdateActivity : Activity() {
    private val tasks = ActivityTaskScope(this)
    private lateinit var client: GitHubUpdateClient
    private lateinit var verifier: UpdateVerifier
    private lateinit var installAccess: InstallSourceAccess
    private lateinit var stateStore: UpdateStateStore

    private lateinit var versionView: TextView
    private lateinit var statusView: TextView
    private lateinit var stageViews: List<TextView>
    private lateinit var progressBar: OtaProgressView
    private lateinit var progressDetails: TextView
    private lateinit var securityView: TextView
    private lateinit var notesTitleView: TextView
    private lateinit var notesView: TextView
    private lateinit var notesScroll: ScrollView
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var backButton: TextView
    private lateinit var checkButton: TextView
    private lateinit var installButton: TextView
    private lateinit var permissionButton: TextView

    private var release: UpdateRelease? = null
    private var verifiedUpdate: VerifiedUpdate? = null
    private var operationInFlight = false
    private var waitingForInstallSource = false
    private var activeStageIndex = 0
    private var stagePulse: ValueAnimator? = null
    private val timeFormat by lazy { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    private val byteFormat = DecimalFormat("0.0")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        client = GitHubUpdateClient(this)
        verifier = UpdateVerifier(this)
        installAccess = InstallSourceAccess(this)
        stateStore = UpdateStateStore(this)
        setContentView(createContent())
        checkForUpdates()
    }

    override fun onResume() {
        super.onResume()
        if (::progressBar.isInitialized) progressBar.onHostResumed()
        restartStagePulse()
        stateStore.consumeInstallerResult()?.let { message ->
            appendLog(message)
            statusView.text = message
            statusView.setTextColor(
                if ("установлено" in message.lowercase()) TvColors.success else TvColors.error,
            )
        }
        if (waitingForInstallSource && installAccess.isAllowed() && !operationInFlight) {
            waitingForInstallSource = false
            permissionButton.visibility = View.GONE
            configureFocusNavigation(permissionVisible = false)
            installVerifiedUpdate()
        }
    }

    override fun onPause() {
        stagePulse?.cancel()
        if (::progressBar.isInitialized) progressBar.onHostPaused()
        super.onPause()
    }

    override fun onDestroy() {
        stagePulse?.cancel()
        tasks.close()
        super.onDestroy()
    }

    private fun createContent(): View {
        val root = FrameLayout(this).apply {
            setBackgroundColor(TvColors.background)
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
            text = "Обновление приложения"
            applyTvScreenTitleStyle(R.drawable.ic_update)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        backButton = actionButton("Назад", R.drawable.ic_back) { finish() }
        header.addView(backButton)
        panel.addView(header)

        panel.addView(TextView(this).apply {
            text = "Проверка, загрузка и установка новых версий приложения"
            applyTvScreenSubtitleStyle()
        })

        val columns = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        columns.addView(
            createProgressPanel(),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.95f).apply {
                marginEnd = dp(18)
            },
        )
        columns.addView(
            createReleasePanel(),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.25f),
        )
        panel.addView(
            columns,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(0, dp(14), 0, 0)
        }
        checkButton = actionButton("Проверить снова", R.drawable.ic_refresh) {
            checkForUpdates()
        }
        installButton = actionButton("Скачать и установить", R.drawable.ic_update) {
            downloadAndInstall()
        }
        permissionButton = actionButton("Разрешить установку", R.drawable.ic_settings) {
            waitingForInstallSource = true
            startActivity(installAccess.settingsIntent())
        }.apply {
            visibility = View.GONE
        }
        actions.addView(checkButton)
        actions.addView(permissionButton, actionMargin())
        actions.addView(installButton, actionMargin())
        configureFocusNavigation(permissionVisible = false)
        panel.addView(actions)

        root.addView(
            panel,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        setInstallEnabled(false)
        return root
    }

    private fun createProgressPanel(): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedDrawable(TvColors.surface, 16)
            setPadding(dp(26), dp(14), dp(26), dp(14))

            addView(sectionTitle("Состояние обновления").apply {
                setPadding(0, 0, 0, dp(5))
            })
            versionView = TextView(this@UpdateActivity).apply {
                text = "Установлено: v${installedVersionName()}\nПоследняя версия: проверка…"
                textSize = 16f
                setTextColor(Color.WHITE)
                setLineSpacing(0f, 1.1f)
                setPadding(0, 0, 0, dp(6))
            }
            addView(versionView)
            statusView = TextView(this@UpdateActivity).apply {
                text = "Подключение к GitHub…"
                textSize = 14f
                setTextColor(TvColors.warning)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, 0, 0, dp(6))
            }
            addView(statusView)

            stageViews = listOf(
                stage("1", "Проверка релиза"),
                stage("2", "Загрузка APK"),
                stage("3", "SHA-256 и подпись"),
                stage("4", "Установка"),
            )
            stageViews.forEach(::addView)

            progressBar = OtaProgressView(this@UpdateActivity).apply {
                visibility = View.INVISIBLE
            }
            addView(
                progressBar,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(20)).apply {
                    topMargin = dp(8)
                },
            )
            progressDetails = TextView(this@UpdateActivity).apply {
                text = ""
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.rgb(94, 184, 255))
                setPadding(0, dp(4), 0, 0)
            }
            addView(progressDetails)
            securityView = TextView(this@UpdateActivity).apply {
                text = "Проверки APK ещё не выполнены"
                textSize = 12f
                setTextColor(TvColors.secondaryText)
                setLineSpacing(0f, 1.08f)
                setPadding(0, dp(6), 0, 0)
            }
            addView(securityView)
        }

    private fun createReleasePanel(): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedDrawable(TvColors.surface, 16)
            setPadding(dp(26), dp(20), dp(26), dp(20))

            notesTitleView = sectionTitle("Изменения в релизе")
            addView(notesTitleView)
            notesScroll = ScrollView(this@UpdateActivity).apply {
                isFillViewport = true
                configureDpadScrolling()
            }
            notesView = TextView(this@UpdateActivity).apply {
                text = "Описание появится после проверки GitHub Release."
                textSize = 14f
                setTextColor(TvColors.secondaryText)
                setLineSpacing(0f, 1.12f)
            }
            notesScroll.addView(notesView)
            addView(
                notesScroll,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.58f),
            )

            addView(sectionTitle("Журнал").apply {
                setPadding(0, dp(12), 0, dp(8))
            })
            logScroll = ScrollView(this@UpdateActivity).apply {
                isFillViewport = true
                configureDpadScrolling()
            }
            logView = TextView(this@UpdateActivity).apply {
                text = ""
                textSize = 13f
                typeface = Typeface.MONOSPACE
                setTextColor(Color.rgb(181, 197, 211))
                setLineSpacing(0f, 1.12f)
            }
            logScroll.addView(logView)
            addView(
                logScroll,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.42f),
            )
        }

    private fun checkForUpdates() {
        if (operationInFlight) return
        operationInFlight = true
        release = null
        verifiedUpdate?.file?.delete()
        verifiedUpdate = null
        setInstallEnabled(false)
        setActionsEnabled(false)
        setStage(0)
        progressBar.visibility = View.INVISIBLE
        progressBar.reset()
        progressDetails.text = ""
        securityView.text = "Проверки APK ещё не выполнены"
        securityView.setTextColor(TvColors.secondaryText)
        statusView.text = "Проверка последнего GitHub Release…"
        statusView.setTextColor(TvColors.warning)
        appendLog("Проверка обновлений")
        tasks.execute {
            runCatching { client.latestRelease() }
                .onSuccess { latest ->
                    stateStore.recordCheck(latest.versionName)
                    tasks.post {
                        operationInFlight = false
                        setActionsEnabled(true)
                        setStage(1)
                        release = latest
                        versionView.text =
                            "Установлено: v${installedVersionName()}\n" +
                                "Последний релиз: ${latest.tagName}"
                        notesTitleView.text = "Изменения в ${latest.tagName}"
                        notesView.text = readableNotes(latest.notes)
                        notesScroll.post { notesScroll.scrollTo(0, 0) }
                        if (VersionPolicy.isNewer(latest.versionName, installedVersionName())) {
                            statusView.text = "Доступно обновление ${latest.tagName}"
                            statusView.setTextColor(TvColors.success)
                            appendLog("Найдена новая версия ${latest.tagName}")
                            setInstallEnabled(true)
                        } else {
                            setStage(completedCount = 1, activeIndex = -1)
                            statusView.text = "Установлена актуальная версия"
                            statusView.setTextColor(TvColors.success)
                            appendLog("Новых версий нет")
                            setInstallEnabled(false)
                        }
                    }
                }
                .onFailure { error ->
                    tasks.post {
                        operationInFlight = false
                        setActionsEnabled(true)
                        statusView.text = "Не удалось проверить обновления"
                        statusView.setTextColor(TvColors.error)
                        appendLog("Ошибка: ${error.message ?: error.javaClass.simpleName}")
                    }
                }
        }
    }

    private fun downloadAndInstall() {
        val target = release ?: return
        if (operationInFlight || !VersionPolicy.isNewer(target.versionName, installedVersionName())) {
            return
        }
        operationInFlight = true
        setActionsEnabled(false)
        setInstallEnabled(false)
        setStage(1)
        progressBar.visibility = View.VISIBLE
        progressBar.reset()
        progressBar.startDownload()
        progressDetails.text = "0%  •  подготовка загрузки"
        statusView.text = "Загрузка версии ${target.tagName}…"
        statusView.setTextColor(TvColors.warning)
        appendLog("Загрузка версии ${target.tagName} (${formatBytes(target.apkSize)})")

        tasks.execute {
            var lastUpdateAt = 0L
            var lastBytes = 0L
            var speedBytes = 0L
            runCatching {
                val file = client.download(target) { downloaded, totalFromServer ->
                    val now = System.currentTimeMillis()
                    val elapsed = now - lastUpdateAt
                    if (lastUpdateAt == 0L || elapsed >= 250 || downloaded >= target.apkSize) {
                        if (lastUpdateAt != 0L && elapsed > 0) {
                            speedBytes = ((downloaded - lastBytes) * 1000L / elapsed).coerceAtLeast(0)
                        }
                        lastUpdateAt = now
                        lastBytes = downloaded
                        val total = totalFromServer.takeIf { it > 0 } ?: target.apkSize
                        tasks.post {
                            val fraction = downloaded.toDouble() / total.coerceAtLeast(1)
                            progressBar.setProgressAnimated(fraction.toFloat())
                            progressDetails.text =
                                "${(fraction * 100).toInt().coerceIn(0, 100)}%  •  " +
                                    "${formatBytes(downloaded)} из ${formatBytes(total)}" +
                                    if (speedBytes > 0) "  •  ${formatBytes(speedBytes)}/с" else ""
                        }
                    }
                }
                tasks.post {
                    setStage(2)
                    statusView.text = "Проверка целостности и подписи…"
                    appendLog("Загрузка завершена, проверка APK")
                }
                verifier.verify(target, file)
            }.onSuccess { verified ->
                verifiedUpdate = verified
                tasks.post {
                    operationInFlight = false
                    progressBar.finishSuccess()
                    setStage(3)
                    securityView.text =
                        "✓ SHA-256 совпал\n" +
                            "✓ Пакет совпал\n" +
                            "✓ Версия v${verified.versionName} новее\n" +
                            "✓ Сертификат подписи совпал"
                    securityView.setTextColor(TvColors.success)
                    statusView.text = "APK проверен и готов к установке"
                    statusView.setTextColor(TvColors.success)
                    appendLog("SHA-256 и сертификат подписи подтверждены")
                    requestInstallAccess()
                }
            }.onFailure { error ->
                tasks.post {
                    operationInFlight = false
                    setActionsEnabled(true)
                    setInstallEnabled(true)
                    progressBar.finishError()
                    statusView.text = "Обновление отклонено"
                    statusView.setTextColor(TvColors.error)
                    securityView.text = error.message ?: error.javaClass.simpleName
                    securityView.setTextColor(TvColors.error)
                    appendLog("Ошибка проверки: ${error.message}")
                }
            }
        }
    }

    private fun requestInstallAccess() {
        if (installAccess.isAllowed()) {
            installVerifiedUpdate()
            return
        }
        operationInFlight = true
        statusView.text = "Подготовка разрешения на установку…"
        statusView.setTextColor(TvColors.warning)
        appendLog("Проверка разрешения «Установка неизвестных приложений»")
        tasks.execute {
            val result = installAccess.tryGrantThroughAvailableBackend()
            tasks.post {
                operationInFlight = false
                appendLog(result.message)
                if (result.allowed) {
                    installVerifiedUpdate()
                } else {
                    waitingForInstallSource = true
                    permissionButton.visibility = View.VISIBLE
                    configureFocusNavigation(permissionVisible = true)
                    permissionButton.requestFocus()
                    setActionsEnabled(true)
                    installButton.isEnabled = false
                    installButton.alpha = 0.45f
                    statusView.text = "Разрешите установку из этого источника"
                    statusView.setTextColor(TvColors.warning)
                    securityView.append("\n\nAndroid ожидает разрешение источнику обновлений")
                    securityView.setTextColor(TvColors.warning)
                }
            }
        }
    }

    private fun installVerifiedUpdate() {
        val update = verifiedUpdate ?: return
        if (operationInFlight) return
        operationInFlight = true
        permissionButton.visibility = View.GONE
        configureFocusNavigation(permissionVisible = false)
        setActionsEnabled(false)
        setStage(3)
        statusView.text = "Подготовка системного установщика…"
        statusView.setTextColor(TvColors.warning)
        appendLog("Передача APK в Android Package Installer")
        tasks.execute {
            runCatching { SystemUpdateInstaller(this).install(update) }
                .onSuccess {
                    verifiedUpdate = null
                    tasks.post {
                        operationInFlight = false
                        setStage(4)
                        statusView.text = "Подтвердите обновление в окне Android"
                        statusView.setTextColor(TvColors.warning)
                        appendLog("Ожидание системного подтверждения")
                    }
                }
                .onFailure { error ->
                    tasks.post {
                        operationInFlight = false
                        setActionsEnabled(true)
                        setInstallEnabled(true)
                        statusView.text = "Не удалось запустить установку"
                        statusView.setTextColor(TvColors.error)
                        appendLog("Ошибка установщика: ${error.message}")
                    }
                }
        }
    }

    private fun setStage(completedCount: Int, activeIndex: Int = completedCount) {
        activeStageIndex = activeIndex
        stagePulse?.cancel()
        stageViews.forEachIndexed { index, view ->
            val completed = index < completedCount
            val active = index == activeIndex && activeIndex in stageViews.indices
            view.text = when {
                completed -> "✓   ${STAGE_TITLES[index]}"
                active -> "●   ${STAGE_TITLES[index]}"
                else -> "${index + 1}   ${STAGE_TITLES[index]}"
            }
            view.setTextColor(
                when {
                    completed -> TvColors.success
                    active -> TvColors.secondaryText
                    else -> TvColors.secondaryText
                },
            )
            view.alpha = if (completed || active) 1f else 0.68f
            view.scaleX = 1f
            view.scaleY = 1f
        }
        restartStagePulse()
    }

    private fun restartStagePulse() {
        stagePulse?.cancel()
        if (!::stageViews.isInitialized || activeStageIndex !in stageViews.indices) return
        val view = stageViews[activeStageIndex]
        stagePulse = ValueAnimator.ofObject(
            ArgbEvaluator(),
            TvColors.secondaryText,
            TvColors.success,
        ).apply {
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener { view.setTextColor(it.animatedValue as Int) }
            duration = 900
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun setActionsEnabled(enabled: Boolean) {
        checkButton.isEnabled = enabled
        checkButton.alpha = if (enabled) 1f else 0.45f
    }

    private fun setInstallEnabled(enabled: Boolean) {
        installButton.isEnabled = enabled
        installButton.alpha = if (enabled) 1f else 0.45f
    }

    private fun stage(number: String, title: String): TextView =
        TextView(this).apply {
            text = "$number   $title"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(TvColors.secondaryText)
            setPadding(dp(8), dp(2), 0, dp(2))
        }

    private fun sectionTitle(text: String): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, dp(10))
        }

    private fun actionButton(text: String, iconRes: Int, action: () -> Unit): TextView =
        TextView(this).apply {
            id = View.generateViewId()
            this.text = text
            applyTvActionStyle(iconRes, horizontalPaddingDp = 20, verticalPaddingDp = 11)
            setOnClickListener { action() }
        }

    private fun configureFocusNavigation(permissionVisible: Boolean) {
        backButton.nextFocusLeftId = backButton.id
        backButton.nextFocusUpId = backButton.id
        backButton.nextFocusRightId = notesScroll.id
        backButton.nextFocusDownId = notesScroll.id

        notesScroll.nextFocusLeftId = backButton.id
        notesScroll.nextFocusUpId = backButton.id
        notesScroll.nextFocusRightId = logScroll.id
        notesScroll.nextFocusDownId = logScroll.id

        logScroll.nextFocusLeftId = notesScroll.id
        logScroll.nextFocusUpId = notesScroll.id
        logScroll.nextFocusRightId = checkButton.id
        logScroll.nextFocusDownId = checkButton.id

        checkButton.nextFocusLeftId = logScroll.id
        checkButton.nextFocusUpId = logScroll.id
        checkButton.nextFocusDownId = checkButton.id

        permissionButton.nextFocusUpId = logScroll.id
        permissionButton.nextFocusDownId = permissionButton.id
        installButton.nextFocusUpId = logScroll.id
        installButton.nextFocusRightId = installButton.id
        installButton.nextFocusDownId = installButton.id

        if (permissionVisible) {
            checkButton.nextFocusRightId = permissionButton.id
            permissionButton.nextFocusLeftId = checkButton.id
            permissionButton.nextFocusRightId = installButton.id
            installButton.nextFocusLeftId = permissionButton.id
        } else {
            checkButton.nextFocusRightId = installButton.id
            permissionButton.nextFocusLeftId = checkButton.id
            permissionButton.nextFocusRightId = installButton.id
            installButton.nextFocusLeftId = checkButton.id
        }
    }

    private fun actionMargin(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            marginStart = dp(12)
        }

    private fun ScrollView.configureDpadScrolling() {
        id = View.generateViewId()
        isFocusable = true
        isFocusableInTouchMode = true
        isVerticalFadingEdgeEnabled = true
        setFadingEdgeLength(dp(22))
        background = scrollFocusBackground()
        setPadding(dp(10), dp(7), dp(10), dp(7))
        clipToPadding = false
        setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (!canScrollVertically(1)) return@setOnKeyListener false
                    smoothScrollBy(0, height.coerceAtLeast(dp(120)) * 3 / 5)
                    true
                }
                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (!canScrollVertically(-1)) return@setOnKeyListener false
                    smoothScrollBy(0, -height.coerceAtLeast(dp(120)) * 3 / 5)
                    true
                }
                else -> false
            }
        }
    }

    private fun scrollFocusBackground(): StateListDrawable =
        StateListDrawable().apply {
            addState(
                intArrayOf(android.R.attr.state_focused),
                roundedDrawable(Color.rgb(35, 47, 59), 8).apply {
                    setStroke(dp(2), Color.rgb(94, 184, 255))
                },
            )
            addState(
                intArrayOf(),
                roundedDrawable(Color.TRANSPARENT, 8),
            )
        }

    private fun appendLog(message: String) {
        val line = "[${timeFormat.format(Date())}] $message"
        logView.append(if (logView.text.isEmpty()) line else "\n$line")
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun readableNotes(markdown: String): String {
        if (markdown.isBlank()) return "Для этого релиза описание не добавлено."
        return markdown
            .lineSequence()
            .map { line ->
                line.replace(Regex("""^\s{0,3}#{1,6}\s*"""), "")
                    .replace(Regex("""\[(.+?)]\((.+?)\)"""), "$1")
                    .replace("**", "")
                    .replace('`', ' ')
            }
            .dropWhile {
                it.isBlank() ||
                    it.trim().equals("Что нового", ignoreCase = true) ||
                    it.trim().equals("What's Changed", ignoreCase = true)
            }
            .joinToString("\n")
            .trim()
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes Б"
        val units = arrayOf("КБ", "МБ", "ГБ")
        var value = bytes.toDouble()
        var unit = -1
        while (value >= 1024 && unit < units.lastIndex) {
            value /= 1024
            unit++
        }
        return "${byteFormat.format(value)} ${units[unit]}"
    }

    @Suppress("DEPRECATION")
    private fun installedVersionName(): String =
        packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()

    companion object {
        private val STAGE_TITLES = listOf(
            "Проверка релиза",
            "Загрузка APK",
            "SHA-256 и подпись",
            "Установка",
        )
    }

}
