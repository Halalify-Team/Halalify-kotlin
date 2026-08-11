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

# Image Model Package

This package contains the version that was actually deployed in the inspected HaramBlur application, after removing the original app's XOR wrapper. The decrypted contents of `merge_base/assets/data.bin` were verified to match this file byte-for-byte.


## What the model does

The model is a YOLO-family object detector, not a classifier for a single cropped face. For each frame, it returns person bounding boxes with a score for one of three classes. The `female` and `male` labels represent the visual class learned by the model; they are not proof of a person's identity or actual gender.

The preprocessing that matches the inspected application is:

1. Correct the frame rotation and convert it to RGB.
2. Resize it with aspect-ratio preservation into a `416 × 416` canvas.
3. Fill the canvas borders with RGB `(114, 114, 114)`.
4. Convert values to `float32` and divide by `255`.
5. Read the first four output rows as `center-x, center-y, width, height`, and the remaining three rows as class scores `a, b, c`.
6. Select the highest-scoring class, apply the confidence threshold, undo letterboxing, and run IoU-based NMS.

## Approved model file

- Model: `halalify_gender_v3_float32.tflite`
- Size: `12,132,492` bytes
- SHA-256: `8F03A7F817C4604FFB8E29C5E2ECE70F4AE8A1BB2C2D189C11E9DD82DEF7E07A`
- Input: RGB `float32`, shape `[1, 416, 416, 3]`
- Output: `float32`, shape `[1, 7, 3549]`
- Classes: `a = female`, `b = male`, `c = ignored`

Preprocessing details are defined in `model_manifest.json`. This directory is the canonical model source, but it is not yet connected to an APK. During implementation, Gradle will package it as an uncompressed asset so the C++ engine can memory-map it without committing a second copy to the repository.

## Available source results

The local v3 validation record, using `1,475` images and `1,839` objects, reported approximately:

| Class | Precision | Recall | mAP50 | mAP50-95 |
|---|---:|---:|---:|---:|
| `a` (female) | 0.563 | 0.588 | 0.598 | 0.448 |
| `b` (male) | 0.694 | 0.794 | 0.784 | 0.622 |
| Overall | 0.629 | 0.691 | 0.691 | 0.535 |

These are reference numbers, not production acceptance results. In particular, the current `female` accuracy is not sufficient to claim reliable protection without an evaluation set representative of real app screens, per-class threshold calibration, and model improvement where needed.

## Verification

Run the following from the repository root:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

## Licensing notice

The source training script starts from `yolov8n.pt` and uses Ultralytics. The model and training-data licensing must be resolved before commercial distribution. The TFLite format does not remove obligations associated with the training framework or weights. Review the [official Ultralytics licensing page](https://www.ultralytics.com/license) with qualified legal counsel when needed.


