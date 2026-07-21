# Halalify Android

Native Android app that removes music from YouTube videos locally on-device. Paste a YouTube URL, and Halalify downloads the video, sends only audio chunks to the backend for AI-powered music removal, then rebuilds a playable clean video.

## What It Does

1. User pastes/shares a YouTube URL
2. App downloads video + audio locally using `yt-dlp` + `aria2c`
3. Audio chunks are sent to the backend for music/lyrics removal (via Replicate Spleeter)
4. Clean audio is received back and muxed with the original video
5. User watches the result and saves to gallery

**Key rule:** YouTube media download happens locally on the device. Only extracted audio chunks are sent to the backend.

## Features

- YouTube URL paste / share from any app
- Multiple quality tiers (360p, 720p, 1080p)
- Music removal via AI (Spleeter on Replicate)
- Optional face blur for women (TFLite gender classification + ML Kit face detection)
- Chunked processing with real-time progress, ETA, and resumable pipeline
- Watch while processing (play chunks as they complete)
- Fullscreen video playback
- Library of saved halalified videos
- Swipe-to-delete library cards
- Google Sign-In with session persistence
- Subscription management via LemonSqueezy
- Confetti celebration on completion
- Splash screen with animated logo

## Getting the APK

### Pre-built APKs

Release APKs are generated as ABI splits:

```
app/build/outputs/apk/release/app-arm64-v8a-release.apk     # Most modern phones
app/build/outputs/apk/release/app-armeabi-v7a-release.apk   # Older devices
app/build/outputs/apk/release/app-x86_64-release.apk        # Emulators
```

For most users, install the **arm64-v8a** APK.

### Building from Source

**Prerequisites:**
- Android Studio Ladybug or newer
- JDK 17
- Android SDK 35

**Setup:**

1. Clone the repo:
   ```bash
   git clone https://github.com/Azex01/Halalify-kotlin.git
   cd Halalify-kotlin
   ```

