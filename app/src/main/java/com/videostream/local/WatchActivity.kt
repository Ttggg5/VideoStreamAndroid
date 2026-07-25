package com.videostream.local

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
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
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.annotation.OptIn
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updateLayoutParams
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.GridLayoutManager
import com.google.android.material.color.MaterialColors
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
    /** Mirrors [binding]'s browseTitle text so [reattachState] can restore it after a rotation
     *  re-inflates the layout, without re-fetching the listing from the host. */
    private var currentBrowseTitle: String = ""

    private var exoPlayer: ExoPlayer? = null
    /** Persisted across videos and player instances (unlike the [ExoPlayer] itself, which gets
     *  recreated whenever the user backs out to browse and opens something else) — see
     *  [setUpAutoplayButton]/[openPlayer]. Default true matches the old Autoplay switch's default. */
    private var autoplayEnabled = true
    /** Mirrors the built-in Shuffle button's own live [ExoPlayer.getShuffleModeEnabled] state
     *  (updated via [Player.Listener.onShuffleModeEnabledChanged] below) so it carries over to
     *  the next [ExoPlayer] instance the same way [autoplayEnabled] does. */
    private var shuffleEnabled = false
    private var autoplayButton: ImageButton? = null
    /** Tracks the player's fullscreen (forced-landscape) toggle across rotations — a freshly
     *  re-inflated [androidx.media3.ui.PlayerView] always starts believing it isn't fullscreen
     *  (see [setUpFullscreenButton]), so this is what [setUpFullscreenButton] uses to bring a new
     *  one back in sync after a rotation caused by entering fullscreen in the first place. */
    private var isFullScreen = false
    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            binding.playerTitle.text = mediaItem?.mediaMetadata?.title ?: ""
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            shuffleEnabled = shuffleModeEnabled
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

        setUpViews()

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

    /**
     * Handled here (see the manifest's `android:configChanges` on this activity) instead of
     * letting the framework recreate the whole Activity on rotation — a recreate tore down and
     * reconnected everything, including the [ExoPlayer], so the video restarted from scratch on
     * every rotate. Re-inflating the layout (picking up `layout-land` where relevant) and
     * re-attaching the still-alive player/adapter/connection state instead keeps playback running
     * straight through it.
     */
    @OptIn(UnstableApi::class)
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Whether the controls (and with them the top bar) were showing before the rotation.
        // Re-attaching the player to a freshly-inflated PlayerView below makes it re-show its
        // controller, so this is captured now and used to hide it again afterwards — otherwise
        // rotating a playing video pops the top bar back into view even though it had auto-hidden.
        val controllerWasVisible = binding.playerView.isControllerFullyVisible
        binding = ActivityWatchBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setUpViews()
        reattachState()
        if (screen == Screen.PLAYER && !followingRemote && !controllerWasVisible) {
            binding.playerView.hideController()
        }
    }

    /** Wires up listeners/layout managers against the current [binding] — called once from
     *  [onCreate] and again from [onConfigurationChanged] every time the layout is re-inflated. */
    private fun setUpViews() {
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
    }

    /**
     * Re-attaches state that lives outside [binding] (the [browseAdapter]'s already-fetched
     * items, the still-playing [exoPlayer], which screen was showing) to the layout that
     * [onConfigurationChanged] just re-inflated, instead of re-fetching or restarting anything.
     */
    private fun reattachState() {
        browseAdapter?.let { binding.browseRecyclerView.adapter = it }
        exoPlayer?.let { binding.playerView.player = it }
        binding.discoveryProgress.visibility = if (discoveryActive) View.VISIBLE else View.GONE
        renderDiscoveredHosts()

        binding.browseTitle.text = currentBrowseTitle
        binding.browseFlatSwitch.isChecked = currentFlat
        if ((browseAdapter?.itemCount ?: 0) > 0) {
            binding.browseRecyclerView.visibility = View.VISIBLE
        } else if (screen == Screen.BROWSE) {
            binding.browseEmptyText.text = getString(R.string.watch_browse_empty)
            binding.browseEmptyText.visibility = View.VISIBLE
        }

        val player = exoPlayer
        if (player != null) {
            binding.playerTitle.text = player.currentMediaItem?.mediaMetadata?.title ?: ""
            // The Autoplay button's tint (updateAutoplayButtonTint, called from
            // setUpAutoplayButton above) and the built-in Shuffle button's own icon state
            // (synced automatically once binding.playerView.player is set above) both already
            // reflect the current state without anything further needed here.
            applyControllerVisible(!followingRemote)
        }

        when (screen) {
            Screen.PRE_CONNECT -> showPreConnect()
            Screen.BROWSE -> showBrowse()
            Screen.PLAYER -> showPlayer()
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
        // Set before attaching the listener below — a fresh Spinner otherwise fires
        // onItemSelected for position 0 as soon as a listener's attached, which would reset
        // back to "Name" (and reload) on every rotation whenever currentSort wasn't already that.
        binding.browseSortSpinner.setSelection(SORT_VALUES.indexOf(currentSort).coerceAtLeast(0))
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
        setUpAutoplayButton()
        setUpControllerVisibilityBinding()
        setUpFullscreenButton()
    }

    /**
     * [playerTopBar] (the back button + title) isn't part of media3's own control bar, but
     * should read as if it were — this makes it show/hide right along with the real controller,
     * whatever the reason: tapping the video to toggle it, the controller's own auto-hide timer,
     * or [setUpFullscreenButton] hiding it on entering fullscreen. Without this it was a
     * separately-timed overlay that could end up visible while the real controls had already
     * auto-hidden, or vice versa.
     */
    private fun setUpControllerVisibilityBinding() {
        // PlayerView overloads setControllerVisibilityListener for the deprecated
        // PlayerControlView.VisibilityListener as well as this one, and both are SAM-convertible
        // from a lambda — a bare trailing lambda is ambiguous between the two, so the listener
        // type has to be spelled out explicitly.
        binding.playerView.setControllerVisibilityListener(
            PlayerView.ControllerVisibilityListener { visibility -> binding.playerTopBar.visibility = visibility }
        )
    }

    /**
     * media3's fullscreen button (`exo_fullscreen`) is hidden by default and does nothing on its
     * own — registering this listener is what makes it visible, and it's on the app to decide
     * what "fullscreen" actually means. Here that's forcing landscape (there's no separate
     * fullscreen layout; playerSection already fills the whole screen either way, so rotating to
     * landscape is what actually removes the letterboxing on a normally-portrait phone) and
     * letting the existing rotation-without-recreating-the-player handling
     * (`android:configChanges` + [onConfigurationChanged]) do the rest. Explicitly hiding/showing
     * the controller (rather than touching [playerTopBar] directly) is what makes it disappear
     * together with the rest of the controls via [setUpControllerVisibilityBinding] instead of
     * as a one-off special case.
     */
    @OptIn(UnstableApi::class)
    private fun setUpFullscreenButton() {
        binding.playerView.setFullscreenButtonClickListener { fullScreen ->
            isFullScreen = fullScreen
            requestedOrientation = if (fullScreen) {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            } else {
                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
            if (fullScreen) binding.playerView.hideController() else binding.playerView.showController()
        }
        if (isFullScreen) {
            // A freshly re-inflated PlayerView always starts showing its "enter fullscreen" icon
            // regardless of our own state above, and there's no public setter to correct that
            // directly — clicking the real button is the only way to bring it back in sync.
            binding.playerView.findViewById<View>(androidx.media3.ui.R.id.exo_fullscreen)?.performClick()
        }
    }

    /**
     * Ties Autoplay and Shuffle into the [androidx.media3.ui.PlayerView]'s own control bar
     * instead of a separate pair of switches in the app's own UI — Shuffle is media3's built-in
     * button ([androidx.media3.ui.PlayerView.setShowShuffleButton], which already toggles
     * [ExoPlayer.getShuffleModeEnabled] on tap by itself); Autoplay has no built-in equivalent,
     * so a matching custom button is added into `exo_basic_controls` — the always-visible bottom
     * icon row, a stable id media3-ui has exposed for exactly this since ExoPlayer's classic
     * `PlayerControlView`. Sizing/padding match media3's own small icon buttons
     * (`exo_small_icon_width/height`, `exo_small_icon_padding_horizontal/vertical`) so it sits
     * naturally alongside them instead of looking bolted on.
     */
    private fun setUpAutoplayButton() {
        binding.playerView.setShowShuffleButton(true)
        val basicControls = binding.playerView.findViewById<LinearLayout>(androidx.media3.ui.R.id.exo_basic_controls)
            ?: return
        val margin = dpToPx(2)
        val padding = dpToPx(12)
        val button = ImageButton(this, null, android.R.attr.borderlessButtonStyle).apply {
            layoutParams = LinearLayout.LayoutParams(dpToPx(48), dpToPx(48)).apply {
                marginStart = margin
                marginEnd = margin
            }
            setPadding(padding, padding, padding, padding)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageResource(R.drawable.ic_autoplay)
            contentDescription = getString(R.string.watch_autoplay)
            isFocusable = true
            setOnClickListener { setAutoplayEnabled(!autoplayEnabled) }
        }
        autoplayButton = button
        basicControls.addView(button, 0)
        updateAutoplayButtonTint()
    }

    private fun setAutoplayEnabled(enabled: Boolean) {
        autoplayEnabled = enabled
        exoPlayer?.pauseAtEndOfMediaItems = !enabled
        updateAutoplayButtonTint()
    }

    /** Accent color while on, matching the rest of the app's toggle-active convention (see e.g.
     *  the folder-tile icon tint), plain white — matching every other control-bar icon's default
     *  — while off. */
    private fun updateAutoplayButtonTint() {
        val color = if (autoplayEnabled) {
            MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary, Color.WHITE)
        } else {
            Color.WHITE
        }
        autoplayButton?.imageTintList = ColorStateList.valueOf(color)
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

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
        currentBrowseTitle = ""
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
                currentBrowseTitle = result.title
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
        player.pauseAtEndOfMediaItems = !autoplayEnabled
        player.shuffleModeEnabled = hasPlaylist && shuffleEnabled
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
            // DefaultLoadControl's stock thresholds are tuned for internet streaming (a 2.5s
            // playback-start threshold and up to 50s of target buffer); over a local network the
            // whole file is reachable at Wi-Fi speed, so both starting playback and recovering
            // from a stall can happen much sooner without any real risk of re-stalling.
            val loadControl = DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    /* minBufferMs= */ 15_000,
                    /* maxBufferMs= */ 30_000,
                    /* bufferForPlaybackMs= */ 500,
                    /* bufferForPlaybackAfterRebufferMs= */ 1_000
                )
                .build()
            // Drives media3's built-in rewind/fast-forward buttons off the same Settings > Skip
            // interval that /remote's skip buttons use, so "jump" means the same thing everywhere.
            val skipMs = AppSettings.getSkipSeconds(this).toLong() * 1000
            player = ExoPlayer.Builder(this)
                .setLoadControl(loadControl)
                .setSeekBackIncrementMs(skipMs)
                .setSeekForwardIncrementMs(skipMs)
                .build()
            // Holds a partial wake lock AND a Wi-Fi lock while playing. Without this, the device's
            // Wi-Fi radio can power-save mid-stream (especially on Android TV, and during the gaps
            // when the buffer above is full and nothing's being fetched), stalling the connection
            // to the host until a stuck radio recovers — which is why playback could drop after a
            // few minutes and not come back even on an app restart. Uses the WAKE_LOCK permission.
            player.setWakeMode(C.WAKE_MODE_NETWORK)
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
        // Leaving the player screen shouldn't leave the browse/pre-connect screens stuck
        // sideways if the video was left in fullscreen.
        if (isFullScreen) {
            isFullScreen = false
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    private fun applyControllerVisible(visible: Boolean) {
        binding.playerView.setUseController(visible)
        // Not "visible" on its own — reattachState() calls this with true on every rotation
        // (including one caused by [setUpFullscreenButton] itself forcing landscape), and it
        // shouldn't force playerTopBar back on over top of a fullscreen session that had
        // already hidden it via [setUpControllerVisibilityBinding].
        binding.playerTopBar.visibility = if (visible && !isFullScreen) View.VISIBLE else View.GONE
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
