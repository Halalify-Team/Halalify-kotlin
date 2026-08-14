"""Build an experimental int8-weight TFLite proxy from an existing Float32 file.

This is not a full post-training integer quantization pass: activations and
model I/O remain Float32. Convolution weights are stored as int8 and decoded
by TFLite DEQUANTIZE nodes, which makes this useful for checking quantization
error while keeping the existing Float32 native contract. It may reduce the
constant-storage portion of the FlatBuffer, but it is not expected to provide
the same speed/battery benefit as a fully INT8 model.
"""

from __future__ import annotations

import argparse
from pathlib import Path

import numpy as np
from tensorflow.lite.python import schema_py_generated as schema
from tensorflow.lite.tools import flatbuffer_utils


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    if not args.source.is_file():
        raise FileNotFoundError(args.source)

    model = flatbuffer_utils.convert_bytearray_to_object(args.source.read_bytes())
    dequantize_opcode = next(
        (i for i, code in enumerate(model.operatorCodes) if code.builtinCode == schema.BuiltinOperator.DEQUANTIZE),
        None,
    )
    if dequantize_opcode is None:
        code = schema.OperatorCodeT()
        code.builtinCode = schema.BuiltinOperator.DEQUANTIZE
        code.deprecatedBuiltinCode = schema.BuiltinOperator.DEQUANTIZE
        code.version = 2
        model.operatorCodes.append(code)
        dequantize_opcode = len(model.operatorCodes) - 1

    total_weights = 0
    total_bytes_before = 0
    total_bytes_after = 0
    for graph in model.subgraphs:
        new_ops = []
        converted = {}
        for tensor_index, tensor in enumerate(list(graph.tensors)):
            name = tensor.name or b""
            data = model.buffers[tensor.buffer].data if tensor.buffer else None
            is_conv_weight = (
                tensor.type == schema.TensorType.FLOAT32
                and data is not None
                and len(tensor.shape) == 4
                and len(data) == int(np.prod(tensor.shape)) * 4
                and b"convolution" in name
            )
            if not is_conv_weight:
                continue

            values = np.frombuffer(data, dtype=np.float32).copy()
            scale = float(np.max(np.abs(values)) / 127.0)
            if not np.isfinite(scale) or scale == 0.0:
                continue
            quantized = np.clip(np.rint(values / scale), -127, 127).astype(np.int8)

            int8_buffer = schema.BufferT()
            int8_buffer.data = quantized.view(np.uint8).tobytes()
            model.buffers.append(int8_buffer)
            # Release the original constant bytes when this buffer is not
            # shared by another tensor. The original tensor remains in the
            # graph as an unused Float32 declaration, while consumers are
            # redirected to the dequantized output below.
            buffer_users = sum(1 for candidate in graph.tensors if candidate.buffer == tensor.buffer)
            if buffer_users == 1:
                model.buffers[tensor.buffer].data = None
            int8_tensor = schema.TensorT()
            int8_tensor.shape = np.array(tensor.shape, dtype=np.int32)
            int8_tensor.type = schema.TensorType.INT8
            int8_tensor.buffer = len(model.buffers) - 1
            int8_tensor.name = name + b"/experimental_int8"
            quant = schema.QuantizationParametersT()
            quant.min = np.array([-127.0 * scale], dtype=np.float32)
            quant.max = np.array([127.0 * scale], dtype=np.float32)
            quant.scale = np.array([scale], dtype=np.float32)
            quant.zeroPoint = np.array([0], dtype=np.int64)
            quant.quantizedDimension = 0
            int8_tensor.quantization = quant
            graph.tensors.append(int8_tensor)
            int8_index = len(graph.tensors) - 1

            output_tensor = schema.TensorT()
            output_tensor.shape = np.array(tensor.shape, dtype=np.int32)
            output_tensor.type = schema.TensorType.FLOAT32
            output_tensor.buffer = 0
            output_tensor.name = name + b"/experimental_dequantized"
            graph.tensors.append(output_tensor)
            output_index = len(graph.tensors) - 1

            dequant = schema.OperatorT()
            dequant.opcodeIndex = dequantize_opcode
            dequant.inputs = np.array([int8_index], dtype=np.int32)
            dequant.outputs = np.array([output_index], dtype=np.int32)
            dequant.builtinOptionsType = schema.BuiltinOptions.NONE
            new_ops.append(dequant)
            converted[tensor_index] = output_index
            total_weights += values.size
            total_bytes_before += len(data)
            total_bytes_after += len(int8_buffer.data)

        for op in graph.operators:
            if op.inputs is not None:
                op.inputs = np.array(
                    [converted.get(int(index), int(index)) for index in op.inputs],
                    dtype=np.int32,
                )
        graph.operators = new_ops + graph.operators

    if total_weights == 0:
        raise RuntimeError("No convolution Float32 weight tensors were found.")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_bytes(flatbuffer_utils.convert_object_to_bytearray(model))
    print(f"Converted weight values: {total_weights:,}")
    print(f"Weight bytes: {total_bytes_before:,} -> {total_bytes_after:,}")
    print(f"Wrote: {args.output} ({args.output.stat().st_size:,} bytes)")


if __name__ == "__main__":
    main()
