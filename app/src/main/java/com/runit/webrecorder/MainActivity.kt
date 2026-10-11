package com.runit.webrecorder

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

class MainActivity : AppCompatActivity() {

    private enum class Screen { HOME, BROWSE, PLACEHOLDER }

    private data class Recent(val title: String, val url: String, val time: Long)

    // Screens
    private lateinit var homeView: View
    private lateinit var browseView: View
    private lateinit var placeholderView: View
    private lateinit var placeholderTitle: TextView
    private lateinit var placeholderText: TextView

    // Home
    private lateinit var greeting: TextView
    private lateinit var homeSearch: EditText
    private lateinit var recentList: LinearLayout
    private lateinit var seeAll: TextView
    private lateinit var tileRec: View
    private lateinit var tileStop: View
    private lateinit var imgAuto: ImageView
    private lateinit var lblRec: TextView
    private lateinit var lblAuto: TextView
    private lateinit var lblDownload: TextView

    // Browse
    private lateinit var webView: WebView
    private lateinit var urlBar: EditText
    private lateinit var loadingBar: ProgressBar
    private lateinit var btnRec: TextView
    private lateinit var btnAuto: TextView
    private lateinit var btnDownload: TextView

    // Bottom nav
    private lateinit var navIcons: List<ImageView>
    private lateinit var navTexts: List<TextView>

    private val sniffer = VideoSniffer()

    private var screen = Screen.HOME
    private var pageOpened = false
    private var showAllRecent = false

    // Auto-record state
    private var autoMode = false
    private var autoPending = false   // capture asked by auto mode
    private var autoStarted = false   // current recording was started by auto mode
    private var autoDeclined = false  // user cancelled the popup on this page
    private var askingCapture = false

    private val uiHandler = Handler(Looper.getMainLooper())
    private val syncRunnable = object : Runnable {
        override fun run() {
            updateUi()
            uiHandler.postDelayed(this, 500)
        }
    }

