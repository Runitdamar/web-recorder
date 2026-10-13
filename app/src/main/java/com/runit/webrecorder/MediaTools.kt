package com.runit.webrecorder

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/** Joins downloaded streams (TS or fragmented MP4, optionally a separate audio file) into one MP4. */
object Remuxer {

    private class Entry(val ex: MediaExtractor, val srcTrack: Int, val dstTrack: Int, val isVideo: Boolean)

    /**
     * [video] holds the video (and its own audio unless [audio] is given); [audio] is an optional
     * separate audio stream. Returns false if the formats can't be put into an MP4.
     */
    fun remux(
        video: File,
        audio: File?,
        out: File,
        durationMs: Long,
        onProgress: (Int) -> Unit,
        isCancelled: () -> Boolean
    ): Boolean {
        val extractors = ArrayList<MediaExtractor>()
        var muxer: MediaMuxer? = null
        var started = false
        try {
            val vEx = MediaExtractor()
            extractors.add(vEx)
            vEx.setDataSource(video.absolutePath)
            var aEx: MediaExtractor? = null
            if (audio != null) {
                aEx = MediaExtractor()
                extractors.add(aEx)
                aEx.setDataSource(audio.absolutePath)
            }

            muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val entries = ArrayList<Entry>()

            var haveAudio = false
            if (aEx != null) {
                for (i in 0 until aEx.trackCount) {
                    val f = aEx.getTrackFormat(i)
                    val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                    if (mime.startsWith("audio/") && !haveAudio) {
                        aEx.selectTrack(i)
                        entries.add(Entry(aEx, i, muxer.addTrack(f), false))
                        haveAudio = true
                    }
                }
            }
            var haveVideo = false
            for (i in 0 until vEx.trackCount) {
                val f = vEx.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") && !haveVideo) {
                    vEx.selectTrack(i)
                    entries.add(Entry(vEx, i, muxer.addTrack(f), true))
                    haveVideo = true
                } else if (mime.startsWith("audio/") && !haveAudio) {
                    vEx.selectTrack(i)
                    entries.add(Entry(vEx, i, muxer.addTrack(f), false))
                    haveAudio = true
                }
            }
            if (entries.isEmpty()) return false

            muxer.start()
            started = true

            // Shared time base so audio and video keep their offset to each other
            var base = Long.MAX_VALUE
            for (e in entries) {
                val t = e.ex.sampleTime
                if (t in 0 until base) base = t
            }
            if (base == Long.MAX_VALUE) base = 0

            val buf = ByteBuffer.allocate(4 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()

            while (true) {
                if (isCancelled()) return false
                // pick the entry whose next sample is earliest
                var pick = -1
                var pickTime = Long.MAX_VALUE
                for (idx in entries.indices) {
                    val e = entries[idx]
                    val t = e.ex.sampleTime
                    if (t >= 0 && t < pickTime && e.ex.sampleTrackIndex == e.srcTrack) {
                        pick = idx
                        pickTime = t
                    }
                }
                if (pick < 0) break
                val e = entries[pick]
                buf.clear()
                val size = e.ex.readSampleData(buf, 0)
                if (size < 0) {
                    // nothing left on this extractor: make sure we don't loop forever
                    e.ex.unselectTrack(e.srcTrack)
                    continue
                }
                var pts = pickTime - base
                if (pts < 0) pts = 0

                info.set(0, size, pts, if (e.ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                muxer.writeSampleData(e.dstTrack, buf, info)
                e.ex.advance()

                if (e.isVideo && durationMs > 0) {
                    onProgress(((pts / 1000) * 100 / durationMs).toInt().coerceIn(0, 100))
                }
            }
            muxer.stop()
            started = false
            return out.length() > 0
        } catch (e: Exception) {
            return false
        } finally {
            try {
                if (started) muxer?.stop()
            } catch (_: Exception) {
            }
            try {
                muxer?.release()
            } catch (_: Exception) {
            }
            for (x in extractors) {
                try {
                    x.release()
                } catch (_: Exception) {
                }
            }
        }
    }
}
