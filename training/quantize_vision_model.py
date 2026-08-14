"""Export the vision model with TensorFlow Lite post-training quantization.

The checked-in vision artifact is already a TFLite FlatBuffer. TensorFlow's
converter needs the original SavedModel/Keras graph, so this script refuses a
TFLite input instead of attempting an unsafe FlatBuffer mutation.
"""

from __future__ import annotations

import argparse
from pathlib import Path

import numpy as np
import tensorflow as tf


def load_converter(source: Path) -> tf.lite.TFLiteConverter:
    if not source.exists():
        raise FileNotFoundError(
            f"Model source was not found: {source}. Provide the real path to "
            "a SavedModel directory or .keras/.h5 file; the example "
            "'original_model.keras' is only a placeholder."
        )
    if source.suffix.lower() == ".tflite":
        raise ValueError(
            "The source is already TFLite. Provide the original SavedModel "
            "directory or a .keras/.h5 model."
        )
    if source.is_dir():
        return tf.lite.TFLiteConverter.from_saved_model(str(source))
    if source.suffix.lower() in {".keras", ".h5"}:
        return tf.lite.TFLiteConverter.from_keras_model(
            tf.keras.models.load_model(source)
        )
    raise ValueError("Source must be a SavedModel directory, .keras, or .h5 file.")


def representative_dataset(source: Path):
    # Calibration data must resemble the real 416x416 RGB inputs. Random data
    # is intentionally not used because it can produce poor INT8 ranges.
    for path in sorted(source.glob("*.npy")):
        image = np.load(path).astype(np.float32)
        if image.shape == (416, 416, 3):
            image = image[None, ...]
        if image.shape != (1, 416, 416, 3):
            continue
        yield [image]


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path, help="SavedModel, .keras, or .h5")
    parser.add_argument("output", type=Path)
    parser.add_argument("--mode", choices=("fp16", "int8"), default="fp16")
    parser.add_argument(
        "--calibration-dir",
        type=Path,
        help="Directory of preprocessed float32 [416,416,3] .npy files for INT8",
    )
    args = parser.parse_args()

    converter = load_converter(args.source)
    if args.mode == "fp16":
        converter.optimizations = [tf.lite.Optimize.DEFAULT]
        converter.target_spec.supported_types = [tf.float16]
    else:
        if args.calibration_dir is None:
            raise ValueError("INT8 requires --calibration-dir with representative .npy inputs.")
        converter.optimizations = [tf.lite.Optimize.DEFAULT]
        converter.representative_dataset = lambda: representative_dataset(args.calibration_dir)
        converter.target_spec.supported_ops = [tf.lite.OpsSet.TFLITE_BUILTINS_INT8]
        converter.inference_input_type = tf.int8
        converter.inference_output_type = tf.int8

    model = converter.convert()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_bytes(model)
    print(f"Wrote {args.output} ({len(model):,} bytes)")

    interpreter = tf.lite.Interpreter(model_content=model)
    interpreter.allocate_tensors()
    input_info = interpreter.get_input_details()[0]
    output_info = interpreter.get_output_details()[0]
    print(f"Input: {input_info['dtype'].__name__} {input_info['shape'].tolist()}")
    print(f"Output: {output_info['dtype'].__name__} {output_info['shape'].tolist()}")


if __name__ == "__main__":
    main()
