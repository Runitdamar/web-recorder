package com.runit.webrecorder

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.projection.MediaProjection
import androidx.annotation.RequiresApi
import java.io.File
import java.nio.ByteBuffer

/**
 * Records the phone's playback audio (Android 10+) into an AAC .m4a file.
 */
@RequiresApi(29)
class AudioCapture(private val projection: MediaProjection) {

    companion object {
        private const val SAMPLE_RATE = 44100
        private const val CHANNELS = 2
        private const val BITRATE = 128_000
        private const val READ_SIZE = 4096
    }

    @Volatile
    private var running = false
    private var thread: Thread? = null
    private var record: AudioRecord? = null
    private var codec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var trackIndex = -1
    private var muxerStarted = false
    private val info = MediaCodec.BufferInfo()
    private var totalFrames = 0L

    @Volatile
    var hasData = false
        private set

    @SuppressLint("MissingPermission")
    fun start(outFile: File): Boolean {
        try {
            val config = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()

            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()

            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val bufSize = maxOf(minBuf, 8192) * 2

            val rec = AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(config)
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufSize)
                .build()

            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                rec.release()
                return false
            }

            val mf = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, CHANNELS
            )
            mf.setInteger(
                MediaFormat.KEY_AAC_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AACObjectLC
            )
            mf.setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
            mf.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, bufSize)

            val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            enc.configure(mf, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            enc.start()

            muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            codec = enc
            record = rec

            rec.startRecording()
            running = true
            thread = Thread { loop() }.also { it.start() }
            return true
        } catch (e: Exception) {
            release()
            return false
        }
    }

    private fun loop() {
        val rec = record ?: return
        val pcm = ByteArray(READ_SIZE)
        try {
            while (running) {
                val n = rec.read(pcm, 0, pcm.size)
                if (n > 0) {
                    feed(pcm, n, false)
                }
                drain(false)
            }
            feed(pcm, 0, true)
            drain(true)
        } catch (e: Exception) {
            // ignore, release below
        } finally {
            release()
        }
    }

    private fun feed(data: ByteArray, size: Int, eos: Boolean) {
        val enc = codec ?: return
        var attempts = 0
        while (attempts < 50) {
            val idx = enc.dequeueInputBuffer(10_000)
            if (idx >= 0) {
                val buf = enc.getInputBuffer(idx) ?: return
                buf.clear()
                val pts = totalFrames * 1_000_000L / SAMPLE_RATE
                if (eos) {
                    enc.queueInputBuffer(idx, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                } else {
                    buf.put(data, 0, size)
                    totalFrames += (size / (2 * CHANNELS)).toLong()
                    enc.queueInputBuffer(idx, 0, size, pts, 0)
                }
                return
            } else {
                drain(false)
                attempts++
            }
        }
    }

    private fun drain(eos: Boolean) {
        val enc = codec ?: return
        val mux = muxer ?: return
        var tries = 0
        while (true) {
            val idx = enc.dequeueOutputBuffer(info, if (eos) 10_000 else 0)
            when {
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!eos) return
                    tries++
                    if (tries > 100) return
                }
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (!muxerStarted) {
                        trackIndex = mux.addTrack(enc.outputFormat)
                        mux.start()
                        muxerStarted = true
                    }
                }
                idx >= 0 -> {
                    val buf = enc.getOutputBuffer(idx)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        info.size = 0
                    }
                    if (info.size > 0 && muxerStarted && buf != null) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        mux.writeSampleData(trackIndex, buf, info)
                        hasData = true
                    }
                    enc.releaseOutputBuffer(idx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    fun stop() {
        running = false
        try {
            thread?.join(4000)
        } catch (_: InterruptedException) {
        }
        thread = null
    }

    private fun release() {
        try { record?.stop() } catch (_: Exception) {}
        try { record?.release() } catch (_: Exception) {}
        record = null
        try { codec?.stop() } catch (_: Exception) {}
        try { codec?.release() } catch (_: Exception) {}
        codec = null
        try { if (muxerStarted) muxer?.stop() } catch (_: Exception) {}
        try { muxer?.release() } catch (_: Exception) {}
        muxer = null
        muxerStarted = false
    }
}

/**
 * Joins the video-only .mp4 and the audio .m4a into one .mp4 (no re-encoding).
 */
object MediaMerger {

    fun merge(video: File, audio: File, out: File): Boolean {
        val vEx = MediaExtractor()
        val aEx = MediaExtractor()
        var mux: MediaMuxer? = null
        var started = false
        try {
            vEx.setDataSource(video.absolutePath)
            aEx.setDataSource(audio.absolutePath)

            val vTrack = findTrack(vEx, "video/")
            val aTrack = findTrack(aEx, "audio/")
            if (vTrack < 0 || aTrack < 0) return false

            vEx.selectTrack(vTrack)
            aEx.selectTrack(aTrack)

            mux = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val vOut = mux.addTrack(vEx.getTrackFormat(vTrack))
            val aOut = mux.addTrack(aEx.getTrackFormat(aTrack))
            mux.start()
            started = true

            val buf = ByteBuffer.allocate(2 * 1024 * 1024)
            val bi = MediaCodec.BufferInfo()
            var vDone = false
            var aDone = false

            while (!vDone || !aDone) {
                val vt = if (vDone) Long.MAX_VALUE else vEx.sampleTime
                val at = if (aDone) Long.MAX_VALUE else aEx.sampleTime
                if (!vDone && vt < 0) { vDone = true; continue }
                if (!aDone && at < 0) { aDone = true; continue }

                val useVideo = vt <= at
                val ex = if (useVideo) vEx else aEx
                val track = if (useVideo) vOut else aOut

                buf.clear()
                val size = ex.readSampleData(buf, 0)
                if (size < 0) {
                    if (useVideo) vDone = true else aDone = true
                    continue
                }
                bi.set(0, size, ex.sampleTime, ex.sampleFlags)
                mux.writeSampleData(track, buf, bi)
                ex.advance()
            }
            return true
        } catch (e: Exception) {
            return false
        } finally {
            try { if (started) mux?.stop() } catch (_: Exception) {}
            try { mux?.release() } catch (_: Exception) {}
            try { vEx.release() } catch (_: Exception) {}
            try { aEx.release() } catch (_: Exception) {}
        }
    }

    private fun findTrack(ex: MediaExtractor, prefix: String): Int {
        for (i in 0 until ex.trackCount) {
            val mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith(prefix)) return i
        }
        return -1
    }
}
