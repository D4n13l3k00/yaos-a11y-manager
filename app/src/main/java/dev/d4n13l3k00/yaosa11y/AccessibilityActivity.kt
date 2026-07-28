package dev.d4n13l3k00.yaosa11y

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.database.ContentObserver
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class AccessibilityActivity : Activity() {
    private lateinit var repository: AccessibilityRepository
    private lateinit var rootHookManager: RootHookManager
    private lateinit var statusView: TextView
    private lateinit var hookStatusView: TextView
    private lateinit var serviceList: LinearLayout
    private val operationButtons = ArrayList<Button>()

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            renderServices()
            if (::rootHookManager.isInitialized && rootHookManager.shouldBeEnabled()) {
                rootHookManager.rememberCurrentAccessibility()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        repository = AccessibilityRepository(this)
        rootHookManager = RootHookManager(this)
        setContentView(createContent())
        contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
            false,
            observer,
        )
        if (rootHookManager.hasStoredChoice()) {
            refreshHookStatus()
        } else {
            changeHookState(true)
        }
    }

    override fun onResume() {
        super.onResume()
        renderServices()
        if (rootHookManager.shouldBeEnabled()) {
            rootHookManager.rememberCurrentAccessibility()
        }
    }

    override fun onDestroy() {
        contentResolver.unregisterContentObserver(observer)
        super.onDestroy()
    }

    private fun createContent(): View {
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(16, 19, 23))
        }

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(72), dp(42), dp(72), dp(36))
        }

        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(TextView(this).apply {
            text = "Спецвозможности"
            setTextColor(Color.WHITE)
            textSize = 30f
            typeface = Typeface.DEFAULT_BOLD
            setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_accessibility, 0, 0, 0)
            compoundDrawablePadding = dp(14)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        titleRow.addView(actionButton("Назад", R.drawable.ic_back) { finish() })
        panel.addView(titleRow)

        panel.addView(TextView(this).apply {
            text = getString(R.string.author_line)
            setTextColor(Color.rgb(94, 184, 255))
            textSize = 15f
            setPadding(0, dp(4), 0, 0)
        })

        statusView = TextView(this).apply {
            setTextColor(Color.rgb(174, 185, 196))
            textSize = 16f
            setPadding(0, dp(8), 0, dp(18))
        }
        panel.addView(statusView)

        hookStatusView = TextView(this).apply {
            text = "Защита YAOS: проверка…"
            setTextColor(Color.rgb(255, 183, 77))
            textSize = 15f
            setPadding(0, 0, 0, dp(12))
            setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_shield, 0, 0, 0)
            compoundDrawablePadding = dp(10)
        }
        panel.addView(hookStatusView)

        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START
        }
        toolbar.addView(actionButton("Обновить", R.drawable.ic_refresh) {
            renderServices()
            refreshHookStatus()
        })
        toolbar.addView(actionButton("Приложения", R.drawable.ic_apps) {
            startActivity(Intent(this, AppManagerActivity::class.java))
        })
        toolbar.addView(
            actionButton("Включить защиту", R.drawable.ic_shield) { changeHookState(true) },
        )
        toolbar.addView(
            actionButton("Отключить защиту", R.drawable.ic_shield_off) {
                changeHookState(false)
            },
        )
        panel.addView(toolbar)

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setPadding(0, dp(22), 0, 0)
        }
        serviceList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        scroll.addView(
            serviceList,
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

    private fun renderServices() {
        val canWrite = repository.canWriteSecureSettings()
        val entries = repository.entries()
        val enabledCount = entries.count { it.enabled }

        statusView.text = if (canWrite) {
            "Найдено служб: ${entries.size} • включено: $enabledCount"
        } else {
            "Нет системного разрешения. Кнопка защиты выдаст его через локальный ADB."
        }
        statusView.setTextColor(
            if (canWrite) Color.rgb(174, 185, 196) else Color.rgb(255, 183, 77),
        )

        serviceList.removeAllViews()
        entries.forEach { serviceList.addView(serviceRow(it, canWrite)) }
    }

    private fun serviceRow(entry: AccessibilityEntry, canWrite: Boolean): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(15), dp(24), dp(15))
            background = focusBackground()
            isFocusable = true
            isClickable = true
            alpha = if (canWrite) 1f else 0.65f
            setOnClickListener {
                if (canWrite) confirmToggle(entry) else showPermissionHelp()
            }
        }

        row.addView(ImageView(this).apply {
            setImageDrawable(entry.info.resolveInfo.loadIcon(packageManager))
            scaleType = ImageView.ScaleType.FIT_CENTER
        }, LinearLayout.LayoutParams(dp(54), dp(54)).apply {
            marginEnd = dp(20)
        })

        val labels = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        labels.addView(TextView(this).apply {
            text = entry.label
            setTextColor(Color.WHITE)
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
        })
        labels.addView(TextView(this).apply {
            text = entry.component.flattenToShortString()
            setTextColor(Color.rgb(153, 166, 179))
            textSize = 12f
            maxLines = 1
        })
        row.addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        row.addView(TextView(this).apply {
            text = if (entry.enabled) "ВКЛ" else "ВЫКЛ"
            setTextColor(if (entry.enabled) Color.rgb(129, 216, 161) else Color.rgb(177, 187, 197))
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = pillBackground(entry.enabled)
            setPadding(dp(18), dp(8), dp(18), dp(8))
        })

        return row.apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin = dp(10)
            }
        }
    }

    private fun confirmToggle(entry: AccessibilityEntry) {
        val enable = !entry.enabled
        AlertDialog.Builder(this)
            .setTitle(if (enable) "Включить ${entry.label}?" else "Отключить ${entry.label}?")
            .setMessage(
                if (enable) {
                    "Служба сможет наблюдать за действиями на экране и обрабатывать события управления."
                } else {
                    "Связанные с этой службой функции перестанут работать."
                },
            )
            .setNegativeButton("Отмена", null)
            .setPositiveButton(if (enable) "Включить" else "Отключить") { _, _ ->
                toggle(entry.component, enable, entry.label)
            }
            .show()
    }

    private fun toggle(component: ComponentName, enabled: Boolean, label: String) {
        if (!repository.setEnabled(component, enabled)) {
            Toast.makeText(this, "Не удалось изменить системную настройку", Toast.LENGTH_LONG).show()
            return
        }

        Handler(Looper.getMainLooper()).postDelayed({
            val persisted = component in repository.enabledComponents()
            if (persisted == enabled) {
                rootHookManager.rememberCurrentAccessibility()
            }
            renderServices()
            if (persisted != enabled) {
                Toast.makeText(
                    this,
                    "YAOS отменил изменение для $label",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }, 450)
    }

    private fun showPermissionHelp() {
        AlertDialog.Builder(this)
            .setTitle("Требуется разрешение")
            .setMessage(
                "Нажмите «Включить защиту». Приложение подключится к локальному ADB " +
                    "телевизора и само выдаст ${Manifest.permission.WRITE_SECURE_SETTINGS}.",
            )
            .setPositiveButton("Понятно", null)
            .show()
    }

    private fun changeHookState(enabled: Boolean) {
        setOperationBusy(true)
        hookStatusView.text =
            if (enabled) "Защита YAOS: установка…" else "Защита YAOS: отключение…"
        hookStatusView.setTextColor(Color.rgb(255, 183, 77))

        rootHookManager.runAsync(enabled) { result ->
            runOnUiThread {
                setOperationBusy(false)
                Toast.makeText(
                    this,
                    result.message,
                    if (result.success) Toast.LENGTH_SHORT else Toast.LENGTH_LONG,
                ).show()
                renderServices()
                refreshHookStatus()
            }
        }
    }

    private fun refreshHookStatus() {
        rootHookManager.queryStateAsync { state ->
            runOnUiThread {
                hookStatusView.text = when (state) {
                    RootHookManager.State.ENABLED -> "Защита YAOS: включена"
                    RootHookManager.State.DISABLED -> "Защита YAOS: отключена"
                    RootHookManager.State.STARTING -> "Защита YAOS: запускается…"
                    RootHookManager.State.UNAVAILABLE -> "Защита YAOS: недоступна"
                }
                hookStatusView.setTextColor(
                    when (state) {
                        RootHookManager.State.ENABLED -> Color.rgb(129, 216, 161)
                        RootHookManager.State.DISABLED -> Color.rgb(177, 187, 197)
                        else -> Color.rgb(255, 183, 77)
                    },
                )
            }
        }
    }

    private fun setOperationBusy(busy: Boolean) {
        operationButtons.forEach {
            it.isEnabled = !busy
            it.alpha = if (busy) 0.55f else 1f
        }
    }

    private fun actionButton(label: String, iconRes: Int = 0, action: () -> Unit): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 15f
            setTextColor(Color.WHITE)
            background = focusBackground()
            setPadding(dp(22), 0, dp(22), 0)
            if (iconRes != 0) {
                setCompoundDrawablesWithIntrinsicBounds(iconRes, 0, 0, 0)
                compoundDrawablePadding = dp(9)
            }
            setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(54),
            ).apply {
                marginEnd = dp(12)
            }
            operationButtons.add(this)
        }

    private fun focusBackground(): StateListDrawable =
        StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), rounded(Color.rgb(44, 103, 148), 12))
            addState(intArrayOf(android.R.attr.state_pressed), rounded(Color.rgb(38, 91, 130), 12))
            addState(intArrayOf(), rounded(Color.rgb(28, 34, 43), 12))
        }

    private fun pillBackground(enabled: Boolean): GradientDrawable =
        rounded(
            if (enabled) Color.rgb(30, 76, 52) else Color.rgb(49, 57, 67),
            18,
        )

    private fun rounded(color: Int, radiusDp: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
        }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()
}