    // Result of the "Start recording?" system popup
    private val captureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        uiHandler.postDelayed({ askingCapture = false }, 2000)
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) {
            autoStarted = autoPending
            autoPending = false
            val svc = Intent(this, RecorderService::class.java).apply {
                action = RecorderService.ACTION_START
                putExtra(RecorderService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(RecorderService.EXTRA_DATA, data)
            }
            ContextCompat.startForegroundService(this, svc)
        } else {
            if (autoPending) autoDeclined = true
            autoPending = false
            autoStarted = false
            Toast.makeText(this, "Recording cancelled", Toast.LENGTH_SHORT).show()
        }
    }

    // Mic + notification permissions, then continue either way
    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        askCapture()
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()
        setupWebView()
        setupClicks()

        greeting.text = greetingText()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when (screen) {
                    Screen.BROWSE -> {
                        if (webView.canGoBack()) webView.goBack() else showScreen(Screen.HOME, 0)
                    }
                    Screen.PLACEHOLDER -> showScreen(Screen.HOME, 0)
                    Screen.HOME -> finish()
                }
            }
        })

        showScreen(Screen.HOME, 0)
    }

    // ---------- Setup ----------

    private fun bindViews() {
        homeView = findViewById(R.id.homeView)
        browseView = findViewById(R.id.browseView)
        placeholderView = findViewById(R.id.placeholderView)
        placeholderTitle = findViewById(R.id.placeholderTitle)
        placeholderText = findViewById(R.id.placeholderText)

        greeting = findViewById(R.id.greeting)
        homeSearch = findViewById(R.id.homeSearch)
        recentList = findViewById(R.id.recentList)
        seeAll = findViewById(R.id.seeAll)
        tileRec = findViewById(R.id.tileRec)
        tileStop = findViewById(R.id.tileStop)
        imgAuto = findViewById(R.id.imgAuto)
        lblRec = findViewById(R.id.lblRec)
        lblAuto = findViewById(R.id.lblAuto)
        lblDownload = findViewById(R.id.lblDownload)

        webView = findViewById(R.id.webView)
        urlBar = findViewById(R.id.urlBar)
        loadingBar = findViewById(R.id.loadingBar)
        btnRec = findViewById(R.id.btnRec)
        btnAuto = findViewById(R.id.btnAuto)
        btnDownload = findViewById(R.id.btnDownload)

        navIcons = listOf(
            findViewById<ImageView>(R.id.iconHome),
            findViewById<ImageView>(R.id.iconTabs),
            findViewById<ImageView>(R.id.iconBookmarks),
            findViewById<ImageView>(R.id.iconMore)
        )
        navTexts = listOf(
            findViewById<TextView>(R.id.textHome),
            findViewById<TextView>(R.id.textTabs),
            findViewById<TextView>(R.id.textBookmarks),
            findViewById<TextView>(R.id.textMore)
        )
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val wv = webView
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.settings.mediaPlaybackRequiresUserGesture = false
        wv.settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        wv.settings.setSupportZoom(true)
        wv.settings.builtInZoomControls = true
        wv.settings.displayZoomControls = false

        // Play / ended events from the page (arrive on a background thread)
        wv.addJavascriptInterface(
            WebBridge(
                { src -> uiHandler.post { onVideoPlaying(src) } },
                { uiHandler.post { onVideoEnded() } }
            ),
            "WRBridge"
        )

        wv.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                loadingBar.visibility = View.VISIBLE
                if (url != null) {
                    if (!urlBar.hasFocus()) urlBar.setText(url)
                    sniffer.clear()
                    sniffer.pageUrl = url
                }
                autoDeclined = false
                view?.evaluateJavascript(VideoSniffer.HOOK_JS, null)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                loadingBar.visibility = View.GONE
                view?.evaluateJavascript(VideoSniffer.HOOK_JS, null)
                if (url != null) addRecent(view?.title, url)
            }

            // Sees every request the page makes, looks for video files
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                if (request != null) {
                    sniffer.checkRequest(request.url.toString())
                }
                return super.shouldInterceptRequest(view, request)
            }
        }

        wv.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                loadingBar.progress = newProgress
            }
        }
    }

    private fun setupClicks() {
        bindGo(homeSearch)
        bindGo(urlBar)

        // Home tiles
        findViewById<View>(R.id.tileGo).setOnClickListener {
            if (homeSearch.text.toString().trim().isEmpty()) {
                homeSearch.requestFocus()
                val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(homeSearch, InputMethodManager.SHOW_IMPLICIT)
            } else {
                submit(homeSearch)
            }
        }
        tileRec.setOnClickListener { startManual() }
        tileStop.setOnClickListener { stopManual() }
        findViewById<View>(R.id.tileAuto).setOnClickListener { toggleAuto() }
        findViewById<View>(R.id.tileDownload).setOnClickListener { onDownloadClicked() }

        // Browse pills
        btnRec.setOnClickListener {
            if (RecorderService.isRecording) stopManual() else startManual()
        }
        btnAuto.setOnClickListener { toggleAuto() }
        btnDownload.setOnClickListener { onDownloadClicked() }

        // Recent
        seeAll.setOnClickListener {
            showAllRecent = !showAllRecent
            renderRecent()
        }

        // Bottom nav
        findViewById<View>(R.id.navHome).setOnClickListener { showScreen(Screen.HOME, 0) }
        findViewById<View>(R.id.navTabs).setOnClickListener {
            if (pageOpened) {
                showScreen(Screen.BROWSE, 1)
            } else {
                showPlaceholder("Tabs", "Multiple tabs are coming in the next update", 1)
            }
        }
        findViewById<View>(R.id.navBookmarks).setOnClickListener {
            showPlaceholder("Bookmarks", "Coming soon", 2)
        }
        findViewById<View>(R.id.navMore).setOnClickListener {
            showPlaceholder("More", "Coming soon", 3)
        }
    }

    private fun bindGo(edit: EditText) {
        edit.setOnEditorActionListener { _, actionId, event ->
            val enterPressed = event != null &&
                event.keyCode == KeyEvent.KEYCODE_ENTER &&
                event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_GO || enterPressed) {
                submit(edit)
                true
            } else {
                false
            }
        }
    }

    // ---------- Screens ----------

    private fun showScreen(s: Screen, nav: Int) {
        screen = s
        homeView.visibility = if (s == Screen.HOME) View.VISIBLE else View.GONE
        browseView.visibility = if (s == Screen.BROWSE) View.VISIBLE else View.GONE
        placeholderView.visibility = if (s == Screen.PLACEHOLDER) View.VISIBLE else View.GONE

        for (i in navIcons.indices) {
            val c = color(if (i == nav) R.color.vio_accent else R.color.vio_subtext)
            navIcons[i].setColorFilter(c)
            navTexts[i].setTextColor(c)
        }

        if (s == Screen.HOME) {
            greeting.text = greetingText()
            renderRecent()
        } else {
            hideKeyboard()
        }
        updateUi()
    }

    private fun showPlaceholder(title: String, text: String, nav: Int) {
        placeholderTitle.text = title
        placeholderText.text = text
        showScreen(Screen.PLACEHOLDER, nav)
    }

    private fun greetingText(): String {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return when {
            hour < 12 -> "Good morning."
            hour < 17 -> "Good afternoon."
            else -> "Good evening."
        }
    }

    // ---------- Navigation / URL ----------

    private fun submit(edit: EditText) {
        val input = edit.text.toString().trim()
        if (input.isEmpty()) return
        if (edit === homeSearch) edit.setText("")
        openInBrowser(resolveUrl(input))
    }

    private fun resolveUrl(input: String): String = when {
        input.startsWith("http://") || input.startsWith("https://") -> input
        input.contains(" ") || !input.contains(".") ->
            "https://www.google.com/search?q=" + java.net.URLEncoder.encode(input, "UTF-8")
        else -> "https://$input"
    }

    private fun openInBrowser(url: String) {
        pageOpened = true
        showScreen(Screen.BROWSE, 1)
        webView.loadUrl(url)
        hideKeyboard()
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        val v = currentFocus ?: window.decorView
        imm.hideSoftInputFromWindow(v.windowToken, 0)
        currentFocus?.clearFocus()
    }

    // ---------- Recent ----------

    private fun loadRecent(): MutableList<Recent> {
        val list = ArrayList<Recent>()
        try {
            val raw = getSharedPreferences("vio", MODE_PRIVATE).getString("recent", "[]") ?: "[]"
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(Recent(o.optString("t"), o.optString("u"), o.optLong("ts")))
            }
        } catch (_: Exception) {
        }
        return list
    }

    private fun saveRecent(list: List<Recent>) {
        val arr = JSONArray()
        for (r in list) {
            val o = JSONObject()
            o.put("t", r.title)
            o.put("u", r.url)
            o.put("ts", r.time)
            arr.put(o)
        }
        getSharedPreferences("vio", MODE_PRIVATE).edit().putString("recent", arr.toString()).apply()
    }

    private fun addRecent(title: String?, url: String) {
        if (!url.startsWith("http")) return
        val list = loadRecent()
        list.removeAll { it.url == url }
        val name = if (title.isNullOrBlank()) hostOf(url) else title
        list.add(0, Recent(name, url, System.currentTimeMillis()))
        while (list.size > 30) list.removeAt(list.size - 1)
        saveRecent(list)
    }

    private fun renderRecent() {
        recentList.removeAllViews()
        val all = loadRecent()
        val shown = if (showAllRecent) all else all.take(5)

        if (shown.isEmpty()) {
            val tv = TextView(this)
            tv.text = "Pages you visit will show up here"
            tv.setTextColor(color(R.color.vio_subtext))
            tv.textSize = 13f
            tv.setPadding(0, dp(12), 0, dp(12))
            recentList.addView(tv)
        }

        for (r in shown) {
            val row = layoutInflater.inflate(R.layout.item_recent, recentList, false)
            val host = hostOf(r.url)
            row.findViewById<TextView>(R.id.recentLetter).text = host.take(1).uppercase()
            row.findViewById<TextView>(R.id.recentTitle).text = r.title
            row.findViewById<TextView>(R.id.recentHost).text = host
            row.findViewById<TextView>(R.id.recentTime).text = timeAgo(r.time)
            row.setOnClickListener { openInBrowser(r.url) }
            row.findViewById<View>(R.id.recentMore).setOnClickListener {
                val list = loadRecent()
                list.removeAll { it.url == r.url }
                saveRecent(list)
                renderRecent()
            }
            recentList.addView(row)
        }

        seeAll.visibility = if (all.size > 5) View.VISIBLE else View.GONE
        seeAll.text = if (showAllRecent) "Show less" else "See all"
    }

    private fun hostOf(url: String): String {
        val host = try {
            Uri.parse(url).host
        } catch (_: Exception) {
            null
        }
        return (host ?: url).removePrefix("www.")
    }

    private fun timeAgo(time: Long): String {
        val diff = System.currentTimeMillis() - time
        val min = diff / 60000
        return when {
            min < 1 -> "now"
            min < 60 -> "${min}m"
            min < 1440 -> "${min / 60}h"
            else -> "${min / 1440}d"
        }
    }

    // ---------- Auto-record ----------

    private fun toggleAuto() {
        autoMode = !autoMode
        autoDeclined = false
        updateUi()
        Toast.makeText(
            this,
            if (autoMode) "Auto-record ON: starts when a video plays" else "Auto-record OFF",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun onVideoPlaying(src: String) {
        sniffer.addFromPage(src)
        if (autoMode &&
            !RecorderService.isRecording &&
            !RecorderService.isSaving &&
            !autoDeclined &&
            !askingCapture
        ) {
            autoPending = true
            startFlow()
        }
    }

    private fun onVideoEnded() {
        if (autoMode && autoStarted && RecorderService.isRecording) {
            autoStarted = false
            val svc = Intent(this, RecorderService::class.java).apply {
                action = RecorderService.ACTION_STOP
            }
            startService(svc)
        }
    }

    // ---------- Download ----------

    private fun onDownloadClicked() {
        if (sniffer.downloading) {
            Toast.makeText(this, "Download in progress", Toast.LENGTH_SHORT).show()
            return
        }
        val found = sniffer.list().reversed() // newest first
        when {
            found.isEmpty() -> Toast.makeText(
                this,
                "No video found yet. Play the video first",
                Toast.LENGTH_SHORT
            ).show()

            found.size == 1 -> startDownload(found[0])

            else -> {
                val labels = found.mapIndexed { i, f -> "${i + 1}. ${shortName(f.url)}" }
                    .toTypedArray()
                AlertDialog.Builder(this)
                    .setTitle("Pick a video")
                    .setItems(labels) { _, which -> startDownload(found[which]) }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
    }

    private fun startDownload(item: VideoSniffer.Found) {
        Toast.makeText(this, "Download started", Toast.LENGTH_SHORT).show()
        sniffer.download(this, item, webView.settings.userAgentString) { msg ->
            uiHandler.post {
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun shortName(url: String): String {
        val host = url.substringAfter("://").substringBefore('/')
        val file = url.substringBefore('?').substringAfterLast('/')
        val text = "$host/$file"
        return if (text.length > 50) text.take(47) + "..." else text
    }

    // ---------- Recording ----------

    private fun startManual() {
        if (RecorderService.isSaving) {
            Toast.makeText(this, "Saving, please wait", Toast.LENGTH_SHORT).show()
            return
        }
        if (RecorderService.isRecording) {
            Toast.makeText(this, "Already recording", Toast.LENGTH_SHORT).show()
            return
        }
        autoPending = false
        startFlow()
    }

    private fun stopManual() {
        if (!RecorderService.isRecording) {
            Toast.makeText(this, "Not recording", Toast.LENGTH_SHORT).show()
            return
        }
        autoStarted = false
        val svc = Intent(this, RecorderService::class.java).apply {
            action = RecorderService.ACTION_STOP
        }
        startService(svc)
    }

    // Checks permissions, then shows the system capture popup
    private fun startFlow() {
        askingCapture = true

        val missing = ArrayList<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            missing.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            missing.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (missing.isEmpty()) {
            askCapture()
        } else {
            permLauncher.launch(missing.toTypedArray())
        }
    }

    private fun askCapture() {
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        captureLauncher.launch(mpm.createScreenCaptureIntent())
    }

    // ---------- UI sync ----------

    private fun updateUi() {
        val rec = RecorderService.isRecording
        val saving = RecorderService.isSaving
        val count = sniffer.count()
        val dlText = if (sniffer.downloading) {
            val p = sniffer.progress
            if (p in 0..100) "$p%" else "..."
        } else {
            "$count"
        }

        // Home tiles
        lblRec.text = if (saving) "Saving" else "Rec"
        tileRec.setBackgroundResource(if (rec) R.drawable.bg_tile_rec else R.drawable.bg_tile)
        tileStop.alpha = if (rec) 1f else 0.4f
        imgAuto.setImageResource(if (autoMode) R.drawable.ic_toggle_on else R.drawable.ic_toggle_off)
        imgAuto.setColorFilter(color(if (autoMode) R.color.vio_accent else R.color.vio_subtext))
        lblAuto.text = if (autoMode) "Auto\nON" else "Auto\nOFF"
        lblDownload.text = "Download\n$dlText"

        // Browse pills
        btnRec.text = when {
            saving -> "Saving"
            rec -> "Stop"
            else -> "Rec"
        }
        btnRec.setBackgroundResource(if (rec) R.drawable.bg_pill_active else R.drawable.bg_pill)
        btnAuto.text = if (autoMode) "Auto: ON" else "Auto: OFF"
        btnAuto.setBackgroundResource(if (autoMode) R.drawable.bg_pill_active else R.drawable.bg_pill)
        btnDownload.text = if (sniffer.downloading) "Downloading $dlText" else "Download ($count)"
    }

    private fun color(id: Int): Int = ContextCompat.getColor(this, id)

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    override fun onResume() {
        super.onResume()
        uiHandler.post(syncRunnable)
    }

    override fun onPause() {
        uiHandler.removeCallbacks(syncRunnable)
        super.onPause()
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}
