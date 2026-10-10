package com.runit.webrecorder

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import java.io.File
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Watches page requests for video files and downloads them.
 */
class VideoSniffer {

    data class Found(val url: String, val referer: String?)

    companion object {
        private val EXTS = listOf(".mp4", ".webm", ".mkv", ".mov", ".m4v", ".3gp")
        private const val MAX_ITEMS = 30

        // Injected into every page: tells the app when a video plays or ends
        const val HOOK_JS = """
(function() {
  if (window.__wrHooked) return;
  window.__wrHooked = true;
  function vid(e) {
    var t = e.target;
    return (t && t.tagName === 'VIDEO') ? t : null;
  }
  document.addEventListener('playing', function(e) {
    var v = vid(e);
    if (v) WRBridge.onPlaying(v.currentSrc || v.src || '');
  }, true);
  document.addEventListener('ended', function(e) {
    var v = vid(e);
    if (v) WRBridge.onEnded();
  }, true);
})();
"""
    }

    private val items = LinkedHashMap<String, Found>()

    @Volatile
    var pageUrl: String? = null

    @Volatile
    var downloading = false

    @Volatile
    var progress = 0

    fun count(): Int = synchronized(items) { items.size }

    fun list(): List<Found> = synchronized(items) { items.values.toList() }

    fun clear() {
        synchronized(items) { items.clear() }
    }

    // Called for every network request the page makes (background thread)
    fun checkRequest(url: String) {
        val lower = url.lowercase()
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return
        val path = lower.substringBefore('?').substringBefore('#')
        if (EXTS.none { path.endsWith(it) }) return
        add(url)
    }

    // Called when a <video> starts playing (catches URLs without .mp4 in them)
    fun addFromPage(src: String) {
        val lower = src.lowercase()
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return
        val path = lower.substringBefore('?').substringBefore('#')
        if (path.endsWith(".m3u8") || path.endsWith(".mpd")) return
        add(src)
    }

    private fun add(url: String) {
        synchronized(items) {
            if (items.containsKey(url)) return
            if (items.size >= MAX_ITEMS) {
                val first = items.keys.first()
                items.remove(first)
            }
            items[url] = Found(url, pageUrl)
        }
    }

    fun download(ctx: Context, item: Found, userAgent: String, onDone: (String) -> Unit) {
        if (downloading) {
            onDone("Already downloading")
            return
        }
        downloading = true
        progress = 0
        val app = ctx.applicationContext
        Thread {
            val msg = try {
                doDownload(app, item, userAgent)
            } catch (e: Exception) {
                "Download failed: ${e.message}"
            }
            downloading = false
            onDone(msg)
        }.start()
    }

    private fun doDownload(ctx: Context, item: Found, userAgent: String): String {
        val conn = URL(item.url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", userAgent)
        if (!item.referer.isNullOrEmpty()) {
            conn.setRequestProperty("Referer", item.referer)
        }
        val cookie = CookieManager.getInstance().getCookie(item.url)
        if (!cookie.isNullOrEmpty()) {
            conn.setRequestProperty("Cookie", cookie)
        }

        val code = conn.responseCode
        if (code !in 200..299) {
            conn.disconnect()
            return "Download failed: HTTP $code"
        }
        val total = conn.contentLengthLong
        val name = fileName(item.url)
        val mime = mimeFor(name)

        var opened: OutputStream? = null
        var uri: android.net.Uri? = null
        var plainFile: File? = null
        val resolver = ctx.contentResolver

        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, name)
                    put(MediaStore.Video.Media.MIME_TYPE, mime)
                    put(
                        MediaStore.Video.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_MOVIES + "/WebRecorder"
                    )
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
                uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                if (uri == null) return "Download failed: cannot create file"
                opened = resolver.openOutputStream(uri)
            } else {
                val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: ctx.filesDir
                dir.mkdirs()
                plainFile = File(dir, name)
                opened = plainFile.outputStream()
            }

            // Fixed (non-changing) reference, safe to use inside the lambda below
            val stream: OutputStream = opened ?: return "Download failed: cannot open file"

            val buf = ByteArray(64 * 1024)
            var done = 0L
            conn.inputStream.use { input ->
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    stream.write(buf, 0, n)
                    done += n
                    progress = if (total > 0) ((done * 100) / total).toInt() else -1
                }
            }
            stream.flush()
            stream.close()
            opened = null

            if (done == 0L) {
                if (uri != null) resolver.delete(uri, null, null)
                plainFile?.delete()
                return "Download failed: empty file"
            }

            if (uri != null) {
                val values = ContentValues()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                return "Downloaded to Movies/WebRecorder: $name"
            }
            return "Downloaded: $name"
        } catch (e: Exception) {
            try { opened?.close() } catch (_: Exception) {}
            if (uri != null) resolver.delete(uri, null, null)
            plainFile?.delete()
            throw e
        } finally {
            conn.disconnect()
        }
    }

    private fun fileName(url: String): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val last = url.substringBefore('?').substringBefore('#').substringAfterLast('/')
        val clean = last.replace(Regex("[^A-Za-z0-9._-]"), "_").take(60)
        val hasExt = EXTS.any { clean.lowercase().endsWith(it) }
        return if (hasExt) "dl_${stamp}_$clean" else "dl_$stamp.mp4"
    }

    private fun mimeFor(name: String): String {
        val n = name.lowercase()
        return when {
            n.endsWith(".webm") -> "video/webm"
            n.endsWith(".mkv") -> "video/x-matroska"
            n.endsWith(".mov") -> "video/quicktime"
            n.endsWith(".3gp") -> "video/3gpp"
            else -> "video/mp4"
        }
    }
}

/**
 * Receives play / ended events from the injected JavaScript.
 * These methods run on a background thread, so the callbacks must post to the UI thread.
 */
class WebBridge(
    private val playing: (String) -> Unit,
    private val ended: () -> Unit
) {
    @JavascriptInterface
    fun onPlaying(src: String) {
        playing(src)
    }

    @JavascriptInterface
    fun onEnded() {
        ended()
    }
}
