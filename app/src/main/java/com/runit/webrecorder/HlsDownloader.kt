package com.runit.webrecorder

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Downloads an HLS stream (VOD): parallel segment fetching, AES-128 decryption, optional separate
 * audio stream, then joins everything into an MP4.
 */
object HlsDownloader {

    private const val WINDOW = 4

    class Track(val media: HlsMedia, val file: File) {
        @Volatile var next = 0
    }

    class State(val tracks: List<Track>, val keys: ConcurrentHashMap<String, ByteArray>)

    /** Returns the finished temp file and its extension ("mp4" or "ts"). */
    fun download(item: VideoItem, dir: File, tracker: Tracker): Pair<File, String> {
        var st = item.engineState as? State
        if (st == null) {
            st = plan(item, dir)
            item.engineState = st
        }
        val state = st
        tracker.total = -1L

        val est = if (item.bitrate > 0 && item.durationMs > 0) item.bitrate / 8 * (item.durationMs / 1000) else 80_000_000L
        if (dir.usableSpace < est * 2) throw IOException("Not enough storage space")

        val totalSegs = state.tracks.sumOf { it.media.segments.size }
        for (t in state.tracks) {
            downloadTrack(item, t, state, tracker, totalSegs)
        }

        // Join into one MP4
        item.message = "Merging..."
        item.speedBps = 0
        item.etaSec = -1
        DownloadCenter.onChange?.invoke()

        val v = state.tracks[0].file
        val a = state.tracks.getOrNull(1)?.file
        val out = File(dir, "${item.id}.mp4")
        out.delete()
        val ok = Remuxer.remux(v, a, out, item.durationMs, { item.progress = it }, { item.control == 2 })
        if (item.control == 2) {
            out.delete()
            throw Interrupted(2)
        }
        if (ok) {
            for (t in state.tracks) t.file.delete()
            item.engineState = null
            return Pair(out, "mp4")
        }
        out.delete()
        if (a == null) {
            item.engineState = null
            val fmp4 = state.tracks[0].media.map != null
            return Pair(v, if (fmp4) "mp4" else "ts")
        }
        throw IOException("Couldn't merge audio and video")
    }

    // ------------------------------------------------------------------ planning

    private fun plan(item: VideoItem, dir: File): State {
        val ua = item.userAgent
        val ref = item.referer
        val text = Http.getText(item.url, ua, ref)
        var mediaText = text
        var base = item.url
        var audioUrl: String? = null
        if (HlsParser.isMaster(text)) {
            val master = HlsParser.parseMaster(text, item.url)
            val variant = item.chosenVariant ?: master.best() ?: throw IOException("No streams in playlist")
            base = variant.url
            mediaText = Http.getText(variant.url, ua, ref)
            audioUrl = master.audioFor(variant)?.url
        }
        val media = HlsParser.parseMedia(mediaText, base)
        validate(media)

        val tracks = ArrayList<Track>()
        val vf = File(dir, "${item.id}_v.bin")
        vf.delete()
        tracks.add(Track(media, vf))
        if (audioUrl != null) {
            val am = HlsParser.parseMedia(Http.getText(audioUrl, ua, ref), audioUrl)
            validate(am)
            val af = File(dir, "${item.id}_a.bin")
            af.delete()
            tracks.add(Track(am, af))
        }
        return State(tracks, ConcurrentHashMap())
    }

    private fun validate(m: HlsMedia) {
        if (m.segments.isEmpty()) throw IOException("Playlist has no video")
        if (!m.ended) throw IOException("Live streams aren't supported yet")
        if (m.keys.any { it.method != "AES-128" }) throw IOException("Protected stream (DRM) can't be downloaded")
    }

    // ------------------------------------------------------------------ downloading

    private fun downloadTrack(item: VideoItem, track: Track, st: State, tracker: Tracker, totalSegs: Int) {
        val m = track.media
        val n = m.segments.size
        val ex = Executors.newFixedThreadPool(WINDOW)
        try {
            FileOutputStream(track.file, true).use { out ->
                if (m.map != null && track.file.length() == 0L) {
                    val mp = m.map
                    out.write(fetch(item, mp.url, mp.offset, mp.length, tracker))
                }
                val pending = ArrayDeque<Future<ByteArray>>()
                var submit = track.next
                while (track.next < n) {
                    checkControl(item)
                    while (pending.size < WINDOW && submit < n) {
                        val idx = submit
                        pending.addLast(ex.submit(Callable { segment(item, m, idx, st, tracker) }))
                        submit++
                    }
                    val data = try {
                        pending.removeFirst().get()
                    } catch (e: ExecutionException) {
                        throw e.cause ?: e
                    }
                    out.write(data)
                    track.next++
                    var done = 0
                    for (t in st.tracks) done += t.next
                    tracker.setSegments(done, totalSegs)
                }
            }
        } finally {
            ex.shutdownNow()
        }
    }

    private fun segment(item: VideoItem, m: HlsMedia, idx: Int, st: State, tracker: Tracker): ByteArray {
        val seg = m.segments[idx]
        val data = fetch(item, seg.url, seg.offset, seg.length, tracker)
        if (seg.keyIndex < 0) return data
        val key = m.keys[seg.keyIndex]
        val uri = key.uri ?: throw IOException("Missing key")
        val keyBytes = st.keys[uri] ?: run {
            val k = fetch(item, uri, -1, -1, tracker)
            if (k.size != 16) throw IOException("Bad key")
            st.keys[uri] = k
            k
        }
        val iv = key.iv ?: DownloadUtil.ivFromSequence(m.mediaSequence + idx)
        return try {
            val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(iv))
            c.doFinal(data)
        } catch (e: Exception) {
            throw IOException("Couldn't decrypt video")
        }
    }

    private fun fetch(item: VideoItem, url: String, off: Long, len: Long, tracker: Tracker): ByteArray {
        var attempt = 0
        while (true) {
            checkControl(item)
            try {
                val range = if (off >= 0 && len > 0) "bytes=$off-${off + len - 1}" else null
                val o = Http.open(url, item.userAgent, item.referer, range, 15000, 20000)
                try {
                    val code = o.conn.responseCode
                    if (code != 200 && code != 206) throw HttpFail(code)
                    if (range != null && code == 200) throw IOException("Range not supported")
                    val out = ByteArrayOutputStream(if (len > 0) len.toInt() else 256 * 1024)
                    val buf = ByteArray(32 * 1024)
                    o.conn.inputStream.use { input ->
                        while (true) {
                            checkControl(item)
                            val r = input.read(buf)
                            if (r < 0) break
                            out.write(buf, 0, r)
                            tracker.add(r.toLong())
                        }
                    }
                    return out.toByteArray()
                } finally {
                    o.conn.disconnect()
                }
            } catch (e: Interrupted) {
                throw e
            } catch (e: Exception) {
                if (e is HttpFail && !e.retryable()) throw e
                attempt++
                if (attempt > DownloadUtil.MAX_RETRIES) throw e
                sleepControlled(item, 800L * attempt)
            }
        }
    }
}
