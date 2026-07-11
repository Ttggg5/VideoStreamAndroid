package com.videostream.local

import android.content.ContentResolver
import android.content.res.AssetManager
import android.net.Uri
import java.net.HttpURLConnection
import java.net.URL
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock

/**
 * Exercises [MediaHttpServer] over a real (loopback, ephemeral-port) HTTP connection —
 * NanoHTTPD itself has no Android dependency, so this runs as a plain JVM test. Only
 * [ContentResolver], [AssetManager], and [Uri] are Android framework types here, and
 * they're mocked with Mockito rather than touched for real, so no device/emulator is
 * needed to check the server's routing, sorting, folder navigation, and HTML escaping.
 */
class MediaHttpServerTest {

    private lateinit var contentResolver: ContentResolver
    private lateinit var assetManager: AssetManager
    private var server: MediaHttpServer? = null

    @Before
    fun setUp() {
        contentResolver = mock(ContentResolver::class.java)
        assetManager = mock(AssetManager::class.java)
    }

    @After
    fun tearDown() {
        server?.stop()
    }

    private fun fakeUri(): Uri = mock(Uri::class.java)

    private fun startServer(
        entries: List<VideoEntry>,
        isFolderMode: Boolean,
        libraryName: String = "Library",
        defaultSort: String = "name"
    ): MediaHttpServer {
        val httpServer = MediaHttpServer(0, contentResolver, assetManager, entries, libraryName, isFolderMode, defaultSort)
        httpServer.start(5000, false)
        server = httpServer
        return httpServer
    }

    /** GETs [path] from [server] and returns (status code, response body). */
    private fun get(server: MediaHttpServer, path: String): Pair<Int, String> {
        val connection = URL("http://127.0.0.1:${server.listeningPort}$path").openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        connection.disconnect()
        return code to body
    }

    /** GETs [path] and returns its `Cache-Control` response header, or null if absent. */
    private fun getCacheControlHeader(server: MediaHttpServer, path: String): String? {
        val connection = URL("http://127.0.0.1:${server.listeningPort}$path").openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.responseCode
        val header = connection.getHeaderField("Cache-Control")
        connection.disconnect()
        return header
    }

    @Test
    fun `video names containing HTML are escaped rather than injected as markup`() {
        val entries = listOf(
            VideoEntry(id = 1, name = "<script>alert(1)</script>.mp4", folderPath = "", uri = fakeUri())
        )
        val httpServer = startServer(entries, isFolderMode = true)

        val (code, body) = get(httpServer, "/browse")

        assertEquals(200, code)
        assertFalse("raw <script> tag must not appear unescaped", body.contains("<script>alert"))
        assertTrue("escaped name should be present instead", body.contains("&lt;script&gt;"))
    }

    @Test
    fun `sort by size orders videos largest first`() {
        val entries = listOf(
            VideoEntry(id = 1, name = "small.mp4", folderPath = "", uri = fakeUri(), sizeBytes = 100),
            VideoEntry(id = 2, name = "large.mp4", folderPath = "", uri = fakeUri(), sizeBytes = 900),
            VideoEntry(id = 3, name = "medium.mp4", folderPath = "", uri = fakeUri(), sizeBytes = 500)
        )
        val httpServer = startServer(entries, isFolderMode = true)

        val (code, body) = get(httpServer, "/browse?sort=size")

        assertEquals(200, code)
        val largeIndex = body.indexOf("large.mp4")
        val mediumIndex = body.indexOf("medium.mp4")
        val smallIndex = body.indexOf("small.mp4")
        assertTrue("large should be listed before medium", largeIndex in 0 until mediumIndex)
        assertTrue("medium should be listed before small", mediumIndex in 0 until smallIndex)
    }

    @Test
    fun `folder navigation shows subfolders at root and their contents one level in`() {
        val entries = listOf(
            VideoEntry(id = 1, name = "root.mp4", folderPath = "", uri = fakeUri()),
            VideoEntry(id = 2, name = "nested.mp4", folderPath = "Movies", uri = fakeUri())
        )
        val httpServer = startServer(entries, isFolderMode = true)

        val (rootCode, rootBody) = get(httpServer, "/browse")
        assertEquals(200, rootCode)
        assertTrue(rootBody.contains("root.mp4"))
        assertTrue(rootBody.contains("Movies"))
        assertFalse("nested video shouldn't show until its folder is opened", rootBody.contains("nested.mp4"))

        val (folderCode, folderBody) = get(httpServer, "/browse?path=Movies")
        assertEquals(200, folderCode)
        assertTrue(folderBody.contains("nested.mp4"))
    }

