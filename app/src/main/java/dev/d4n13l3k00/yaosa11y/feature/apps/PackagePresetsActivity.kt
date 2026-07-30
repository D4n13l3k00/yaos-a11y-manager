package dev.d4n13l3k00.yaosa11y.feature.apps

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import dev.d4n13l3k00.yaosa11y.R
import dev.d4n13l3k00.yaosa11y.core.ui.ActivityTaskScope
import dev.d4n13l3k00.yaosa11y.core.ui.dp
import dev.d4n13l3k00.yaosa11y.core.ui.postIfAlive
import dev.d4n13l3k00.yaosa11y.core.ui.redirectDpadLeftTo
import dev.d4n13l3k00.yaosa11y.core.ui.roundedDrawable
import dev.d4n13l3k00.yaosa11y.core.ui.tvFocusBackground
import dev.d4n13l3k00.yaosa11y.feature.recovery.RecoveryDialog

class PackagePresetsActivity : Activity() {
    private val tasks = ActivityTaskScope(this)
    private lateinit var controller: AppManagerController
    private lateinit var statusView: TextView
    private lateinit var presetList: LinearLayout
    private lateinit var recommendedButton: Button
    private lateinit var clearButton: Button
    private lateinit var disableButton: Button
    private lateinit var enableButton: Button

