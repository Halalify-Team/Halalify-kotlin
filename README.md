# Halalify

An Android prototype for configuring on-device content blur. Users select one target for the future inference engine: detected male people or detected female people. They can enable the rule for images, video, or both.

## Current functionality

- English settings screen for blur target and content type.
- Settings persist locally across app launches.
- Unit-tested mapping from the extracted model labels to the selected blur target.
- No YouTube URL handling, download code, network access, microphone, media-projection, or foreground-service permissions.

## Current limitation

The UI and settings layer are complete, but the TFLite inference and drawing pipeline is not connected yet. Android applications cannot inspect the visual content of every other installed app automatically; the engine must run in an explicitly supported surface such as a built-in browser or another permitted integration.

## Model-label contract

The extracted web implementation currently interprets model label `a` as female and `b` as male, while `c` is not blurred. Validate that mapping with known images before relying on it in a production inference pipeline.

## Image AI engine plan

The verified deployed model, its machine-readable contract, provenance notes, and the cross-platform C++ integration plan are in [`Model`](Model/README.md) and [`docs/IMAGE_AI_ENGINE_PLAN_AR.md`](docs/IMAGE_AI_ENGINE_PLAN_AR.md).

## Verification

Run the following from the repository root:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```
