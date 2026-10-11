package com.runit.webrecorder

import android.net.Uri
import android.webkit.JavascriptInterface

/**
 * Watches one tab's page requests for video files and keeps the list of videos found.
 * Downloading and metadata live in DownloadCenter and MetadataFetcher.
 */
class VideoSniffer(
    private val tabId: Int,
    private val nextId: () -> Int
) {

    companion object {
        private val EXTS = listOf(".mp4", ".webm", ".mkv", ".mov", ".m4v", ".3gp")
        private const val MAX_ITEMS = 30

        // Injected into every page: tells the app when a video plays or ends
        const val HOOK_JS = """
(function() {
  if (window.__wrHooked) return;
  window.__wrHooked = true;
  function vid(e) {
    var t = e.target;
    return (t && t.tagName === 'VIDEO') ? t : null;
  }
  document.addEventListener('playing', function(e) {
    var v = vid(e);
    if (v) WRBridge.onPlaying(v.currentSrc || v.src || '');
  }, true);
  document.addEventListener('ended', function(e) {
    var v = vid(e);
    if (v) WRBridge.onEnded();
  }, true);
})();
"""
    }

    private val items = LinkedHashMap<String, VideoItem>()

    @Volatile
    var pageUrl: String? = null

    @Volatile
    var userAgent: String = ""

    /** Called (from any thread) whenever the list or a video's info changes. */
    @Volatile
    var onChange: (() -> Unit)? = null

    fun count(): Int = synchronized(items) { items.size }

    fun list(): List<VideoItem> = synchronized(items) { items.values.toList() }

    fun latest(): VideoItem? = synchronized(items) { items.values.lastOrNull() }

    /** New page: forget old videos, but keep ones that are still downloading. */
    fun clear() {
        synchronized(items) {
            val keep = items.filter { it.value.isBusy() }
            items.clear()
            items.putAll(keep)
        }
    }

    // Called for every network request the page makes (background thread)
    fun checkRequest(url: String) {
        val lower = url.lowercase()
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return
        val path = lower.substringBefore('?').substringBefore('#')
        if (EXTS.none { path.endsWith(it) }) return
        add(url)
    }

    // Called when a <video> starts playing (catches URLs without .mp4 in them)
    fun addFromPage(src: String): VideoItem? {
        val lower = src.lowercase()
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return null
        val path = lower.substringBefore('?').substringBefore('#')
        if (path.endsWith(".m3u8") || path.endsWith(".mpd")) return null
        return add(src)
    }

    /** Starts reading resolution, duration and thumbnail for this video. */
    fun loadDetails(item: VideoItem) {
        MetadataFetcher.loadDetails(item) { onChange?.invoke() }
    }

    private fun add(url: String): VideoItem? {
        var created: VideoItem? = null
        synchronized(items) {
            val existing = items[url]
            if (existing != null) return existing

            if (items.size >= MAX_ITEMS) {
                val victim = items.entries.firstOrNull { !it.value.isBusy() } ?: return null
                items.remove(victim.key)
            }
            val index = items.size + 1
            val item = VideoItem(
                nextId(),
                url,
                pageUrl,
                userAgent,
                tabId,
                hostOf(url),
                titleFrom(url, index)
            )
            items[url] = item
            created = item
        }

        val item = created ?: return null
        MetadataFetcher.fetchBasic(item) { onChange?.invoke() }
        onChange?.invoke()
        return item
    }

    private fun hostOf(url: String): String {
        val host = try {
            Uri.parse(url).host
        } catch (_: Exception) {
            null
        }
        return (host ?: "web").removePrefix("www.")
    }

    private fun titleFrom(url: String, index: Int): String {
        val raw = url.substringBefore('?').substringBefore('#').substringAfterLast('/')
        val decoded = try {
            java.net.URLDecoder.decode(raw, "UTF-8")
        } catch (_: Exception) {
            raw
        }
        val noExt = decoded.substringBeforeLast('.', decoded)
        val clean = noExt.replace(Regex("[_\\-+.]+"), " ").trim()
        val looksLikeHash = clean.length > 24 && !clean.contains(' ')
        return if (clean.length >= 3 && clean.any { it.isLetter() } && !looksLikeHash) {
            clean.take(48)
        } else {
            "Video $index"
        }
    }
}

/**
 * Receives play / ended events from the injected JavaScript.
 * These methods run on a background thread, so the callbacks must post to the UI thread.
 */
class WebBridge(
    private val playing: (String) -> Unit,
    private val ended: () -> Unit
) {
    @JavascriptInterface
    fun onPlaying(src: String) {
        playing(src)
    }

    @JavascriptInterface
    fun onEnded() {
        ended()
    }
}
