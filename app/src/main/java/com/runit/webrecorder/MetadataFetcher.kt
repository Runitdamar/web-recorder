package com.runit.webrecorder

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import java.util.concurrent.Executors

/**
 * Finds out what a detected video is without downloading it.
 *  - probe: cheap network look (size, type, range support, file name; HLS playlist and qualities)
 *  - loadPoster: the page's own poster image
 *  - loadDetails: resolution, duration, bitrate and a preview frame (heavier, on demand)
 */
object MetadataFetcher {

    private const val MIN_VIDEO_BYTES = 150_000L

    private val pool = Executors.newFixedThreadPool(3)
    private val detailPool = Executors.newFixedThreadPool(2)

    fun probe(item: VideoItem, onDone: () -> Unit) {
        synchronized(item) {
            if (item.probeState != 0) return
            item.probeState = 1
        }
        pool.execute {
            probeNow(item)
            onDone()
        }
    }

    /** Runs the probe on the calling thread (used by the downloader if it has not run yet). */
    fun probeNow(item: VideoItem) {
        synchronized(item) {
            if (item.probeState == 2) return
            item.probeState = 1
        }
        try {
            if (item.kind == VideoItem.Kind.HLS) probeHls(item) else probeFile(item)
            item.probeState = 2
        } catch (_: Exception) {
            item.probeState = 3
        }
    }

    fun loadPoster(item: VideoItem, onDone: () -> Unit) {
        val url = item.posterUrl ?: return
        if (item.thumb != null) return
        pool.execute {
            try {
                val bytes = Http.getBytes(url, item.userAgent, item.referer, 3_000_000)
                val opts = BitmapFactory.Options()
                opts.inJustDecodeBounds = true
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                var sample = 1
                while (opts.outWidth / sample > 480 && opts.outHeight / sample > 480) sample *= 2
                val o2 = BitmapFactory.Options()
                o2.inSampleSize = sample
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o2)
                if (bmp != null && item.thumb == null) {
                    item.thumb = scaled(bmp, 240)
                }
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
        detailPool.execute {
            try {
                if (item.kind == VideoItem.Kind.FILE) retrieve(item)
            } catch (_: Exception) {
            }
            val ok = item.width > 0 || item.durationMs > 0 || item.thumb != null
            item.detailsState = if (ok) 2 else 3
            onDone()
        }
    }

    // ---------------------------------------------------------------- FILE

    private fun probeFile(item: VideoItem) {
        val o = Http.open(item.url, item.userAgent, item.referer, "bytes=0-0", 10000, 10000)
        try {
            val conn = o.conn
            val code = conn.responseCode
            item.finalUrl = o.finalUrl
            if (code != 206 && code != 200) {
                item.hidden = code in 400..499 && code != 416
                throw java.io.IOException("HTTP $code")
            }
            var total = -1L
            val cr = conn.getHeaderField("Content-Range")
            if (cr != null && cr.contains("/")) {
                total = cr.substringAfter('/').trim().toLongOrNull() ?: -1L
            }
            if (total <= 0 && code == 200) total = conn.contentLengthLong
            item.sizeBytes = total
            item.acceptsRanges = code == 206 ||
                (conn.getHeaderField("Accept-Ranges") ?: "").contains("bytes", true)

            val type = conn.contentType?.substringBefore(';')?.trim()?.lowercase()
            if (!type.isNullOrEmpty()) item.mime = type

            val name = DownloadUtil.parseFileName(conn.getHeaderField("Content-Disposition"))
            if (name != null) item.serverFileName = name

            // Not really a video?
            if (type != null && (type.startsWith("audio/") || type.startsWith("image/") ||
                    type.startsWith("text/") || type == "application/json")
            ) {
                item.hidden = true
            } else if (total in 1 until MIN_VIDEO_BYTES) {
                item.hidden = true
            }
            if (type == "application/vnd.apple.mpegurl" || type == "application/x-mpegurl") {
                item.kind = VideoItem.Kind.HLS
            }
        } finally {
            o.conn.disconnect()
        }
    }

    // ---------------------------------------------------------------- HLS

    private fun probeHls(item: VideoItem) {
        val text = Http.getText(item.url, item.userAgent, item.referer)
        if (!text.contains("#EXTM3U")) {
            item.hidden = true
            throw java.io.IOException("not a playlist")
        }
        item.mime = "application/x-mpegURL"
        var mediaText = text
        var mediaBase = item.url

        if (HlsParser.isMaster(text)) {
            val master = HlsParser.parseMaster(text, item.url)
            if (master.variants.isEmpty()) throw java.io.IOException("empty master")
            item.master = master
            item.variants = master.variants.sortedByDescending { it.height * 100000L + it.bandwidth }
            item.related = master.relatedUrls()
            val best = master.best()!!
            item.width = best.width
            item.height = best.height
            item.bitrate = best.bandwidth
            mediaBase = best.url
            mediaText = Http.getText(best.url, item.userAgent, item.referer)
        }

        val media = HlsParser.parseMedia(mediaText, mediaBase)
        if (media.segments.isEmpty()) throw java.io.IOException("no segments")
        item.durationMs = (media.totalDuration * 1000).toLong()
        item.problem = when {
            !media.ended -> "Live stream (not supported yet)"
            media.keys.any { it.method != "AES-128" } -> "Protected stream (DRM)"
            else -> null
        }
    }

    // ---------------------------------------------------------------- details

    private fun retrieve(item: VideoItem) {
        val r = MediaMetadataRetriever()
        try {
            val headers = HashMap<String, String>()
            headers["User-Agent"] = item.userAgent
            val ref = item.referer
            if (!ref.isNullOrEmpty()) headers["Referer"] = ref
            val cookie = Http.cookieFor(item.url)
            if (!cookie.isNullOrEmpty()) headers["Cookie"] = cookie

            r.setDataSource(item.finalUrl ?: item.url, headers)

            var w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            var h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rotation = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rotation == 90 || rotation == 270) {
                val t = w
                w = h
                h = t
            }
            if (w > 0) item.width = w
            if (h > 0) item.height = h
            val d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            if (d > 0) item.durationMs = d
            val br = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull() ?: 0L
            if (br > 0) item.bitrate = br

            if (item.thumb == null) {
                val frame = r.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                if (frame != null) item.thumb = scaled(frame, 240)
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
