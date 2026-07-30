package dev.d4n13l3k00.yaosa11y.feature.apps

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import dev.d4n13l3k00.yaosa11y.R
import dev.d4n13l3k00.yaosa11y.core.ui.ActivityTaskScope
import dev.d4n13l3k00.yaosa11y.core.ui.applyTvActionStyle
import dev.d4n13l3k00.yaosa11y.core.ui.applyTvScreenInsets
import dev.d4n13l3k00.yaosa11y.core.ui.applyTvScreenSubtitleStyle
import dev.d4n13l3k00.yaosa11y.core.ui.applyTvScreenTitleStyle
import dev.d4n13l3k00.yaosa11y.core.ui.dp
import dev.d4n13l3k00.yaosa11y.core.ui.postIfAlive
import dev.d4n13l3k00.yaosa11y.core.ui.redirectDpadLeftTo
import dev.d4n13l3k00.yaosa11y.core.ui.roundedDrawable
import dev.d4n13l3k00.yaosa11y.core.ui.tvFocusBackground
import dev.d4n13l3k00.yaosa11y.feature.recovery.RecoveryDialog

class AppManagerActivity : Activity() {
    private val tasks = ActivityTaskScope(this)
    private enum class Filter(val title: String) {
        ALL("Все"),
        USER("Пользовательские"),
        SYSTEM("Системные"),
        FROZEN("Замороженные"),
        REMOVED("Удалённые"),
    }

