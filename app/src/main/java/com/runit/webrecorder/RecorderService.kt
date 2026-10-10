package com.runit.webrecorder

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
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
import android.provider.MediaStore
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
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

        @Volatile
        var isSaving = false
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private var projection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var recorder: MediaRecorder? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var audioCapture: AudioCapture? = null
    private var videoFile: File? = null
    private var audioFile: File? = null
    private var finalFile: File? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording(intent)
            ACTION_STOP -> stopRecording()
            else -> if (!isRecording && !isSaving) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startRecording(intent: Intent) {
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

        if (isRecording || isSaving) {
            toast(if (isSaving) "Still saving previous recording" else "Already recording")
            return
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
            val cb = object : MediaProjection.Callback() {
                override fun onStop() {
                    stopRecording()
                }
            }
            projectionCallback = cb
            proj.registerCallback(cb, mainHandler)

            val metrics = resources.displayMetrics
            val width = metrics.widthPixels / 2 * 2
            val height = metrics.heightPixels / 2 * 2
            val dpi = metrics.densityDpi

            val dir = getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: filesDir
            dir.mkdirs()
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val vFile = File(dir, "vid_$stamp.mp4")
            val aFile = File(dir, "aud_$stamp.m4a")
            videoFile = vFile
            audioFile = aFile
            finalFile = File(dir, "rec_$stamp.mp4")

            val rec = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()
            rec.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            rec.setVideoSize(width, height)
            rec.setVideoFrameRate(30)
            rec.setVideoEncodingBitRate(4_000_000)
            rec.setOutputFile(vFile.absolutePath)
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

            // Internal audio (Android 10+ and mic permission needed)
            val micOk = ContextCompat.checkSelfPermission(
                this, Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED

            if (Build.VERSION.SDK_INT >= 29 && micOk) {
                val ac = AudioCapture(proj)
                if (ac.start(aFile)) {
                    audioCapture = ac
                } else {
                    toast("Audio capture unavailable, recording video only")
                }
            } else {
                toast("No audio permission, recording video only")
            }

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
            if (!isSaving) {
                cleanup()
                stopSelf()
            }
            return
        }

        isRecording = false
        isSaving = true

        // Hand everything to a background thread so the UI doesn't freeze
        val rec = recorder
        val vd = virtualDisplay
        val proj = projection
        val cb = projectionCallback
        val audio = audioCapture
        val video = videoFile
        val audioF = audioFile
        val out = finalFile

        recorder = null
        virtualDisplay = null
        projection = null
        projectionCallback = null
        audioCapture = null

        Thread {
            audio?.stop()

            var videoOk = true
            try { rec?.stop() } catch (e: RuntimeException) { videoOk = false }
            try { rec?.release() } catch (_: Exception) {}
            try { vd?.release() } catch (_: Exception) {}
            try { if (cb != null) proj?.unregisterCallback(cb) } catch (_: Exception) {}
            try { proj?.stop() } catch (_: Exception) {}

            val msg = finishFiles(
                videoOk,
                video,
                audioF,
                out,
                audio != null,
                audio?.hasData == true
            )
            toast(msg)

            isSaving = false
            mainHandler.post {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }.start()
    }

    private fun finishFiles(
        videoOk: Boolean,
        video: File?,
        audioF: File?,
        out: File?,
        audioStarted: Boolean,
        audioHasData: Boolean
    ): String {
        if (!videoOk || video == null || out == null || !video.exists() || video.length() == 0L) {
            video?.delete()
            audioF?.delete()
            return "Recording too short, nothing saved"
        }

        if (audioStarted && audioHasData && audioF != null && audioF.exists()) {
            if (MediaMerger.merge(video, audioF, out)) {
                video.delete()
                audioF.delete()
                return publish(out, "")
            }
            out.delete()
        }

        video.renameTo(out)
        audioF?.delete()
        return publish(out, " (no audio)")
    }

    // Copies the finished file into the public Movies/WebRecorder folder (Android 10+)
    private fun publish(file: File, note: String): String {
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(
                        MediaStore.Video.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_MOVIES + "/WebRecorder"
                    )
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
                val resolver = contentResolver
                val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    val os = resolver.openOutputStream(uri)
                    if (os != null) {
                        os.use { o -> file.inputStream().use { it.copyTo(o) } }
                        values.clear()
                        values.put(MediaStore.Video.Media.IS_PENDING, 0)
                        resolver.update(uri, values, null, null)
                        file.delete()
                        return "Saved to Movies/WebRecorder: ${file.name}$note"
                    } else {
                        resolver.delete(uri, null, null)
                    }
                }
            } catch (_: Exception) {
            }
        }
        return "Saved: ${file.name}$note"
    }

    private fun cleanup() {
        try { audioCapture?.stop() } catch (_: Exception) {}
        audioCapture = null
        try { recorder?.release() } catch (_: Exception) {}
        recorder = null
        try { virtualDisplay?.release() } catch (_: Exception) {}
        virtualDisplay = null
        try {
            val cb = projectionCallback
            if (cb != null) projection?.unregisterCallback(cb)
        } catch (_: Exception) {}
        projectionCallback = null
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
        mainHandler.post {
            Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        if (!isSaving) cleanup()
        super.onDestroy()
    }
}
