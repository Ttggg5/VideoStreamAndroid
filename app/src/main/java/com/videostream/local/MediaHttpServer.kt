package com.videostream.local

import android.content.ContentResolver
import android.content.res.AssetManager
import android.os.ParcelFileDescriptor
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.FileInputStream
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicReference

/** Read-ahead size for [MediaHttpServer.serveVideo]'s BufferedInputStream — see its comment. */
private const val VIDEO_STREAM_BUFFER_SIZE = 256 * 1024

/**
 * Serves one or more existing video files over HTTP with byte-range support, so a
 * browser or media player on another device can start playing and seek without
 * downloading the whole file first. In folder mode, `/browse` mirrors the chosen
 * folder's actual directory structure (subfolders navigate further, matching entries'
 * [VideoEntry.folderPath]) by default; a plain single file goes straight to the player.
 *
 * Whether to flatten that structure, autoplay the next video, and whether to shuffle are
 * all viewer-local choices — controlled by a toggle rendered on the page itself and
 * remembered per-browser via `localStorage`, rather than something the host configures
 * once for every viewer.
 *
 * The player itself is [video.js](https://videojs.com), bundled under `assets/videojs/`
 * and served from `/assets/...` so playback works with no internet access — same as
 * everything else this server serves.
 *
 * `/remote` is a second way to pick a video: instead of a viewer browsing and choosing for
 * themselves, whoever loads `/remote` (the host app's own screen, or any other browser on the
 * LAN) picks on everyone else's behalf, like a TV remote, and gets a transport control panel
 * (play/pause/seek) for whatever's currently selected — the panel decodes the video just enough
 * to drive a real seek bar, but stays muted and never shows the picture, since this device is
 * controlling the stream, not watching it. Every `/browse` and `/watch` page connects to
 * `/remote/ws`, which pushes the current state the instant it's opened and again on every
 * subsequent change — no polling interval to wait out — and once anything's been selected there,
 * hands off entirely to a bare, control-less full-screen player that just follows along — no
 * viewer keeps their own play/pause/seek controls once a remote is driving. Leaving remote mode
 * (the control panel's "Exit remote mode" button, which calls `/remote/clear`) hands every
 * viewer straight back to a normal watch page with its own controls.
 */