    @Test
    fun `single file mode serves the player directly at root`() {
        val entries = listOf(VideoEntry(id = 1, name = "only.mp4", folderPath = "", uri = fakeUri()))
        val httpServer = startServer(entries, isFolderMode = false)

        val (code, body) = get(httpServer, "/")

        assertEquals(200, code)
        assertTrue(body.contains("only.mp4"))
        assertTrue("player should use the video.js player", body.contains("video-js"))
    }

    @Test
    fun `watch with an unknown id returns 404`() {
        val entries = listOf(VideoEntry(id = 1, name = "only.mp4", folderPath = "", uri = fakeUri()))
        val httpServer = startServer(entries, isFolderMode = false)

        val (code, _) = get(httpServer, "/watch?id=999")

        assertEquals(404, code)
    }

    @Test
    fun `video route fails gracefully when the file can't be opened`() {
        val entries = listOf(VideoEntry(id = 1, name = "only.mp4", folderPath = "", uri = fakeUri()))
        val httpServer = startServer(entries, isFolderMode = false)

        // contentResolver is an unstubbed mock, so openFileDescriptor() returns null here —
        // this is exactly the "file went away / unreadable" case the server has to handle
        // without crashing.
        val (code, body) = get(httpServer, "/video?id=1")

        assertEquals(500, code)
        assertTrue(body.contains("Cannot open video file"))
    }

    private fun post(server: MediaHttpServer, path: String): Pair<Int, String> {
        val connection = URL("http://127.0.0.1:${server.listeningPort}$path").openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        connection.disconnect()
        return code to body
    }

    @Test
    fun `remote state starts out unselected`() {
        val entries = listOf(VideoEntry(id = 1, name = "only.mp4", folderPath = "", uri = fakeUri()))
        val httpServer = startServer(entries, isFolderMode = true)

        val (code, body) = get(httpServer, "/remote/state")

        assertEquals(200, code)
        assertTrue(body.contains("\"videoId\":null"))
    }

    @Test
    fun `selecting a video via remote updates the polled state`() {
        val entries = listOf(
            VideoEntry(id = 1, name = "one.mp4", folderPath = "", uri = fakeUri()),
            VideoEntry(id = 2, name = "two.mp4", folderPath = "", uri = fakeUri())
        )
        val httpServer = startServer(entries, isFolderMode = true)

        val (selectCode, _) = post(httpServer, "/remote/select?id=2")
        assertEquals(200, selectCode)

        val (code, body) = get(httpServer, "/remote/state")
        assertEquals(200, code)
        assertTrue(body.contains("\"videoId\":2"))
    }

    @Test
    fun `selecting an unknown video via remote returns 404 and leaves state untouched`() {
        val entries = listOf(VideoEntry(id = 1, name = "only.mp4", folderPath = "", uri = fakeUri()))
        val httpServer = startServer(entries, isFolderMode = true)

        val (selectCode, _) = post(httpServer, "/remote/select?id=999")
        assertEquals(404, selectCode)

        val (_, body) = get(httpServer, "/remote/state")
        assertTrue(body.contains("\"videoId\":null"))
    }

    @Test
    fun `remote page lists videos as selectable cards rather than player links`() {
        val entries = listOf(
            VideoEntry(id = 1, name = "<b>weird</b>.mp4", folderPath = "", uri = fakeUri())
        )
        val httpServer = startServer(entries, isFolderMode = true)

        val (code, body) = get(httpServer, "/remote")

        assertEquals(200, code)
        assertTrue(body.contains("data-id=\"1\""))
        assertFalse("remote cards shouldn't link straight into the player", body.contains("/watch?id=1"))
        assertFalse("raw markup must not appear unescaped", body.contains("<b>weird</b>.mp4"))
    }

    @Test
    fun `remote is unavailable in single file mode`() {
        val entries = listOf(VideoEntry(id = 1, name = "only.mp4", folderPath = "", uri = fakeUri()))
        val httpServer = startServer(entries, isFolderMode = false)

        val (code, _) = get(httpServer, "/remote")

        assertEquals(404, code)
    }

    @Test
    fun `remote page shows a placeholder until a video is selected, then an embedded player`() {
        val entries = listOf(VideoEntry(id = 1, name = "only.mp4", folderPath = "", uri = fakeUri()))
        val httpServer = startServer(entries, isFolderMode = true)

        val (_, beforeBody) = get(httpServer, "/remote")
        assertTrue("no video selected yet, so no player should be embedded", !beforeBody.contains("id=\"player\""))

        post(httpServer, "/remote/select?id=1")
        val (_, afterBody) = get(httpServer, "/remote")
        assertTrue("selecting a video should embed a controllable player", afterBody.contains("id=\"player\""))
    }

