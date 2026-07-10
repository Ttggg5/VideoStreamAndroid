# VideoStreamAndroid

![Android CI/CD](https://github.com/Ttggg5/VideoStreamAndroid/actions/workflows/android-ci-cd.yml/badge.svg)

An Android app that streams a video file already stored on the phone to
any device on the same local network (Wi-Fi/LAN) — open a URL in a
browser on your laptop, TV, or another phone and start watching, without
waiting for the whole file to download first. No internet, cloud account,
or external server required.

## How it works

- **Pick**: the system document picker (`ACTION_OPEN_DOCUMENT`) is used to
  choose any video file on the device — no broad storage permission needed.
- **Serve**: an embedded HTTP server ([NanoHTTPD](https://github.com/NanoHttpd/nanohttpd))
  serves that file at `http://<phone-ip>:8080/video`, honoring HTTP
  `Range` requests (`Accept-Ranges: bytes` / `206 Partial Content`), so a
  browser's `<video>` tag or a media player can start playing immediately
  and seek around without re-downloading — this is progressive streaming,
  not a full download.
- **Stay alive**: the server runs inside a foreground `Service`, so
  streaming keeps going even if you switch away from the app (the
  notification shows the URL and has a Stop action).

Any modern browser plays the stream directly with a `<video>` tag —
no app or plugin needed on the viewing device. Desktop media players like
VLC can also open the `http://<ip>:8080/video` URL directly.

## Requirements

- Android Studio (Koala or newer) with an Android SDK installed.
- A physical Android device or emulator running Android 7.0 (API 24) or
  newer.
- The streaming device and the viewing device must be on the **same**
  Wi-Fi network.

## Running it

1. Open this project's root folder in Android Studio and let it sync
   (it will download the Android Gradle Plugin, Kotlin, and NanoHTTPD the
   first time, so an internet connection is needed for that initial sync).
2. Run the app on a device.
3. Tap **Choose Video File** and pick a video from device storage.
4. Grant the notification permission if prompted (Android 13+).
5. Tap **Start Streaming**. The screen shows a URL like
   `http://192.168.1.23:8080`.
6. On another device connected to the same Wi-Fi, open that URL in a
   browser (or paste it into VLC's "Open Network Stream").

## Project layout

```
app/src/main/java/com/videostream/local/
  MainActivity.kt          UI: file picker, start/stop, notification permission
  StreamingService.kt      Foreground service hosting the HTTP server
  VideoFileHttpServer.kt   NanoHTTPD server; serves the file with byte-range support
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
- One video at a time — starting a new stream replaces the previous one.
- No authentication — anyone on the same LAN can open the stream URL.
- Some cloud-backed "virtual" documents (e.g. certain Google Drive/Photos
  entries) don't expose a normal file descriptor and won't be servable;
  pick a file that's actually stored on the device.
