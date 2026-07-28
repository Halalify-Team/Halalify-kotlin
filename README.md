# Halalify

> An Android audio experience that aims to let people listen to supported app audio with music reduced or removed while preserving speech.

Halalify is an Android project exploring real-time audio playback capture, on-device signal processing, and AI-assisted music separation. The intended experience is simple: a user chooses an app, grants Android's screen-capture permission, and Halalify attempts to capture the app's playback audio. When capture is permitted, the audio is processed and played back with an emphasis on speech and voice.

## Important platform limitation

Android does **not** allow one app to capture audio from every other app without restriction. Playback capture is controlled by the source app, Android version, device policy, and media type. Some applications may reject capture or produce silence. Halalify must always communicate this clearly and must never claim universal compatibility.

For reliable processing, Halalify will also provide its own in-app player for supported sources and local media.

## Product goals

- Let the user select an installed, launchable application.
- Attempt Android-compliant playback capture only after explicit user consent.
- Detect music and avoid expensive processing when it is not needed.
- Reduce or separate music while keeping speech intelligible.
- Play processed audio with low latency.
- Make the capture state, compatibility, latency, and active output device visible to the user.

## Planned user journey

```text
Choose an app
    ↓
Grant MediaProjection permission
    ↓
Halalify attempts playback capture
    ↓
Capture allowed? ── No ──→ Show a clear compatibility message
    ↓ Yes
Detect music → Process when needed → Play cleaned audio
```

## Core capabilities

| Area | Planned capability |
| --- | --- |
| App selection | Show launchable apps with name, icon, package name, and UID. |
| Capture | Use `MediaProjection` and `AudioPlaybackCaptureConfiguration` to request audio from the selected UID. |
| Processing | Resampling, STFT/ISTFT, music detection, music separation, and optional speech enhancement. |
| Playback | Low-latency output through Oboe or AAudio, including wired, speaker, and Bluetooth routes where supported. |
| Controls | Bypass, original-only, cleaned-only, removal strength, mono/stereo, output device, and latency display. |
| Reliability | A foreground capture service, persistent notification, and immediate shutdown when permission is revoked. |

## Android requirements

Halalify will require the following permissions and service declaration:

```xml
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION" />

<service
    android:name=".capture.AudioCaptureService"
    android:exported="false"
    android:foregroundServiceType="mediaProjection" />
```

The user must explicitly approve the capture request created with `MediaProjectionManager.createScreenCaptureIntent()`. On Android 14 and later, the capture service must run as a foreground service of type `mediaProjection`.

## Capture design

The capture layer will use Android's playback-capture API to narrow recording to the selected app UID and supported usages:

```kotlin
val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
    .addMatchingUid(selectedUid)
    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
    .addMatchingUsage(AudioAttributes.USAGE_GAME)
    .build()

val recorder = AudioRecord.Builder()
    .setAudioFormat(
        AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setSampleRate(48_000)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()
    )
    .setAudioPlaybackCaptureConfig(config)
    .build()
```

Capture availability is determined only when a session begins. Early testing should cover YouTube, Chrome, Instagram, TikTok, Spotify, and games, while expecting that some sources will refuse capture.

## Architecture

```text
AudioRecord
    ↓
Single-producer / single-consumer ring buffer
    ↓
Resampler → STFT → Music detector → Music separator → Speech enhancer → ISTFT
    ↓
Oboe / AAudio output
```

The audio callback and AI-processing path must remain separate. In particular:

- Do not allocate memory in the real-time audio callback.
- Do not use locks in the audio callback.
- Do not run ONNX inference directly in the callback.
- Use the ring buffer as the boundary between real-time I/O and background processing.
- Support both 44.1 kHz and 48 kHz input, using Float32 PCM as the initial internal format.
- Keep output stereo even when a model runs internally in mono.

## Proposed project structure

```text
Halalify/
├── app/
│   └── src/main/
│       ├── java/com/halalify/
│       │   ├── ui/
│       │   ├── appselection/
│       │   ├── capture/
│       │   ├── service/
│       │   └── jni/
│       └── AndroidManifest.xml
├── native/
│   ├── AudioEngine.cpp
│   ├── AudioEngine.h
│   ├── RingBuffer.cpp
│   ├── RingBuffer.h
│   ├── AudioInput.cpp
│   ├── AudioOutput.cpp
│   ├── STFT.cpp
│   ├── ISTFT.cpp
│   ├── Resampler.cpp
│   ├── MusicDetector.cpp
│   ├── MusicSeparator.cpp
│   ├── SpeechEnhancer.cpp
│   └── CMakeLists.txt
├── inference/
│   ├── OnnxModel.cpp
│   ├── OnnxModel.h
│   ├── TensorUtils.cpp
│   └── Quantization.cpp
├── models/
│   ├── detector.onnx
│   ├── separator.onnx
│   └── enhancer.onnx
└── tests/
    ├── RingBufferTest.cpp
    ├── StftTest.cpp
    ├── ModelTest.cpp
    └── LatencyTest.cpp
```

## AI and DSP strategy

Start with a single compact model whose input is mixed audio and whose output prioritizes speech or voice. Add a music detector later so that separation runs only when music is present, reducing battery use and latency.

The preferred model path is:

1. Select a small music-separation model.
2. Convert it to ONNX.
3. Validate inference on Android.
4. Optimize with INT8 or FP16 where accuracy and device support permit.
5. Add speech enhancement only if separation artifacts require it.

Noise-suppression tools such as RNNoise and DeepFilterNet may improve speech quality, but they are not replacements for a music-separation model.

## Delivery roadmap

### Release 1 — Capture foundation

- App selection and package/UID display.
- MediaProjection permission flow.
- Playback-audio capture.
- Pass-through playback without filtering.
- Audio-level and diagnostic display.
- Compatibility testing across different source apps.

### Release 2 — Initial music filtering

- Bypass mode.
- Music detector.
- Small separation model.
- Cleaned-audio playback.
- Latency and dropout measurements.

### Release 3 — Quality and device support

- INT8 model optimization.
- Speech enhancement.
- Bluetooth support.
- Per-app settings.
- Notification controls for the foreground service.
- Automatic stop when the source or projection session ends.

### Release 4 — In-app player

- Built-in player for local media and supported video sources.
- Cleaned-audio-only playback.
- Prevention of duplicate/original audio playback.

## Development principles

- Privacy first: capture only after user consent and process audio on device whenever possible.
- Honest compatibility: show a clear message when a source prevents capture.
- Battery awareness: bypass the separator when the detector finds no music.
- Real-time safety: protect the audio thread from allocations, blocking work, and inference.
- Measurable quality: track latency, CPU, memory, battery impact, and audible artifacts on real devices.

## Status

This repository is in the prototyping stage. The sections above describe the intended architecture and implementation roadmap, not a guarantee that every planned capability is already available.

## References

- [Android MediaProjection](https://developer.android.com/media/grow/media-projection)
- [Android audio and video capture](https://developer.android.com/media/platform/av-capture)
- [Android high-performance audio](https://developer.android.com/ndk/guides/audio)
- [AudioManager reference](https://developer.android.com/reference/android/media/AudioManager)
- [ONNX Runtime for Android](https://onnxruntime.ai/docs/build/android.html)
