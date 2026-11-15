package dev.openhost.app.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import dev.openhost.app.MainActivity
import dev.openhost.app.data.SettingsStore

class HostService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        ServerHolder.setContext(applicationContext)
        createChannel()
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "OpenHost:ServerWake",
        ).apply { setReferenceCounted(false) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, makeNotification("Starting server..."))
        if (wakeLock?.isHeld == false) {
            wakeLock?.acquire()
        }

        val settings = SettingsStore(this).load()
        val error = ServerHolder.start(settings)
        if (error != null) {
            updateNotification("Could not start: $error")
            stopSelf()
            return START_NOT_STICKY
        }

        val state = ServerHolder.state.value
        val parts = buildList {
            add("Port ${state.port}")
            if (state.lanIp.isNotBlank()) add("LAN ${state.lanIp}")
            if (state.tailscaleIp.isNotBlank()) add("Tailscale ${state.tailscaleIp}")
        }
        updateNotification(parts.joinToString(" | "))
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        ServerHolder.stop()
        if (wakeLock?.isHeld == true) {
            runCatching { wakeLock?.release() }
        }
        wakeLock = null
        super.onDestroy()
    }

    private fun makeNotification(text: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPending = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopPending = PendingIntent.getService(
            this,
            1,
            Intent(this, HostService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setOngoing(true)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentTitle("OpenHost")
            .setContentText(text)
            .setContentIntent(openPending)
            .addAction(0, "Stop", stopPending)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, makeNotification(text))
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "OpenHost Server",
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "Keeps the OpenHost server running" }
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_STOP = "dev.openhost.app.STOP_SERVER"
        private const val CHANNEL_ID = "openhost.server"
        private const val NOTIFICATION_ID = 2
    }
}
