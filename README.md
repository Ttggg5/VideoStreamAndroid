# VideoStreamAndroid

![Android CI/CD](https://github.com/Ttggg5/VideoStreamAndroid/actions/workflows/android-ci-cd.yml/badge.svg)

An Android app that streams video already stored on the phone to any
device on the same local network (Wi-Fi/LAN) — open a URL in a browser
on your laptop, TV, or another phone and start watching, without waiting
for the whole file to download first. No internet, cloud account, or
external server required. The app can also act as the **viewer** itself,
with a built-in screen to open another device's stream.

## How it works

- **Host**: pick a single video file, or a whole **folder** of videos, via
  the system document picker (`ACTION_OPEN_DOCUMENT` / `ACTION_OPEN_DOCUMENT_TREE`)
  — no broad storage permission needed.
- **Serve**: an embedded HTTP server ([NanoHTTPD](https://github.com/NanoHttpd/nanohttpd))
  serves the video at `http://<phone-ip>:<port>/video` (port `8080` by
  default, changeable in Settings), honoring HTTP
  `Range` requests (`Accept-Ranges: bytes` / `206 Partial Content`), so a
  browser's `<video>` tag or a media player can start playing immediately
  and seek around without re-downloading — this is progressive streaming,
  not a full download. When a folder was chosen, the same server exposes
  a browsable web page mirroring that folder's actual structure by
  default — subfolders stay subfolders, navigable one level at a time —
  so a viewer finds and picks a video the same way they would in a file
  browser. Each video shows a thumbnail (a frame grabbed a second into
  the file via `MediaMetadataRetriever`), both in the folder browsing
  grid and as the player's poster image. A folder with more than one
  video gets a **Sort** bar (Name / Newest / Largest) that carries
  through folder navigation and back out of the player.
- **Player**: the actual player is [video.js](https://videojs.com), bundled
  under `assets/videojs/` and served from `/assets/...` so it works with
  no internet access on the viewing device, same as everything else this
  server serves — no CDN dependency. Its default grey-blue skin is
  re-themed with a CSS override in the watch page (big play button,
  control bar, progress/volume fill, menus) to match the rest of the
  app's dark, accent-colored look, rather than the stock video.js style.
- **Per-viewer controls, not host settings**: whether to flatten the
  folder view into one list, autoplay the next video, and shuffle
  playback are choices each viewer makes on the page itself — a
  **View: Folders / All videos** toggle on the browse page, and
  **Autoplay** / **Shuffle** checkboxes on the player — remembered per
  browser via `localStorage`, not something the host configures once for
  everyone. The player page shows the rest of the current folder as a
  **playlist** next to the video, with **‹ Prev** / **Next ›** buttons
  added into video.js's own control bar right next to the play button
  (inserted there by a small script after the player loads, rather than
  floating over the video); clicking another entry, pressing Prev/Next,
  letting the current one finish (autoplay), or shuffle picking one at
  random all switch to it in place (swap the player's source and call
  `.play()`) instead of reloading the page. Next follows shuffle order
  when Shuffle is on; Prev retraces actual play order (including shuffle
  jumps) and is disabled once there's nowhere earlier to go. This is all
  for the plain web page any browser gets at `/browse`/`/watch` — the
  app's own **Watch a Stream** screen doesn't use this page at all; see
  below.
- **Remote control mode**: normally each viewer browses and picks for
  themselves, but for a folder stream there's a second way to pick a
  video — `/remote` (opened via a **Remote Control** button on the Host
  screen once a folder is streaming, or by loading `/remote` from any
  other browser on the LAN) lets one device drive playback for everyone
  else, like an actual TV remote. `/remote` is a **control panel, not a
  viewing screen**: a "Now playing" card (thumbnail + title) and transport
  controls (Previous/Next, a plain play/pause button, a range-input scrub
  bar, and a **Random** toggle — no embedded video player UI of its own)
  float in a fixed panel docked to the bottom of the page, staying
  reachable while the folder browser above it is scrolled to pick
  something else. The controls drive whatever's currently picked but never
  show the video picture itself, and stay muted — the video decodes in the
  background just enough to drive a real seek bar/duration, since this
  device is controlling the stream, not watching it. Previous/Next step
  through whichever folder's videos are currently listed on screen, in the
  active sort order; with Random turned on, Next instead jumps to a
  uniformly random video from that same list. The folder browser sits
  below the floating panel; tapping a video calls `/remote/select`, and
  using the transport controls calls `/remote/command`. Every other open
  `/browse`/`/watch` page keeps a WebSocket open to `/remote/ws`, which
  pushes the current state the instant it connects and again on every
  later change (a `/remote/state` HTTP endpoint still exists as a
  non-realtime fallback) — no polling interval to wait out — and the
  moment anything's ever been picked there, hands off entirely to a bare,
  full-screen player with **no controls of its own** — it just shows
  whatever `/remote` is currently playing and follows play/pause/seek/
  video-switch commands as they arrive. An **Exit remote mode** button on
  the control panel calls
  `/remote/clear`, which sends every one of those bare viewer pages back
  to a normal watch page with its own controls restored. Until `/remote`
  is used for the first time, nothing changes for anyone — it's entirely
  additive. `RemoteControlActivity` is a thin `WebView` wrapper the host
  app uses to open its own `/remote` page without leaving the app; the
  page itself works the same from any browser.
- **Stay alive**: the server runs inside a foreground `Service`, so
  streaming keeps going even if you switch away from the app (the
  notification shows the URL and has a Stop action). A partial wake
  lock and a Wi-Fi lock are held for as long as it's running so neither
  the CPU nor the Wi-Fi radio go to sleep mid-stream, and the first time
  you start streaming you're asked to exempt the app from battery
  optimization so Android doesn't throttle it during long background
  stretches (declining is remembered — it won't ask again).
- **Watch, natively**: the app's own **Watch a Stream** screen is not a
  browser — it's native views talking to the host's JSON API
  (`/api/info`, `/api/browse`, `/api/video`, alongside the existing
  `/video`/`/thumbnail`/`/remote/ws` routes) and playing video with
  [ExoPlayer](https://developer.android.com/media/media3/exoplayer)
  (`androidx.media3`) instead of an embedded video.js page. It
  automatically finds hosts on the same local network via NSD/mDNS
  service discovery (`android.net.nsd`) — every host advertises itself
  under `_videostream._tcp.` as soon as it starts streaming — and lists
  them for a tap to connect; typing in the hosting device's address and
  tapping **Connect** still works too, for networks where discovery
  doesn't reach (see Known limitations). Once connected, a folder-mode
  host gets a native folder/video grid (thumbnails loaded over HTTP into
  a `RecyclerView`, with its own Sort spinner and Folders/All-videos
  switch); tapping a video opens a native player built on ExoPlayer's own
  playlist support, which is what actually provides Autoplay, Shuffle,
  and Prev/Next — those aren't hand-rolled here the way the web page has
  to, they're just `pauseAtEndOfMediaItems`, `shuffleModeEnabled`, and
  ExoPlayer's built-in previous/next media-item navigation. The screen
  also keeps a WebSocket (via OkHttp) open to `/remote/ws` for as long as
  it's connected: the moment a host ever makes a pick on `/remote`, this
  screen hides its own controls and follows along — same play/pause/seek/
  video-switch behavior as the bare web viewer, pushed instead of polled
  — and hands normal controls back once the host leaves remote mode.
  Another copy of this app works as the viewer too, of course, the same
  as any other browser would.
- **Rotates and adapts to bigger screens**: every screen in the app now
  supports landscape (previously locked to portrait) with a dedicated
  layout — the two Host/Watch options sit side by side instead of
  stacked, `HostActivity` splits into a picker column and a
  status/action column, and `WatchActivity`'s address/discovery panel
  becomes a capped-width side panel instead of stretching full-width. On
  tablets (`sw600dp+`) every screen also gets wider side margins instead
  of stretching content edge-to-edge. Picking a file/folder and an
  in-progress stream both survive a rotation instead of resetting; on
  `WatchActivity` specifically, rotation is handled in place (see below)
  rather than by recreating the screen, so a playing video keeps playing
  — same position, same instance — straight through the rotation instead
  of restarting.
- **Settings**: a gear icon on the landing screen opens **Accent
  color** (six presets, applied as a runtime `ThemeOverlay` to every
  screen — including the `--accent` color on the browse/watch web
  pages), **Theme** (match system / light / dark), **Streaming port**
  (default `8080`, used both when hosting and as the default port when
  connecting to an address that doesn't specify one), and **Keep screen
  on while watching**.

Any modern browser plays the stream directly with a `<video>` tag —
no app or plugin needed on the viewing device. Desktop media players like
VLC can also open the `http://<ip>:<port>/video` URL directly.

## Requirements

- Android Studio (Koala or newer) with an Android SDK installed.
- A physical Android device or emulator running Android 7.0 (API 24) or
  newer.
- The streaming device and the viewing device must be on the **same**
  local network. This works two ways:
  - Both devices joined to the same external Wi-Fi network, or
  - The streaming phone turns on its own **Wi-Fi hotspot** (Personal
    Hotspot / Portable Wi-Fi Hotspot) and the viewing device connects to
    it directly — no router or internet connection needed at all.

## Running it

1. Open this project's root folder in Android Studio and let it sync
   (it will download the Android Gradle Plugin, Kotlin, and NanoHTTPD the
   first time, so an internet connection is needed for that initial sync).
2. Run the app on a device. You'll land on a screen with two options:
   **Host a Video** and **Watch a Stream**.

### Hosting

1. Tap **Host a Video**.
2. Tap **Choose Video File** for a single video, or **Choose Folder** to
   pick a whole folder — the app scans it (including subfolders, up to
   500 videos) for playable files.
   A thumbnail preview appears once a single file is picked (folders
   don't get one, since there's no single representative frame).
   Picking a folder also reveals **Default sort** (Name / Newest /
   Largest) — sets the browse page's initial order; any viewer can still
   switch it live from the sort bar.
3. Grant the notification permission if prompted (Android 13+).
4. Tap **Start Streaming**. The screen shows a URL like
   `http://192.168.1.23:8080`.
5. On another device connected to the same Wi-Fi (or the browser field
   above, or VLC's "Open Network Stream"), open that URL. For a folder,
   you'll see it laid out just like on disk — browse into subfolders,
   tap a video to play it, tap "back" to go up a level; for a single
   file, it starts playing immediately. From there, each viewer picks
   their own experience, remembered for next time:
   - **View: Folders / All videos** on the browse page — switches
     between the folder hierarchy and one flat list of every video (each
     tagged with its original subfolder).
   - **‹ Prev** / **Next ›** buttons next to the play button in the
     player's control bar — jump to the previous or next playlist entry
     without reloading the page.
   - **Autoplay** on the player — advances to the next playlist entry
     when the current video ends.
   - **Shuffle** on the player — with Autoplay on (or when pressing
     Next), plays through the rest of the playlist in random order (no
     repeats until everything's played) instead of in sequence.

   For a folder stream, a **Remote Control** button also appears on the
   Host screen — tap it to pick what plays on every connected viewer
   yourself, instead of leaving it to each of them.

### Watching from this app

1. Tap **Watch a Stream**. The screen searches the local network for
   hosts and lists any it finds (name and address); tap the search icon
   to search again.
2. Tap a host in the list, **or** — if none show up, or you'd rather
   connect directly — enter the address shown on the hosting device
   (just the IP, e.g. `192.168.1.23`, or the full URL) and tap
   **Connect**.
3. For a folder stream, a native grid of subfolders/videos with
   thumbnails appears — tap a folder to browse into it, tap **‹** to go
   back up a level, and use the **Sort** spinner or **All videos** switch
   the same way you would on the web page. For a single-file stream,
   playback just starts.
4. Tapping a video opens the native player — **Autoplay** (a custom
   button added into the player's own control bar) and **Shuffle**
   (media3's built-in shuffle button) sit right in the control bar
   itself rather than as separate switches elsewhere on screen, and
   Prev/Next live there too. If the host ever uses **Remote Control**,
   this screen's controls disappear and it just follows along with
   whatever the host is doing, the same as any other connected viewer.

## Project layout

```
app/src/main/java/com/videostream/local/
  MainActivity.kt        Landing screen: choose Host, Watch, or Settings
  HostActivity.kt         Host UI: file/folder picker, start/stop, notification permission, Remote Control launch
  WatchActivity.kt        Viewer UI: NSD host discovery, native folder/video browsing, ExoPlayer playback, /remote-follow
  RemoteLibraryApi.kt      Blocking HTTP/JSON client for a host's /api/info, /api/browse, /api/video, /remote/state
  RemoteStateSocket.kt     OkHttp WebSocket client for a host's /remote/ws — reconnecting real-time remote-state push
  BrowseAdapter.kt         RecyclerView adapter mixing folder and video cells (same thumbnail-tile card style) for WatchActivity's browse screen
  ThumbnailLoader.kt       Loads a host's /thumbnail?id=… images into ImageViews with a small in-memory cache
  RemoteControlActivity.kt   Thin WebView wrapper around this device's own /remote page (host-side control panel)
  SettingsActivity.kt      Accent color / theme / streaming port / keep-screen-on
  BaseActivity.kt          Applies the saved accent color to every screen, recreating it if changed
  AppSettings.kt           SharedPreferences-backed store for all Settings values
  VideoStreamApplication.kt   Applies the saved light/dark mode on process start
  StreamingService.kt      Foreground service hosting the HTTP server; scans folders for videos
  MediaHttpServer.kt      NanoHTTPD server; serves the web browsing/player UI, a JSON API for native clients, and byte-range video streaming
  ThumbnailUtil.kt         Extracts a downscaled JPEG preview frame from a local video file, used by the server and HostActivity
  VideoEntry.kt            One playable video (id, display name, folder path, content Uri, size/date for sorting)
  NetworkUtils.kt          Finds the device's local IPv4 address
app/src/main/assets/videojs/
  video.min.js, video-js.min.css   Bundled video.js player (Apache-2.0), served at /assets/videojs/... for any browser (and RemoteControlActivity's /remote WebView) — not used by WatchActivity's native player
app/src/main/res/layout-land/     Landscape layouts for MainActivity, HostActivity, WatchActivity
app/src/main/res/layout/item_browse_folder.xml, item_browse_video.xml   Row/grid-cell layouts for WatchActivity's native browse screen
app/src/main/res/values(-sw600dp)/dimens.xml   screen_horizontal_margin — wider on tablets
app/src/main/res/values(-sw600dp)/integers.xml   video_grid_span_count — more columns on tablets
app/src/test/java/com/videostream/local/
  MediaHttpServerTest.kt   Starts a real MediaHttpServer on a loopback port and hits it with HTTP requests
  NetworkUtilsTest.kt      Sanity-checks getLocalIpAddress()'s return shape
```

## Testing

`app/src/test/` holds JVM unit tests, run with `gradle testDebugUnitTest`
(also part of CI, see below):

- **`MediaHttpServerTest`** starts a real `MediaHttpServer` bound to an
  ephemeral loopback port and issues actual HTTP requests against it —
  NanoHTTPD itself has no Android dependency, so this runs as a plain JVM
  test. The only Android framework types involved (`ContentResolver`,
  `AssetManager`, `Uri`) are mocked with Mockito rather than touched for
  real. Covers: HTML-escaping video/folder names (so a filename can't
  inject markup into the page), Sort ordering, folder-structure
  navigation, single-file vs. folder mode, that missing files/videos come
  back as a clean 404/500 instead of crashing the server, the `/remote`
  select/command/clear flow, and the `/api/info`, `/api/browse`,
  `/api/video` JSON endpoints `WatchActivity` (via `RemoteLibraryApi`)
  actually consumes.
- **`NetworkUtilsTest`** checks that `getLocalIpAddress()` never throws
  and that whatever it returns (if anything — this depends on the
  machine's actual network interfaces) looks like a real, non-loopback,
  non-link-local IPv4 address.

Activities and the foreground service aren't covered here — they're
thin, Android-lifecycle-heavy wrappers around the tested logic above,
and meaningfully testing them would need Robolectric or instrumented
(on-device) tests rather than plain JVM unit tests.

## Third-party

The player on `/watch` pages (served to any browser, and to
`RemoteControlActivity`'s embedded `/remote` view) is
[video.js](https://videojs.com) (Apache License 2.0), bundled directly in
the app under `app/src/main/assets/videojs/` — see `LICENSE` alongside it
— rather than loaded from a CDN, so playback doesn't depend on the
viewing device having internet access.

`WatchActivity`'s own native player uses
[ExoPlayer](https://developer.android.com/media/media3/exoplayer)
(`androidx.media3`, Apache License 2.0) via standard Gradle dependencies
rather than a bundled copy, for broader/more consistent video format
support across devices than the platform's built-in `MediaPlayer` and
built-in playlist/autoplay/shuffle support.

## CI/CD

`.github/workflows/android-ci-cd.yml` runs on GitHub Actions:

- **Every push and pull request**: runs unit tests and builds a debug APK,
  uploaded as a workflow artifact (visible on the Actions run page under
  "Artifacts") — this is the CI gate.
- **Pushing a tag matching `v*.*.*`** (e.g. `v1.0.0`): additionally builds
  a release APK and publishes it to a new [GitHub Release](../../releases)
  with the APK attached — this is the CD/publish step.

CI installs Gradle itself via `gradle/actions/setup-gradle` and runs
`gradle` directly, rather than `./gradlew`. This repo's `gradlew` /
`gradlew.bat` scripts are checked in for convenience, but the binary
`gradle-wrapper.jar` they need isn't committed — Android Studio
regenerates it automatically the first time you open the project (with
internet access), which is why CI doesn't rely on it either.

To cut a release:

```sh
git tag v1.0.0
git push origin v1.0.0
```

By default the release APK is signed with the auto-generated debug key
(installable for testing, not meant for the Play Store). To have CI sign
it with your own key instead, add these repository secrets under
**Settings → Secrets and variables → Actions**, and the workflow will pick
them up automatically — no workflow changes needed:

| Secret              | Value                                              |
|----------------------|----------------------------------------------------|
| `KEYSTORE_BASE64`    | `base64 -i your.keystore` output                    |
| `KEYSTORE_PASSWORD`  | keystore password                                   |
| `KEY_ALIAS`          | key alias                                           |
| `KEY_PASSWORD`       | key password                                        |

This repo doesn't publish to the Google Play Store — that needs a Play
Console developer account and a service-account key that only you can
provide. If you want that added later, the release job is the place to
plug in `r0adkll/upload-google-play` (or similar) once those secrets exist.

## Known limitations

- One video (or one folder) at a time — starting a new stream replaces
  the previous one.
- No authentication — anyone on the same LAN can open the stream URL,
  including `/remote`: any device that can reach it can change what
  plays on every other connected viewer.
- Folder scanning is capped at 500 videos and 6 levels deep, to keep
  startup fast on very large folders.
- Some cloud-backed "virtual" documents (e.g. certain Google Drive/Photos
  entries) don't expose a normal file descriptor and won't be servable;
  pick a file that's actually stored on the device.
- Thumbnails depend on the device's codecs being able to decode a frame;
  an unsupported codec or a corrupt file just shows no thumbnail rather
  than blocking playback.
- The in-app **Watch a Stream** screen has no encryption/authentication
  either — it's plain HTTP/JSON calls to the host, matching the host's
  own local-network-only design.
- Automatic host discovery relies on mDNS/NSD multicast traffic reaching
  both devices; some Wi-Fi hotspot implementations isolate clients from
  each other (AP/client isolation) and block it, so a discovered host
  may not always show up even when the manual address still works fine.
- Rotating the device on the **Host** screen still recreates that
  Activity (config-change recreation), but picking a file/folder and an
  in-progress stream both survive it without resetting. `WatchActivity`
  handles rotation itself instead (`android:configChanges` +
  `onConfigurationChanged`) specifically so the `ExoPlayer` instance, the
  playlist, and exact playback position are never torn down — a playing
  video keeps playing straight through a rotation rather than reloading
  from the start, the opposite of the old WebView-based viewer's behavior
  (and this app's own earlier native version).
