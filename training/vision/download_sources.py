from __future__ import annotations

"""Download large, independently sourced training data with provenance.

The Bancmark directory is deliberately never read as training input. Exact
benchmark/source leakage is rejected later by ``prepare_dataset.py``.
"""

import argparse
import csv
import heapq
import io
import json
import sys
import time
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path
from typing import Any, Iterable

from PIL import Image

from training.vision.common import (
    Box,
    read_jsonl,
    sha256_file,
    stable_split,
    stable_u64,
    write_jsonl,
)


OPEN_IMAGES_BOXES_URL = (
    "https://storage.googleapis.com/openimages/v6/oidv6-train-annotations-bbox.csv"
)
OPEN_IMAGES_CLASSES_URL = (
    "https://storage.googleapis.com/openimages/v7/oidv7-class-descriptions-boxable.csv"
)
OPEN_IMAGES_BUCKET = "open-images-dataset"
SPECIFIC_PEOPLE = {"Woman": 0, "Girl": 0, "Man": 1, "Boy": 1}
GENERIC_PEOPLE = {
    "Person",
    "Human face",
    "Human body",
    "Human head",
}
HARD_NEGATIVE_CLASSES = {
    "Animal",
    "Bird",
    "Car",
    "Cat",
    "Dog",
    "Drink",
    "Fast food",
    "Food",
    "Furniture",
    "Houseplant",
    "Kitchen appliance",
    "Mannequin",
    "Sculpture",
    "Statue",
    "Toy",
    "Vehicle",
}


class BoundedHashSample:
    """Keep the N lowest deterministic hashes without loading image pixels."""

    def __init__(self, limit: int, seed: int) -> None:
        self.limit = max(0, limit)
        self.seed = seed
        self._heap: list[tuple[int, str, dict[str, Any]]] = []

    def add(self, source_id: str, record: dict[str, Any]) -> None:
        if self.limit == 0:
            return
        score = stable_u64(source_id, self.seed)
        entry = (-score, source_id, record)
        if len(self._heap) < self.limit:
            heapq.heappush(self._heap, entry)
        elif score < -self._heap[0][0]:
            heapq.heapreplace(self._heap, entry)

    def records(self) -> list[dict[str, Any]]:
        return [item[2] for item in sorted(self._heap, key=lambda item: (-item[0], item[1]))]


def _url_text(url: str):
    request = urllib.request.Request(url, headers={"User-Agent": "Halalify-training/1"})
    response = urllib.request.urlopen(request, timeout=120)
    return response, io.TextIOWrapper(response, encoding="utf-8", newline="")


def load_openimages_classes() -> dict[str, str]:
    response, stream = _url_text(OPEN_IMAGES_CLASSES_URL)
    try:
        return {row[0]: row[1] for row in csv.reader(stream) if len(row) >= 2}
    finally:
        stream.detach()
        response.close()


def _normalized_box(row: dict[str, str], class_id: int) -> Box:
    return Box(
        class_id,
        float(row["XMin"]),
        float(row["YMin"]),
        float(row["XMax"]),
        float(row["YMax"]),
    ).validate()


def _drop_generic_duplicates(specific: list[Box], generic: list[Box]) -> list[Box]:
    from training.vision.common import intersection_over_union

    return specific + [
        candidate
        for candidate in generic
        if all(intersection_over_union(candidate, known) < 0.65 for known in specific)
    ]


