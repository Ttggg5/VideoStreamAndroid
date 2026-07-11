package com.videostream.local

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updateLayoutParams
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.recyclerview.widget.GridLayoutManager
import com.videostream.local.databinding.ActivityWatchBinding

/**
 * Lets this device act as a viewer, browsing and playing another device's hosted stream with
 * native views instead of an embedded browser. Hosts on the same local network are found
 * automatically via NSD/mDNS (the same mechanism [StreamingService] advertises itself under)
 * and listed for a tap-to-connect choice; typing an address manually and tapping Connect always
 * works too. Once connected, folder browsing ([BrowseAdapter]) and playback ([ExoPlayer] via
 * [androidx.media3.ui.PlayerView]) both talk to the host's JSON API (`/api/info`, `/api/browse`,
 * `/api/video`) and its existing `/video`/`/thumbnail` routes — see [RemoteLibraryApi].
 *
 * `/remote` support (see [MediaHttpServer]'s doc) is mirrored natively too: this activity keeps a
 * [RemoteStateSocket] open to `/remote/ws` the whole time it's connected, and the moment a host
 * ever makes a pick there, takes over the player entirely — hiding its own controls and following
 * play/pause/seek/video-switch commands, pushed the instant they happen rather than polled —
 * until the host leaves remote mode, at which point normal native controls come back for
 * whatever's currently playing.
 */
class WatchActivity : BaseActivity() {

    private lateinit var binding: ActivityWatchBinding
    private lateinit var nsdManager: NsdManager
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Keyed by NSD service name so repeat discovery callbacks update rather than duplicate an entry. */
    private val discoveredHosts = LinkedHashMap<String, DiscoveredHost>()
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var discoveryActive = false

    private enum class Screen { PRE_CONNECT, BROWSE, PLAYER }
    private var screen = Screen.PRE_CONNECT

    private var baseUrl: String? = null
    private var libraryInfo: RemoteLibraryInfo? = null
    private var currentPath = ""
    private var currentSort = "name"
    private var currentFlat = false
    /** The videos [browseAdapter] is currently showing — the playlist scope for whichever one gets tapped. */
    private var currentVideos: List<RemoteVideo> = emptyList()
    private var browseAdapter: BrowseAdapter? = null

