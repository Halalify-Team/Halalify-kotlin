# Halalify

An Android prototype for on-device content blur. Users select one visual model class—male or female—and the local vision engine blurs matching detections over the shared app or device screen.

## Current functionality

- English settings screen for blur target and content type.
- Settings persist locally across app launches.
- A C++17 vision core performs RGB letterboxing, LiteRT inference, YOLO output decoding, class-agnostic NMS, and the selected-target policy.
- Full-display Android MediaProjection frames are passed through JNI to the packaged v3 model. Matching detections are blurred both in the protected preview and through a touch-through system overlay.
- The optional Android playback-audio monitor now runs the packaged YAMNet music detector and streaming DTLN speech separator on-device. After two consecutive music-positive frames it mutes the device media stream, requests a transient audio focus so a cooperative media player pauses, and shows the action in the app and foreground notification.
- The model stays on device. Captured preview frames are not persisted or uploaded.

## Current limitation

Device-level blur requires the explicit Android display-over-other-apps permission. Application overlays remain below critical system windows such as the status bar and keyboard, and Android protected surfaces may also be absent from MediaProjection captures. Coordinate, rotation, and OEM behavior still require broader device testing.

Playback capture is limited to apps and players that allow it. Android gives a normal app a copy of eligible playback; it does not guarantee that Halalify can close another app or replace its device output with the processed speech stem. The pause request is best-effort and player-dependent.

## Model-label contract

The extracted web implementation currently interprets model label `a` as female and `b` as male, while `c` is not blurred. Validate that mapping with known images before relying on it in a production inference pipeline.

## Image AI engine plan

The verified deployed model, its machine-readable contract, provenance notes, and the cross-platform C++ integration plan are in [`Model`](Model/README.md).

## Audio AI engine plan

The playback-capture architecture, exact TFLite waveform contract, integration point, and Android platform limits are documented.

The downloaded YAMNet and DTLN models, licensed starter dataset, reproducible preparation scripts, local Python environment instructions, and fine-tuning/export commands are documented.

## Verification

Run the following from the repository root:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
