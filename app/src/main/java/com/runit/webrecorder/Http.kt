package com.runit.webrecorder

import android.webkit.CookieManager
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** Small HTTP helper: manual redirects (including http -> https), cookies, referer, user agent. */
object Http {

    class Opened(val conn: HttpURLConnection, val finalUrl: String)

    fun cookieFor(url: String): String? {
        return try {
            CookieManager.getInstance().getCookie(url)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Opens [url] and follows up to 8 redirects. The caller must disconnect the connection.
     * [range] is a full Range header value such as "bytes=0-99", or null.
     */
    fun open(
        url: String,
        ua: String,
        referer: String?,
        range: String?,
        connectMs: Int = 12000,
        readMs: Int = 25000
    ): Opened {
        var current = url
        var hops = 0
        while (true) {
            val conn = URL(current).openConnection() as HttpURLConnection
            conn.connectTimeout = connectMs
            conn.readTimeout = readMs
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("User-Agent", ua)
            conn.setRequestProperty("Accept-Encoding", "identity")
            conn.setRequestProperty("Accept", "*/*")
            if (!referer.isNullOrEmpty()) conn.setRequestProperty("Referer", referer)
            val cookie = cookieFor(current)
            if (!cookie.isNullOrEmpty()) conn.setRequestProperty("Cookie", cookie)
            if (range != null) conn.setRequestProperty("Range", range)

            val code = conn.responseCode
            if (code in 300..399 && code != 304) {
                val loc = conn.getHeaderField("Location")
                conn.disconnect()
                if (loc.isNullOrEmpty() || ++hops > 8) throw IOException("too many redirects")
                current = HlsParser.resolve(current, loc)
                continue
            }
            return Opened(conn, current)
        }
    }

    fun readLimited(input: InputStream, maxBytes: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (out.size() < maxBytes) {
            val n = input.read(buf, 0, minOf(buf.size, maxBytes - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    fun getBytes(url: String, ua: String, referer: String?, maxBytes: Int = 4_000_000): ByteArray {
        val o = open(url, ua, referer, null)
        try {
            if (o.conn.responseCode !in 200..299) throw IOException("HTTP ${o.conn.responseCode}")
            return o.conn.inputStream.use { readLimited(it, maxBytes) }
        } finally {
            o.conn.disconnect()
        }
    }

    fun getText(url: String, ua: String, referer: String?, maxBytes: Int = 2_000_000): String =
        String(getBytes(url, ua, referer, maxBytes), Charsets.UTF_8)
}
