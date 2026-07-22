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
    private val accentColorHex: String = "#4A5FFF",
    /** The host's Settings > Skip interval, in seconds; how far `/remote`'s skip buttons jump. */
    private val skipSeconds: Int = 10
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

    // Small inline SVGs used across the browse/watch pages instead of emoji, so icons render
    // identically regardless of the viewing device's font/emoji set.
    private val folderIconSvg =
        "<svg width=\"16\" height=\"16\" viewBox=\"0 0 24 24\" fill=\"currentColor\"><path d=\"M3 6a1 1 0 0 1 1-1h5l2 2h9a1 1 0 0 1 1 1v9a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1z\"/></svg>"
    private val chevronLeftIconSvg =
        "<svg width=\"16\" height=\"16\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\"><path d=\"M15 6l-6 6 6 6\"/></svg>"
    private val chevronRightIconSvg =
        "<svg width=\"16\" height=\"16\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\"><path d=\"M9 6l6 6-6 6\"/></svg>"
    private val playBadgeIconSvg =
        "<svg width=\"36\" height=\"36\" viewBox=\"0 0 24 24\"><circle cx=\"12\" cy=\"12\" r=\"10\" fill=\"rgba(0,0,0,0.55)\"/><path d=\"M10 8l6 4-6 4z\" fill=\"#fff\"/></svg>"
    // Circular "replay"/"forward" arrows (the seconds count is overlaid as text in the button),
    // for the /remote skip-back / skip-forward controls.
    private val skipBackIconSvg =
        "<svg viewBox=\"0 0 24 24\" fill=\"currentColor\"><path d=\"M12 5V1L7 6l5 5V7c3.31 0 6 2.69 6 6s-2.69 6-6 6-6-2.69-6-6H4c0 4.42 3.58 8 8 8s8-3.58 8-8-3.58-8-8-8z\"/></svg>"
    private val skipForwardIconSvg =
        "<svg viewBox=\"0 0 24 24\" fill=\"currentColor\"><path d=\"M12 5V1l5 5-5 5V7c-3.31 0-6 2.69-6 6s2.69 6 6 6 6-2.69 6-6h2c0 4.42-3.58 8-8 8s-8-3.58-8-8 3.58-8 8-8z\"/></svg>"
    private val speakerMutedIconSvg =
        "<svg width=\"20\" height=\"20\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\"><path d=\"M11 5 6 9H3v6h3l5 4V5z\"/><line x1=\"23\" y1=\"9\" x2=\"17\" y2=\"15\"/><line x1=\"17\" y1=\"9\" x2=\"23\" y2=\"15\"/></svg>"

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
                SortMode.fromParam(session.parameters["sort"]?.firstOrNull() ?: defaultSortParam)
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
                :root { --accent: $accentColorHex; }
                * { box-sizing: border-box; }
                body {
                  margin: 0; padding: 24px; background: #111319; color: #eee;
                  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
                }
                h1 { font-size: 21px; margin: 0 0 16px; letter-spacing: -0.01em; }
                .bar { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; margin: 0 0 16px; font-size: 13px; }
                .bar .label { color: #888; margin-right: 2px; }
                .bar a, .bar .active {
                  padding: 6px 14px; border-radius: 999px; text-decoration: none; font-size: 13px;
                }
                .bar a { color: #ccc; background: #1c1f28; }
                .bar a:hover { background: #262a36; }
                .bar .active { background: var(--accent); color: #fff; font-weight: 600; }
                ul.folders { list-style: none; padding: 0; margin: 0 0 16px; }
                ul.folders li { margin: 6px 0; }
                ul.folders a {
                  display: flex; align-items: center; gap: 10px; padding: 12px 16px;
                  background: #1c1f28; color: #fff; text-decoration: none; border-radius: 12px;
                  transition: background 0.15s ease;
                }
                ul.folders a:hover { background: #262a36; }
                ul.folders a.back { color: #ccc; }
                ul.videos {
                  list-style: none; padding: 0; margin: 0; display: grid;
                  grid-template-columns: repeat(auto-fill, minmax(160px, 1fr)); gap: 14px;
                }
                ul.videos a {
                  display: flex; flex-direction: column; background: #1c1f28; color: #fff;
                  text-decoration: none; border-radius: 12px; overflow: hidden;
                  transition: transform 0.15s ease, background 0.15s ease;
                }
                ul.videos a:hover { background: #262a36; transform: translateY(-2px); }
                ul.videos .thumb { display: block; position: relative; background: #000; }
                ul.videos .thumb img { display: block; width: 100%; aspect-ratio: 16 / 9; object-fit: cover; }
                ul.videos .play-badge {
                  position: absolute; right: 6px; bottom: 6px; display: flex; opacity: 0.9;
                }
                ul.videos .title { display: block; padding: 8px; font-size: 13px; word-break: break-word; }
                ul.videos small { display: block; padding: 0 8px 8px; margin-top: -8px; color: #888; font-size: 11px; word-break: break-word; }
              </style>
            </head>
            <body>
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
                :root { --accent: $accentColorHex; }
                * { box-sizing: border-box; }
                html, body {
                  margin: 0; height: 100%; background: #111319; color: #eee;
                  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
                }
                body { display: flex; flex-direction: column; }
                .topbar { display: flex; align-items: center; gap: 16px; padding: 10px 16px; flex-shrink: 0; flex-wrap: wrap; }
                .back { display: flex; align-items: center; gap: 4px; color: #9db0ff; text-decoration: none; flex-shrink: 0; }
                #currentTitle { font-size: 14px; color: #ccc; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
                .controls { display: flex; gap: 12px; align-items: center; margin-left: auto; }
                .toggle {
                  display: flex; align-items: center; gap: 6px; padding: 6px 12px; border-radius: 999px;
                  background: #1c1f28; font-size: 12px; color: #ccc; white-space: nowrap; cursor: pointer;
                }
                .toggle:has(input:checked) { background: var(--accent); color: #fff; }
                .main { flex: 1; display: flex; min-height: 0; }
                .player { flex: 1; background: #000; min-width: 0; }
                .video-js { width: 100%; height: 100%; }
                .video-js .vjs-tech { object-fit: contain; }
                /* Re-skins video.js's default look (grey-blue box skin) to match this app's
                   dark surfaces + accent color, reusing the same play-badge circle motif as
                   the thumbnails on the browse page. */
                .video-js .vjs-big-play-button {
                  width: 64px; height: 64px; line-height: 64px;
                  margin: -32px 0 0 -32px;
                  font-size: 26px;
                  border: none;
                  border-radius: 50%;
                  background-color: rgba(0, 0, 0, 0.55);
                  transition: background-color 0.15s ease;
                }
                .video-js:hover .vjs-big-play-button,
                .video-js .vjs-big-play-button:focus,
                .video-js .vjs-big-play-button:hover {
                  border: none;
                  background-color: var(--accent);
                }
                .video-js .vjs-control-bar { background-color: rgba(17, 19, 25, 0.85); }
                .video-js .vjs-slider { background-color: rgba(255, 255, 255, 0.15); }
                .video-js .vjs-play-progress,
                .video-js .vjs-volume-level {
                  background-color: var(--accent);
                }
                .video-js .vjs-load-progress div { background: rgba(74, 95, 255, 0.35); }
                .video-js .vjs-control:focus:before,
                .video-js .vjs-control:hover:before {
                  text-shadow: 0 0 1em var(--accent);
                }
                .video-js .vjs-menu-content { background-color: #1c1f28; border-radius: 10px; overflow: hidden; }
                .video-js .vjs-menu-item:hover,
                .video-js .vjs-menu li.vjs-menu-item:focus,
                .video-js .vjs-menu li.vjs-menu-item:hover {
                  background-color: rgba(74, 95, 255, 0.25);
                }
                .video-js .vjs-selected:hover,
                .video-js .vjs-menu li.vjs-selected,
                .video-js .vjs-menu li.vjs-selected:focus,
                .video-js .vjs-menu li.vjs-selected:hover {
                  background-color: var(--accent);
                  color: #fff;
                }
                /* Prev/Next live inside video.js's own control bar (see navButtonsScript),
                   right next to the play button, styled to match its other controls. */
                .video-js .vjs-nav-button {
                  display: flex;
                  align-items: center;
                  justify-content: center;
                  cursor: pointer;
                }
                .video-js .vjs-nav-icon { font-size: 1.8em; line-height: 1; color: #fff; }
                .video-js .vjs-nav-button:hover .vjs-nav-icon,
                .video-js .vjs-nav-button:focus .vjs-nav-icon {
                  color: var(--accent);
                }
                .video-js .vjs-nav-button:disabled { cursor: default; opacity: 0.35; }
                .playlist { width: 280px; flex-shrink: 0; overflow-y: auto; border-left: 1px solid #222; list-style: none; margin: 0; padding: 0; }
                .playlist li { display: flex; gap: 10px; align-items: center; padding: 8px 12px; cursor: pointer; border-radius: 10px; margin: 4px 6px; }
                .playlist li:hover { background: #1c1f28; }
                .playlist li.active { background: #1c1f28; box-shadow: inset 3px 0 0 var(--accent); }
                .playlist img { width: 72px; aspect-ratio: 16 / 9; object-fit: cover; background: #000; border-radius: 8px; flex-shrink: 0; }
                .playlist span { font-size: 12px; word-break: break-word; }
                @media (max-width: 700px) {
                  .main { flex-direction: column; }
                  .playlist { width: 100%; max-height: 35vh; border-left: none; border-top: 1px solid #222; }
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
                  align-items: center; gap: 6px; padding: 10px 14px; border: none; border-radius: 999px;
                  background: rgba(0, 0, 0, 0.65); color: #fff; font-size: 13px; cursor: pointer;
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
                    // The remote's been cleared — hand control back to a normal watch page
                    // with its own controls instead of sitting on a bare screen forever.
                    location.href = '/watch?id=' + currentId;
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
    private fun remotePage(path: String, sortMode: SortMode): Response {
        val current = remoteSelection.get()
        val currentEntry = current.videoId?.let { id -> entries.firstOrNull { it.id == id } }

        val effectivePath: String
        val title: String
        val videos: List<VideoEntry>
        val subfolders: List<String>
        if (isFolderMode) {
            val listing = computeListing(path, flat = false, sortMode)
                ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Folder not found")
            effectivePath = listing.effectivePath
            title = listing.title
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

        val backLink = if (effectivePath.isNotEmpty()) {
            val parentPath = effectivePath.substringBeforeLast('/', "")
            "<li><a class=\"back\" href=\"/remote?path=${encodePath(parentPath)}&sort=${sortMode.param}\">$chevronLeftIconSvg ..</a></li>"
        } else {
            ""
        }
        val folderItems = subfolders.joinToString("\n") { folderName ->
            val childPath = if (effectivePath.isEmpty()) folderName else "$effectivePath/$folderName"
            "<li><a href=\"/remote?path=${encodePath(childPath)}&sort=${sortMode.param}\">$folderIconSvg ${escapeHtml(folderName)}</a></li>"
        }
        // Single-file mode has no grid to pick from — the one video is taken control of via the
        // placeholder's "Take control" button instead.
        val videoItems = if (!isFolderMode) "" else videos.joinToString("\n") { entry ->
            val selectedClass = if (entry.id == current.videoId) " selected" else ""
            """
            <li>
              <button type="button" class="videoCard$selectedClass" data-id="${entry.id}">
                <span class="thumb">
                  <img src="/thumbnail?id=${entry.id}" loading="lazy" alt="">
                  <span class="play-badge">$playBadgeIconSvg</span>
                </span>
                <span class="title">${escapeHtml(entry.name)}</span>
              </button>
            </li>
            """.trimIndent()
        }
        val sortLinks = SortMode.values().joinToString(" ") { mode ->
            if (mode == sortMode) {
                "<span class=\"active\">${mode.label}</span>"
            } else {
                "<a href=\"/remote?path=${encodePath(effectivePath)}&sort=${mode.param}\">${mode.label}</a>"
            }
        }
        val sortBar = if (videos.size > 1) {
            "<div class=\"bar\"><span class=\"label\">Sort:</span> $sortLinks</div>"
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
                <label class="toggle"><input type="checkbox" id="autoplayToggle"> Autoplay</label>
                <label class="toggle"><input type="checkbox" id="shuffleToggle"> Random</label>
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
                $panelToggles
                <button type="button" id="exitRemoteButton" class="exitButton">Exit remote mode</button>
              </div>
              <div class="transportControls">
                $prevButtonHtml
                <button type="button" id="skipBackButton" class="ctrlButton skipButton navButton" aria-label="Skip back $skipSeconds seconds">$skipBackIconSvg<span class="skipNum">$skipSeconds</span></button>
                <button type="button" id="playPauseButton" class="ctrlButton" aria-label="Play or pause">&#9654;</button>
                <button type="button" id="skipForwardButton" class="ctrlButton skipButton navButton" aria-label="Skip forward $skipSeconds seconds">$skipForwardIconSvg<span class="skipNum">$skipSeconds</span></button>
                $nextButtonHtml
                <span id="currentTimeLabel" class="timeLabel">0:00</span>
                <input type="range" id="seekBar" class="seekBar" min="0" max="0" value="0" step="0.1">
                <span id="durationLabel" class="timeLabel">0:00</span>
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
                :root { --accent: $accentColorHex; }
                * { box-sizing: border-box; }
                body {
                  margin: 0; padding: 24px; background: #111319; color: #eee;
                  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
                }
                /* Set on <body> only while a video is selected (see the controlPanel below) — the
                   panel is fixed/floating, so without this the bottom of the folder/video grid
                   would sit hidden underneath it. */
                body.hasControlPanel { padding-bottom: 210px; }
                h1 { font-size: 21px; margin: 0 0 4px; letter-spacing: -0.01em; }
                .subtitle { margin: 0 0 16px; color: #888; font-size: 13px; }
                /* Floats over the bottom of the page instead of sitting inline at the top, so
                   the transport controls stay reachable without scrolling back up while
                   browsing for the next video to pick. */
                .controlPanel {
                  position: fixed; left: 0; right: 0; bottom: 0; z-index: 20;
                  padding: 12px 16px calc(12px + env(safe-area-inset-bottom));
                  background: #181b24; border-top: 1px solid #262a36;
                  box-shadow: 0 -6px 20px rgba(0, 0, 0, 0.4);
                }
                .nowPlayingCard {
                  display: flex; align-items: center; flex-wrap: wrap; gap: 12px; padding: 10px; margin-bottom: 8px;
                  background: #1c1f28; border-radius: 12px;
                }
                .nowPlayingThumb { width: 64px; aspect-ratio: 16 / 9; object-fit: cover; background: #000; border-radius: 8px; flex-shrink: 0; }
                .nowPlayingInfo { flex: 1; min-width: 120px; }
                .nowPlayingLabel { margin: 0 0 2px; font-size: 11px; color: #888; }
                .nowPlayingTitle { margin: 0; font-size: 14px; word-break: break-word; }
                .exitButton {
                  flex-shrink: 0; padding: 8px 14px; border-radius: 999px; border: none; cursor: pointer;
                  background: #262a36; color: #eee; font-size: 12px; font: inherit;
                }
                .exitButton:hover { background: #333846; }
                .toggle {
                  display: flex; align-items: center; gap: 6px; padding: 8px 14px; border-radius: 999px;
                  background: #262a36; font-size: 12px; color: #ccc; white-space: nowrap; cursor: pointer;
                  flex-shrink: 0;
                }
                .toggle:has(input:checked) { background: var(--accent); color: #fff; }
                /* This is a control panel, not a viewing screen — the video itself stays loaded
                   (so the seek bar/duration are real) but is never shown or heard; a plain
                   button + range-input scrub bar drive it instead of an embedded video player's
                   own on-screen controls. display:none is deliberately avoided here — some
                   browsers/WebViews throttle or refuse to actually play a display:none video,
                   which is why the picture never showed up as "playing"; this keeps it laid out
                   and decoding, just invisible and out of the way. */
                .hiddenVideo {
                  position: fixed; top: 0; left: 0; width: 1px; height: 1px;
                  opacity: 0; pointer-events: none;
                }
                .transportControls {
                  display: flex; align-items: center; gap: 10px; padding: 10px 14px;
                  background: #1c1f28; border-radius: 12px;
                }
                .ctrlButton {
                  flex-shrink: 0; width: 40px; height: 40px; border-radius: 50%; border: none; cursor: pointer;
                  background: var(--accent); color: #fff; font-size: 15px; line-height: 1;
                  display: flex; align-items: center; justify-content: center;
                }
                .ctrlButton:hover { opacity: 0.85; }
                .ctrlButton.navButton { background: #262a36; width: 36px; height: 36px; }
                .ctrlButton.navButton:hover { background: #333846; }
                /* Skip buttons pair a circular replay/forward arrow with the seconds count
                   overlaid in its hollow centre, so the exact jump size is visible on the button. */
                .skipButton { position: relative; }
                .skipButton svg { width: 22px; height: 22px; }
                .skipNum {
                  position: absolute; top: 50%; left: 50%; transform: translate(-50%, -42%);
                  font-size: 9px; font-weight: 700; line-height: 1; pointer-events: none;
                }
                .ctrlButton:disabled { opacity: 0.35; cursor: default; }
                .ctrlButton:disabled:hover { background: #262a36; }
                .timeLabel { flex-shrink: 0; width: 36px; font-size: 12px; color: #ccc; text-align: center; }
                .seekBar { flex: 1; accent-color: var(--accent); cursor: pointer; }
                .placeholder { margin: 0 0 20px; padding: 32px; text-align: center; color: #888; background: #1c1f28; border-radius: 12px; }
                .bar { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; margin: 0 0 16px; font-size: 13px; }
                .bar .label { color: #888; margin-right: 2px; }
                .bar a, .bar .active {
                  padding: 6px 14px; border-radius: 999px; text-decoration: none; font-size: 13px;
                }
                .bar a { color: #ccc; background: #1c1f28; }
                .bar a:hover { background: #262a36; }
                .bar .active { background: var(--accent); color: #fff; font-weight: 600; }
                ul.folders { list-style: none; padding: 0; margin: 0 0 16px; }
                ul.folders li { margin: 6px 0; }
                ul.folders a {
                  display: flex; align-items: center; gap: 10px; padding: 12px 16px;
                  background: #1c1f28; color: #fff; text-decoration: none; border-radius: 12px;
                  transition: background 0.15s ease;
                }
                ul.folders a:hover { background: #262a36; }
                ul.folders a.back { color: #ccc; }
                ul.videos {
                  list-style: none; padding: 0; margin: 0; display: grid;
                  grid-template-columns: repeat(auto-fill, minmax(160px, 1fr)); gap: 14px;
                }
                .videoCard {
                  display: flex; flex-direction: column; width: 100%; background: #1c1f28; color: #fff;
                  border: 2px solid transparent; border-radius: 12px; overflow: hidden; padding: 0;
                  font: inherit; text-align: left; cursor: pointer;
                  transition: transform 0.15s ease, background 0.15s ease, border-color 0.15s ease;
                }
                .videoCard:hover { background: #262a36; transform: translateY(-2px); }
                .videoCard.selected { border-color: var(--accent); }
                .videoCard .thumb { display: block; position: relative; background: #000; }
                .videoCard .thumb img { display: block; width: 100%; aspect-ratio: 16 / 9; object-fit: cover; }
                .videoCard .play-badge {
                  position: absolute; right: 6px; bottom: 6px; display: flex; opacity: 0.9;
                }
                .videoCard .title { display: block; padding: 8px; font-size: 13px; word-break: break-word; }
              </style>
            </head>
            <body${if (currentEntry != null) " class=\"hasControlPanel\"" else ""}>
              <h1>${escapeHtml(title)}</h1>
              <p class="subtitle">${if (isFolderMode) "Every connected viewer sees exactly what plays here — pick a video, then use the player controls to drive playback for everyone." else "Every connected viewer sees exactly what plays here — use the player controls to drive playback for everyone."}</p>
              $playerSection
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
                    selectVideo(parseInt(this.getAttribute('data-id'), 10));
                  });
                }

                // Single-file mode's one-button hand-off (there's no grid of cards to pick from).
                var takeControlButton = document.getElementById('takeControlButton');
                if (takeControlButton) {
                  takeControlButton.addEventListener('click', function () {
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
                    var id = prevVideoId();
                    if (id !== null) selectVideo(id);
                  });
                }
                if (nextButton) {
                  nextButton.addEventListener('click', function () {
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
                  skipBackButton.addEventListener('click', function () { skipBy(-skipSeconds); });
                }
                if (skipForwardButton) {
                  skipForwardButton.addEventListener('click', function () { skipBy(skipSeconds); });
                }

                var exitButton = document.getElementById('exitRemoteButton');
                if (exitButton) {
                  exitButton.addEventListener('click', function () {
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
