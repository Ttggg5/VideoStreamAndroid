# VideoStreamAndroid

An Android app that streams the phone's camera to any device on the same
local network (Wi-Fi/LAN) — open a URL in a browser on your laptop, TV, or
another phone and watch the live feed. No internet, cloud account, or
external server required.

## How it works

- **Capture**: [CameraX](https://developer.android.com/training/camerax) grabs
  frames from the back camera.
- **Encode**: each frame is converted to a JPEG image.
- **Serve**: an embedded HTTP server ([NanoHTTPD](https://github.com/NanoHttpd/nanohttpd))
  serves those JPEGs as an MJPEG (`multipart/x-mixed-replace`) stream at
  `http://<phone-ip>:8080/stream`, and a tiny HTML page at `/` that displays it.
- **Stay alive**: capture and serving run inside a foreground `Service`, so
  streaming keeps going even if you switch away from the app (the
  notification shows the URL and has a Stop action).

Any modern browser can play an MJPEG stream directly with an `<img>` tag —
no app or plugin needed on the viewing device.

## Requirements

- Android Studio (Koala or newer) with an Android SDK installed.
- A physical Android device running Android 7.0 (API 24) or newer with a
  camera — an emulator's virtual camera will also work for testing the
  server, but a real device is recommended.
- The streaming device and the viewing device must be on the **same**
  Wi-Fi network.

## Running it

1. Open this project's root folder in Android Studio and let it sync
   (it will download the Android Gradle Plugin, Kotlin, CameraX, and
   NanoHTTPD the first time, so an internet connection is needed for that
   initial sync).
2. Run the app on a device.
3. Grant the camera permission (and notification permission on Android 13+)
   when prompted.
4. Tap **Start Streaming**. The screen shows a URL like
   `http://192.168.1.23:8080`.
5. On another device connected to the same Wi-Fi, open that URL in a
   browser.

## Project layout

```
app/src/main/java/com/videostream/local/
  MainActivity.kt        UI: camera preview, start/stop, permissions
  StreamingService.kt    Foreground service: CameraX capture + JPEG encoding
  MjpegHttpServer.kt     NanoHTTPD server serving the MJPEG stream
  NetworkUtils.kt        Finds the device's local IPv4 address
```

## Known limitations

- The port (8080) isn't configurable from the UI yet.
- Only one viewer connection is exercised in testing; NanoHTTPD can serve
  multiple concurrent viewers, but expect increased CPU/battery use with
  more of them.
- No authentication — anyone on the same LAN can open the stream URL.