def select_openimages(
    positive_limit: int,
    negative_limit: int,
    seed: int,
) -> list[dict[str, Any]]:
    class_names = load_openimages_classes()
    # Selection and train/validation assignment use independent hash streams.
    # Sharing the split seed biases a lowest-hash sample into validation.
    positives = BoundedHashSample(positive_limit, seed + 201)
    negatives = BoundedHashSample(negative_limit, seed + 202)
    response, stream = _url_text(OPEN_IMAGES_BOXES_URL)
    current_id: str | None = None
    current_rows: list[dict[str, str]] = []
    rows_seen = 0

    def consume(image_id: str, rows: list[dict[str, str]]) -> None:
        names = [class_names.get(row["LabelName"], "") for row in rows]
        specific = [
            _normalized_box(row, SPECIFIC_PEOPLE[name])
            for row, name in zip(rows, names)
            if name in SPECIFIC_PEOPLE
        ]
        generic = [
            _normalized_box(row, 2)
            for row, name in zip(rows, names)
            if name in GENERIC_PEOPLE
        ]
        source_id = f"openimages:v7:train:{image_id}"
        if specific:
            boxes = _drop_generic_duplicates(specific, generic)
            positives.add(
                source_id,
                {
                    "source": "openimages_v7",
                    "source_url": "https://storage.googleapis.com/openimages/web/index.html",
                    "annotation_license": "CC BY 4.0",
                    "image_license": "listed by Open Images as CC BY 2.0; verify attribution",
                    "source_id": source_id,
                    "image_id": image_id,
                    "boxes": [box.as_dict() for box in boxes],
                },
            )
            return
        if not generic and any(name in HARD_NEGATIVE_CLASSES for name in names):
            negatives.add(
                source_id,
                {
                    "source": "openimages_v7_hard_negative",
                    "source_url": "https://storage.googleapis.com/openimages/web/index.html",
                    "annotation_license": "CC BY 4.0",
                    "image_license": "listed by Open Images as CC BY 2.0; verify attribution",
                    "source_id": source_id,
                    "image_id": image_id,
                    "boxes": [],
                },
            )

    try:
        reader = csv.DictReader(stream)
        for row in reader:
            rows_seen += 1
            image_id = row["ImageID"]
            if current_id is None:
                current_id = image_id
            if image_id != current_id:
                consume(current_id, current_rows)
                current_id = image_id
                current_rows = []
            current_rows.append(row)
            if rows_seen % 1_000_000 == 0:
                print(f"Scanned {rows_seen:,} Open Images boxes", flush=True)
        if current_id is not None:
            consume(current_id, current_rows)
    finally:
        stream.detach()
        response.close()
    selected = positives.records() + negatives.records()
    print(
        f"Selected {len(positives.records()):,} people scenes and "
        f"{len(negatives.records()):,} hard negatives from {rows_seen:,} boxes"
    )
    return selected


def _download_openimages_one(
    client: Any,
    record: dict[str, Any],
    image_dir: Path,
    max_dimension: int,
) -> dict[str, Any]:
    image_id = record["image_id"]
    destination = image_dir / f"{image_id}.jpg"
    valid_existing = False
    if destination.exists():
        try:
            with Image.open(destination) as existing:
                existing.verify()
            valid_existing = True
        except (OSError, ValueError):
            valid_existing = False
    if not valid_existing:
        last_error: Exception | None = None
        for attempt in range(8):
            try:
                body = client.get_object(
                    Bucket=OPEN_IMAGES_BUCKET,
                    Key=f"train/{image_id}.jpg",
                )["Body"]
                try:
                    payload = body.read()
                finally:
                    body.close()
                with Image.open(io.BytesIO(payload)) as image:
                    image = image.convert("RGB")
                    image.thumbnail((max_dimension, max_dimension), Image.Resampling.LANCZOS)
                    destination.parent.mkdir(parents=True, exist_ok=True)
                    temporary = destination.with_suffix(".jpg.download")
                    image.save(temporary, "JPEG", quality=82, optimize=True)
                    temporary.replace(destination)
                last_error = None
                break
            except Exception as error:
                last_error = error
                if attempt < 7:
                    time.sleep(min(0.25 * (2**attempt), 4.0))
        if last_error is not None:
            raise RuntimeError(f"Could not download Open Images {image_id}") from last_error
    with Image.open(destination) as image:
        width, height = image.size
    return {
        **record,
        "image": str(destination.resolve()),
        "width": width,
        "height": height,
        "sha256": sha256_file(destination),
    }


def download_openimages(
    output: Path,
    positive_limit: int,
    negative_limit: int,
    seed: int,
    validation_fraction: float,
    workers: int,
    max_dimension: int,
) -> list[dict[str, Any]]:
    import boto3
    import botocore

    records_path = output / "openimages_records.v2.jsonl"
    if records_path.exists():
        cached = list(read_jsonl(records_path))
        expected = positive_limit + negative_limit
        if len(cached) == expected and all(Path(item["image"]).is_file() for item in cached):
            print(f"Reusing {len(cached):,} completed Open Images records")
            return cached
    selected_path = output / "openimages_selected.v2.json"
    if selected_path.exists():
        selected = json.loads(selected_path.read_text(encoding="utf-8"))
    else:
        selected = select_openimages(positive_limit, negative_limit, seed)
        selected_path.parent.mkdir(parents=True, exist_ok=True)
        selected_path.write_text(
            json.dumps(selected, ensure_ascii=False, sort_keys=True),
            encoding="utf-8",
        )
    client = boto3.client(
        "s3",
        config=botocore.config.Config(
            signature_version=botocore.UNSIGNED,
            max_pool_connections=max(16, workers),
            retries={"max_attempts": 8, "mode": "adaptive"},
        ),
    )
    image_dir = output / "openimages" / "images"
    records: list[dict[str, Any]] = []
    with ThreadPoolExecutor(max_workers=max(1, workers)) as executor:
        futures = [
            executor.submit(
                _download_openimages_one,
                client,
                record,
                image_dir,
                max_dimension,
            )
            for record in selected
        ]
        for completed, future in enumerate(as_completed(futures), 1):
            record = future.result()
            record["split"] = stable_split(record["source_id"], validation_fraction, seed)
            records.append(record)
            if completed % 1000 == 0:
                print(f"Downloaded {completed:,}/{len(futures):,} Open Images files", flush=True)
    records.sort(key=lambda item: item["source_id"])
    write_jsonl(records_path, records)
    return records