    private lateinit var controller: AppManagerController
    private lateinit var statusView: TextView
    private lateinit var appList: LinearLayout
    private lateinit var search: EditText
    private lateinit var filterButton: Button
    private lateinit var cleanupButton: Button
    private lateinit var presetsButton: Button
    private val operationButtons = ArrayList<Button>()
    private var apps: List<ManagedApp> = emptyList()
    private var filter = Filter.ALL
    private var pendingInitialFocus = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        controller = AppManagerController(this)
        setContentView(createContent())
        pendingInitialFocus = savedInstanceState == null
        if (pendingInitialFocus) search.isFocusable = false
        refreshApps()
    }

    override fun onDestroy() {
        tasks.close()
        controller.close()
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
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(TextView(this).apply {
            text = "Менеджер приложений"
            applyTvScreenTitleStyle(R.drawable.ic_apps)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        titleRow.addView(
            actionButton("Назад", R.drawable.ic_back) { finish() }.apply {
                (layoutParams as LinearLayout.LayoutParams).marginEnd = 0
            },
        )
        panel.addView(titleRow)

        statusView = TextView(this).apply {
            text = "Загрузка списка…"
            applyTvScreenSubtitleStyle()
        }
        panel.addView(statusView)

        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        search = EditText(this).apply {
            id = View.generateViewId()
            hint = "Поиск по названию или пакету"
            setHintTextColor(Color.rgb(126, 140, 154))
            setTextColor(Color.WHITE)
            textSize = 15f
            setSingleLine(true)
            background = rounded(Color.rgb(28, 34, 43), 10)
            setPadding(dp(18), 0, dp(18), 0)
            setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_search, 0, 0, 0)
            compoundDrawablePadding = dp(12)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    renderApps()
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        toolbar.addView(search, LinearLayout.LayoutParams(0, dp(54), 1f).apply {
            marginEnd = dp(10)
        })
        filterButton = actionButton("Фильтр: ${filter.title}", R.drawable.ic_filter) {
            filter = Filter.entries[(filter.ordinal + 1) % Filter.entries.size]
            filterButton.text = "Фильтр: ${filter.title}"
            renderApps()
        }
        filterButton.id = View.generateViewId()
        toolbar.addView(filterButton)
        toolbar.addView(actionButton("Обновить", R.drawable.ic_refresh) { refreshApps() })
        panel.addView(toolbar)

        val cleanupRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
        }
        cleanupButton = actionButton(
            "Очистить кэш всех приложений",
            R.drawable.ic_cache,
        ) {
            confirmGlobalCacheCleanup()
        }.apply {
            id = View.generateViewId()
            nextFocusUpId = filterButton.id
        }
        cleanupRow.addView(cleanupButton)
        presetsButton = actionButton(
            "Пресеты отключения",
            R.drawable.ic_shield_off,
        ) {
            startActivity(Intent(this, PackagePresetsActivity::class.java))
        }.apply {
            id = View.generateViewId()
            nextFocusUpId = filterButton.id
            nextFocusLeftId = cleanupButton.id
        }
        cleanupButton.nextFocusRightId = presetsButton.id
        cleanupRow.addView(presetsButton)
        for (index in 0 until toolbar.childCount) {
            toolbar.getChildAt(index).nextFocusDownId = cleanupButton.id
        }
        cleanupButton.nextFocusLeftId = cleanupButton.id
        panel.addView(cleanupRow)

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setPadding(0, dp(16), 0, 0)
        }
        appList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        scroll.addView(
            appList,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        panel.addView(
            scroll,
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

    private fun refreshApps() {
        setBusy(true)
        statusView.text = "Чтение установленных пакетов…"
        tasks.execute {
            val result = runCatching { controller.loadApps() }
            tasks.post {
                setBusy(false)
                result.onSuccess {
                    apps = it
                    renderApps()
                }.onFailure {
                    statusView.text = "Ошибка: ${it.message}"
                    statusView.setTextColor(Color.rgb(255, 128, 128))
                }
                restoreInitialFocus()
            }
        }
    }

    private fun restoreInitialFocus() {
        if (!pendingInitialFocus) return
        pendingInitialFocus = false
        search.isFocusable = true
        filterButton.requestFocus()
    }

    private fun renderApps() {
        if (!::appList.isInitialized) return
        val query = search.text?.toString().orEmpty().trim()
        val visible = apps.filter { app ->
            val matchesFilter = when (filter) {
                Filter.ALL -> true
                Filter.USER -> !app.system && app.installedForUser
                Filter.SYSTEM -> app.system && app.installedForUser
                Filter.FROZEN -> app.installedForUser && !app.enabled
                Filter.REMOVED -> !app.installedForUser
            }
            val matchesQuery = query.isBlank() ||
                app.label.contains(query, ignoreCase = true) ||
                app.packageName.contains(query, ignoreCase = true)
            matchesFilter && matchesQuery
        }
        val installed = apps.count { it.installedForUser }
        val frozen = apps.count { it.installedForUser && !it.enabled }
        statusView.text =
            "Пакетов: ${apps.size} • установлено: $installed • заморожено: $frozen • показано: ${visible.size}"
        statusView.setTextColor(Color.rgb(174, 185, 196))
        appList.removeAllViews()
        visible.forEach { appList.addView(appRow(it)) }
        if (appList.childCount > 0) {
            val firstRowId = appList.getChildAt(0).id
            cleanupButton.nextFocusRightId = presetsButton.id
            cleanupButton.nextFocusDownId = firstRowId
            presetsButton.nextFocusRightId = firstRowId
            presetsButton.nextFocusDownId = firstRowId
        }
    }

    private fun appRow(app: ManagedApp): View {
        val row = LinearLayout(this).apply {
            id = View.generateViewId()
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(12))
            background = focusBackground()
            isFocusable = true
            isClickable = true
            redirectDpadLeftTo(cleanupButton)
            setOnClickListener { showAppActions(app) }
        }
        row.addView(ImageView(this).apply {
            setImageDrawable(
                runCatching { app.applicationInfo.loadIcon(packageManager) }
                    .getOrElse { getDrawable(R.drawable.app_icon) },
            )
            scaleType = ImageView.ScaleType.FIT_CENTER
            alpha = if (app.enabled) 1f else 0.55f
        }, LinearLayout.LayoutParams(dp(46), dp(46)).apply {
            marginEnd = dp(16)
        })
        val labels = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val nameView = TextView(this).apply {
            text = app.label
            setTextColor(if (app.installedForUser) Color.WHITE else Color.rgb(150, 160, 170))
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.MARQUEE
            marqueeRepeatLimit = -1
            isHorizontalFadingEdgeEnabled = true
        }
        labels.addView(nameView)
        val packageView = TextView(this).apply {
            text = buildString {
                append(app.packageName)
                if (app.versionName.isNotBlank()) append(" • ").append(app.versionName)
            }
            setTextColor(Color.rgb(153, 166, 179))
            textSize = 12f
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.MARQUEE
            marqueeRepeatLimit = -1
            isHorizontalFadingEdgeEnabled = true
        }
        labels.addView(packageView)
        row.addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(TextView(this).apply {
            text = when {
                !app.installedForUser -> "УДАЛЕНО"
                !app.enabled -> "ЗАМОРОЖЕНО"
                app.system -> "СИСТЕМА"
                else -> "ПРИЛОЖЕНИЕ"
            }
            setTextColor(
                when {
                    !app.installedForUser -> Color.rgb(255, 183, 77)
                    !app.enabled -> Color.rgb(120, 184, 255)
                    app.system -> Color.rgb(177, 187, 197)
                    else -> Color.rgb(129, 216, 161)
                },
            )
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = rounded(Color.rgb(49, 57, 67), 16)
            setPadding(dp(14), dp(7), dp(14), dp(7))
        })
        row.setOnFocusChangeListener { _, hasFocus ->
            nameView.isSelected = hasFocus
            packageView.isSelected = hasFocus
            if (hasFocus) {
                presetsButton.nextFocusRightId = row.id
            }
        }
        return row.apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin = dp(8)
            }
        }
    }

    private fun showAppActions(app: ManagedApp) {
        val actions = ArrayList<Pair<String, (() -> Unit)>>()
        if (app.installedForUser) {
            packageManager.getLaunchIntentForPackage(app.packageName)?.let { intent ->
                actions += "Открыть" to { startActivity(intent) }
            }
            actions += if (app.enabled) {
                "Заморозить" to {
                    confirmOperation(app, AppManagerController.Operation.FREEZE)
                }
            } else {
                "Разморозить" to {
                    runOperation(app, AppManagerController.Operation.UNFREEZE)
                }
            }
            actions += "Остановить" to {
                runOperation(app, AppManagerController.Operation.FORCE_STOP)
            }
            actions += "Очистить кэш" to {
                runOperation(app, AppManagerController.Operation.CLEAR_CACHE)
            }
            actions += "Стереть данные" to {
                confirmOperation(app, AppManagerController.Operation.CLEAR_DATA)
            }
            actions += "Удалить для пользователя" to {
                confirmOperation(app, AppManagerController.Operation.UNINSTALL_FOR_USER)
            }
            if (!app.system) {
                actions += "Удалить APK полностью" to {
                    confirmOperation(app, AppManagerController.Operation.UNINSTALL_COMPLETELY)
                }
            }
        } else {
            actions += "Восстановить для пользователя" to {
                runOperation(app, AppManagerController.Operation.RESTORE_FOR_USER)
            }
        }
        actions += "Сведения о пакете" to { showPackageInfo(app) }

        AlertDialog.Builder(this)
            .setTitle(app.label)
            .setItems(actions.map { it.first }.toTypedArray()) { _, index ->
                actions[index].second.invoke()
            }
            .setNegativeButton("Закрыть", null)
            .show()
    }

    private fun confirmOperation(app: ManagedApp, operation: AppManagerController.Operation) {
        val critical = app.packageName in CRITICAL_PACKAGES
        val message = when (operation) {
            AppManagerController.Operation.FREEZE ->
                "Приложение перестанет запускаться и выполнять фоновые задачи."
            AppManagerController.Operation.CLEAR_DATA ->
                "Будут безвозвратно удалены настройки, аккаунты и файлы приложения."
            AppManagerController.Operation.UNINSTALL_FOR_USER ->
                if (app.system) {
                    "Системное приложение исчезнет для текущего пользователя. Его APK останется в прошивке, поэтому пакет можно будет восстановить."
                } else {
                    "Приложение и его данные будут удалены из текущего профиля."
                }
            AppManagerController.Operation.UNINSTALL_COMPLETELY ->
                "APK и данные приложения будут удалены полностью."
            else -> "Выполнить операцию?"
        } + if (critical) {
            "\n\nВНИМАНИЕ: это критический компонент Android/YAOS. Операция может нарушить интерфейс или загрузку телевизора."
        } else {
            ""
        }
        AlertDialog.Builder(this)
            .setTitle("${operationTitle(operation)}: ${app.label}?")
            .setMessage(message)
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Выполнить") { _, _ -> runOperation(app, operation) }
            .show()
    }

    private fun runOperation(app: ManagedApp, operation: AppManagerController.Operation) {
        setBusy(true)
        statusView.text = "${operationTitle(operation)}: ${app.label}…"
        controller.runAsync(operation, app) { result ->
            postIfAlive {
                setBusy(false)
                Toast.makeText(
                    this,
                    result.message,
                    if (result.success) Toast.LENGTH_SHORT else Toast.LENGTH_LONG,
                ).show()
                refreshApps()
            }
        }
    }

    private fun confirmGlobalCacheCleanup() {
        AlertDialog.Builder(this)
            .setTitle("Очистить кэш всех приложений?")
            .setMessage("Личные данные и настройки приложений затронуты не будут.")
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Очистить") { _, _ ->
                setBusy(true)
                controller.runAsync(AppManagerController.Operation.TRIM_ALL_CACHES) { result ->
                    postIfAlive {
                        setBusy(false)
                        Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
                    }
                }
            }
            .show()
    }

    private fun showPackageInfo(app: ManagedApp) {
        val message = buildString {
            appendLine(app.packageName)
            appendLine()
            appendLine("Версия: ${app.versionName.ifBlank { "не указана" }}")
            appendLine("Тип: ${if (app.system) "системное" else "пользовательское"}")
            appendLine("Для пользователя: ${if (app.installedForUser) "установлено" else "удалено"}")
            appendLine("Состояние: ${if (app.enabled) "включено" else "отключено"}")
            append("APK: ${app.applicationInfo.sourceDir}")
        }
        AlertDialog.Builder(this)
            .setTitle(app.label)
            .setMessage(message)
            .setPositiveButton("Закрыть", null)
            .show()
    }

    private fun operationTitle(operation: AppManagerController.Operation): String =
        when (operation) {
            AppManagerController.Operation.FREEZE -> "Заморозить"
            AppManagerController.Operation.UNFREEZE -> "Разморозить"
            AppManagerController.Operation.UNINSTALL_FOR_USER -> "Удалить для пользователя"
            AppManagerController.Operation.RESTORE_FOR_USER -> "Восстановить"
            AppManagerController.Operation.UNINSTALL_COMPLETELY -> "Удалить полностью"
            AppManagerController.Operation.CLEAR_CACHE -> "Очистить кэш"
            AppManagerController.Operation.CLEAR_DATA -> "Стереть данные"
            AppManagerController.Operation.FORCE_STOP -> "Остановить"
            AppManagerController.Operation.TRIM_ALL_CACHES -> "Очистить общий кэш"
        }

    private fun setBusy(busy: Boolean) {
        operationButtons.forEach {
            it.isEnabled = !busy
            it.alpha = if (busy) 0.55f else 1f
        }
        if (::appList.isInitialized) appList.isEnabled = !busy
    }

    private fun actionButton(label: String, iconRes: Int = 0, action: () -> Unit): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            applyTvActionStyle(iconRes, horizontalPaddingDp = 18, verticalPaddingDp = 0)
            setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(54),
            ).apply {
                marginEnd = dp(10)
            }
            operationButtons.add(this)
        }

    private fun focusBackground(): StateListDrawable =
        tvFocusBackground(11)

    private fun rounded(color: Int, radiusDp: Int): GradientDrawable =
        roundedDrawable(color, radiusDp)

    companion object {
        private val CRITICAL_PACKAGES = setOf(
            "android",
            "com.android.systemui",
            "com.android.settings",
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.yandex.tv.services.platform",
            "com.yandex.tv.home",
        )
    }
}
