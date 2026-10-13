package com.runit.webrecorder

import android.net.Uri
import android.webkit.JavascriptInterface
import org.json.JSONObject

/** What the page's own JavaScript told us about a <video> element. */
class PageInfo(
    val src: String,
    val poster: String,
    val title: String,
    val width: Int,
    val height: Int,
    val durationMs: Long,
    val pageUrl: String,
    val sources: List<PageSource>
) {
    companion object {
        fun parse(json: String): PageInfo? {
            return try {
                val o = JSONObject(json)
                val list = ArrayList<PageSource>()
                val arr = o.optJSONArray("srcs")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val s = arr.optJSONObject(i) ?: continue
                        list.add(PageSource(s.optString("u"), s.optString("t"), s.optInt("h")))
                    }
                }
                PageInfo(
                    o.optString("src"),
                    o.optString("poster"),
                    o.optString("title"),
                    o.optInt("w"),
                    o.optInt("h"),
                    o.optLong("dur"),
                    o.optString("page"),
                    list
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}

class PageSource(val url: String, val type: String, val height: Int)

/**
 * Watches one tab's page requests for video files / HLS playlists and keeps the list found.
 * Downloading and metadata live in DownloadCenter and MetadataFetcher.
 */
class VideoSniffer(
    private val tabId: Int,
    private val nextId: () -> Int
) {

    companion object {
        private val EXTS = listOf(".mp4", ".webm", ".mkv", ".mov", ".m4v", ".3gp", ".m3u8")
        private const val MAX_ITEMS = 40

        // Injected into every page: reports <video> info (title, poster, size, sources) and play / end
        const val HOOK_JS = """
(function() {
  if (window.__wrHooked) return;
  window.__wrHooked = true;
  function meta(n) {
    var e = document.querySelector('meta[property="' + n + '"],meta[name="' + n + '"]');
    return e ? (e.content || '') : '';
  }
  function abs(u) {
    try { return u ? new URL(u, location.href).href : ''; } catch (x) { return u || ''; }
  }
  function info(v) {
    var srcs = [];
    var s = v.querySelectorAll('source');
    for (var i = 0; i < s.length && i < 12; i++) {
      var h = parseInt(s[i].getAttribute('res') || s[i].getAttribute('data-res') || s[i].getAttribute('size') || '0', 10) || 0;
      srcs.push({u: abs(s[i].src), t: s[i].type || '', h: h});
    }
    return JSON.stringify({
      src: v.currentSrc || v.src || '',
      poster: abs(v.poster) || abs(meta('og:image')),
      title: meta('og:title') || document.title || '',
      w: v.videoWidth || 0,
      h: v.videoHeight || 0,
      dur: isFinite(v.duration) ? Math.round(v.duration * 1000) : 0,
      page: location.href,
      srcs: srcs
    });
  }
  function vid(e) {
    var t = e.target;
    return (t && t.tagName === 'VIDEO') ? t : null;
  }
  document.addEventListener('playing', function(e) {
    var v = vid(e);
    if (v) WRBridge.onVideo(info(v), true);
  }, true);
  document.addEventListener('loadedmetadata', function(e) {
    var v = vid(e);
    if (v) WRBridge.onVideo(info(v), false);
  }, true);
  document.addEventListener('ended', function(e) {
    var v = vid(e);
    if (v) WRBridge.onEnded();
  }, true);
})();
"""
    }

    private val items = LinkedHashMap<String, VideoItem>()
    private val suppressed = HashSet<String>()

    @Volatile
    var pageUrl: String? = null

    @Volatile
    var userAgent: String = ""

    /** Called (from any thread) whenever the list or a video's info changes. */
    @Volatile
    var onChange: (() -> Unit)? = null

    private fun shown(v: VideoItem): Boolean = v.isActive() || (!v.hidden && v.probeState >= 2)

    fun count(): Int = synchronized(items) { items.values.count { shown(it) } }

    fun list(): List<VideoItem> = synchronized(items) { items.values.filter { shown(it) } }

    fun latest(): VideoItem? = synchronized(items) { items.values.lastOrNull { shown(it) } }

    fun find(url: String): VideoItem? = synchronized(items) { items[url] }

    /** New page: forget old videos, but keep ones that are still downloading. */
    fun clear() {
        synchronized(items) {
            val keep = items.filter { it.value.isActive() }
            items.clear()
            items.putAll(keep)
            suppressed.clear()
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

    /** Page told us about a <video>: add it if needed and fill in title, poster, size. */
    fun applyPageInfo(info: PageInfo): VideoItem? {
        var item: VideoItem? = null
        val src = info.src
        if (src.startsWith("http://") || src.startsWith("https://")) {
            if (!src.lowercase().substringBefore('?').endsWith(".mpd")) item = add(src)
        } else if (info.sources.isNotEmpty()) {
            val best = info.sources
                .filter { it.url.startsWith("http") }
                .maxByOrNull { it.height }
            if (best != null) item = add(best.url)
        }
        if (item == null) item = latest() // blob: players — the request sniffer usually found the real file
        if (item == null) return null

        val t = DownloadUtil.cleanFileBase(info.title)
        if (t.length >= 3 && !item.titleFromPage) {
            item.title = t
            item.titleFromPage = true
        }
        if (info.poster.startsWith("http") && item.posterUrl == null) {
            item.posterUrl = info.poster
            MetadataFetcher.loadPoster(item) { onChange?.invoke() }
        }
        if (item.width == 0 && info.width > 0) {
            item.width = info.width
            item.height = info.height
        }
        if (item.durationMs == 0L && info.durationMs > 0) item.durationMs = info.durationMs
        onChange?.invoke()
        return item
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
            if (suppressed.contains(url)) return null

            if (items.size >= MAX_ITEMS) {
                val victim = items.entries.firstOrNull { !it.value.isActive() } ?: return null
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
        MetadataFetcher.probe(item) {
            afterProbe(item)
            onChange?.invoke()
        }
        return item
    }

    /** A master playlist hides the variant playlists it points to. */
    private fun afterProbe(item: VideoItem) {
        val rel = item.related
        if (rel.isEmpty()) return
        synchronized(items) {
            for (u in rel) {
                suppressed.add(u)
                val other = items[u]
                if (other != null && other !== item && !other.isActive()) other.hidden = true
            }
        }
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
        val generic = clean.lowercase() in setOf("index", "master", "playlist", "video", "main", "manifest")
        return if (clean.length >= 3 && clean.any { it.isLetter() } && !looksLikeHash && !generic) {
            clean.take(48)
        } else {
            "Video $index"
        }
    }
}

/**
 * Receives video info / ended events from the injected JavaScript.
 * These methods run on a background thread, so the callbacks must post to the UI thread.
 */
class WebBridge(
    private val video: (PageInfo, Boolean) -> Unit,
    private val ended: () -> Unit
) {
    @JavascriptInterface
    fun onVideo(json: String, playing: Boolean) {
        val info = PageInfo.parse(json) ?: return
        video(info, playing)
    }

    @JavascriptInterface
    fun onEnded() {
        ended()
    }
}
