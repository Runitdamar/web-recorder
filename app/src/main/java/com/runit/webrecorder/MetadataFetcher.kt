package com.runit.webrecorder

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.webkit.CookieManager
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Finds out what a detected video is without downloading it.
 *  - fetchBasic: a tiny range request for file size and type (cheap, runs for every video)
 *  - loadDetails: reads resolution, duration and a thumbnail (heavier, runs on demand)
 */
object MetadataFetcher {

    private val pool = Executors.newFixedThreadPool(3)

    fun fetchBasic(item: VideoItem, onDone: () -> Unit) {
        pool.execute {
            try {
                basic(item)
            } catch (_: Exception) {
            }
            onDone()
        }
    }

    fun loadDetails(item: VideoItem, onDone: () -> Unit) {
        synchronized(item) {
            if (item.detailsState != 0) return
            item.detailsState = 1
        }
        pool.execute {
            try {
                retrieve(item)
            } catch (_: Exception) {
            }
            item.detailsState = if (item.width > 0 || item.durationMs > 0) 2 else 3
            onDone()
        }
    }

    private fun cookieFor(url: String): String? {
        return try {
            CookieManager.getInstance().getCookie(url)
        } catch (_: Exception) {
            null
        }
    }

    private fun basic(item: VideoItem) {
        val conn = URL(item.url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("Range", "bytes=0-0")
            conn.setRequestProperty("User-Agent", item.userAgent)
            val ref = item.referer
            if (!ref.isNullOrEmpty()) conn.setRequestProperty("Referer", ref)
            val cookie = cookieFor(item.url)
            if (!cookie.isNullOrEmpty()) conn.setRequestProperty("Cookie", cookie)

            val code = conn.responseCode
            if (code == 206 || code == 200) {
                var total = -1L
                val range = conn.getHeaderField("Content-Range")
                if (range != null && range.contains("/")) {
                    total = range.substringAfter('/').trim().toLongOrNull() ?: -1L
                }
                if (total <= 0 && code == 200) {
                    total = conn.contentLengthLong
                }
                item.sizeBytes = total
                val type = conn.contentType
                if (type != null) {
                    item.mime = type.substringBefore(';').trim()
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun retrieve(item: VideoItem) {
        val r = MediaMetadataRetriever()
        try {
            val headers = HashMap<String, String>()
            headers["User-Agent"] = item.userAgent
            val ref = item.referer
            if (!ref.isNullOrEmpty()) headers["Referer"] = ref
            val cookie = cookieFor(item.url)
            if (!cookie.isNullOrEmpty()) headers["Cookie"] = cookie

            r.setDataSource(item.url, headers)

            var w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            var h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rotation = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rotation == 90 || rotation == 270) {
                val t = w
                w = h
                h = t
            }
            item.width = w
            item.height = h
            item.durationMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L

            val frame = r.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            if (frame != null) {
                item.thumb = scaled(frame, 200)
            }
        } finally {
            try {
                r.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun scaled(src: Bitmap, maxSide: Int): Bitmap {
        val w = src.width
        val h = src.height
        val longest = maxOf(w, h)
        if (longest <= maxSide) return src
        val scale = maxSide.toFloat() / longest.toFloat()
        val nw = (w * scale).toInt().coerceAtLeast(1)
        val nh = (h * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, nw, nh, true)
    }
}
