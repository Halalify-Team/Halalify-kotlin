# Image Model Package

This package contains the current `halalify_v2` vision detector selected after fine-tuning, ONNX-to-TFLite conversion, and benchmark pass. The Android app packages this directory directly as uncompressed assets.

## Approved model file

- Model: `halalify_v2.tflite`
- Size: `2,849,880` bytes
- SHA-256: `44F0CEE3A5AABE2074042DC1D8AA50A02C703D9B0EB97D32082C78DC6A2C8945`
- Input: RGB `float32`, shape `[1, 416, 416, 3]`
- Output: one `float32` tensor, shape `[1, 7, 14365]`
- Classes: `a = female`, `b = male`, `c = ignored`

## NSFW classifier

The package also contains `nsfw2.tflite`, a 5,958,568-byte float32 classifier exported by the archived [Open-NSFW Android project](https://github.com/devzwy/open_nsfw_android). Its SHA-256 is `EB3B446A6A8C1A73998A76011B97CFC67BC01084C63EE195C774E71344A66442`.

- Input: float32 tensor `[1, 224, 224, 3]`; the native adapter follows the source model's centered 224 crop from a 256x256 resize and BGR VGG-mean preprocessing.
- Output: float32 `[1, 2]`, interpreted as `[sfw, nsfw]`.
- Runtime threshold: `0.70` NSFW probability.
- The classifier is run over up to the configured number of already selected detector regions. A positive result annotates that region as NSFW; it does not override the selected male/female target and there is no full-frame fallback.

The upstream repository documents the model as Apache 2.0, but its README also notes that the project is archived and may be inaccurate on some images. Revalidate the model and review upstream/model provenance before commercial distribution. The classifier is orchestrated by the native `AiEngine`; Kotlin does not load or invoke this model directly.

Preprocessing details are defined in `model_manifest.json`. This directory is the canonical model source. Gradle now packages the model directly from this directory as an uncompressed Android asset, without committing a second copy to the repository, and the C++ engine validates its tensor contract when it starts.

## Quantization status

The deployed artifact uses post-training dynamic-range quantization for the
weights while keeping Float32 activations and Float32 input/output tensors.
The native LiteRT adapter accepts the raw `[1,7,14365]` output and normalizes
the converted box-channel layout before shared postprocessing. This keeps
the confidence scores usable, unlike the rejected single-scale full-INT8
export.

## What the model does

The model is a YOLO-family object detector, not a classifier for a single cropped face. For each frame, it returns person bounding boxes with a score for one of three classes. The `female` and `male` labels represent the visual class learned by the model; they are not proof of a person's identity or actual gender.

The preprocessing that matches the inspected application is:

1. Correct the frame rotation and convert it to RGB.
2. Resize it with aspect-ratio preservation into a `416 × 416` canvas.
3. Fill the canvas borders with RGB `(114, 114, 114)`.
4. Convert values to `float32` and divide by `255`.
5. Read the first four output rows as `center-x, center-y, width, height`, and the remaining three rows as class scores `a, b, c`.
6. Select the highest-scoring class, apply the confidence threshold, undo letterboxing, and run IoU-based NMS.

## Available source results

The production-suite benchmark used 340 cases and reported approximately:

| Metric | Result |
|---|---:|
| Female recall | 0.881 |
| Male recall | 0.898 |
| Specificity | 0.950 |
| Overall benchmark score | 64.3 |

These are comparison results, not a production acceptance claim. Avatar recall remained a gap and the release still requires broader device testing and representative screenshots.

## Licensing notice

The source training script starts from `yolov8n.pt` and uses Ultralytics. The model and training-data licensing must be resolved before commercial distribution. The TFLite format does not remove obligations associated with the training framework or weights. Review the [official Ultralytics licensing page](https://www.ultralytics.com/license) with qualified legal counsel when needed.

## Plan

The engine, integration, and testing plan is documented in the [Image AI Engine Plan](../docs/IMAGE_AI_ENGINE_PLAN_AR.md).

The optional music-isolation model is separate from this vision detector. Its required filename and waveform tensor contract are documented in [audio_model_manifest.example.json](audio_model_manifest.example.json) and the [Audio AI Engine Plan](../docs/AUDIO_AI_ENGINE_PLAN_AR.md). No trained audio model is currently bundled.
