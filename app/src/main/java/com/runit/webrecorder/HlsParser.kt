package com.runit.webrecorder

import java.net.URL

/** One quality level listed in an HLS master playlist. */
data class HlsVariant(
    val url: String,
    val bandwidth: Long,
    val width: Int,
    val height: Int,
    val codecs: String,
    val audioGroup: String?
) {
    val label: String
        get() = when {
            height >= 2160 -> "4K"
            height > 0 -> "${height}p"
            bandwidth > 0 -> "${bandwidth / 1000} kbps"
            else -> "Auto"
        }
}

/** A separate audio stream listed in a master playlist (#EXT-X-MEDIA TYPE=AUDIO). */
data class HlsAudio(val groupId: String, val name: String, val url: String?, val isDefault: Boolean)

class HlsMaster(val variants: List<HlsVariant>, val audio: List<HlsAudio>) {

    fun best(): HlsVariant? =
        variants.maxWithOrNull(compareBy<HlsVariant>({ it.height }, { it.bandwidth }))

    /** The separate audio stream that goes with this variant, if it has one. */
    fun audioFor(v: HlsVariant): HlsAudio? {
        val group = v.audioGroup ?: return null
        val list = audio.filter { it.groupId == group && it.url != null }
        return list.firstOrNull { it.isDefault } ?: list.firstOrNull()
    }

    /** Every playlist URL this master points to (so the page's own requests for them can be hidden). */
    fun relatedUrls(): List<String> {
        val out = ArrayList<String>()
        for (v in variants) out.add(v.url)
        for (a in audio) {
            val u = a.url
            if (u != null) out.add(u)
        }
        return out
    }
}

class HlsKey(val method: String, val uri: String?, val iv: ByteArray?)

class HlsMapInfo(val url: String, val offset: Long, val length: Long)

class HlsSegment(
    val url: String,
    val duration: Double,
    val offset: Long,
    val length: Long,
    val keyIndex: Int
)

class HlsMedia(
    val segments: List<HlsSegment>,
    val keys: List<HlsKey>,
    val map: HlsMapInfo?,
    val mediaSequence: Long,
    val ended: Boolean
) {
    val totalDuration: Double
        get() = segments.sumOf { it.duration }
}

object HlsParser {

    fun isMaster(text: String): Boolean = text.contains("#EXT-X-STREAM-INF")

    fun resolve(base: String, ref: String): String {
        return try {
            URL(URL(base), ref).toString()
        } catch (e: Exception) {
            ref
        }
    }

    /** Parses `KEY=value,KEY2="quoted, value"` lists. */
    fun parseAttrs(s: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        var i = 0
        val n = s.length
        while (i < n) {
            val eq = s.indexOf('=', i)
            if (eq < 0) break
            val key = s.substring(i, eq).trim()
            var j = eq + 1
            val value: String
            if (j < n && s[j] == '"') {
                val close = s.indexOf('"', j + 1)
                val end = if (close < 0) n else close
                value = s.substring(j + 1, end)
                j = if (close < 0) n else close + 1
            } else {
                val comma = s.indexOf(',', j)
                val end = if (comma < 0) n else comma
                value = s.substring(j, end).trim()
                j = end
            }
            out[key] = value
            i = if (j < n && s[j] == ',') j + 1 else j
        }
        return out
    }

