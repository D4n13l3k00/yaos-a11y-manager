package dev.d4n13l3k00.yaosa11y.feature.accessibility

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import dev.d4n13l3k00.yaosa11y.R
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Persistent guard for protected accessibility services.
 *
 * The native hook blocks the known YAOS watchdog. This service is the second
 * layer: it observes both secure settings, preserves user-owned desired state,
 * reconciles changes from any other process and reboots the hook when needed.
 */
class AccessibilityGuardService : Service() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val bootstrapExecutor = Executors.newSingleThreadExecutor()
    private val reconcileExecutor = Executors.newSingleThreadExecutor()
    private val bootstrapRunning = AtomicBoolean(false)
    private val reconcileRunning = AtomicBoolean(false)
    @Volatile
    private var destroyed = false
    private var healthTick = 0

    private lateinit var rootHookManager: RootHookManager

    private val settingsObserver = object : ContentObserver(mainHandler) {
        override fun onChange(selfChange: Boolean) {
            scheduleReconcile(SETTINGS_DEBOUNCE_MILLIS)
        }
    }

    private val wakeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            scheduleReconcile(WAKE_RECONCILE_DELAY_MILLIS)
            mainHandler.postDelayed({ ensureHookHealthy() }, WAKE_HOOK_DELAY_MILLIS)
        }
    }

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            scheduleReconcile(PACKAGE_RECONCILE_DELAY_MILLIS)
        }
    }

    private val periodicCheck = object : Runnable {
        override fun run() {
            if (destroyed || !rootHookManager.shouldBeEnabled()) return
            scheduleReconcile(0)
            healthTick += 1
            if (healthTick % HOOK_CHECK_EVERY_TICKS == 0) ensureHookHealthy()
            mainHandler.postDelayed(this, PERIODIC_RECONCILE_MILLIS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        rootHookManager = RootHookManager(this)
        createChannel()
        startForeground(NOTIFICATION_ID, notification("Запуск защиты специальных возможностей…"))
        registerObservers()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!rootHookManager.shouldBeEnabled()) {
            stopSelf()
            return START_NOT_STICKY
        }
        startBootstrap()
        return START_STICKY
    }

    override fun onDestroy() {
        destroyed = true
        mainHandler.removeCallbacksAndMessages(null)
        runCatching { contentResolver.unregisterContentObserver(settingsObserver) }
        runCatching { unregisterReceiver(wakeReceiver) }
        runCatching { unregisterReceiver(packageReceiver) }
        bootstrapExecutor.shutdownNow()
        reconcileExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun registerObservers() {
        contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
            false,
            settingsObserver,
        )
        contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(Settings.Secure.ACCESSIBILITY_ENABLED),
            false,
            settingsObserver,
        )
        registerReceiver(
            wakeReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            },
        )
        registerReceiver(
            packageReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addDataScheme("package")
            },
        )
    }

    private fun startBootstrap() {
        if (!bootstrapRunning.compareAndSet(false, true)) return
        bootstrapExecutor.execute {
            try {
                val deadline = System.currentTimeMillis() + BOOTSTRAP_WINDOW_MILLIS
                var attempt = 0
                var lastError = "Защита ещё не запускалась"
                while (
                    !destroyed &&
                    rootHookManager.shouldBeEnabled() &&
                    System.currentTimeMillis() < deadline
                ) {
                    val result = rootHookManager.enableWithRetries(attempts = 1)
                    if (result.success) {
                        updateNotification(result.message)
                        scheduleReconcile(0)
                        mainHandler.removeCallbacks(periodicCheck)
                        mainHandler.postDelayed(periodicCheck, PERIODIC_RECONCILE_MILLIS)
                        return@execute
                    }
                    lastError = result.message
                    val delay = RETRY_DELAYS_MILLIS[
                        attempt.coerceAtMost(RETRY_DELAYS_MILLIS.lastIndex)
                    ]
                    attempt += 1
                    updateNotification(
                        "Повтор запуска через ${delay / 1_000} с • ${lastError.take(90)}",
                    )
                    Thread.sleep(delay)
                }
                if (!destroyed && rootHookManager.shouldBeEnabled()) {
                    updateNotification("Защита ожидает системный доступ • ${lastError.take(110)}")
                    mainHandler.postDelayed({ startBootstrap() }, LONG_RETRY_MILLIS)
                }
            } finally {
                bootstrapRunning.set(false)
            }
        }
    }

    private fun scheduleReconcile(delayMillis: Long) {
        mainHandler.removeCallbacks(reconcileRequest)
        mainHandler.postDelayed(reconcileRequest, delayMillis)
    }

    private val reconcileRequest = Runnable {
        if (
            destroyed ||
            !rootHookManager.shouldBeEnabled() ||
            !reconcileRunning.compareAndSet(false, true)
        ) {
            return@Runnable
        }
        reconcileExecutor.execute {
            try {
                val result = rootHookManager.reconcileProtectedServices()
                if (result.changed) {
                    updateNotification(result.message)
                } else if (!result.success) {
                    updateNotification("Восстановление ожидает доступ • ${result.message}")
                    startBootstrap()
                }
            } finally {
                reconcileRunning.set(false)
            }
        }
    }

    private fun ensureHookHealthy() {
        if (destroyed || bootstrapRunning.get() || !rootHookManager.shouldBeEnabled()) return
        bootstrapExecutor.execute {
            if (rootHookManager.queryState() in setOf(
                    RootHookManager.State.UNAVAILABLE,
                    RootHookManager.State.STARTING,
                )
            ) {
                startBootstrap()
            }
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Защита специальных возможностей",
            NotificationManager.IMPORTANCE_LOW,
        )
        channel.description = "Контроль и восстановление выбранных accessibility-служб"
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification(text))
    }

    private fun notification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, AccessibilityActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(R.drawable.app_icon)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "yaos_hook"
        private const val NOTIFICATION_ID = 1042
        private const val SETTINGS_DEBOUNCE_MILLIS = 450L
        private const val WAKE_RECONCILE_DELAY_MILLIS = 1_500L
        private const val WAKE_HOOK_DELAY_MILLIS = 4_000L
        private const val PACKAGE_RECONCILE_DELAY_MILLIS = 2_000L
        private const val PERIODIC_RECONCILE_MILLIS = 30_000L
        private const val HOOK_CHECK_EVERY_TICKS = 4
        private const val BOOTSTRAP_WINDOW_MILLIS = 15 * 60_000L
        private const val LONG_RETRY_MILLIS = 5 * 60_000L
        private val RETRY_DELAYS_MILLIS =
            longArrayOf(2_000, 4_000, 8_000, 15_000, 30_000, 60_000, 120_000)
    }
}
