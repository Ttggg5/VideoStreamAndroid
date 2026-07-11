package com.videostream.local

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.videostream.local.databinding.ActivityWatchBinding

/**
 * Lets this device act as a viewer, opening another device's hosted stream in an embedded
 * browser. Hosts on the same local network are found automatically via NSD/mDNS (the same
 * mechanism [StreamingService] advertises itself under) and listed for a tap-to-connect choice;
 * typing an address manually and tapping Connect always works too, in case discovery doesn't
 * reach a particular network (e.g. some Wi-Fi hotspot configurations isolate multicast traffic).
 */
class WatchActivity : BaseActivity() {

    private lateinit var binding: ActivityWatchBinding
    private lateinit var nsdManager: NsdManager

    /** Keyed by NSD service name so repeat discovery callbacks update rather than duplicate an entry. */
    private val discoveredHosts = LinkedHashMap<String, DiscoveredHost>()
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var discoveryActive = false
    private var connected = false

    // Tracks the native view Chromium hands back while an in-page <video> (e.g. video.js's
    // fullscreen button) is fullscreen, so the back button can exit it and onHideCustomView
    // can clean up — a plain WebView without a WebChromeClient can't enter fullscreen at all.
    private var fullscreenView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null

    private data class DiscoveredHost(val name: String, val host: String, val port: Int)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWatchBinding.inflate(layoutInflater)
        setContentView(binding.root)
        nsdManager = getSystemService(Context.NSD_SERVICE) as NsdManager

        if (AppSettings.getKeepScreenOnWhileWatching(this)) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        binding.webView.settings.apply {
            // Required for the host's playlist / next-video-without-reload player script.
            // Safe here: this WebView only ever loads pages this app's own server renders by
            // default, and no JavascriptInterface bridge is exposed to give page script any
            // access beyond the normal WebView sandbox.
            @Suppress("SetJavaScriptEnabled")
            javaScriptEnabled = true
            mediaPlaybackRequiresUserGesture = false
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
                        this@WatchActivity,
                        getString(R.string.watch_connection_failed),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
        binding.webView.webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                if (fullscreenView != null) {
                    callback.onCustomViewHidden()
                    return
                }
                fullscreenView = view
                fullscreenCallback = callback
                binding.fullscreenContainer.addView(view)
                binding.fullscreenContainer.visibility = View.VISIBLE
                binding.webView.visibility = View.INVISIBLE
                setImmersiveMode(true)
            }

            override fun onHideCustomView() {
                binding.fullscreenContainer.removeAllViews()
                binding.fullscreenContainer.visibility = View.GONE
                binding.webView.visibility = View.VISIBLE
                fullscreenView = null
                fullscreenCallback = null
                setImmersiveMode(false)
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
        binding.discoveryRefreshButton.setOnClickListener { restartDiscovery() }

        onBackPressedDispatcher.addCallback(this) {
            when {
                fullscreenView != null -> fullscreenCallback?.onCustomViewHidden()
                binding.webView.canGoBack() -> binding.webView.goBack()
                else -> {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        }

        // Config-change recreation (e.g. rotating to landscape) would otherwise drop the
        // user right back to the connect screen mid-stream. WebView.restoreState() re-navigates
        // to the same page from its saved history — it can't resume exact video playback
        // position since that's live JS/DOM state, but at least it doesn't lose the page.
        if (savedInstanceState != null && binding.webView.restoreState(savedInstanceState) != null) {
            connected = savedInstanceState.getBoolean(STATE_CONNECTED)
            if (connected) {
                binding.preConnectSection.visibility = View.GONE
                binding.webView.visibility = View.VISIBLE
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        binding.webView.saveState(outState)
        outState.putBoolean(STATE_CONNECTED, connected)
    }

    override fun onStart() {
        super.onStart()
        startDiscovery()
    }

    override fun onStop() {
        stopDiscovery()
        super.onStop()
    }

    private fun connect() {
        val url = normalizeUrl(binding.addressInput.text?.toString().orEmpty())
        if (url == null) {
            Toast.makeText(this, R.string.watch_invalid_address, Toast.LENGTH_SHORT).show()
            return
        }
        loadUrl(url)
    }

    private fun loadUrl(url: String) {
        connected = true
        binding.preConnectSection.visibility = View.GONE
        binding.webView.visibility = View.VISIBLE
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
        val port = if (uri.port != -1) uri.port else AppSettings.getHttpPort(this)
        val path = uri.path.orEmpty()
        return "http://$host:$port$path"
    }

    private fun startDiscovery() {
        if (discoveryActive) return
        discoveredHosts.clear()
        renderDiscoveredHosts()
        binding.discoveryProgress.visibility = View.VISIBLE

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                discoveryActive = true
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                resolveService(serviceInfo)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                runOnUiThread {
                    discoveredHosts.remove(serviceInfo.serviceName)
                    renderDiscoveredHosts()
                }
            }

            override fun onDiscoveryStopped(serviceType: String) {
                discoveryActive = false
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                discoveryActive = false
                runOnUiThread { binding.discoveryProgress.visibility = View.GONE }
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                discoveryActive = false
            }
        }
        discoveryListener = listener
        try {
            nsdManager.discoverServices(StreamingService.NSD_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (_: Exception) {
            discoveryListener = null
            binding.discoveryProgress.visibility = View.GONE
        }
    }

    private fun stopDiscovery() {
        binding.discoveryProgress.visibility = View.GONE
        val listener = discoveryListener ?: return
        discoveryListener = null
        if (discoveryActive) {
            try {
                nsdManager.stopServiceDiscovery(listener)
            } catch (_: Exception) {
                // Already stopped, or never fully started; nothing to clean up.
            }
        }
        discoveryActive = false
    }

    private fun restartDiscovery() {
        stopDiscovery()
        startDiscovery()
    }

    private fun resolveService(serviceInfo: NsdServiceInfo) {
        val resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = Unit

            override fun onServiceResolved(info: NsdServiceInfo) {
                val address = info.host?.hostAddress ?: return
                runOnUiThread {
                    discoveredHosts[info.serviceName] = DiscoveredHost(info.serviceName, address, info.port)
                    renderDiscoveredHosts()
                }
            }
        }
        try {
            nsdManager.resolveService(serviceInfo, resolveListener)
        } catch (_: Exception) {
            // A resolve may already be in flight for a duplicate onServiceFound callback; skip it.
        }
    }

    private fun renderDiscoveredHosts() {
        val container = binding.discoveredHostsList
        container.removeAllViews()
        val hosts = discoveredHosts.values.sortedBy { it.name.lowercase() }
        for (host in hosts) {
            val row = layoutInflater.inflate(R.layout.item_discovered_host, container, false)
            row.findViewById<TextView>(R.id.hostName).text = host.name
            row.findViewById<TextView>(R.id.hostAddress).text = "${host.host}:${host.port}"
            row.setOnClickListener {
                binding.addressInput.setText("${host.host}:${host.port}")
                loadUrl("http://${host.host}:${host.port}")
            }
            container.addView(row)
        }
        binding.discoveryEmptyText.visibility = if (hosts.isEmpty()) View.VISIBLE else View.GONE
    }

    /** Hides/restores the status and navigation bars for fullscreen video playback. */
    private fun setImmersiveMode(enabled: Boolean) {
        WindowCompat.setDecorFitsSystemWindows(window, !enabled)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (enabled) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onDestroy() {
        binding.webView.destroy()
        super.onDestroy()
    }

    companion object {
        private const val STATE_CONNECTED = "connected"
    }
}
