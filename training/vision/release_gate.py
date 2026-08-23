from __future__ import annotations

"""Fail closed unless the quantized candidate is accurate and fast on a phone."""

import argparse
import json
import sys
from pathlib import Path
from typing import Any

from training.vision.common import load_recipe


def select_model(report: dict[str, Any], model_id: str | None) -> dict[str, Any]:
    models = report.get("models")
    if not isinstance(models, dict) or not models:
        raise ValueError("Benchmark report has no models object")
    if model_id:
        if model_id not in models:
            raise KeyError(f"Model {model_id!r} is absent from the benchmark report")
        return models[model_id]
    if len(models) != 1:
        raise ValueError("Use --model-id when the report contains multiple models")
    return next(iter(models.values()))


def check_at_least(
    failures: list[str],
    name: str,
    actual: float | None,
    required: float,
) -> None:
    if actual is None or actual < required:
        failures.append(f"{name}={actual} is below {required}")


def check_at_most(
    failures: list[str],
    name: str,
    actual: float | None,
    required: float,
) -> None:
    if actual is None or actual > required:
        failures.append(f"{name}={actual} is above {required}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--benchmark-report", type=Path, required=True)
    parser.add_argument("--candidate-manifest", type=Path, required=True)
    parser.add_argument("--calibration-report", type=Path, required=True)
    parser.add_argument("--phone-p50-ms", type=float, required=True)
    parser.add_argument("--phone-p95-ms", type=float, required=True)
    parser.add_argument("--float-map50-95", type=float, required=True)
    parser.add_argument("--model-id")
    parser.add_argument("--recipe", type=Path, default=Path(__file__).with_name("recipe.json"))
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()

    recipe = load_recipe(args.recipe)
    gates = recipe["release_gates"]
    report = json.loads(args.benchmark_report.read_text(encoding="utf-8"))
    candidate = json.loads(args.candidate_manifest.read_text(encoding="utf-8"))
    calibration = json.loads(args.calibration_report.read_text(encoding="utf-8"))
    model = select_model(report, args.model_id)
    metrics = model["metrics"]
    readiness = metrics["production_readiness"]
    ap = metrics["coco_style_ap"]
    quantized_map = ap.get("AP_all_50_95")
    retention = (
        float(quantized_map) / args.float_map50_95
        if quantized_map is not None and args.float_map50_95 > 0
        else None
    )
    failures: list[str] = []
    candidate_sha256 = str(candidate.get("sha256") or "").upper()
    benchmark_sha256 = str(model.get("sha256") or "").upper()
    calibration_sha256 = str(calibration.get("model_sha256") or "").upper()
    if not candidate_sha256 or benchmark_sha256 != candidate_sha256:
        failures.append("benchmark report SHA-256 does not match the candidate")
    if calibration_sha256 != candidate_sha256:
        failures.append("calibration report SHA-256 does not match the candidate")
    if calibration.get("status") != "PASS":
        failures.append("per-class threshold calibration did not pass")
    check_at_most(failures, "tflite_size_bytes", candidate.get("size_bytes"), gates["max_tflite_size_bytes"])
    check_at_most(failures, "phone_p50_ms", args.phone_p50_ms, gates["max_phone_p50_ms"])
    check_at_most(failures, "phone_p95_ms", args.phone_p95_ms, gates["max_phone_p95_ms"])
    check_at_least(failures, "female_recall", readiness.get("female_recall"), gates["min_female_recall"])
    check_at_least(failures, "male_recall", readiness.get("male_recall"), gates["min_male_recall"])
    check_at_least(failures, "avatar_recall", readiness.get("avatar_recall"), gates["min_avatar_recall"])
    check_at_least(failures, "profile_recall", readiness.get("profile_recall"), gates["min_profile_recall"])
    check_at_least(failures, "AP_all_50", ap.get("AP_all_50"), gates["min_ap50"])
    check_at_most(failures, "false_blur_rate", readiness.get("false_blur_rate"), gates["max_false_blur_rate"])
    check_at_least(failures, "quantized_map_retention", retention, gates["min_quantized_map_retention"])
    result = {
        "schema_version": 1,
        "status": "PASS" if not failures else "FAIL",
        "candidate_sha256": candidate_sha256,
        "runtime_thresholds": calibration.get("runtime_thresholds"),
        "measured": {
            "phone_p50_ms": args.phone_p50_ms,
            "phone_p95_ms": args.phone_p95_ms,
            "female_recall": readiness.get("female_recall"),
            "male_recall": readiness.get("male_recall"),
            "avatar_recall": readiness.get("avatar_recall"),
            "profile_recall": readiness.get("profile_recall"),
            "AP_all_50": ap.get("AP_all_50"),
            "AP_all_50_95": quantized_map,
            "false_blur_rate": readiness.get("false_blur_rate"),
            "quantized_map_retention": retention,
        },
        "required": gates,
        "failures": failures,
    }
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(
            json.dumps(result, indent=2, ensure_ascii=False, sort_keys=True) + "\n",
            encoding="utf-8",
        )
    print(json.dumps(result, indent=2, ensure_ascii=False))
    if failures:
        sys.exit(2)


if __name__ == "__main__":
    main()
