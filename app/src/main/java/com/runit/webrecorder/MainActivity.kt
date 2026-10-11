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
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
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
import java.util.concurrent.atomic.AtomicInteger

class MainActivity : AppCompatActivity() {

    private enum class Screen { HOME, BROWSE, TABS, DOWNLOADS, PLACEHOLDER }

    private data class Recent(val title: String, val url: String, val time: Long)

    // Screens
    private lateinit var homeView: View
    private lateinit var browseView: View
    private lateinit var tabsView: View
    private lateinit var downloadsView: View
    private lateinit var placeholderView: View
    private lateinit var placeholderTitle: TextView
    private lateinit var placeholderText: TextView

    // Home
    private lateinit var greeting: TextView
    private lateinit var homeSubtitle: TextView
    private lateinit var homeSearch: EditText
    private lateinit var recentSection: View
    private lateinit var recentList: LinearLayout
    private lateinit var seeAll: TextView
    private lateinit var tileRec: View
    private lateinit var tileStop: View
    private lateinit var imgAuto: ImageView
    private lateinit var lblRec: TextView
    private lateinit var lblAuto: TextView
    private lateinit var lblDownload: TextView

    // Browse
    private lateinit var webContainer: FrameLayout
    private lateinit var urlBar: EditText
    private lateinit var loadingBar: ProgressBar
    private lateinit var privateBadge: TextView
    private lateinit var tabCount: TextView
    private lateinit var btnRec: TextView
    private lateinit var btnAuto: TextView
    private lateinit var btnDownload: TextView

    // Detect card (popup when a video plays)
    private lateinit var detectCard: View
    private lateinit var dcThumb: ImageView
    private lateinit var dcTitle: TextView
    private lateinit var dcMeta: TextView
    private lateinit var dcAction: TextView

    // Tabs screen
    private lateinit var tabsList: LinearLayout
    private lateinit var chips: List<TextView>

    // Download screen
    private lateinit var dlList: LinearLayout
    private lateinit var dlCount: TextView
    private lateinit var dlEmpty: TextView
    private lateinit var btnDlSelected: TextView

    // Bottom nav
    private lateinit var navIcons: List<ImageView>
    private lateinit var navTexts: List<TextView>

    // Tabs
    private val tabs = ArrayList<Tab>()
    private var currentTab: Tab? = null
    private var nextTabId = 1
    private val itemIds = AtomicInteger(1)
    private var tabFilter = 0 // 0 All, 1 Normal, 2 Private, 3 Recent

    private var screen = Screen.HOME
    private var returnScreen = Screen.HOME
    private var homePrivate = false
    private var showAllRecent = false

    // Download screen rows
    private val rowViews = HashMap<Int, View>()
    private var shownIds: List<Int> = emptyList()