    private var exoPlayer: ExoPlayer? = null
    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            binding.playerTitle.text = mediaItem?.mediaMetadata?.title ?: ""
        }

        override fun onPlayerError(error: PlaybackException) {
            Toast.makeText(this@WatchActivity, R.string.watch_playback_error, Toast.LENGTH_SHORT).show()
        }
    }

    /** True once a `/remote` pick has taken the player over — see class doc. */
    private var followingRemote = false
    private var remoteFollowVideoId: Int? = null
    /** True while [enterRemoteFollow]'s metadata fetch is in flight, so a poll landing in that
     *  window doesn't apply a play/pause/seek command to whatever the player still has loaded
     *  from before the switch. */
    private var remoteFollowLoading = false
    private var lastPlayRevision = -1L
    private var lastSeekRevision = -1L
    private var remoteStateSocket: RemoteStateSocket? = null

    private data class DiscoveredHost(val name: String, val host: String, val port: Int)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWatchBinding.inflate(layoutInflater)
        setContentView(binding.root)
        nsdManager = getSystemService(Context.NSD_SERVICE) as NsdManager

        if (AppSettings.getKeepScreenOnWhileWatching(this)) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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

        setUpBrowseSection()
        setUpPlayerSection()
        setUpWindowInsets()

        onBackPressedDispatcher.addCallback(this) {
            when (screen) {
                Screen.PLAYER -> onPlayerBack()
                Screen.BROWSE -> onBrowseBack()
                Screen.PRE_CONNECT -> {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        }

        val savedBaseUrl = savedInstanceState?.getString(STATE_BASE_URL)
        if (savedBaseUrl != null) {
            restoreConnection(savedInstanceState, savedBaseUrl)
        }
    }

    private fun setUpBrowseSection() {
        val spanCount = resources.getInteger(R.integer.video_grid_span_count)
        binding.browseRecyclerView.layoutManager = GridLayoutManager(this, spanCount)
        binding.browseBackButton.setOnClickListener { onBrowseBack() }
        binding.browseSortSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            arrayOf(getString(R.string.sort_name), getString(R.string.sort_date), getString(R.string.sort_size))
        )
        binding.browseSortSpinner.setOnItemSelectedListener(object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val newSort = SORT_VALUES.getOrElse(position) { SORT_VALUES[0] }
                if (newSort != currentSort) {
                    currentSort = newSort
                    loadBrowse(currentPath, currentFlat)
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        })
        binding.browseFlatSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked != currentFlat) {
                currentFlat = isChecked
                loadBrowse("", currentFlat)
            }
        }
    }

    private fun setUpPlayerSection() {
        binding.playerBackButton.setOnClickListener { onPlayerBack() }
        binding.playerAutoplaySwitch.isChecked = true
        binding.playerAutoplaySwitch.setOnCheckedChangeListener { _, isChecked ->
            exoPlayer?.pauseAtEndOfMediaItems = !isChecked
        }
        binding.playerShuffleSwitch.setOnCheckedChangeListener { _, isChecked ->
            exoPlayer?.shuffleModeEnabled = isChecked
        }
    }

    /**
     * browseSection's back button/title row is the one piece of this screen shown while system
     * bars are visible (playerSection stays immersive — see [setImmersiveMode]), so unlike every
     * other screen in the app it can't just rely on the window's default system-bar fitting: this
     * activity toggles [WindowCompat.setDecorFitsSystemWindows] itself on entering/leaving the
     * player, and that toggling is exactly the kind of thing that leaves content drawing under
     * the status bar/notch on some OEM skins if only relied on implicitly. Padding it by the
     * actual status bar inset keeps it clear regardless.
     */
    private fun setUpWindowInsets() {
        val baseTopMargin = (binding.browseBackButton.layoutParams as ViewGroup.MarginLayoutParams).topMargin
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val statusBarTop = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            binding.browseBackButton.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = baseTopMargin + statusBarTop
            }
            insets
        }
    }

    private fun restoreConnection(savedInstanceState: Bundle, savedBaseUrl: String) {
        baseUrl = savedBaseUrl
        val isFolderMode = savedInstanceState.getBoolean(STATE_IS_FOLDER_MODE)
        currentSort = savedInstanceState.getString(STATE_SORT) ?: SORT_VALUES[0]
        currentFlat = savedInstanceState.getBoolean(STATE_FLAT)
        libraryInfo = RemoteLibraryInfo(
            libraryName = savedInstanceState.getString(STATE_LIBRARY_NAME).orEmpty(),
            isFolderMode = isFolderMode,
            defaultSort = currentSort
        )
        resetRemoteFollowState()
        startRemoteStateSocket()
        if (isFolderMode) {
            showBrowse()
            // A config change (e.g. rotation) drops exact mid-video playback position the same
            // way the old WebView-based viewer did — that's live player state, not something a
            // recreated Activity can restore — but folder position/sort/flatten do carry over.
            loadBrowse(savedInstanceState.getString(STATE_PATH).orEmpty(), currentFlat)
        } else {
            loadSingleFileAndPlay(savedBaseUrl)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_BASE_URL, baseUrl)
        outState.putString(STATE_LIBRARY_NAME, libraryInfo?.libraryName)
        outState.putBoolean(STATE_IS_FOLDER_MODE, libraryInfo?.isFolderMode ?: false)
        outState.putString(STATE_PATH, currentPath)
        outState.putString(STATE_SORT, currentSort)
        outState.putBoolean(STATE_FLAT, currentFlat)
    }

    override fun onStart() {
        super.onStart()
        startDiscovery()
        if (baseUrl != null) startRemoteStateSocket()
    }

    override fun onStop() {
        stopDiscovery()
        remoteStateSocket?.stop()
        remoteStateSocket = null
        super.onStop()
    }

    override fun onDestroy() {
        releasePlayer()
        super.onDestroy()
    }

    // ---- Connecting ----------------------------------------------------------------------

    private fun connect() {
        val url = normalizeUrl(binding.addressInput.text?.toString().orEmpty())
        if (url == null) {
            Toast.makeText(this, R.string.watch_invalid_address, Toast.LENGTH_SHORT).show()
            return
        }
        connectTo(url)
    }

    private fun connectTo(url: String) {
        binding.preConnectSection.visibility = View.GONE
        binding.loadingProgress.visibility = View.VISIBLE
        baseUrl = url
        Thread {
            val info = RemoteLibraryApi.fetchInfo(url)
            runOnUiThread {
                if (baseUrl != url) return@runOnUiThread // superseded by another connect attempt
                binding.loadingProgress.visibility = View.GONE
                if (info == null) {
                    Toast.makeText(this, R.string.watch_connection_failed, Toast.LENGTH_LONG).show()
                    baseUrl = null
                    showPreConnect()
                    return@runOnUiThread
                }
                libraryInfo = info
                currentSort = info.defaultSort
                currentFlat = false
                currentPath = ""
                resetRemoteFollowState()
                startRemoteStateSocket()
                if (info.isFolderMode) {
                    showBrowse()
                    loadBrowse("", false)
                } else {
                    loadSingleFileAndPlay(url)
                }
            }
        }.start()
    }

    private fun loadSingleFileAndPlay(url: String) {
        Thread {
            val result = RemoteLibraryApi.fetchBrowse(url, "", "name", false)
            runOnUiThread {
                if (baseUrl != url) return@runOnUiThread
                val video = result?.videos?.firstOrNull()
                if (video == null) {
                    Toast.makeText(this, R.string.watch_connection_failed, Toast.LENGTH_LONG).show()
                    disconnect()
                    return@runOnUiThread
                }
                currentVideos = listOf(video)
                openPlayer(listOf(video), 0)
            }
        }.start()
    }

    private fun disconnect() {
        remoteStateSocket?.stop()
        remoteStateSocket = null
        resetRemoteFollowState()
        releasePlayer()
        baseUrl = null
        libraryInfo = null
        currentPath = ""
        currentVideos = emptyList()
        // A reconnect might go to a different host — drop this one so ensureBrowseAdapter()
        // builds a fresh adapter bound to the new base URL instead of reusing this one's,
        // which would otherwise keep pointing thumbnails at the old host.
        browseAdapter = null
        binding.browseRecyclerView.adapter = null
        showPreConnect()
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

    // ---- Browsing --------------------------------------------------------------------------

    private fun loadBrowse(path: String, flat: Boolean) {
        val url = baseUrl ?: return
        binding.browseRecyclerView.visibility = View.INVISIBLE
        binding.browseLoadingProgress.visibility = View.VISIBLE
        binding.browseEmptyText.visibility = View.GONE
        Thread {
            val result = RemoteLibraryApi.fetchBrowse(url, path, currentSort, flat)
            runOnUiThread {
                if (baseUrl != url) return@runOnUiThread
                binding.browseLoadingProgress.visibility = View.GONE
                if (result == null) {
                    binding.browseEmptyText.text = getString(R.string.watch_browse_failed)
                    binding.browseEmptyText.visibility = View.VISIBLE
                    return@runOnUiThread
                }
                currentPath = result.effectivePath
                currentFlat = flat
                currentVideos = result.videos
                binding.browseTitle.text = result.title
                binding.browseFlatSwitch.isChecked = flat

                val adapter = ensureBrowseAdapter(url)
                val items = mutableListOf<BrowseItem>()
                result.subfolders.forEach { name ->
                    val childPath = if (currentPath.isEmpty()) name else "$currentPath/$name"
                    items.add(BrowseItem.Folder(name, childPath))
                }
                result.videos.forEach { video ->
                    val subtitle = if (flat && video.folderPath.isNotEmpty()) video.folderPath else null
                    items.add(BrowseItem.Video(video, subtitle))
                }
                adapter.submitList(items)
                if (items.isEmpty()) {
                    binding.browseEmptyText.text = getString(R.string.watch_browse_empty)
                    binding.browseEmptyText.visibility = View.VISIBLE
                } else {
                    binding.browseRecyclerView.visibility = View.VISIBLE
                }
            }
        }.start()
    }

    private fun ensureBrowseAdapter(url: String): BrowseAdapter {
        var adapter = browseAdapter
        if (adapter == null) {
            adapter = BrowseAdapter(
                baseUrl = url,
                onFolderClick = { folder -> loadBrowse(folder.path, currentFlat) },
                onVideoClick = { item -> openPlayerFromBrowse(item.video) }
            )
            browseAdapter = adapter
            binding.browseRecyclerView.adapter = adapter
            val spanCount = resources.getInteger(R.integer.video_grid_span_count)
            (binding.browseRecyclerView.layoutManager as GridLayoutManager).spanSizeLookup =
                adapter.spanSizeLookup(spanCount)
        }
        return adapter
    }

    private fun onBrowseBack() {
        if (currentFlat || currentPath.isEmpty()) {
            disconnect()
        } else {
            loadBrowse(currentPath.substringBeforeLast('/', ""), false)
        }
    }

    // ---- Playback --------------------------------------------------------------------------

    private fun openPlayerFromBrowse(video: RemoteVideo) {
        val index = currentVideos.indexOfFirst { it.id == video.id }.coerceAtLeast(0)
        openPlayer(currentVideos, index)
    }

    private fun openPlayer(videos: List<RemoteVideo>, startIndex: Int) {
        val url = baseUrl ?: return
        followingRemote = false
        val player = ensurePlayer()
        val hasPlaylist = videos.size > 1
        binding.playerAutoplaySwitch.visibility = if (hasPlaylist) View.VISIBLE else View.GONE
        binding.playerShuffleSwitch.visibility = if (hasPlaylist) View.VISIBLE else View.GONE
        player.pauseAtEndOfMediaItems = !binding.playerAutoplaySwitch.isChecked
        player.shuffleModeEnabled = hasPlaylist && binding.playerShuffleSwitch.isChecked
        player.setMediaItems(videos.map { toMediaItem(url, it) }, startIndex, 0L)
        player.prepare()
        player.playWhenReady = true
        binding.playerTitle.text = videos.getOrNull(startIndex)?.name ?: ""
        applyControllerVisible(true)
        showPlayer()
    }

    private fun toMediaItem(url: String, video: RemoteVideo): MediaItem =
        MediaItem.Builder()
            .setUri("$url/video?id=${video.id}")
            .setMediaId(video.id.toString())
            .setMimeType(video.type)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(video.name).build())
            .build()

    private fun ensurePlayer(): ExoPlayer {
        var player = exoPlayer
        if (player == null) {
            player = ExoPlayer.Builder(this).build()
            player.addListener(playerListener)
            binding.playerView.player = player
            exoPlayer = player
        }
        return player
    }

    private fun releasePlayer() {
        exoPlayer?.let {
            it.removeListener(playerListener)
            it.release()
        }
        exoPlayer = null
        binding.playerView.player = null
    }

    private fun applyControllerVisible(visible: Boolean) {
        binding.playerView.setUseController(visible)
        binding.playerTopBar.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun onPlayerBack() {
        if (followingRemote) {
            // The remote is still driving this screen — there's nothing of "my own" to go back
            // to, so back means leaving the stream entirely, same as backing out of a live TV
            // channel someone else is controlling.
            disconnect()
            return
        }
        releasePlayer()
        if (libraryInfo?.isFolderMode == true) {
            showBrowse()
        } else {
            disconnect()
        }
    }

    // ---- Screen visibility -------------------------------------------------------------------

    private fun showPreConnect() {
        screen = Screen.PRE_CONNECT
        binding.preConnectSection.visibility = View.VISIBLE
        binding.browseSection.visibility = View.GONE
        binding.playerSection.visibility = View.GONE
        setImmersiveMode(false)
    }

    private fun showBrowse() {
        screen = Screen.BROWSE
        binding.preConnectSection.visibility = View.GONE
        binding.browseSection.visibility = View.VISIBLE
        binding.playerSection.visibility = View.GONE
        setImmersiveMode(false)
    }

    private fun showPlayer() {
        screen = Screen.PLAYER
        binding.preConnectSection.visibility = View.GONE
        binding.browseSection.visibility = View.GONE
        binding.playerSection.visibility = View.VISIBLE
        setImmersiveMode(true)
    }

    /** Hides/restores the status and navigation bars — the player fills the whole screen. */
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

    // ---- Following a host's /remote pick --------------------------------------------------

    private fun resetRemoteFollowState() {
        followingRemote = false
        remoteFollowVideoId = null
        remoteFollowLoading = false
        lastPlayRevision = -1L
        lastSeekRevision = -1L
    }

    private fun startRemoteStateSocket() {
        val url = baseUrl ?: return
        remoteStateSocket?.stop()
        remoteStateSocket = RemoteStateSocket(url, mainHandler) { state ->
            if (baseUrl == url) applyRemoteState(state)
        }.also { it.start() }
    }

    private fun applyRemoteState(state: RemoteState?) {
        state ?: return
        if (state.videoId != null) {
            if (state.videoId != remoteFollowVideoId) {
                remoteFollowVideoId = state.videoId
                lastPlayRevision = state.playRevision
                lastSeekRevision = state.seekRevision
                enterRemoteFollow(state.videoId, state.playing, state.positionSeconds)
                return
            }
            if (remoteFollowLoading) return
            if (state.playRevision != lastPlayRevision) {
                lastPlayRevision = state.playRevision
                if (state.playing) exoPlayer?.play() else exoPlayer?.pause()
            }
            if (state.seekRevision != lastSeekRevision) {
                lastSeekRevision = state.seekRevision
                state.seekSeconds?.let { exoPlayer?.seekTo((it * 1000).toLong()) }
            }
        } else if (remoteFollowVideoId != null) {
            // The host left remote mode — hand control back to a normal player for whatever's
            // already loaded, the same as the bare web viewer falling back to a normal watch page.
            remoteFollowVideoId = null
            followingRemote = false
            if (screen == Screen.PLAYER) applyControllerVisible(true)
        }
    }

    /** [positionSeconds] is the host's estimated current playback position — non-zero when
     *  playback was already underway before this device joined, so it starts in roughly the
     *  right spot instead of from the beginning. */
    private fun enterRemoteFollow(videoId: Int, playing: Boolean, positionSeconds: Double) {
        val url = baseUrl ?: return
        followingRemote = true
        remoteFollowLoading = true
        Thread {
            val video = RemoteLibraryApi.fetchVideo(url, videoId)
            runOnUiThread {
                // The remote may have moved on again while this metadata fetch was in flight.
                if (baseUrl != url || remoteFollowVideoId != videoId) return@runOnUiThread
                remoteFollowLoading = false
                val player = ensurePlayer()
                val item = if (video != null) {
                    toMediaItem(url, video)
                } else {
                    MediaItem.Builder().setUri("$url/video?id=$videoId").build()
                }
                player.pauseAtEndOfMediaItems = false
                player.shuffleModeEnabled = false
                player.setMediaItem(item)
                if (positionSeconds > 0) player.seekTo((positionSeconds * 1000).toLong())
                player.prepare()
                player.playWhenReady = playing
                binding.playerTitle.text = video?.name ?: ""
                binding.playerAutoplaySwitch.visibility = View.GONE
                binding.playerShuffleSwitch.visibility = View.GONE
                applyControllerVisible(false)
                showPlayer()
            }
        }.start()
    }

    // ---- Discovery ---------------------------------------------------------------------------

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
                connectTo("http://${host.host}:${host.port}")
            }
            container.addView(row)
        }
        binding.discoveryEmptyText.visibility = if (hosts.isEmpty()) View.VISIBLE else View.GONE
    }

    companion object {
        // Must stay in the same order as the labels populating browseSortSpinner's adapter.
        private val SORT_VALUES = arrayOf("name", "date", "size")

        private const val STATE_BASE_URL = "baseUrl"
        private const val STATE_LIBRARY_NAME = "libraryName"
        private const val STATE_IS_FOLDER_MODE = "isFolderMode"
        private const val STATE_PATH = "path"
        private const val STATE_SORT = "sort"
        private const val STATE_FLAT = "flat"
    }
}
