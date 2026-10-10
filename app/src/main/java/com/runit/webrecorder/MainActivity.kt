package com.runit.webrecorder

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var urlBar: EditText
    private lateinit var loadingBar: ProgressBar
    private lateinit var recBtn: Button

    private val uiHandler = Handler(Looper.getMainLooper())
    private val syncRunnable = object : Runnable {
        override fun run() {
            updateRecButton()
            uiHandler.postDelayed(this, 500)
        }
    }

    // Result of the "Start recording?" system popup
    private val captureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) {
            val svc = Intent(this, RecorderService::class.java).apply {
                action = RecorderService.ACTION_START
                putExtra(RecorderService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(RecorderService.EXTRA_DATA, data)
            }
            ContextCompat.startForegroundService(this, svc)
        } else {
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

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        // Top bar: URL box + Go + Rec
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

        recBtn = Button(this).apply {
            text = "Rec"
            setOnClickListener { toggleRecording() }
        }
        bar.addView(
            recBtn,
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

    private fun toggleRecording() {
        if (RecorderService.isSaving) {
            Toast.makeText(this, "Saving, please wait", Toast.LENGTH_SHORT).show()
            return
        }

        if (RecorderService.isRecording) {
            val svc = Intent(this, RecorderService::class.java).apply {
                action = RecorderService.ACTION_STOP
            }
            startService(svc)
            return
        }

        // Collect missing permissions (mic for audio, notifications on Android 13+)
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

    private fun updateRecButton() {
        recBtn.text = when {
            RecorderService.isSaving -> "Saving"
            RecorderService.isRecording -> "Stop"
            else -> "Rec"
        }
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
