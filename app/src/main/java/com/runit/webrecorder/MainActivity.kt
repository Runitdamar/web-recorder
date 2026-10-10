package com.runit.webrecorder

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var urlBar: EditText
    private lateinit var loadingBar: ProgressBar

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        // Top bar: URL box + Go button
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }

        urlBar = EditText(this).apply {
            hint = "Search or enter URL"
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { _, actionId, event ->
                val enterPressed = event != null &&
                    event.keyCode == KeyEvent.KEYCODE_ENTER &&
                    event.action == KeyEvent.ACTION_DOWN
                if (actionId == EditorInfo.IME_ACTION_GO || enterPressed) {
                    go()
                    true
                } else {
                    false
                }
            }
        }
        bar.addView(
            urlBar,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )

        val goBtn = Button(this).apply {
            text = "Go"
            setOnClickListener { go() }
        }
        bar.addView(
            goBtn,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        root.addView(bar)

        // Loading bar
        loadingBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        loadingBar.max = 100
        loadingBar.visibility = View.GONE
        root.addView(
            loadingBar,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(3))
        )

        // WebView
        val wv = WebView(this)
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.settings.mediaPlaybackRequiresUserGesture = false
        wv.settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        wv.settings.setSupportZoom(true)
        wv.settings.builtInZoomControls = true
        wv.settings.displayZoomControls = false

        wv.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                loadingBar.visibility = View.VISIBLE
                if (url != null) urlBar.setText(url)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                loadingBar.visibility = View.GONE
            }
        }

        wv.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                loadingBar.progress = newProgress
            }
        }

        webView = wv
        root.addView(
            webView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        setContentView(root)

        // Back button goes back in page history first
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    finish()
                }
            }
        })

        webView.loadUrl("https://www.google.com")
    }

    private fun go() {
        val input = urlBar.text.toString().trim()
        if (input.isEmpty()) return

        val url = when {
            input.startsWith("http://") || input.startsWith("https://") -> input
            input.contains(" ") || !input.contains(".") ->
                "https://www.google.com/search?q=" + java.net.URLEncoder.encode(input, "UTF-8")
            else -> "https://$input"
        }
        webView.loadUrl(url)

        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(urlBar.windowToken, 0)
        urlBar.clearFocus()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}
