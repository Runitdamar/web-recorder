package com.runit.webrecorder

import android.content.Context
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Runs downloads (two at a time) and keeps every VideoItem's progress up to date.
 * Supports pause / resume / cancel. Finished files go to Movies/WebRecorder.
 */
object DownloadCenter {

    private val pool = Executors.newFixedThreadPool(2)
    private val jobs = ConcurrentHashMap<Int, VideoItem>()

    /** Short messages for the UI to show as toasts. */
    @Volatile
    var onEvent: ((String) -> Unit)? = null

    @Volatile
    var onChange: (() -> Unit)? = null

    /** Every video that was ever started in this run, newest first. */
    fun all(): List<VideoItem> = jobs.values.sortedByDescending { it.id }

    fun activeCount(): Int = jobs.values.count { it.isBusy() }

    fun enqueue(ctx: Context, item: VideoItem, variant: HlsVariant? = null) {
        if (item.isBusy()) return
        if (item.problem != null) {
            onEvent?.invoke(item.problem ?: "Can't download this")
            return
        }
        if (variant != null) item.chosenVariant = variant
        if (item.state != VideoItem.State.PAUSED) {
            item.progress = 0
            item.doneBytes = 0
        }
        item.control = 0
        item.state = VideoItem.State.QUEUED
        item.message = ""
        item.speedBps = 0
        item.etaSec = -1
        jobs[item.id] = item
        val app = ctx.applicationContext
        DownloadService.sync(app)
        onChange?.invoke()
        pool.execute { process(app, item) }
    }

    fun pause(item: VideoItem) {
        if (item.isBusy()) {
            item.control = 1
        }
    }

    fun resume(ctx: Context, item: VideoItem) {
        if (item.state == VideoItem.State.PAUSED) enqueue(ctx, item)
    }

    fun cancel(ctx: Context, item: VideoItem) {
        when {
            item.isBusy() -> item.control = 2
            item.state == VideoItem.State.PAUSED || item.state == VideoItem.State.FAILED -> {
                discard(item)
                item.state = VideoItem.State.IDLE
                item.progress = 0
                item.message = ""
                item.speedBps = 0
                jobs.remove(item.id)
                DownloadService.sync(ctx.applicationContext)
                onChange?.invoke()
            }
            else -> {}
        }
    }

    /** Remove a finished / failed row from the global list. */
    fun forget(item: VideoItem) {
        if (!item.isActive()) {
            jobs.remove(item.id)
            onChange?.invoke()
        }
    }

    fun tempDir(ctx: Context): File {
        val d = File(ctx.getExternalFilesDir(null) ?: ctx.cacheDir, "tmp")
        d.mkdirs()
        return d
    }

    /** Called when the app process starts: leftovers from a killed run can't be resumed. */
    fun cleanTemp(ctx: Context) {
        if (jobs.isNotEmpty()) return
        try {
            tempDir(ctx).listFiles()?.forEach { it.delete() }
        } catch (_: Exception) {
        }
    }

    private fun discard(item: VideoItem) {
        item.engineState = null
        // temp files are named <id>.part / <id>_v.bin / <id>_a.bin / <id>.mp4
        // (the dir is only known with a context, so leftovers are cleaned on next start)
    }

    private fun process(ctx: Context, item: VideoItem) {
        val dir = tempDir(ctx)
        when (item.control) {
            1 -> {
                item.state = VideoItem.State.PAUSED
                item.speedBps = 0
                finishRound(ctx)
                return
            }
            2 -> {
                cleanFiles(dir, item)
                resetToIdle(item)
                finishRound(ctx)
                return
            }
        }

        item.state = VideoItem.State.DOWNLOADING
        item.message = if (item.kind == VideoItem.Kind.HLS) "Preparing..." else ""
        onChange?.invoke()

        try {
            MetadataFetcher.probeNow(item)
            if (item.problem != null) throw java.io.IOException(item.problem)

            val tracker = Tracker(item, item.sizeBytes)
            val result: Pair<File, String> = if (item.kind == VideoItem.Kind.HLS) {
                item.message = ""
                HlsDownloader.download(item, dir, tracker)
            } else {
                Pair(DirectDownloader.download(item, dir, tracker), Naming.directExt(item))
            }

            item.message = "Saving..."
            item.speedBps = 0
            item.etaSec = -1
            onChange?.invoke()
            val name = Naming.baseName(item) + "." + result.second
            val saved = Publisher.publish(ctx, result.first, name, Naming.mimeFor(result.second))
            cleanFiles(dir, item)
            item.engineState = null
            item.message = saved
            item.progress = 100
            item.state = VideoItem.State.DONE
            onEvent?.invoke("Saved to Movies/WebRecorder: $saved")
            DownloadService.notifyDone(ctx, saved)
        } catch (e: Interrupted) {
            if (e.kind == 1) {
                item.state = VideoItem.State.PAUSED
                item.speedBps = 0
                item.etaSec = -1
            } else {
                cleanFiles(dir, item)
                resetToIdle(item)
            }
        } catch (e: Exception) {
            item.message = e.message ?: "unknown error"
            item.state = VideoItem.State.FAILED
            item.speedBps = 0
            item.etaSec = -1
            onEvent?.invoke("Download failed: ${item.message}")
        }
        item.control = 0
        finishRound(ctx)
    }

    private fun resetToIdle(item: VideoItem) {
        item.engineState = null
        item.state = VideoItem.State.IDLE
        item.progress = 0
        item.message = ""
        item.speedBps = 0
        item.etaSec = -1
        item.control = 0
        jobs.remove(item.id)
    }

    private fun cleanFiles(dir: File, item: VideoItem) {
        for (n in listOf("${item.id}.part", "${item.id}_v.bin", "${item.id}_a.bin", "${item.id}.mp4")) {
            try {
                File(dir, n).delete()
            } catch (_: Exception) {
            }
        }
    }

    private fun finishRound(ctx: Context) {
        DownloadService.sync(ctx)
        onChange?.invoke()
    }
}
