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
    val title: String
) {
    enum class State { IDLE, QUEUED, DOWNLOADING, DONE, FAILED }

    // Metadata
    @Volatile var sizeBytes: Long = -1L
    @Volatile var mime: String? = null
    @Volatile var width: Int = 0
    @Volatile var height: Int = 0
    @Volatile var durationMs: Long = 0L
    @Volatile var thumb: Bitmap? = null

    // 0 = not started, 1 = loading, 2 = done, 3 = failed
    @Volatile var detailsState: Int = 0

    // Download state
    @Volatile var state: State = State.IDLE
    @Volatile var progress: Int = 0
    @Volatile var message: String = ""
    @Volatile var selected: Boolean = false

    fun isBusy(): Boolean = state == State.QUEUED || state == State.DOWNLOADING

    fun isSelectable(): Boolean = state == State.IDLE || state == State.FAILED

    fun typeLabel(): String {
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

    /** e.g. "1280 × 720 • 12.4 MB • 0:32" */
    fun metaLine(): String {
        val parts = ArrayList<String>()
        if (width > 0 && height > 0) parts.add("$width × $height")
        if (sizeBytes > 0) parts.add(formatSize(sizeBytes))
        if (durationMs > 0) parts.add(formatDuration(durationMs))
        if (parts.isNotEmpty()) return parts.joinToString(" • ")
        return if (detailsState == 3) "No info available" else "Reading info..."
    }

    /** e.g. "MP4 • example.com" */
    fun typeLine(): String = typeLabel() + " • " + sourceHost
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
