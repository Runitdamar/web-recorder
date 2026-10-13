package com.runit.webrecorder

import android.graphics.Bitmap
import java.util.Locale

/**
 * One detected video: where it is, what we know about it, and its download state.
 * Fields are written from background threads and read from the UI thread.
 */
class VideoItem(
    val id: Int,
    val url: String,
    val referer: String?,
    val userAgent: String,
    val tabId: Int,
    val sourceHost: String,
    title: String
) {
    enum class Kind { FILE, HLS }
    enum class State { IDLE, QUEUED, DOWNLOADING, PAUSED, DONE, FAILED }

    @Volatile var kind: Kind = if (isPlaylistUrl(url)) Kind.HLS else Kind.FILE

    // ---- Metadata ----
    @Volatile var title: String = title
    @Volatile var titleFromPage: Boolean = false
    @Volatile var sizeBytes: Long = -1L
    @Volatile var mime: String? = null
    @Volatile var width: Int = 0
    @Volatile var height: Int = 0
    @Volatile var durationMs: Long = 0L
    @Volatile var bitrate: Long = 0L
    @Volatile var thumb: Bitmap? = null
    @Volatile var posterUrl: String? = null
    @Volatile var acceptsRanges: Boolean = false
    @Volatile var serverFileName: String? = null
    @Volatile var finalUrl: String? = null

    /** HLS only: quality levels and the parsed master playlist. */
    @Volatile var master: HlsMaster? = null
    @Volatile var variants: List<HlsVariant> = emptyList()

    /** True for things that turned out not to be a real video (audio, images, tiny files, variant playlists). */
    @Volatile var hidden: Boolean = false

    /** Why this can't be downloaded (DRM, live stream, ...), or null. */
    @Volatile var problem: String? = null

    /** Playlists / files related to this one that should not be listed separately. */
    @Volatile var related: List<String> = emptyList()

    // 0 = not started, 1 = loading, 2 = done, 3 = failed
    @Volatile var probeState: Int = 0
    @Volatile var detailsState: Int = 0

    // ---- Download state ----
    @Volatile var state: State = State.IDLE
    @Volatile var progress: Int = 0
    @Volatile var message: String = ""
    @Volatile var selected: Boolean = false
    @Volatile var speedBps: Long = 0L
    @Volatile var etaSec: Long = -1L
    @Volatile var doneBytes: Long = 0L

    /** HLS quality chosen for download (null = best). */
    @Volatile var chosenVariant: HlsVariant? = null

    /** Resume data kept by the downloader between pause and resume (in memory only). */
    @Volatile var engineState: Any? = null

    /** 0 = run, 1 = pause requested, 2 = cancel requested. Read by the downloader. */
    @Volatile var control: Int = 0

    fun isBusy(): Boolean = state == State.QUEUED || state == State.DOWNLOADING

    fun isActive(): Boolean = isBusy() || state == State.PAUSED

    fun isSelectable(): Boolean = (state == State.IDLE || state == State.FAILED) && problem == null

    fun typeLabel(): String {
        if (kind == Kind.HLS) return "HLS"
        val m = mime?.lowercase(Locale.US) ?: ""
        return when {
            m.contains("mp4") -> "MP4"
            m.contains("webm") -> "WEBM"
            m.contains("matroska") -> "MKV"
            m.contains("quicktime") -> "MOV"
            m.contains("3gpp") -> "3GP"
            else -> {
                val ext = url.substringBefore('?').substringBefore('#').substringAfterLast('.', "")
                if (ext.length in 2..4) ext.uppercase(Locale.US) else "VIDEO"
            }
        }
    }

    /** Resolution label such as "720p" or "1080p", or "". */
    fun qualityLabel(): String {
        if (height > 0) return if (height >= 2160) "4K" else "${height}p"
        val best = master?.best()
        if (best != null && best.height > 0) return best.label
        return ""
    }

    /** e.g. "1280 × 720 • 12.4 MB • 0:32" */
    fun metaLine(): String {
        val parts = ArrayList<String>()
        if (width > 0 && height > 0) parts.add("$width × $height")
        else if (kind == Kind.HLS) {
            val b = master?.best()
            if (b != null && b.height > 0) parts.add(b.label)
        }
        if (sizeBytes > 0) parts.add(formatSize(sizeBytes))
        else if (kind == Kind.HLS && bitrate > 0 && durationMs > 0) {
            parts.add("~" + formatSize(bitrate / 8 * (durationMs / 1000)))
        }
        if (durationMs > 0) parts.add(formatDuration(durationMs))
        if (parts.isNotEmpty()) return parts.joinToString(" • ")
        return if (probeState == 3 && detailsState == 3) "No info available" else "Reading info..."
    }

    /** e.g. "MP4 • example.com" */
    fun typeLine(): String {
        val extra = ArrayList<String>()
        extra.add(typeLabel())
        if (kind == Kind.HLS && variants.size > 1) extra.add("${variants.size} qualities")
        extra.add(sourceHost)
        return extra.joinToString(" • ")
    }

    /** Status text under the title while downloading / paused. */
    fun statusLine(): String {
        return when (state) {
            State.QUEUED -> "Waiting..."
            State.DOWNLOADING -> {
                val parts = ArrayList<String>()
                if (message.isNotEmpty()) parts.add(message)
                val sp = DownloadUtil.formatSpeed(speedBps)
                if (sp.isNotEmpty()) parts.add(sp)
                val eta = DownloadUtil.formatEta(etaSec)
                if (eta.isNotEmpty()) parts.add(eta + " left")
                if (parts.isEmpty()) "Starting..." else parts.joinToString(" • ")
            }
            State.PAUSED -> "Paused" + if (progress in 0..100) " • $progress%" else ""
            State.DONE -> "Saved • $message"
            State.FAILED -> "Failed • $message"
            State.IDLE -> problem ?: ""
        }
    }

    companion object {
        fun isPlaylistUrl(url: String): Boolean {
            val p = url.lowercase(Locale.US).substringBefore('?').substringBefore('#')
            return p.endsWith(".m3u8") || p.endsWith(".m3u")
        }
    }
}

fun formatSize(bytes: Long): String {
    if (bytes <= 0) return ""
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0
    return when {
        gb >= 1.0 -> String.format(Locale.US, "%.2f GB", gb)
        mb >= 1.0 -> String.format(Locale.US, "%.1f MB", mb)
        else -> String.format(Locale.US, "%.0f KB", kb)
    }
}

fun formatDuration(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) {
        String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    } else {
        String.format(Locale.US, "%d:%02d", m, s)
    }
}
