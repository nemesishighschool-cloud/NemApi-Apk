package com.nemap.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.nemap.android.MainActivity
import com.nemap.android.api.ApiServer
import com.nemap.android.core.AppState
import com.nemap.android.core.NemLog

/**
 * ForegroundService : garde le processus (donc le WebView et l'API) vivant
 * quand l'utilisateur quitte l'application.
 */
class BridgeService : Service() {

    companion object {
        const val ACTION_START = "com.nemap.android.action.START"
        const val ACTION_STOP = "com.nemap.android.action.STOP"
        private const val CHANNEL_ID = "nemapi_service"
        private const val NOTIF_ID = 1001
    }

    private var api: ApiServer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopEverything()
                return START_NOT_STICKY
            }
            else -> startEverything()
        }
        return START_STICKY
    }

    private fun startEverything() {
        try {
            goForeground("NemApi actif — API sur le port ${AppState.API_PORT}")
        } catch (t: Throwable) {
            NemLog.e("service", "startForeground : ${t.message}")
        }
        if (api == null) {
            api = ApiServer(applicationContext)
        }
        if (api?.running != true) api?.start()
        AppState.serviceRunning = true
        if (AppState.startedAt == 0L) AppState.startedAt = System.currentTimeMillis()
        NemLog.i("service", "service démarré")
        notifyState("NemApi actif — ${AppState.activeProvider} — port ${AppState.API_PORT}")
    }

    private fun stopEverything() {
        api?.stop()
        api = null
        AppState.serviceRunning = false
        NemLog.i("service", "service arrêté")
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun goForeground(text: String) {
        val notif = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIF_ID,
                notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun notifyState(text: String) {
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(NOTIF_ID, buildNotification(text))
        } catch (_: Throwable) {
        }
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("NemApi")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(open)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "NemApi", NotificationManager.IMPORTANCE_LOW).apply {
                        description = "Service NemApi (proxy local + navigateur intégré)"
                        setShowBadge(false)
                    }
                )
            }
        }
    }

    override fun onDestroy() {
        api?.stop()
        api = null
        AppState.serviceRunning = false
        super.onDestroy()
    }
}
