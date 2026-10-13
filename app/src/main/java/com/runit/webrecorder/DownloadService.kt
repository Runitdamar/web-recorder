package com.runit.webrecorder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/** Keeps downloads alive in the background and shows progress in a notification. */
class DownloadService : Service() {

    companion object {
        private const val CHANNEL = "vio_downloads"
        private const val DONE_CHANNEL = "vio_done"
        private const val NOTIF_ID = 4201
        private var doneCounter = 5000

        /** Start the service while something is downloading, stop it when nothing is. */
        fun sync(ctx: Context) {
            val intent = Intent(ctx, DownloadService::class.java)
            try {
                if (DownloadCenter.activeCount() > 0) {
                    if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(intent) else ctx.startService(intent)
                } else {
                    ctx.stopService(intent)
                }
            } catch (_: Exception) {
            }
        }

        fun notifyDone(ctx: Context, name: String) {
            try {
                val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                ensureChannels(nm)
                val open = PendingIntent.getActivity(
                    ctx, 0, Intent(ctx, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                val n = Notification.Builder(ctx, DONE_CHANNEL)
                    .setSmallIcon(R.drawable.ic_check)
                    .setContentTitle("Download complete")
                    .setContentText(name)
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .build()
                nm.notify(doneCounter++, n)
            } catch (_: Exception) {
            }
        }

        private fun ensureChannels(nm: NotificationManager) {
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, "Downloads in progress", NotificationManager.IMPORTANCE_LOW)
                )
                nm.createNotificationChannel(
                    NotificationChannel(DONE_CHANNEL, "Finished downloads", NotificationManager.IMPORTANCE_DEFAULT)
                )
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            if (DownloadCenter.activeCount() == 0) {
                stopSelf()
                return
            }
            update()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureChannels(nm)
        val n = build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, n)
        }
        handler.removeCallbacks(ticker)
        handler.postDelayed(ticker, 1000)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        super.onDestroy()
    }

    private fun update() {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID, build())
        } catch (_: Exception) {
        }
    }

    private fun build(): Notification {
        val active = DownloadCenter.all().filter { it.isBusy() }
        val first = active.firstOrNull()
        val title = if (active.size > 1) "Downloading ${active.size} videos" else "Downloading"
        val text = if (first == null) "Starting..." else {
            val p = if (first.progress >= 0) "${first.progress}%" else ""
            val sp = DownloadUtil.formatSpeed(first.speedBps)
            listOf(first.title, p, sp).filter { it.isNotEmpty() }.joinToString(" • ")
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else Notification.Builder(this)
        b.setSmallIcon(R.drawable.ic_download)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
        if (first != null && first.progress >= 0) b.setProgress(100, first.progress, false)
        else b.setProgress(0, 0, true)
        return b.build()
    }
}