    @Test
    fun `remote command requires a video to already be selected`() {
        val entries = listOf(VideoEntry(id = 1, name = "only.mp4", folderPath = "", uri = fakeUri()))
        val httpServer = startServer(entries, isFolderMode = true)

        val (code, _) = post(httpServer, "/remote/command?action=play")

        assertEquals(400, code)
    }

    @Test
    fun `remote play and pause commands toggle playing state and bump playRevision`() {
        val entries = listOf(VideoEntry(id = 1, name = "only.mp4", folderPath = "", uri = fakeUri()))
        val httpServer = startServer(entries, isFolderMode = true)
        post(httpServer, "/remote/select?id=1")
        val (_, afterSelect) = get(httpServer, "/remote/state")
        assertTrue(afterSelect.contains("\"playing\":true"))

        val (pauseCode, _) = post(httpServer, "/remote/command?action=pause")
        assertEquals(200, pauseCode)
        val (_, afterPause) = get(httpServer, "/remote/state")
        assertTrue(afterPause.contains("\"playing\":false"))
        assertFalse("pausing should bump playRevision", afterPause.contains("\"playRevision\":0"))

        val (playCode, _) = post(httpServer, "/remote/command?action=play")
        assertEquals(200, playCode)
        val (_, afterPlay) = get(httpServer, "/remote/state")
        assertTrue(afterPlay.contains("\"playing\":true"))
    }

    @Test
    fun `remote seek command updates seekSeconds and bumps seekRevision`() {
        val entries = listOf(VideoEntry(id = 1, name = "only.mp4", folderPath = "", uri = fakeUri()))
        val httpServer = startServer(entries, isFolderMode = true)
        post(httpServer, "/remote/select?id=1")

        val (seekCode, _) = post(httpServer, "/remote/command?action=seek&position=42.5")

        assertEquals(200, seekCode)
        val (_, body) = get(httpServer, "/remote/state")
        assertTrue(body.contains("\"seekSeconds\":42.5"))
        assertFalse("seeking should bump seekRevision", body.contains("\"seekRevision\":0"))
    }

    /** Extracts the `positionSeconds` value from a `/remote/state`-shaped JSON body. */
    private fun positionSecondsOf(body: String): Double {
        val match = Regex("\"positionSeconds\":([0-9.]+)").find(body)
            ?: throw AssertionError("no positionSeconds field in: $body")
        return match.groupValues[1].toDouble()
    }

    @Test
    fun `positionSeconds freezes at the seeked spot once paused`() {
        // positionSeconds keeps advancing with wall-clock time while playing (it's an
        // extrapolation of elapsed real time, not a fixed value), so this only asserts it's
        // close to the seeked position rather than exactly equal — small scheduling/network
        // delays between the seek and pause commands are expected and harmless.
        val entries = listOf(VideoEntry(id = 1, name = "only.mp4", folderPath = "", uri = fakeUri()))
        val httpServer = startServer(entries, isFolderMode = true)
        post(httpServer, "/remote/select?id=1")

        post(httpServer, "/remote/command?action=seek&position=100")
        post(httpServer, "/remote/command?action=pause")

        val (_, afterPause) = get(httpServer, "/remote/state")
        val positionAfterPause = positionSecondsOf(afterPause)
        assertEquals(
            "pausing right after a seek should freeze positionSeconds near the seeked spot",
            100.0, positionAfterPause, 2.0
        )

        // Frozen while paused means it must not keep drifting forward on its own.
        val (_, later) = get(httpServer, "/remote/state")
        assertEquals(positionAfterPause, positionSecondsOf(later), 0.0)
    }

    @Test
    fun `a freshly selected video starts at positionSeconds roughly zero`() {
        val entries = listOf(VideoEntry(id = 1, name = "only.mp4", folderPath = "", uri = fakeUri()))
        val httpServer = startServer(entries, isFolderMode = true)

        post(httpServer, "/remote/select?id=1")

        val (_, body) = get(httpServer, "/remote/state")
        assertEquals(0.0, positionSecondsOf(body), 2.0)
    }

    @Test
    fun `remote command with an unknown action is rejected`() {
        val entries = listOf(VideoEntry(id = 1, name = "only.mp4", folderPath = "", uri = fakeUri()))
        val httpServer = startServer(entries, isFolderMode = true)
        post(httpServer, "/remote/select?id=1")

        val (code, _) = post(httpServer, "/remote/command?action=nonsense")

        assertEquals(400, code)
    }