class MediaHttpServer(
    port: Int,
    private val contentResolver: ContentResolver,
    private val assetManager: AssetManager,
    private val entries: List<VideoEntry>,
    private val libraryName: String,
    private val isFolderMode: Boolean,
    /** One of [SortMode]'s `param` values, used when a request doesn't specify `?sort=`. */
    private val defaultSortParam: String,
    /** The host's Settings > Accent color choice, as a "#RRGGBB" string; used as the `--accent`
     *  CSS variable on the browse/watch pages, so a viewer's browser matches the host app's look. */
    private val accentColorHex: String = "#7C4DFF",
    /** The host's Settings > Skip interval, in seconds; how far `/remote`'s skip buttons jump. */
    private val skipSeconds: Int = 10,
    /** The host's Settings > Vibrate on control choice; when true, `/remote`'s control buttons
     *  buzz via the Web Vibration API (where the browser/WebView supports it) on each press. */
    private val vibrateOnControl: Boolean = true,
    /** Second stop of the host's Settings > Theme gradient ([accentColorHex] is the first);
     *  the pages render `--grad: linear-gradient(135deg, accent, accent-2)` from the pair. */
    private val accentColorEndHex: String = "#FF4FA3"
) : NanoWSD(port) {

    // Keyed by VideoEntry.id. An empty array means extraction was already tried and failed,
    // so a broken/DRM'd file isn't re-decoded on every thumbnail request.
    private val thumbnailCache = ConcurrentHashMap<Int, ByteArray>()

    /** Every currently-open `/remote/ws` connection, pushed the current remote state on every
     *  change instead of making each viewer poll `/remote/state` on a timer. */
    private val remoteSockets = CopyOnWriteArraySet<RemoteWebSocket>()

    /**
     * The current `/remote` pick and playback state. [revision] increments on every select so
     * polling clients (browse/watch pages) can tell a genuinely new command apart from the one
     * they already acted on, without needing to compare video IDs (the host might re-select the
     * same video, e.g. to restart it for a viewer who just joined). [playRevision]/[seekRevision]
     * work the same way for play/pause and seek commands, so a bare, control-less viewer page
     * (see [watchPage]'s `remote` mode) can apply each command exactly once instead of re-running
     * `player.play()`/`.currentTime()` on every poll.
     */
    private data class RemoteSelection(
        val videoId: Int? = null,
        val revision: Long = 0,
        val playing: Boolean = true,
        val playRevision: Long = 0,
        val seekSeconds: Double? = null,
        val seekRevision: Long = 0,
        /** Wall-clock bookkeeping so a viewer/control-panel connecting mid-playback can start
         *  at roughly the right spot instead of position 0 — see [currentPositionSeconds].
         *  Updated on every select/play/pause/seek; never broadcast as a seek command of its
         *  own, so it doesn't disrupt anyone already following along. */
        val positionBaseSeconds: Double = 0.0,
        val positionBaseAtMs: Long = System.currentTimeMillis()
    )
    private val remoteSelection = AtomicReference(RemoteSelection())

    /** Estimates where playback actually is right now by extrapolating from [RemoteSelection]'s
     *  last known anchor — used only to give a freshly-connecting client a sane starting
     *  position, not to drive already-connected followers (those stay in sync purely via
     *  [RemoteSelection.seekRevision]/[RemoteSelection.playRevision] diffs). */
    private fun currentPositionSeconds(state: RemoteSelection): Double {
        if (!state.playing) return state.positionBaseSeconds
        val elapsedSeconds = (System.currentTimeMillis() - state.positionBaseAtMs) / 1000.0
        return (state.positionBaseSeconds + elapsedSeconds).coerceAtLeast(0.0)
    }

    /** The video.js JS/CSS bundle, read once from assets and served from memory. */
    private val bundledAssets: Map<String, Pair<String, ByteArray>> by lazy {
        listOf(
            "videojs/video.min.js" to "application/javascript",
            "videojs/video-js.min.css" to "text/css"
        ).mapNotNull { (path, mime) ->
            try {
                path to (mime to assetManager.open(path).use { it.readBytes() })
            } catch (e: Exception) {
                null
            }
        }.toMap()
    }

    /**
     * Shared design tokens for every served page (dark cinematic + gradient look), so browse /
     * watch / remote all match the app. `--accent`/`--accent-2` are the host's Settings > Theme
     * gradient stops; `--grad` is the gradient itself, `--glass*` the translucent card surfaces.
     */
    private val themeTokensCss: String
        get() = """
            :root {
              --accent: $accentColorHex; --accent-2: $accentColorEndHex;
              --grad: linear-gradient(135deg, var(--accent), var(--accent-2));
              --bg: #0a0a12; --text: #f3f1fb; --muted: #a9a5be; --faint: #77738c;
              --glass: rgba(23, 23, 36, 0.72); --glass-strong: rgba(28, 28, 43, 0.9);
              --glass-hi: rgba(40, 40, 60, 0.85); --line: rgba(255, 255, 255, 0.10);
              --glow: 0 8px 30px -6px color-mix(in srgb, var(--accent) 60%, transparent);
              --radius-l: 28px; --radius-m: 22px;
            }
            * { box-sizing: border-box; }
            html { background: var(--bg); }
            body {
              background:
                radial-gradient(900px 520px at 0% -10%, color-mix(in srgb, var(--accent) 26%, transparent), transparent 70%),
                radial-gradient(760px 480px at 100% 0%, color-mix(in srgb, var(--accent-2) 20%, transparent), transparent 70%),
                var(--bg);
              background-attachment: fixed;
              color: var(--text);
              font-family: "Inter", -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
              -webkit-font-smoothing: antialiased;
            }
            .brand { display: flex; align-items: center; gap: 10px; margin: 4px 0 18px; color: var(--muted);
              font-size: 13px; font-weight: 700; letter-spacing: 0.12em; text-transform: uppercase; }
            .brand .logo { width: 34px; height: 34px; border-radius: 11px; background: var(--grad);
              display: flex; align-items: center; justify-content: center; color: #fff; box-shadow: var(--glow); }
            .brand .logo svg { width: 20px; height: 20px; }
        """

    private val brandHeaderHtml: String
        get() = "<header class=\"brand\"><span class=\"logo\">$playIconSvg</span>Local Video Stream</header>"

    // Small inline SVGs used across the browse/watch pages instead of emoji, so icons render
    // identically regardless of the viewing device's font/emoji set.
    private val folderIconSvg =
        "<svg width=\"16\" height=\"16\" viewBox=\"0 0 24 24\" fill=\"currentColor\"><path d=\"M10 4H4c-1.1 0-1.99.9-1.99 2L2 18c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V8c0-1.1-.9-2-2-2h-8l-2-2z\"/></svg>"
    private val chevronLeftIconSvg =
        "<svg width=\"20\" height=\"20\" viewBox=\"0 0 24 24\" fill=\"currentColor\"><path d=\"M15.41 7.41L14 6l-6 6 6 6 1.41-1.41L10.83 12z\"/></svg>"
    private val chevronRightIconSvg =
        "<svg width=\"20\" height=\"20\" viewBox=\"0 0 24 24\" fill=\"currentColor\"><path d=\"M10 6L8.59 7.41 13.17 12l-4.58 4.59L10 18l6-6z\"/></svg>"
    private val playBadgeIconSvg =
        "<svg width=\"36\" height=\"36\" viewBox=\"0 0 24 24\" fill=\"currentColor\"><path d=\"M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm-2 14.5v-9l6 4.5-6 4.5z\"/></svg>"
    private val playIconSvg =
        "<svg width=\"45\" height=\"45\" viewBox=\"0 0 24 24\" fill=\"currentColor\"><path d=\"M8 5v14l11-7z\"/></svg>"
    // Circular "replay"/"forward" arrows (the seconds count is overlaid as text in the button),
    // for the /remote skip-back / skip-forward controls.
    private val skipBackIconSvg =
        "<svg viewBox=\"0 0 24 24\" fill=\"currentColor\"><path d=\"M12 5V1L7 6l5 5V7c3.31 0 6 2.69 6 6s-2.69 6-6 6-6-2.69-6-6H4c0 4.42 3.58 8 8 8s8-3.58 8-8-3.58-8-8-8z\"/></svg>"
    private val skipForwardIconSvg =
        "<svg viewBox=\"0 0 24 24\" fill=\"currentColor\"><path d=\"M12 5V1l5 5-5 5V7c-3.31 0-6 2.69-6 6s2.69 6 6 6 6-2.69 6-6h2c0 4.42-3.58 8-8 8s-8-3.58-8-8 3.58-8 8-8z\"/></svg>"
    private val speakerMutedIconSvg =
        "<svg width=\"20\" height=\"20\" viewBox=\"0 0 24 24\" fill=\"currentColor\"><path d=\"M16.5 12c0-1.77-1.02-3.29-2.5-4.03v2.21l2.45 2.45c.03-.2.05-.41.05-.63zm2.5 0c0 .94-.2 1.82-.54 2.64l1.51 1.51C20.63 14.91 21 13.5 21 12c0-4.28-2.99-7.86-7-8.77v2.06c2.89.86 5 3.54 5 6.71zM4.27 3L3 4.27 7.73 9H3v6h4l5 5v-6.73l4.25 4.25c-.67.52-1.42.93-2.25 1.18v2.06c1.38-.31 2.63-.95 3.69-1.81L19.73 21 21 19.73 12 10.73 4.27 3zM12 4L9.91 6.09 12 8.18z\"/></svg>"
    private val exitIconSvg =
        "<svg width=\"24\" height=\"24\" viewBox=\"0 0 24 24\" fill=\"currentColor\"><path d=\"M17 7l-1.41 1.41L18.17 11H8v2h10.17l-2.58 2.58L17 17l5-5zM4 5h8V3H4c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h8v-2H4z\"/></svg>"
    private val autoPlayIconSvg =
        "<svg width=\"20\" height=\"20\" viewBox=\"0 0 24 24\" fill=\"currentColor\"><path d=\"M21 3H3c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h18c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2zM9.5 16.5v-9l7 4.5-7 4.5z\"/></svg>"
    private val randomPlayIconSvg =
        "<svg width=\"20\" height=\"20\" viewBox=\"0 0 24 24\" fill=\"currentColor\"><path d=\"M10.59 9.17L5.41 4 4 5.41l5.17 5.17 1.42-1.41zM14.5 4l2.04 2.04L4 18.59 5.41 20 17.96 7.46 20 9.5V4h-5.5zm.33 9.41l-1.41 1.41 3.13 3.13L14.5 20H20v-5.5l-2.04 2.04-3.13-3.13z\"/></svg>"

    /** Closes the underlying [ParcelFileDescriptor] together with the stream view over it. */
    private class ClosingFileInputStream(private val pfd: ParcelFileDescriptor) :
        FileInputStream(pfd.fileDescriptor) {
        override fun close() {
            try {
                super.close()
            } finally {
                pfd.close()
            }
        }
    }

    /** How the videos in [browsePage] are ordered; switched via the `sort` query param. */
    private enum class SortMode(val param: String, val label: String) {
        NAME("name", "Name"),
        DATE("date", "Newest"),
        SIZE("size", "Largest");

        companion object {
            fun fromParam(value: String?): SortMode = values().firstOrNull { it.param == value } ?: NAME
        }
    }

    override fun serveHttp(session: IHTTPSession): Response {
        if (session.uri.startsWith("/assets/")) {
            return serveAsset(session.uri.removePrefix("/assets/"))
        }
        return when (session.uri) {
            "/", "/index.html" -> serveIndex()
            "/browse" -> browsePage(
                session.parameters["path"]?.firstOrNull().orEmpty(),
                SortMode.fromParam(session.parameters["sort"]?.firstOrNull() ?: defaultSortParam),
                session.parameters["flat"]?.firstOrNull() == "1"
            )
            "/watch" -> serveWatch(session)
            "/video" -> serveVideo(session)
            "/thumbnail" -> serveThumbnail(session)
            "/remote" -> remotePage(
                session.parameters["path"]?.firstOrNull().orEmpty(),
                SortMode.fromParam(session.parameters["sort"]?.firstOrNull() ?: defaultSortParam),
                session.parameters["flat"]?.firstOrNull() == "1"
            )
            "/remote/state" -> remoteStateJson()
            "/remote/select" -> handleRemoteSelect(session)
            "/remote/command" -> handleRemoteCommand(session)
            "/remote/clear" -> handleRemoteClear()
            "/api/info" -> apiInfoJson()
            "/api/browse" -> apiBrowseJson(
                session.parameters["path"]?.firstOrNull().orEmpty(),
                SortMode.fromParam(session.parameters["sort"]?.firstOrNull() ?: defaultSortParam),
                session.parameters["flat"]?.firstOrNull() == "1"
            )
            "/api/video" -> apiVideoJson(session)
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
        }
    }

    /**
     * The only WebSocket endpoint this server has, so every upgrade request (NanoWSD routes
     * these here regardless of URI) becomes a [RemoteWebSocket] pushing `/remote` state — see
     * [broadcastRemoteState].
     */
    override fun openWebSocket(handshake: IHTTPSession): WebSocket = RemoteWebSocket(handshake)

    /** Pushes the current remote state to [handshake] the instant it connects, and again on every
     *  later change via [broadcastRemoteState] — replaces the `/remote/state` polling loop that
     *  every `/browse`/`/watch`/remote-follow/[remotePage] page used to run on a timer. */
    private inner class RemoteWebSocket(handshake: IHTTPSession) : WebSocket(handshake) {
        override fun onOpen() {
            remoteSockets.add(this)
            trySend(remoteStateJsonString())
        }

        override fun onClose(code: WebSocketFrame.CloseCode?, reason: String?, initiatedByRemote: Boolean) {
            remoteSockets.remove(this)
        }

        override fun onMessage(message: WebSocketFrame) {
            // Purely a push channel — clients never send anything meaningful over it.
        }

        override fun onPong(pong: WebSocketFrame) {
        }

        override fun onException(exception: IOException) {
            remoteSockets.remove(this)
        }

        fun trySend(json: String): Boolean = try {
            send(json)
            true
        } catch (e: IOException) {
            remoteSockets.remove(this)
            false
        }
    }

    /** Called after every `/remote/select`, `/remote/command`, and `/remote/clear` mutation so
     *  every connected viewer/control-panel updates within milliseconds instead of waiting out a
     *  poll interval. */
    private fun broadcastRemoteState() {
        val json = remoteStateJsonString()
        for (socket in remoteSockets) {
            socket.trySend(json)
        }
    }

    private fun serveAsset(assetPath: String): Response {
        val (mimeType, bytes) = bundledAssets[assetPath]
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
        return newFixedLengthResponse(Response.Status.OK, mimeType, ByteArrayInputStream(bytes), bytes.size.toLong())
    }

    private fun entryFor(session: IHTTPSession): VideoEntry? {
        val id = session.parameters["id"]?.firstOrNull()?.toIntOrNull() ?: return null
        return entries.firstOrNull { it.id == id }
    }

    private fun serveIndex(): Response {
        if (!isFolderMode) {
            val single = entries.singleOrNull()
                ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "No video available")
            return watchPage(single, SortMode.NAME, flat = false, remote = false)
        }
        // flat=false here is just the request's own starting point — browsePage's inline script
        // immediately redirects to the viewer's remembered preference if it differs.
        return browsePage("", SortMode.fromParam(defaultSortParam), flat = false)
    }

    private fun serveWatch(session: IHTTPSession): Response {
        val entry = entryFor(session)
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Video not found")
        val sortMode = SortMode.fromParam(session.parameters["sort"]?.firstOrNull() ?: defaultSortParam)
        val flat = session.parameters["flat"]?.firstOrNull() == "1"
        // /remote's control panel drives this viewer entirely — no local controls of its own.
        val remote = session.parameters["remote"]?.firstOrNull() == "1"
        return watchPage(entry, sortMode, flat, remote)
    }

    private fun sortVideos(videos: List<VideoEntry>, sortMode: SortMode): List<VideoEntry> = when (sortMode) {
        SortMode.NAME -> videos.sortedBy { it.name.lowercase() }
        SortMode.DATE -> videos.sortedByDescending { it.lastModified }
        SortMode.SIZE -> videos.sortedByDescending { it.sizeBytes }
    }

    /** Shared by [browsePage] and [remotePage] — the videos/subfolders directly inside [path]. */
    private data class FolderListing(
        val effectivePath: String,
        val title: String,
        val videos: List<VideoEntry>,
        val subfolders: List<String>
    )

    /**
     * Computes what [browsePage]/[remotePage] should show for [path]. When [flat] is on, [path]
     * is ignored entirely and every video in the library is returned together. Returns null if
     * there's nothing at [path] at all (a 404 either way).
     */
    private fun computeListing(path: String, flat: Boolean, sortMode: SortMode): FolderListing? {
        val effectivePath = if (flat) "" else path
        val prefix = if (effectivePath.isEmpty()) "" else "$effectivePath/"
        val videos = if (flat) {
            sortVideos(entries, sortMode)
        } else {
            sortVideos(entries.filter { it.folderPath == effectivePath }, sortMode)
        }
        val subfolders = if (flat) {
            emptyList()
        } else {
            entries
                .filter { it.folderPath != effectivePath && it.folderPath.startsWith(prefix) }
                .map { it.folderPath.removePrefix(prefix).substringBefore('/') }
                .distinct()
                .sorted()
        }
        if (videos.isEmpty() && subfolders.isEmpty()) return null
        val title = if (effectivePath.isEmpty()) libraryName else effectivePath.substringAfterLast('/')
        return FolderListing(effectivePath, title, videos, subfolders)
    }

    /**
     * Guesses a video's MIME type from its file extension, for the `<source type>` hint
     * video.js uses to pick a playback source — the actual `/video` response's real
     * `Content-Type` header (from [ContentResolver.getType]) is what ultimately governs
     * playback, this is just advisory since `/video?id=…` URLs have no extension of their own.
     */
    private fun guessVideoMimeType(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "mp4", "m4v" -> "video/mp4"
        "webm" -> "video/webm"
        "mkv" -> "video/x-matroska"
        "mov" -> "video/quicktime"
        "avi" -> "video/x-msvideo"
        "3gp" -> "video/3gpp"
        "ts" -> "video/mp2t"
        "flv" -> "video/x-flv"
        "wmv" -> "video/x-ms-wmv"
        "mpg", "mpeg" -> "video/mpeg"
        else -> "video/mp4"
    }

    /**
     * Tells a native client (the app's own [WatchActivity], or any other JSON-speaking client)
     * what it's connecting to, before it decides whether to show a folder browser or go
     * straight to a single video.
     */
    private fun apiInfoJson(): Response {
        val json = "{" +
            "\"libraryName\":${jsonString(libraryName)}," +
            "\"isFolderMode\":$isFolderMode," +
            "\"defaultSort\":${jsonString(defaultSortParam)}" +
            "}"
        return newFixedLengthResponse(Response.Status.OK, "application/json", json)
    }

    /**
     * The JSON equivalent of [browsePage] — what a native client needs to render the same
     * folder/video listing itself, instead of parsing server-rendered HTML. Same [computeListing]
     * backing, same 404-if-nothing-here behavior.
     */
    private fun apiBrowseJson(path: String, sortMode: SortMode, flat: Boolean): Response {
        val listing = computeListing(path, flat, sortMode)
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json", "{\"error\":\"Folder not found\"}")
        val videosJson = listing.videos.joinToString(",") { entry ->
            "{" +
                "\"id\":${entry.id}," +
                "\"name\":${jsonString(entry.name)}," +
                "\"folderPath\":${jsonString(entry.folderPath)}," +
                "\"type\":${jsonString(guessVideoMimeType(entry.name))}," +
                "\"sizeBytes\":${entry.sizeBytes}," +
                "\"lastModified\":${entry.lastModified}" +
                "}"
        }
        val subfoldersJson = listing.subfolders.joinToString(",") { jsonString(it) }
        val json = "{" +
            "\"effectivePath\":${jsonString(listing.effectivePath)}," +
            "\"title\":${jsonString(listing.title)}," +
            "\"videos\":[$videosJson]," +
            "\"subfolders\":[$subfoldersJson]" +
            "}"
        val response = newFixedLengthResponse(Response.Status.OK, "application/json", json)
        response.addHeader("Cache-Control", "no-store")
        return response
    }

    /**
     * Metadata for a single video by id, regardless of what folder it's in — what a native
     * client needs to show a title/type for a video it hasn't browsed to itself, e.g. one
     * picked by someone else on `/remote` that it's now following.
     */
    private fun apiVideoJson(session: IHTTPSession): Response {
        val entry = entryFor(session)
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json", "{\"error\":\"Video not found\"}")
        val json = "{" +
            "\"id\":${entry.id}," +
            "\"name\":${jsonString(entry.name)}," +
            "\"folderPath\":${jsonString(entry.folderPath)}," +
            "\"type\":${jsonString(guessVideoMimeType(entry.name))}," +
            "\"sizeBytes\":${entry.sizeBytes}," +
            "\"lastModified\":${entry.lastModified}" +
            "}"
        return newFixedLengthResponse(Response.Status.OK, "application/json", json)
    }

    /**
     * Renders the videos and immediate subfolders that live directly inside [path]. When
     * [flat] is on, [path] is ignored entirely and every video in the library is listed
     * together, with its original folder shown as a subtitle for context. [flat] reflects
     * only this one request; the page's own script is what keeps it in sync with the
     * viewer's remembered `localStorage` preference across navigation.
     */
    private fun browsePage(path: String, sortMode: SortMode, flat: Boolean): Response {
        val listing = computeListing(path, flat, sortMode)
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Folder not found")
        val (effectivePath, title, videos, subfolders) = listing
        val backLink = if (effectivePath.isNotEmpty()) {
            val parentPath = effectivePath.substringBeforeLast('/', "")
            "<li><a class=\"back\" href=\"/browse?path=${encodePath(parentPath)}&sort=${sortMode.param}\">$chevronLeftIconSvg ..</a></li>"
        } else {
            ""
        }
        val folderItems = subfolders.joinToString("\n") { folderName ->
            val childPath = if (effectivePath.isEmpty()) folderName else "$effectivePath/$folderName"
            "<li><a href=\"/browse?path=${encodePath(childPath)}&sort=${sortMode.param}\">$folderIconSvg ${escapeHtml(folderName)}</a></li>"
        }
        val videoItems = videos.joinToString("\n") { entry ->
            val subtitle = if (flat && entry.folderPath.isNotEmpty()) {
                "<small>${escapeHtml(entry.folderPath)}</small>"
            } else {
                ""
            }
            """
            <li>
              <a href="/watch?id=${entry.id}&sort=${sortMode.param}&flat=${if (flat) "1" else "0"}">
                <span class="thumb">
                  <img src="/thumbnail?id=${entry.id}" loading="lazy" alt="">
                  <span class="play-badge">$playBadgeIconSvg</span>
                </span>
                <span class="title">${escapeHtml(entry.name)}</span>
                $subtitle
              </a>
            </li>
            """.trimIndent()
        }
        val sortLinks = SortMode.values().joinToString(" ") { mode ->
            if (mode == sortMode) {
                "<span class=\"active\">${mode.label}</span>"
            } else {
                "<a href=\"/browse?path=${encodePath(effectivePath)}&sort=${mode.param}&flat=${if (flat) "1" else "0"}\">${mode.label}</a>"
            }
        }
        val sortBar = if (videos.size > 1) {
            "<div class=\"bar\"><span class=\"label\">Sort:</span> $sortLinks</div>"
        } else {
            ""
        }
        // Only worth offering when the library actually has subfolders — otherwise the two
        // modes would look identical.
        val hasSubfolderSomewhere = entries.any { it.folderPath.isNotEmpty() }
        val viewBar = if (hasSubfolderSomewhere) {
            val foldersOption = if (!flat) {
                "<span class=\"active\">Folders</span>"
            } else {
                "<a href=\"/browse?path=${encodePath("")}&sort=${sortMode.param}&flat=0\">Folders</a>"
            }
            val flatOption = if (flat) {
                "<span class=\"active\">All videos</span>"
            } else {
                "<a href=\"/browse?path=${encodePath("")}&sort=${sortMode.param}&flat=1\">All videos</a>"
            }
            "<div class=\"bar\"><span class=\"label\">View:</span> $foldersOption $flatOption</div>"
        } else {
            ""
        }

        val html = """
            <!DOCTYPE html>
            <html>
            <head>
              <script>
              // Runs before anything renders: if this device has a remembered flat/folder
              // preference that the URL doesn't already reflect, jump straight to it instead
              // of showing the "wrong" layout for a moment.
              (function () {
                var params = new URLSearchParams(location.search);
                if (params.get('flat') === null) {
                  if (localStorage.getItem('flatView') === '1') {
                    params.set('flat', '1');
                    location.replace(location.pathname + '?' + params.toString());
                  }
                } else {
                  localStorage.setItem('flatView', params.get('flat'));
                }
              })();
              </script>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <title>${escapeHtml(title)}</title>
              <style>
                $themeTokensCss
                body {
                  margin: 0 auto; max-width: 1180px; min-height: 100vh;
                  padding: max(22px, env(safe-area-inset-top)) clamp(16px, 4vw, 44px) 48px;
                }
                h1 {
                  font-size: clamp(28px, 6vw, 44px); font-weight: 800;
                  margin: 0 0 22px; letter-spacing: -0.03em; line-height: 1.05;
                }
                .bar { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; margin: 0 0 20px; font-size: 14px; }
                .bar .label { color: var(--faint); margin-right: 4px; font-weight: 600; }
                .bar a, .bar .active {
                  padding: 10px 18px; border-radius: 999px; text-decoration: none; font-size: 14px;
                  border: 1px solid var(--line); transition: background 0.2s ease, transform 0.2s ease;
                }
                .bar a { color: var(--muted); background: var(--glass); backdrop-filter: blur(14px); }
                .bar a:hover { background: var(--glass-hi); color: var(--text); transform: translateY(-1px); }
                .bar .active { background: var(--grad); color: #fff; font-weight: 700; border-color: transparent; box-shadow: var(--glow); }
                ul.folders { list-style: none; padding: 0; margin: 0 0 24px; display: grid; gap: 12px;
                  grid-template-columns: repeat(auto-fill, minmax(min(100%, 260px), 1fr)); }
                ul.folders li { margin: 0; }
                ul.folders a {
                  display: flex; align-items: center; gap: 14px; padding: 16px 18px;
                  background: var(--glass); backdrop-filter: blur(14px); border: 1px solid var(--line);
                  color: var(--text); text-decoration: none; border-radius: var(--radius-m);
                  font-size: 15px; font-weight: 600; transition: background 0.2s ease, transform 0.2s ease;
                }
                ul.folders a svg { flex-shrink: 0; width: 22px; height: 22px; color: var(--accent-2); }
                ul.folders a:hover { background: var(--glass-hi); transform: translateY(-2px); }
                ul.folders a.back { color: var(--muted); }
                ul.videos {
                  list-style: none; padding: 0; margin: 0; display: grid;
                  grid-template-columns: repeat(auto-fill, minmax(min(100%, 210px), 1fr)); gap: 18px;
                }
                ul.videos a {
                  display: flex; flex-direction: column; color: var(--text); text-decoration: none;
                  background: var(--glass); backdrop-filter: blur(14px); border: 1px solid var(--line);
                  border-radius: var(--radius-m); overflow: hidden; padding: 8px;
                  transition: transform 0.25s ease, box-shadow 0.25s ease, border-color 0.25s ease;
                }
                ul.videos a:hover, ul.videos a:focus-visible {
                  transform: translateY(-4px); border-color: color-mix(in srgb, var(--accent-2) 55%, transparent);
                  box-shadow: 0 18px 40px -12px rgba(0, 0, 0, 0.7), var(--glow); outline: none;
                }
                ul.videos .thumb { display: block; position: relative; background: #000; border-radius: 16px; overflow: hidden; }
                ul.videos .thumb img { display: block; width: 100%; aspect-ratio: 16 / 9; object-fit: cover; transition: transform 0.4s ease; }
                ul.videos a:hover .thumb img { transform: scale(1.05); }
                ul.videos .thumb::after { content: ""; position: absolute; inset: 0;
                  background: linear-gradient(to top, rgba(0, 0, 0, 0.55), transparent 55%); }
                ul.videos .play-badge {
                  position: absolute; right: 10px; bottom: 10px; z-index: 1; display: flex;
                  width: 38px; height: 38px; border-radius: 50%; background: var(--grad); color: #fff;
                  align-items: center; justify-content: center; box-shadow: var(--glow);
                  transition: transform 0.25s ease;
                }
                ul.videos .play-badge svg { width: 24px; height: 24px; }
                ul.videos a:hover .play-badge { transform: scale(1.12); }
                ul.videos .title { display: block; padding: 12px 6px 4px; font-size: 14px; font-weight: 600; word-break: break-word; line-height: 1.35; }
                ul.videos small { display: block; padding: 0 6px 8px; color: var(--accent-2); font-size: 12px; font-weight: 600; word-break: break-word; }
                ul.videos a .title:last-child { padding-bottom: 8px; }
              </style>
            </head>
            <body>
              $brandHeaderHtml
              <h1>${escapeHtml(title)}</h1>
              $viewBar
              <ul class="folders">
                $backLink
                $folderItems
              </ul>
              $sortBar
              <ul class="videos">
                $videoItems
              </ul>
              <script>
              // Hands off to the bare, control-less player as soon as someone picks a video from
              // /remote — a no-op until the first /remote/select ever happens. Once the remote's
              // been used, browsing here would just get interrupted anyway, so this jumps
              // straight to the page /remote is actually driving. A /remote/ws push (instead of
              // polling /remote/state) means this happens within milliseconds of the pick.
              (function () {
                function applyState(state) {
                  if (state.videoId !== null) {
                    location.href = '/watch?id=' + state.videoId + '&remote=1';
                  }
                }
                function connect() {
                  var proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
                  var ws = new WebSocket(proto + '//' + location.host + '/remote/ws');
                  ws.onmessage = function (e) {
                    try { applyState(JSON.parse(e.data)); } catch (err) {}
                  };
                  ws.onclose = function () { setTimeout(connect, 1000); };
                  ws.onerror = function () { ws.close(); };
                }
                connect();
              })();
              </script>
            </body>
            </html>
        """.trimIndent()
        val response = newFixedLengthResponse(Response.Status.OK, "text/html", html)
        // Without this, a browser/WebView can serve a cached copy of this page back on
        // location.reload() (most visibly on /remote right after selecting a video, or on the
        // bare remote-follow player right after a video switch) instead of actually re-fetching
        // the now-current state from the server — this page always reflects live state, so it
        // must never be served stale.
        response.addHeader("Cache-Control", "no-store")
        return response
    }

    private fun watchPage(entry: VideoEntry, sortMode: SortMode, flat: Boolean, remote: Boolean): Response {
        if (remote) return bareRemotePlayerPage(entry)

        // The playlist is the other videos alongside this one — the same folder, or the whole
        // library when viewing flat — same scope the browse page would show, in the same sort
        // order.
        val siblings = when {
            !isFolderMode -> listOf(entry)
            flat -> sortVideos(entries, sortMode)
            else -> sortVideos(entries.filter { it.folderPath == entry.folderPath }, sortMode)
        }
        val showPlaylist = isFolderMode && siblings.size > 1

        val backLink = if (isFolderMode) {
            val backPath = if (flat) "" else entry.folderPath
            "<a class=\"back\" href=\"/browse?path=${encodePath(backPath)}&sort=${sortMode.param}&flat=${if (flat) "1" else "0"}\">$chevronLeftIconSvg Back</a>"
        } else {
            ""
        }
        val playlistItems = siblings.joinToString("\n") { item ->
            val activeClass = if (item.id == entry.id) " class=\"active\"" else ""
            """
            <li$activeClass data-id="${item.id}">
              <img src="/thumbnail?id=${item.id}" loading="lazy" alt="">
              <span>${escapeHtml(item.name)}</span>
            </li>
            """.trimIndent()
        }
        val playlistJson = siblings.joinToString(",") { item ->
            "{\"id\":${item.id},\"name\":${jsonString(item.name)},\"type\":${jsonString(guessVideoMimeType(item.name))}}"
        }
        val controls = if (showPlaylist) {
            """
            <div class="controls">
              <label class="toggle"><input type="checkbox" id="autoplayToggle"> Autoplay</label>
              <label class="toggle"><input type="checkbox" id="shuffleToggle"> Shuffle</label>
            </div>
            """.trimIndent()
        } else {
            ""
        }
        // Prev/Next are inserted into video.js's own control bar (next to the play button)
        // via JS after the player initializes, rather than rendered as static HTML here —
        // see navButtonsScript below.
        val navButtonsScript = if (showPlaylist) {
            """
                var controlBar = player.controlBar && player.controlBar.el();
                var playToggleEl = player.controlBar &&
                  player.controlBar.getChild('playToggle') &&
                  player.controlBar.getChild('playToggle').el();
                if (controlBar && playToggleEl) {
                  var makeNavButton = function (id, symbol, label) {
                    var btn = document.createElement('button');
                    btn.type = 'button';
                    btn.id = id;
                    btn.className = 'vjs-control vjs-button vjs-nav-button';
                    btn.title = label;
                    btn.setAttribute('aria-label', label);
                    var span = document.createElement('span');
                    span.className = 'vjs-nav-icon';
                    span.setAttribute('aria-hidden', 'true');
                    span.textContent = symbol;
                    btn.appendChild(span);
                    return btn;
                  };
                  prevBtn = makeNavButton('prevBtn', '‹', 'Previous video');
                  nextBtn = makeNavButton('nextBtn', '›', 'Next video');
                  controlBar.insertBefore(prevBtn, playToggleEl);
                  controlBar.insertBefore(nextBtn, playToggleEl.nextSibling);
                }
            """.trimIndent()
        } else {
            ""
        }
        // A remote pick hands off entirely to the bare, control-less player — this page's own
        // controls/playlist stop being relevant the moment /remote is used. This applies in
        // single-file mode too: /remote can now drive a lone video's playback for everyone, so a
        // single-file viewer has to follow along the same way a folder viewer does.
        val remoteFollowScript = """
                (function connectRemoteFollow() {
                  function applyState(state) {
                    if (state.videoId !== null) {
                      location.href = '/watch?id=' + state.videoId + '&remote=1';
                    }
                  }
                  function connect() {
                    var proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
                    var ws = new WebSocket(proto + '//' + location.host + '/remote/ws');
                    ws.onmessage = function (e) {
                      try { applyState(JSON.parse(e.data)); } catch (err) {}
                    };
                    ws.onclose = function () { setTimeout(connect, 1000); };
                    ws.onerror = function () { ws.close(); };
                  }
                  connect();
                })();
            """.trimIndent()

        val html = """
            <!DOCTYPE html>
            <html>
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <title>${escapeHtml(entry.name)}</title>
              <link href="/assets/videojs/video-js.min.css" rel="stylesheet">
              <style>
                $themeTokensCss
                html, body { margin: 0; height: 100%; }
                body { display: flex; flex-direction: column; }
                .topbar {
                  display: flex; align-items: center; gap: 12px; flex-shrink: 0; flex-wrap: wrap;
                  padding: max(12px, env(safe-area-inset-top)) clamp(12px, 3vw, 24px) 12px;
                  background: var(--glass); backdrop-filter: blur(18px); border-bottom: 1px solid var(--line);
                }
                .back {
                  display: flex; align-items: center; gap: 4px; color: var(--text); text-decoration: none;
                  flex-shrink: 0; padding: 9px 16px 9px 10px; border-radius: 999px; font-weight: 600;
                  background: var(--glass-hi); border: 1px solid var(--line); transition: transform 0.2s ease;
                }
                .back:hover { transform: translateX(-2px); }
                #currentTitle { font-size: 16px; font-weight: 700; color: var(--text); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
                .controls { display: flex; gap: 8px; align-items: center; margin-left: auto; }
                .toggle {
                  display: flex; align-items: center; gap: 6px; padding: 9px 16px; border-radius: 999px;
                  background: var(--glass-hi); border: 1px solid var(--line); font-size: 13px; font-weight: 600;
                  color: var(--muted); white-space: nowrap; cursor: pointer; transition: all 0.2s ease;
                }
                .toggle:has(input:checked) { background: var(--grad); color: #fff; border-color: transparent; box-shadow: var(--glow); }
                .main { flex: 1; display: flex; min-height: 0; }
                .player { flex: 1; background: #000; min-width: 0; }
                .video-js { width: 100%; height: 100%; }
                .video-js .vjs-tech { object-fit: contain; }
                /* Re-skins video.js's default box skin with the brand gradient + glass. */
                .video-js .vjs-big-play-button {
                  width: 84px; height: 84px; line-height: 84px; margin: -42px 0 0 -42px;
                  font-size: 34px; border: none; border-radius: 50%;
                  background: var(--grad); box-shadow: var(--glow); transition: transform 0.2s ease;
                }
                .video-js:hover .vjs-big-play-button,
                .video-js .vjs-big-play-button:focus,
                .video-js .vjs-big-play-button:hover { border: none; background: var(--grad); transform: scale(1.08); }
                .video-js .vjs-control-bar {
                  height: 3.6em; background: linear-gradient(to top, rgba(0, 0, 0, 0.85), rgba(0, 0, 0, 0.25));
                }
                .video-js .vjs-slider { background-color: rgba(255, 255, 255, 0.18); border-radius: 999px; }
                .video-js .vjs-play-progress, .video-js .vjs-volume-level { background: var(--grad); border-radius: 999px; }
                .video-js .vjs-load-progress div { background: rgba(255, 255, 255, 0.22); }
                .video-js .vjs-control:focus:before,
                .video-js .vjs-control:hover:before { text-shadow: 0 0 1em var(--accent-2); }
                .video-js .vjs-menu-content { background-color: var(--glass-strong); border-radius: 14px; overflow: hidden; }
                .video-js .vjs-menu-item:hover,
                .video-js .vjs-menu li.vjs-menu-item:focus,
                .video-js .vjs-menu li.vjs-menu-item:hover { background-color: rgba(255, 255, 255, 0.1); }
                .video-js .vjs-selected:hover,
                .video-js .vjs-menu li.vjs-selected,
                .video-js .vjs-menu li.vjs-selected:focus,
                .video-js .vjs-menu li.vjs-selected:hover { background: var(--grad); color: #fff; }
                /* Prev/Next live inside video.js's own control bar (see navButtonsScript). */
                .video-js .vjs-nav-button { display: flex; align-items: center; justify-content: center; cursor: pointer; }
                .video-js .vjs-nav-icon { font-size: 1.8em; line-height: 1; color: #fff; }
                .video-js .vjs-nav-button:hover .vjs-nav-icon,
                .video-js .vjs-nav-button:focus .vjs-nav-icon { color: var(--accent-2); }
                .video-js .vjs-nav-button:disabled { cursor: default; opacity: 0.35; }
                .playlist {
                  width: 320px; flex-shrink: 0; overflow-y: auto; list-style: none; margin: 0; padding: 10px;
                  background: var(--glass); backdrop-filter: blur(18px); border-left: 1px solid var(--line);
                }
                .playlist li {
                  display: flex; gap: 12px; align-items: center; padding: 8px; cursor: pointer;
                  border-radius: 16px; margin: 4px 0; border: 1px solid transparent; transition: background 0.2s ease;
                }
                .playlist li:hover { background: var(--glass-hi); }
                .playlist li.active {
                  background: var(--glass-hi); border-color: color-mix(in srgb, var(--accent-2) 55%, transparent);
                  box-shadow: var(--glow);
                }
                .playlist img { width: 92px; aspect-ratio: 16 / 9; object-fit: cover; background: #000; border-radius: 12px; flex-shrink: 0; }
                .playlist span { font-size: 13px; font-weight: 600; word-break: break-word; }
                @media (max-width: 760px) {
                  .main { flex-direction: column; }
                  .playlist { width: 100%; max-height: 40vh; border-left: none; border-top: 1px solid var(--line); }
                }
              </style>
            </head>
            <body>
              <div class="topbar">
                $backLink
                <span id="currentTitle">${escapeHtml(entry.name)}</span>
                $controls
              </div>
              <div class="main">
                <div class="player">
                  <video id="player" class="video-js vjs-big-play-centered" controls preload="auto" poster="/thumbnail?id=${entry.id}">
                    <source src="/video?id=${entry.id}" type="${guessVideoMimeType(entry.name)}">
                  </video>
                </div>
                ${if (showPlaylist) "<ul class=\"playlist\" id=\"playlist\">$playlistItems</ul>" else ""}
              </div>
              <script src="/assets/videojs/video.min.js"></script>
              <script>
              (function () {
                var playlist = [$playlistJson];
                var currentId = ${entry.id};
                var player = videojs('player', { autoplay: true });
                var titleEl = document.getElementById('currentTitle');
                var listEl = document.getElementById('playlist');
                var autoplayCheckbox = document.getElementById('autoplayToggle');
                var shuffleCheckbox = document.getElementById('shuffleToggle');
                var prevBtn = null;
                var nextBtn = null;
                var shuffleQueue = [];
                var playHistory = [];

                function loadPref(key, defaultValue) {
                  var v = localStorage.getItem(key);
                  return v === null ? defaultValue : v === '1';
                }
                function savePref(key, value) {
                  localStorage.setItem(key, value ? '1' : '0');
                }

                var autoplayNext = loadPref('autoplayNext', true);
                var shuffleMode = loadPref('shuffleMode', false);
                if (autoplayCheckbox) {
                  autoplayCheckbox.checked = autoplayNext;
                  autoplayCheckbox.addEventListener('change', function () {
                    autoplayNext = autoplayCheckbox.checked;
                    savePref('autoplayNext', autoplayNext);
                  });
                }
                if (shuffleCheckbox) {
                  shuffleCheckbox.checked = shuffleMode;
                  shuffleCheckbox.addEventListener('change', function () {
                    shuffleMode = shuffleCheckbox.checked;
                    savePref('shuffleMode', shuffleMode);
                    shuffleQueue = [];
                    updateNavButtons();
                  });
                }

                function indexOf(id) {
                  for (var i = 0; i < playlist.length; i++) {
                    if (playlist[i].id === id) return i;
                  }
                  return -1;
                }

                // A shuffle bag: play through every other video once, in random order, before
                // any repeat, instead of picking independently at random each time (which can
                // repeat the same video several times before covering the rest).
                function refillShuffleQueue(excludeId) {
                  var ids = [];
                  for (var i = 0; i < playlist.length; i++) {
                    if (playlist[i].id !== excludeId) ids.push(playlist[i].id);
                  }
                  for (var i = ids.length - 1; i > 0; i--) {
                    var j = Math.floor(Math.random() * (i + 1));
                    var tmp = ids[i]; ids[i] = ids[j]; ids[j] = tmp;
                  }
                  shuffleQueue = ids;
                }

                function nextIdForAutoplay() {
                  if (shuffleMode) {
                    if (shuffleQueue.length === 0) refillShuffleQueue(currentId);
                    return shuffleQueue.length > 0 ? shuffleQueue.shift() : null;
                  }
                  var idx = indexOf(currentId);
                  return (idx >= 0 && idx + 1 < playlist.length) ? playlist[idx + 1].id : null;
                }

                // trackHistory records the video we're leaving so the Prev button can retrace
                // actual play order (including shuffle jumps); back/forward navigation and the
                // Prev button itself pass false so they don't create their own history entries.
                function playItem(id, pushHistory, trackHistory) {
                  var item = playlist[indexOf(id)];
                  if (!item) return;
                  if (trackHistory !== false && currentId !== id) playHistory.push(currentId);
                  currentId = id;
                  player.poster('/thumbnail?id=' + id);
                  player.src({ src: '/video?id=' + id, type: item.type });
                  player.play().catch(function () {});
                  titleEl.textContent = item.name;
                  document.title = item.name;
                  if (pushHistory !== false && window.history && window.history.pushState) {
                    window.history.pushState(
                      { id: id },
                      '',
                      '/watch?id=' + id + '&sort=${sortMode.param}&flat=${if (flat) "1" else "0"}'
                    );
                  }
                  if (listEl) {
                    var nodes = listEl.querySelectorAll('li');
                    for (var j = 0; j < nodes.length; j++) {
                      var match = parseInt(nodes[j].getAttribute('data-id'), 10) === id;
                      nodes[j].classList.toggle('active', match);
                    }
                  }
                  updateNavButtons();
                }

                function updateNavButtons() {
                  var idx = indexOf(currentId);
                  if (prevBtn) prevBtn.disabled = playHistory.length === 0 && idx <= 0;
                  if (nextBtn) nextBtn.disabled = !shuffleMode && (idx < 0 || idx >= playlist.length - 1);
                }

                function goNext() {
                  var nextId = nextIdForAutoplay();
                  if (nextId !== null) playItem(nextId, true, true);
                }

                function goPrev() {
                  if (playHistory.length > 0) {
                    playItem(playHistory.pop(), true, false);
                    return;
                  }
                  var idx = indexOf(currentId);
                  if (idx > 0) playItem(playlist[idx - 1].id, true, false);
                }

                $navButtonsScript
                if (prevBtn) prevBtn.addEventListener('click', goPrev);
                if (nextBtn) nextBtn.addEventListener('click', goNext);

                if (listEl) {
                  listEl.addEventListener('click', function (e) {
                    var li = e.target.closest('li');
                    if (!li) return;
                    var id = parseInt(li.getAttribute('data-id'), 10);
                    if (id !== currentId) playItem(id, true, true);
                  });
                }

                player.on('ended', function () {
                  if (!autoplayNext) return;
                  goNext();
                });

                updateNavButtons();

                // Keeps the player in sync when the user navigates back/forward through the
                // playlist history entries created by pushState above, instead of leaving the
                // video on whatever it happened to be while only the (invisible) URL changes.
                window.addEventListener('popstate', function (e) {
                  if (e.state && typeof e.state.id === 'number' && e.state.id !== currentId) {
                    playItem(e.state.id, false, false);
                  }
                });

                $remoteFollowScript
              })();
              </script>
            </body>
            </html>
        """.trimIndent()
        val response = newFixedLengthResponse(Response.Status.OK, "text/html", html)
        // Without this, a browser/WebView can serve a cached copy of this page back on
        // location.reload() (most visibly on /remote right after selecting a video, or on the
        // bare remote-follow player right after a video switch) instead of actually re-fetching
        // the now-current state from the server — this page always reflects live state, so it
        // must never be served stale.
        response.addHeader("Cache-Control", "no-store")
        return response
    }

    /**
     * A control-less full-bleed player: just the video, no topbar/back link/playlist/video.js
     * control bar. This is where every `/browse`/`/watch` page hands off to once `/remote` has
     * ever been used — from that point on, [remotePage] is the only thing driving playback, so
     * this page has nothing of its own to offer a viewer besides the picture itself. It polls
     * `/remote/state` the same as the full [watchPage], but applies play/pause/seek/video-switch
     * commands directly to the player instead of only following video switches.
     */
    private fun bareRemotePlayerPage(entry: VideoEntry): Response {
        val baseline = remoteSelection.get()
        // The remote can pick any video in the whole library, not just this one's folder
        // siblings, so (unlike watchPage's playlistJson) this needs every entry's MIME type.
        val videoTypesJson = "{" + entries.joinToString(",") { "\"${it.id}\":${jsonString(guessVideoMimeType(it.name))}" } + "}"
        // When the host exits remote mode this viewer should stop and leave the bare player, not
        // resume a self-controlled one: a folder viewer goes back to the browse listing, and a
        // single-file viewer (with nowhere to browse to) stops and shows that the session ended.
        val onRemoteClearedJs = if (isFolderMode) {
            "location.href = '/browse';"
        } else {
            "player.pause(); document.body.innerHTML = " +
                "'<div style=\"height:100vh;display:flex;align-items:center;justify-content:center;color:#aaa;font-family:sans-serif;font-size:16px;\">Remote control ended.</div>';"
        }
        val html = """
            <!DOCTYPE html>
            <html>
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <title>${escapeHtml(entry.name)}</title>
              <link href="/assets/videojs/video-js.min.css" rel="stylesheet">
              <style>
                * { box-sizing: border-box; }
                html, body { margin: 0; height: 100%; background: #000; overflow: hidden; }
                .video-js { width: 100%; height: 100%; }
                .video-js .vjs-tech { object-fit: contain; }
                /* No controls of its own — playback is driven entirely by whoever's using
                   /remote, so video.js's own UI chrome is hidden rather than just unused. */
                .video-js .vjs-control-bar,
                .video-js .vjs-big-play-button,
                .video-js .vjs-loading-spinner,
                .video-js .vjs-error-display {
                  display: none !important;
                }
                /* Shown only when the browser refused to autoplay with sound (its usual
                   policy for a page that hasn't seen a direct user gesture) — this player
                   falls back to muted playback so the poster thumbnail doesn't stay stuck on
                   screen forever, and offers one tap to restore sound. */
                .unmuteButton {
                  display: none; position: fixed; right: 16px; bottom: 16px; z-index: 10;
                  align-items: center; gap: 8px; padding: 12px 18px; border: none; border-radius: 999px;
                  background: linear-gradient(135deg, $accentColorHex, $accentColorEndHex); color: #fff;
                  font-size: 14px; font-weight: 700; cursor: pointer; box-shadow: 0 8px 26px rgba(0, 0, 0, 0.5);
                }
              </style>
            </head>
            <body>
              <video id="player" class="video-js" preload="auto" poster="/thumbnail?id=${entry.id}">
                <source src="/video?id=${entry.id}" type="${guessVideoMimeType(entry.name)}">
              </video>
              <button type="button" id="unmuteButton" class="unmuteButton">$speakerMutedIconSvg Tap for sound</button>
              <script src="/assets/videojs/video.min.js"></script>
              <script>
              (function () {
                var videoTypes = $videoTypesJson;
                var currentId = ${entry.id};
                var lastPlayRevision = ${baseline.playRevision};
                var lastSeekRevision = ${baseline.seekRevision};
                var player = videojs('player', { autoplay: true, controls: false });
                var unmuteButton = document.getElementById('unmuteButton');

                // Browsers routinely refuse to autoplay a video WITH sound unless this page
                // was reached via a direct user gesture — which a remote pick pushed over
                // /remote/ws never is. Rather than leave the poster thumbnail stuck on screen
                // forever (play() silently rejecting), fall back to muted playback so the
                // picture always actually starts, and offer a one-tap way to restore sound.
                function attemptPlay() {
                  var playPromise = player.play();
                  if (playPromise && typeof playPromise.catch === 'function') {
                    playPromise.catch(function () {
                      if (!player.muted()) {
                        player.muted(true);
                        if (unmuteButton) unmuteButton.style.display = 'flex';
                        player.play().catch(function () {});
                      }
                    });
                  }
                }
                if (unmuteButton) {
                  unmuteButton.addEventListener('click', function () {
                    player.muted(false);
                    unmuteButton.style.display = 'none';
                  });
                }

                // If playback was already underway elsewhere before this viewer connected,
                // start at the same spot instead of position 0.
                player.ready(function () {
                  var startAt = ${currentPositionSeconds(baseline)};
                  if (startAt > 0) player.currentTime(startAt);
                  attemptPlay();
                });

                function applyState(state) {
                  if (state.videoId === null) {
                    // The host left remote mode — stop playback and leave the bare player.
                    $onRemoteClearedJs
                    return;
                  }
                  if (state.videoId !== currentId) {
                    currentId = state.videoId;
                    lastPlayRevision = state.playRevision;
                    lastSeekRevision = state.seekRevision;
                    player.poster('/thumbnail?id=' + state.videoId);
                    player.src({ src: '/video?id=' + state.videoId, type: videoTypes[state.videoId] || 'video/mp4' });
                    if (state.positionSeconds > 0) player.currentTime(state.positionSeconds);
                    attemptPlay();
                    return;
                  }
                  if (state.playRevision !== lastPlayRevision) {
                    lastPlayRevision = state.playRevision;
                    if (state.playing) attemptPlay(); else player.pause();
                  }
                  if (state.seekRevision !== lastSeekRevision) {
                    lastSeekRevision = state.seekRevision;
                    if (state.seekSeconds !== null) player.currentTime(state.seekSeconds);
                  }
                }
                function connect() {
                  var proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
                  var ws = new WebSocket(proto + '//' + location.host + '/remote/ws');
                  ws.onmessage = function (e) {
                    try { applyState(JSON.parse(e.data)); } catch (err) {}
                  };
                  ws.onclose = function () { setTimeout(connect, 1000); };
                  ws.onerror = function () { ws.close(); };
                }
                connect();
              })();
              </script>
            </body>
            </html>
        """.trimIndent()
        val response = newFixedLengthResponse(Response.Status.OK, "text/html", html)
        // Without this, a browser/WebView can serve a cached copy of this page back on
        // location.reload() (most visibly on /remote right after selecting a video, or on the
        // bare remote-follow player right after a video switch) instead of actually re-fetching
        // the now-current state from the server — this page always reflects live state, so it
        // must never be served stale.
        response.addHeader("Cache-Control", "no-store")
        return response
    }

    /**
     * The control panel: a player with full transport controls (play/pause/seek/skip) for the
     * currently selected video, plus — in folder mode — a folder-navigation UI below it to pick a
     * different one. This is the page a "remote control" device (the host app's own screen, or any
     * other browser on the LAN) loads — every other connected viewer's [watchPage] is a
     * control-less display that just follows what happens here. In single-file mode there's
     * nothing to pick between, so the browser/grid and the Prev/Next/Random/Autoplay controls are
     * dropped, but driving one video's play/pause/seek for everyone still works.
     */
    private fun remotePage(path: String, sortMode: SortMode, flat: Boolean): Response {
        val current = remoteSelection.get()
        val currentEntry = current.videoId?.let { id -> entries.firstOrNull { it.id == id } }
        val flatParam = if (flat) "1" else "0"

        val effectivePath: String
        val title: String
        val videos: List<VideoEntry>
        val subfolders: List<String>
        if (isFolderMode) {
            val listing = computeListing(path, flat, sortMode)
                ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Folder not found")
            effectivePath = listing.effectivePath
            title = if (flat) "All videos" else listing.title
            videos = listing.videos
            subfolders = listing.subfolders
        } else {
            val single = entries.singleOrNull()
                ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "No video available")
            effectivePath = ""
            title = single.name
            videos = listOf(single)
            subfolders = emptyList()
        }

        // In flat ("All videos") mode there's no folder structure to walk, so the back link and
        // subfolder rows are empty and the grid shows every video with its folder as a subtitle.
        val backLink = if (effectivePath.isNotEmpty()) {
            val parentPath = effectivePath.substringBeforeLast('/', "")
            "<li><a class=\"back\" href=\"/remote?path=${encodePath(parentPath)}&sort=${sortMode.param}&flat=0\">$chevronLeftIconSvg ..</a></li>"
        } else {
            ""
        }
        val folderItems = subfolders.joinToString("\n") { folderName ->
            val childPath = if (effectivePath.isEmpty()) folderName else "$effectivePath/$folderName"
            "<li><a href=\"/remote?path=${encodePath(childPath)}&sort=${sortMode.param}&flat=0\">$folderIconSvg ${escapeHtml(folderName)}</a></li>"
        }
        // Single-file mode has no grid to pick from — the one video is taken control of via the
        // placeholder's "Take control" button instead.
        val videoItems = if (!isFolderMode) "" else videos.joinToString("\n") { entry ->
            val selectedClass = if (entry.id == current.videoId) " selected" else ""
            val subtitle = if (flat && entry.folderPath.isNotEmpty()) {
                "<span class=\"sub\">${escapeHtml(entry.folderPath)}</span>"
            } else {
                ""
            }
            """
            <li>
              <button type="button" class="videoCard$selectedClass" data-id="${entry.id}">
                <span class="thumb">
                  <img src="/thumbnail?id=${entry.id}" loading="lazy" alt="">
                  <span class="play-badge">$playBadgeIconSvg</span>
                </span>
                <span class="title">${escapeHtml(entry.name)}</span>
                $subtitle
              </button>
            </li>
            """.trimIndent()
        }
        val sortLinks = SortMode.values().joinToString(" ") { mode ->
            if (mode == sortMode) {
                "<span class=\"active\">${mode.label}</span>"
            } else {
                "<a href=\"/remote?path=${encodePath(effectivePath)}&sort=${mode.param}&flat=$flatParam\">${mode.label}</a>"
            }
        }
        val sortBar = if (videos.size > 1) {
            "<div class=\"bar\"><span class=\"label\">Sort:</span> $sortLinks</div>"
        } else {
            ""
        }
        // Folders vs. "All videos" toggle — only when the library actually has subfolders, and
        // only in folder mode (single-file has just the one video).
        val viewBar = if (isFolderMode && entries.any { it.folderPath.isNotEmpty() }) {
            val foldersOption = if (!flat) {
                "<span class=\"active\">Folders</span>"
            } else {
                "<a href=\"/remote?path=${encodePath("")}&sort=${sortMode.param}&flat=0\">Folders</a>"
            }
            val flatOption = if (flat) {
                "<span class=\"active\">All videos</span>"
            } else {
                "<a href=\"/remote?path=${encodePath("")}&sort=${sortMode.param}&flat=1\">All videos</a>"
            }
            "<div class=\"bar\"><span class=\"label\">View:</span> $foldersOption $flatOption</div>"
        } else {
            ""
        }
        // Prev/Next (and Random) step through whatever's currently listed below in this same
        // folder/sort order — the same set the grid itself shows, so the buttons always match
        // what's visually on screen.
        val videoIdsJson = videos.joinToString(",") { it.id.toString() }
        // Prev/Next/Random/Autoplay only make sense when there's more than one video to move
        // between, i.e. folder mode; single-file keeps just play/pause, skip, and seek.
        val panelToggles = if (isFolderMode) {
            """
                <label class="toggle"><input type="checkbox" id="autoplayToggle">$autoPlayIconSvg Auto play</label>
                <label class="toggle"><input type="checkbox" id="shuffleToggle">$randomPlayIconSvg</label>
            """.trimIndent()
        } else {
            ""
        }
        val prevButtonHtml = if (isFolderMode) {
            """<button type="button" id="prevButton" class="ctrlButton navButton" aria-label="Previous video">$chevronLeftIconSvg</button>"""
        } else {
            ""
        }
        val nextButtonHtml = if (isFolderMode) {
            """<button type="button" id="nextButton" class="ctrlButton navButton" aria-label="Next video">$chevronRightIconSvg</button>"""
        } else {
            ""
        }
        val playerSection = if (currentEntry != null) {
            """
            <div class="controlPanel">
              <div class="nowPlayingCard">
                <img class="nowPlayingThumb" src="/thumbnail?id=${currentEntry.id}" alt="">
                <div class="nowPlayingInfo">
                  <p class="nowPlayingLabel">Now playing on every connected viewer</p>
                  <p class="nowPlayingTitle">${escapeHtml(currentEntry.name)}</p>
                </div>
                <button type="button" id="exitRemoteButton" class="exitButton">
                  $exitIconSvg
                  Exit
                </button>
              </div>
              <div class="playFunctionPanel">
                $panelToggles
              </div>
              <div class="transportControls">
                <div class="transportControlsSection">
                  $prevButtonHtml
                  <button type="button" id="skipBackButton" class="ctrlButton skipButton navButton" aria-label="Skip back $skipSeconds seconds">$skipBackIconSvg<span class="skipNum">$skipSeconds</span></button>
                  <button type="button" id="playPauseButton" class="ctrlButton" aria-label="Play or pause">$playIconSvg</button>
                  <button type="button" id="skipForwardButton" class="ctrlButton skipButton navButton" aria-label="Skip forward $skipSeconds seconds">$skipForwardIconSvg<span class="skipNum">$skipSeconds</span></button>
                  $nextButtonHtml
                </div>
                <div class="transportControlsSection">
                  <span id="currentTimeLabel" class="timeLabel">0:00</span>
                  <input type="range" id="seekBar" class="seekBar" min="0" max="0" value="0" step="0.1">
                  <span id="durationLabel" class="timeLabel">0:00</span>
                </div>
              </div>
              <video id="player" class="hiddenVideo" muted autoplay playsinline preload="auto">
                <source src="/video?id=${currentEntry.id}" type="${guessVideoMimeType(currentEntry.name)}">
              </video>
            </div>
            """.trimIndent()
        } else if (isFolderMode) {
            """<p class="placeholder">Pick a video below to start controlling playback on every connected viewer.</p>"""
        } else {
            // Single-file mode: nothing to pick, so one button hands this video's playback off to
            // every connected viewer and reveals the control panel.
            val singleId = videos.first().id
            """
            <div class="placeholder">
              <p style="margin: 0 0 16px;">Take over playback of this video on every connected viewer.</p>
              <button type="button" id="takeControlButton" class="ctrlButton" data-id="$singleId" style="width: auto; padding: 10px 20px; border-radius: 999px;">Take control</button>
            </div>
            """.trimIndent()
        }

        val html = """
            <!DOCTYPE html>
            <html>
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <title>Remote: ${escapeHtml(title)}</title>
              <style>
                $themeTokensCss
                body {
                  margin: 0 auto; max-width: 1180px; min-height: 100vh;
                  padding: max(22px, env(safe-area-inset-top)) clamp(16px, 4vw, 44px) 28px;
                }
                /* Set on <body> only while a video is selected (see the controlPanel below) — the
                   panel is fixed/floating, so without this the bottom of the folder/video grid
                   would sit hidden underneath it. */
                body.hasControlPanel { padding-bottom: 300px; }
                h1 { font-size: clamp(26px, 6vw, 40px); font-weight: 800; margin: 0 0 8px; letter-spacing: -0.03em; line-height: 1.05; }
                .subtitle { margin: 0 0 22px; color: var(--muted); font-size: 14px; line-height: 1.5; max-width: 640px; }
                /* Floats over the bottom of the page, so the transport controls stay reachable
                   while browsing for the next video to pick. */
                .controlPanel {
                  position: fixed; left: 0; right: 0; bottom: 0; z-index: 20;
                  padding: 16px clamp(12px, 4vw, 40px) calc(16px + env(safe-area-inset-bottom));
                  background: rgba(14, 14, 24, 0.82); backdrop-filter: blur(22px) saturate(1.4);
                  border-top: 1px solid var(--line); border-radius: 28px 28px 0 0;
                  box-shadow: 0 -12px 40px rgba(0, 0, 0, 0.55);
                }
                .controlPanel > * { max-width: 1040px; margin-left: auto; margin-right: auto; }
                .nowPlayingCard {
                  display: flex; align-items: center; flex-wrap: wrap; gap: 14px; padding: 10px; margin-bottom: 10px;
                  background: var(--glass-hi); border: 1px solid var(--line); border-radius: 20px;
                }
                .nowPlayingThumb { width: 76px; aspect-ratio: 16 / 9; object-fit: cover; background: #000; border-radius: 12px; flex-shrink: 0; }
                .nowPlayingInfo { flex: 1; min-width: 120px; }
                .nowPlayingLabel {
                  margin: 0 0 3px; font-size: 11px; font-weight: 800; letter-spacing: 0.12em; text-transform: uppercase;
                  background: var(--grad); -webkit-background-clip: text; background-clip: text; color: transparent;
                }
                .nowPlayingTitle { margin: 0; font-size: 15px; font-weight: 700; word-break: break-word; }
                .exitButton {
                  flex-shrink: 0; padding: 10px 16px; border-radius: 999px; cursor: pointer; font: inherit;
                  font-size: 13px; font-weight: 700; display: flex; column-gap: 6px; align-items: center;
                  background: rgba(255, 92, 122, 0.14); color: #ff8fa3; border: 1px solid rgba(255, 92, 122, 0.4);
                  transition: background 0.2s ease;
                }
                .exitButton svg { width: 20px; height: 20px; }
                .exitButton:hover { background: rgba(255, 92, 122, 0.26); }
                .playFunctionPanel { display: flex; gap: 10px; margin-bottom: 10px; flex-wrap: wrap; }
                .toggle {
                  display: flex; align-items: center; gap: 8px; padding: 10px 16px; border-radius: 999px;
                  background: var(--glass-hi); border: 1px solid var(--line); font-size: 13px; font-weight: 600;
                  color: var(--muted); white-space: nowrap; cursor: pointer; flex-shrink: 0; transition: all 0.2s ease;
                }
                .toggle > input { display: none; }
                .toggle:has(input:checked) { background: var(--grad); color: #fff; border-color: transparent; box-shadow: var(--glow); }
                /* This is a control panel, not a viewing screen — the video itself stays loaded
                   (so the seek bar/duration are real) but is never shown or heard. display:none is
                   deliberately avoided here — some browsers/WebViews throttle or refuse to play a
                   display:none video; this keeps it laid out and decoding, just invisible. */
                .hiddenVideo {
                  position: fixed; top: 0; left: 0; width: 1px; height: 1px;
                  opacity: 0; pointer-events: none;
                }
                .transportControls {
                  display: flex; flex-wrap: wrap-reverse; align-items: center; gap: 12px; padding: 12px 14px;
                  background: var(--glass-hi); border: 1px solid var(--line); border-radius: 22px;
                }
                .transportControlsSection {
                  display: flex; align-items: center; justify-content: center; gap: 14px; width: 100%;
                }
                .ctrlButton {
                  flex-shrink: 0; width: 80px; height: 80px; border-radius: 50%; border: none; cursor: pointer;
                  background: var(--grad); color: #fff; font-size: 15px; line-height: 1; box-shadow: var(--glow);
                  display: flex; align-items: center; justify-content: center;
                  transition: transform 0.15s ease, box-shadow 0.2s ease;
                }
                .ctrlButton svg { width: 58px; height: 58px; }
                .ctrlButton:hover { transform: scale(1.05); }
                .ctrlButton:active { transform: scale(0.94); }
                .ctrlButton.navButton {
                  background: rgba(255, 255, 255, 0.07); border: 1px solid var(--line);
                  width: 60px; height: 60px; box-shadow: none;
                }
                .ctrlButton.navButton:hover { background: rgba(255, 255, 255, 0.14); }
                .ctrlButton.navButton svg { width: 32px; height: 32px; }
                /* Skip buttons pair a circular replay/forward arrow with the seconds count
                   overlaid in its hollow centre, so the exact jump size is visible on the button. */
                .skipButton { position: relative; }
                .ctrlButton.skipButton.navButton svg { width: 38px; height: 38px; }
                .skipNum {
                  position: absolute; top: 50%; left: 50%; transform: translate(-50%, -42%);
                  font-size: 14px; font-weight: 800; line-height: 1; pointer-events: none;
                }
                .ctrlButton:disabled { opacity: 0.3; cursor: default; transform: none; }
                .ctrlButton:disabled:hover { background: rgba(255, 255, 255, 0.07); }
                .timeLabel { flex-shrink: 0; width: 48px; font-size: 13px; font-weight: 600; color: var(--muted); text-align: center; font-variant-numeric: tabular-nums; }
                .seekBar {
                  flex: 1; height: 6px; cursor: pointer; -webkit-appearance: none; appearance: none;
                  border-radius: 999px; background: rgba(255, 255, 255, 0.16); accent-color: var(--accent-2);
                }
                .seekBar::-webkit-slider-thumb {
                  -webkit-appearance: none; width: 20px; height: 20px; border-radius: 50%;
                  background: #fff; box-shadow: 0 0 0 4px color-mix(in srgb, var(--accent-2) 45%, transparent);
                }
                .seekBar::-moz-range-thumb { width: 20px; height: 20px; border: none; border-radius: 50%; background: #fff; }
                .seekBar::-moz-range-progress { height: 6px; border-radius: 999px; background: var(--grad); }
                .placeholder {
                  margin: 0 0 24px; padding: 36px 28px; text-align: center; color: var(--muted); font-size: 15px;
                  background: var(--glass); backdrop-filter: blur(14px); border: 1px dashed var(--line); border-radius: var(--radius-l);
                }
                #takeControlButton { box-shadow: var(--glow); font-weight: 800; font-size: 16px; }
                .bar { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; margin: 0 0 18px; font-size: 14px; }
                .bar .label { color: var(--faint); margin-right: 4px; font-weight: 600; }
                .bar a, .bar .active {
                  padding: 10px 18px; border-radius: 999px; text-decoration: none; font-size: 14px; border: 1px solid var(--line);
                }
                .bar a { color: var(--muted); background: var(--glass); backdrop-filter: blur(14px); }
                .bar a:hover { background: var(--glass-hi); color: var(--text); }
                .bar .active { background: var(--grad); color: #fff; font-weight: 700; border-color: transparent; box-shadow: var(--glow); }
                ul.folders { list-style: none; padding: 0; margin: 0 0 22px; display: grid; gap: 12px;
                  grid-template-columns: repeat(auto-fill, minmax(min(100%, 260px), 1fr)); }
                ul.folders li { margin: 0; }
                ul.folders a {
                  display: flex; align-items: center; gap: 14px; padding: 16px 18px;
                  background: var(--glass); backdrop-filter: blur(14px); border: 1px solid var(--line);
                  color: var(--text); text-decoration: none; border-radius: var(--radius-m);
                  font-size: 15px; font-weight: 600; transition: background 0.2s ease, transform 0.2s ease;
                }
                ul.folders a svg { flex-shrink: 0; width: 22px; height: 22px; color: var(--accent-2); }
                ul.folders a:hover { background: var(--glass-hi); transform: translateY(-2px); }
                ul.folders a.back { color: var(--muted); }
                ul.videos {
                  list-style: none; padding: 0; margin: 0; display: grid;
                  grid-template-columns: repeat(auto-fill, minmax(min(100%, 190px), 1fr)); gap: 16px;
                }
                .videoCard {
                  display: flex; flex-direction: column; width: 100%; color: var(--text);
                  background: var(--glass); backdrop-filter: blur(14px); border: 1px solid var(--line);
                  border-radius: var(--radius-m); overflow: hidden; padding: 8px;
                  font: inherit; text-align: left; cursor: pointer;
                  transition: transform 0.25s ease, box-shadow 0.25s ease, border-color 0.25s ease;
                }
                .videoCard:hover, .videoCard:focus-visible {
                  transform: translateY(-4px); outline: none;
                  border-color: color-mix(in srgb, var(--accent-2) 55%, transparent);
                  box-shadow: 0 18px 40px -12px rgba(0, 0, 0, 0.7);
                }
                .videoCard.selected { border: 2px solid var(--accent-2); box-shadow: var(--glow); }
                .videoCard .thumb { display: block; position: relative; background: #000; border-radius: 16px; overflow: hidden; }
                .videoCard .thumb img { display: block; width: 100%; aspect-ratio: 16 / 9; object-fit: cover; }
                .videoCard .play-badge {
                  position: absolute; right: 10px; bottom: 10px; display: flex;
                  width: 36px; height: 36px; border-radius: 50%; background: var(--grad); color: #fff;
                  align-items: center; justify-content: center; box-shadow: var(--glow);
                }
                .videoCard .play-badge svg { width: 22px; height: 22px; }
                .videoCard .title { display: block; padding: 12px 6px 2px; font-size: 14px; font-weight: 600; word-break: break-word; line-height: 1.35; }
                .videoCard .sub { display: block; padding: 2px 6px 8px; font-size: 12px; font-weight: 600; color: var(--accent-2); word-break: break-word; }
              </style>
            </head>
            <body${if (currentEntry != null) " class=\"hasControlPanel\"" else ""}>
              $brandHeaderHtml
              <h1>${escapeHtml(title)}</h1>
              <p class="subtitle">${if (isFolderMode) "Every connected viewer sees exactly what plays here — pick a video, then use the player controls to drive playback for everyone." else "Every connected viewer sees exactly what plays here — use the player controls to drive playback for everyone."}</p>
              $playerSection
              $viewBar
              <ul class="folders">
                $backLink
                $folderItems
              </ul>
              $sortBar
              <ul class="videos">
                $videoItems
              </ul>
              <script>
              (function () {
                var initialVideoId = ${currentEntry?.id ?: "null"};
                var lastPlayRevision = ${current.playRevision};
                var lastSeekRevision = ${current.seekRevision};
                // If playback was already underway (another /remote panel started it, or this
                // one's just being reloaded/reopened mid-video), start the scrub bar at the
                // same spot instead of 0.
                var initialPositionSeconds = ${currentPositionSeconds(current)};
                // Backs the Prev/Next buttons — the same videos listed below, in the same sort
                // order, so the buttons always match what's visually on screen.
                var videoIds = [$videoIdsJson];

                function loadPref(key, defaultValue) {
                  var v = localStorage.getItem(key);
                  return v === null ? defaultValue : v === '1';
                }
                function savePref(key, value) {
                  localStorage.setItem(key, value ? '1' : '0');
                }
                var shuffleMode = loadPref('remoteShuffleMode', false);
                var autoplayNext = loadPref('remoteAutoplay', true);
                var skipSeconds = $skipSeconds;
                // Short haptic tick on each control-button press, when the host enabled it in
                // Settings and the browser/WebView actually supports the Web Vibration API.
                var vibrateOnControl = $vibrateOnControl;
                function buzz() {
                  if (vibrateOnControl && navigator.vibrate) {
                    try { navigator.vibrate(15); } catch (e) {}
                  }
                }

                // Every /remote/select reloads this whole page (see below), so there's no
                // in-page state to keep a shuffle "bag" in between picks — a fresh uniformly
                // random pick each time is simpler and just as good a fit for "random play".
                function nextVideoId() {
                  if (shuffleMode) {
                    var candidates = videoIds.filter(function (id) { return id !== initialVideoId; });
                    if (candidates.length === 0) return null;
                    return candidates[Math.floor(Math.random() * candidates.length)];
                  }
                  var idx = videoIds.indexOf(initialVideoId);
                  return (idx >= 0 && idx + 1 < videoIds.length) ? videoIds[idx + 1] : null;
                }
                function prevVideoId() {
                  var idx = videoIds.indexOf(initialVideoId);
                  return idx > 0 ? videoIds[idx - 1] : null;
                }
                function selectVideo(id) {
                  fetch('/remote/select?id=' + id, { method: 'POST' }).then(function () {
                    location.reload();
                  }).catch(function () {});
                }
                // Muted: this device is a remote, not a viewer — the video decodes only to
                // drive a real seek bar/duration, never to be watched or listened to itself.
                // No player library here — a plain <video> plus a button and a range-input
                // scrub bar, driven by the native media events, is the whole control panel.
                var player = document.getElementById('player');
                var playPauseButton = document.getElementById('playPauseButton');
                var seekBar = document.getElementById('seekBar');
                var currentTimeLabel = document.getElementById('currentTimeLabel');
                var durationLabel = document.getElementById('durationLabel');
                // Set while applying a command that arrived from /remote/state, so the player
                // events that fire as a side effect don't get echoed straight back as a new
                // command — otherwise every incoming play/pause/seek would immediately re-send
                // itself (and, with more than one /remote open, the two could fight forever).
                var applyingRemote = false;
                // True while the seek bar is being dragged, so timeupdate doesn't fight the
                // gesture by snapping the handle back to the actual playback position mid-drag.
                var scrubbing = false;

                function formatTime(seconds) {
                  if (!isFinite(seconds) || seconds < 0) seconds = 0;
                  var m = Math.floor(seconds / 60);
                  var s = Math.floor(seconds % 60);
                  return m + ':' + (s < 10 ? '0' : '') + s;
                }

                if (player) {
                  player.addEventListener('loadedmetadata', function () {
                    seekBar.max = player.duration || 0;
                    durationLabel.textContent = formatTime(player.duration);
                    if (initialPositionSeconds > 0) {
                      applyingRemote = true;
                      player.currentTime = initialPositionSeconds;
                      setTimeout(function () { applyingRemote = false; }, 400);
                    }
                  });
                  // Some browsers/WebViews don't reliably honor the plain autoplay attribute
                  // for this off-screen driver video — kick it explicitly instead of hoping
                  // the attribute alone starts playback.
                  applyingRemote = true;
                  player.play().catch(function () {});
                  setTimeout(function () { applyingRemote = false; }, 400);
                  player.addEventListener('timeupdate', function () {
                    if (!scrubbing) seekBar.value = player.currentTime;
                    currentTimeLabel.textContent = formatTime(player.currentTime);
                  });
                  player.addEventListener('play', function () {
                    playPauseButton.innerHTML = '&#10074;&#10074;';
                    if (applyingRemote) return;
                    fetch('/remote/command?action=play', { method: 'POST' }).catch(function () {});
                  });
                  player.addEventListener('pause', function () {
                    playPauseButton.innerHTML = '&#9654;';
                    if (applyingRemote) return;
                    fetch('/remote/command?action=pause', { method: 'POST' }).catch(function () {});
                  });
                  // When Autoplay is on, reaching the end of a video advances everyone to the
                  // next one in the current list (or a random one), the same as the native
                  // player's Autoplay — off, playback just stops at the end.
                  player.addEventListener('ended', function () {
                    if (!autoplayNext) return;
                    var id = nextVideoId();
                    if (id !== null) selectVideo(id);
                  });

                  playPauseButton.addEventListener('click', function () {
                    buzz();
                    if (player.paused) player.play().catch(function () {}); else player.pause();
                  });

                  seekBar.addEventListener('input', function () {
                    scrubbing = true;
                    currentTimeLabel.textContent = formatTime(parseFloat(seekBar.value));
                  });
                  seekBar.addEventListener('change', function () {
                    scrubbing = false;
                    var position = parseFloat(seekBar.value);
                    player.currentTime = position;
                    fetch('/remote/command?action=seek&position=' + position, { method: 'POST' }).catch(function () {});
                  });
                }

                var cards = document.querySelectorAll('.videoCard');
                for (var i = 0; i < cards.length; i++) {
                  cards[i].addEventListener('click', function () {
                    buzz();
                    selectVideo(parseInt(this.getAttribute('data-id'), 10));
                  });
                }

                // Single-file mode's one-button hand-off (there's no grid of cards to pick from).
                var takeControlButton = document.getElementById('takeControlButton');
                if (takeControlButton) {
                  takeControlButton.addEventListener('click', function () {
                    buzz();
                    selectVideo(parseInt(takeControlButton.getAttribute('data-id'), 10));
                  });
                }

                var prevButton = document.getElementById('prevButton');
                var nextButton = document.getElementById('nextButton');
                var shuffleToggle = document.getElementById('shuffleToggle');
                function updateNavButtonsState() {
                  if (prevButton) prevButton.disabled = prevVideoId() === null;
                  if (nextButton) nextButton.disabled = nextVideoId() === null;
                }
                if (prevButton) {
                  prevButton.addEventListener('click', function () {
                    buzz();
                    var id = prevVideoId();
                    if (id !== null) selectVideo(id);
                  });
                }
                if (nextButton) {
                  nextButton.addEventListener('click', function () {
                    buzz();
                    var id = nextVideoId();
                    if (id !== null) selectVideo(id);
                  });
                }
                if (shuffleToggle) {
                  shuffleToggle.checked = shuffleMode;
                  shuffleToggle.addEventListener('change', function () {
                    shuffleMode = shuffleToggle.checked;
                    savePref('remoteShuffleMode', shuffleMode);
                    updateNavButtonsState();
                  });
                }
                var autoplayToggle = document.getElementById('autoplayToggle');
                if (autoplayToggle) {
                  autoplayToggle.checked = autoplayNext;
                  autoplayToggle.addEventListener('change', function () {
                    autoplayNext = autoplayToggle.checked;
                    savePref('remoteAutoplay', autoplayNext);
                  });
                }
                updateNavButtonsState();

                // Skip jumps the driver video by the host's configured interval and pushes the
                // resulting position to every viewer, the same as dragging the scrub bar does.
                function skipBy(delta) {
                  if (!player) return;
                  var target = player.currentTime + delta;
                  if (target < 0) target = 0;
                  if (isFinite(player.duration) && target > player.duration) target = player.duration;
                  player.currentTime = target;
                  seekBar.value = target;
                  currentTimeLabel.textContent = formatTime(target);
                  fetch('/remote/command?action=seek&position=' + target, { method: 'POST' }).catch(function () {});
                }
                var skipBackButton = document.getElementById('skipBackButton');
                var skipForwardButton = document.getElementById('skipForwardButton');
                if (skipBackButton) {
                  skipBackButton.addEventListener('click', function () { buzz(); skipBy(-skipSeconds); });
                }
                if (skipForwardButton) {
                  skipForwardButton.addEventListener('click', function () { buzz(); skipBy(skipSeconds); });
                }

                var exitButton = document.getElementById('exitRemoteButton');
                if (exitButton) {
                  exitButton.addEventListener('click', function () {
                    buzz();
                    fetch('/remote/clear', { method: 'POST' }).then(function () {
                      location.reload();
                    }).catch(function () {});
                  });
                }

                function applyState(state) {
                  if (state.videoId !== initialVideoId) {
                    location.reload();
                    return;
                  }
                  if (!player) return;
                  if (state.playRevision !== lastPlayRevision) {
                    lastPlayRevision = state.playRevision;
                    applyingRemote = true;
                    if (state.playing) player.play().catch(function () {}); else player.pause();
                    setTimeout(function () { applyingRemote = false; }, 400);
                  }
                  if (state.seekRevision !== lastSeekRevision) {
                    lastSeekRevision = state.seekRevision;
                    if (state.seekSeconds !== null) {
                      applyingRemote = true;
                      player.currentTime = state.seekSeconds;
                      setTimeout(function () { applyingRemote = false; }, 400);
                    }
                  }
                }
                function connectWs() {
                  var proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
                  var ws = new WebSocket(proto + '//' + location.host + '/remote/ws');
                  ws.onmessage = function (e) {
                    try { applyState(JSON.parse(e.data)); } catch (err) {}
                  };
                  ws.onclose = function () { setTimeout(connectWs, 1000); };
                  ws.onerror = function () { ws.close(); };
                }
                connectWs();
              })();
              </script>
            </body>
            </html>
        """.trimIndent()
        val response = newFixedLengthResponse(Response.Status.OK, "text/html", html)
        // Without this, a browser/WebView can serve a cached copy of this page back on
        // location.reload() (most visibly on /remote right after selecting a video, or on the
        // bare remote-follow player right after a video switch) instead of actually re-fetching
        // the now-current state from the server — this page always reflects live state, so it
        // must never be served stale.
        response.addHeader("Cache-Control", "no-store")
        return response
    }

    /** Shared by the `/remote/state` HTTP fallback and every `/remote/ws` push. */
    private fun remoteStateJsonString(): String {
        val state = remoteSelection.get()
        return "{" +
            "\"videoId\":${state.videoId ?: "null"}," +
            "\"revision\":${state.revision}," +
            "\"playing\":${state.playing}," +
            "\"playRevision\":${state.playRevision}," +
            "\"seekSeconds\":${state.seekSeconds ?: "null"}," +
            "\"seekRevision\":${state.seekRevision}," +
            "\"positionSeconds\":${currentPositionSeconds(state)}" +
            "}"
    }

    private fun remoteStateJson(): Response {
        val response = newFixedLengthResponse(Response.Status.OK, "application/json", remoteStateJsonString())
        response.addHeader("Cache-Control", "no-store")
        return response
    }

    private fun handleRemoteSelect(session: IHTTPSession): Response {
        val id = session.parameters["id"]?.firstOrNull()?.toIntOrNull()
            ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Missing or invalid id")
        if (entries.none { it.id == id }) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Video not found")
        }
        // A fresh pick always starts playing from the top — any pending seek/pause from whatever
        // was selected before no longer applies.
        remoteSelection.updateAndGet {
            RemoteSelection(
                videoId = id,
                revision = it.revision + 1,
                playing = true,
                playRevision = it.playRevision + 1,
                seekSeconds = null,
                seekRevision = it.seekRevision,
                positionBaseSeconds = 0.0,
                positionBaseAtMs = System.currentTimeMillis()
            )
        }
        broadcastRemoteState()
        return newFixedLengthResponse(Response.Status.OK, "text/plain", "OK")
    }

    /** Handles play/pause/seek commands from the control panel's player — see [remotePage]. */
    private fun handleRemoteCommand(session: IHTTPSession): Response {
        if (remoteSelection.get().videoId == null) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "No video selected")
        }
        when (session.parameters["action"]?.firstOrNull()) {
            // Resuming: keep the paused position as the timeline anchor, just restart its clock.
            "play" -> remoteSelection.updateAndGet {
                it.copy(playing = true, playRevision = it.playRevision + 1, positionBaseAtMs = System.currentTimeMillis())
            }
            "pause" -> remoteSelection.updateAndGet {
                it.copy(
                    playing = false,
                    playRevision = it.playRevision + 1,
                    positionBaseSeconds = currentPositionSeconds(it),
                    positionBaseAtMs = System.currentTimeMillis()
                )
            }
            "seek" -> {
                val position = session.parameters["position"]?.firstOrNull()?.toDoubleOrNull()
                    ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Missing or invalid position")
                remoteSelection.updateAndGet {
                    it.copy(
                        seekSeconds = position,
                        seekRevision = it.seekRevision + 1,
                        positionBaseSeconds = position,
                        positionBaseAtMs = System.currentTimeMillis()
                    )
                }
            }
            else -> return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Unknown action")
        }
        broadcastRemoteState()
        return newFixedLengthResponse(Response.Status.OK, "text/plain", "OK")
    }

    /**
     * Leaves remote mode: clears the current pick so every bare, control-less viewer page hands
     * playback back to a normal watch page with its own controls — see [bareRemotePlayerPage].
     */
    private fun handleRemoteClear(): Response {
        remoteSelection.updateAndGet { RemoteSelection(revision = it.revision + 1, playRevision = it.playRevision + 1) }
        broadcastRemoteState()
        return newFixedLengthResponse(Response.Status.OK, "text/plain", "OK")
    }

    private fun serveVideo(session: IHTTPSession): Response {
        val entry = entryFor(session)
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Video not found")

        val pfd = try {
            contentResolver.openFileDescriptor(entry.uri, "r")
        } catch (e: Exception) {
            null
        } ?: return newFixedLengthResponse(
            Response.Status.INTERNAL_ERROR, "text/plain", "Cannot open video file"
        )

        val fileSize = pfd.statSize
        if (fileSize <= 0) {
            pfd.close()
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Unknown file size")
        }

        val mimeType = contentResolver.getType(entry.uri) ?: "video/mp4"
        val rangeHeader = session.headers["range"]

        var start = 0L
        var end = fileSize - 1
        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            val parts = rangeHeader.removePrefix("bytes=").split("-")
            parts.getOrNull(0)?.takeIf { it.isNotBlank() }?.toLongOrNull()?.let { start = it }
            parts.getOrNull(1)?.takeIf { it.isNotBlank() }?.toLongOrNull()?.let { end = it }
        }
        start = start.coerceIn(0, fileSize - 1)
        end = end.coerceIn(start, fileSize - 1)
        val contentLength = end - start + 1

        // A large buffer here (rather than handing NanoHTTPD the raw stream) matters most for
        // SAF/content:// URIs, where each underlying read() can be a FUSE/binder round trip —
        // NanoHTTPD copies the response in small fixed-size chunks, so without this a big video
        // turns into many small expensive reads instead of far fewer large ones.
        val stream = BufferedInputStream(ClosingFileInputStream(pfd), VIDEO_STREAM_BUFFER_SIZE)
        var skipped = 0L
        while (skipped < start) {
            val n = stream.skip(start - skipped)
            if (n <= 0) break
            skipped += n
        }

        val status = if (rangeHeader != null) Response.Status.PARTIAL_CONTENT else Response.Status.OK
        val response = newFixedLengthResponse(status, mimeType, stream, contentLength)
        response.addHeader("Accept-Ranges", "bytes")
        if (rangeHeader != null) {
            response.addHeader("Content-Range", "bytes $start-$end/$fileSize")
        }
        return response
    }

    private fun serveThumbnail(session: IHTTPSession): Response {
        val entry = entryFor(session)
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Video not found")

        val jpeg = thumbnailCache.getOrPut(entry.id) {
            ThumbnailUtil.extractThumbnailJpeg(contentResolver, entry.uri) ?: ByteArray(0)
        }
        return if (jpeg.isNotEmpty()) {
            newFixedLengthResponse(Response.Status.OK, "image/jpeg", ByteArrayInputStream(jpeg), jpeg.size.toLong())
        } else {
            newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "No thumbnail available")
        }
    }

    private fun encodePath(path: String): String = URLEncoder.encode(path, "UTF-8")

    private fun escapeHtml(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    /** Encodes [text] as a JSON string literal, safe to inline into a `<script>` block. */
    private fun jsonString(text: String): String {
        val sb = StringBuilder("\"")
        for (c in text) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '<' -> sb.append("\\u003c") // avoids a stray "</script>" breaking out of the tag
                else -> if (c.code < 0x20) sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        sb.append("\"")
        return sb.toString()
    }
}
