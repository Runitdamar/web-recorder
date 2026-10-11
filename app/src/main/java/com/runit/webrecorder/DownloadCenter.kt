package com.runit.webrecorder

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.CookieManager
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Downloads videos one or two at a time and keeps each VideoItem's progress up to date.
 * Files end up in Movies/WebRecorder.
 */
object DownloadCenter {

    private val EXTS = listOf(".mp4", ".webm", ".mkv", ".mov", ".m4v", ".3gp")
    private val pool = Executors.newFixedThreadPool(2)

    /** Short messages for the UI to show as toasts. */
    @Volatile
    var onEvent: ((String) -> Unit)? = null

    @Volatile
    var onChange: (() -> Unit)? = null

    fun enqueue(ctx: Context, item: VideoItem) {
        if (item.isBusy()) return
        item.state = VideoItem.State.QUEUED
        item.progress = 0
        item.message = ""
        onChange?.invoke()
        val app = ctx.applicationContext
        pool.execute { process(app, item) }
    }

    private fun process(ctx: Context, item: VideoItem) {
        item.state = VideoItem.State.DOWNLOADING
        onChange?.invoke()
        try {
            val name = doDownload(ctx, item)
            item.message = name
            item.progress = 100
            item.state = VideoItem.State.DONE
            onEvent?.invoke("Saved to Movies/WebRecorder: $name")
        } catch (e: Exception) {
            item.message = e.message ?: "unknown error"
            item.state = VideoItem.State.FAILED
            onEvent?.invoke("Download failed: ${item.message}")
        }
        onChange?.invoke()
    }

    private fun doDownload(ctx: Context, item: VideoItem): String {
        val conn = URL(item.url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", item.userAgent)
        val ref = item.referer
        if (!ref.isNullOrEmpty()) conn.setRequestProperty("Referer", ref)
        val cookie = try {
            CookieManager.getInstance().getCookie(item.url)
        } catch (_: Exception) {
            null
        }
        if (!cookie.isNullOrEmpty()) conn.setRequestProperty("Cookie", cookie)

        val code = conn.responseCode
        if (code !in 200..299) {
            conn.disconnect()
            throw IOException("HTTP $code")
        }
        val total = conn.contentLengthLong
        val name = fileName(item)
        val mime = mimeFor(name)

        var opened: OutputStream? = null
        var uri: Uri? = null
        var plainFile: File? = null
        val resolver = ctx.contentResolver

        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues()
                values.put(MediaStore.Video.Media.DISPLAY_NAME, name)
                values.put(MediaStore.Video.Media.MIME_TYPE, mime)
                values.put(
                    MediaStore.Video.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_MOVIES + "/WebRecorder"
                )
                values.put(MediaStore.Video.Media.IS_PENDING, 1)
                val created = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                    ?: throw IOException("cannot create file")
                uri = created
                opened = resolver.openOutputStream(created)
            } else {
                val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: ctx.filesDir
                dir.mkdirs()
                val f = File(dir, name)
                plainFile = f
                opened = f.outputStream()
            }

            // Fixed reference, safe to use inside the lambda below
            val stream: OutputStream = opened ?: throw IOException("cannot open file")

            val buf = ByteArray(64 * 1024)
            var done = 0L
            conn.inputStream.use { input ->
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    stream.write(buf, 0, n)
                    done += n
                    item.progress = if (total > 0) ((done * 100) / total).toInt() else -1
                }
            }
            stream.flush()
            stream.close()
            opened = null

            if (done == 0L) {
                throw IOException("empty file")
            }

            val finished = uri
            if (finished != null) {
                val update = ContentValues()
                update.put(MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(finished, update, null, null)
            }
            return name
        } catch (e: Exception) {
            try {
                opened?.close()
            } catch (_: Exception) {
            }
            val broken = uri
            if (broken != null) {
                try {
                    resolver.delete(broken, null, null)
                } catch (_: Exception) {
                }
            }
            plainFile?.delete()
            throw e
        } finally {
            conn.disconnect()
        }
    }

    private fun fileName(item: VideoItem): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        var base = item.title.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().replace(' ', '_')
        if (base.length > 40) base = base.substring(0, 40)
        if (base.isEmpty()) base = "video"

        val path = item.url.substringBefore('?').substringBefore('#').lowercase(Locale.US)
        var ext = ".mp4"
        val fromUrl = EXTS.firstOrNull { path.endsWith(it) }
        if (fromUrl != null) {
            ext = fromUrl
        } else if (item.mime?.contains("webm") == true) {
            ext = ".webm"
        }
        return "${base}_${stamp}_${item.id}$ext"
    }

    private fun mimeFor(name: String): String {
        val n = name.lowercase(Locale.US)
        return when {
            n.endsWith(".webm") -> "video/webm"
            n.endsWith(".mkv") -> "video/x-matroska"
            n.endsWith(".mov") -> "video/quicktime"
            n.endsWith(".3gp") -> "video/3gpp"
            else -> "video/mp4"
        }
    }
}