    @Test
    fun `following a remote pick lands on a bare player with no controls`() {
        val entries = listOf(VideoEntry(id = 1, name = "only.mp4", folderPath = "", uri = fakeUri()))
        val httpServer = startServer(entries, isFolderMode = true)

        val (code, body) = get(httpServer, "/watch?id=1&remote=1")

        assertEquals(200, code)
        assertTrue("bare player still needs a video element", body.contains("id=\"player\""))
        assertTrue("bare player should disable video.js's own controls", body.contains("controls: false"))
        assertFalse("bare player shouldn't offer a way back to browsing", body.contains("class=\"back\""))
        assertFalse("bare player shouldn't show a playlist", body.contains("id=\"playlist\""))
    }

    @Test
    fun `bare player offers a muted-fallback unmute button for when autoplay-with-sound is blocked`() {
        val entries = listOf(VideoEntry(id = 1, name = "only.mp4", folderPath = "", uri = fakeUri()))
        val httpServer = startServer(entries, isFolderMode = true)

        val (_, body) = get(httpServer, "/watch?id=1&remote=1")

        assertTrue("needs a fallback control to recover sound after a forced mute", body.contains("id=\"unmuteButton\""))
        assertTrue("should attempt playback through a helper that can retry muted", body.contains("attemptPlay"))
    }

    @Test
    fun `a normal watch page still has its own controls when not following a remote`() {
        val entries = listOf(VideoEntry(id = 1, name = "only.mp4", folderPath = "", uri = fakeUri()))
        val httpServer = startServer(entries, isFolderMode = true)

        val (code, body) = get(httpServer, "/watch?id=1")

        assertEquals(200, code)
        assertTrue(body.contains("controls preload"))
    }

    @Test
    fun `remote page hides the video picture behind a plain button-and-scrubbar control panel`() {
        val entries = listOf(VideoEntry(id = 1, name = "only.mp4", folderPath = "", uri = fakeUri()))
        val httpServer = startServer(entries, isFolderMode = true)
        post(httpServer, "/remote/select?id=1")

        val (_, body) = get(httpServer, "/remote")

        assertTrue("host player should stay muted rather than play audio itself", body.contains("muted"))
        assertTrue("video picture itself should be hidden, not shown", body.contains("class=\"hiddenVideo\""))
        assertTrue("play/pause should be a plain button, not an embedded player's own UI", body.contains("id=\"playPauseButton\""))
        assertTrue("scrubbing should be a plain range input, not an embedded player's own UI", body.contains("type=\"range\""))
        assertTrue("the currently playing video's name should be surfaced", body.contains("nowPlayingTitle"))
        assertFalse(
            "the control panel shouldn't pull in a video player library at all anymore",
            body.contains("videojs(")
        )
    }

    @Test
    fun `remote page offers an exit control only once a video is selected`() {
        val entries = listOf(VideoEntry(id = 1, name = "only.mp4", folderPath = "", uri = fakeUri()))
        val httpServer = startServer(entries, isFolderMode = true)

        // Note: the page's script always references getElementById('exitRemoteButton') (single
        // quotes) so it can no-op when there's nothing to attach to — only the actual button
        // element (id="..." in double quotes) indicates whether one was rendered.
        val (_, beforeBody) = get(httpServer, "/remote")
        assertFalse("nothing to exit yet with no video selected", beforeBody.contains("id=\"exitRemoteButton\""))

        post(httpServer, "/remote/select?id=1")
        val (_, afterBody) = get(httpServer, "/remote")
        assertTrue("exit control should appear once something's selected", afterBody.contains("id=\"exitRemoteButton\""))
    }

    @Test
    fun `clearing the remote selection resets state back to unselected`() {
        val entries = listOf(VideoEntry(id = 1, name = "only.mp4", folderPath = "", uri = fakeUri()))
        val httpServer = startServer(entries, isFolderMode = true)
        post(httpServer, "/remote/select?id=1")

        val (clearCode, _) = post(httpServer, "/remote/clear")

        assertEquals(200, clearCode)
        val (_, body) = get(httpServer, "/remote/state")
        assertTrue(body.contains("\"videoId\":null"))
    }

    @Test
    fun `bare player hands control back to a normal watch page once the remote is cleared`() {
        val entries = listOf(VideoEntry(id = 1, name = "only.mp4", folderPath = "", uri = fakeUri()))
        val httpServer = startServer(entries, isFolderMode = true)

        val (code, body) = get(httpServer, "/watch?id=1&remote=1")

        assertEquals(200, code)
        assertTrue(
            "bare player should fall back to the normal watch page once state.videoId goes null",
            body.contains("location.href = '/watch?id=' + currentId;")
        )
    }