    // Detect card state
    private var detectItem: VideoItem? = null
    private var detectDoneHandled = false
    private val shownKeys = HashSet<String>()
    private val hideCardRunnable = Runnable { detectCard.visibility = View.GONE }

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
            toast("Recording cancelled")
        }
    }

    // Mic + notification permissions, then continue either way
    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        askCapture()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()
        setupClicks()

        DownloadCenter.onEvent = { msg ->
            uiHandler.post { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when (screen) {
                    Screen.BROWSE -> {
                        val wv = currentTab?.webView
                        if (wv != null && wv.canGoBack()) wv.goBack() else goHome()
                    }
                    Screen.TABS -> goHome()
                    Screen.DOWNLOADS -> leaveDownloads()
                    Screen.PLACEHOLDER -> goHome()
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
        tabsView = findViewById(R.id.tabsView)
        downloadsView = findViewById(R.id.downloadsView)
        placeholderView = findViewById(R.id.placeholderView)
        placeholderTitle = findViewById(R.id.placeholderTitle)
        placeholderText = findViewById(R.id.placeholderText)

        greeting = findViewById(R.id.greeting)
        homeSubtitle = findViewById(R.id.homeSubtitle)
        homeSearch = findViewById(R.id.homeSearch)
        recentSection = findViewById(R.id.recentSection)
        recentList = findViewById(R.id.recentList)
        seeAll = findViewById(R.id.seeAll)
        tileRec = findViewById(R.id.tileRec)
        tileStop = findViewById(R.id.tileStop)
        imgAuto = findViewById(R.id.imgAuto)
        lblRec = findViewById(R.id.lblRec)
        lblAuto = findViewById(R.id.lblAuto)
        lblDownload = findViewById(R.id.lblDownload)

        webContainer = findViewById(R.id.webContainer)
        urlBar = findViewById(R.id.urlBar)
        loadingBar = findViewById(R.id.loadingBar)
        privateBadge = findViewById(R.id.privateBadge)
        tabCount = findViewById(R.id.tabCount)
        btnRec = findViewById(R.id.btnRec)
        btnAuto = findViewById(R.id.btnAuto)
        btnDownload = findViewById(R.id.btnDownload)

        detectCard = findViewById(R.id.detectCard)
        dcThumb = findViewById(R.id.dcThumb)
        dcTitle = findViewById(R.id.dcTitle)
        dcMeta = findViewById(R.id.dcMeta)
        dcAction = findViewById(R.id.dcAction)

        tabsList = findViewById(R.id.tabsList)
        chips = listOf(
            findViewById<TextView>(R.id.chipAll),
            findViewById<TextView>(R.id.chipNormal),
            findViewById<TextView>(R.id.chipPrivate),
            findViewById<TextView>(R.id.chipRecent)
        )

        dlList = findViewById(R.id.dlList)
        dlCount = findViewById(R.id.dlCount)
        dlEmpty = findViewById(R.id.dlEmpty)
        btnDlSelected = findViewById(R.id.btnDlSelected)

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
        findViewById<View>(R.id.tileDownload).setOnClickListener { openDownloads() }

        // Browse pills
        btnRec.setOnClickListener {
            if (RecorderService.isRecording) stopManual() else startManual()
        }
        btnAuto.setOnClickListener { toggleAuto() }
        btnDownload.setOnClickListener { openDownloads() }
        tabCount.setOnClickListener { showScreen(Screen.TABS, 1) }

        // Recent
        seeAll.setOnClickListener {
            showAllRecent = !showAllRecent
            renderRecent()
        }

        // Detect card
        dcAction.setOnClickListener { onDetectAction() }
        findViewById<View>(R.id.dcClose).setOnClickListener { hideDetectCard() }
        detectCard.setOnClickListener { openDownloads() }

        // Tabs screen
        findViewById<View>(R.id.tabsBack).setOnClickListener { goHome() }
        findViewById<View>(R.id.tabsEdit).setOnClickListener { showCloseTabsDialog() }
        for (i in chips.indices) {
            chips[i].setOnClickListener {
                tabFilter = i
                renderTabs()
            }
        }
        findViewById<View>(R.id.btnNewTab).setOnClickListener { newTab(false) }
        findViewById<View>(R.id.btnNewPrivate).setOnClickListener { newTab(true) }

        // Download screen
        findViewById<View>(R.id.dlBack).setOnClickListener { leaveDownloads() }
        findViewById<View>(R.id.dlSelectAll).setOnClickListener { selectAllVideos() }
        btnDlSelected.setOnClickListener { downloadSelected() }

        // Bottom nav
        findViewById<View>(R.id.navHome).setOnClickListener { goHome() }
        findViewById<View>(R.id.navTabs).setOnClickListener { showScreen(Screen.TABS, 1) }
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
        tabsView.visibility = if (s == Screen.TABS) View.VISIBLE else View.GONE
        downloadsView.visibility = if (s == Screen.DOWNLOADS) View.VISIBLE else View.GONE
        placeholderView.visibility = if (s == Screen.PLACEHOLDER) View.VISIBLE else View.GONE

        for (i in navIcons.indices) {
            val c = color(if (i == nav) R.color.vio_accent else R.color.vio_subtext)
            navIcons[i].setColorFilter(c)
            navTexts[i].setTextColor(c)
        }

        if (s != Screen.BROWSE) hideDetectCard()
        if (s != Screen.HOME) hideKeyboard()

        when (s) {
            Screen.HOME -> {
                applyHomeMode()
                renderRecent()
            }
            Screen.BROWSE -> applyPrivateStyle()
            Screen.TABS -> renderTabs()
            Screen.DOWNLOADS -> renderDownloads()
            Screen.PLACEHOLDER -> {}
        }
        updateUi()
    }

    private fun goHome() {
        homePrivate = currentTab?.isPrivate == true
        showScreen(Screen.HOME, 0)
    }

    private fun showPlaceholder(title: String, text: String, nav: Int) {
        placeholderTitle.text = title
        placeholderText.text = text
        showScreen(Screen.PLACEHOLDER, nav)
    }

    private fun applyHomeMode() {
        if (homePrivate) {
            greeting.text = "Private browsing."
            greeting.setTextColor(color(R.color.vio_accent))
            homeSubtitle.text = "Pages you visit here won't be saved to Recent"
            recentSection.visibility = View.GONE
        } else {
            greeting.text = greetingText()
            greeting.setTextColor(color(R.color.vio_text))
            homeSubtitle.text = "Search the web or type a URL"
            recentSection.visibility = View.VISIBLE
        }
    }

    private fun applyPrivateStyle() {
        privateBadge.visibility = if (currentTab?.isPrivate == true) View.VISIBLE else View.GONE
    }

    private fun greetingText(): String {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return when {
            hour < 12 -> "Good morning."
            hour < 17 -> "Good afternoon."
            else -> "Good evening."
        }
    }

    // ---------- Tabs ----------

    @SuppressLint("SetJavaScriptEnabled")
    private fun createTab(isPrivate: Boolean): Tab {
        val id = nextTabId++
        val wv = WebView(this)

        val st = wv.settings
        st.javaScriptEnabled = true
        st.domStorageEnabled = true
        st.mediaPlaybackRequiresUserGesture = false
        st.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        st.setSupportZoom(true)
        st.builtInZoomControls = true
        st.displayZoomControls = false
        if (isPrivate) {
            st.cacheMode = WebSettings.LOAD_NO_CACHE
        }

        val sniffer = VideoSniffer(id) { itemIds.getAndIncrement() }
        sniffer.userAgent = st.userAgentString
        sniffer.onChange = { uiHandler.post { updateUi() } }

        val tab = Tab(id, isPrivate, wv, sniffer)

        // Play / ended events from the page (arrive on a background thread)
        wv.addJavascriptInterface(
            WebBridge(
                { src -> uiHandler.post { onVideoPlaying(tab, src) } },
                { uiHandler.post { onVideoEnded(tab) } }
            ),
            "WRBridge"
        )

        wv.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                if (url != null) {
                    tab.url = url
                    sniffer.clear()
                    sniffer.pageUrl = url
                    clearShown(tab)
                    if (tab === currentTab && !urlBar.hasFocus()) urlBar.setText(url)
                }
                if (tab === currentTab) {
                    loadingBar.visibility = View.VISIBLE
                    autoDeclined = false
                }
                view?.evaluateJavascript(VideoSniffer.HOOK_JS, null)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                if (tab === currentTab) loadingBar.visibility = View.GONE
                view?.evaluateJavascript(VideoSniffer.HOOK_JS, null)
                tab.title = view?.title ?: ""
                if (url != null && !tab.isPrivate) addRecent(view?.title, url)
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
                if (tab === currentTab) loadingBar.progress = newProgress
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                tab.title = title ?: ""
            }
        }

        wv.visibility = View.GONE
        // Index 0 keeps the detect card above every WebView
        webContainer.addView(
            wv,
            0,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        tabs.add(tab)
        return tab
    }

    private fun switchTo(tab: Tab) {
        currentTab = tab
        tab.lastUsed = System.currentTimeMillis()
        for (t in tabs) {
            t.webView.visibility = if (t === tab) View.VISIBLE else View.GONE
        }
        urlBar.setText(tab.url)
        loadingBar.visibility = View.GONE
        hideDetectCard()
        applyPrivateStyle()
        updateUi()
    }

    private fun closeTab(tab: Tab) {
        if (!tabs.remove(tab)) return
        try {
            tab.webView.stopLoading()
        } catch (_: Exception) {
        }
        if (tab.isPrivate) {
            try {
                tab.webView.clearCache(true)
            } catch (_: Exception) {
            }
        }
        webContainer.removeView(tab.webView)
        tab.webView.destroy()
        if (currentTab === tab) {
            currentTab = tabs.maxByOrNull { it.lastUsed }
            hideDetectCard()
        }
    }

    private fun closeMany(match: (Tab) -> Boolean) {
        val victims = tabs.filter { match(it) }
        for (t in victims) closeTab(t)
    }

    private fun newTab(isPrivate: Boolean) {
        val t = createTab(isPrivate)
        homePrivate = isPrivate
        switchTo(t)
        showScreen(Screen.HOME, 0)
    }

    private fun openTab(tab: Tab) {
        switchTo(tab)
        if (tab.isBlank) {
            homePrivate = tab.isPrivate
            showScreen(Screen.HOME, 0)
        } else {
            showScreen(Screen.BROWSE, 1)
        }
    }

    private fun showCloseTabsDialog() {
        val options = arrayOf("Close all tabs", "Close private tabs", "Close normal tabs")
        AlertDialog.Builder(this)
            .setTitle("Close tabs")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> closeMany { true }
                    1 -> closeMany { it.isPrivate }
                    else -> closeMany { !it.isPrivate }
                }
                renderTabs()
                updateUi()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun renderTabs() {
        for (i in chips.indices) {
            val on = i == tabFilter
            chips[i].setBackgroundResource(if (on) R.drawable.bg_chip_on else R.drawable.bg_chip)
            chips[i].setTextColor(color(if (on) R.color.vio_surface else R.color.vio_text))
        }

        tabsList.removeAllViews()
        val sorted = tabs.sortedByDescending { it.lastUsed }
        val list = when (tabFilter) {
            1 -> sorted.filter { !it.isPrivate }
            2 -> sorted.filter { it.isPrivate }
            3 -> sorted.take(5)
            else -> sorted
        }

        if (list.isEmpty()) {
            val tv = TextView(this)
            tv.text = "No tabs here yet"
            tv.setTextColor(color(R.color.vio_subtext))
            tv.textSize = 14f
            tv.setPadding(0, dp(24), 0, dp(24))
            tabsList.addView(tv)
        }

        for (tab in list) {
            val row = layoutInflater.inflate(R.layout.item_tab, tabsList, false)
            val title = when {
                tab.title.isNotBlank() -> tab.title
                tab.isBlank -> "New tab"
                else -> hostOf(tab.url)
            }
            val sub = when {
                tab.isBlank && tab.isPrivate -> "Private tab"
                tab.isBlank -> "Start page"
                tab.isPrivate -> "Private • " + hostOf(tab.url)
                else -> hostOf(tab.url)
            }
            row.findViewById<TextView>(R.id.tabTitle).text = title
            row.findViewById<TextView>(R.id.tabHost).text = sub

            val letter = row.findViewById<TextView>(R.id.tabLetter)
            val lock = row.findViewById<ImageView>(R.id.tabLock)
            val avatar = row.findViewById<View>(R.id.tabAvatar)
            if (tab.isPrivate) {
                avatar.setBackgroundResource(R.drawable.bg_avatar_dark)
                letter.visibility = View.GONE
                lock.visibility = View.VISIBLE
            } else {
                letter.text = (if (tab.isBlank) "+" else hostOf(tab.url).take(1).uppercase())
            }

            row.setBackgroundResource(
                if (tab === currentTab) R.drawable.bg_card_current else R.drawable.bg_card
            )
            row.setOnClickListener { openTab(tab) }
            row.findViewById<View>(R.id.tabClose).setOnClickListener {
                closeTab(tab)
                renderTabs()
                updateUi()
            }
            tabsList.addView(row)
        }
    }

    // ---------- Navigation / URL ----------

    private fun submit(edit: EditText) {
        val input = edit.text.toString().trim()
        if (input.isEmpty()) return
        val url = resolveUrl(input)
        if (edit === homeSearch) {
            edit.setText("")
            openFromHome(url)
        } else {
            val tab = currentTab
            if (tab != null) openInTab(tab, url)
        }
    }

    private fun resolveUrl(input: String): String = when {
        input.startsWith("http://") || input.startsWith("https://") -> input
        input.contains(" ") || !input.contains(".") ->
            "https://www.google.com/search?q=" + java.net.URLEncoder.encode(input, "UTF-8")
        else -> "https://$input"
    }

    // Uses the current tab if it is still empty, otherwise opens a new one
    private fun openFromHome(url: String) {
        val cur = currentTab
        val tab = if (cur != null && cur.isBlank && cur.isPrivate == homePrivate) {
            cur
        } else {
            createTab(homePrivate)
        }
        openInTab(tab, url)
    }

    private fun openInTab(tab: Tab, url: String) {
        tab.url = url
        switchTo(tab)
        showScreen(Screen.BROWSE, 1)
        tab.webView.loadUrl(url)
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
            row.setOnClickListener { openFromHome(r.url) }
            row.findViewById<View>(R.id.recentMore).setOnClickListener {
                val list = loadRecent()
                list.removeAll { entry -> entry.url == r.url }
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

    // ---------- Video playing: popup + auto-record ----------

    private fun onVideoPlaying(tab: Tab, src: String) {
        val direct = tab.sniffer.addFromPage(src)
        val isCurrent = tab === currentTab

        // Auto-record
        if (isCurrent &&
            autoMode &&
            !RecorderService.isRecording &&
            !RecorderService.isSaving &&
            !autoDeclined &&
            !askingCapture
        ) {
            autoPending = true
            startFlow()
        }

        // Download popup
        if (isCurrent && screen == Screen.BROWSE) {
            if (direct != null) {
                showDetectCard(tab, direct)
            } else {
                val seen = tab.sniffer.latest()
                if (seen != null) {
                    showDetectCard(tab, seen)
                } else {
                    // The network request for the video may arrive a moment after "playing"
                    uiHandler.postDelayed({
                        if (tab === currentTab && screen == Screen.BROWSE) {
                            showDetectCard(tab, tab.sniffer.latest())
                        }
                    }, 1500)
                }
            }
        }
    }

    private fun onVideoEnded(tab: Tab) {
        if (tab === currentTab && autoMode && autoStarted && RecorderService.isRecording) {
            autoStarted = false
            val svc = Intent(this, RecorderService::class.java).apply {
                action = RecorderService.ACTION_STOP
            }
            startService(svc)
        }
    }

    private fun clearShown(tab: Tab) {
        val prefix = "${tab.id}|"
        shownKeys.removeAll { it.startsWith(prefix) }
    }

    private fun showDetectCard(tab: Tab, item: VideoItem?) {
        val key = "${tab.id}|${item?.url ?: "none"}"
        if (!shownKeys.add(key)) return
        detectItem = item
        detectDoneHandled = false
        if (item != null) tab.sniffer.loadDetails(item)
        detectCard.visibility = View.VISIBLE
        bindDetectCard()
        uiHandler.removeCallbacks(hideCardRunnable)
        uiHandler.postDelayed(hideCardRunnable, 12000)
    }

    private fun hideDetectCard() {
        uiHandler.removeCallbacks(hideCardRunnable)
        detectCard.visibility = View.GONE
    }

    private fun bindDetectCard() {
        val item = detectItem
        if (item == null) {
            dcTitle.text = "Video playing"
            dcMeta.text = "Direct download isn't available"
            dcAction.text = "Record"
            dcAction.alpha = 1f
            applyThumb(dcThumb, null)
            return
        }

        dcTitle.text = item.title
        dcMeta.text = item.metaLine()
        applyThumb(dcThumb, item.thumb)

        when (item.state) {
            VideoItem.State.IDLE -> {
                dcAction.text = "Download"
                dcAction.alpha = 1f
            }
            VideoItem.State.FAILED -> {
                dcAction.text = "Retry"
                dcAction.alpha = 1f
            }
            VideoItem.State.QUEUED -> {
                dcAction.text = "Queued"
                dcAction.alpha = 0.6f
                uiHandler.removeCallbacks(hideCardRunnable)
            }
            VideoItem.State.DOWNLOADING -> {
                val p = item.progress
                dcAction.text = if (p in 0..100) "$p%" else "..."
                dcAction.alpha = 0.6f
                uiHandler.removeCallbacks(hideCardRunnable)
            }
            VideoItem.State.DONE -> {
                dcAction.text = "Saved"
                dcAction.alpha = 0.6f
                if (!detectDoneHandled) {
                    detectDoneHandled = true
                    uiHandler.removeCallbacks(hideCardRunnable)
                    uiHandler.postDelayed(hideCardRunnable, 2500)
                }
            }
        }
    }

    private fun onDetectAction() {
        val item = detectItem
        if (item == null) {
            hideDetectCard()
            startManual()
            return
        }
        if (item.isSelectable()) {
            DownloadCenter.enqueue(this, item)
            toast("Download started")
            bindDetectCard()
        }
    }

    // ---------- Download screen ----------

    private fun openDownloads() {
        returnScreen = if (screen == Screen.BROWSE) Screen.BROWSE else Screen.HOME
        showScreen(Screen.DOWNLOADS, -1)
    }

    private fun leaveDownloads() {
        if (returnScreen == Screen.BROWSE && currentTab != null) {
            showScreen(Screen.BROWSE, 1)
        } else {
            goHome()
        }
    }

    private fun currentItems(): List<VideoItem> {
        val tab = currentTab ?: return emptyList()
        return tab.sniffer.list().reversed() // newest first
    }

    private fun renderDownloads() {
        val items = currentItems()
        dlCount.text = "${items.size}"
        dlEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE

        val ids = items.map { it.id }
        if (ids != shownIds) {
            dlList.removeAllViews()
            rowViews.clear()
            for (item in items) {
                val row = layoutInflater.inflate(R.layout.item_video, dlList, false)
                row.setOnClickListener { toggleSelect(item) }
                dlList.addView(row)
                rowViews[item.id] = row
                currentTab?.sniffer?.loadDetails(item)
            }
            shownIds = ids
        }

        for (item in items) {
            val row = rowViews[item.id]
            if (row != null) bindRow(row, item)
        }

        val n = items.count { it.selected && it.isSelectable() }
        btnDlSelected.text = "Download Selected ($n)"
        btnDlSelected.alpha = if (n > 0) 1f else 0.5f
    }

    private fun bindRow(row: View, item: VideoItem) {
        applyThumb(row.findViewById<ImageView>(R.id.vThumb), item.thumb)
        row.findViewById<TextView>(R.id.vTitle).text = item.title
        row.findViewById<TextView>(R.id.vMeta).text = item.metaLine()
        row.findViewById<TextView>(R.id.vType).text = item.typeLine()

        val progress = row.findViewById<ProgressBar>(R.id.vProgress)
        val status = row.findViewById<TextView>(R.id.vStatus)
        val check = row.findViewById<CheckBox>(R.id.vCheck)
        val done = row.findViewById<ImageView>(R.id.vDone)

        when (item.state) {
            VideoItem.State.IDLE -> {
                progress.visibility = View.GONE
                status.visibility = View.GONE
                done.visibility = View.GONE
                check.visibility = View.VISIBLE
                check.isChecked = item.selected
            }
            VideoItem.State.QUEUED -> {
                progress.visibility = View.GONE
                status.visibility = View.VISIBLE
                status.text = "Queued"
                status.setTextColor(color(R.color.vio_accent))
                check.visibility = View.GONE
                done.visibility = View.GONE
            }
            VideoItem.State.DOWNLOADING -> {
                val p = item.progress
                progress.visibility = View.VISIBLE
                progress.isIndeterminate = p < 0
                if (p in 0..100) progress.progress = p
                status.visibility = View.VISIBLE
                status.text = if (p in 0..100) "Downloading $p%" else "Downloading..."
                status.setTextColor(color(R.color.vio_accent))
                check.visibility = View.GONE
                done.visibility = View.GONE
            }
            VideoItem.State.DONE -> {
                progress.visibility = View.GONE
                status.visibility = View.VISIBLE
                status.text = "Saved to Movies/WebRecorder"
                status.setTextColor(color(R.color.vio_green))
                check.visibility = View.GONE
                done.visibility = View.VISIBLE
            }
            VideoItem.State.FAILED -> {
                progress.visibility = View.GONE
                status.visibility = View.VISIBLE
                status.text = "Failed: ${item.message}. Select to retry"
                status.setTextColor(color(R.color.vio_red))
                done.visibility = View.GONE
                check.visibility = View.VISIBLE
                check.isChecked = item.selected
            }
        }
    }

    private fun applyThumb(view: ImageView, bmp: Bitmap?) {
        if (bmp != null) {
            if (view.tag !== bmp) {
                view.clearColorFilter()
                view.setPadding(0, 0, 0, 0)
                view.scaleType = ImageView.ScaleType.CENTER_CROP
                view.setImageBitmap(bmp)
                view.tag = bmp
            }
        } else if (view.tag != "ph") {
            val p = dp(16)
            view.scaleType = ImageView.ScaleType.CENTER_INSIDE
            view.setPadding(p, p, p, p)
            view.setImageResource(R.drawable.ic_video)
            view.setColorFilter(color(R.color.vio_subtext))
            view.tag = "ph"
        }
    }

    private fun toggleSelect(item: VideoItem) {
        if (!item.isSelectable()) return
        item.selected = !item.selected
        renderDownloads()
    }

    private fun selectAllVideos() {
        val items = currentItems().filter { it.isSelectable() }
        val allOn = items.isNotEmpty() && items.all { it.selected }
        for (item in items) item.selected = !allOn
        renderDownloads()
    }

    private fun downloadSelected() {
        val chosen = currentItems().filter { it.selected && it.isSelectable() }
        if (chosen.isEmpty()) {
            toast("Select at least one video")
            return
        }
        for (item in chosen) {
            item.selected = false
            DownloadCenter.enqueue(this, item)
        }
        toast(if (chosen.size == 1) "Download started" else "Downloading ${chosen.size} videos")
        renderDownloads()
    }

    // ---------- Recording ----------

    private fun toggleAuto() {
        autoMode = !autoMode
        autoDeclined = false
        updateUi()
        toast(if (autoMode) "Auto-record ON: starts when a video plays" else "Auto-record OFF")
    }

    private fun startManual() {
        if (RecorderService.isSaving) {
            toast("Saving, please wait")
            return
        }
        if (RecorderService.isRecording) {
            toast("Already recording")
            return
        }
        autoPending = false
        startFlow()
    }

    private fun stopManual() {
        if (!RecorderService.isRecording) {
            toast("Not recording")
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
        val items = currentTab?.sniffer?.list() ?: emptyList()
        val active = items.filter { it.isBusy() }
        val dlText = if (active.isNotEmpty()) {
            val p = active[0].progress
            if (p in 0..100) "$p%" else "..."
        } else {
            "${items.size}"
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
        btnDownload.text = if (active.isNotEmpty()) "Downloading $dlText" else "Download (${items.size})"
        tabCount.text = "${tabs.size}"

        if (screen == Screen.DOWNLOADS) renderDownloads()
        if (detectCard.visibility == View.VISIBLE) bindDetectCard()
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
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
        DownloadCenter.onEvent = null
        for (t in tabs) {
            try {
                t.webView.destroy()
            } catch (_: Exception) {
            }
        }
        super.onDestroy()
    }
}
