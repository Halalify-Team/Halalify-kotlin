"""Attempt full post-training INT8 quantization of an existing TFLite model.

This uses TensorFlow Lite's internal calibrator because no SavedModel/Keras
source is available. The input and output are intentionally changed to INT8;
the Android native backend must be updated before this artifact can be shipped.
"""

from __future__ import annotations

import argparse
from pathlib import Path

import numpy as np
import tensorflow as tf
from tensorflow.lite.python.optimize.calibrator import Calibrator


def letterbox(path: Path) -> np.ndarray:
    image = tf.cast(tf.io.decode_jpeg(tf.io.read_file(str(path)), channels=3), tf.float32)
    height, width = image.shape[:2]
    scale = min(416.0 / float(width), 416.0 / float(height))
    resized_width = max(1, round(width * scale))
    resized_height = max(1, round(height * scale))
    image = tf.image.resize(image, [resized_height, resized_width], method="bilinear")
    image = tf.pad(
        image,
        [
            [(416 - resized_height) // 2, 416 - resized_height - (416 - resized_height) // 2],
            [(416 - resized_width) // 2, 416 - resized_width - (416 - resized_width) // 2],
            [0, 0],
        ],
        constant_values=114.0,
    )
    return (image.numpy() / 255.0).astype(np.float32)[None, ...]


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path)
    parser.add_argument("calibration_dir", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument(
        "--float-io",
        action="store_true",
        help="Keep Float32 I/O while quantizing internal activations and weights",
    )
    args = parser.parse_args()
    model = args.source.read_bytes()
    images = sorted(args.calibration_dir.glob("*.jpg"))
    if not images:
        raise ValueError(f"No .jpg calibration images found in {args.calibration_dir}")

    def dataset():
        for image in images:
            yield [letterbox(image)]

    calibrator = Calibrator(model)
    quantized = calibrator.calibrate_and_quantize(
        dataset,
        tf.float32 if args.float_io else tf.int8,
        tf.float32 if args.float_io else tf.int8,
        allow_float=False,
        activations_type=tf.int8,
        bias_type=tf.int32,
        disable_per_channel=False,
    )
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_bytes(quantized)
    print(f"Wrote {args.output} ({len(quantized):,} bytes) using {len(images)} images")


if __name__ == "__main__":
    main()