    @Test
    fun `api info reports library name, folder mode, and default sort`() {
        val entries = listOf(VideoEntry(id = 1, name = "only.mp4", folderPath = "", uri = fakeUri()))
        val httpServer = startServer(entries, isFolderMode = true, libraryName = "My Library", defaultSort = "date")

        val (code, body) = get(httpServer, "/api/info")

        assertEquals(200, code)
        assertTrue(body.contains("\"libraryName\":\"My Library\""))
        assertTrue(body.contains("\"isFolderMode\":true"))
        assertTrue(body.contains("\"defaultSort\":\"date\""))
    }

    @Test
    fun `api browse returns videos and subfolders as json instead of html`() {
        val entries = listOf(
            VideoEntry(id = 1, name = "root.mp4", folderPath = "", uri = fakeUri(), sizeBytes = 42, lastModified = 1000),
            VideoEntry(id = 2, name = "nested.mp4", folderPath = "Movies", uri = fakeUri())
        )
        val httpServer = startServer(entries, isFolderMode = true)

        val (rootCode, rootBody) = get(httpServer, "/api/browse")
        assertEquals(200, rootCode)
        assertTrue(rootBody.contains("\"id\":1"))
        assertTrue(rootBody.contains("\"name\":\"root.mp4\""))
        assertTrue(rootBody.contains("\"sizeBytes\":42"))
        assertTrue(rootBody.contains("\"lastModified\":1000"))
        assertTrue(rootBody.contains("\"type\":\"video/mp4\""))
        assertTrue(rootBody.contains("\"subfolders\":[\"Movies\"]"))
        assertFalse("nested video shouldn't show until its folder is opened", rootBody.contains("nested.mp4"))

        val (folderCode, folderBody) = get(httpServer, "/api/browse?path=Movies")
        assertEquals(200, folderCode)
        assertTrue(folderBody.contains("\"name\":\"nested.mp4\""))
    }

    @Test
    fun `api browse escapes video and folder names for safe json`() {
        val entries = listOf(
            VideoEntry(id = 1, name = "quote\"video.mp4", folderPath = "", uri = fakeUri())
        )
        val httpServer = startServer(entries, isFolderMode = true)

        val (code, body) = get(httpServer, "/api/browse")

        assertEquals(200, code)
        assertTrue(body.contains("quote\\\"video.mp4"))
    }

    @Test
    fun `api browse 404s for an unknown path`() {
        val entries = listOf(VideoEntry(id = 1, name = "only.mp4", folderPath = "", uri = fakeUri()))
        val httpServer = startServer(entries, isFolderMode = true)

        val (code, _) = get(httpServer, "/api/browse?path=DoesNotExist")

        assertEquals(404, code)
    }

    @Test
    fun `api video returns metadata for a single video by id regardless of folder`() {
        val entries = listOf(
            VideoEntry(id = 1, name = "root.mp4", folderPath = "", uri = fakeUri()),
            VideoEntry(id = 2, name = "nested.mkv", folderPath = "Movies", uri = fakeUri())
        )
        val httpServer = startServer(entries, isFolderMode = true)

        val (code, body) = get(httpServer, "/api/video?id=2")

        assertEquals(200, code)
        assertTrue(body.contains("\"name\":\"nested.mkv\""))
        assertTrue(body.contains("\"folderPath\":\"Movies\""))
        assertTrue(body.contains("\"type\":\"video/x-matroska\""))
    }

    @Test
    fun `api video 404s for an unknown id`() {
        val entries = listOf(VideoEntry(id = 1, name = "only.mp4", folderPath = "", uri = fakeUri()))
        val httpServer = startServer(entries, isFolderMode = true)

        val (code, _) = get(httpServer, "/api/video?id=999")

        assertEquals(404, code)
    }

    @Test
    fun `remote, browse, watch, and bare-follow pages are never cached`() {
        // A cached copy of any of these is exactly what would make a reloaded page (e.g. the
        // control panel right after selecting a video) show stale state instead of the real
        // current pick — see MediaHttpServer's Cache-Control comment on each of them.
        val entries = listOf(VideoEntry(id = 1, name = "only.mp4", folderPath = "", uri = fakeUri()))
        val httpServer = startServer(entries, isFolderMode = true)
        post(httpServer, "/remote/select?id=1")

        assertEquals("no-store", getCacheControlHeader(httpServer, "/remote"))
        assertEquals("no-store", getCacheControlHeader(httpServer, "/browse"))
        assertEquals("no-store", getCacheControlHeader(httpServer, "/watch?id=1"))
        assertEquals("no-store", getCacheControlHeader(httpServer, "/watch?id=1&remote=1"))
    }
}
