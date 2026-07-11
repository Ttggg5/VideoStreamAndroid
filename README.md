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
  serves the video at `http://<phone-ip>:8080/video`, honoring HTTP
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
  `.play()`) instead of reloading the page — this needs a bit of inline
  JavaScript, which is why `WatchActivity`'s embedded browser runs with
  JS enabled. Next follows shuffle order when Shuffle is on; Prev
  retraces actual play order (including shuffle jumps) and is disabled
  once there's nowhere earlier to go.
- **Stay alive**: the server runs inside a foreground `Service`, so
  streaming keeps going even if you switch away from the app (the
  notification shows the URL and has a Stop action).
- **Watch**: the app also has a built-in **Watch a Stream** screen. It
  automatically finds hosts on the same local network via NSD/mDNS
  service discovery (`android.net.nsd`) — every host advertises itself
  under `_videostream._tcp.` as soon as it starts streaming — and lists
  them for a tap to connect; typing in the hosting device's address and
  tapping **Connect** still works too, for networks where discovery
  doesn't reach (see Known limitations). Either way it opens that
  device's page in an embedded browser, so you don't need a separate
  laptop/browser to view a stream; another copy of this app works as the
  viewer too.

Any modern browser plays the stream directly with a `<video>` tag —
no app or plugin needed on the viewing device. Desktop media players like
VLC can also open the `http://<ip>:8080/video` URL directly.

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

### Watching from this app

1. Tap **Watch a Stream**. The screen searches the local network for
   hosts and lists any it finds (name and address); tap the search icon
   to search again.
2. Tap a host in the list, **or** — if none show up, or you'd rather
   connect directly — enter the address shown on the hosting device
   (just the IP, e.g. `192.168.1.23`, or the full URL) and tap
   **Connect**.
3. The host's page loads in an embedded browser — browse the folder and
   pick a video (folder mode) or it starts playing right away
   (single-file mode).

## Project layout

```
app/src/main/java/com/videostream/local/
  MainActivity.kt        Landing screen: choose Host or Watch
  HostActivity.kt         Host UI: file/folder picker, start/stop, notification permission
  WatchActivity.kt        Viewer UI: NSD host discovery + address input + embedded WebView browser
  StreamingService.kt      Foreground service hosting the HTTP server; scans folders for videos
  MediaHttpServer.kt      NanoHTTPD server; serves a folder-structured browsing UI and byte-range video streaming
  ThumbnailUtil.kt         Extracts a downscaled JPEG preview frame from a video, used by both the server and HostActivity
  VideoEntry.kt            One playable video (id, display name, folder path, content Uri, size/date for sorting)
  NetworkUtils.kt          Finds the device's local IPv4 address
app/src/main/assets/videojs/
  video.min.js, video-js.min.css   Bundled video.js player (Apache-2.0), served at /assets/videojs/...
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
  navigation, single-file vs. folder mode, and that missing
  files/videos come back as a clean 404/500 instead of crashing the
  server.
- **`NetworkUtilsTest`** checks that `getLocalIpAddress()` never throws
  and that whatever it returns (if anything — this depends on the
  machine's actual network interfaces) looks like a real, non-loopback,
  non-link-local IPv4 address.

Activities and the foreground service aren't covered here — they're
thin, Android-lifecycle-heavy wrappers around the tested logic above,
and meaningfully testing them would need Robolectric or instrumented
(on-device) tests rather than plain JVM unit tests.

## Third-party

The player on `/watch` pages is [video.js](https://videojs.com)
(Apache License 2.0), bundled directly in the app under
`app/src/main/assets/videojs/` — see `LICENSE` alongside it — rather than
loaded from a CDN, so playback doesn't depend on the viewing device
having internet access.

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

- The port (8080) isn't configurable from the UI yet.
- One video (or one folder) at a time — starting a new stream replaces
  the previous one.
- No authentication — anyone on the same LAN can open the stream URL.
- Folder scanning is capped at 500 videos and 6 levels deep, to keep
  startup fast on very large folders.
- Some cloud-backed "virtual" documents (e.g. certain Google Drive/Photos
  entries) don't expose a normal file descriptor and won't be servable;
  pick a file that's actually stored on the device.
- Thumbnails depend on the device's codecs being able to decode a frame;
  an unsupported codec or a corrupt file just shows no thumbnail rather
  than blocking playback.
- The in-app **Watch a Stream** screen has no encryption/authentication
  either (it's a plain embedded browser over HTTP), matching the host's
  own local-network-only design.
- Automatic host discovery relies on mDNS/NSD multicast traffic reaching
  both devices; some Wi-Fi hotspot implementations isolate clients from
  each other (AP/client isolation) and block it, so a discovered host
  may not always show up even when the manual address still works fine.
