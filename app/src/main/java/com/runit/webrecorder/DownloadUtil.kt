package com.runit.webrecorder

import java.util.Locale

/**
 * Small pure helpers used by the downloader (no Android classes, so they are easy to test).
 */
object DownloadUtil {

    const val MIN_SPLIT_BYTES = 1_000_000L
    const val MAX_RETRIES = 5

    /** How many parallel connections to use for a file of this size. */
    fun connectionsFor(total: Long): Int = when {
        total < MIN_SPLIT_BYTES -> 1
        total < 8_000_000L -> 2
        total < 64_000_000L -> 4
        else -> 6
    }

    /** Splits [0, total) into [parts] inclusive ranges: each entry is [start, end]. */
    fun splitRanges(total: Long, parts: Int): List<LongArray> {
        var n = if (parts < 1) 1 else parts
        if (total < n.toLong()) n = 1
        val chunk = total / n
        val out = ArrayList<LongArray>()
        var start = 0L
        for (i in 0 until n) {
            val end = if (i == n - 1) total - 1 else start + chunk - 1
            out.add(longArrayOf(start, end))
            start = end + 1
        }
        return out
    }

    /** Reads the file name out of a Content-Disposition header, or null. */
    fun parseFileName(header: String?): String? {
        if (header.isNullOrEmpty()) return null

        val star = Regex("filename\\*\\s*=\\s*([^;]+)", RegexOption.IGNORE_CASE).find(header)
        if (star != null) {
            val v = star.groupValues[1].trim().trim('"')
            val raw = if (v.contains("''")) v.substringAfter("''") else v
            val decoded = try {
                java.net.URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8")
            } catch (e: Exception) {
                raw
            }
            if (decoded.isNotBlank()) return decoded
        }

        val plain = Regex("filename\\s*=\\s*(\"([^\"]*)\"|[^;]+)", RegexOption.IGNORE_CASE).find(header)
        if (plain != null) {
            val quoted = plain.groupValues[2]
            val v = if (quoted.isNotEmpty()) quoted else plain.groupValues[1].trim()
            if (v.isNotBlank()) return v
        }
        return null
    }

    /** Makes a string safe to use as a file name (without extension). */
    fun cleanFileBase(s: String, maxLen: Int = 80): String {
        var t = s.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ")
        t = t.replace(Regex("\\s+"), " ").trim()
        if (t.length > maxLen) t = t.substring(0, maxLen).trim()
        t = t.trimEnd('.', ' ')
        return t
    }

    fun formatSpeed(bps: Long): String {
        if (bps <= 0) return ""
        val kb = bps / 1024.0
        val mb = kb / 1024.0
        return if (mb >= 1.0) String.format(Locale.US, "%.1f MB/s", mb)
        else String.format(Locale.US, "%.0f KB/s", kb)
    }

    fun formatEta(sec: Long): String {
        if (sec < 0) return ""
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        return when {
            h > 0 -> String.format(Locale.US, "%dh %02dm", h, m)
            m > 0 -> String.format(Locale.US, "%dm %02ds", m, s)
            else -> "${s}s"
        }
    }

    fun formatBitrate(bps: Long): String {
        if (bps <= 0) return ""
        val mbps = bps / 1_000_000.0
        return if (mbps >= 1.0) String.format(Locale.US, "%.1f Mbps", mbps)
        else String.format(Locale.US, "%d kbps", bps / 1000)
    }

    /** IV for AES-128 HLS when the playlist gives none: the segment sequence number, big endian. */
    fun ivFromSequence(seq: Long): ByteArray {
        val iv = ByteArray(16)
        var v = seq
        for (i in 15 downTo 8) {
            iv[i] = (v and 0xff).toByte()
            v = v shr 8
        }
        return iv
    }
}

/** Measures download speed over a short sliding window. */
class SpeedMeter(private val windowMs: Long = 4000L) {
    private val times = ArrayDeque<Long>()
    private val bytes = ArrayDeque<Long>()

    fun add(nowMs: Long, totalBytes: Long) {
        times.addLast(nowMs)
        bytes.addLast(totalBytes)
        while (times.size > 2 && nowMs - times.first() > windowMs) {
            times.removeFirst()
            bytes.removeFirst()
        }
    }

    /** Bytes per second. */
    fun speed(): Long {
        if (times.size < 2) return 0L
        val dt = times.last() - times.first()
        if (dt <= 0L) return 0L
        val db = bytes.last() - bytes.first()
        return if (db <= 0L) 0L else db * 1000L / dt
    }
}
