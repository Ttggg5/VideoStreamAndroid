package com.videostream.local

import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import com.videostream.local.databinding.ActivityWatchBinding

/** Lets this device act as a viewer, opening another device's hosted stream in an embedded browser. */
class WatchActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWatchBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWatchBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.webView.settings.apply {
            @Suppress("SetJavaScriptEnabled")
            javaScriptEnabled = false
            mediaPlaybackRequiresUserGesture = false
            domStorageEnabled = true
        }
        binding.webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                binding.loadingProgress.visibility = View.VISIBLE
                binding.watchHintText.visibility = View.GONE
            }

            override fun onPageFinished(view: WebView, url: String) {
                binding.loadingProgress.visibility = View.GONE
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                if (request.isForMainFrame) {
                    binding.loadingProgress.visibility = View.GONE
                    Toast.makeText(
                        this@WatchActivity,
                        getString(R.string.watch_connection_failed),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

        binding.connectButton.setOnClickListener { connect() }
        binding.addressInput.setOnEditorActionListener { _, actionId, event ->
            val isGo = actionId == EditorInfo.IME_ACTION_GO
            val isEnterDown = event != null && event.keyCode == KeyEvent.KEYCODE_ENTER &&
                event.action == KeyEvent.ACTION_DOWN
            if (isGo || isEnterDown) {
                connect()
                true
            } else {
                false
            }
        }

        onBackPressedDispatcher.addCallback(this) {
            if (binding.webView.canGoBack()) {
                binding.webView.goBack()
            } else {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        }
    }

    private fun connect() {
        val url = normalizeUrl(binding.addressInput.text?.toString().orEmpty())
        if (url == null) {
            Toast.makeText(this, R.string.watch_invalid_address, Toast.LENGTH_SHORT).show()
            return
        }
        binding.webView.loadUrl(url)
    }

    private fun normalizeUrl(rawInput: String): String? {
        val input = rawInput.trim()
        if (input.isEmpty()) return null

        val withScheme = if (input.startsWith("http://") || input.startsWith("https://")) {
            input
        } else {
            "http://$input"
        }

        val uri = try {
            Uri.parse(withScheme)
        } catch (_: Exception) {
            null
        } ?: return null

        val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
        val port = if (uri.port != -1) uri.port else StreamingService.HTTP_PORT
        val path = uri.path.orEmpty()
        return "http://$host:$port$path"
    }

    override fun onDestroy() {
        binding.webView.destroy()
        super.onDestroy()
    }
}
