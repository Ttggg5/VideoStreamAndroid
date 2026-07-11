package com.videostream.local

import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.addCallback
import com.videostream.local.databinding.ActivityRemoteControlBinding

/**
 * Loads this device's own `/remote` page in an embedded WebView, so the host can pick what
 * plays on every connected viewer without leaving the app. Only ever points at localhost —
 * this app's own [StreamingService] must already be streaming a folder for [HostActivity] to
 * have shown the button that launches this activity.
 */
class RemoteControlActivity : BaseActivity() {

    private lateinit var binding: ActivityRemoteControlBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRemoteControlBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.webView.settings.apply {
            @Suppress("SetJavaScriptEnabled")
            javaScriptEnabled = true
            domStorageEnabled = true
        }
        binding.webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                binding.loadingProgress.visibility = View.VISIBLE
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
                        this@RemoteControlActivity,
                        getString(R.string.watch_connection_failed),
                        Toast.LENGTH_LONG
                    ).show()
                }
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

        if (savedInstanceState == null) {
            val port = AppSettings.getHttpPort(this)
            binding.webView.loadUrl("http://127.0.0.1:$port/remote")
        } else {
            binding.webView.restoreState(savedInstanceState)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        binding.webView.saveState(outState)
    }

    override fun onDestroy() {
        binding.webView.destroy()
        super.onDestroy()
    }
}