    private val operationButtons = ArrayList<Button>()
    private val groupButtons = ArrayList<Button>()
    private val rowViews = ArrayList<View>()
    private var entries = emptyList<PresetEntry>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        controller = AppManagerController(this)
        setContentView(createContent())
        loadEntries()
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
            setPadding(dp(56), dp(30), dp(56), dp(28))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = "Пресеты системных приложений"
            textSize = 28f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_shield_off, 0, 0, 0)
            compoundDrawablePadding = dp(14)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(actionButton("Назад", R.drawable.ic_back, track = false) { finish() })
        panel.addView(header)

        panel.addView(TextView(this).apply {
            text =
                "Выберите только нужные пакеты. com.yandex.tv.services.platform " +
                    "защищён и никогда не входит в пресеты."
            textSize = 14f
            setTextColor(Color.rgb(255, 183, 77))
            setPadding(0, dp(4), 0, dp(6))
        })

        statusView = TextView(this).apply {
            text = "Чтение состояния пакетов…"
            textSize = 14f
            setTextColor(Color.rgb(174, 185, 196))
            setPadding(0, 0, 0, dp(8))
        }
        panel.addView(statusView)

        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        recommendedButton = actionButton("Рекомендуемые", R.drawable.ic_shield) {
            selectRecommended()
        }
        clearButton = actionButton("Снять выбор", R.drawable.ic_refresh) {
            entries.forEach { it.selected = false }
            renderEntries()
        }
        disableButton = actionButton("Отключить выбранные", R.drawable.ic_shield_off) {
            confirmDisable()
        }
        enableButton = actionButton("Включить выбранные", R.drawable.ic_shield) {
            runSelected(enabled = true)
        }
        toolbar.addView(recommendedButton)
        toolbar.addView(clearButton)
        toolbar.addView(disableButton)
        toolbar.addView(enableButton)
        panel.addView(toolbar)

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setPadding(0, dp(12), 0, 0)
        }
        presetList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        scroll.addView(
            presetList,
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
        recommendedButton.post { recommendedButton.requestFocus() }
        return root
    }

    private fun loadEntries(preserveSelection: Boolean = false) {
        setBusy(true)
        statusView.text = "Чтение состояния пакетов…"
        val previousSelection = if (preserveSelection) {
            entries.associate { it.definition.packageName to it.selected }
        } else {
            emptyMap()
        }
        tasks.execute {
            val result = runCatching {
                val apps = controller.loadApps().associateBy { it.packageName }
                PresetCatalog.definitions.map { definition ->
                    PresetEntry(
                        definition = definition,
                        app = apps[definition.packageName],
                        selected = previousSelection[definition.packageName] == true,
                    )
                }
            }
            tasks.post {
                setBusy(false)
                result.onSuccess {
                    entries = it
                    renderEntries()
                    if (!preserveSelection) {
                        recommendedButton.post { recommendedButton.requestFocus() }
                    }
                }.onFailure {
                    statusView.text = "Ошибка чтения пакетов: ${it.message}"
                    statusView.setTextColor(Color.rgb(255, 128, 128))
                }
            }
        }
    }

    private fun renderEntries() {
        operationButtons.removeAll(groupButtons.toSet())
        groupButtons.clear()
        rowViews.clear()
        presetList.removeAllViews()
        var firstFocusId = View.NO_ID

        PresetGroup.entries.forEach { group ->
            val groupEntries = entries.filter { it.definition.group == group }
            val (header, groupButton) = groupHeader(group, groupEntries)
            presetList.addView(header)
            if (firstFocusId == View.NO_ID) firstFocusId = groupButton.id
            groupEntries.forEach { entry ->
                val row = presetRow(entry)
                presetList.addView(row)
                rowViews += row
            }
        }

        if (firstFocusId != View.NO_ID) {
            listOf(recommendedButton, clearButton, disableButton, enableButton).forEach {
                it.nextFocusDownId = firstFocusId
            }
        }
        updateSelectionStatus()
        if (currentFocus == null) {
            recommendedButton.post { recommendedButton.requestFocus() }
        }
    }

    private fun groupHeader(
        group: PresetGroup,
        groupEntries: List<PresetEntry>,
    ): Pair<View, Button> {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(7))
        }
        val labels = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        labels.addView(TextView(this).apply {
            text = group.title
            textSize = 20f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        })
        labels.addView(TextView(this).apply {
            text = group.subtitle
            textSize = 12f
            setTextColor(Color.rgb(153, 166, 179))
        })
        row.addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val button = actionButton("Выбрать группу", R.drawable.ic_apps) {
            val selectable = groupEntries.filter {
                it.available && it.definition.groupSelectable
            }
            val select = selectable.any { !it.selected }
            selectable.forEach { it.selected = select }
            renderEntries()
        }.apply {
            id = View.generateViewId()
            redirectDpadLeftTo(disableButton)
        }
        groupButtons += button
        row.addView(button)
        return row to button
    }

    private fun presetRow(entry: PresetEntry): View {
        val row = LinearLayout(this).apply {
            id = View.generateViewId()
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(10), dp(18), dp(10))
            background = focusBackground()
            isFocusable = true
            isClickable = true
            redirectDpadLeftTo(disableButton)
            alpha = if (entry.available) 1f else 0.52f
        }
        val marker = TextView(this).apply {
            textSize = 24f
            gravity = Gravity.CENTER
        }
        row.addView(marker, LinearLayout.LayoutParams(dp(48), dp(48)).apply {
            marginEnd = dp(12)
        })

        val labels = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val title = TextView(this).apply {
            text = entry.definition.label
            textSize = 17f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.MARQUEE
            marqueeRepeatLimit = -1
            isHorizontalFadingEdgeEnabled = true
        }
        val details = TextView(this).apply {
            text = buildString {
                append(entry.definition.packageName)
                entry.definition.warning?.let { append(" • ").append(it) }
            }
            textSize = 12f
            setTextColor(
                if (entry.definition.risk == PresetRisk.CRITICAL) {
                    Color.rgb(255, 128, 128)
                } else {
                    Color.rgb(153, 166, 179)
                },
            )
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.MARQUEE
            marqueeRepeatLimit = -1
            isHorizontalFadingEdgeEnabled = true
        }
        labels.addView(title)
        labels.addView(details)
        row.addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        row.addView(TextView(this).apply {
            text = entry.stateLabel
            textSize = 12f
            setTextColor(entry.stateColor)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = rounded(Color.rgb(49, 57, 67), 16)
            setPadding(dp(14), dp(7), dp(14), dp(7))
        })

        fun updateMarker() {
            marker.text = if (entry.selected) "✓" else "○"
            marker.setTextColor(
                if (entry.selected) Color.rgb(129, 216, 161) else Color.rgb(126, 140, 154),
            )
        }
        updateMarker()
        row.setOnClickListener {
            if (!entry.available) {
                Toast.makeText(this, "Пакет не установлен для пользователя", Toast.LENGTH_SHORT)
                    .show()
                return@setOnClickListener
            }
            entry.selected = !entry.selected
            updateMarker()
            updateSelectionStatus()
        }
        row.setOnFocusChangeListener { _, focused ->
            title.isSelected = focused
            details.isSelected = focused
        }
        return row.apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(7) }
        }
    }

    private fun selectRecommended() {
        entries.forEach { entry ->
            entry.selected = entry.available && entry.definition.recommended
        }
        renderEntries()
    }

    private fun confirmDisable() {
        val selected = selectedEntries()
        if (selected.isEmpty()) {
            Toast.makeText(this, "Сначала выберите пакеты", Toast.LENGTH_SHORT).show()
            return
        }
        val warnings = selected.mapNotNull { it.definition.warning }.distinct()
        val message = buildString {
            appendLine("Будет отключено пакетов: ${selected.size}")
            if (warnings.isNotEmpty()) {
                appendLine()
                appendLine("Предупреждения:")
                warnings.forEach { appendLine("• $it") }
            }
            appendLine()
            append(selected.joinToString("\n") { it.definition.packageName })
        }
        AlertDialog.Builder(this)
            .setTitle("Отключить выбранные пакеты?")
            .setMessage(message)
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Отключить") { _, _ ->
                runBatch(selected.map { it.definition.packageName }, enabled = false)
            }
            .show()
    }

    private fun runSelected(enabled: Boolean) {
        val selected = selectedEntries()
        if (selected.isEmpty()) {
            Toast.makeText(this, "Сначала выберите пакеты", Toast.LENGTH_SHORT).show()
            return
        }
        runBatch(selected.map { it.definition.packageName }, enabled)
    }

    private fun runBatch(packageNames: List<String>, enabled: Boolean) {
        setBusy(true)
        statusView.text =
            if (enabled) "Включение выбранных пакетов…" else "Отключение выбранных пакетов…"
        statusView.setTextColor(Color.rgb(255, 183, 77))
        controller.setPackagesEnabledAsync(packageNames, enabled) { result ->
            postIfAlive {
                setBusy(false)
                if (result.success) {
                    entries.forEach { it.selected = false }
                    AlertDialog.Builder(this)
                        .setTitle("Операция завершена")
                        .setMessage(result.message)
                        .setPositiveButton("Закрыть", null)
                        .show()
                    loadEntries()
                } else if (looksLikeAdbFailure(result.message)) {
                    RecoveryDialog.show(this, result.message) {
                        runBatch(packageNames, enabled)
                    }
                    loadEntries(preserveSelection = true)
                } else {
                    AlertDialog.Builder(this)
                        .setTitle("Готово с ошибками")
                        .setMessage(result.message)
                        .setPositiveButton("Закрыть", null)
                        .show()
                    loadEntries(preserveSelection = true)
                }
            }
        }
    }

    private fun selectedEntries(): List<PresetEntry> =
        entries.filter { it.selected && it.available }

    private fun updateSelectionStatus() {
        val installed = entries.count { it.available }
        val selected = selectedEntries().size
        val disabled = entries.count { it.available && it.app?.enabled == false }
        statusView.text =
            "Доступно: $installed из ${entries.size} • выбрано: $selected • уже отключено: $disabled"
        statusView.setTextColor(Color.rgb(174, 185, 196))
    }

    private fun setBusy(busy: Boolean) {
        operationButtons.forEach {
            it.isEnabled = !busy
            it.alpha = if (busy) 0.55f else 1f
        }
        rowViews.forEach {
            it.isEnabled = !busy
            it.alpha = if (busy) 0.55f else 1f
        }
    }

    private fun looksLikeAdbFailure(message: String): Boolean {
        val normalized = message.lowercase()
        return "adb" in normalized ||
            "127.0.0.1" in normalized ||
            "connection" in normalized ||
            "соединен" in normalized
    }

    private fun actionButton(
        label: String,
        iconRes: Int,
        track: Boolean = true,
        action: () -> Unit,
    ): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 14f
            setTextColor(Color.WHITE)
            background = focusBackground()
            setPadding(dp(18), 0, dp(18), 0)
            setCompoundDrawablesWithIntrinsicBounds(iconRes, 0, 0, 0)
            compoundDrawablePadding = dp(9)
            setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(54),
            ).apply { marginEnd = dp(10) }
            if (track) operationButtons += this
        }

    private fun focusBackground(): StateListDrawable =
        tvFocusBackground(11)

    private fun rounded(color: Int, radiusDp: Int): GradientDrawable =
        roundedDrawable(color, radiusDp)

    private data class PresetEntry(
        val definition: PresetDefinition,
        val app: ManagedApp?,
        var selected: Boolean,
    ) {
        val available: Boolean
            get() = app?.installedForUser == true

        val stateLabel: String
            get() = when {
                app == null -> "НЕТ"
                !app.installedForUser -> "УДАЛЕНО"
                !app.enabled -> "ОТКЛ"
                else -> "ВКЛ"
            }

        val stateColor: Int
            get() = when {
                app == null -> Color.rgb(126, 140, 154)
                !app.installedForUser -> Color.rgb(255, 183, 77)
                !app.enabled -> Color.rgb(120, 184, 255)
                else -> Color.rgb(129, 216, 161)
            }
    }

}
