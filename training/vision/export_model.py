from __future__ import annotations

"""Export a checkpoint to calibrated INT8 LiteRT without promoting it."""

import argparse
import hashlib
import json
import shutil
from pathlib import Path
from typing import Any

from training.vision.common import CLASS_NAMES, load_recipe


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest().upper()


def inspect_contract(model_path: Path) -> dict[str, Any]:
    try:
        import tensorflow as tf
    except ImportError as error:
        raise RuntimeError("TensorFlow is required to inspect the exported LiteRT contract") from error
    interpreter = tf.lite.Interpreter(model_path=str(model_path), num_threads=2)
    interpreter.allocate_tensors()
    inputs = interpreter.get_input_details()
    outputs = interpreter.get_output_details()
    if len(inputs) != 1 or tuple(inputs[0]["shape"]) != (1, 416, 416, 3):
        raise ValueError(f"Unsupported input contract: {[item['shape'].tolist() for item in inputs]}")
    output_shapes = [tuple(item["shape"]) for item in outputs]
    supported = (
        len(outputs) == 1
        and (
            (len(output_shapes[0]) == 3 and output_shapes[0][0] == 1 and output_shapes[0][2] == 6)
            or (len(output_shapes[0]) == 3 and output_shapes[0][0] == 1 and output_shapes[0][1] == 7)
        )
    )
    if not supported:
        raise ValueError(f"Unsupported detector output contract: {output_shapes}")
    return {
        "input_shape": inputs[0]["shape"].tolist(),
        "input_dtype": inputs[0]["dtype"].__name__,
        "input_quantization": list(inputs[0]["quantization"]),
        "output_shapes": [item["shape"].tolist() for item in outputs],
        "output_dtypes": [item["dtype"].__name__ for item in outputs],
        "output_quantization": [list(item["quantization"]) for item in outputs],
        "layout": "end_to_end_xyxy_score_class" if output_shapes[0][2] == 6 else "raw_channels_first",
    }


def resolve_export(result: Any) -> Path:
    path = Path(str(result)).resolve()
    if path.is_file() and path.suffix == ".tflite":
        return path
    search_root = path if path.is_dir() else path.parent
    candidates = sorted(search_root.rglob("*.tflite"), key=lambda item: ("int8" not in item.name.lower(), item.name))
    if not candidates:
        raise FileNotFoundError(f"Ultralytics did not produce a .tflite file under {search_root}")
    return candidates[0]


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--checkpoint", type=Path, required=True)
    parser.add_argument("--dataset", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--recipe", type=Path, default=Path(__file__).with_name("recipe.json"))
    args = parser.parse_args()

    from ultralytics import YOLO

    recipe = load_recipe(args.recipe)
    dataset_yaml = args.dataset / "dataset.yaml"
    model = YOLO(str(args.checkpoint.resolve()))
    exported = model.export(
        format="litert",
        quantize=8,
        data=str(dataset_yaml.resolve()),
        fraction=1.0,
        imgsz=int(recipe["model"]["input_size"]),
        batch=1,
        end2end=True,
        max_det=100,
        device="cpu",
    )
    source = resolve_export(exported)
    args.output.mkdir(parents=True, exist_ok=True)
    destination = args.output / "halalify_visual_v4_p2_int8.tflite"
    shutil.copy2(source, destination)
    contract = inspect_contract(destination)
    size_bytes = destination.stat().st_size
    max_size = int(recipe["release_gates"]["max_tflite_size_bytes"])
    if size_bytes > max_size:
        raise ValueError(f"Export is too large for release: {size_bytes:,} > {max_size:,} bytes")
    manifest = {
        "schema_version": 1,
        "model_id": "halalify-visual-v4-yolo26n-p2-int8-candidate",
        "version": "4.0.0-candidate",
        "model_file": destination.name,
        "size_bytes": size_bytes,
        "sha256": sha256(destination),
        "classes": [
            {"id": index, "label": name, "runtime_label": ("female", "male", "ignored")[index]}
            for index, name in enumerate(CLASS_NAMES)
        ],
        "contract": contract,
        "status": "candidate_not_promoted",
        "promotion_requires": "host benchmark, physical-phone latency/thermal run, and release_gate.py PASS",
    }
    manifest_path = args.output / "model_manifest.candidate.json"
    manifest_path.write_text(
        json.dumps(manifest, indent=2, ensure_ascii=False, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    print(json.dumps(manifest, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()
