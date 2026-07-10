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
  a simple web page listing every video file found in it (recursively),
  so a viewer picks which one to watch before playback starts.
- **Stay alive**: the server runs inside a foreground `Service`, so
  streaming keeps going even if you switch away from the app (the
  notification shows the URL and has a Stop action).
- **Watch**: the app also has a built-in **Watch a Stream** screen — type
  in the hosting device's address and it opens that device's page in an
  embedded browser, so you don't need a separate laptop/browser to view
  a stream; another copy of this app works as the viewer too.

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
3. Grant the notification permission if prompted (Android 13+).
4. Tap **Start Streaming**. The screen shows a URL like
   `http://192.168.1.23:8080`.
5. On another device connected to the same Wi-Fi (or the browser field
   above, or VLC's "Open Network Stream"), open that URL. For a folder,
   you'll see a list of videos to pick from first; for a single file, it
   starts playing immediately.

### Watching from this app

1. Tap **Watch a Stream**.
2. Enter the address shown on the hosting device (just the IP, e.g.
   `192.168.1.23`, or the full URL) and tap **Connect**.
3. The host's page loads in an embedded browser — pick a video from the
   list (folder mode) or it starts playing right away (single-file mode).

## Project layout

```
app/src/main/java/com/videostream/local/
  MainActivity.kt        Landing screen: choose Host or Watch
  HostActivity.kt         Host UI: file/folder picker, start/stop, notification permission
  WatchActivity.kt        Viewer UI: address input + embedded WebView browser
  StreamingService.kt      Foreground service hosting the HTTP server; scans folders for videos
  MediaHttpServer.kt      NanoHTTPD server; serves a video list page and byte-range video streaming
  VideoEntry.kt            One playable video (id, display name, content Uri)
  NetworkUtils.kt          Finds the device's local IPv4 address
```

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
- The in-app **Watch a Stream** screen has no encryption/authentication
  either (it's a plain embedded browser over HTTP), matching the host's
  own local-network-only design.