2. Set up Google Sign-In (required for login):
   - Create a project in [Google Cloud Console](https://console.cloud.google.com/)
   - Enable Credential Manager API
   - Create an OAuth 2.0 client ID (Android type) with your app's package name and SHA-1
   - Add your `GOOGLE_WEB_CLIENT_ID` to `gradle.properties`:
     ```
     GOOGLE_WEB_CLIENT_ID=your-client-id.apps.googleusercontent.com
     ```

3. Build:
   ```bash
   ./gradlew :app:assembleRelease
   ```

4. Install on a connected device:
   ```bash
   adb install -r app/build/outputs/apk/release/app-arm64-v8a-release.apk
   ```

### Testing on Emulator

Start a Pixel emulator (API 33+) and share a YouTube URL:

```bash
adb shell am start \
  -a android.intent.action.SEND \
  -t text/plain \
  --es android.intent.extra.TEXT 'https://www.youtube.com/watch?v=JVu7-XSI_OM' \
  -n com.halalify.kotlin/.MainActivity
```

### Clean Install (after backend changes)

```bash
adb shell pm clear com.halalify.kotlin
```

## How to Use

1. **Open the app** -- You'll see the Halalify home screen with a URL input field
2. **Sign in** -- Tap "Sign in with Google" to authenticate (required for music removal)
3. **Paste a YouTube URL** -- Type/paste a URL or share from another app (YouTube, Chrome, etc.)
4. **Choose options** -- Toggle music removal and/or face blur, select quality tier
5. **Tap "Halalify It"** -- A pre-flight summary shows before processing starts
6. **Watch or wait** -- Processing screen shows circular progress with ETA. You can start watching as soon as the first chunk is ready
7. **Save** -- Tap "Save to Gallery" to export the video to your device

## Project Structure

```
app/src/main/java/com/halalify/kotlin/
├── MainActivity.kt                    # Entry point, splash, share intent
├── viewmodel/
│   └── HalalifyViewModel.kt           # Single source of truth for all state
├── network/
│   └── BackendApi.kt                  # OkHttp client for backend communication
├── media/
│   ├── YoutubeDlOperations.kt         # yt-dlp integration via youtubedl-android
│   ├── YoutubeFormatResolver.kt       # Quality tier resolution
│   ├── YoutubeFastFormatDiscovery.kt  # Fast InnerTube API format discovery
│   ├── FfmpegOperations.kt           # FFmpeg mux, cut, concat, normalize
│   ├── VideoBlurOperations.kt         # Face detection + gender-based blur
│   ├── GenderFaceClassifier.kt        # TFLite gender classification
│   ├── LocalMediaProxy.kt            # Local HTTP proxy for FFmpeg HTTPS bridging
│   └── TemporaryFileCleaner.kt        # Temp file cleanup
├── processing/
│   ├── ChunkPlanner.kt               # Chunk time-range planning
│   └── ProcessingForegroundService.kt # Background processing notification
├── security/
│   └── SecureSessionStore.kt          # AES-256 encrypted session persistence
├── storage/
│   └── LibraryRepository.kt           # Saved videos persistence
└── ui/
    ├── VideoPlayers.kt                # ExoPlayer composables (single + playlist)
    ├── navigation/
    │   ├── AppNavigation.kt           # Nav graph, Google Sign-In flow
    │   └── HalalifyBottomBar.kt       # Home / Library / Profile tabs
    ├── screens/
    │   ├── InputScreen.kt             # URL input, options, quality selection
    │   ├── ProcessingScreen.kt        # Circular progress, ETA, chunk details
    │   ├── ResultScreen.kt            # Video playback, fullscreen, save
    │   ├── DownloadScreen.kt          # Simple download (no music removal)
    │   ├── LibraryScreen.kt           # Saved videos list
    │   └── ProfileScreen.kt          # Account, quota, subscription
    ├── components/
    │   ├── HalalifyTopBar.kt          # Reusable top app bar
    │   ├── HalalifyLogo.kt            # Animated brand logo (Canvas)
    │   └── CelebrationOverlay.kt      # Confetti animation
    └── theme/
        ├── Color.kt                   # Teal/gold accent palette
        ├── Theme.kt                   # Dark-only Material3 theme
        └── Type.kt                    # Typography
```

## Tech Stack

| Layer | Technology |
|-------|-----------|
| UI | Jetpack Compose + Material3 (dark theme) |
| Video Playback | ExoPlayer / Media3 1.7.1 |
| Media Processing | FFmpegKit Audio 6.0.1 |
| YouTube Download | youtubedl-android 0.18.1 (yt-dlp + aria2c) |
| Face Detection | ML Kit Face Detection 16.1.7 |
| Gender Classification | TensorFlow Lite 2.11.0 |
| Auth | AndroidX Credentials 1.3.0 (Google Sign-In) |
| Networking | OkHttp 4.12.0 |
| Image Loading | Coil Compose 2.7.0 |
| Encrypted Storage | AndroidX Security Crypto 1.1.0-alpha06 |

## Build Configuration

- **compileSdk:** 35
- **minSdk:** 24 (Android 7.0)
- **targetSdk:** 35
- **JVM Target:** 17
- **ABI Splits:** arm64-v8a, armeabi-v7a, x86_64
- **Backend URL:** `https://halalify-backend-2.onrender.com` (configurable in DEBUG)

## Important Decisions (Do Not Break)

1. Media download stays local to Android -- backend only receives audio chunks
2. `aria2c` bundled and initialized for reliable downloads
3. `--downloader libaria2c.so` used for all real downloads
4. Never use `formats=missing_pot` -- causes YouTube 403 errors
5. All CLI timestamps formatted with `Locale.US` (Arabic locale bug fix)
6. ABI split APKs instead of a universal APK
7. Backend never downloads YouTube media

## Backend

This app requires the Halalify backend to process audio chunks. See the [halalify-backend-2](https://github.com/Azex01/halalify-backend-2) repo for backend setup.

## License

Private -- All rights reserved.
