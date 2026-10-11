package com.runit.webrecorder

import android.webkit.WebView

/**
 * One browser tab: its own WebView, its own list of detected videos.
 * A private tab saves nothing to Recent and does not use the page cache.
 */
class Tab(
    val id: Int,
    val isPrivate: Boolean,
    val webView: WebView,
    val sniffer: VideoSniffer
) {
    var title: String = ""
    var url: String = ""
    var lastUsed: Long = System.currentTimeMillis()

    /** A tab that has not loaded anything yet (shows the start page). */
    val isBlank: Boolean
        get() = url.isEmpty()
}