    fun parseMaster(text: String, base: String): HlsMaster {
        val variants = ArrayList<HlsVariant>()
        val audio = ArrayList<HlsAudio>()
        val lines = text.lines().map { it.trim() }
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.startsWith("#EXT-X-STREAM-INF:")) {
                val a = parseAttrs(line.substringAfter(':'))
                var j = i + 1
                while (j < lines.size && (lines[j].isEmpty() || lines[j].startsWith("#"))) j++
                if (j < lines.size) {
                    val res = a["RESOLUTION"] ?: ""
                    val w = res.substringBefore('x', "").toIntOrNull() ?: 0
                    val h = res.substringAfter('x', "").toIntOrNull() ?: 0
                    val bw = a["BANDWIDTH"]?.toLongOrNull() ?: a["AVERAGE-BANDWIDTH"]?.toLongOrNull() ?: 0L
                    variants.add(HlsVariant(resolve(base, lines[j]), bw, w, h, a["CODECS"] ?: "", a["AUDIO"]))
                    i = j
                }
            } else if (line.startsWith("#EXT-X-MEDIA:")) {
                val a = parseAttrs(line.substringAfter(':'))
                if (a["TYPE"] == "AUDIO") {
                    val uri = a["URI"]
                    audio.add(
                        HlsAudio(
                            a["GROUP-ID"] ?: "",
                            a["NAME"] ?: "",
                            if (uri.isNullOrEmpty()) null else resolve(base, uri),
                            a["DEFAULT"] == "YES"
                        )
                    )
                }
            }
            i++
        }
        return HlsMaster(variants, audio)
    }

    fun parseMedia(text: String, base: String): HlsMedia {
        val segs = ArrayList<HlsSegment>()
        val keys = ArrayList<HlsKey>()
        var map: HlsMapInfo? = null
        var seq = 0L
        var ended = false
        var curKey = -1
        var pendingDur = 0.0
        var pendingLen = -1L
        var pendingOff = -1L
        var lastEnd = 0L
        var lastRangeUrl = ""

        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isEmpty()) continue

            if (line.startsWith("#")) {
                when {
                    line.startsWith("#EXT-X-MEDIA-SEQUENCE:") ->
                        seq = line.substringAfter(':').trim().toLongOrNull() ?: 0L

                    line.startsWith("#EXT-X-ENDLIST") -> ended = true

                    line.startsWith("#EXT-X-KEY:") -> {
                        val a = parseAttrs(line.substringAfter(':'))
                        val method = a["METHOD"] ?: "NONE"
                        if (method == "NONE") {
                            curKey = -1
                        } else {
                            val uri = a["URI"]
                            keys.add(
                                HlsKey(
                                    method,
                                    if (uri.isNullOrEmpty()) null else resolve(base, uri),
                                    parseHexIv(a["IV"])
                                )
                            )
                            curKey = keys.size - 1
                        }
                    }

                    line.startsWith("#EXT-X-MAP:") -> {
                        if (map == null) {
                            val a = parseAttrs(line.substringAfter(':'))
                            val uri = a["URI"]
                            if (!uri.isNullOrEmpty()) {
                                val br = a["BYTERANGE"]
                                var len = -1L
                                var off = -1L
                                if (br != null) {
                                    len = br.substringBefore('@').toLongOrNull() ?: -1L
                                    off = if (br.contains('@')) br.substringAfter('@').toLongOrNull() ?: 0L else 0L
                                }
                                map = HlsMapInfo(resolve(base, uri), if (len >= 0) off else -1L, len)
                            }
                        }
                    }

                    line.startsWith("#EXTINF:") ->
                        pendingDur = line.substringAfter(':').substringBefore(',').trim().toDoubleOrNull() ?: 0.0

                    line.startsWith("#EXT-X-BYTERANGE:") -> {
                        val v = line.substringAfter(':').trim()
                        pendingLen = v.substringBefore('@').toLongOrNull() ?: -1L
                        pendingOff = if (v.contains('@')) v.substringAfter('@').toLongOrNull() ?: -1L else -1L
                    }
                }
            } else {
                val url = resolve(base, line)
                var off = -1L
                var len = -1L
                if (pendingLen >= 0) {
                    off = if (pendingOff >= 0) pendingOff else if (lastRangeUrl == url) lastEnd else 0L
                    len = pendingLen
                    lastEnd = off + len
                    lastRangeUrl = url
                }
                segs.add(HlsSegment(url, pendingDur, off, len, curKey))
                pendingDur = 0.0
                pendingLen = -1L
                pendingOff = -1L
            }
        }
        return HlsMedia(segs, keys, map, seq, ended)
    }

    /** "0x1234..." to a 16 byte IV (left padded), or null. */
    fun parseHexIv(s: String?): ByteArray? {
        if (s.isNullOrEmpty()) return null
        var h = s.trim()
        if (h.startsWith("0x") || h.startsWith("0X")) h = h.substring(2)
        if (h.isEmpty() || h.length > 32) return null
        if (h.length % 2 == 1) h = "0$h"
        val raw = ByteArray(h.length / 2)
        for (i in raw.indices) {
            val b = h.substring(i * 2, i * 2 + 2).toIntOrNull(16) ?: return null
            raw[i] = b.toByte()
        }
        val out = ByteArray(16)
        System.arraycopy(raw, 0, out, 16 - raw.size, raw.size)
        return out
    }
}
