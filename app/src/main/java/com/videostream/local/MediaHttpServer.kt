package com.videostream.local

import android.content.ContentResolver
import android.content.res.AssetManager
import android.os.ParcelFileDescriptor
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.io.FileInputStream
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

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
 */
class MediaHttpServer(
    port: Int,
    private val contentResolver: ContentResolver,
    private val assetManager: AssetManager,
    private val entries: List<VideoEntry>,
    private val libraryName: String,
    private val isFolderMode: Boolean,
    /** One of [SortMode]'s `param` values, used when a request doesn't specify `?sort=`. */
    private val defaultSortParam: String
) : NanoHTTPD(port) {

    // Keyed by VideoEntry.id. An empty array means extraction was already tried and failed,
    // so a broken/DRM'd file isn't re-decoded on every thumbnail request.
    private val thumbnailCache = ConcurrentHashMap<Int, ByteArray>()

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

    override fun serve(session: IHTTPSession): Response {
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
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
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
            return watchPage(single, SortMode.NAME, flat = false)
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
        return watchPage(entry, sortMode, flat)
    }

    private fun sortVideos(videos: List<VideoEntry>, sortMode: SortMode): List<VideoEntry> = when (sortMode) {
        SortMode.NAME -> videos.sortedBy { it.name.lowercase() }
        SortMode.DATE -> videos.sortedByDescending { it.lastModified }
        SortMode.SIZE -> videos.sortedByDescending { it.sizeBytes }
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
     * Renders the videos and immediate subfolders that live directly inside [path]. When
     * [flat] is on, [path] is ignored entirely and every video in the library is listed
     * together, with its original folder shown as a subtitle for context. [flat] reflects
     * only this one request; the page's own script is what keeps it in sync with the
     * viewer's remembered `localStorage` preference across navigation.
     */
    private fun browsePage(path: String, sortMode: SortMode, flat: Boolean): Response {
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

        if (videos.isEmpty() && subfolders.isEmpty()) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Folder not found")
        }

        val title = if (effectivePath.isEmpty()) libraryName else effectivePath.substringAfterLast('/')
        val backLink = if (effectivePath.isNotEmpty()) {
            val parentPath = effectivePath.substringBeforeLast('/', "")
            "<li><a href=\"/browse?path=${encodePath(parentPath)}&sort=${sortMode.param}\">&larr; ..</a></li>"
        } else {
            ""
        }
        val folderItems = subfolders.joinToString("\n") { folderName ->
            val childPath = if (effectivePath.isEmpty()) folderName else "$effectivePath/$folderName"
            "<li><a href=\"/browse?path=${encodePath(childPath)}&sort=${sortMode.param}\">&#128193; ${escapeHtml(folderName)}</a></li>"
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
                <img src="/thumbnail?id=${entry.id}" loading="lazy" alt="">
                <span>${escapeHtml(entry.name)}</span>
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
                body { margin: 0; padding: 24px; background: #111; color: #eee; font-family: sans-serif; }
                h1 { font-size: 20px; }
                .bar { margin: 0 0 16px; font-size: 13px; }
                .bar .label { color: #888; margin-right: 8px; }
                .bar a, .bar .active { margin-right: 12px; text-decoration: none; }
                .bar a { color: #9cf; }
                .bar .active { color: #fff; font-weight: bold; }
                ul.folders { list-style: none; padding: 0; margin: 0 0 16px; }
                ul.folders li { margin: 4px 0; }
                ul.folders a { display: block; padding: 12px 16px; background: #222; color: #fff; text-decoration: none; border-radius: 8px; }
                ul.folders a:hover { background: #333; }
                ul.videos { list-style: none; padding: 0; margin: 0; display: grid; grid-template-columns: repeat(auto-fill, minmax(150px, 1fr)); gap: 12px; }
                ul.videos a { display: flex; flex-direction: column; background: #222; color: #fff; text-decoration: none; border-radius: 8px; overflow: hidden; }
                ul.videos a:hover { background: #333; }
                ul.videos img { width: 100%; aspect-ratio: 16 / 9; object-fit: cover; background: #000; }
                ul.videos span { padding: 8px; font-size: 13px; word-break: break-word; }
                ul.videos small { padding: 0 8px 8px; margin-top: -8px; color: #888; font-size: 11px; word-break: break-word; }
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
            </body>
            </html>
        """.trimIndent()
        return newFixedLengthResponse(Response.Status.OK, "text/html", html)
    }

    private fun watchPage(entry: VideoEntry, sortMode: SortMode, flat: Boolean): Response {
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
            "<a class=\"back\" href=\"/browse?path=${encodePath(backPath)}&sort=${sortMode.param}&flat=${if (flat) "1" else "0"}\">&larr; Back</a>"
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
        val prevOverlayButton = if (showPlaylist) {
            "<button type=\"button\" id=\"prevBtn\" class=\"navOverlayBtn navPrev\" title=\"Previous video\">&#8249;</button>"
        } else {
            ""
        }
        val nextOverlayButton = if (showPlaylist) {
            "<button type=\"button\" id=\"nextBtn\" class=\"navOverlayBtn navNext\" title=\"Next video\">&#8250;</button>"
        } else {
            ""
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
                html, body { margin: 0; height: 100%; background: #111; color: #eee; font-family: sans-serif; }
                body { display: flex; flex-direction: column; }
                .topbar { display: flex; align-items: center; gap: 16px; padding: 10px 16px; flex-shrink: 0; flex-wrap: wrap; }
                .back { color: #9cf; text-decoration: none; flex-shrink: 0; }
                #currentTitle { font-size: 14px; color: #ccc; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
                .controls { display: flex; gap: 12px; align-items: center; margin-left: auto; }
                .toggle { display: flex; align-items: center; gap: 4px; font-size: 12px; color: #ccc; white-space: nowrap; }
                .main { flex: 1; display: flex; min-height: 0; }
                .player { flex: 1; position: relative; background: #000; min-width: 0; }
                .video-js { width: 100%; height: 100%; }
                .video-js .vjs-tech { object-fit: contain; }
                .navOverlayBtn {
                  position: absolute;
                  top: 50%;
                  transform: translateY(-50%);
                  width: 44px;
                  height: 44px;
                  border-radius: 50%;
                  border: none;
                  background: rgba(0, 0, 0, 0.45);
                  color: #fff;
                  font-size: 24px;
                  line-height: 1;
                  cursor: pointer;
                  display: flex;
                  align-items: center;
                  justify-content: center;
                  z-index: 2;
                }
                .navOverlayBtn:hover:not(:disabled) { background: rgba(0, 0, 0, 0.7); }
                .navOverlayBtn:disabled { opacity: 0.25; cursor: default; }
                .navPrev { left: 12px; }
                .navNext { right: 12px; }
                .playlist { width: 280px; flex-shrink: 0; overflow-y: auto; border-left: 1px solid #222; list-style: none; margin: 0; padding: 0; }
                .playlist li { display: flex; gap: 8px; align-items: center; padding: 8px; cursor: pointer; }
                .playlist li:hover { background: #1a1a1a; }
                .playlist li.active { background: #232323; box-shadow: inset 3px 0 0 #9cf; }
                .playlist img { width: 72px; aspect-ratio: 16 / 9; object-fit: cover; background: #000; border-radius: 4px; flex-shrink: 0; }
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
                  $prevOverlayButton
                  <video id="player" class="video-js vjs-big-play-centered" controls preload="auto" poster="/thumbnail?id=${entry.id}">
                    <source src="/video?id=${entry.id}" type="${guessVideoMimeType(entry.name)}">
                  </video>
                  $nextOverlayButton
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
                var prevBtn = document.getElementById('prevBtn');
                var nextBtn = document.getElementById('nextBtn');
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
              })();
              </script>
            </body>
            </html>
        """.trimIndent()
        return newFixedLengthResponse(Response.Status.OK, "text/html", html)
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

        val stream = ClosingFileInputStream(pfd)
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
