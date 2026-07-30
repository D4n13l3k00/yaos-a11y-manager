package dev.d4n13l3k00.yaosa11y.feature.apps

import android.annotation.SuppressLint
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import dev.d4n13l3k00.yaosa11y.R
import dev.d4n13l3k00.yaosa11y.core.ui.applyTvActionStyle
import dev.d4n13l3k00.yaosa11y.core.ui.applyTvScreenInsets
import dev.d4n13l3k00.yaosa11y.core.ui.applyTvScreenSubtitleStyle
import dev.d4n13l3k00.yaosa11y.core.ui.applyTvScreenTitleStyle
import dev.d4n13l3k00.yaosa11y.core.ui.dp
import dev.d4n13l3k00.yaosa11y.core.ui.postIfAlive
import dev.d4n13l3k00.yaosa11y.core.ui.roundedDrawable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class WebInstallActivity : Activity() {
    private lateinit var controller: AppManagerController
    private lateinit var qrView: ImageView
    private lateinit var qrCard: LinearLayout
    private lateinit var addressView: TextView
    private lateinit var statusView: TextView
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var restartButton: Button
    private lateinit var backButton: Button
    private var server: LocalApkServer? = null
    private var stageAnimator: ValueAnimator? = null
    private val installStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val message = intent.getStringExtra(ApkInstallReceiver.EXTRA_MESSAGE) ?: return
            appendLog(message)
            showStatus(message)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        controller = AppManagerController(this)
        setContentView(createContent())
        registerInstallStatusReceiver()
        backButton.post { backButton.requestFocus() }
        startServer()
    }

    override fun onDestroy() {
        stageAnimator?.cancel()
        runCatching { unregisterReceiver(installStatusReceiver) }
        server?.close()
        server = null
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
            text = "Установка APK с телефона"
            applyTvScreenTitleStyle(R.drawable.ic_phone)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        restartButton =
            actionButton("Перезапустить сервер", R.drawable.ic_refresh) {
                startServer()
                restartButton.post { restartButton.requestFocus() }
            }
        backButton = actionButton("Назад", R.drawable.ic_back) { finish() }
        titleRow.addView(restartButton)
        titleRow.addView(backButton)
        panel.addView(titleRow)

        panel.addView(TextView(this).apply {
            text = "Телефон и телевизор должны находиться в одной локальной сети. " +
                "Откройте QR-код камерой, выберите APK или вставьте прямую ссылку."
            applyTvScreenSubtitleStyle()
        })

        val columns = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
        }
        qrCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = rounded(Color.rgb(28, 34, 43), 16)
            setPadding(dp(20), dp(20), dp(20), dp(16))
        }
        qrView = ImageView(this).apply {
            setBackgroundColor(Color.WHITE)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        qrCard.addView(qrView, LinearLayout.LayoutParams(dp(256), dp(256)))
        addressView = TextView(this).apply {
            text = "Запуск сервера…"
            setTextColor(Color.rgb(94, 184, 255))
            textSize = 16f
            gravity = Gravity.CENTER
            maxLines = 3
            setPadding(0, dp(10), 0, dp(4))
            setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_phone, 0, 0, 0)
            compoundDrawablePadding = dp(10)
        }
        qrCard.addView(
            addressView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        statusView = TextView(this).apply {
            text = "Подготовка…"
            setTextColor(Color.rgb(255, 183, 77))
            textSize = 15f
            gravity = Gravity.CENTER
        }
        qrCard.addView(statusView)
        columns.addView(
            qrCard,
            LinearLayout.LayoutParams(dp(296), ViewGroup.LayoutParams.MATCH_PARENT).apply {
                marginEnd = dp(20)
            },
        )

        val logPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Color.rgb(28, 34, 43), 16)
            setPadding(dp(24), dp(20), dp(24), dp(20))
        }
        logPanel.addView(TextView(this).apply {
            text = "Журнал установки"
            setTextColor(Color.WHITE)
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(12))
            setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_log, 0, 0, 0)
            compoundDrawablePadding = dp(12)
        })
        logScroll = ScrollView(this).apply {
            isFillViewport = true
            configureDpadScrolling()
        }
        logView = TextView(this).apply {
            text = ""
            setTextColor(Color.rgb(181, 197, 211))
            textSize = 14f
            typeface = Typeface.MONOSPACE
            setLineSpacing(0f, 1.15f)
            isFocusable = false
            isClickable = false
            isLongClickable = false
        }
        logScroll.addView(
            logView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        logPanel.addView(
            logScroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ),
        )
        columns.addView(
            logPanel,
            LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.MATCH_PARENT,
                1f,
            ),
        )
        panel.addView(
            columns,
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
        configureFocusNavigation()
        return root
    }

    private fun startServer() {
        server?.close()
        appendLog("Запуск локального HTTP-сервера")
        showStatus("Запуск…")
        val newServer = LocalApkServer(this, controller) { message ->
            postIfAlive {
                appendLog(message)
                showStatus(message)
            }
        }
        server = newServer
        runCatching { newServer.start() }
            .onSuccess { url ->
                addressView.text = url
                addressView.contentDescription = "Адрес установщика $url"
                qrView.setImageBitmap(qrCode(url, dp(256)))
                qrView.contentDescription = "QR-код адреса $url"
                showStatus("Сервер готов")
                appendLog("Сервер готов: $url")
                appendLog("Ожидание подключения телефона")
            }
            .onFailure {
                showStatus("Ошибка запуска")
                appendLog("Ошибка запуска: ${it.message}")
            }
    }

    private fun appendLog(message: String) {
        val timestamp = TIME_FORMAT.format(Date())
        val line = "[$timestamp] $message"
        logView.append(if (logView.text.isEmpty()) line else "\n$line")
        logScroll.post {
            val bottom = (logView.height - logScroll.height).coerceAtLeast(0)
            logScroll.smoothScrollTo(0, bottom)
        }
    }

    private fun showStatus(message: String) {
        stageAnimator?.cancel()
        statusView.animate().cancel()
        qrView.animate().cancel()
        qrCard.animate().cancel()
        statusView.translationX = 0f
        qrView.alpha = 1f
        qrView.scaleX = 1f
        qrView.scaleY = 1f
        statusView.text = message
        val error = message.contains("ошиб", true) ||
            message.contains("не удалось", true) ||
            message.contains("не завершена", true)
        val success = message.contains("готов", true) ||
            message.contains("успешно", true) ||
            message.contains("установлен", true)
        val installing = message.contains("установ", true) ||
            message.contains("провер", true) ||
            message.contains("подтверж", true)
        val transferring = message.contains("получ", true) ||
            message.contains("загруз", true) ||
            message.contains("%")

        statusView.setTextColor(
            when {
                error -> Color.rgb(255, 128, 128)
                success -> Color.rgb(129, 216, 161)
                else -> Color.rgb(255, 183, 77)
            },
        )
        statusView.alpha = 0.4f
        statusView.scaleX = 0.96f
        statusView.scaleY = 0.96f
        statusView.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(220)
            .setInterpolator(DecelerateInterpolator())
            .start()

        when {
            error -> ObjectAnimator.ofFloat(
                statusView,
                View.TRANSLATION_X,
                0f,
                -dp(7).toFloat(),
                dp(7).toFloat(),
                -dp(4).toFloat(),
                dp(4).toFloat(),
                0f,
            ).apply {
                duration = 420
                start()
            }
            success -> {
                statusView.scaleX = 0.88f
                statusView.scaleY = 0.88f
                statusView.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(460)
                    .setInterpolator(OvershootInterpolator(1.35f))
                    .start()
            }
            transferring || installing -> startStagePulse(installing)
        }
    }

    private fun startStagePulse(installationStage: Boolean) {
        stageAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (installationStage) 700 else 1_050
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { animator ->
                val progress = animator.animatedValue as Float
                statusView.alpha = 0.72f + progress * 0.28f
                statusView.scaleX = 1f + if (installationStage) progress * 0.035f else 0f
                statusView.scaleY = statusView.scaleX
                qrView.alpha = 0.82f + progress * 0.18f
                val qrScale = 1f + if (installationStage) progress * 0.008f else progress * 0.016f
                qrView.scaleX = qrScale
                qrView.scaleY = qrScale
            }
            start()
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerInstallStatusReceiver() {
        val filter = IntentFilter(ApkInstallReceiver.ACTION_STATUS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(installStatusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(installStatusReceiver, filter)
        }
    }

    private fun qrCode(text: String, size: Int): Bitmap {
        val matrix = MultiFormatWriter().encode(
            text,
            BarcodeFormat.QR_CODE,
            size,
            size,
            mapOf(
                EncodeHintType.MARGIN to 1,
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.CHARACTER_SET to "UTF-8",
            ),
        )
        val pixels = IntArray(size * size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                pixels[y * size + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
            }
        }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }

    private fun actionButton(label: String, iconRes: Int = 0, action: () -> Unit): Button =
        Button(this).apply {
            id = View.generateViewId()
            text = label
            isAllCaps = false
            applyTvActionStyle(iconRes, horizontalPaddingDp = 18, verticalPaddingDp = 0)
            setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(54),
            ).apply {
                marginStart = dp(10)
            }
        }

    private fun configureFocusNavigation() {
        backButton.nextFocusUpId = backButton.id
        backButton.nextFocusRightId = backButton.id
        backButton.nextFocusLeftId = restartButton.id
        backButton.nextFocusDownId = logScroll.id

        restartButton.nextFocusUpId = restartButton.id
        restartButton.nextFocusLeftId = restartButton.id
        restartButton.nextFocusRightId = backButton.id
        restartButton.nextFocusDownId = logScroll.id

        logScroll.nextFocusUpId = backButton.id
        logScroll.nextFocusLeftId = restartButton.id
        logScroll.nextFocusRightId = logScroll.id
        logScroll.nextFocusDownId = logScroll.id
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
                rounded(Color.rgb(35, 47, 59), 8).apply {
                    setStroke(dp(2), Color.rgb(94, 184, 255))
                },
            )
            addState(
                intArrayOf(),
                rounded(Color.TRANSPARENT, 8),
            )
        }

    private fun rounded(color: Int, radiusDp: Int): GradientDrawable =
        roundedDrawable(color, radiusDp)

    companion object {
        private val TIME_FORMAT = SimpleDateFormat("HH:mm:ss", Locale.US)
    }
}
