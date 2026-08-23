from __future__ import annotations

"""Calibrate per-class thresholds on the held-out YOLO validation split."""

import argparse
import hashlib
import json
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from PIL import Image

from bancmark.src.model_adapter import Detection, TFLiteDetector
from training.vision.common import CLASS_NAMES, load_recipe


@dataclass(frozen=True)
class GroundTruth:
    class_id: int
    box: tuple[float, float, float, float]


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest().upper()


def iou(left: tuple[float, float, float, float], right: tuple[float, float, float, float]) -> float:
    x1 = max(left[0], right[0])
    y1 = max(left[1], right[1])
    x2 = min(left[2], right[2])
    y2 = min(left[3], right[3])
    intersection = max(0.0, x2 - x1) * max(0.0, y2 - y1)
    left_area = max(0.0, left[2] - left[0]) * max(0.0, left[3] - left[1])
    right_area = max(0.0, right[2] - right[0]) * max(0.0, right[3] - right[1])
    union = left_area + right_area - intersection
    return intersection / union if union > 0.0 else 0.0


def read_labels(path: Path) -> list[GroundTruth]:
    labels: list[GroundTruth] = []
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        class_value, cx, cy, width, height = (float(value) for value in line.split())
        class_id = int(class_value)
        labels.append(
            GroundTruth(
                class_id,
                (
                    max(0.0, cx - width / 2),
                    max(0.0, cy - height / 2),
                    min(1.0, cx + width / 2),
                    min(1.0, cy + height / 2),
                ),
            )
        )
    return labels


def match_count(
    predictions: list[Detection],
    truth: list[GroundTruth],
    class_id: int,
    threshold: float,
) -> tuple[int, int]:
    candidates = sorted(
        (item for item in predictions if item.class_id == class_id and item.confidence >= threshold),
        key=lambda item: item.confidence,
        reverse=True,
    )
    targets = [item for item in truth if item.class_id == class_id]
    unmatched = set(range(len(targets)))
    matched = 0
    for prediction in candidates:
        best_index = None
        best_iou = 0.0
        for index in unmatched:
            overlap = iou(prediction.box, targets[index].box)
            if overlap > best_iou:
                best_iou = overlap
                best_index = index
        if best_index is not None and best_iou >= 0.5:
            unmatched.remove(best_index)
            matched += 1
    return matched, len(candidates)


def score_threshold(
    samples: list[tuple[list[GroundTruth], list[Detection]]],
    class_id: int,
    threshold: float,
) -> dict[str, float | int]:
    truth_instances = 0
    matched_instances = 0
    predicted_instances = 0
    negative_images = 0
    false_blur_images = 0
    for truth, predictions in samples:
        target_truth = sum(item.class_id == class_id for item in truth)
        matched, predicted = match_count(predictions, truth, class_id, threshold)
        truth_instances += target_truth
        matched_instances += matched
        predicted_instances += predicted
        if target_truth == 0:
            negative_images += 1
            if predicted > 0:
                false_blur_images += 1
    recall = matched_instances / truth_instances if truth_instances else 0.0
    precision = matched_instances / predicted_instances if predicted_instances else 0.0
    false_blur_rate = false_blur_images / negative_images if negative_images else 0.0
    beta_squared = 4.0
    f2 = (
        (1.0 + beta_squared) * precision * recall / (beta_squared * precision + recall)
        if precision + recall > 0.0
        else 0.0
    )
    return {
        "threshold": round(threshold, 4),
        "recall": recall,
        "precision": precision,
        "f2": f2,
        "false_blur_rate": false_blur_rate,
        "truth_instances": truth_instances,
        "matched_instances": matched_instances,
        "predicted_instances": predicted_instances,
        "negative_images": negative_images,
        "false_blur_images": false_blur_images,
    }


def choose_threshold(
    samples: list[tuple[list[GroundTruth], list[Detection]]],
    class_id: int,
    minimum_recall: float,
    maximum_false_blur_rate: float,
) -> dict[str, Any]:
    candidates = [
        score_threshold(samples, class_id, value / 100.0)
        for value in range(5, 91)
    ]
    feasible = [
        item
        for item in candidates
        if item["recall"] >= minimum_recall
        and item["false_blur_rate"] <= maximum_false_blur_rate
    ]
    pool = feasible or candidates
    selected = max(
        pool,
        key=lambda item: (item["f2"], item["recall"], -item["false_blur_rate"], item["threshold"]),
    )
    return {
        **selected,
        "status": "PASS" if feasible else "FAIL",
        "required_minimum_recall": minimum_recall,
        "required_maximum_false_blur_rate": maximum_false_blur_rate,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model", type=Path, required=True)
    parser.add_argument("--dataset", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--threads", type=int, default=2)
    parser.add_argument("--max-images", type=int)
    parser.add_argument("--recipe", type=Path, default=Path(__file__).with_name("recipe.json"))
    args = parser.parse_args()

    recipe = load_recipe(args.recipe)
    gates = recipe["release_gates"]
    image_root = args.dataset / "images" / "val"
    label_root = args.dataset / "labels" / "val"
    images = sorted(image_root.glob("*.jpg"))
    if args.max_images is not None:
        images = images[: args.max_images]
    if not images:
        raise ValueError(f"No validation images found under {image_root}")

    detector = TFLiteDetector(args.model, threads=args.threads)
    samples: list[tuple[list[GroundTruth], list[Detection]]] = []
    for index, image_path in enumerate(images, start=1):
        label_path = label_root / f"{image_path.stem}.txt"
        if not label_path.is_file():
            raise FileNotFoundError(label_path)
        truth = read_labels(label_path)
        with Image.open(image_path) as image:
            predictions, _ = detector.run_mobile_sweep(
                image,
                confidence_threshold=0.01,
                iou_threshold=0.5,
                max_detections=300,
            )
        samples.append((truth, predictions))
        if index % 1000 == 0:
            print(f"Calibrated inference {index:,}/{len(images):,}", flush=True)

    classes = {
        CLASS_NAMES[class_id]: choose_threshold(
            samples,
            class_id,
            float(gates[f"min_{('female', 'male')[class_id]}_recall"]),
            float(gates["max_false_blur_rate"]),
        )
        for class_id in (0, 1)
    }
    failures = [name for name, result in classes.items() if result["status"] != "PASS"]
    report = {
        "schema_version": 1,
        "status": "PASS" if not failures else "FAIL",
        "model": str(args.model.resolve()),
        "model_sha256": sha256(args.model),
        "validation_images": len(samples),
        "classes": classes,
        "runtime_thresholds": {
            "female_confidence_threshold": classes[CLASS_NAMES[0]]["threshold"],
            "male_confidence_threshold": classes[CLASS_NAMES[1]]["threshold"],
        },
        "failures": failures,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(
        json.dumps(report, indent=2, ensure_ascii=False, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    print(json.dumps(report, indent=2, ensure_ascii=False))
    if failures:
        sys.exit(2)


if __name__ == "__main__":
    main()
