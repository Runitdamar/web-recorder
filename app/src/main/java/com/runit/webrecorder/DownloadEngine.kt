package com.runit.webrecorder

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray
import java.util.concurrent.atomic.AtomicReference

/** Thrown when the user paused (kind 1) or cancelled (kind 2). */
class Interrupted(val kind: Int) : Exception("interrupted")

/** The server ignored a Range request: fall back to one plain stream. */
class RangeUnsupported : IOException("range not supported")

class HttpFail(val code: Int) : IOException(
    when (code) {
        401, 403 -> "Blocked by the site (HTTP $code). Play the video again, then retry"
        404, 410 -> "Link expired (HTTP $code). Play the video again, then retry"
        else -> "HTTP $code"
    }
) {
    fun retryable(): Boolean = code >= 500 || code == 429 || code == 408
}

/** Keeps item.progress / speed / ETA up to date, throttled. */
class Tracker(private val item: VideoItem, @Volatile var total: Long) {
    private val done = AtomicLong(0)
    private val meter = SpeedMeter()
    private val started = System.currentTimeMillis()
    private var lastTick = 0L

    @Volatile private var segDone = 0
    @Volatile private var segTotal = 0

    fun setDone(n: Long) {
        done.set(n)
    }

    fun add(n: Long) {
        done.addAndGet(n)
        tick(false)
    }

    fun setSegments(d: Int, t: Int) {
        segDone = d
        segTotal = t
        tick(true)
    }

    @Synchronized
    fun tick(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastTick < 350) return
        lastTick = now
        val d = done.get()
        meter.add(now, d)
        item.doneBytes = d
        item.speedBps = meter.speed()
        if (total > 0) {
            item.progress = (d * 100 / total).toInt().coerceIn(0, 100)
            item.etaSec = if (item.speedBps > 0) (total - d) / item.speedBps else -1L
        } else if (segTotal > 0) {
            item.progress = (segDone * 100 / segTotal).coerceIn(0, 100)
            item.etaSec = if (segDone > 0) {
                ((now - started) / 1000.0 * (segTotal - segDone) / segDone).toLong()
            } else -1L
        } else {
            item.progress = -1
            item.etaSec = -1L
        }
        DownloadCenter.onChange?.invoke()
    }
}

fun checkControl(item: VideoItem) {
    val c = item.control
    if (c != 0) throw Interrupted(c)
}

/** Sleeps in short slices so pause / cancel react quickly. */
fun sleepControlled(item: VideoItem, ms: Long) {
    var left = ms
    while (left > 0) {
        checkControl(item)
        val s = minOf(200L, left)
        Thread.sleep(s)
        left -= s
    }
}

object Naming {
    private val VIDEO_EXTS = listOf("mp4", "webm", "mkv", "mov", "m4v", "3gp")

    fun baseName(item: VideoItem): String {
        var base = DownloadUtil.cleanFileBase(item.title)
        val generic = base.isEmpty() || Regex("Video \\d+").matches(base)
        if (generic) {
            val sn = item.serverFileName
            if (sn != null) {
                val b = DownloadUtil.cleanFileBase(sn.substringBeforeLast('.', sn))
                if (b.length >= 3) base = b
            }
        }
        if (base.isEmpty()) base = "video"
        return base
    }

    fun directExt(item: VideoItem): String {
        val fromServer = item.serverFileName?.substringAfterLast('.', "")?.lowercase(Locale.US)
        if (fromServer != null && fromServer in VIDEO_EXTS) return fromServer
        val path = item.url.substringBefore('?').substringBefore('#').lowercase(Locale.US)
        val fromUrl = path.substringAfterLast('.', "")
        if (fromUrl in VIDEO_EXTS) return fromUrl
        val m = item.mime ?: ""
        return when {
            m.contains("webm") -> "webm"
            m.contains("matroska") -> "mkv"
            m.contains("quicktime") -> "mov"
            m.contains("3gpp") -> "3gp"
            else -> "mp4"
        }
    }

    fun mimeFor(ext: String): String = when (ext) {
        "webm" -> "video/webm"
        "mkv" -> "video/x-matroska"
        "mov" -> "video/quicktime"
        "3gp" -> "video/3gpp"
        "ts" -> "video/mp2t"
        else -> "video/mp4"
    }
}

/** Moves a finished temp file into Movies/WebRecorder. */
object Publisher {
    fun publish(ctx: Context, src: File, displayName: String, mime: String): String {
        val resolver = ctx.contentResolver
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues()
            values.put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            values.put(MediaStore.Video.Media.MIME_TYPE, mime)
            values.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/WebRecorder")
            values.put(MediaStore.Video.Media.IS_PENDING, 1)
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("Cannot create file")
            try {
                val out = resolver.openOutputStream(uri) ?: throw IOException("Cannot open file")
                out.use { o -> src.inputStream().use { it.copyTo(o, 256 * 1024) } }
                val upd = ContentValues()
                upd.put(MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(uri, upd, null, null)
            } catch (e: Exception) {
                try {
                    resolver.delete(uri, null, null)
                } catch (_: Exception) {
                }
                throw e
            }
            return displayName
        }
        val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: ctx.filesDir
        dir.mkdirs()
        var f = File(dir, displayName)
        var n = 1
        val stem = displayName.substringBeforeLast('.')
        val ext = displayName.substringAfterLast('.', "")
        while (f.exists()) {
            f = File(dir, "$stem ($n).$ext")
            n++
        }
        src.inputStream().use { i -> f.outputStream().use { o -> i.copyTo(o, 256 * 1024) } }
        return f.name
    }
}

