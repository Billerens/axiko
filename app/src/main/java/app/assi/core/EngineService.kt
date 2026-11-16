package app.assi.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import app.assi.MainActivity
import app.assi.R
import app.assi.sense.Bubble

/**
 * Keeps the process, and with it the engine, alive while the app is off screen, so jobs,
 * hooks and a running agent turn are not cut short.
 */
class EngineService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Assi работает", NotificationManager.IMPORTANCE_MIN).apply {
                setShowBadge(false)
            },
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_spark)
            .setContentTitle("Assi")
            .setContentText("Работает в фоне")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        startForeground(1, notification)
        Bubble.restore()
        Lan.start()
        return START_STICKY
    }

    companion object {
        private const val CHANNEL = "engine"

        fun start(context: Context) {
            runCatching { context.startForegroundService(Intent(context, EngineService::class.java)) }
        }
    }
}
