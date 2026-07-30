# Halalify

Halalify is an Android prototype for configuring on-device image and video blur. The user selects exactly one target—**Female** or **Male**—and enables blur for still images, video, or both.

## Current status

Implemented:

- English Compose settings screen.
- Exactly two target choices: `Female` and `Male`.
- Independent image and video switches.
- Local persistence of settings.
- Unit tests for model-label filtering.
- No YouTube URL handling, downloader, FFmpeg, backend, network permission, microphone permission, or media-projection service.

Not yet implemented:

- TFLite model loading and inference.
- Image replacement or video overlay rendering.
- A supported browser/player surface.

## Important platform boundary

A normal Android app cannot inspect and modify the visual content of every other installed app. The first production surface must be content Halalify owns and renders, such as an in-app WebView or local-media player. External-app support requires a permitted, app-specific integration and must be stated clearly in the UI.

## Model contract

The extracted web integration currently treats model label `a` as female and `b` as male; label `c` is excluded. This mapping must be validated against known, consented test images before production. Label letters alone do not prove their meaning.

The target filter follows these rules:

| Selected target | Accepted label |
| --- | --- |
| Female | `a` |
| Male | `b` |

Unknown labels, `c`, low-confidence results, and invalid bounding boxes must never be blurred.

## Planned processing pipeline

```text
Supported content surface
        ↓
Image or decoded video frame
        ↓
Resize and normalize off the main thread
        ↓
Long-lived TFLite interpreter
        ↓
Validate confidence, label, and bounding box
        ↓
Blur accepted regions
        ↓
Render image or video overlay
```

### Still images

1. Reject invalid or very small images.
2. Resize a copy to the model input size while retaining scale factors.
3. Run inference off the UI thread.
4. Keep only high-confidence detections matching the selected target.
5. Convert boxes back to original coordinates and blur only those regions.
6. Cache results by source and settings version; clear the cache after a target change.

### Video

1. Process frames only from a supported player or surface.
2. Throttle inference to a device-tested rate.
3. Allow one inference request in flight and drop stale frames.
4. Draw the latest valid boxes in a non-interactive overlay.
5. Clear stale overlays after consecutive empty results or a source/target change.
6. Stop processing and release resources when playback stops or video blur is disabled.

## Privacy and reliability requirements

- Run inference locally; do not upload pixels by default.
- Do not retain frames, thumbnails, or detections after rendering.
- Keep model work off the main thread.
- Release the interpreter with the screen or player lifecycle.
- Treat inference failures as recoverable; skip the failed frame and show status.
- Never claim universal cross-app image access.

## Test plan

### Unit tests

- Female mode accepts only `a`.
- Male mode accepts only `b`.
- `c` and unknown labels are rejected.
- Invalid or legacy saved targets fall back to Female.

### UI tests

- The picker displays exactly Female and Male.
- Save and relaunch restore the target and image/video switches.
- Disabling both content switches disables Save.
- Changing target clears old blur overlays.

### Model and rendering tests

- Validate the `a`/`b` mapping on known test images.
- Test confidence thresholds, box scaling, clipping, and overlaps.
- Test video pause, seek, source changes, frame drops, and rotation.
- Measure frame time, CPU, memory, battery, and thermal behavior.

## Project structure

```text
app/src/main/java/com/halalify/kotlin/
├── MainActivity.kt
├── settings/BlurSettings.kt
└── ui/HalalifyApp.kt

app/src/test/java/com/halalify/kotlin/settings/
└── BlurSettingsTest.kt
```

## Build and test

From the repository root:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

If Android SDK discovery fails, set `ANDROID_HOME` to your SDK path or create a local, uncommitted `local.properties` file containing:

```properties
sdk.dir=C:\\Users\\YOUR_USER\\AppData\\Local\\Android\\Sdk
```

The generated debug APK is located at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Roadmap

1. Validate the model label mapping and add the TFLite asset.
2. Implement a background classifier and detection filter.
3. Add still-image blur in one supported surface.
4. Add throttled video detection and overlays.
5. Run on-device performance, accessibility, privacy, and release tests.
