package com.runit.webrecorder

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.Toast
import androidx.core.app.NotificationCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RecorderService : Service() {

    companion object {
        const val ACTION_START = "com.runit.webrecorder.START"
        const val ACTION_STOP = "com.runit.webrecorder.STOP"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "data"
        private const val CHANNEL_ID = "recorder"
        private const val NOTIF_ID = 1

        @Volatile
        var isRecording = false
    }

    private var projection: MediaProjection? = null
    private var recorder: MediaRecorder? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var outputFile: File? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording(intent)
            ACTION_STOP -> stopRecording()
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startRecording(intent: Intent) {
        if (isRecording) return

        createChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_DATA)
        }

        if (data == null) {
            toast("Recording failed: no permission data")
            stopSelf()
            return
        }

        try {
            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val proj = mpm.getMediaProjection(resultCode, data)
            projection = proj

            // Required on Android 14+ before creating the virtual display
            proj.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    stopRecording()
                }
            }, Handler(Looper.getMainLooper()))

            val metrics = resources.displayMetrics
            val width = metrics.widthPixels / 2 * 2
            val height = metrics.heightPixels / 2 * 2
            val dpi = metrics.densityDpi

            val dir = getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: filesDir
            dir.mkdirs()
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val file = File(dir, "rec_$stamp.mp4")
            outputFile = file

            val rec = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()
            rec.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            rec.setVideoSize(width, height)
            rec.setVideoFrameRate(30)
            rec.setVideoEncodingBitRate(4_000_000)
            rec.setOutputFile(file.absolutePath)
            rec.prepare()
            recorder = rec

            virtualDisplay = proj.createVirtualDisplay(
                "WebRecorder",
                width,
                height,
                dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                rec.surface,
                null,
                null
            )

            rec.start()
            isRecording = true
            toast("Recording started")
        } catch (e: Exception) {
            toast("Recording failed: ${e.message}")
            cleanup()
            stopSelf()
        }
    }

    private fun stopRecording() {
        if (!isRecording) {
            cleanup()
            stopSelf()
            return
        }
        isRecording = false

        try {
            recorder?.stop()
        } catch (e: RuntimeException) {
            // stopped too quickly, file may be empty
            outputFile?.delete()
            outputFile = null
        }

        val saved = outputFile
        cleanup()

        if (saved != null) {
            toast("Saved: ${saved.name}")
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun cleanup() {
        try { recorder?.release() } catch (_: Exception) {}
        recorder = null
        try { virtualDisplay?.release() } catch (_: Exception) {}
        virtualDisplay = null
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
        isRecording = false
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Screen recording",
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.presence_video_online)
        .setContentTitle("Web Recorder")
        .setContentText("Recording screen...")
        .setOngoing(true)
        .addAction(
            android.R.drawable.ic_media_pause,
            "Stop",
            PendingIntent.getService(
                this,
                0,
                Intent(this, RecorderService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        )
        .build()

    private fun toast(msg: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }
}
