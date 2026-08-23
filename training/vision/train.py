from __future__ import annotations

"""Train the mobile P2 detector after all dataset release gates pass."""

import argparse
import json
import platform
from pathlib import Path
from typing import Any

from training.vision.common import load_recipe
from training.vision.prepare_dataset import enforce_targets


def json_safe(value: Any) -> Any:
    if value is None or isinstance(value, (str, int, float, bool)):
        return value
    if isinstance(value, dict):
        return {str(key): json_safe(item) for key, item in value.items()}
    if isinstance(value, (list, tuple)):
        return [json_safe(item) for item in value]
    if hasattr(value, "tolist"):
        return json_safe(value.tolist())
    return str(value)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--recipe", type=Path, default=Path(__file__).with_name("recipe.json"))
    parser.add_argument("--resume", type=Path)
    parser.add_argument("--smoke", action="store_true", help="One epoch on 1% of data")
    args = parser.parse_args()

    import torch
    import ultralytics
    from ultralytics import YOLO

    recipe = load_recipe(args.recipe)
    summary_path = args.dataset / "dataset_summary.json"
    if not summary_path.is_file():
        raise FileNotFoundError(f"Missing dataset preflight summary: {summary_path}")
    dataset_summary = json.loads(summary_path.read_text(encoding="utf-8"))
    if not args.smoke:
        enforce_targets(dataset_summary, recipe)
    data_yaml = args.dataset / "dataset.yaml"
    if not data_yaml.is_file():
        raise FileNotFoundError(data_yaml)

    training = dict(recipe["training"])
    requested_device = training["device"]
    if requested_device != "cpu" and not torch.cuda.is_available():
        raise RuntimeError("CUDA training was requested, but PyTorch cannot access the GPU")
    args.output.mkdir(parents=True, exist_ok=True)
    if args.resume:
        model = YOLO(str(args.resume.resolve()))
        results = model.train(resume=True)
    else:
        architecture = str(recipe["model"]["architecture"])
        pretrained = str(recipe["model"]["pretrained"])
        model = YOLO(architecture)
        model.load(pretrained)
        train_args = {
            **training,
            "data": str(data_yaml.resolve()),
            "imgsz": int(recipe["model"]["input_size"]),
            "seed": int(recipe["seed"]),
            "project": str(args.output.resolve()),
            "name": "halalify_visual_v4_p2",
            "exist_ok": True,
            "plots": True,
        }
        if args.smoke:
            train_args.update({"epochs": 1, "fraction": 0.01, "patience": 1, "save_period": -1})
        results = model.train(**train_args)

    run_dir = Path(results.save_dir)
    best = run_dir / "weights" / "best.pt"
    last = run_dir / "weights" / "last.pt"
    report = {
        "schema_version": 1,
        "recipe": str(args.recipe.resolve()),
        "dataset": str(args.dataset.resolve()),
        "dataset_summary": dataset_summary,
        "environment": {
            "python": platform.python_version(),
            "platform": platform.platform(),
            "torch": torch.__version__,
            "ultralytics": ultralytics.__version__,
            "cuda_available": torch.cuda.is_available(),
            "cuda_device": torch.cuda.get_device_name(0) if torch.cuda.is_available() else None,
        },
        "run_dir": str(run_dir.resolve()),
        "best_checkpoint": str(best.resolve()) if best.exists() else None,
        "last_checkpoint": str(last.resolve()) if last.exists() else None,
        "metrics": json_safe(getattr(results, "results_dict", {})),
    }
    (run_dir / "halalify_training_report.json").write_text(
        json.dumps(report, indent=2, ensure_ascii=False, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    print(json.dumps(report, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()
