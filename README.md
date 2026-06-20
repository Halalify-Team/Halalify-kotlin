# Halalify Kotlin Android

This repository contains the first confirmed working native Android build of Halalify.

The working baseline is the branch:

```text
stable/android-working-base
```

This branch successfully processed and played a YouTube video on a real Android phone and on the Android emulator. Treat it as the stable base for future work. New experiments should be built from a new branch, not directly on top of this branch.

## Goal

Halalify is a native Android app that takes a YouTube URL, downloads the required media locally on the user's device, sends only audio chunks to the backend for music removal, then rebuilds a playable video with clean audio.

The important product rule is:

```text
YouTube media download happens locally on the Android device.
Only extracted audio chunks are sent to the backend.
```

The app is not a YouTube web wrapper. It uses `yt-dlp` locally through `youtubedl-android`.

## High-Level Flow

1. The user shares or pastes a YouTube URL into the app.
2. The app reads metadata locally with `yt-dlp`.
3. The app calculates chunk plans from the video duration.
4. The app downloads source audio locally.
5. The app downloads source video locally.
6. FFmpeg cuts audio segments from the source audio.
7. Each audio segment is sent to the backend music-removal endpoint.
8. The backend returns a clean audio segment.
9. FFmpeg normalizes the clean audio.
10. FFmpeg cuts matching video segments.
11. FFmpeg muxes each video segment with the matching clean audio.
12. The app concatenates the clean audio and prepares the final playable result.
13. The user can watch the halalified result and save it to gallery.

## The Issue That Blocked Android

The app originally failed on Android with `yt-dlp` download errors. The most important errors were:

```text
yt-dlp audio chunk download failed
invalid --download-sections time range
status=403
aria2c exited with code 22
```

There were multiple root causes:

### 1. Locale-Dependent Timestamps

Android phones using Arabic locale produced Arabic-Indic digits inside `--download-sections`.

`yt-dlp` requires ASCII timestamps like:

```text
*00:00:00-00:00:10
```

but the app could produce localized digits. That caused:

```text
invalid --download-sections time range
```

The fix was to force `Locale.US` whenever formatting CLI timestamps or numbers.

### 2. Missing Native Downloader

The app initially relied on the default `yt-dlp` Python/urllib downloader. On Android this was slow and fragile with YouTube media URLs.

The fix was to bundle and initialize `aria2c` from the same `youtubedl-android` family:

```kotlin
implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")
implementation("io.github.junkfood02.youtubedl-android:aria2c:0.18.1")
```

and initialize both:

```kotlin
YoutubeDL.getInstance().init(context)
Aria2c.getInstance().init(context)
```

Downloads then use:

```text
--downloader libaria2c.so
```

### 3. YouTube 403 From `formats=missing_pot`

The biggest breakthrough was removing this extractor argument:

```text
formats=missing_pot
```

With `formats=missing_pot`, `yt-dlp` may expose formats that appear selectable but fail at download time with HTTP 403 because YouTube expects a PO token for those formats.

The working build uses normal Android/mweb clients without `formats=missing_pot`, for example:

```text
youtube:player_client=android
youtube:player_client=mweb
youtube:player_client=default,android,mweb
```

That change fixed the repeated Android 403 failures.

## Current Working yt-dlp Strategy

The app uses local `yt-dlp` through `YoutubeDLRequest`.

For metadata:

```text
--no-playlist
--skip-download
--socket-timeout 15
--retries 1
--fragment-retries 1
--extractor-retries 1
--extractor-args youtube:player_client=android,mweb
--print %(title)s
--print %(duration)s
```

For audio download attempts:

```text
-f bestaudio[ext=m4a]/bestaudio/best
--extractor-args youtube:player_client=android
--downloader libaria2c.so
```

Fallback attempts use `mweb` and `default,android,mweb`.

For video download attempts:

```text
-f 18/best[height<=480]/best
--extractor-args youtube:player_client=android
--downloader libaria2c.so
```

Fallback attempts use `mweb`, `android,mweb`, and `default,android,mweb`.

Do not reintroduce `formats=missing_pot` unless there is a very specific token strategy in place.

## APK Strategy

The universal APK became too large and also made testing painful. The working baseline uses ABI split APKs:

```kotlin
splits {
    abi {
        isEnable = true
        reset()
        include("arm64-v8a", "armeabi-v7a", "x86_64")
        isUniversalApk = false
    }
}
```

Generated debug APKs:

```text
app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
app/build/outputs/apk/debug/app-armeabi-v7a-debug.apk
app/build/outputs/apk/debug/app-x86_64-debug.apk
```

For most modern Android phones, use:

```text
app-arm64-v8a-debug.apk
```

## Build

From the repository root:

```bash
./gradlew :app:assembleDebug
```

Install on a connected ARM64 Android device:

```bash
adb install -r app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
```

For a clean test after changing native downloader behavior:

```bash
adb shell pm clear com.halalify.kotlin
```

## Emulator Verification

The first confirmed emulator test used:

```text
https://www.youtube.com/watch?v=JVu7-XSI_OM
```

The app completed:

```text
3 / 3 chunks
100%
Watch Result
```

The resulting video opened in the native player and ExoPlayer decoded H264/AAC successfully.

The emulator test command used a share intent:

```bash
adb shell am start \
  -a android.intent.action.SEND \
  -t text/plain \
  --es android.intent.extra.TEXT 'https://www.youtube.com/watch?v=JVu7-XSI_OM' \
  -n com.halalify.kotlin/.MainActivity
```

## Backend Role

The backend should only receive audio chunks. It should not download YouTube media.

The Android app is responsible for:

```text
YouTube metadata
YouTube media download
Audio extraction
Video cutting
Audio/video muxing
Final local playable output
Saving to gallery
```

The backend is responsible for:

```text
Music removal from uploaded audio chunks
Returning clean audio chunks
```

## Files To Understand First

Important implementation files:

```text
app/src/main/java/com/halalify/kotlin/media/YoutubeDlOperations.kt
app/src/main/java/com/halalify/kotlin/media/FfmpegOperations.kt
app/src/main/java/com/halalify/kotlin/viewmodel/HalalifyViewModel.kt
app/src/main/java/com/halalify/kotlin/network/BackendApi.kt
app/src/main/java/com/halalify/kotlin/ui/screens/ProcessingScreen.kt
app/src/main/java/com/halalify/kotlin/ui/screens/ResultScreen.kt
app/build.gradle.kts
```

## Do Not Break These Decisions

Keep these decisions unless a replacement is tested on both emulator and a real Android phone:

1. Keep media download local to Android.
2. Keep `aria2c` bundled and initialized.
3. Keep `--downloader libaria2c.so` for real downloads.
4. Do not use `formats=missing_pot`.
5. Keep CLI timestamp and number formatting on `Locale.US`.
6. Keep ABI split APKs instead of one huge universal APK.
7. Do not make the backend download YouTube media.
8. Test with a real YouTube URL and confirm the final video opens in the app player.

## Known Tradeoff

The current baseline prioritizes reliability over maximum streaming speed. It downloads source audio/video locally and then cuts/muxes chunks. This proved the full Android flow works.

Future optimization should happen on a new branch. The next likely improvement is to make chunk availability faster while preserving the same reliable downloader rules:

```text
local yt-dlp + aria2c
no formats=missing_pot
Locale.US timestamps
backend receives audio only
```