class DirectState(
    val total: Long,
    val ranges: List<LongArray>,
    val done: AtomicLongArray,
    val file: File
)

/** Downloads a plain video file using several parallel range connections, with retries and resume. */
object DirectDownloader {

    fun download(item: VideoItem, dir: File, tracker: Tracker): File {
        MetadataFetcher.probeNow(item)
        val total = item.sizeBytes
        val canSplit = total > 0 && item.acceptsRanges

        if (canSplit) {
            try {
                return multi(item, dir, tracker, total)
            } catch (e: RangeUnsupported) {
                item.engineState = null
                File(dir, "${item.id}.part").delete()
                // fall through to a single stream
            }
        }
        return single(item, dir, tracker)
    }

    private fun multi(item: VideoItem, dir: File, tracker: Tracker, total: Long): File {
        var st = item.engineState as? DirectState
        if (st == null || st.total != total || !st.file.exists()) {
            if (dir.usableSpace < total + total / 10) throw IOException("Not enough storage space")
            val parts = DownloadUtil.connectionsFor(total)
            val file = File(dir, "${item.id}.part")
            RandomAccessFile(file, "rw").use { it.setLength(total) }
            val ranges = DownloadUtil.splitRanges(total, parts)
            st = DirectState(total, ranges, AtomicLongArray(ranges.size), file)
            item.engineState = st
        }
        val state = st

        var already = 0L
        for (i in state.ranges.indices) already += state.done.get(i)
        tracker.total = total
        tracker.setDone(already)

        val abort = AtomicBoolean(false)
        val error = AtomicReference<Throwable?>(null)
        val threads = ArrayList<Thread>()
        val url = item.finalUrl ?: item.url

        for (i in state.ranges.indices) {
            val len = state.ranges[i][1] - state.ranges[i][0] + 1
            if (state.done.get(i) >= len) continue
            val t = Thread {
                try {
                    runPart(item, state, i, url, tracker, abort)
                } catch (e: Throwable) {
                    error.compareAndSet(null, e)
                    abort.set(true)
                }
            }
            t.isDaemon = true
            t.start()
            threads.add(t)
        }
        for (t in threads) t.join()
        tracker.tick(true)

        val err = error.get()
        if (err != null) throw err

        for (i in state.ranges.indices) {
            val len = state.ranges[i][1] - state.ranges[i][0] + 1
            if (state.done.get(i) < len) throw IOException("Download incomplete")
        }
        item.engineState = null
        return state.file
    }

    private fun runPart(
        item: VideoItem,
        st: DirectState,
        i: Int,
        url: String,
        tracker: Tracker,
        abort: AtomicBoolean
    ) {
        val rangeStart = st.ranges[i][0]
        val rangeEnd = st.ranges[i][1]
        val len = rangeEnd - rangeStart + 1
        val buf = ByteArray(64 * 1024)
        var attempt = 0

        while (true) {
            if (abort.get()) return
            checkControl(item)
            val have = st.done.get(i)
            if (have >= len) return
            val start = rangeStart + have
            try {
                val o = Http.open(url, item.userAgent, item.referer, "bytes=$start-$rangeEnd", 15000, 20000)
                try {
                    val code = o.conn.responseCode
                    if (code == 200 && start != 0L) throw RangeUnsupported()
                    if (code == 200 && st.ranges.size > 1) throw RangeUnsupported()
                    if (code != 200 && code != 206) throw HttpFail(code)

                    RandomAccessFile(st.file, "rw").use { raf ->
                        raf.seek(start)
                        o.conn.inputStream.use { input ->
                            var left = rangeEnd - start + 1
                            while (left > 0) {
                                if (abort.get()) return
                                checkControl(item)
                                val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                                if (n < 0) break
                                raf.write(buf, 0, n)
                                st.done.addAndGet(i, n.toLong())
                                left -= n
                                tracker.add(n.toLong())
                                attempt = 0
                            }
                        }
                    }
                } finally {
                    o.conn.disconnect()
                }
                if (st.done.get(i) >= len) return
                throw IOException("Connection closed early")
            } catch (e: Interrupted) {
                throw e
            } catch (e: RangeUnsupported) {
                throw e
            } catch (e: Exception) {
                if (e is HttpFail && !e.retryable()) throw e
                attempt++
                if (attempt > DownloadUtil.MAX_RETRIES) throw e
                sleepControlled(item, 1000L * attempt)
            }
        }
    }

    private fun single(item: VideoItem, dir: File, tracker: Tracker): File {
        val file = File(dir, "${item.id}.part")
        file.delete()
        val o = Http.open(item.finalUrl ?: item.url, item.userAgent, item.referer, null, 15000, 30000)
        try {
            val code = o.conn.responseCode
            if (code !in 200..299) throw HttpFail(code)
            val total = o.conn.contentLengthLong
            tracker.total = total
            tracker.setDone(0)
            if (total > 0 && dir.usableSpace < total + total / 10) throw IOException("Not enough storage space")
            val buf = ByteArray(64 * 1024)
            var got = 0L
            o.conn.inputStream.use { input ->
                file.outputStream().use { out ->
                    while (true) {
                        checkControl(item)
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        got += n
                        tracker.add(n.toLong())
                    }
                }
            }
            if (got == 0L) throw IOException("Empty file")
            if (total > 0 && got < total) throw IOException("Download incomplete")
            tracker.tick(true)
            return file
        } catch (e: Exception) {
            if (e is Interrupted) file.delete() // a plain stream cannot resume
            throw e
        } finally {
            o.conn.disconnect()
        }
    }
}