def download_fairface(
    output: Path,
    limit: int,
    seed: int,
    validation_fraction: float,
) -> list[dict[str, Any]]:
    from datasets import load_dataset

    records_path = output / "fairface_records.jsonl"
    if records_path.exists():
        cached = list(read_jsonl(records_path))
        if len(cached) == limit and all(Path(item["image"]).is_file() for item in cached):
            print(f"Reusing {len(cached):,} completed FairFace records")
            return cached
    dataset = load_dataset(
        "HuggingFaceM4/FairFace",
        "0.25",
        split="train",
        keep_in_memory=False,
    )
    selected_indices = sorted(
        range(len(dataset)),
        # Sampling and train/validation assignment must use independent hash
        # streams. Reusing the split seed makes a small lowest-hash smoke
        # sample collapse entirely into validation.
        key=lambda index: stable_u64(f"fairface:0.25:train:{index}", seed + 101),
    )[: min(limit, len(dataset))]
    image_dir = output / "fairface" / "images"
    image_dir.mkdir(parents=True, exist_ok=True)
    metadata = dataset.select_columns(["gender"])
    records: list[dict[str, Any]] = []
    for completed, row_index in enumerate(selected_indices, 1):
        source_id = f"fairface:0.25:train:{row_index}"
        destination = image_dir / f"{row_index:06d}.jpg"
        if not destination.exists():
            row = dataset[row_index]
            row["image"].convert("RGB").save(destination, "JPEG", quality=95, optimize=True)
        with Image.open(destination) as image:
            width, height = image.size
        gender = int(metadata[row_index]["gender"])
        class_id = 1 if gender == 0 else 0
        records.append(
            {
                "source": "fairface_0.25",
                "source_url": "https://huggingface.co/datasets/HuggingFaceM4/FairFace",
                "license": "CC BY 4.0",
                "source_id": source_id,
                "row_index": row_index,
                "image": str(destination.resolve()),
                "width": width,
                "height": height,
                "sha256": sha256_file(destination),
                "split": stable_split(source_id, validation_fraction, seed),
                "boxes": [Box(class_id, 0.02, 0.02, 0.98, 0.98).as_dict()],
            }
        )
        if completed % 2000 == 0:
            print(f"Prepared {completed:,}/{len(selected_indices):,} FairFace files", flush=True)
    records.sort(key=lambda item: item["source_id"])
    write_jsonl(records_path, records)
    return records


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--fairface-limit", type=int, default=80000)
    parser.add_argument("--openimages-people", type=int, default=45000)
    parser.add_argument("--openimages-negatives", type=int, default=15000)
    parser.add_argument("--openimages-max-dimension", type=int, default=640)
    parser.add_argument("--validation-fraction", type=float, default=0.06)
    parser.add_argument("--seed", type=int, default=20260823)
    parser.add_argument("--workers", type=int, default=8)
    parser.add_argument("--skip-fairface", action="store_true")
    parser.add_argument("--skip-openimages", action="store_true")
    args = parser.parse_args()
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    args.output.mkdir(parents=True, exist_ok=True)
    records: list[dict[str, Any]] = []
    if not args.skip_fairface:
        records.extend(
            download_fairface(
                args.output,
                args.fairface_limit,
                args.seed,
                args.validation_fraction,
            )
        )
    if not args.skip_openimages:
        records.extend(
            download_openimages(
                args.output,
                args.openimages_people,
                args.openimages_negatives,
                args.seed,
                args.validation_fraction,
                args.workers,
                args.openimages_max_dimension,
            )
        )
    records.sort(key=lambda item: item["source_id"])
    manifest = args.output / "sources.jsonl"
    write_jsonl(manifest, records)
    print(f"Wrote {manifest} with {len(records):,} independent source images")


if __name__ == "__main__":
    main()
