package dev.d4n13l3k00.yaosa11y

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import java.util.concurrent.Executors

class HookBootstrapService : Service() {
    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, notification("Восстановление защиты YAOS"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        EXECUTOR.execute {
            val result = RootHookManager(this).enableWithRetries()
            val manager = getSystemService(NotificationManager::class.java)
            if (!result.success) {
                manager.notify(NOTIFICATION_ID, notification("Ошибка: ${result.message}"))
            } else {
                manager.cancel(NOTIFICATION_ID)
            }
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Защита специальных возможностей",
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun notification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            },
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
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "yaos_hook"
        private const val NOTIFICATION_ID = 1042
        private val EXECUTOR = Executors.newSingleThreadExecutor()
    }
}
