"""Full-int8 post-training quantization with independent detector outputs.

The original detector's last operation concatenates box coordinates and class
probabilities. This script temporarily exposes those two branches as separate
outputs during calibration, so TFLite gives each branch its own INT8 scale.
It then restores the public `[1, 7, 3549]` Float32 output by dequantizing the
branches independently and concatenating them. All preceding operators stay
fully integer-quantized; only the boundary output is Float32 by design.
"""

from __future__ import annotations

import argparse
from pathlib import Path

import numpy as np
import tensorflow as tf
from tensorflow.lite.python import schema_py_generated as schema
from tensorflow.lite.python.optimize.calibrator import Calibrator
from tensorflow.lite.tools import flatbuffer_utils


def _seq(value):
    return [] if value is None else list(value)


def _opcode(model, builtin_code: int, version: int = 2) -> int:
    for index, code in enumerate(model.operatorCodes):
        if code.builtinCode == builtin_code:
            return index
    code = schema.OperatorCodeT()
    code.builtinCode = builtin_code
    code.deprecatedBuiltinCode = builtin_code
    code.version = version
    model.operatorCodes.append(code)
    return len(model.operatorCodes) - 1


def _letterbox(path: Path) -> np.ndarray:
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


def _unary(opcode_index: int, input_index: int, output_index: int):
    op = schema.OperatorT()
    op.opcodeIndex = opcode_index
    op.inputs = np.asarray([input_index], dtype=np.int32)
    op.outputs = np.asarray([output_index], dtype=np.int32)
    op.builtinOptionsType = schema.BuiltinOptions.NONE
    return op


def _make_float_tensor(name: bytes, shape):
    tensor = schema.TensorT()
    tensor.name = name + b"/float_output"
    tensor.shape = np.asarray(shape, dtype=np.int32)
    tensor.type = schema.TensorType.FLOAT32
    tensor.buffer = 0
    tensor.quantization = None
    return tensor


def _prepare_two_branch_graph(source: Path):
    model = flatbuffer_utils.convert_bytearray_to_object(source.read_bytes())
    if len(model.subgraphs) != 1:
        raise ValueError("The source must contain exactly one subgraph.")
    graph = model.subgraphs[0]
    outputs = _seq(graph.outputs)
    if len(outputs) != 1:
        raise ValueError("Expected one original output.")
    original_output = int(outputs[0])
    final_index = None
    for index, op in enumerate(graph.operators):
        if original_output in _seq(op.outputs):
            if model.operatorCodes[op.opcodeIndex].builtinCode != schema.BuiltinOperator.CONCATENATION:
                raise ValueError("The original output is not a CONCATENATION.")
            final_index = index
            break
    if final_index is None:
        raise ValueError("Could not locate the original final CONCATENATION.")
    final_op = graph.operators[final_index]
    branch_outputs = [int(x) for x in _seq(final_op.inputs)]
    if len(branch_outputs) != 2:
        raise ValueError("Expected exactly two final output branches.")
    graph.operators = [op for index, op in enumerate(graph.operators) if index != final_index]
    graph.outputs = np.asarray(branch_outputs, dtype=np.int32)
    return flatbuffer_utils.convert_object_to_bytearray(model), original_output, branch_outputs, final_op


def _restore_float_output(model_bytes: bytes, branch_outputs, original_output: int, final_op) -> bytes:
    model = flatbuffer_utils.convert_bytearray_to_object(model_bytes)
    graph = model.subgraphs[0]
    dequantize_opcode = _opcode(model, schema.BuiltinOperator.DEQUANTIZE)
    float_outputs = []
    dequant_ops = []
    for branch_index in branch_outputs:
        branch = graph.tensors[int(branch_index)]
        if branch.type != schema.TensorType.INT8:
            raise ValueError(f"Calibrated branch {branch_index} is not INT8.")
        output = _make_float_tensor(branch.name or b"branch", branch.shape)
        graph.tensors.append(output)
        output_index = len(graph.tensors) - 1
        dequant_ops.append(_unary(dequantize_opcode, int(branch_index), output_index))
        float_outputs.append(output_index)

    final_op.opcodeIndex = _opcode(model, schema.BuiltinOperator.CONCATENATION)
    final_op.inputs = np.asarray(float_outputs, dtype=np.int32)
    final_op.outputs = np.asarray([original_output], dtype=np.int32)
    graph.tensors[original_output].type = schema.TensorType.FLOAT32
    graph.tensors[original_output].buffer = 0
    graph.tensors[original_output].quantization = None
    graph.operators.extend(dequant_ops)
    graph.operators.append(final_op)
    graph.outputs = np.asarray([original_output], dtype=np.int32)
    return flatbuffer_utils.convert_object_to_bytearray(model)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path, help="Original Float32 TFLite model")
    parser.add_argument("calibration_dir", type=Path, help="JPEG representative images")
    parser.add_argument("output", type=Path)
    parser.add_argument(
        "--int8-io",
        action="store_true",
        help="Keep the two independent branch outputs and use INT8 input/output",
    )
    args = parser.parse_args()
    if not args.source.is_file():
        raise FileNotFoundError(args.source)
    images = sorted(args.calibration_dir.glob("*.jpg"))
    if not images:
        raise ValueError(f"No .jpg calibration images found in {args.calibration_dir}")

    prepared, original_output, branch_outputs, final_op = _prepare_two_branch_graph(args.source)
    def dataset():
        for image in images:
            yield [_letterbox(image)]

    quantized = Calibrator(prepared).calibrate_and_quantize(
        dataset,
        tf.int8 if args.int8_io else tf.float32,
        tf.int8,
        allow_float=False,
        activations_type=tf.int8,
        bias_type=tf.int32,
        disable_per_channel=False,
    )
    repaired = (
        quantized
        if args.int8_io
        else _restore_float_output(quantized, branch_outputs, original_output, final_op)
    )
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_bytes(repaired)
    print(f"Wrote {args.output} ({len(repaired):,} bytes) using {len(images)} images")


if __name__ == "__main__":
    main()
