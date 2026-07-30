package dev.d4n13l3k00.yaosa11y.feature.apps

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
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
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import dev.d4n13l3k00.yaosa11y.R
import dev.d4n13l3k00.yaosa11y.core.ui.dp
import dev.d4n13l3k00.yaosa11y.core.ui.postIfAlive
import dev.d4n13l3k00.yaosa11y.core.ui.roundedDrawable
import dev.d4n13l3k00.yaosa11y.core.ui.tvFocusBackground
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class WebInstallActivity : Activity() {
    private lateinit var controller: AppManagerController
    private lateinit var qrView: ImageView
    private lateinit var addressView: TextView
    private lateinit var statusView: TextView
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private var server: LocalApkServer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        controller = AppManagerController(this)
        setContentView(createContent())
        startServer()
    }

    override fun onDestroy() {
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
            setPadding(dp(56), dp(30), dp(56), dp(30))
        }
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(TextView(this).apply {
            text = "Установка APK с телефона"
            setTextColor(Color.WHITE)
            textSize = 28f
            typeface = Typeface.DEFAULT_BOLD
            setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_phone, 0, 0, 0)
            compoundDrawablePadding = dp(14)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        titleRow.addView(
            actionButton("Перезапустить сервер", R.drawable.ic_refresh) { startServer() },
        )
        titleRow.addView(actionButton("Назад", R.drawable.ic_back) { finish() })
        panel.addView(titleRow)

        panel.addView(TextView(this).apply {
            text = "Телефон и телевизор должны находиться в одной локальной сети. " +
                "Откройте QR-код камерой, выберите APK или вставьте прямую ссылку."
            setTextColor(Color.rgb(174, 185, 196))
            textSize = 15f
            setPadding(0, dp(6), 0, dp(16))
        })

        val columns = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
        }
        val qrColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = rounded(Color.rgb(28, 34, 43), 16)
            setPadding(dp(24), dp(24), dp(24), dp(20))
        }
        qrView = ImageView(this).apply {
            setBackgroundColor(Color.WHITE)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        qrColumn.addView(qrView, LinearLayout.LayoutParams(dp(260), dp(260)))
        addressView = TextView(this).apply {
            text = "Запуск сервера…"
            setTextColor(Color.rgb(94, 184, 255))
            textSize = 16f
            gravity = Gravity.CENTER
            maxLines = 3
            setPadding(0, dp(14), 0, dp(8))
            setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_phone, 0, 0, 0)
            compoundDrawablePadding = dp(10)
        }
        qrColumn.addView(
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
        qrColumn.addView(statusView)
        columns.addView(
            qrColumn,
            LinearLayout.LayoutParams(dp(500), ViewGroup.LayoutParams.MATCH_PARENT).apply {
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
        }
        logView = TextView(this).apply {
            text = ""
            setTextColor(Color.rgb(181, 197, 211))
            textSize = 14f
            typeface = Typeface.MONOSPACE
            setLineSpacing(0f, 1.15f)
            movementMethod = ScrollingMovementMethod()
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
        return root
    }

    private fun startServer() {
        server?.close()
        appendLog("Запуск локального HTTP-сервера")
        statusView.text = "Запуск…"
        statusView.setTextColor(Color.rgb(255, 183, 77))
        val newServer = LocalApkServer(this, controller) { message ->
            postIfAlive {
                appendLog(message)
                statusView.text = message
                statusView.setTextColor(
                    when {
                        message.contains("ошиб", true) -> Color.rgb(255, 128, 128)
                        message.contains("установлен", true) -> Color.rgb(129, 216, 161)
                        else -> Color.rgb(255, 183, 77)
                    },
                )
            }
        }
        server = newServer
        runCatching { newServer.start() }
            .onSuccess { url ->
                addressView.text = url
                addressView.contentDescription = "Адрес установщика $url"
                qrView.setImageBitmap(qrCode(url, dp(260)))
                qrView.contentDescription = "QR-код адреса $url"
                statusView.text = "Сервер готов"
                statusView.setTextColor(Color.rgb(129, 216, 161))
                appendLog("Сервер готов: $url")
                appendLog("Ожидание подключения телефона")
            }
            .onFailure {
                statusView.text = "Ошибка запуска"
                statusView.setTextColor(Color.rgb(255, 128, 128))
                appendLog("Ошибка запуска: ${it.message}")
            }
    }

    private fun appendLog(message: String) {
        val timestamp = TIME_FORMAT.format(Date())
        val line = "[$timestamp] $message"
        logView.append(if (logView.text.isEmpty()) line else "\n$line")
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
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
            text = label
            isAllCaps = false
            textSize = 14f
            setTextColor(Color.WHITE)
            background = focusBackground()
            setPadding(dp(18), 0, dp(18), 0)
            if (iconRes != 0) {
                setCompoundDrawablesWithIntrinsicBounds(iconRes, 0, 0, 0)
                compoundDrawablePadding = dp(9)
            }
            setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(54),
            ).apply {
                marginStart = dp(10)
            }
        }

    private fun focusBackground(): StateListDrawable =
        tvFocusBackground(11)

    private fun rounded(color: Int, radiusDp: Int): GradientDrawable =
        roundedDrawable(color, radiusDp)

    companion object {
        private val TIME_FORMAT = SimpleDateFormat("HH:mm:ss", Locale.US)
    }
}
