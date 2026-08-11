# Halalify

An Android prototype for on-device content blur. Users select one visual model class—male or female—and the local vision engine blurs matching detections in captured preview frames.

## Current functionality

- English settings screen for blur target and content type.
- Settings persist locally across app launches.
- A C++17 vision core performs RGB letterboxing, LiteRT inference, YOLO output decoding, class-agnostic NMS, and the selected-target policy.
- Android MediaProjection frames are passed through JNI to the packaged v3 model. Matching detections are blurred in the in-app protected preview and detection counts are shown live.
- The model stays on device. Captured preview frames are not persisted or uploaded.

## Current limitation

The current renderer protects the preview inside Halalify. It does not yet draw a system-wide overlay above the shared application; that remains phase 4 of the plan and requires the overlay permission, coordinate/inset handling, and OEM testing. Android protected surfaces may also be absent from MediaProjection captures.

## Model-label contract

The extracted web implementation currently interprets model label `a` as female and `b` as male, while `c` is not blurred. Validate that mapping with known images before relying on it in a production inference pipeline.

## Image AI engine plan

The verified deployed model, its machine-readable contract, provenance notes, and the cross-platform C++ integration plan are in [`Model`](Model/README.md) and [`docs/IMAGE_AI_ENGINE_PLAN_AR.md`](docs/IMAGE_AI_ENGINE_PLAN_AR.md).

## Verification

Run the following from the repository root:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.
